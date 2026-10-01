package ai.joydurm.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ai.joydurm.core.Drum
import ai.joydurm.core.Role
import java.util.Locale

/** Native setup controls. Transparent pages leave the real scene available for interaction. */
class SetupFlow(
    private val context: Context,
    private val theme: StageTheme,
    private val actions: StageActions,
) {
    private val updates = mutableListOf<(StageState) -> Unit>()
    private var root: View? = null

    fun build(page: StagePage, state: StageState): View {
        dispose()
        require(page in setupPages) { "SetupFlow cannot render $page" }
        val view = when (page) {
            StagePage.WELCOME -> welcome()
            StagePage.CONNECT -> connection()
            StagePage.ROLES -> roles()
            StagePage.HANDS -> hands()
            StagePage.HAT -> hat()
            StagePage.PLACE -> placement()
            StagePage.SOUND_CHECK -> soundCheck()
            else -> error("Unsupported setup page")
        }
        view.tag = "screen-${page.name}"
        view.contentDescription = "screen-${page.name}"
        root = view
        refresh(state)
        return view
    }

    fun refresh(state: StageState) {
        updates.forEach { it(state) }
    }

    fun dispose() {
        updates.clear()
        root = null
    }

    private fun welcome(): View {
        val top = theme.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(14), dp(6), dp(16))
            addView(theme.icon("back", "返回", actions::back), LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.START })
            addView(theme.text("JoyDrum", 52, true).apply { gravity=Gravity.CENTER })
            addView(theme.text("拿起手柄，世界就是你的鼓台", 16, color = StageTheme.MUTED).apply { gravity = Gravity.CENTER }, full())
        }
        val bottom = theme.column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(theme.text("把每一次挥动，变成节奏", 22, true), full())
            addView(theme.text("连接、校准，然后开始你的演奏。", 14, color = StageTheme.MUTED), spaced())
            addView(primary("开始设置", "start-setup") { actions.navigate(StagePage.CONNECT) }, spaced(16))
            addView(secondary("先用触屏试奏", "skip-to-touch") { actions.navigate(StagePage.SOUND_CHECK) }, spaced(8))
            addView(status(), spaced(8))
        }
        return overlay(top, bottom)
    }

    private fun connection(): View = scrolling(StagePage.CONNECT, "连接你的手柄", "四个位置，一套属于你的鼓组") { body ->
        val count = theme.text("", 20, true, StageTheme.BLUE)
        body.addView(count, spaced(18))
        updates += { state -> count.text = "运动手柄 ${state.motionDeviceCount} / 4" }
        roleGrid(body, showAssignment = false)
        body.addView(theme.text("只有收到新鲜的 IMU 数据，才会显示已连接。普通蓝牙配对不代表运动输入可用。", 13, color = StageTheme.MUTED), spaced(12))
        body.addView(primary("打开蓝牙配对", "pair-bluetooth", actions::pairBluetooth), spaced(18))
        body.addView(secondary("连接方式与诊断", "connection-details", actions::connectionDetails), spaced(8))
        body.addView(status(), spaced(10))
        body.addView(primary("继续 · 分配角色", "continue-CONNECT") { actions.navigate(StagePage.ROLES) }, spaced(18))
        body.addView(skip(), spaced(8))
    }

    private fun roles(): View = scrolling(StagePage.ROLES, "分配演奏角色", "每只手柄，都有自己的节奏") { body ->
        roleGrid(body, showAssignment = true)
        body.addView(theme.text("点击卡片选择实际输入设备。同一设备只能属于一个角色；左右脚分别控制踩镲和地鼓。", 13, color = StageTheme.MUTED), spaced(14))
        body.addView(status(), spaced(10))
        body.addView(primary("继续 · 校准双手", "continue-ROLES") { actions.navigate(StagePage.HANDS) }, spaced(20))
        body.addView(skip(), spaced(8))
    }

    private fun hands(): View = scrolling(StagePage.HANDS, "校准你的双手", "先静置，再归中，最后绑定击打方向") { body ->
        body.addView(theme.text("将手柄放稳，保持静止 3 秒。静置校准完成后，以自然握持姿势归中。", 14, color = StageTheme.MUTED), spaced(16))
        val cards = theme.row()
        listOf(Role.LEFT_HAND, Role.RIGHT_HAND).forEachIndexed { index, role ->
            val card = theme.card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(16), dp(12), dp(14))
                addView(theme.roleArt(role), LinearLayout.LayoutParams(dp(66), dp(138)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(theme.text(role.label, 18, true), spaced(12))
                val detail = theme.text("", 12, color = StageTheme.MUTED)
                addView(detail, spaced(8))
                updates += { state ->
                    val item = state.role(role)
                    detail.text = when {
                        !item.live -> "等待运动输入"
                        !item.calibrated -> "尚未静置校准"
                        item.needsRecenter -> "已校准 · 需要归中"
                        else -> "已归中 · ${item.targetCount} 个方向"
                    }
                    detail.setTextColor(if (item.live && item.calibrated && !item.needsRecenter) StageTheme.GREEN else StageTheme.MUTED)
                }
                val calibrate = primary("静置 3 秒校准", "calibrate-${role.name}") { actions.calibrate(role) }
                addView(calibrate, spaced(14))
                val recenter = secondary("以当前姿势归中", "recenter-${role.name}") { actions.recenter(role) }
                addView(recenter, spaced(8))
                val bind = secondary("绑定击打方向", "bind-target-${role.name}") { actions.bindTarget(role) }
                addView(bind, spaced(8))
                val tune = secondary("调整击打阈值", "tune-${role.name}") { actions.tune(role) }
                addView(tune, spaced(8))
                updates += { state ->
                    val item = state.role(role)
                    calibrate.isEnabled = item.live
                    recenter.isEnabled = item.live && item.calibrated
                    bind.isEnabled = item.live && item.calibrated && !item.needsRecenter
                    tune.isEnabled = item.bound
                }
            }
            cards.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index == 0) marginEnd = dp(10)
            })
        }
        body.addView(cards, spaced(18))
        body.addView(theme.text("击打阈值由你手动调整。方向绑定用于选择鼓件，静置校准不会自动训练灵敏度。", 13, color = StageTheme.MUTED), spaced(14))
        body.addView(status(), spaced(10))
        body.addView(primary("继续 · 校准踩镲", "continue-HANDS") { actions.navigate(StagePage.HAT) }, spaced(18))
        body.addView(skip(), spaced(8))
    }

    private fun hat(): View = scrolling(StagePage.HAT, "校准踩镲", "用脚的自然姿势，设置开合范围") { body ->
        val foot = theme.card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(theme.roleArt(Role.LEFT_FOOT), LinearLayout.LayoutParams(dp(58), dp(108)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(theme.text("左脚 · 踩镲", 18, true), spaced(10))
            val live = theme.text("", 13, color = StageTheme.MUTED)
            addView(live, spaced(6))
            updates += { state -> live.text = connectionText(state.role(Role.LEFT_FOOT)) }
        }
        body.addView(foot, spaced(16))
        val calibration = secondary("静置 3 秒校准左脚", "calibrate-LEFT_FOOT") { actions.calibrate(Role.LEFT_FOOT) }
        body.addView(calibration, spaced(12))
        val recenter = secondary("以当前脚姿归中", "recenter-LEFT_FOOT") { actions.recenter(Role.LEFT_FOOT) }
        body.addView(recenter, spaced(8))
        val closed = primary("1 · 脚掌放平，记录闭合", "capture-hat-closed", actions::captureHatClosed)
        val open = primary("2 · 自然抬脚，记录张开", "capture-hat-open", actions::captureHatOpen)
        body.addView(closed, spaced(18))
        val captured = theme.text("", 13, color = StageTheme.MUTED)
        body.addView(captured, spaced(8))
        body.addView(open, spaced(14))
        val completed = theme.text("", 13, color = StageTheme.MUTED)
        body.addView(completed, spaced(8))
        updates += { state ->
            val footState = state.role(Role.LEFT_FOOT)
            val ready = footState.live && footState.calibrated && !footState.needsRecenter
            calibration.isEnabled = footState.live
            recenter.isEnabled = footState.live && footState.calibrated
            closed.isEnabled = ready
            open.isEnabled = ready && state.hatClosedCaptured
            captured.text = if (state.hatClosedCaptured) "✓ 已记录闭合姿势" else "尚未记录闭合姿势"
            captured.setTextColor(if (state.hatClosedCaptured) StageTheme.GREEN else StageTheme.MUTED)
            completed.text = if (state.hatCalibrated) "✓ 开合范围已标定 · ${String.format(Locale.ROOT, "%.1f", state.hatRangeDegrees)}°" else "尚未完成开合标定"
            completed.setTextColor(if (state.hatCalibrated) StageTheme.GREEN else StageTheme.MUTED)
        }
        body.addView(openness(), spaced(20))
        body.addView(secondary("敲一下踩镲试听", "test-hat") { actions.trigger(Drum.HAT) }, spaced(14))
        body.addView(theme.text("需要重新标定时，重新记录闭合与张开姿势；归中后也请重做开合标定。", 13, color = StageTheme.MUTED), spaced(12))
        body.addView(status(), spaced(10))
        body.addView(primary("继续 · 放置鼓组", "continue-HAT") { actions.navigate(StagePage.PLACE) }, spaced(18))
        body.addView(skip(), spaced(8))
    }

    private fun placement(): View {
        val top = header(StagePage.PLACE, "放置你的鼓组", "让房间，成为你的舞台")
        val bottom = theme.column().apply {
            setPadding(dp(18), dp(14), dp(18), dp(20))
            val mode = theme.text("", 14, true)
            addView(mode, full())
            val ar = secondary("AR 相机", "toggle-ar", actions::toggleAr)
            addView(ar, spaced(10))
            updates += { state ->
                ar.text = if (state.arEnabled) "AR 相机 · 关闭" else "AR 相机 · 开启"
                mode.text = when {
                    state.arEnabled && state.placed -> "已放置 · 可继续调整鼓组"
                    state.arEnabled -> "移动相机寻找地面，再点击场景放置"
                    else -> "三维舞台 · 无需相机也能演奏"
                }
            }
            val tools = theme.row()
            tools.addView(secondary("移动 / 旋转 / 缩放", "edit-layout", actions::editLayout), weighted(end = 8))
            tools.addView(secondary("重新放置", "reset-placement", actions::resetPlacement), weighted())
            addView(tools, spaced(8))
            addView(theme.text("AR 相机跟踪地面与鼓组位置；手柄使用运动方向击鼓，不提供空间 XYZ 定位。设备不支持 AR 时可使用三维舞台。", 12, color = StageTheme.MUTED), spaced(8))
            addView(status(), spaced(8))
            addView(primary("确认位置 · 测试声音", "continue-PLACE") { actions.navigate(StagePage.SOUND_CHECK) }, spaced(12))
        }
        return overlay(top, bottom)
    }

    private fun soundCheck(): View {
        val top = header(StagePage.SOUND_CHECK, "声音测试", "点击每个鼓件，听听你的第一拍")
        val bottom = theme.column().apply {
            setPadding(dp(18), dp(14), dp(18), dp(20))
            val drums = listOf(Drum.CRASH, Drum.RIDE, Drum.TOM1, Drum.TOM2, Drum.HAT, Drum.SNARE, Drum.FLOOR, Drum.KICK)
            drums.chunked(4).forEach { line ->
                val row = theme.row()
                line.forEachIndexed { index, drum ->
                    val pad=secondary(drum.label, "pad-${drum.name}") { actions.trigger(drum) }.apply {
                        textSize=14f; setPadding(dp(4),dp(8),dp(4),dp(8))
                    }
                    row.addView(pad, weighted(if (index < 3) 6 else 0))
                }
                addView(row, spaced(8))
            }
            addView(openness(), spaced(12))
            addView(theme.text("没有声音？检查媒体音量与当前音频输出。", 12, color = StageTheme.MUTED), spaced(8))
            addView(secondary("打开系统音频设置", "sound-check-audio", actions::systemAudioSettings), spaced(8))
            addView(status(), spaced(8))
            addView(primary("已听到声音，开始演奏", "complete-onboarding", actions::completeOnboarding), spaced(12))
        }
        return overlay(top, bottom)
    }

    private fun roleGrid(body: LinearLayout, showAssignment: Boolean) {
        Role.entries.chunked(2).forEach { pair ->
            val row = theme.row()
            pair.forEachIndexed { index, role ->
                val card = theme.card().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                    addView(theme.roleArt(role), LinearLayout.LayoutParams(dp(48), dp(94)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    addView(theme.text(role.label, 15, true), spaced(10))
                    val detail = theme.text("", 12, color = StageTheme.MUTED)
                    detail.gravity = Gravity.CENTER
                    addView(detail, spaced(6))
                    updates += { state ->
                        val item = state.role(role)
                        detail.text = connectionText(item)
                        detail.setTextColor(if (item.live) StageTheme.GREEN else StageTheme.MUTED)
                    }
                    if (showAssignment) {
                        val assign = secondary("选择设备", "assign-${role.name}") { actions.assign(role) }
                        addView(assign, spaced(12))
                        updates += { state -> assign.text = state.role(role).deviceName?.let { "更换设备" } ?: "选择设备" }
                    }
                }
                row.addView(card, weighted(if (index == 0) 10 else 0))
            }
            body.addView(row, spaced(12))
        }
    }

    private fun connectionText(role: StageRole): String = when {
        role.live -> "已连接 ✓\n${role.deviceName ?: role.role.label}"
        role.bound -> "等待运动数据\n${role.deviceName ?: "已分配设备"}"
        else -> "未分配运动设备"
    }

    private fun openness(): View = theme.column().apply {
        val labels = theme.row()
        labels.addView(theme.text("闭合", 12, color = StageTheme.MUTED), weighted())
        val value = theme.text("", 12, true, StageTheme.GREEN)
        labels.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labels.addView(theme.text("张开", 12, color = StageTheme.MUTED).apply { gravity = Gravity.END }, weighted())
        addView(labels, full())
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(StageTheme.GREEN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(StageTheme.BORDER)
            contentDescription = "踩镲开合程度"
        }
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(6) })
        updates += { state ->
            val amount = (state.openness.coerceIn(0f, 1f) * 100).toInt()
            bar.progress = amount
            value.text = "$amount%"
        }
    }

    private fun status(): TextView = theme.text("", 12, color = StageTheme.MUTED).also { view ->
        view.contentDescription = "setup-status"
        updates += { state ->
            view.text = state.lastStatus
            view.visibility = if (state.lastStatus.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun skip(): Button = secondary("跳过设置，用触屏试奏", "skip-to-touch") { actions.navigate(StagePage.SOUND_CHECK) }

    private fun scrolling(page: StagePage, title: String, subtitle: String, content: (LinearLayout) -> Unit): View {
        val body = theme.column().apply {
            setPadding(dp(18), dp(8), dp(18), dp(28))
            addView(header(page, title, subtitle), full())
        }
        content(body)
        val footer=theme.column().apply { setPadding(dp(18),0,dp(18),dp(12)) }
        // Keep the next action reachable while dense controller details scroll.
        val next=body.getChildAt(body.childCount-2)
        val skip=body.getChildAt(body.childCount-1)
        body.removeView(next); body.removeView(skip)
        footer.addView(next,spaced(6)); footer.addView(skip,spaced(6))
        val scroll=ScrollView(context).apply {
            setBackgroundColor(StageTheme.BG)
            isFillViewport = false
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return theme.column().apply {
            setBackgroundColor(StageTheme.BG)
            addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            addView(footer,full())
        }
    }

    private fun header(page: StagePage, title: String, subtitle: String): LinearLayout = theme.column().apply {
        setPadding(dp(2), dp(14), dp(2), dp(10))
        val row = theme.row().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(theme.icon("back", "返回", actions::back), LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(theme.text(title, 23, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, full())
        addView(theme.text(subtitle, 13, color = StageTheme.MUTED).apply {
            gravity = Gravity.CENTER
        }, spaced(4))
        val steps = theme.row().apply { gravity = Gravity.CENTER }
        val current = setupPages.indexOf(page)
        repeat(7) { index ->
            steps.addView(theme.text(if (index <= current) "●" else "○", 12, color = if (index <= current) StageTheme.BLUE else StageTheme.BORDER), LinearLayout.LayoutParams(dp(21), dp(22)))
        }
        addView(steps, spaced(10))
    }

    private fun overlay(top: View, bottom: View): View = FrameLayout(context).apply {
        isClickable = false
        isFocusable = false
        addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
        })
        val controls = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Keep the middle of the scene interactive, including with enlarged system fonts.
                val available = MeasureSpec.getSize(heightMeasureSpec)
                val cap = if (available > 0) (available * 0.49f).toInt() else dp(360)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            setBackgroundColor(0xe6101822.toInt())
            clipToPadding = false
            addView(bottom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun primary(label: String, id: String, action: () -> Unit): Button = theme.primary(label, action).apply {
        contentDescription = id
        tag = id
    }

    private fun secondary(label: String, id: String, action: () -> Unit): Button = theme.secondary(label, action).apply {
        contentDescription = id
        tag = id
    }

    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun spaced(top: Int = 6) = full().apply { topMargin = dp(top) }
    private fun weighted(end: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(end) }
    private fun dp(value: Int) = theme.dp(value)

    companion object {
        private val setupPages = listOf(StagePage.WELCOME, StagePage.CONNECT, StagePage.ROLES, StagePage.HANDS, StagePage.HAT, StagePage.PLACE, StagePage.SOUND_CHECK)
    }
}
