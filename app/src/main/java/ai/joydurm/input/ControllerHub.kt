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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ControllerInfo(val id: String, val name: String, val transport: String, val motion: Boolean)

/** Never substitute the PHONE's sensors for a controller that has no exposed IMU. */
class ControllerHub(private val context: Context, private val sample: (ImuSample)->Unit, private val status: (String)->Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val listeners = mutableMapOf<Int, Pair<SensorManager,SensorEventListener>>()
    val devices = ConcurrentHashMap<String,ControllerInfo>()
    private val raw = mutableListOf<RawHidSource>()
    private var udp: DatagramSocket? = null
    @Volatile private var running = false
    var onDeviceLost: ((String)->Unit)? = null
    var onRemoteRole: ((Role,String)->Unit)? = null
    fun start() {
        if(running) return
        running=true
        input.registerInputDeviceListener(this,Handler(Looper.getMainLooper()))
        refresh()
    }
    fun stop() {
        running=false; input.unregisterInputDeviceListener(this)
        listeners.values.forEach { it.first.unregisterListener(it.second) }; listeners.clear()
        raw.forEach { it.close() }; raw.clear(); udp?.close(); udp=null
        devices.keys.toList().forEach { onDeviceLost?.invoke(it) }; devices.clear()
    }
    @SuppressLint("MissingPermission") fun bondedNames(): List<String> = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { "${it.name ?: "Bluetooth"} · ${it.address}" } ?: emptyList()
    } catch (_: SecurityException) { listOf("请授予附近设备权限") }
    fun refresh() {
        InputDevice.getDeviceIds().asIterable().mapNotNull { InputDevice.getDevice(it) }.filter { it.vendorId==0x057e || it.name.contains("Joy-Con",true) }.forEach { attach(it) }
    }
    private fun attach(device: InputDevice) {
        if(listeners.containsKey(device.id)) return
        val id="android:${device.descriptor}"
        if(Build.VERSION.SDK_INT<31) { devices[id]=ControllerInfo(id,device.name,"系统手柄（Android <12）",false); return }
        val sm=device.sensorManager
        val acc=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val hasImu=acc!=null && gyro!=null
        devices[id]=ControllerInfo(id,device.name,"Android 手柄传感器",hasImu)
        if(!hasImu) { status("${device.name} 已配对，但系统未开放 IMU；请使用 HID 或桥接模式"); return }
        var acceleration=Vec3()
        var accelerationTime=0L
        val listener=object: SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                val v=Vec3(e.values[0].toDouble(),e.values[1].toDouble(),e.values[2].toDouble())
                if(e.sensor.type==Sensor.TYPE_ACCELEROMETER) { acceleration=v; accelerationTime=e.timestamp }
                else if(accelerationTime > 0 && e.timestamp >= accelerationTime && e.timestamp-accelerationTime <= 50_000_000L) sample(ImuSample(id,e.timestamp,acceleration,v))
            }
        }
        val a=sm.registerListener(listener,acc,5_000)
        val g=sm.registerListener(listener,gyro,5_000)
        if(a && g) listeners[device.id]=Pair(sm,listener)
        else { sm.unregisterListener(listener); devices[id]=ControllerInfo(id,device.name,"传感器注册失败",false) }
    }
    fun openHid(path: String) {
        require(Regex("/dev/hidraw[0-9]{1,3}").matches(path)) { "仅允许 /dev/hidrawN" }
        raw.removeAll { !it.isActive }
        require(raw.none { it.path==path }) { "此 HID 节点已打开" }
        val source=RawHidSource(path,sample,status) { id -> devices.remove(id); onDeviceLost?.invoke(id) }
        devices[path]=ControllerInfo(path,path,"原始 HID（需要设备节点权限）",true)
        try { source.start(); raw.add(source) } catch(e: Exception) { devices.remove(path); throw e }
    }
    fun listenBridge(port: Int, token: String) {
        require(port in 1024..65535 && token.length>=16) { "端口应为 1024–65535；令牌至少 16 字符" }
        udp?.close()
        devices.keys.filter { it.startsWith("bridge:") }.forEach { devices.remove(it); onDeviceLost?.invoke(it) }
        val socket=DatagramSocket(port).apply { soTimeout=1000 }; udp=socket
        thread(name="JoyDurm-UDP",isDaemon=true) {
            val seqs=mutableMapOf<String,Long>()
            val lastTimes=mutableMapOf<String,Long>()
            val buffer=ByteArray(8192)
            while(running && !socket.isClosed) try {
                val packet=DatagramPacket(buffer,buffer.size); socket.receive(packet)
                val expiryNow=SystemClock.elapsedRealtimeNanos()
                lastTimes.filterValues { expiryNow-it>3_000_000_000L }.keys.toList().forEach {
                    devices.remove(it); lastTimes.remove(it); seqs.remove(it); onDeviceLost?.invoke(it)
                }
                val json=JSONObject(String(packet.data,packet.offset,packet.length,Charsets.UTF_8))
                if(json.optInt("v")!=1 || !MessageDigest.isEqual(json.optString("token").toByteArray(),token.toByteArray())) continue
                val identity=json.getString("device")
                if(identity.isBlank() || identity.length>120) continue
                val device="bridge:$identity"
                val seq=json.getLong("seq")
                val now=SystemClock.elapsedRealtimeNanos()
                // A restarted bridge may reset its sequence, but only after an idle timeout.
                if(now-(lastTimes[device] ?: 0)>2_000_000_000L) seqs.remove(device)
                if(seq < 0 || seq <= (seqs[device] ?: -1)) continue
                val data=json.getJSONArray("samples")
                if(data.length() !in 1..3) continue
                val decoded=(0 until data.length()).map { i ->
                    val s=data.getJSONObject(i); val a=s.getJSONArray("a"); val g=s.getJSONArray("g")
                    require(a.length()==3 && g.length()==3)
                    ImuSample(device,now-(data.length()-1-i)*5_000_000L,Vec3(a.getDouble(0),a.getDouble(1),a.getDouble(2)),Vec3(g.getDouble(0),g.getDouble(1),g.getDouble(2)))
                }
                if(decoded.any { !it.accel.finite() || !it.gyro.finite() || it.accel.norm()>200 || it.gyro.norm()>100 }) continue
                seqs[device]=seq; lastTimes[device]=now
                devices[device]=ControllerInfo(device,json.optString("name","Joy-Con"),"LAN · ${packet.address.hostAddress}",true)
                val role=Role.entries.firstOrNull { it.name==json.optString("role") }
                if(role!=null) onRemoteRole?.invoke(role,device)
                decoded.forEach(sample)
            } catch (_: SocketTimeoutException) {
                val now=SystemClock.elapsedRealtimeNanos()
                lastTimes.filterValues { now-it>3_000_000_000L }.keys.toList().forEach {
                    devices.remove(it); lastTimes.remove(it); seqs.remove(it); onDeviceLost?.invoke(it)
                }
            } catch (e: Exception) {
                if(!socket.isClosed && running) status("桥接数据异常：${e.message}")
            }
        }
        status("桥接监听已开启 · UDP $port")
    }
    override fun onInputDeviceAdded(deviceId: Int) { InputDevice.getDevice(deviceId)?.let { if(it.vendorId==0x057e || it.name.contains("Joy-Con",true)) attach(it) } }
    override fun onInputDeviceChanged(deviceId: Int) { onInputDeviceRemoved(deviceId); onInputDeviceAdded(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) {
        listeners.remove(deviceId)?.let { it.first.unregisterListener(it.second) }
        // Remove disconnected system entries without affecting raw HID/LAN connections.
        val live=InputDevice.getDeviceIds().asIterable().mapNotNull { InputDevice.getDevice(it)?.descriptor }.toSet()
        devices.keys.filter { it.startsWith("android:") && it.removePrefix("android:") !in live }.forEach { devices.remove(it); onDeviceLost?.invoke(it) }
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
