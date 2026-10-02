package ai.joydurm.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import ai.joydurm.core.Drum
import ai.joydurm.core.Role
import java.util.ArrayDeque

/** Native navigation and overlays. The existing SceneView stays owned by MainActivity. */
class StageUi(context: Context, val sceneHost: FrameLayout, private val actions: StageActions) {
    private val theme=StageTheme(context)
    val root=FrameLayout(context).apply { setBackgroundColor(StageTheme.BG) }
    private val overlay=FrameLayout(context)
    private val setup=SetupFlow(context,theme,actions)
    private val settings=StageSettings(context,theme,actions)
    private val history=ArrayDeque<StagePage>()
    private val handler=Handler(Looper.getMainLooper())
    var page=StagePage.WELCOME; private set
    private var state: StageState?=null
    private var status: TextView?=null
    private var hatLabel: TextView?=null
    private var hatProgress: ProgressBar?=null
    private val roleLabels=mutableMapOf<Role,TextView>()
    private val pads=mutableMapOf<Drum,Button>()
    private var arButton: Button?=null
    private var selected: TextView?=null

    init {
        root.addView(sceneHost,FrameLayout.LayoutParams(-1,-1))
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener { view,insets ->
            view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
    }

    fun show(next: StagePage,newState: StageState,remember: Boolean=true) {
        if(remember && state!=null && next!=page)history.addLast(page)
        setup.dispose(); settings.dispose()
        handler.removeCallbacksAndMessages(null)
        roleLabels.clear(); pads.clear(); status=null; hatLabel=null; hatProgress=null; arButton=null; selected=null
        page=next; state=newState
        // Frame the welcome and test kit above their larger guidance controls.
        sceneHost.setPadding(0,0,0,if(next==StagePage.WELCOME || next==StagePage.SOUND_CHECK)theme.dp(150) else 0)
        overlay.removeAllViews()
        val content=when(next) {
            StagePage.PLAY -> performance(newState)
            StagePage.KIT,StagePage.DEVICES,StagePage.SETTINGS -> settings.build(next,newState)
            else -> setup.build(next,newState)
        }
        content.tag="screen-${next.name}"; content.contentDescription="screen-${next.name}"
        overlay.addView(content,FrameLayout.LayoutParams(-1,-1))
        refresh(newState)
    }

    fun back(): Boolean {
        if(history.isEmpty())return false
        state?.let { show(history.removeLast(),it,remember=false) }
        return true
    }

    fun refresh(newState: StageState) {
        state=newState
        if(page!=StagePage.PLAY) { setup.refresh(newState); settings.refresh(newState); return }
        newState.roles.forEach { role ->
            roleLabels[role.role]?.apply {
                text=if(role.live && !role.needsRecenter)"实时" else if(role.live)"归中" else if(role.bound)"等待" else "未连"
                setTextColor(if(role.live)StageTheme.GREEN else StageTheme.MUTED)
                contentDescription="${role.role.label} · $text"
            }
        }
        hatLabel?.text=if(newState.hatCalibrated)"踩镲 · 开度 ${(newState.openness*100).toInt()}%" else "踩镲 · 尚未标定"
        hatProgress?.progress=if(newState.hatCalibrated)(newState.openness*100).toInt() else 0
        arButton?.text=if(newState.arEnabled)"返回 3D" else "AR 相机"
        status?.text=newState.lastStatus.ifBlank { "连接手柄，或轻触下方鼓垫演奏" }
    }

    fun hit(drum: Drum,velocity: Float) {
        if(page!=StagePage.PLAY)return
        selected?.text="${drum.label} · 力度 ${(velocity*127).toInt()}"
        pads[drum]?.let { button ->
            button.alpha=0.55f
            handler.postDelayed({ button.alpha=1f },130)
        }
    }

    private fun performance(s: StageState): View {
        val context=root.context
        val panel=FrameLayout(context)
        val top=theme.row().apply { gravity=Gravity.TOP; setPadding(theme.dp(16),theme.dp(12),theme.dp(16),0) }
        val roleRow=theme.row()
        Role.entries.forEach { role ->
            val cell=theme.column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(theme.dp(2),theme.dp(4),theme.dp(2),theme.dp(4)) }
            cell.addView(theme.roleArt(role),LinearLayout.LayoutParams(theme.dp(28),theme.dp(42)))
            val live=theme.text("未连",11,true,StageTheme.MUTED).apply { gravity=Gravity.CENTER; setPadding(0,0,0,0) }
            roleLabels[role]=live; cell.addView(live)
            cell.contentDescription="${role.label}状态"
            roleRow.addView(cell,LinearLayout.LayoutParams(theme.dp(42),-2))
        }
        top.addView(roleRow,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(theme.icon("kit","鼓组") { actions.navigate(StagePage.KIT) },LinearLayout.LayoutParams(theme.dp(48),theme.dp(48)))
        top.addView(theme.icon("settings","设置") { actions.navigate(StagePage.SETTINGS) },LinearLayout.LayoutParams(theme.dp(48),theme.dp(48)))
        panel.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))

        val bottom=theme.column().apply { setPadding(theme.dp(16),0,theme.dp(16),theme.dp(12)) }
        selected=theme.text("触摸鼓垫，也能开始演奏",16,true).also { bottom.addView(it) }
        val hat=theme.row()
        val hatColumn=theme.column()
        hatLabel=theme.text("",14,true).also { hatColumn.addView(it) }
        hatProgress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; progressTintList=android.content.res.ColorStateList.valueOf(StageTheme.GREEN)
            contentDescription="踩镲实时开度"
        }.also { hatColumn.addView(it,LinearLayout.LayoutParams(-1,theme.dp(12))) }
        hat.addView(hatColumn,LinearLayout.LayoutParams(0,-2,1f))
        hat.addView(compactButton("重标定") { actions.navigate(StagePage.HAT) },LinearLayout.LayoutParams(theme.dp(88),theme.dp(48)))
        bottom.addView(hat)

        val padRow=theme.row()
        Drum.entries.filter { it!=Drum.CHICK }.forEach { drum ->
            val button=compactButton(drum.label) { actions.trigger(drum) }.apply { contentDescription="触摸${drum.label}" }
            pads[drum]=button
            val lp=LinearLayout.LayoutParams(theme.dp(82),theme.dp(48)); lp.marginEnd=theme.dp(6)
            padRow.addView(button,lp)
        }
        bottom.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(padRow) },LinearLayout.LayoutParams(-1,theme.dp(56)))
        val nav=theme.row()
        listOf("设备" to { actions.navigate(StagePage.DEVICES) },"校准" to { actions.navigate(StagePage.HANDS) }).forEach { (label,action) ->
            val lp=LinearLayout.LayoutParams(0,theme.dp(48),1f); lp.marginEnd=theme.dp(6)
            nav.addView(compactButton(label,action),lp)
        }
        arButton=compactButton("AR 相机") { actions.toggleAr() }.also { nav.addView(it,LinearLayout.LayoutParams(0,theme.dp(48),1f)) }
        bottom.addView(nav)
        status=theme.text(s.lastStatus,12,false,StageTheme.MUTED).apply { maxLines=2; contentDescription="演奏状态" }.also { bottom.addView(it) }
        val scroll=object: ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val cap=(MeasureSpec.getSize(heightMeasureSpec)*0.39f).toInt()
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport=false; setBackgroundColor(0xe6080e13.toInt()); addView(bottom) }
        panel.addView(scroll,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        return panel
    }

    private fun compactButton(label: String,action: ()->Unit)=theme.secondary(label,action).apply {
        textSize=14f; setPadding(theme.dp(8),theme.dp(6),theme.dp(8),theme.dp(6))
    }

    fun close() { setup.dispose(); settings.dispose(); handler.removeCallbacksAndMessages(null) }
}
