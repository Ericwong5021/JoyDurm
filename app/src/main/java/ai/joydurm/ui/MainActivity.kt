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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.ArCoreApk
import ai.joydurm.audio.DrumAudio
import ai.joydurm.audio.SoundVoice
import ai.joydurm.core.*
import ai.joydurm.input.ControllerHub
import ai.joydurm.input.ConnectionDiagnostics
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
    private lateinit var stageUi: StageUi
    private var lastStatus=""
    private var lastUiUpdateNs=0L
    private var diagnosticTimeNs=0L
    private var diagnostic: ConnectionDiagnostics?=null
    private var hatClosedEpoch: Long?=null
    private var restoredPage: StagePage?=null
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
    private var activeBluetoothPicker: BluetoothDevicePicker?=null
    private var activeInputDialog: AlertDialog?=null
    private lateinit var bluetoothSelections: BluetoothRoleSelections
    private var bluetoothInventory: BluetoothInventory?=null
    private val bluetoothPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        activeBluetoothPicker?.refresh(); if(::hub.isInitialized)hub.refresh()
    }
    private val bluetoothEnable=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        activeBluetoothPicker?.refresh()
    }
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
        bluetoothSelections=store.loadBluetoothSelections()
        restoredPage=savedInstanceState?.getString("stagePage")?.let { name -> StagePage.entries.firstOrNull { it.name==name } }
        bpm=store.prefs.getInt("bpm",100).coerceIn(30,240)
        arInstallRequested=savedInstanceState?.getBoolean("arInstallRequested") ?: false
        arRequested=savedInstanceState?.getBoolean("arRequested") ?: false
        if(savedInstanceState?.containsKey("pendingModel")==true) pendingImport=ImportRequest(savedInstanceState.getBoolean("pendingModel"),SoundVoice.valueOf(savedInstanceState.getString("pendingDrum",SoundVoice.SNARE.name)),savedInstanceState.getInt("pendingBank",0).coerceIn(0,2))
        audio=DrumAudio(this,::showStatus)
        audio.kit=store.prefs.getInt("kit",0).coerceIn(0,2); audio.volume=store.prefs.getFloat("volume",0.8f).takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.8f
        engine=DrumEngine(onHatControl={ audio.control(it) }) { event ->
            audio.play(event)
            main.post {
                if(isDestroyed)return@post
                if(::scene.isInitialized)scene.hit(event)
                hitCount++
                lastStatus="${event.drum.label} · 力度 ${(event.velocity*127).toInt()}"
                if(::message.isInitialized)message.text=lastStatus
                if(::stageUi.isInitialized) { stageUi.hit(event.drum,event.velocity); stageUi.refresh(stageState()) }
            }
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
        buildUi(); darkSystemBars(); buildScene(false)
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!stageUi.back()) { isEnabled=false; onBackPressedDispatcher.onBackPressed() } }
        })
        // Restore user samples after the built-in sample loader's serial work has begun.
        for(bank in 0..2) for(d in SoundVoice.importable) store.prefs.getString("wav-$bank-${d.name}",null)?.let { path ->
            val f=File(path); if(f.exists()) runCatching { audio.importWav(f,d,bank) }
        }
    }
    private fun buildUi() {
        sceneHost=FrameLayout(this)
        message=text("",12); monitor=text("",13)
        hatBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        stageUi=StageUi(this,sceneHost,stageActions)
        setContentView(stageUi.root)
        stageUi.show(restoredPage ?: if(store.prefs.getBoolean("onboarded",false))StagePage.PLAY else StagePage.WELCOME,stageState(),remember=false)
    }
    private fun stageState(): StageState {
        val now=SystemClock.elapsedRealtimeNanos(); val snap=engineExecutor.snapshot()
        if(::hub.isInitialized && (diagnostic==null || now-diagnosticTimeNs>500_000_000L)) {
            diagnostic=hub.diagnostics(); diagnosticTimeNs=now
            if(bluetoothSelections.all().isNotEmpty()) bluetoothInventory=AndroidBluetoothPlatform(this).inventory()
        }
        val devices=diagnostic?.devices.orEmpty().associateBy { it.id }
        val foot=snap.roles[Role.LEFT_FOOT]!!
        if(foot.needsRecenter || hatClosedEpoch!=foot.epoch.value)hatClosedEpoch=null
        return StageState(Role.entries.map { role ->
            val value=snap.roles[role]!!; val device=devices[value.device]
            val selected=bluetoothSelections.get(role)
            val bluetoothState=selected?.let { selection ->
                val inventory=bluetoothInventory
                when {
                    inventory?.permitted!=true -> "已选择角色 · 需要附近设备权限"
                    !inventory.enabled -> "已选择角色 · 蓝牙已关闭"
                    inventory.devices.firstOrNull { it.address.equals(selection.address,true) }?.bond==android.bluetooth.BluetoothDevice.BOND_BONDED -> "已选择角色 · 已配对 · 连接由系统管理"
                    inventory.devices.firstOrNull { it.address.equals(selection.address,true) }?.bond==android.bluetooth.BluetoothDevice.BOND_BONDING -> "已选择角色 · 正在配对"
                    else -> "已选择角色 · 尚未配对"
                }
            }
            StageRole(role,selected?.name ?: device?.name,value.device!=null || selected!=null,
                value.latestTimeNs?.let { now-it in 0..500_000_000L }==true,
                value.calibration!=null,value.needsRecenter,value.targetCount,device?.sampleCount ?: 0,
                device?.lastSampleTimeNs?.let { (now-it).coerceAtLeast(0)/1_000_000 },device?.lastError,value.threshold,bluetoothState,selected?.address ?: value.device)
        },snap.openness,Math.toDegrees(snap.hatRange),hatClosedEpoch!=null,foot.hatCalibrated,
            ::scene.isInitialized && scene.ar,::scene.isInitialized && scene.placed,
            audio.kit,(audio.volume*100).roundToInt(),bpm,metronome,
            diagnostic?.devices?.count { it.motion } ?: 0,lastStatus,"由 Android 系统管理")
    }
    private fun navigate(page: StagePage) { stageUi.show(page,stageState()) }
    private fun requestBluetoothPermissions() {
        val required=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing=required.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty())bluetoothPermissions.launch(missing.toTypedArray())
        else activeBluetoothPicker?.refresh()
    }
    private fun openSystemBluetooth() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { showStatus("无法打开系统蓝牙，请在手机设置中检查连接") }
    }
    private fun assignRole(role: Role) = showRolePicker(role,AndroidBluetoothPlatform(this))
    internal fun showRolePicker(role: Role,platform: BluetoothPlatform) {
        if(activeBluetoothPicker?.dialog?.isShowing==true || activeInputDialog?.isShowing==true) { platform.close(); return }
        lateinit var picker: BluetoothDevicePicker
        picker=BluetoothDevicePicker(this,role,platform,
            { bluetoothSelections.get(role)?.address },bluetoothSelections::owner,
            { choice ->
                val unchanged=bluetoothSelections.get(role)?.address.equals(choice.address,true)
                val displaced=bluetoothSelections.select(role,SelectedBluetoothDevice(choice.address,choice.name))
                store.saveBluetoothSelections(bluetoothSelections); diagnosticTimeNs=0
                engineCommand("${role.label} 已选择 ${choice.name}；IMU 尚需确认") { e ->
                    displaced?.let { e.unassign(it) }
                    if(!unchanged || e.snapshot().roles[role]?.device==null)e.assign(role,"bluetooth:${choice.address}")
                }
            },::requestBluetoothPermissions,
            { runCatching { bluetoothEnable.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { openSystemBluetooth() } },::openSystemBluetooth,
            { picker.dismiss(); showRoleInputs(role) },
            { picker.dismiss(); devicesDialog() },
            { if(activeBluetoothPicker===picker)activeBluetoothPicker=null })
        activeBluetoothPicker=picker; picker.show()
    }
    private fun showRoleInputs(role: Role) {
        if(activeInputDialog?.isShowing==true)return
        hub.refresh(); val inputs=hub.devices.values.sortedBy { it.id }
        val body=column()
        val selected=bluetoothSelections.get(role)
        body.addView(text(selected?.let { "${role.label} 已选择 ${it.name} · ${it.address}" } ?: "为${role.label}选择实际输入",14,true))
        body.addView(text("Android 未公开蓝牙地址与手柄输入 ID 的对应关系。请按真实设备确认输入；同名手柄不会自动猜测关联。没有实时 IMU 时仍可关联，但不能校准或体感演奏。",13))
        if(inputs.isEmpty())body.addView(text("暂无控制器输入。请先在系统蓝牙完成配对和连接，再返回刷新。",14))
        val warning=text("",14); body.addView(warning)
        var pendingBind: (() -> Unit)?=null
        val confirmInput=button("确认转移输入到${role.label}") { pendingBind?.invoke() }
        confirmInput.visibility=View.GONE; body.addView(confirmInput)
        inputs.forEach { candidate -> body.addView(button("${candidate.name} · ${candidate.transport}\n${candidate.id}\n${if(candidate.motion) "实时 IMU" else "尚无实时 IMU"}") {
            val previous=engineExecutor.snapshot().roles.entries.firstOrNull { it.key!=role && it.value.device==candidate.id }?.key
            fun bind() {
                if(!hub.devices.containsKey(candidate.id)) { warning.text="输入已断开，请刷新后重新选择。"; return }
                if(previous!=null)bluetoothSelections.remove(previous)
                store.saveBluetoothSelections(bluetoothSelections)
                engineCommand("${role.label} 已关联 ${candidate.name}；请确认实时 IMU 后校准") { it.assign(role,candidate.id) }
                activeInputDialog?.dismiss()
            }
            if(previous!=null) {
                warning.text="此输入正用于${previous.label}；确认后转移到${role.label}。"
                pendingBind=::bind; confirmInput.visibility=View.VISIBLE
            } else bind()
        }) }
        body.addView(button("刷新控制器输入") { activeInputDialog?.dismiss(); showRoleInputs(role) })
        body.addView(button("系统蓝牙连接") { openSystemBluetooth() })
        val dialog=AlertDialog.Builder(this).setTitle("关联${role.label}输入")
            .setView(ScrollView(this).apply { addView(body) }).setNegativeButton("取消",null).create()
        activeInputDialog=dialog
        dialog.setOnDismissListener { if(activeInputDialog===dialog)activeInputDialog=null }
        dialog.show()
    }
    private fun bindDirection(role: Role) {
        val targets=Drum.entries.filter { it!=Drum.KICK && it!=Drum.CHICK }
        AlertDialog.Builder(this).setTitle("${role.label} 指向鼓件，然后选择")
            .setItems(targets.map { it.label }.toTypedArray()) { _,i ->
                engineCommand("已绑定 ${targets[i].label}") { requireFresh(role); it.bindTarget(role,targets[i]) }
            }.show()
    }
    private fun chooseWav() {
        AlertDialog.Builder(this).setTitle("选择要替换的音色")
            .setItems(SoundVoice.importable.map { it.label }.toTypedArray()) { _,i ->
                launchImport(ImportRequest(false,SoundVoice.importable[i],audio.kit))
            }.show()
    }
    private val stageActions=object: StageActions {
        override fun navigate(page: StagePage)=this@MainActivity.navigate(page)
        override fun back() { if(!stageUi.back())this@MainActivity.navigate(StagePage.PLAY) }
        override fun pairBluetooth() = openSystemBluetooth()
        override fun connectionDetails()=devicesDialog()
        override fun assign(role: Role)=assignRole(role)
        override fun calibrate(role: Role)=this@MainActivity.calibrate(role)
        override fun recenter(role: Role)=engineCommand("${role.label} 已归中，请绑定鼓件方向") { requireFresh(role); it.recenter(role) }
        override fun tune(role: Role)=tuningDialog(role)
        override fun bindTarget(role: Role)=bindDirection(role)
        override fun captureHatClosed()=engineCommand("已记录闭镲点",onSuccess={ hatClosedEpoch=engineExecutor.snapshot().roles[Role.LEFT_FOOT]!!.epoch.value }) { requireFresh(Role.LEFT_FOOT); it.captureHatClosed() }
        override fun captureHatOpen()=engineCommand("已记录全开点，请缓慢抬脚验证开度") { requireFresh(Role.LEFT_FOOT); it.captureHatOpen() }
        override fun toggleAr() { if(scene.ar)buildScene(false) else requestAr() }
        override fun editLayout()=layoutDialog()
        override fun resetPlacement() { scene.resetPlacement(); showStatus("扫描并点击地面重新摆放，之后需归中并绑定方向") }
        override fun trigger(drum: Drum)=engineCommand { it.trigger(drum,timeNs=SystemClock.elapsedRealtimeNanos()) }
        override fun setKit(index: Int) { audio.kit=index.coerceIn(0,2); store.prefs.edit().putInt("kit",audio.kit).apply(); showStatus("已选择 ${audio.kitNames[audio.kit]}") }
        override fun setVolume(percent: Int) { audio.volume=percent.coerceIn(0,100)/100f; store.prefs.edit().putFloat("volume",audio.volume).apply() }
        override fun setTempo(bpm: Int) { this@MainActivity.bpm=bpm.coerceIn(30,240); store.prefs.edit().putInt("bpm",this@MainActivity.bpm).apply(); beatHandler.post { beatClock.setBpm(this@MainActivity.bpm,SystemClock.elapsedRealtimeNanos()) }; showStatus("节拍器 ${this@MainActivity.bpm} BPM") }
        override fun toggleMetronome() { metronome=!metronome; setBeatRunning(metronome); showStatus(if(metronome)"节拍器 $bpm BPM" else "节拍器已关闭") }
        override fun importSound()=chooseWav()
        override fun exportAudioTrace() { importWorker.execute { val f=File(filesDir,"audio-trace.csv"); audio.exportTraceCsv(f); main.post { copy(f.readText(),"音频提交记录（不含声学起音）") } } }
        override fun importModel()=launchImport(ImportRequest(true,SoundVoice.SNARE,audio.kit))
        override fun exportImu()=frameExporter.launch("JoyDrum-imu.json")
        override fun capabilityReport() { importWorker.execute { val report=hub.capabilityReport(); main.post { copy(report,"能力与采样报告") } } }
        override fun systemAudioSettings() { runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }.onFailure { showStatus("请在手机系统设置中选择音频输出") } }
        override fun setSensitivity(role: Role,threshold: Double)=engineCommand { e -> val s=e.snapshot().roles[role]!!; e.tune(role,threshold.coerceIn(0.2,30.0),s.cooldownNs,s.axis,s.sign) }
        override fun openLegacyCalibration()=calibrationDialog()
        override fun completeOnboarding() { store.prefs.edit().putBoolean("onboarded",true).apply(); navigate(StagePage.PLAY) }
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
        Role.entries.forEach { role -> body.addView(button("绑定 ${role.label}") { assignRole(role) }) }
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
        scene.openness=snapshot.openness
        if(now-lastUiUpdateNs>=100_000_000L) { stageUi.refresh(stageState()); lastUiUpdateNs=now }
        sceneHost.postOnAnimation(this)
    } }
    override fun onResume() {
        super.onResume(); resumed=true; audio.resume(); hub.start()
        darkSystemBars(); activeBluetoothPicker?.refresh()
        if(store.prefs.getBoolean("bridge",false)) runCatching { hub.listenBridge(store.prefs.getInt("port",18185),store.prefs.getString("token","")!!) }.onFailure { showStatus("桥接启动失败：${it.message}") }
        sceneHost.postOnAnimation(update); if(metronome)setBeatRunning(true)
        if(arInstallRequested && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)enableAr()
    }
    @Suppress("DEPRECATION")
    private fun darkSystemBars() {
        if(Build.VERSION.SDK_INT>=30) {
            window.insetsController?.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            window.decorView.systemUiVisibility=window.decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("stagePage",stageUi.page.name)
        outState.putBoolean("arInstallRequested",arInstallRequested)
        outState.putBoolean("arRequested",arRequested)
        pendingImport?.let { outState.putBoolean("pendingModel",it.model); outState.putString("pendingDrum",it.voice.name); outState.putInt("pendingBank",it.bank) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { activeCalibrationDialog?.dismiss(); resumed=false; hub.stop(); sceneHost.removeCallbacks(update); setBeatRunning(false); audio.pause(); engineCommand { }; if(::scene.isInitialized)store.saveScene(scene); super.onPause() }
    override fun onDestroy() { activeBluetoothPicker?.dismiss(); activeInputDialog?.dismiss(); if(::stageUi.isInitialized)stageUi.close(); sceneGeneration++; importWorker.shutdown(); main.removeCallbacksAndMessages(null); hub.stop(); engineExecutor.close(); beatThread.quitSafely(); audio.close(); scene.destroy(); super.onDestroy() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        val drum=when(keyCode) { KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_BUTTON_A -> Drum.SNARE; KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_BUTTON_B -> Drum.KICK; KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_BUTTON_X -> Drum.HAT; KeyEvent.KEYCODE_F,KeyEvent.KEYCODE_BUTTON_Y -> Drum.CRASH; else -> null }
        if(drum!=null && event.repeatCount==0) { engineCommand { it.trigger(drum,timeNs=SystemClock.elapsedRealtimeNanos()) }; return true }; return super.onKeyDown(keyCode,event)
    }
    private fun engineCommand(success: String?=null, onSuccess: (()->Unit)?=null, action: (DrumEngine)->Unit) {
        if(!::engineExecutor.isInitialized)return
        engineExecutor.call { e -> action(e); store.save(e.snapshot()) }.whenComplete { _,error ->
            if(error!=null)showStatus(error.cause?.message ?: error.message ?: "操作失败") else { if(success!=null)showStatus(success); if(onSuccess!=null)main.post { if(!isDestroyed)onSuccess.invoke() } }
        }
    }
    private fun showStatus(msg: String) { main.post { if(!isDestroyed) { lastStatus=msg; if(::message.isInitialized)message.text=msg; if(::stageUi.isInitialized)stageUi.refresh(stageState()) } } }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun text(value: String,size: Int=14,bold: Boolean=false)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(Color.rgb(226,230,239)); setPadding(dp(6),dp(6),dp(6),dp(6)); if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD) }
    private fun button(label: String,action: ()->Unit)=StageTheme(this).secondary(label,action)
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
