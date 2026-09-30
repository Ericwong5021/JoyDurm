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
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import ai.joydurm.core.*
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.net.NetworkInterface
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ControllerInfo(val id: String, val name: String, val transport: String, val motion: Boolean)
data class DeviceDiagnostics(val id: String, val name: String, val transport: String, val motion: Boolean,
    val lastSampleTimeNs: Long?, val sampleCount: Long, val lastError: String?)
data class ConnectionDiagnostics(val running: Boolean, val bridgeRunning: Boolean, val bridgePort: Int?,
    val acceptedPackets: Long, val rejectedPackets: Long, val devices: List<DeviceDiagnostics>, val lanAddresses: List<String>)

/** Never substitute the PHONE's sensors for a controller that has no exposed IMU. */
class ControllerHub(private val context: Context, private val sample: (ImuSample)->Unit, private val status: (String)->Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private data class SensorBinding(val id: String, val manager: SensorManager, val listener: SensorEventListener)
    private val listeners = mutableMapOf<Int,SensorBinding>()
    private val androidIds=mutableMapOf<Int,String>()
    val devices = ConcurrentHashMap<String,ControllerInfo>()
    private val health=mutableMapOf<String,InputHealth>()
    private val raw = mutableMapOf<String,RawHidSource>()
    private var udp: DatagramSocket? = null
    private var acceptedPackets=0L
    private var rejectedPackets=0L
    private var running = false
    var onDeviceLost: ((String)->Unit)? = null
    var onRemoteRole: ((Role,String)->Unit)? = null
    private val watchdog=object: Runnable {
        override fun run() { synchronized(this@ControllerHub) {
            if(!running) return
            expire(SystemClock.elapsedRealtimeNanos())
            handler.postDelayed(this,1000)
        } }
    }
    @Synchronized fun start() {
        if(running) return
        running=true
        input.registerInputDeviceListener(this,handler)
        refresh(); handler.postDelayed(watchdog,1000)
    }
    @Synchronized fun stop() {
        running=false; handler.removeCallbacks(watchdog); input.unregisterInputDeviceListener(this)
        listeners.values.forEach { it.manager.unregisterListener(it.listener) }; listeners.clear(); androidIds.clear()
        closeBridge()
        val sources=raw.values.toList(); raw.clear(); sources.forEach { it.close() }
        devices.keys.toList().forEach { lose(it,"监听已停止",remove=true) }
    }
    @SuppressLint("MissingPermission") fun bondedNames(): List<String> = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { "${it.name ?: "Bluetooth"} · ${it.address}" } ?: emptyList()
    } catch (_: SecurityException) { listOf("请授予附近设备权限") }
    @Synchronized fun diagnostics(): ConnectionDiagnostics {
        val now=SystemClock.elapsedRealtimeNanos(); expire(now)
        val addresses=runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct() }.getOrDefault(emptyList())
        return ConnectionDiagnostics(running,udp?.isClosed==false,udp?.takeUnless { it.isClosed }?.localPort,
            acceptedPackets,rejectedPackets,devices.values.sortedBy { it.id }.map { d ->
                val h=health[d.id]; DeviceDiagnostics(d.id,d.name,d.transport,h?.fresh(now)==true,h?.lastSampleTimeNs,h?.sampleCount ?: 0,h?.lastError)
            },addresses)
    }
    private fun lose(id: String, reason: String, remove: Boolean=false) {
        val wasKnown=devices.containsKey(id)
        health.getOrPut(id) { InputHealth() }.disconnect(reason)
        if(remove) devices.remove(id) else devices[id]?.let { devices[id]=it.copy(motion=false) }
        if(wasKnown) onDeviceLost?.invoke(id)
    }
    private fun expire(now: Long) {
        health.forEach { (id,h) -> if(h.expire(now)) {
            devices[id]?.let { devices[id]=it.copy(motion=false) }; onDeviceLost?.invoke(id)
        } }
    }
    private fun deliver(s: ImuSample): Boolean {
        val now=SystemClock.elapsedRealtimeNanos()
        val h=health.getOrPut(s.device) { InputHealth() }
        if(!h.accept(s,now)) return false
        devices[s.device]?.let { devices[s.device]=it.copy(motion=true) }
        sample(s); return true
    }
    @Synchronized fun refresh() {
        if(!running) return
        InputDevice.getDeviceIds().asIterable().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.vendorId==0x057e || it.name.contains("Joy-Con",true) }.forEach { attach(it) }
    }
    private fun attach(device: InputDevice) {
        if(!running || androidIds.containsKey(device.id)) return
        val id="android:${device.descriptor}"; androidIds[device.id]=id
        health[id]=InputHealth(SystemClock.elapsedRealtimeNanos())
        devices[id]=ControllerInfo(id,device.name,"Android 手柄传感器",false)
        if(Build.VERSION.SDK_INT<31) { lose(id,"Android <12 无控制器传感器 API"); return }
        try {
            val sm=device.sensorManager
            val acc=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyro=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if(acc==null || gyro==null) { lose(id,"系统未开放控制器 IMU"); return }
            var acceleration=Vec3(); var accelerationTime=0L
            val listener=object: SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
                override fun onSensorChanged(e: SensorEvent) { synchronized(this@ControllerHub) {
                    if(!running || listeners[device.id]?.listener !== this || e.values.size<3) return
                    val v=Vec3(e.values[0].toDouble(),e.values[1].toDouble(),e.values[2].toDouble())
                    if(e.sensor.type==Sensor.TYPE_ACCELEROMETER) { acceleration=v; accelerationTime=e.timestamp }
                    else if(e.sensor.type==Sensor.TYPE_GYROSCOPE && accelerationTime>0 && e.timestamp>=accelerationTime && e.timestamp-accelerationTime<=50_000_000L)
                        deliver(ImuSample(id,e.timestamp,acceleration,v))
                } }
            }
            // Track before registration so every partial registration can be cleaned up.
            listeners[device.id]=SensorBinding(id,sm,listener)
            val a=sm.registerListener(listener,acc,5_000,handler)
            val g=sm.registerListener(listener,gyro,5_000,handler)
            if(!a || !g) { sm.unregisterListener(listener); listeners.remove(device.id); lose(id,"传感器注册失败") }
        } catch(e: Exception) {
            listeners.remove(device.id)?.let { it.manager.unregisterListener(it.listener) }
            lose(id,"传感器初始化失败：${e.javaClass.simpleName}")
        }
    }
    @Synchronized fun openHid(path: String) {
        require(running) { "请先启动设备监听" }
        require(Regex("/dev/hidraw[0-9]{1,3}").matches(path)) { "仅允许 /dev/hidrawN" }
        require(raw[path]?.isActive!=true) { "此 HID 节点已打开" }
        lateinit var source: RawHidSource
        source=RawHidSource(path,{ s -> synchronized(this) { if(running && raw[path]===source) deliver(s) } },status) {
            synchronized(this) { if(raw[path]===source) { raw.remove(path); lose(path,"HID 连接已关闭") } }
        }
        raw[path]=source; health[path]=InputHealth(SystemClock.elapsedRealtimeNanos())
        devices[path]=ControllerInfo(path,path,"原始 HID（需要设备节点权限）",false)
        try { source.start() } catch(e: Exception) { raw.remove(path); lose(path,"无法打开设备节点"); throw e }
    }
    @Synchronized fun stopBridge() = closeBridge()
    @Synchronized fun closeBridge() {
        val previous=udp; udp=null; previous?.close()
        devices.keys.filter { it.startsWith("bridge:") }.forEach { lose(it,"桥接监听已关闭",remove=true) }
    }
    @Synchronized fun listenBridge(port: Int, token: String) {
        require(running) { "请先启动设备监听" }
        require(port in 1024..65535 && token.length>=16) { "端口应为 1024–65535；令牌至少 16 字符" }
        closeBridge()
        val socket=DatagramSocket(port).apply { soTimeout=500 }; udp=socket
        acceptedPackets=0; rejectedPackets=0
        thread(name="JoyDurm-UDP",isDaemon=true) {
            val seqs=mutableMapOf<String,Long>(); val lastTimes=mutableMapOf<String,Long>()
            // One extra byte detects truncated oversized datagrams.
            val buffer=ByteArray(8193)
            while(!socket.isClosed) try {
                val packet=DatagramPacket(buffer,buffer.size); socket.receive(packet)
                synchronized(this) {
                    if(!running || udp!==socket) return@thread
                    val now=SystemClock.elapsedRealtimeNanos(); expire(now)
                    try {
                        require(packet.length<=8192)
                        val json=JSONObject(String(packet.data,packet.offset,packet.length,Charsets.UTF_8))
                        require(json.getInt("v")==1 && MessageDigest.isEqual(json.getString("token").toByteArray(),token.toByteArray()))
                        val identity=json.getString("device"); require(identity.isNotBlank() && identity.length<=120)
                        val device="bridge:$identity"; val seq=json.getLong("seq"); require(seq>=0)
                        val previousTime=lastTimes[device]
                        if(previousTime!=null && now-previousTime>2_000_000_000L) seqs.remove(device)
                        require(seq>(seqs[device] ?: -1))
                        val data=json.getJSONArray("samples"); require(data.length() in 1..3)
                        val decoded=(0 until data.length()).map { i ->
                            val obj=data.getJSONObject(i); val a=obj.getJSONArray("a"); val g=obj.getJSONArray("g")
                            require(a.length()==3 && g.length()==3)
                            ImuSample(device,now-(data.length()-1-i)*5_000_000L,Vec3(a.getDouble(0),a.getDouble(1),a.getDouble(2)),Vec3(g.getDouble(0),g.getDouble(1),g.getDouble(2)))
                        }
                        require(decoded.all { it.accel.finite() && it.gyro.finite() && it.accel.norm()<=200 && it.gyro.norm()<=100 })
                        seqs[device]=seq; lastTimes[device]=now
                        devices[device]=ControllerInfo(device,json.optString("name","Joy-Con").take(120),"LAN · ${packet.address.hostAddress}",false)
                        val role=Role.entries.firstOrNull { it.name==json.optString("role") }
                        if(role!=null) onRemoteRole?.invoke(role,device)
                        decoded.forEach { deliver(it) }; acceptedPackets++
                    } catch(_: Exception) { rejectedPackets++ }
                }
            } catch(_: SocketTimeoutException) {
                synchronized(this) { if(running && udp===socket) expire(SystemClock.elapsedRealtimeNanos()) }
            } catch(_: Exception) {
                synchronized(this) { if(udp===socket) { closeBridge(); status("桥接监听已中断") } }
                break
            }
        }
        status("桥接监听已开启 · UDP $port")
    }
    @Synchronized override fun onInputDeviceAdded(deviceId: Int) { if(running) InputDevice.getDevice(deviceId)?.let { if(it.vendorId==0x057e || it.name.contains("Joy-Con",true)) attach(it) } }
    @Synchronized override fun onInputDeviceChanged(deviceId: Int) { onInputDeviceRemoved(deviceId); onInputDeviceAdded(deviceId) }
    @Synchronized override fun onInputDeviceRemoved(deviceId: Int) {
        listeners.remove(deviceId)?.let { it.manager.unregisterListener(it.listener) }
        androidIds.remove(deviceId)?.let { lose(it,"系统手柄已断开",remove=true) }
    }
}

/** Direct HID transport for devices/ROMs that grant this app access to hidraw. No root is requested by the app. */
private class RawHidSource(val path: String,private val callback: (ImuSample)->Unit,private val status: (String)->Unit,private val removed: (String)->Unit) {
    private var input: FileInputStream?=null
    private var output: FileOutputStream?=null
    @Volatile private var active=false
    val isActive get() = active
    private var counter=0
    private var scale=JoyConProtocol.ImuScale()
    fun start() {
        try { input=FileInputStream(File(path)); output=FileOutputStream(File(path)); active=true }
        catch(e: Exception) { close(); throw IllegalStateException("无法打开 $path：${e.message}。普通手机请使用桥接模式。",e) }
        thread(name="JoyDurm-HID",isDaemon=true) {
            try {
                command(0x40,1); command(0x03,0x30); command(0x30,1)
                command(0x10,0x20,0x60,0,0,24) // Read factory IMU calibration at 0x6020.
                val buffer=ByteArray(128)
                var lastTimer: Int?=null
                var lastReportTime=0L
                while(active) {
                    val n=input?.read(buffer) ?: break
                    if(n<=0) break
                    val report=buffer.copyOf(n)
                    if(n>=44 && (report[0].toInt() and 255)==0x21 && (report[13].toInt() and 0x80)!=0 && (report[14].toInt() and 255)==0x10 && (report[15].toInt() and 255)==0x20 && (report[16].toInt() and 255)==0x60 && report[17]==0.toByte() && report[18]==0.toByte() && report[19]==24.toByte()) {
                        runCatching { JoyConProtocol.factoryScale(report.copyOfRange(20,44)) }.onSuccess { scale=it }
                    }
                    val now=SystemClock.elapsedRealtimeNanos()
                    if(n>=49 && (report[0].toInt() and 255)==0x30) {
                        val timer=report[1].toInt() and 255
                        val previous=lastTimer
                        if(previous!=null && now-lastReportTime<250_000_000L) {
                            val delta=(timer-previous) and 255
                            if(delta==0 || delta>127) continue
                        }
                        lastTimer=timer; lastReportTime=now
                    }
                    JoyConProtocol.parse(report,path,now,scale).forEach(callback)
                }
            } catch(e: Exception) { if(active) status("HID 连接中断：${e.message}") }
            finally { close(); removed(path) }
        }
    }
    private fun command(cmd: Int,vararg data: Int) { output?.write(JoyConProtocol.subcommand(counter++,cmd,*data)) }
    fun close() { active=false; runCatching { input?.close() }; runCatching { output?.close() }; input=null; output=null }
}
