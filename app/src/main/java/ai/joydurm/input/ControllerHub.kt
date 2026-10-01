package ai.joydurm.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.InputDevice
import ai.joydurm.core.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class ControllerInfo(val id: String, val name: String, val transport: String, val motion: Boolean,
    val identityStable: Boolean = false, val sessionId: String? = null)
data class DeviceDiagnostics(val id: String, val name: String, val transport: String, val motion: Boolean,
    val lastSampleTimeNs: Long?, val sampleCount: Long, val lastError: String?)
data class ConnectionDiagnostics(val running: Boolean, val bridgeRunning: Boolean, val bridgePort: Int?,
    val acceptedPackets: Long, val rejectedPackets: Long, val devices: List<DeviceDiagnostics>, val lanAddresses: List<String>,
    val bridgeStatistics: BridgeStatistics? = null)

/** Controller data only. All mutable transport state has one dedicated input-thread owner. */
class ControllerHub(private val context: Context, private val sample: (ImuSample)->Unit, private val status: (String)->Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val lifecycleLock = Any()
    @Volatile private var inputThread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private data class SensorBinding(val id: String, val manager: SensorManager, val listener: SensorEventListener)
    private val listeners = mutableMapOf<Int,SensorBinding>()
    private val androidIds = mutableMapOf<Int,String>()
    val devices = ConcurrentHashMap<String,ControllerInfo>()
    private val health = mutableMapOf<String,InputHealth>()
    private val raw = mutableMapOf<String,RawHidSource>()
    private val probe = CapabilityProbe()
    private var udp: DatagramSocket? = null
    private var decoder: BridgePacketDecoder? = null
    private val endpoints = java.util.LinkedHashMap<String,Pair<java.net.InetAddress,Int>>(32,0.75f,true)
    private val pendingPackets = AtomicInteger()
    private val queueDrops = AtomicLong()
    @Volatile private var running = false
    var onDeviceLost: ((String)->Unit)? = null
    var onRemoteRole: ((Role,String)->Unit)? = null
    var onFrame: ((ImuFrame)->Unit)? = null
    private val watchdog = object: Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            decoder?.poll(now)?.let(::handleBridge)
            expire(now)
            handler?.postDelayed(this,10)
        }
    }

    fun start() {
        val h = synchronized(lifecycleLock) {
            handler ?: HandlerThread("JoyDurm-Input").also { it.start(); inputThread = it }.let { Handler(it.looper).also { handler = it } }
        }
        onInput(h) {
            if (!running) {
                running = true; input.registerInputDeviceListener(this,h)
                refreshInternal(); h.postDelayed(watchdog,10)
            }
        }
    }

    fun stop() {
        val h = handler ?: return
        onInput(h) {
            if (running) {
                running = false; h.removeCallbacks(watchdog); input.unregisterInputDeviceListener(this)
                listeners.values.forEach { it.manager.unregisterListener(it.listener) }; listeners.clear(); androidIds.clear()
                closeBridgeInternal()
                val sources = raw.values.toList(); raw.clear(); sources.forEach { it.close() }
                devices.keys.toList().forEach { lose(it,"监听已停止",remove=true) }
            }
        }
        // Never wait for a reader or engine callback while holding the lifecycle lock.
        synchronized(lifecycleLock) {
            if (handler === h) { handler = null; inputThread?.quitSafely(); inputThread = null }
        }
    }

    @SuppressLint("MissingPermission") fun bondedNames(): List<String> = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { "${it.name ?: "Bluetooth"} · ${it.address}" } ?: emptyList()
    } catch (_: SecurityException) { listOf("请授予附近设备权限") }

    fun capabilityReport(): String = handler?.let { onInput(it) { probe.report(decoder?.statistics(SystemClock.elapsedRealtimeNanos())) } } ?: probe.report()
    fun diagnostics(): ConnectionDiagnostics {
        val addresses = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct() }.getOrDefault(emptyList())
        val h = handler ?: return ConnectionDiagnostics(false,false,null,0,0,emptyList(),addresses)
        return onInput(h) {
            val now = SystemClock.elapsedRealtimeNanos(); expire(now)
            val statistics = decoder?.statistics(now)
            ConnectionDiagnostics(running,udp?.isClosed==false,udp?.takeUnless { it.isClosed }?.localPort,
                statistics?.acceptedPackets ?: 0,(statistics?.rejectedPackets ?: 0) + queueDrops.get(),
                devices.values.sortedBy { it.id }.map { d ->
                    val health = health[d.id]
                    DeviceDiagnostics(d.id,d.name,d.transport,health?.fresh(now)==true,health?.lastSampleTimeNs,health?.sampleCount ?: 0,health?.lastError)
                },addresses,statistics)
        }
    }

    private fun lose(id: String, reason: String, remove: Boolean=false) {
        val known = devices.containsKey(id)
        health.getOrPut(id) { InputHealth() }.disconnect(reason)
        if (remove) { devices.remove(id); health.remove(id) } else devices[id]?.let { devices[id] = it.copy(motion=false) }
        if (known) onDeviceLost?.invoke(id)
    }
    private fun expire(now: Long) {
        health.forEach { (id,h) -> if (h.expire(now)) {
            devices[id]?.let { devices[id] = it.copy(motion=false) }; onDeviceLost?.invoke(id)
        } }
    }
    private fun deliver(frame: ImuFrame): Boolean {
        val s = frame.sample; val now = SystemClock.elapsedRealtimeNanos()
        if (now - s.timeNs !in 0..BridgePacketDecoder.STALE_NS) return false
        val h = health.getOrPut(s.device) { InputHealth() }
        if (!h.accept(s,now)) return false
        devices[s.device]?.let { devices[s.device] = it.copy(motion=true) }
        probe.record(frame)
        // There is no hub lock held here. The engine callback enqueues and returns.
        onFrame?.invoke(frame); sample(s); return true
    }

    fun refresh() { handler?.let { onInput(it) { refreshInternal() } } }
    private fun refreshInternal() {
        if (!running) return
        InputDevice.getDeviceIds().asIterable().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.vendorId==0x057e || it.name.contains("Joy-Con",true) }.forEach(::attach)
    }
    private fun attach(device: InputDevice) {
        if (!running || androidIds.containsKey(device.id)) return
        val session = SessionId(UUID.randomUUID().toString())
        val physical = "android:${device.descriptor}"
        val id = "$physical/session:${session.value}"; androidIds[device.id] = id
        health[id] = InputHealth(SystemClock.elapsedRealtimeNanos())
        devices[id] = ControllerInfo(id,device.name,"Android 手柄传感器",false,false,session.value)
        probe.androidDevice(id,session.value,device)
        if (Build.VERSION.SDK_INT < 31) { lose(id,"Android <12 无控制器传感器 API"); return }
        try {
            val sm = device.sensorManager
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (acc==null || gyro==null) { lose(id,"系统未开放控制器 IMU"); return }
            var acceleration = Vec3(); var accelerationTime = 0L
            val listener = object: SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
                override fun onSensorChanged(e: SensorEvent) {
                    if (!running || listeners[device.id]?.listener !== this || e.values.size < 3) return
                    val v = Vec3(e.values[0].toDouble(),e.values[1].toDouble(),e.values[2].toDouble())
                    if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) { acceleration = v; accelerationTime = e.timestamp }
                    else if (e.sensor.type == Sensor.TYPE_GYROSCOPE && accelerationTime > 0 && e.timestamp >= accelerationTime && e.timestamp - accelerationTime <= 50_000_000L) {
                        val s = ImuSample(id,e.timestamp,acceleration,v)
                        deliver(ImuFrame(ControllerIdentity(physical,"android",false,bindingId=id),session,e.timestamp,SystemClock.elapsedRealtimeNanos(),0,s))
                    }
                }
            }
            listeners[device.id] = SensorBinding(id,sm,listener)
            val a = sm.registerListener(listener,acc,5_000,handler)
            val g = sm.registerListener(listener,gyro,5_000,handler)
            if (!a || !g) { sm.unregisterListener(listener); listeners.remove(device.id); lose(id,"传感器注册失败") }
        } catch (e: Exception) {
            listeners.remove(device.id)?.let { it.manager.unregisterListener(it.listener) }
            lose(id,"传感器初始化失败：${e.javaClass.simpleName}")
        }
    }

    fun openHid(path: String) {
        val h = handler ?: error("请先启动设备监听")
        onInput(h) {
            require(running) { "请先启动设备监听" }
            require(Regex("/dev/hidraw[0-9]{1,3}").matches(path)) { "仅允许 /dev/hidrawN" }
            require(raw[path]?.isActive != true) { "此 HID 节点已打开" }
            val session = SessionId(UUID.randomUUID().toString()); val id = "raw:$path/session:${session.value}"
            lateinit var source: RawHidSource
            source = RawHidSource(path,id,session,{ frame -> h.post { if (running && raw[path]===source) deliver(frame) } },status) {
                h.post { if (raw[path]===source) { raw.remove(path); lose(id,"HID 连接已关闭",remove=true) } }
            }
            raw[path] = source; health[id] = InputHealth(SystemClock.elapsedRealtimeNanos())
            devices[id] = ControllerInfo(id,path,"原始 HID（需要设备节点权限）",false,false,session.value)
            probe.rawDevice(id,session.value,path)
            try { source.start() } catch (e: Exception) { raw.remove(path); lose(id,"无法打开设备节点",remove=true); throw e }
        }
    }
    fun stopBridge() = closeBridge()
    fun closeBridge() { handler?.let { onInput(it) { closeBridgeInternal() } } }
    private fun closeBridgeInternal() {
        val previous = udp; udp = null; previous?.close(); decoder = null; endpoints.clear()
        devices.keys.filter { it.startsWith("bridge:") }.forEach { lose(it,"桥接监听已关闭",remove=true) }
    }
    fun listenBridge(port: Int, token: String) {
        require(port in 1024..65535) { "端口应为 1024–65535" }
        listenBridgeInternal(port,token)
    }
    /** Atomically reserves an OS-selected port; callers read bridgePort from diagnostics. */
    fun listenBridgeOnAvailablePort(token: String) = listenBridgeInternal(0,token)
    private fun listenBridgeInternal(port: Int, token: String) {
        val h = handler ?: error("请先启动设备监听")
        onInput(h) {
            require(running) { "请先启动设备监听" }
            require((port == 0 || port in 1024..65535) && token.length>=16) { "端口应为 1024–65535；令牌至少 16 字符" }
            closeBridgeInternal()
            val socket = DatagramSocket(port).apply { soTimeout=500 }; udp = socket
            decoder = BridgePacketDecoder(token); queueDrops.set(0)
            thread(name="JoyDurm-UDP",isDaemon=true) {
                val buffer = ByteArray(8193)
                while (!socket.isClosed) try {
                    val packet = DatagramPacket(buffer,buffer.size); socket.receive(packet)
                    val received = SystemClock.elapsedRealtimeNanos()
                    val bytes = packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
                    if (pendingPackets.incrementAndGet() > 64) { pendingPackets.decrementAndGet(); queueDrops.incrementAndGet(); continue }
                    val posted = h.post {
                        try {
                            if (running && udp===socket) {
                                val endpoint = "${packet.address.hostAddress}:${packet.port}"
                                endpoints[endpoint] = packet.address to packet.port
                                if (endpoints.size > 32) endpoints.remove(endpoints.keys.first())
                                decoder?.receive(bytes,endpoint,received)?.let(::handleBridge)
                            }
                        } finally { pendingPackets.decrementAndGet() }
                    }
                    if (!posted) pendingPackets.decrementAndGet()
                } catch (_: SocketTimeoutException) { /* bounded wait, watchdog owns expiration */ }
                catch (_: Exception) {
                    h.post { if (udp===socket) { closeBridgeInternal(); status("桥接监听已中断") } }; break
                }
            }
            status("桥接监听已开启 · UDP ${socket.localPort} · 协议 v2")
        }
    }
    private fun handleBridge(result: BridgeDecodeResult) {
        result.syncRequests.forEach { request ->
            endpoints[request.endpoint]?.let { (address,port) ->
                runCatching {
                    decoder?.dispatchSync(request,SystemClock::elapsedRealtimeNanos) { bytes ->
                        udp?.send(DatagramPacket(bytes,bytes.size,address,port))
                    }
                }
            }
        }
        result.lostDevices.forEach { lose(it,"桥接会话已变化，请重新归中",remove=true) }
        result.batches.forEach { batch ->
            val d = batch.device
            devices.putIfAbsent(d.id,ControllerInfo(d.id,d.name,"LAN · ${d.endpoint}",false,d.identity.stable,d.session.value))
            probe.bridgeDevice(d)
            batch.roleHint?.let { onRemoteRole?.invoke(it,d.id) }
            batch.frames.forEach(::deliver)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (running) InputDevice.getDevice(deviceId)?.let { if (it.vendorId==0x057e || it.name.contains("Joy-Con",true)) attach(it) }
    }
    override fun onInputDeviceChanged(deviceId: Int) { onInputDeviceRemoved(deviceId); onInputDeviceAdded(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) {
        listeners.remove(deviceId)?.let { it.manager.unregisterListener(it.listener) }
        androidIds.remove(deviceId)?.let { lose(it,"系统手柄已断开",remove=true) }
    }

    private fun <T> onInput(h: Handler, block: ()->T): T {
        if (android.os.Looper.myLooper() === h.looper) return block()
        val completed = CountDownLatch(1); var result: Result<T>? = null
        check(h.post { try { result = runCatching(block) } finally { completed.countDown() } }) { "输入线程已停止" }
        check(completed.await(2,TimeUnit.SECONDS)) { "输入线程响应超时" }
        return result!!.getOrThrow()
    }
}

/** Experimental node access. No privilege escalation. Poll timeout makes readers stoppable. */
private class RawHidSource(private val path: String, private val id: String, private val session: SessionId,
    private val callback: (ImuFrame)->Unit, private val status: (String)->Unit, private val removed: ()->Unit) {
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    @Volatile private var active = false
    val isActive get() = active
    private val ack = RawHidAck()
    private var timerExecutor: java.util.concurrent.ScheduledExecutorService? = null
    fun start() {
        try { input=FileInputStream(File(path)); output=FileOutputStream(File(path)); active=true }
        catch (e: Exception) { close(); throw IllegalStateException("无法打开 $path：${e.message}。普通手机请使用桥接模式。",e) }
        timerExecutor = Executors.newSingleThreadScheduledExecutor { Runnable -> Thread(Runnable,"JoyDurm-HID-ACK").apply { isDaemon=true } }.apply {
            scheduleAtFixedRate({
                if (!active) return@scheduleAtFixedRate
                try {
                    ack.nextWrite(SystemClock.elapsedRealtimeNanos())?.let { packet ->
                        val sink = output ?: return@let; sink.write(packet); ack.written(packet.size,packet.size)
                    }
                    ack.failure?.let { status("HID 初始化失败：$it"); close() }
                } catch (e: Exception) { status("HID 写入失败：${e.javaClass.simpleName}"); close() }
            },0,20,TimeUnit.MILLISECONDS)
        }
        thread(name="JoyDurm-HID",isDaemon=true) {
            try {
                val buffer = ByteArray(128)
                var lastTimer: Int? = null; var lastRead = 0L; var elapsed = 0L
                var warmupStart = 0L; var anchor = Long.MAX_VALUE; var anchored = false
                while (active) {
                    val stream = input ?: break
                    val poll = StructPollfd().apply { fd=stream.fd; events=OsConstants.POLLIN.toShort() }
                    if (Os.poll(arrayOf(poll),100) <= 0) continue
                    if (poll.revents.toInt().and(OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                    val n = stream.read(buffer); if (n<=0) break
                    val report = buffer.copyOf(n); ack.onReport(report)
                    if (!ack.ready) continue
                    val offset = if (report.firstOrNull()?.toInt()?.and(255)==0xa1) 1 else 0
                    if (n < offset+49 || report[offset].toInt().and(255) != 0x30) continue
                    val now = SystemClock.elapsedRealtimeNanos(); val timer = report[offset+1].toInt().and(255)
                    val previous = lastTimer
                    if (previous != null) {
                        if (now-lastRead > 600_000_000L) { status("HID 时钟连续性丢失，请重新打开并归中"); break }
                        val delta = (timer-previous).and(255)
                        if (delta==0 || delta>127) continue
                        elapsed += delta*5_000_000L
                    } else warmupStart = now
                    lastTimer=timer; lastRead=now
                    if (!anchored) { anchor=minOf(anchor,now-elapsed); if (now-warmupStart < 100_000_000L) continue; anchored=true }
                    val sourceTime=anchor+elapsed
                    JoyConProtocol.parse(report,id,sourceTime,ack.scale).forEach { sample ->
                        if (now-sample.timeNs in 0..BridgePacketDecoder.STALE_NS)
                            callback(ImuFrame(ControllerIdentity(path,"raw_hid",false,bindingId=id),session,sample.timeNs,now,5_000_000L,sample))
                    }
                }
            } catch (e: Exception) { if (active) status("HID 连接中断：${e.javaClass.simpleName}") }
            finally { close(); removed() }
        }
    }
    fun close() {
        active=false; timerExecutor?.shutdownNow(); timerExecutor=null
        runCatching { input?.close() }; runCatching { output?.close() }; input=null; output=null
    }
}
