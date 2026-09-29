package ai.joydurm.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import ai.joydurm.core.*
import ai.joydurm.input.ControllerHub
import ai.joydurm.render.DrumScene
import java.io.File
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.*

class MainActivity: ComponentActivity() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var engine: DrumEngine
    private lateinit var hub: ControllerHub
    private lateinit var audio: DrumAudio
    private lateinit var store: SettingsStore
    private lateinit var scene: DrumScene
    private lateinit var sceneHost: FrameLayout
    private lateinit var message: TextView
    private lateinit var monitor: TextView
    private lateinit var hatBar: ProgressBar
    private var bpm=100
    private var metronome=false
    private var resumed=false
    private var importDrum=Drum.SNARE
    private var arRequested=false
    private var arInstallRequested=false
    private var activeCalibrationDialog: AlertDialog?=null
    private var importModel=false
    private var hitCount=0
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(arRequested && granted[Manifest.permission.CAMERA]==true) enableAr()
        else if(arRequested) showStatus("摄像头权限未授予，可继续使用 3D 模式")
        arRequested=false; if(::hub.isInitialized)hub.refresh()
    }
    private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            val limit=if(importModel)20_000_000 else 1_000_000
            val target=File(filesDir,if(importModel)"imported.glb" else "custom-${audio.kit}-${importDrum.name}.wav")
            val temporary=File(filesDir,if(importModel)"pending.glb" else "pending.wav")
            runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { out ->
                    val buffer=ByteArray(8192); var total=0
                    while(true) { val count=input.read(buffer); if(count<0)break; total+=count; require(total<=limit){"文件过大"}; out.write(buffer,0,count) }
                } }
                if(importModel) scene.importModel(temporary) else WaveValidator.validate(temporary.readBytes())
                Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                if(importModel) { store.prefs.edit().putBoolean("customModel",true).remove("layout").apply(); store.saveScene(scene); showStatus("模型已加载") }
                else { audio.importWav(target,importDrum); store.prefs.edit().putString("wav-${audio.kit}-${importDrum.name}",target.path).apply(); showStatus("${importDrum.label} 的 WAV 已导入") }
            }.onFailure { showStatus("导入失败：${it.message}"); temporary.delete() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store=SettingsStore(this)
        audio=DrumAudio(this,::showStatus)
        audio.kit=store.prefs.getInt("kit",0).coerceIn(0,2); audio.volume=store.prefs.getFloat("volume",0.8f)
        engine=DrumEngine { event ->
            audio.play(event)
            main.post { if(isDestroyed)return@post; if(::scene.isInitialized) scene.hit(event); hitCount++; if(::message.isInitialized) message.text="${event.drum.label} · 力度 ${(event.velocity*127).toInt()}" }
        }
        store.load(engine)
        hub=ControllerHub(this,{ engine.process(it) },::showStatus)
        hub.onDeviceLost={ engine.deviceLost(it) }
        hub.onRemoteRole={ role,device -> synchronized(engine) {
            if(engine.roles[role]?.device==null && engine.roles.values.none { it.device==device }) engine.assign(role,device)
        } }
        buildUi(); buildScene(false)
        // Restore user samples after the built-in sample loader's serial work has begun.
        for(bank in 0..2) for(d in Drum.entries) store.prefs.getString("wav-$bank-${d.name}",null)?.let { path ->
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
        side.addView(button("重新归中") { Role.entries.forEach { engine.recenter(it) }; store.save(engine); showStatus("姿态已归中；手柄朝向前方，左脚平放") })
        side.addView(button("节拍器") { metronome=!metronome; main.removeCallbacks(tick); if(metronome) main.post(tick); showStatus(if(metronome)"节拍器 $bpm BPM" else "节拍器已关闭") })
        side.addView(text("触摸下方鼓垫也可演奏\n无需连接硬件",12))
        middle.addView(ScrollView(this).apply { addView(side) },LinearLayout.LayoutParams(dp(if(resources.configuration.screenWidthDp<600)130 else 190),-1))
        root.addView(middle,LinearLayout.LayoutParams(-1,0,1f))
        val pads=LinearLayout(this)
        Drum.entries.filter { it!=Drum.CHICK }.forEach { d -> pads.addView(button(d.label) { engine.trigger(d) },LinearLayout.LayoutParams(0,dp(54),1f)) }
        root.addView(HorizontalScrollView(this).apply { addView(pads,FrameLayout.LayoutParams(max(resources.displayMetrics.widthPixels-dp(24),dp(640)),dp(54))) })
        message=text("连接 → 绑定四肢 → 校准 → 演奏。AR 需要兼容手机。",12)
        root.addView(message); setContentView(root)
    }
    private fun buildScene(ar: Boolean) {
        if(::scene.isInitialized) { store.saveScene(scene); sceneHost.removeAllViews(); scene.destroy() }
        scene=DrumScene(this,ar,{ engine.trigger(it) },::showStatus)
        val custom=File(filesDir,"imported.glb")
        if(store.prefs.getBoolean("customModel",false) && custom.exists()) runCatching { scene.importModel(custom) }.onFailure { showStatus("自定义模型加载失败：${it.message}") }
        store.loadScene(scene); sceneHost.addView(scene.view,FrameLayout.LayoutParams(-1,-1))
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
    private fun devicesDialog() {
        val body=column()
        body.addView(text("原版 Switch Joy-Con · 四只手柄分别绑定左右手与左右脚。蓝牙配对由系统完成。",14))
        body.addView(button("打开蓝牙配对") {
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        body.addView(text(hub.bondedNames().joinToString("\n").ifBlank { "暂无已配对设备" },12))
        val list=text("",13); body.addView(list)
        fun refreshList() { hub.refresh(); list.text=hub.devices.values.joinToString("\n") { "${if(it.motion)"●" else "○"} ${it.name}\n${it.transport}${if(it.motion)"" else " · 无 IMU 数据"}" }.ifBlank { "未发现 Joy-Con；桥接收到数据后会自动出现" } }
        refreshList(); body.addView(button("刷新连接状态") { refreshList() })
        Role.entries.forEach { role -> body.addView(button("绑定 ${role.label}") {
            val all=hub.devices.values.filter { it.motion }.sortedBy { it.id }
            if(all.isEmpty()) { showStatus("暂无提供 IMU 的设备"); return@button }
            AlertDialog.Builder(this).setTitle(role.label).setItems(all.map { "${it.name} · ${it.transport}" }.toTypedArray()) { _,i -> engine.assign(role,all[i].id); store.save(engine) }.show()
        }) }
        body.addView(text("LAN 桥接（同一 Wi-Fi）",15,true))
        val port=field("UDP 端口",store.prefs.getInt("port",18185).toString()); body.addView(port)
        val token=field("桥接令牌",store.prefs.getString("token",null) ?: newToken()); body.addView(token)
        body.addView(button("复制令牌") { getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("JoyDurm token",token.text)); showStatus("令牌已复制") })
        body.addView(button("开启桥接监听") { runCatching {
            val p=port.text.toString().toInt(); val t=token.text.toString(); hub.listenBridge(p,t)
            store.prefs.edit().putInt("port",p).putString("token",t).putBoolean("bridge",true).apply()
        }.onFailure { showStatus(it.message ?: "参数错误") } })
        body.addView(text("高级：直连原始 HID。仅适用于已开放 /dev/hidraw 权限的开发设备；普通手机使用上方桥接。",12))
        val path=field("HID 节点","/dev/hidraw0"); body.addView(path)
        body.addView(button("打开 HID") { runCatching { hub.openHid(path.text.toString()); refreshList() }.onFailure { showStatus(it.message ?: "HID 打开失败") } })
        dialog("连接与角色绑定",body)
    }
    private fun calibrationDialog() {
        val body=column()
        body.addView(text("1. 静置校准：测量陀螺仪零偏。\n2. 朝前归中：双手朝向正前方，左脚平放。\n3. 指向鼓件：指向预期击打位置并绑定。\n姿态分区决定鼓件；Joy-Con 不提供绝对 XYZ。",14))
        Role.entries.forEach { role ->
            body.addView(text(role.label,17,true))
            body.addView(button("静置校准 · 3 秒") { calibrate(role) })
            body.addView(button("设置当前位置为零点") { engine.recenter(role); store.save(engine); showStatus("${role.label} 已归中") })
            body.addView(button("击打阈值 / 方向") { tuningDialog(role) })
            if(role==Role.LEFT_HAND || role==Role.RIGHT_HAND) body.addView(button("绑定一个鼓件方向") {
                val targets=Drum.entries.filter { it!=Drum.KICK && it!=Drum.CHICK }
                AlertDialog.Builder(this).setTitle("${role.label} 指向对应位置，然后点击鼓件").setItems(targets.map { it.label }.toTypedArray()) { _,i ->
                    runCatching { engine.bindTarget(role,targets[i]); store.save(engine); showStatus("已绑定 ${targets[i].label}") }.onFailure { showStatus(it.message ?: "绑定失败") }
                }.show()
            })
        }
        body.addView(button("恢复默认鼓件分区") { engine.resetTargets(); store.save(engine); showStatus("分区已重置") })
        dialog("校准与映射",body)
    }
    private fun calibrate(role: Role) {
        val s=engine.roles[role]!!
        if(s.latest==null || SystemClock.elapsedRealtimeNanos()-(s.latest?.timeNs ?: 0)>1_000_000_000) { showStatus("${role.label} 没有实时 IMU 数据"); return }
        activeCalibrationDialog?.dismiss()
        engine.startCalibration(role)
        val progress=text("保持手柄静止，正在采样…",18)
        val d=AlertDialog.Builder(this).setTitle(role.label).setView(progress).setNegativeButton("取消") { _,_ -> engine.cancelCalibration(role) }.create()
        val finish=Runnable {
            if(!d.isShowing) return@Runnable
            runCatching { engine.finishCalibration(role); store.save(engine) }.onSuccess { showStatus("${role.label} 零偏校准完成，请重新归中") }.onFailure { showStatus(it.message ?: "校准失败") }
            d.dismiss()
        }
        d.setOnDismissListener { main.removeCallbacks(finish); engine.cancelCalibration(role); if(activeCalibrationDialog===d)activeCalibrationDialog=null }
        activeCalibrationDialog=d
        d.show(); main.postDelayed(finish,3000)
    }
    private fun tuningDialog(role: Role) {
        val body=column(); val s=engine.roles[role]!!
        val threshold=field(if(role==Role.RIGHT_FOOT)"踩击阈值 m/s²" else "挥击阈值 rad/s",s.stroke.threshold.toString()); body.addView(threshold)
        val cooldown=field("防重复间隔 ms",(s.stroke.cooldownNs/1_000_000).toString()); body.addView(cooldown)
        val axis=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("X 轴","Y 轴","Z 轴")); setSelection(s.axis) }; body.addView(axis)
        val flip=CheckBox(this).apply { text="反转挥击方向"; isChecked=s.sign<0 }; body.addView(flip)
        val range=field("踩镲全开角度 °",Math.toDegrees(engine.hatRange).roundToInt().toString())
        val footFlip=CheckBox(this).apply { text="反转左脚抬起方向"; isChecked=engine.hatSign<0 }
        if(role==Role.LEFT_FOOT) { body.addView(range); body.addView(footFlip) }
        dialog("${role.label} · 参数",body) {
            runCatching {
                synchronized(engine) { s.stroke.threshold=threshold.text.toString().toDouble().coerceIn(0.2,30.0); s.stroke.cooldownNs=cooldown.text.toString().toLong().coerceIn(30,500)*1_000_000
                    s.axis=axis.selectedItemPosition; s.sign=if(flip.isChecked)-1.0 else 1.0
                    if(role==Role.LEFT_FOOT) { engine.hatRange=Math.toRadians(range.text.toString().toDouble().coerceIn(6.0,85.0)); engine.hatSign=if(footFlip.isChecked)-1.0 else 1.0 }
                }; store.save(engine)
            }.onFailure { showStatus("参数不是有效数字") }
        }
    }
    private fun layoutDialog() {
        val body=column(); scene.editMode=true
        body.addView(text("AR 中首次点击地面摆放；尺寸和各鼓件位置以米为单位。鼓件位置与 IMU 分区分别校准。",14))
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
            scene.kitScale=scale.text.toString().toFloat().coerceIn(0.3f,2f); scene.kitYaw=yaw.text.toString().toFloat().coerceIn(-360f,360f)
            scene.setPiecePosition(drums[spinner.selectedItemPosition],x.text.toString().toFloat().coerceIn(-3f,3f),y.text.toString().toFloat().coerceIn(0f,3f),z.text.toString().toFloat().coerceIn(-3f,3f)); store.saveScene(scene)
        }.onFailure { showStatus("布局参数无效") } })
        body.addView(button("重新选择 AR 地面位置") { scene.resetPlacement(); showStatus("点击地面重新摆放") })
        body.addView(button("导入 GLB 模型") { importModel=true; importer.launch(arrayOf("model/gltf-binary","application/octet-stream")) })
        body.addView(text("GLB 的八个鼓件需独立命名，详见 README。Sketchfab 模型须保留原作者署名。",12))
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
        body.addView(button("设置 BPM") { bpm=tempo.text.toString().toIntOrNull()?.coerceIn(30,240) ?: 100 })
        body.addView(button("导入单鼓 WAV 音色") {
            AlertDialog.Builder(this).setTitle("选择要替换的音色").setItems(Drum.entries.map { it.label }.toTypedArray()) { _,i -> importDrum=Drum.entries[i]; importModel=false; importer.launch(arrayOf("audio/wav","audio/x-wav","application/octet-stream")) }.show()
        })
        body.addView(text("内置为原创合成音色，离线可用；真实鼓采样可通过 WAV 导入。先用手机扬声器或有线耳机验证延迟。",13))
        dialog("音色与节拍器",body)
    }
    private val tick=object: Runnable { override fun run() { if(metronome && resumed) { audio.click(); main.postDelayed(this,60_000L/bpm) } } }
    private val update=object: Runnable { override fun run() {
        if(!resumed) return
        val now=SystemClock.elapsedRealtimeNanos()
        monitor.text=Role.entries.joinToString("\n\n") { r -> val s=engine.roles[r]!!; val live=now-(s.latest?.timeNs ?: 0)<500_000_000
            "${if(live)"●" else "○"} ${r.label}\n${if(live)"实时数据" else if(s.device!=null)"等待数据" else "未绑定"}${if(s.calibration!=null)" · 已校准" else ""}" }
        hatBar.progress=(engine.openness*100).toInt(); scene.openness=engine.openness
        main.postDelayed(this,100)
    } }
    override fun onResume() {
        super.onResume(); resumed=true; hub.start()
        if(store.prefs.getBoolean("bridge",false)) runCatching { hub.listenBridge(store.prefs.getInt("port",18185),store.prefs.getString("token","")!!) }.onFailure { showStatus("桥接启动失败：${it.message}") }
        main.post(update); if(metronome) main.post(tick)
        if(arInstallRequested && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) enableAr()
    }
    override fun onPause() { activeCalibrationDialog?.dismiss(); resumed=false; hub.stop(); main.removeCallbacks(update); main.removeCallbacks(tick); store.save(engine); if(::scene.isInitialized)store.saveScene(scene); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); hub.stop(); audio.close(); scene.destroy(); super.onDestroy() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        val drum=when(keyCode) { KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_BUTTON_A -> Drum.SNARE; KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_BUTTON_B -> Drum.KICK; KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_BUTTON_X -> Drum.HAT; KeyEvent.KEYCODE_F,KeyEvent.KEYCODE_BUTTON_Y -> Drum.CRASH; else -> null }
        if(drum!=null && event.repeatCount==0) { engine.trigger(drum); return true }; return super.onKeyDown(keyCode,event)
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
        return AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(if(onSave==null)"完成" else "保存") { _,_ -> onSave?.invoke() }.create().also { it.show(); it.window?.setLayout((resources.displayMetrics.widthPixels*if(resources.configuration.screenWidthDp<600)0.95 else 0.65).toInt(),(resources.displayMetrics.heightPixels*0.85).toInt()) }
    }
    private fun newToken(): String { val b=ByteArray(16); SecureRandom().nextBytes(b); return b.joinToString(""){"%02x".format(it)} }
}
