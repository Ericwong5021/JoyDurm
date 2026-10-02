package ai.joydurm.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import ai.joydurm.core.Drum
import ai.joydurm.core.Role
import java.util.Locale
import kotlin.math.roundToInt

/** Native settings pages. The activity owns all mutations and transport work. */
class StageSettings(
    private val context: Context,
    private val theme: StageTheme,
    private val actions: StageActions,
) {
    private var currentState: StageState? = null
    private var kitTab = 0
    private val bindings = mutableListOf<(StageState) -> Unit>()
    private val tabBindings = mutableListOf<(StageState) -> Unit>()
    private val trackedSliders = mutableSetOf<SeekBar>()
    private val editors = mutableListOf<EditText>()
    private val renderedKitThumbnail: Bitmap by lazy {
        context.assets.open("ui/kit-preview.png").use { BitmapFactory.decodeStream(it) }
    }
    private val kitNames = listOf("Studio", "Electronic", "Lo-fi")
    private val kitDetails = listOf("原创合成鼓 · 清晰均衡", "电子音色 · 紧凑明亮", "柔和音色 · 温暖质感")
    @Suppress("DEPRECATION")
    private val installedVersion: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "版本 ${info.versionName} · 构建 ${info.versionCode}"
    }.getOrDefault("版本信息暂不可用")

    fun build(page: StagePage, state: StageState): View {
        require(page in listOf(StagePage.KIT, StagePage.DEVICES, StagePage.SETTINGS))
        dispose()
        currentState = state
        val root = theme.column().apply {
            setBackgroundColor(StageTheme.BG)
            tag = "screen-${page.name}"
            contentDescription = "screen-${page.name}"
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val header = theme.row().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(theme.icon("back", "返回上一页") { actions.back() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(theme.text(when (page) {
            StagePage.KIT -> "鼓组设置"
            StagePage.DEVICES -> "设备管理"
            else -> "应用设置"
        }, 22, true).apply { gravity = Gravity.CENTER; minHeight=dp(48) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(theme.icon("play", "返回演奏") { actions.navigate(StagePage.PLAY) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        val content = theme.column().apply { setPadding(dp(20), dp(8), dp(20), dp(24)) }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        when (page) {
            StagePage.KIT -> buildKit(content)
            StagePage.DEVICES -> buildDevices(content)
            StagePage.SETTINGS -> buildSettings(content, state)
            else -> Unit
        }
        val status = theme.text("", 14, color = StageTheme.MUTED)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        bindings.add { s ->
            updateText(status, s.lastStatus)
            status.visibility = if (s.lastStatus.isBlank()) View.GONE else View.VISIBLE
        }
        add(content, status, 16)
        val navigation = theme.row().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        listOf(StagePage.KIT to "鼓组", StagePage.DEVICES to "设备", StagePage.SETTINGS to "设置").forEachIndexed { index, item ->
            val button = theme.secondary(item.second) { actions.navigate(item.first) }
            button.contentDescription = "打开${item.second}页面"
            button.isSelected = item.first == page
            button.setTextColor(if (item.first == page) StageTheme.BLUE else StageTheme.MUTED)
            navigation.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        root.addView(navigation)
        refresh(state)
        return root
    }

    fun refresh(state: StageState) {
        currentState = state
        bindings.forEach { it(state) }
        tabBindings.forEach { it(state) }
    }

    fun dispose() {
        bindings.clear()
        tabBindings.clear()
        trackedSliders.clear()
        editors.clear()
        currentState = null
    }

    private fun buildKit(body: LinearLayout) {
        add(body, theme.text("选择音色，让每次击打都有自己的声音。", 14, color = StageTheme.MUTED))
        val tabs = theme.row()
        val panel = theme.column()
        val buttons = mutableListOf<Button>()
        val titles = listOf("风格", "位置", "声音")
        fun showTab(index: Int) {
            kitTab = index
            tabBindings.clear()
            panel.removeAllViews()
            buttons.forEachIndexed { i, button ->
                button.isSelected = i == index
                button.setTextColor(if (i == index) StageTheme.TEXT else StageTheme.MUTED)
                button.backgroundTintList = ColorStateList.valueOf(if (i == index) StageTheme.BLUE else StageTheme.PANEL)
            }
            when (index) {
                0 -> buildKitStyles(panel)
                1 -> buildKitPosition(panel)
                2 -> buildKitSound(panel)
            }
            currentState?.let { s -> tabBindings.forEach { it(s) } }
        }
        titles.forEachIndexed { index, label ->
            val button = theme.secondary(label) { showTab(index) }
            button.contentDescription = "鼓组${label}标签"
            buttons.add(button)
            tabs.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        add(body, tabs, 20)
        add(body, panel, 16)
        showTab(kitTab)
    }

    private fun buildKitStyles(body: LinearLayout) {
        kitNames.forEachIndexed { index, name ->
            val card = theme.card()
            val row = theme.row()
            row.addView(ImageView(context).apply { setImageBitmap(renderedKitThumbnail); scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="当前鼓组实际渲染预览" }, LinearLayout.LayoutParams(dp(76), dp(70)).apply { marginEnd = dp(12) })
            val labels = theme.column()
            labels.addView(theme.text(name, 19, true))
            add(labels, theme.text(kitDetails[index], 14, color = StageTheme.MUTED), 4)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = theme.text("", 14, true, StageTheme.GREEN)
            add(card, row)
            add(card, check, 10)
            val select = theme.secondary("选择 $name") { actions.setKit(index) }
            add(card, select, 10)
            add(card, theme.secondary("试听 $name 军鼓") {
                actions.setKit(index)
                actions.trigger(Drum.SNARE)
            }, 8)
            tabBindings.add { state ->
                val selected = state.kitIndex == index
                updateText(check, if (selected) "✓ 当前音库" else "内置音库")
                check.setTextColor(if (selected) StageTheme.GREEN else StageTheme.MUTED)
                select.isEnabled = !selected
                select.isSelected = selected
                updateText(select, if (selected) "已选择 $name" else "选择 $name")
                card.background = outline(if (selected) StageTheme.BLUE else StageTheme.BORDER)
            }
            add(body, card, if (index == 0) 0 else 12)
        }
        add(body, theme.text("三套内置音库均为原创合成音色，可在「声音」中导入单鼓 WAV。", 14, color = StageTheme.MUTED), 16)
    }

    private fun buildKitPosition(body: LinearLayout) {
        val card = theme.card()
        add(card, theme.text("你的鼓组位置", 20, true))
        val placement = theme.text("", 14, color = StageTheme.MUTED)
        add(card, placement, 10)
        tabBindings.add { state ->
            updateText(placement, if (state.arEnabled) {
                if (state.placed) "AR 已启用 · 鼓组已放置" else "AR 已启用 · 等待选择地面"
            } else "使用虚拟场景 · 可编辑鼓件位置、旋转与尺寸")
        }
        add(card, theme.primary("编辑鼓组布局") { actions.editLayout() }, 16)
        add(card, theme.secondary("重新放置鼓组") { actions.resetPlacement() }, 8)
        add(card, theme.secondary("进入放置引导") { actions.navigate(StagePage.PLACE) }, 8)
        add(body, card)
        val custom = theme.card()
        add(custom, theme.text("自定义外观", 18, true))
        add(custom, theme.text("导入含独立命名鼓件的 GLB 模型。模型外观与内置音库分别设置。", 14, color = StageTheme.MUTED), 8)
        add(custom, theme.secondary("导入 GLB 模型") { actions.importModel() }, 16)
        add(body, custom, 12)
    }

    private fun buildKitSound(body: LinearLayout) {
        val card = theme.card()
        val current = theme.text("", 20, true)
        add(card, current)
        tabBindings.add { s -> updateText(current, "当前音库 · ${kitNames.getOrElse(s.kitIndex) { "Studio" }}") }
        add(card, theme.text("点击鼓件试听。踩镲音色随当前开合状态变化。", 14, color = StageTheme.MUTED), 8)
        val drums = listOf(Drum.KICK, Drum.SNARE, Drum.TOM1, Drum.TOM2, Drum.FLOOR, Drum.HAT, Drum.CRASH, Drum.RIDE)
        drums.chunked(2).forEach { pair ->
            val row = theme.row()
            pair.forEachIndexed { index, drum ->
                row.addView(theme.secondary("试听${drum.label}") { actions.trigger(drum) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            add(card, row, 10)
        }
        add(body, card)
        add(body, theme.primary("导入单鼓 WAV 音色") { actions.importSound() }, 16)
        add(body, theme.secondary("导出音频提交延迟记录") { actions.exportAudioTrace() }, 8)
        add(body, theme.text("提交记录反映软件时序；声学起音延迟未实测。", 14, color = StageTheme.MUTED), 12)
    }

    private fun buildDevices(body: LinearLayout) {
        val count = theme.text("", 14, color = StageTheme.MUTED)
        add(body, count)
        bindings.add { s -> updateText(count, "${s.motionDeviceCount} 个运动输入设备 · 4 个演奏角色") }
        Role.entries.forEach { role ->
            val card = theme.card()
            val row = theme.row()
            row.addView(theme.roleArt(role), LinearLayout.LayoutParams(dp(44), dp(68)).apply { marginEnd = dp(14) })
            val details = theme.column()
            add(details, theme.text(role.label, 18, true))
            val name = theme.text("", 14, color = StageTheme.MUTED)
            val signal = theme.text("", 14)
            add(details, name, 4)
            add(details, signal, 5)
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            add(card, row)
            add(card, theme.text("电量未提供", 14, color = StageTheme.MUTED), 8)
            val telemetry = theme.text("", 14, color = StageTheme.MUTED)
            add(card, telemetry, 8)
            val calibration = theme.text("", 14, color = StageTheme.MUTED)
            add(card, calibration, 4)
            val issue = theme.text("", 14, color = StageTheme.RED)
            add(card, issue, 4)
            val assign = theme.secondary("重新分配${shortRole(role)}") { actions.assign(role) }
            add(card, assign, 12)
            val calibrationButton = theme.secondary("校准${shortRole(role)}") { actions.calibrate(role) }
            add(card, calibrationButton, 8)
            val recenter = theme.secondary("归中${shortRole(role)}") { actions.recenter(role) }
            add(card, recenter, 8)
            add(card, theme.secondary(if (role == Role.LEFT_FOOT) "调整踩镲开合" else "调整${shortRole(role)}参数") {
                if (role == Role.LEFT_FOOT) actions.navigate(StagePage.HAT) else actions.tune(role)
            }, 8)
            bindings.add { s ->
                val device = s.role(role)
                updateText(name, (if (device.bound) device.deviceName ?: "已绑定输入设备" else "尚未绑定设备") + device.deviceId?.let { "\n$it" }.orEmpty())
                updateText(signal, when {
                    !device.bound -> "○ 未绑定"
                    device.live -> device.connectionStatus?.let { "$it\n" }.orEmpty() + "● 实时数据"
                    else -> (device.connectionStatus ?: "○ 已绑定输入") + "\n○ IMU 未就绪 · 等待真实运动数据"
                })
                signal.setTextColor(if (device.live && device.bound) StageTheme.GREEN else StageTheme.MUTED)
                updateText(telemetry, "累计 ${device.samples} 个样本 · ${device.ageMs?.let { "最新样本 ${it.coerceAtLeast(0)} ms 前" } ?: "尚无样本"}")
                val needsHat = role == Role.LEFT_FOOT && !s.hatCalibrated
                updateText(calibration, listOf(
                    if (device.calibrated) "零偏已校准" else "零偏未校准",
                    if (device.needsRecenter) "需要归中" else "已归中",
                    if (role == Role.LEFT_FOOT) { if (needsHat) "开合未标定" else "开合已标定" } else "${device.targetCount} 个鼓件映射",
                ).joinToString(" · "))
                updateText(issue, device.error.orEmpty())
                issue.visibility = if (device.error.isNullOrBlank()) View.GONE else View.VISIBLE
                calibrationButton.isEnabled = device.bound && device.live
                recenter.isEnabled = device.bound && device.live && device.calibrated
                updateText(assign, if (device.bound) "重新分配${shortRole(role)}" else "分配${shortRole(role)}设备")
            }
            add(body, card, 12)
        }
        add(body, theme.text("连接与诊断", 16, true), 22)
        add(body, theme.secondary("配对蓝牙设备") { actions.pairBluetooth() }, 12)
        add(body, theme.secondary("连接详情") { actions.connectionDetails() }, 8)
        add(body, theme.secondary("重新分配角色") { actions.navigate(StagePage.ROLES) }, 8)
        add(body, theme.secondary("测试输入") { actions.navigate(StagePage.SOUND_CHECK) }, 8)
        add(body, theme.secondary("导出 IMU 记录") { actions.exportImu() }, 8)
        add(body, theme.secondary("设备能力报告") { actions.capabilityReport() }, 8)
        val firmware = theme.secondary("手柄固件更新 · 暂不支持") {}.apply { isEnabled = false }
        add(body, firmware, 12)
        add(body, theme.text("应用未提供手柄固件升级，也未获取电量遥测。", 14, color = StageTheme.MUTED), 8)
    }

    private fun buildSettings(body: LinearLayout, state: StageState) {
        section(body, "音频")
        val audio = theme.card()
        val output = theme.text("", 14, color = StageTheme.MUTED)
        add(audio, theme.text("输出设备", 16, true))
        add(audio, output, 8)
        bindings.add { s -> updateText(output, s.outputDescription) }
        add(audio, theme.secondary("打开系统音频设置") { actions.systemAudioSettings() }, 12)
        add(audio, theme.text("声学延迟 · 未实测", 16, true), 18)
        add(audio, theme.text("先用手机扬声器或有线耳机验证。蓝牙音频时延取决于系统和设备。", 14, color = StageTheme.MUTED), 8)
        val volumeLabel = theme.text("", 16, true)
        add(audio, volumeLabel, 18)
        val volume = slider(100, "主音量") { value ->
            updateText(volumeLabel, "主音量 · $value%")
            actions.setVolume(value)
        }
        add(audio, volume, 4)
        bindings.add { s ->
            if (volume !in trackedSliders) {
                updateText(volumeLabel, "主音量 · ${s.volumePercent}%")
                if (volume.progress != s.volumePercent) volume.progress = s.volumePercent.coerceIn(0, 100)
            }
        }
        add(body, audio, 10)
        section(body, "节拍器")
        val beat = theme.card()
        val tempo = theme.field("BPM 30–240", state.bpm.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            contentDescription = "节拍器 BPM"
            setSingleLine(true)
        }
        editors.add(tempo)
        add(beat, theme.text("速度 · BPM", 16, true))
        add(beat, tempo, 10)
        add(beat, theme.secondary("设置 BPM") {
            val value = tempo.text.toString().trim().toIntOrNull()
            if (value == null || value !in 30..240) tempo.error = "请输入 30–240 的整数"
            else {
                tempo.error = null
                tempo.clearFocus()
                actions.setTempo(value)
            }
        }, 8)
        val metronome = theme.secondary("", actions::toggleMetronome)
        add(beat, metronome, 8)
        bindings.add { s ->
            if (!tempo.hasFocus() && tempo.text.toString() != s.bpm.toString()) tempo.setText(s.bpm.toString())
            updateText(metronome, if (s.metronome) "停止节拍器" else "开启节拍器")
            metronome.isSelected = s.metronome
        }
        add(body, beat, 10)
        section(body, "击打灵敏度")
        add(body, theme.text("阈值越低，轻击越容易触发；单位随角色而不同。", 14, color = StageTheme.MUTED), 8)
        listOf(Role.LEFT_HAND, Role.RIGHT_HAND, Role.RIGHT_FOOT).forEach { role ->
            val card = theme.card()
            val unit = if (role == Role.RIGHT_FOOT) "m/s²" else "rad/s"
            val title = theme.text("", 16, true)
            add(card, title)
            val seek = slider(298, "${shortRole(role)}阈值，0.2 至 30 $unit") { progress ->
                val value = 0.2 + progress / 10.0
                updateText(title, "${shortRole(role)}阈值 · ${decimal(value)} $unit")
                actions.setSensitivity(role, value)
            }
            add(card, seek, 6)
            add(card, theme.text("可调范围 0.2–30 $unit", 14, color = StageTheme.MUTED), 4)
            add(card, theme.secondary(if (role == Role.RIGHT_FOOT) "调整下踩轴与方向" else "调整${shortRole(role)}轴与方向") { actions.tune(role) }, 12)
            bindings.add { s ->
                if (seek !in trackedSliders) {
                    val threshold = s.role(role).threshold.coerceIn(0.2, 30.0)
                    updateText(title, "${shortRole(role)}阈值 · ${decimal(threshold)} $unit")
                    val progress = ((threshold - 0.2) * 10).roundToInt()
                    if (seek.progress != progress) seek.progress = progress
                }
            }
            add(body, card, 12)
        }
        val hat = theme.card()
        add(hat, theme.text("左脚 · 踩镲开合", 16, true))
        add(hat, theme.text("踩镲用闭合和全开两个姿态识别开合，不使用挥击阈值。", 14, color = StageTheme.MUTED), 8)
        add(hat, theme.secondary("校准踩镲开合") { actions.navigate(StagePage.HAT) }, 12)
        add(hat, theme.secondary("打开高级校准与映射") { actions.openLegacyCalibration() }, 8)
        add(body, hat, 12)
        section(body, "通用")
        val general = theme.card()
        add(general, theme.text("语言 · 中文", 16, true))
        add(general, theme.text("当前界面使用中文。", 14, color = StageTheme.MUTED), 8)
        add(general, theme.text("关于 JoyDrum", 18, true), 20)
        add(general, theme.text(installedVersion, 14, color = StageTheme.MUTED), 8)
        add(general, theme.text("通过运动输入演奏鼓组。六轴 IMU 提供姿态估计，不能测量自由 XYZ 位置；位置与映射需手动设置。", 14, color = StageTheme.MUTED), 10)
        add(general, theme.secondary("查看设备能力与限制") { actions.capabilityReport() }, 12)
        add(general, theme.secondary("REC 录音 · 暂不支持") {}.apply { isEnabled = false }, 12)
        add(general, theme.text("当前可导出音频提交记录和 IMU 数据，尚不支持录制或导出演奏音频。", 14, color = StageTheme.MUTED), 8)
        add(body, general, 10)
    }

    private fun slider(maximum: Int, description: String, change: (Int) -> Unit): SeekBar = SeekBar(context).apply {
        max = maximum
        contentDescription = description
        minimumHeight = dp(48)
        progressTintList = ColorStateList.valueOf(StageTheme.BLUE)
        thumbTintList = ColorStateList.valueOf(StageTheme.TEXT)
        progressBackgroundTintList = ColorStateList.valueOf(StageTheme.BORDER)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) change(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { bar?.let { trackedSliders.add(it) } }
            override fun onStopTrackingTouch(bar: SeekBar?) { bar?.let { trackedSliders.remove(it) } }
        })
    }

    private fun section(body: LinearLayout, label: String) { add(body, theme.text(label, 17, true), if (body.childCount == 0) 0 else 22) }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }
    private fun updateText(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
    private fun dp(value: Int) = theme.dp(value)
    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun shortRole(role: Role) = when (role) {
        Role.LEFT_HAND -> "左手"
        Role.RIGHT_HAND -> "右手"
        Role.LEFT_FOOT -> "左脚"
        Role.RIGHT_FOOT -> "右脚"
    }
    private fun outline(border: Int) = GradientDrawable().apply {
        setColor(StageTheme.PANEL)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), border)
    }

}
