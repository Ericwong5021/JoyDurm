package ai.joydurm.render

import android.graphics.Color
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import ai.joydurm.core.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

/** SceneView owns rendering, hit testing, camera tracking, materials and GLB loading. */
class DrumScene(private val activity: ComponentActivity,val ar: Boolean,private val hit: (Drum)->Unit,private val status: (String)->Unit) {
    val view: SceneView = if(ar) ARSceneView(activity,sharedLifecycle=activity.lifecycle) else SceneView(activity,sharedLifecycle=activity.lifecycle)
    val kit=Node(view.engine)
    private var anchor: AnchorNode?=null
    private val pieces=mutableMapOf<Drum,Node>()
    private val heads=mutableMapOf<Drum,Node>()
    private val headBase=mutableMapOf<Drum,Position>()
    private val headRotation=mutableMapOf<Drum,Rotation>()
    private val headScale=mutableMapOf<Drum,Scale>()
    private var beaterRotation=Rotation()
    private var destroyed=false
    private val bases=mutableMapOf<Drum,Position>()
    private val pulses=mutableMapOf<Drum,Pair<Long,Float>>()
    private val pending=ConcurrentLinkedQueue<Hit>()
    private var imported: ModelNode?=null
    private var hatTop: Node?=null
    private var hatBase=Position()
    private var beaterNode: Node?=null
    var openness=0f
    var kitScale=1f
    var kitYaw=0f
    var editMode=false
    var selected=Drum.SNARE
    var placed=!ar; private set
    private var lastTracking: TrackingState?=null
    init {
        view.setBackgroundColor(Color.rgb(16,21,31))
        if(ar) {
            (view as ARSceneView).apply {
                configureSession { _,config ->
                    config.planeFindingMode=Config.PlaneFindingMode.HORIZONTAL
                    config.lightEstimationMode=Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }
                onSessionFailed = { status("AR 不可用：${it.message}；可切回 3D 模式") }
                onSessionUpdated = { _,frame ->
                    if(frame.camera.trackingState!=lastTracking) {
                        lastTracking=frame.camera.trackingState
                        status(if(lastTracking==TrackingState.TRACKING) { if(placed) "AR 跟踪正常" else "缓慢扫描地面，然后点击摆放" } else "AR 跟踪暂停 · 增加光照并缓慢移动")
                    }
                }
            }
        } else {
            view.cameraNode.position=Position(0f,2.5f,3.5f)
            view.cameraNode.lookAt(Position(0f,0.7f,0f))
            view.addChildNode(kit)
        }
        buildKit()
        runCatching {
            val cached=File(activity.cacheDir,"joydurm-kit.glb")
            activity.assets.open("models/joydurm-kit.glb").use { input -> cached.outputStream().use { input.copyTo(it) } }
            importModel(cached)
        }.onFailure { status("内置模型加载失败，使用基础鼓组：${it.message}") }
        view.onGestureListener=object: GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent,node: Node?) {
                if(ar && !placed) {
                    val result=(view as ARSceneView).hitTestAR(e.x,e.y,planeTypes=setOf(Plane.Type.HORIZONTAL_UPWARD_FACING))
                    if(result==null) { status("还未识别到地面，请继续扫描"); return }
                    anchor=AnchorNode(view.engine,result.createAnchor()).also { it.isPositionEditable=false; it.addChildNode(kit); view.addChildNode(it) }
                    placed=true; status("鼓组已固定 · 可在布局里调节方向和尺寸")
                } else {
                    var found=node
                    while(found!=null && found.name?.startsWith("drum:")!=true) found=found.parent
                    found?.name?.removePrefix("drum:")?.let { name -> Drum.entries.firstOrNull { it.name==name }?.let { if(editMode) { selected=it; status("已选择 ${it.label}") } else hit(it) } }
                }
            }
        }
        view.onFrame={ time -> update(time) }
    }
    private fun cylinder(parent: Node,r: Float,h: Float,pos: Position,color: Int,metal: Float=0f): Node {
        val material=view.materialLoader.createColorInstance(color,metallic=metal,roughness=if(metal>0)0.3f else 0.65f)
        return CylinderNode(view.engine,radius=r,height=h,materialInstance=material).apply { position=pos; parent.addChildNode(this) }
    }
    private fun buildKit() {
        val brass=Color.rgb(219,175,79); val chrome=Color.rgb(167,178,193); val shell=Color.rgb(211,76,50); val skin=Color.rgb(221,230,227)
        data class Piece(val d: Drum,val p: Position,val r: Float,val h: Float)
        val parts=listOf(Piece(Drum.KICK,Position(0f,0.34f,-0.38f),0.34f,0.46f),Piece(Drum.SNARE,Position(-0.28f,0.70f,0.44f),0.22f,0.16f),
            Piece(Drum.TOM1,Position(-0.28f,1.0f,-0.25f),0.19f,0.22f),Piece(Drum.TOM2,Position(0.25f,1.0f,-0.25f),0.21f,0.24f),
            Piece(Drum.FLOOR,Position(0.73f,0.65f,0.35f),0.27f,0.35f),Piece(Drum.HAT,Position(-0.85f,0.95f,0.42f),0.23f,0.016f),
            Piece(Drum.CRASH,Position(-0.74f,1.42f,-0.48f),0.33f,0.012f),Piece(Drum.RIDE,Position(0.83f,1.23f,-0.38f),0.35f,0.014f))
        parts.forEach { p ->
            val group=Node(view.engine).apply { name="drum:${p.d.name}"; position=p.p }
            kit.addChildNode(group); pieces[p.d]=group; bases[p.d]=p.p
            val cymbal=p.d in listOf(Drum.HAT,Drum.CRASH,Drum.RIDE)
            if(cymbal) {
                cylinder(group,0.012f,p.p.y,Position(0f,-p.p.y/2,0f),chrome,0.85f)
                val disc=cylinder(group,p.r,p.h,Position(),brass,0.75f); heads[p.d]=disc
                cylinder(disc,p.r*0.19f,0.045f,Position(0f,0.014f,0f),brass,0.75f)
                if(p.d==Drum.HAT) { hatTop=disc; cylinder(group,p.r,0.012f,Position(0f,-0.025f,0f),brass,0.75f) }
            } else {
                val body=Node(view.engine).apply { if(p.d==Drum.KICK) rotation=Rotation(90f,0f,0f) }; group.addChildNode(body)
                cylinder(body,p.r,p.h,Position(),shell)
                val head=cylinder(body,p.r*1.015f,0.018f,Position(0f,p.h/2,0f),skin); heads[p.d]=head
                cylinder(body,p.r*1.04f,0.021f,Position(0f,-p.h/2,0f),chrome,0.8f)
                repeat(8) { k -> val a=k.toDouble()/8*PI*2; cylinder(body,0.01f,p.h,Position((sin(a)*p.r).toFloat(),0f,(cos(a)*p.r).toFloat()),chrome,0.8f) }
                if(p.d!=Drum.KICK) cylinder(group,0.018f,p.p.y-p.h/2,Position(0f,-(p.p.y+p.h/2)/2,0f),chrome,0.8f)
            }
        }
        // Kick beater: separate pivot, rotates into the rear drumhead on every kick.
        val beater=Node(view.engine).apply { name="beater"; position=Position(0f,0.1f,0.0f) }; kit.addChildNode(beater); beaterNode=beater
        cylinder(beater,0.012f,0.3f,Position(0f,0.15f,0f),chrome,0.8f)
        cylinder(beater,0.045f,0.05f,Position(0f,0.31f,0f),skin)
        captureTransforms()
    }
    fun hit(event: Hit) { pending.add(event) }
    private fun update(time: Long) {
        while(true) { val event=pending.poll() ?: break; pulses[if(event.drum==Drum.CHICK)Drum.HAT else event.drum]=time to event.velocity }
        kit.scale=Scale(kitScale); kit.rotation=Rotation(0f,kitYaw,0f)
        pulses.entries.removeAll { (drum,event) ->
            val t=(time-event.first)/1e9
            val strength=event.second
            val head=heads[drum]
            if(drum in listOf(Drum.HAT,Drum.CRASH,Drum.RIDE)) head?.rotation=Rotation((headRotation[drum]?.x ?: 0f)+(sin(t*34)*exp(-t*4)*10*strength).toFloat(),headRotation[drum]?.y ?: 0f,(headRotation[drum]?.z ?: 0f)+(sin(t*25)*exp(-t*4)*5*strength).toFloat())
            else {
                headScale[drum]?.let { base -> head?.scale=Scale(base.x,base.y*(1.0-sin(t*28)*exp(-t*12)*0.18*strength).toFloat(),base.z) }
                headBase[drum]?.let { p -> head?.position=Position(p.x,p.y-(sin(t*28)*exp(-t*12)*0.008*strength).toFloat(),p.z) }
            }
            if(drum==Drum.KICK) beaterNode?.rotation=Rotation(beaterRotation.x+(-sin(t*22)*exp(-t*9)*40*strength).toFloat(),beaterRotation.y,beaterRotation.z)
            if(t>2.0) { headRotation[drum]?.let { head?.rotation=it }; headScale[drum]?.let { head?.scale=it }; headBase[drum]?.let { head?.position=it }; if(drum==Drum.KICK)beaterNode?.rotation=beaterRotation; true } else false
        }
        hatTop?.position=Position(hatBase.x,hatBase.y+openness.coerceIn(0f,1f)*0.09f,hatBase.z)
    }
    fun setPiecePosition(drum: Drum,x: Float,y: Float,z: Float) {
        val position=Position(x,y,z)
        pieces[drum]?.position=position; bases[drum]=position
        if(heads[drum]===pieces[drum]) { headBase[drum]=position; if(drum==Drum.HAT)hatBase=position }
    }
    fun piecePosition(drum: Drum)=bases[drum] ?: Position()
    fun resetPlacement() {
        anchor?.removeChildNode(kit); anchor?.let { view.removeChildNode(it); it.destroy() }; anchor=null
        placed=!ar; if(!ar && kit.parent==null) view.addChildNode(kit)
    }
    /** Import a GLB containing individually named drum roots. Strict mapping prevents a static model pretending to animate. */
    fun importModel(file: File) {
        val node=ModelNode(view.modelLoader.createModelInstance(file),autoAnimate=false)
        val required=Drum.entries.filter { it!=Drum.CHICK }
        val mapping=required.associateWith { d -> node.nodes.firstOrNull { it.name.equals(d.name,true) } }
        if(mapping.values.any { it==null }) { view.modelLoader.destroyModel(node.model); error("GLB 需包含 kick / snare / tom1 / tom2 / floor / hat / crash / ride 八个独立节点") }
        imported?.let { kit.removeChildNode(it); view.modelLoader.destroyModel(it.model) }
        if(imported==null) kit.childNodes.toList().forEach { destroyTree(it) }
        pieces.clear(); heads.clear(); headBase.clear(); hatTop=null; pulses.clear()
        imported=node; kit.addChildNode(node)
        mapping.forEach { (d,n) ->
            n!!.name="drum:${d.name}"; pieces[d]=n; bases[d]=n.position
            val suffix=if(d in listOf(Drum.HAT,Drum.CRASH,Drum.RIDE))"_cymbal" else "_head"
            heads[d]=node.nodes.firstOrNull { it.name.equals(d.name+suffix,true) } ?: n
            headBase[d]=heads[d]!!.position
        }
        hatTop=heads[Drum.HAT]; hatBase=hatTop?.position ?: Position()
        beaterNode=node.nodes.firstOrNull { it.name=="beater" }
        captureTransforms()
    }
    private fun captureTransforms() {
        heads.forEach { (d,n) -> headBase[d]=n.position; headRotation[d]=n.rotation; headScale[d]=n.scale }
        hatBase=hatTop?.position ?: Position(); beaterRotation=beaterNode?.rotation ?: Rotation()
    }
    private fun destroyTree(node: Node) { node.childNodes.toList().forEach(::destroyTree); node.destroy() }
    fun destroy() {
        if(destroyed)return
        destroyed=true; view.onFrame=null; pending.clear()
        anchor?.let { view.removeChildNode(it); it.removeChildNode(kit); it.destroy() }; anchor=null
        view.removeChildNode(kit)
        imported?.let { kit.removeChildNode(it); view.modelLoader.destroyModel(it.model) }; imported=null
        destroyTree(kit); view.destroy()
    }
}
