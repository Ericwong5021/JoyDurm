package ai.joydurm.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.ArCoreApk
import ai.joydurm.audio.DrumAudio
import ai.joydurm.audio.SoundVoice
import ai.joydurm.core.*
import ai.joydurm.input.ControllerHub
import ai.joydurm.input.FrameRecording
import ai.joydurm.render.DrumScene
import java.io.File
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity: ComponentActivity() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var engine: DrumEngine
    private lateinit var engineExecutor: EngineExecutor
    private val beatThread=HandlerThread("JoyDurm-beat").apply { start() }
    private val beatHandler=Handler(beatThread.looper)
    private val beatClock=AbsoluteBeatClock()
    private lateinit var hub: ControllerHub
    private lateinit var audio: DrumAudio
    private lateinit var store: SettingsStore
    private lateinit var scene: DrumScene
    private lateinit var sceneHost: FrameLayout
    private lateinit var message: TextView
    private lateinit var monitor: TextView
    private lateinit var hatBar: ProgressBar
    @Volatile private var bpm=100
    @Volatile private var metronome=false
    @Volatile private var resumed=false
    private data class ImportRequest(val model: Boolean,val voice: SoundVoice,val bank: Int)
    private var pendingImport: ImportRequest?=null
    private var importing=false
    private val importWorker=Executors.newSingleThreadExecutor()
    private var arRequested=false
    private var arInstallRequested=false
    private var activeCalibrationDialog: AlertDialog?=null
    private var hitCount=0
    private var sceneGeneration=0L
    private val frameRecording=FrameRecording(4000)
    private val frameExporter=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null)importWorker.execute {
            runCatching { requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(frameRecording.exportJson()) } }
                .onSuccess { showStatus("已保存最近接收的 IMU 样本；记录不代表硬件已验收") }.onFailure { showStatus("记录保存失败：${it.message}") }
        }
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(arRequested && granted[Manifest.permission.CAMERA]==true) enableAr()
        else if(arRequested) showStatus("摄像头权限未授予，可继续使用 3D 模式")
        arRequested=false; if(::hub.isInitialized)hub.refresh()
    }
    private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request=pendingImport; pendingImport=null
        if(uri!=null && request!=null) {
            importing=true; showStatus("正在读取并检查文件…")
            importWorker.execute {
                val temporary=runCatching { File.createTempFile("import-",if(request.model)".glb" else ".wav",cacheDir) }.getOrElse { error -> main.post { importing=false; showStatus("无法创建导入文件：${error.message}") }; return@execute }
                val result=runCatching {
                    val limit=if(request.model)20_000_000 else 1_000_000
                    requireNotNull(contentResolver.openInputStream(uri)) { "无法读取文件" }.use { input -> temporary.outputStream().use { out ->
                        val buffer=ByteArray(8192); var total=0
                        while(true) { val count=input.read(buffer); if(count<0)break; total+=count; require(total<=limit){"文件过大"}; out.write(buffer,0,count) }
                    } }
                    if(request.model) GlbValidator.prepare(temporary) else { WaveValidator.validate(temporary.readBytes()); null }
                }
                main.post {
                    if(isDestroyed) { temporary.delete(); return@post }
                    val previousModel=File(store.prefs.getString("modelPath",File(filesDir,"imported.glb").path)!!)
                    val target=File(filesDir,if(request.model)"imported-${SystemClock.elapsedRealtimeNanos()}.glb" else "custom-${request.bank}-${request.voice.name}-${SystemClock.elapsedRealtimeNanos()}.wav")
                    runCatching {
                        val prepared=result.getOrThrow()
                        try { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
                        catch(_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING) }
                        if(request.model) { store.saveScene(scene); scene.importModel(target,prepared); store.prefs.edit().putBoolean("customModel",true).putString("modelPath",target.path).remove("layout").apply(); store.saveScene(scene); if(previousModel!=target)previousModel.delete(); showStatus("模型已加载") }
                        else {
                            val key="wav-${request.bank}-${request.voice.name}"
                            audio.importWav(target,request.voice,request.bank) { success ->
                                if(success) {
                                    val previous=store.prefs.getString(key,null)
                                    store.prefs.edit().putString(key,target.path).apply()
                                    previous?.let { if(it!=target.path)File(it).delete() }
                                    showStatus("${audio.kitNames[request.bank]} · ${request.voice.label} 音色已加载")
                                } else { target.delete(); showStatus("音色加载失败，保留原音色") }
                            }
                            showStatus("正在加载 ${audio.kitNames[request.bank]} · ${request.voice.label}…")
                        }
                    }.onFailure { target.delete(); showStatus("导入失败：${it.message}") }
                    temporary.delete(); importing=false
                }
            }
        }
    }
    private fun launchImport(request: ImportRequest) {
        if(importing || pendingImport!=null) { showStatus("请先完成当前导入"); return }
        pendingImport=request
        importer.launch(if(request.model)arrayOf("model/gltf-binary","application/octet-stream") else arrayOf("audio/wav","audio/x-wav","application/octet-stream"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store=SettingsStore(this)
        bpm=store.prefs.getInt("bpm",100).coerceIn(30,240)
        arInstallRequested=savedInstanceState?.getBoolean("arInstallRequested") ?: false
        arRequested=savedInstanceState?.getBoolean("arRequested") ?: false
        if(savedInstanceState?.containsKey("pendingModel")==true) pendingImport=ImportRequest(savedInstanceState.getBoolean("pendingModel"),SoundVoice.valueOf(savedInstanceState.getString("pendingDrum",SoundVoice.SNARE.name)),savedInstanceState.getInt("pendingBank",0).coerceIn(0,2))
        audio=DrumAudio(this,::showStatus)
        audio.kit=store.prefs.getInt("kit",0).coerceIn(0,2); audio.volume=store.prefs.getFloat("volume",0.8f).takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.8f
        engine=DrumEngine(onHatControl={ audio.control(it) }) { event ->
            audio.play(event)
            main.post { if(isDestroyed)return@post; if(::scene.isInitialized) scene.hit(event); hitCount++; if(::message.isInitialized) message.text="${event.drum.label} · 力度 ${(event.velocity*127).toInt()}" }
        }
        store.load(engine)
        engineExecutor=EngineExecutor(engine)
        hub=ControllerHub(this,{},::showStatus)
        hub.onFrame={ frameRecording.record(it); engineExecutor.submit(it) }
        hub.onDeviceLost={ device -> engineExecutor.deviceLost(device) }
        hub.onRemoteRole={ role,device -> engineExecutor.call { e ->
            val state=e.snapshot()
            if(state.roles[role]?.device==null && state.roles.values.none { it.device==device }) e.assign(role,device)
        } }
        buildUi(); buildScene(false)
        if(!store.prefs.getBoolean("onboarded",false) && pendingImport==null && !arInstallRequested) main.post { if(!isDestroyed)onboardingDialog() }
        // Restore user samples after the built-in sample loader's serial work has begun.
        for(bank in 0..2) for(d in SoundVoice.importable) store.prefs.getString("wav-$bank-${d.name}",null)?.let { path ->
            val f=File(path); if(f.exists()) runCatching { audio.importWav(f,d,bank) }
        }
    }
    private fun buildUi() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16,21,31)); setPadding(dp(12),dp(8),dp(12),dp(8)) }
        root.setOnApplyWindowInsetsListener { v,insets -> v.setPadding(dp(12)+insets.systemWindowInsetLeft,dp(8)+insets.systemWindowInsetTop,dp(12)+insets.systemWindowInsetRight,dp(8)+insets.systemWindowInsetBottom); insets }
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        bar.addView(text("JoyDurm",24,true),LinearLayout.LayoutParams(0,dp(48),1f))
        listOf("设备" to ::devicesDialog,"校准" to ::calibrationDialog,"布局" to ::layoutDialog,"音色" to ::audioDialog).forEach { (name,fn) -> bar.addView(button(name,fn)) }
        bar.addView(button("AR 相机") { if(scene.ar) buildScene(false) else requestAr() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(bar) })
        val middle=LinearLayout(this)
        sceneHost=FrameLayout(this); middle.addView(sceneHost,LinearLayout.LayoutParams(0,-1,1f))
        val side=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
        side.addView(text("LIVE INPUT",12,true))
        monitor=text("四个 Joy-Con 尚未绑定",13); side.addView(monitor)
        side.addView(text("HI-HAT OPEN",11,true))
        hatBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }; side.addView(hatBar)
        side.addView(button("重新归中") { engineCommand("已归中可用角色；断连和布局变更后请重新标定") { e ->
            Role.entries.forEach { role -> if(e.snapshot().roles[role]?.latestTimeNs?.let { SystemClock.elapsedRealtimeNanos()-it in 0..500_000_000L }==true) e.recenter(role) }
        } })
        side.addView(button("节拍器") { metronome=!metronome; setBeatRunning(metronome); showStatus(if(metronome)"节拍器 $bpm BPM" else "节拍器已关闭") })
        side.addView(text("触摸下方鼓垫也可演奏\n无需连接硬件",12))
        middle.addView(ScrollView(this).apply { addView(side) },LinearLayout.LayoutParams(dp(if(resources.configuration.screenWidthDp<600)130 else 190),-1))
        root.addView(middle,LinearLayout.LayoutParams(-1,0,1f))
        val pads=LinearLayout(this)
        Drum.entries.filter { it!=Drum.CHICK }.forEach { d -> pads.addView(button(d.label) { engineCommand { it.trigger(d,timeNs=SystemClock.elapsedRealtimeNanos()) } },LinearLayout.LayoutParams(0,dp(54),1f)) }
        root.addView(HorizontalScrollView(this).apply { addView(pads,FrameLayout.LayoutParams(max(resources.displayMetrics.widthPixels-dp(24),dp(640)),dp(54))) })
        message=text("连接 → 绑定四肢 → 校准 → 演奏。AR 需要兼容手机。",12)
        root.addView(message); setContentView(root)
    }
    private fun buildScene(ar: Boolean) {
        if(importing) { showStatus("模型或音色导入期间请稍后切换场景"); return }
        if(::scene.isInitialized) { store.saveScene(scene); scene.destroy(); sceneHost.removeAllViews() }
        scene=DrumScene(this,ar,{ drum -> engineCommand { it.trigger(drum,timeNs=SystemClock.elapsedRealtimeNanos()) } },::showStatus)
        val generation=++sceneGeneration
        val current=scene
        store.loadScene(current)
        current.snapshotProvider={ engineExecutor.snapshot() }
        current.onLayoutChanged={ pieces,scale,yaw -> engineCommand("布局已变化，请归中并重新绑定鼓件方向") { it.replaceLayout(pieces,scale,yaw,forceRevision=true) } }
        engineCommand { it.replaceLayout(current.layoutPieces(),current.kitScale,current.kitYaw) }
        sceneHost.addView(current.view,FrameLayout.LayoutParams(-1,-1))
        val custom=File(store.prefs.getString("modelPath",File(filesDir,"imported.glb").path)!!)
        if(store.prefs.getBoolean("customModel",false) && custom.exists()) importWorker.execute {
            val prepared=runCatching { GlbValidator.prepare(custom) }
            main.post {
                if(isDestroyed || sceneGeneration!=generation || scene!==current)return@post
                runCatching { current.importModel(custom,prepared.getOrThrow()); store.loadScene(current) }
                    .onFailure { showStatus("自定义模型加载失败，保留现有鼓组：${it.message}") }
            }
        }
        showStatus(if(ar)"扫描地面，然后点击摆放鼓组" else "3D 演奏模式 · 点击鼓件或使用鼓垫")
    }
    private fun requestAr() {
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { arRequested=true; permissions.launch(arrayOf(Manifest.permission.CAMERA)); return }
        enableAr()
    }
    private fun enableAr() {
        try {
            val availability=ArCoreApk.getInstance().checkAvailability(this)
            if(availability.isTransient) { showStatus("正在检测 AR 支持，请稍后再点"); return }
            if(!availability.isSupported) { showStatus("此手机未获 ARCore 支持，请使用 3D 模式"); return }
            if(ArCoreApk.getInstance().requestInstall(this,!arInstallRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) { arInstallRequested=true; return }
            arInstallRequested=false
            buildScene(true)
        } catch(e: Exception) { arInstallRequested=false; showStatus("AR 启动失败：${e.message}") }
    }
    private fun onboardingDialog() {
        val body=column()
        body.addView(text("先用触摸鼓垫确认声音，再连接手柄。",18,true))
        body.addView(text("1. 点击下方鼓垫，确认手机扬声器或有线耳机有声音。\n2. 在电脑运行桥接脚本，将四只 Joy-Con 的 IMU 数据发到手机；系统蓝牙已配对不代表收到 IMU。\n3. 进入设备 → 连接诊断，确认每只手柄的样本持续更新，再绑定左右手和左右脚。\n4. 进入校准，静置三秒、朝前归中，再绑定鼓件方向。\n5. 先用 3D 演奏；AR 需兼容设备、摄像头权限和地面扫描。",14))
        body.addView(text("此版本尚未完成真实手机和四只 Joy-Con 的联合验证，请通过连接诊断确认实际数据。",13))
        body.addView(button("打开设备与连接诊断") { devicesDialog() })
        dialog("首次使用",body) { store.prefs.edit().putBoolean("onboarded",true).apply() }
    }
    private fun copy(value: String,label: String) {
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(label,value)); showStatus("$label 已复制")
    }
    private fun devicesDialog() {
        val body=column()
        body.addView(text("原版 Switch Joy-Con · 四只手柄分别绑定左右手与左右脚。蓝牙配对由系统完成。",14))
        body.addView(button("打开蓝牙配对") {
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        body.addView(button("首次使用步骤") { onboardingDialog() })
        body.addView(text("系统已配对（不等于实时 IMU）\n"+hub.bondedNames().joinToString("\n").ifBlank { "暂无已配对设备，或尚未授权蓝牙访问" },12))
        val list=text("",13); body.addView(list)
        fun refreshList() {
            hub.refresh()
            val diagnostic=hub.diagnostics(); val now=SystemClock.elapsedRealtimeNanos()
            list.text="连接诊断\n本机 LAN：${diagnostic.lanAddresses.joinToString().ifBlank { "没有可用 IPv4，请连接 Wi-Fi" }}\n"+
                "UDP：${if(diagnostic.bridgeRunning)"监听 ${diagnostic.bridgePort}" else "已关闭"} · 接受 ${diagnostic.acceptedPackets} / 拒绝 ${diagnostic.rejectedPackets}\n\n"+
                diagnostic.devices.joinToString("\n\n") { device ->
                    val age=device.lastSampleTimeNs?.let { ((now-it).coerceAtLeast(0)/1_000_000) }
                    "${if(device.motion)"● 实时 IMU" else "○ 无新鲜 IMU"} ${device.name}\n${device.transport} · ${device.id}\n"+
                        "${age?.let { "最后样本 ${it} ms 前" } ?: "尚未收到有效样本"} · 样本 ${device.sampleCount}"+
                        (device.lastError?.let { "\n错误：$it" } ?: "")
                }.ifBlank { "未发现输入设备；先运行桥接，确认 IP、端口和令牌一致" }
        }
        refreshList(); body.addView(button("刷新连接状态") { refreshList() })
        body.addView(button("导出能力与采样报告") { importWorker.execute {
            val report=hub.capabilityReport(); val f=File(filesDir,"capability-probe.json"); f.writeText(report)
            main.post { copy(report,"能力报告（实测状态见报告）") }
        } })
        body.addView(button("保存最近 IMU 样本") { frameExporter.launch("JoyDurm-imu.json") })
        Role.entries.forEach { role -> body.addView(button("绑定 ${role.label}") {
            val all=hub.devices.values.filter { it.motion }.sortedBy { it.id }
            if(all.isEmpty()) { showStatus("暂无提供 IMU 的设备"); return@button }
            AlertDialog.Builder(this).setTitle(role.label).setItems(all.map { "${it.name} · ${it.transport} · ${it.id}" }.toTypedArray()) { _,i -> engineCommand("${role.label} 已绑定，请归中") { it.assign(role,all[i].id) } }.show()
        }) }
        body.addView(text("LAN 桥接（同一 Wi-Fi）",15,true))
        val host=field("手机 LAN 地址（多网卡时选择 Wi-Fi 地址）",hub.diagnostics().lanAddresses.firstOrNull() ?: ""); body.addView(host)
        val port=field("UDP 端口",store.prefs.getInt("port",18185).toString()); body.addView(port)
        val bridgeToken=store.prefs.getString("token",null) ?: newToken().also { store.prefs.edit().putString("token",it).apply() }
        val token=field("桥接令牌",bridgeToken); body.addView(token)
        body.addView(button("复制令牌") { copy(token.text.toString(),"令牌") })
        body.addView(button("开启桥接监听") { runCatching {
            val p=port.text.toString().toInt(); require(p in 1024..65535) { "端口须为 1024–65535" }; val t=token.text.toString(); require(t.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "令牌须为 16–128 位字母、数字、下划线或短横线" }; hub.listenBridge(p,t)
            store.prefs.edit().putInt("port",p).putString("token",t).putBoolean("bridge",true).apply(); refreshList()
        }.onFailure { showStatus(it.message ?: "参数错误") } })
        body.addView(button("关闭桥接监听") { hub.stopBridge(); store.prefs.edit().putBoolean("bridge",false).apply(); refreshList() })
        body.addView(button("复制电脑桥接命令") { runCatching {
            val ip=host.text.toString(); require(ip.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && ip.split(".").all { it.toInt() in 0..255 }) { "请输入连接诊断中的本机 IPv4 地址" }
            val p=port.text.toString().toInt(); require(p in 1024..65535) { "端口无效" }
            val t=token.text.toString(); require(t.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "令牌格式无效" }
            copy("python tools/bridge/joydurm_bridge.py --host $ip --port $p --token $t --bind LEFT_HAND=ID1 --bind RIGHT_HAND=ID2 --bind LEFT_FOOT=ID3 --bind RIGHT_FOOT=ID4","桥接命令")
        }.onFailure { showStatus(it.message ?: "无法生成命令") } })
        body.addView(text("电脑在项目目录先运行 python tools/bridge/joydurm_bridge.py --list，将命令中的 ID1–ID4 替换为实际设备 ID。手机和电脑连接同一局域网，并允许 UDP。拒绝计数增加时检查令牌和数据格式。",12))
        body.addView(text("高级：直连原始 HID。仅适用于已开放 /dev/hidraw 权限的开发设备；普通手机使用上方桥接。",12))
        val path=field("HID 节点","/dev/hidraw0"); body.addView(path)
        body.addView(button("打开 HID") { runCatching { hub.openHid(path.text.toString()); refreshList() }.onFailure { showStatus(it.message ?: "HID 打开失败") } })
        val deviceDialog=dialog("连接与角色绑定",body)
        val refresh=object: Runnable { override fun run() { if(deviceDialog.isShowing) { if(resumed)refreshList(); main.postDelayed(this,1000) } } }
        deviceDialog.setOnDismissListener { main.removeCallbacks(refresh) }; main.post(refresh)
    }
    private fun calibrationDialog() {
        val body=column()
        body.addView(text("1. 静置校准：测量陀螺仪零偏。\n2. 朝前归中：双手朝向正前方，左脚平放。\n3. 指向鼓件：指向预期击打位置并绑定。\n布局变更后重新归中并绑定方向。左脚归中后记录平放／全开两点。姿态分区决定鼓件；Joy-Con 不提供绝对 XYZ。",14))
        Role.entries.forEach { role ->
            body.addView(text(role.label,17,true))
            body.addView(button("静置校准 · 3 秒") { calibrate(role) })
            body.addView(button("设置当前位置为零点") { engineCommand("${role.label} 已归中") { e -> requireFresh(role); e.recenter(role) } })
            if(role!=Role.LEFT_FOOT)body.addView(button("击打阈值 / 方向") { tuningDialog(role) })
            if(role==Role.LEFT_FOOT) {
                body.addView(button("左脚平放：记录闭镲点") { engineCommand("已记录闭镲点") { it.captureHatClosed() } })
                body.addView(button("左脚全开：记录开镲点") { engineCommand("已记录开镲点") { it.captureHatOpen() } })
            }
            if(role==Role.LEFT_HAND || role==Role.RIGHT_HAND) body.addView(button("绑定一个鼓件方向") {
                val targets=Drum.entries.filter { it!=Drum.KICK && it!=Drum.CHICK }
                AlertDialog.Builder(this).setTitle("${role.label} 指向对应位置，然后点击鼓件").setItems(targets.map { it.label }.toTypedArray()) { _,i ->
                    engineCommand("已绑定 ${targets[i].label}") { e -> requireFresh(role); e.bindTarget(role,targets[i]) }
                }.show()
            })
        }
        body.addView(button("清空鼓件方向映射") { engineCommand("当前布局映射已清空，请归中并绑定鼓件方向") { it.resetTargets() } })
        dialog("校准与映射",body)
    }
    private fun calibrate(role: Role) {
        runCatching { requireFresh(role) }.onFailure { showStatus(it.message ?: "没有实时数据") }.getOrElse { return }
        activeCalibrationDialog?.dismiss()
        engineCommand { it.startCalibration(role) }
        val progress=text("保持手柄静止，正在采样…",18)
        val d=AlertDialog.Builder(this).setTitle(role.label).setView(progress).setNegativeButton("取消") { _,_ -> engineCommand { it.cancelCalibration(role) } }.create()
        val finish=Runnable { if(d.isShowing) { engineCommand("${role.label} 零偏校准完成，请重新归中") { it.finishCalibration(role) }; d.dismiss() } }
        d.setOnDismissListener { main.removeCallbacks(finish); engineCommand { it.cancelCalibration(role) }; if(activeCalibrationDialog===d)activeCalibrationDialog=null }
        activeCalibrationDialog=d; d.show(); main.postDelayed(finish,3000)
    }
    private fun tuningDialog(role: Role) {
        if(role==Role.LEFT_FOOT) { showStatus("左脚使用闭镲／全开两点自动识别铰链轴，请先归中后记录两点"); return }
        val body=column(); val s=engineExecutor.snapshot().roles[role]!!
        val threshold=field(if(role==Role.RIGHT_FOOT)"踩击阈值 m/s²" else "挥击阈值 rad/s",s.threshold.toString()); body.addView(threshold)
        val cooldown=field("防重复间隔 ms",(s.cooldownNs/1_000_000).toString()); body.addView(cooldown)
        val axis=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("X 轴","Y 轴","Z 轴")); setSelection(s.axis) }; body.addView(axis)
        val flip=CheckBox(this).apply { text=if(role==Role.RIGHT_FOOT)"反转下踩方向" else "反转挥击方向"; isChecked=s.sign<0 }; body.addView(flip)
        val range=field("踩镲全开角度 °",Math.toDegrees(engineExecutor.snapshot().hatRange).roundToInt().toString())
        val footFlip=CheckBox(this).apply { text="反转左脚抬起方向"; isChecked=engineExecutor.snapshot().hatSign<0 }
        if(role==Role.LEFT_FOOT) { body.addView(range); body.addView(footFlip) }
        dialog("${role.label} · 参数",body) {
            runCatching {
                val newThreshold=finite(threshold,0.2,30.0); val newCooldown=cooldown.text.toString().toLong().coerceIn(30,500)*1_000_000
                val newRange=if(role==Role.LEFT_FOOT)Math.toRadians(finite(range,6.0,85.0)) else engineExecutor.snapshot().hatRange
                val chosenAxis=axis.selectedItemPosition; val chosenSign=if(flip.isChecked)-1.0 else 1.0
                val chosenHatSign=if(footFlip.isChecked)-1.0 else 1.0
                engineCommand("参数已保存，请重新归中") { e ->
                    e.tune(role,newThreshold,newCooldown,chosenAxis,chosenSign)
                    if(role==Role.LEFT_FOOT)e.configureHat(newRange,chosenHatSign)
                }
            }.getOrThrow()
        }
    }
    private fun layoutDialog() {
        val body=column(); scene.editMode=true
        body.addView(text("AR 中首次点击地面摆放；尺寸和各鼓件位置以米为单位。布局变化后需归中并重新标定方向；IMU 不提供自由 XYZ 位置。",14))
        val scale=field("鼓组缩放 0.3–2.0",scene.kitScale.toString()); body.addView(scale)
        val yaw=field("鼓组旋转 °",scene.kitYaw.toString()); body.addView(yaw)
        val drums=Drum.entries.filter { it!=Drum.CHICK }
        val spinner=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,drums.map { it.label }) }; body.addView(spinner)
        val x=field("X 左右（米）","0"); val y=field("Y 高度（米）","0"); val z=field("Z 前后（米）","0")
        body.addView(x); body.addView(y); body.addView(z)
        spinner.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?,v: View?,position: Int,id: Long) { val pos=scene.piecePosition(drums[position]); x.setText(pos.x.toString()); y.setText(pos.y.toString()); z.setText(pos.z.toString()) }
        }
        body.addView(button("应用布局") { runCatching {
            val newScale=finite(scale,0.3,2.0).toFloat(); val newYaw=finite(yaw,-360.0,360.0).toFloat()
            val newX=finite(x,-3.0,3.0).toFloat(); val newY=finite(y,0.0,3.0).toFloat(); val newZ=finite(z,-3.0,3.0).toFloat()
            scene.kitScale=newScale; scene.kitYaw=newYaw
            scene.setPiecePosition(drums[spinner.selectedItemPosition],newX,newY,newZ); store.saveScene(scene)
        }.onFailure { showStatus("布局参数无效") } })
        body.addView(button("重新选择 AR 地面位置") { scene.resetPlacement(); showStatus("点击地面重新摆放") })
        body.addView(button("导入 GLB 模型") { launchImport(ImportRequest(true,SoundVoice.SNARE,audio.kit)) })
        body.addView(text("GLB 的八个鼓件需独立命名，详见 README。导入前请确认模型有再分发授权，并保留作者与许可。",12))
        dialog("鼓组空间布局",body).setOnDismissListener { scene.editMode=false }
    }
    private fun audioDialog() {
        val body=column()
        val kit=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,audio.kitNames); setSelection(audio.kit) }; body.addView(kit)
        kit.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { audio.kit=position; store.prefs.edit().putInt("kit",position).apply() }
        }
        body.addView(text("音量",14)); body.addView(SeekBar(this).apply { max=100; progress=(audio.volume*100).toInt(); setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(p: SeekBar?) {}; override fun onStopTrackingTouch(p: SeekBar?) {}
            override fun onProgressChanged(p: SeekBar?,v: Int,user: Boolean) { audio.volume=v/100f; store.prefs.edit().putFloat("volume",audio.volume).apply() }
        }) })
        val tempo=field("节拍器 BPM 30–240",bpm.toString()); body.addView(tempo)
        body.addView(button("设置 BPM") { val value=tempo.text.toString().toIntOrNull(); if(value==null)tempo.error="请输入整数 BPM" else { bpm=value.coerceIn(30,240); store.prefs.edit().putInt("bpm",bpm).apply(); beatHandler.post { beatClock.setBpm(bpm,SystemClock.elapsedRealtimeNanos()) }; showStatus("节拍器 $bpm BPM") } })
        body.addView(button("导入单鼓 WAV 音色") {
            AlertDialog.Builder(this).setTitle("选择要替换的音色").setItems(SoundVoice.importable.map { it.label }.toTypedArray()) { _,i -> launchImport(ImportRequest(false,SoundVoice.importable[i],audio.kit)) }.show()
        })
        body.addView(button("试听音色") { AlertDialog.Builder(this).setTitle("试听").setItems(SoundVoice.importable.map { it.label }.toTypedArray()) { _,i -> audio.playVoice(SoundVoice.importable[i]) }.show() })
        body.addView(button("导出音频提交延迟记录") { importWorker.execute { val f=File(filesDir,"audio-trace.csv"); audio.exportTraceCsv(f); main.post { copy(f.readText(),"音频提交记录（不含声学起音）") } } })
        body.addView(text("内置为原创合成音色，离线可用；真实鼓采样可通过 WAV 导入。先用手机扬声器或有线耳机验证延迟。",13))
        dialog("音色与节拍器",body)
    }
    private fun setBeatRunning(enabled: Boolean) { beatHandler.post {
        beatHandler.removeCallbacks(tick)
        if(enabled && resumed) { beatClock.setBpm(bpm,SystemClock.elapsedRealtimeNanos()); beatClock.start(SystemClock.elapsedRealtimeNanos()); beatHandler.post(tick) }
        else beatClock.stop()
    } }
    private val tick=object: Runnable { override fun run() {
        if(!metronome || !resumed)return
        val now=SystemClock.elapsedRealtimeNanos(); beatClock.poll(now)?.let { audio.click(it.timeNs) }
        beatClock.nextDeadlineNs?.let { next -> beatHandler.postDelayed(this,maxOf(1L,(next-SystemClock.elapsedRealtimeNanos()+999_999L)/1_000_000L)) }
    } }
    private val update=object: Runnable { override fun run() {
        if(!resumed)return
        val now=SystemClock.elapsedRealtimeNanos(); val snapshot=engineExecutor.snapshot()
        monitor.text=Role.entries.joinToString("\n\n") { r -> val s=snapshot.roles[r]!!; val live=s.latestTimeNs?.let { now-it in 0..500_000_000L }==true
            "${if(live)"●" else "○"} ${r.label}\n${if(s.status==OrientationStatus.NEEDS_RECENTER && live)"需要归中" else if(live && r==Role.LEFT_FOOT && !s.hatCalibrated)"需标定开闭两点" else if(live)"实时数据" else if(s.device!=null)"等待数据" else "未绑定"}${if(s.calibration!=null)" · 已校准" else ""}" }
        hatBar.progress=(snapshot.openness*100).toInt(); scene.openness=snapshot.openness
        sceneHost.postOnAnimation(this)
    } }
    override fun onResume() {
        super.onResume(); resumed=true; audio.resume(); hub.start()
        if(store.prefs.getBoolean("bridge",false)) runCatching { hub.listenBridge(store.prefs.getInt("port",18185),store.prefs.getString("token","")!!) }.onFailure { showStatus("桥接启动失败：${it.message}") }
        sceneHost.postOnAnimation(update); if(metronome)setBeatRunning(true)
        if(arInstallRequested && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)enableAr()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("arInstallRequested",arInstallRequested)
        outState.putBoolean("arRequested",arRequested)
        pendingImport?.let { outState.putBoolean("pendingModel",it.model); outState.putString("pendingDrum",it.voice.name); outState.putInt("pendingBank",it.bank) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { activeCalibrationDialog?.dismiss(); resumed=false; hub.stop(); sceneHost.removeCallbacks(update); setBeatRunning(false); audio.pause(); engineCommand { }; if(::scene.isInitialized)store.saveScene(scene); super.onPause() }
    override fun onDestroy() { sceneGeneration++; importWorker.shutdown(); main.removeCallbacksAndMessages(null); hub.stop(); engineExecutor.close(); beatThread.quitSafely(); audio.close(); scene.destroy(); super.onDestroy() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        val drum=when(keyCode) { KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_BUTTON_A -> Drum.SNARE; KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_BUTTON_B -> Drum.KICK; KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_BUTTON_X -> Drum.HAT; KeyEvent.KEYCODE_F,KeyEvent.KEYCODE_BUTTON_Y -> Drum.CRASH; else -> null }
        if(drum!=null && event.repeatCount==0) { engineCommand { it.trigger(drum,timeNs=SystemClock.elapsedRealtimeNanos()) }; return true }; return super.onKeyDown(keyCode,event)
    }
    private fun engineCommand(success: String?=null, action: (DrumEngine)->Unit) {
        if(!::engineExecutor.isInitialized)return
        engineExecutor.call { e -> action(e); store.save(e.snapshot()) }.whenComplete { _,error ->
            if(error!=null)showStatus(error.cause?.message ?: error.message ?: "操作失败") else if(success!=null)showStatus(success)
        }
    }
    private fun showStatus(msg: String) { main.post { if(!isDestroyed && ::message.isInitialized) message.text=msg } }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun text(value: String,size: Int=14,bold: Boolean=false)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(Color.rgb(226,230,239)); setPadding(dp(6),dp(6),dp(6),dp(6)); if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD) }
    private fun button(label: String,action: ()->Unit)=Button(this).apply { text=label; textSize=12f; isAllCaps=false; minWidth=0; minimumWidth=0; setOnClickListener { action() } }
    private fun field(hint: String,value: String)=EditText(this).apply {
        this.hint=hint; contentDescription=hint; setText(value); isSingleLine=true; textSize=14f
        addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) {}
            override fun onViewAttachedToWindow(v: View) {
                removeOnAttachStateChangeListener(this)
                val container=parent as? LinearLayout ?: return
                container.post { if(parent===container)container.addView(text(hint,12,true),container.indexOfChild(v)) }
            }
        })
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
    private fun dialog(title: String,body: View,onSave: (()->Unit)?=null): AlertDialog {
        val scroll=ScrollView(this).apply { addView(body) }
        return AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(if(onSave==null)"完成" else "保存",null).create().also { d -> d.show(); d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(onSave==null) d.dismiss() else runCatching { onSave.invoke() }.onSuccess { d.dismiss() }.onFailure { Toast.makeText(this,it.message ?: "参数无效",Toast.LENGTH_LONG).show(); showStatus(it.message ?: "参数无效") } }; d.window?.setLayout((resources.displayMetrics.widthPixels*if(resources.configuration.screenWidthDp<600)0.95 else 0.65).toInt(),(resources.displayMetrics.heightPixels*0.85).toInt()) }
    }
    private fun requireFresh(role: Role) {
        val time=engineExecutor.snapshot().roles[role]?.latestTimeNs ?: error("没有运动数据")
        require(SystemClock.elapsedRealtimeNanos()-time in 0..500_000_000L) { "没有实时 IMU 数据，请先检查连接诊断" }
    }
    private fun finite(field: EditText,min: Double,max: Double): Double {
        val number=field.text.toString().toDoubleOrNull() ?: run { field.error="请输入数字"; error("请输入有效数字") }
        if(!number.isFinite()) { field.error="请输入有限数字"; error("请输入有限数字") }
        return number.coerceIn(min,max)
    }
    private fun newToken(): String { val b=ByteArray(16); SecureRandom().nextBytes(b); return b.joinToString(""){"%02x".format(it)} }
}
