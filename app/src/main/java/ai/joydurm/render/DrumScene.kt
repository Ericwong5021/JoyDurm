package ai.joydurm.render

import android.graphics.Color
import android.os.SystemClock
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
import kotlin.math.*

/** SceneView owns rendering, hit testing, camera tracking, materials and GLB loading. */
class DrumScene(private val activity: ComponentActivity,val ar: Boolean,private val hit: (Drum)->Unit,private val status: (String)->Unit) {
    // Lifecycle destruction and view detachment can precede Activity.onDestroy.
    // Retire our nodes/model while Filament is alive on every SceneView destruction path.
    val view: SceneView = if(ar) object : ARSceneView(activity,sharedLifecycle=activity.lifecycle) {
        override fun destroy() { try { destroyContent() } finally { super.destroy() } }
    } else object : SceneView(activity,sharedLifecycle=activity.lifecycle) {
        override fun destroy() { try { destroyContent() } finally { super.destroy() } }
    }
    val kit=Node(view.engine)
    private var anchor: AnchorNode?=null
    private val pieces=mutableMapOf<Drum,Node>()
    private val heads=mutableMapOf<Drum,Node>()
    private val headBase=mutableMapOf<Drum,Position>()
    private val headRotation=mutableMapOf<Drum,Rotation>()
    private val headScale=mutableMapOf<Drum,Scale>()
    private var beaterRotation=Rotation()
    @Volatile private var destroyed=false
    private val bases=mutableMapOf<Drum,Position>()
    private val timeline=HitAnimationQueue()
    private val hatMotion=HatVisualInterpolator()
    private var imported: ModelNode?=null
    private var hatTop: Node?=null
    private var hatBase=Position()
    private var beaterNode: Node?=null
    /** Called for layout edits and AR placement; the engine owns revision and recenter policy. */
    var onLayoutChanged: ((Map<Drum,PiecePose>,Float,Float)->Unit)?=null
        set(value) { field=value; if(value!=null) notifyLayoutChanged() }
    /** Read an immutable engine snapshot on every frame, independently of UI refresh cadence. */
    var snapshotProvider: (()->EngineSnapshot)?=null
    var openness=0f
        set(value) { require(value.isFinite()); field=value.coerceIn(0f,1f) }
    var kitScale=1f
        set(value) { require(value.isFinite()); val next=value.coerceIn(0.3f,2f); if(field!=next) { field=next; notifyLayoutChanged() } }
    var kitYaw=0f
        set(value) { require(value.isFinite()); val next=value.coerceIn(-360f,360f); if(field!=next) { field=next; notifyLayoutChanged() } }
    var editMode=false
    var selected=Drum.SNARE
    var placed=!ar; private set
    private var lastTracking: TrackingState?=null
    init {
        // An Android background draws over SurfaceView's hole and hides the Filament surface.
        // Color the existing 3D skybox instead; AR keeps its camera background untouched.
        if(!ar) view.skybox?.setColor(16f/255f,21f/255f,31f/255f,1f)
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
                    placed=true; notifyLayoutChanged(); status("鼓组已固定 · 布局变化后请重新归中")
                } else {
                    var found=node
                    while(found!=null && found.name?.startsWith("drum:")!=true) found=found.parent
                    found?.name?.removePrefix("drum:")?.let { name -> Drum.entries.firstOrNull { it.name==name }?.let { if(editMode) { selected=it; status("已选择 ${it.label}") } else hit(it) } }
                }
            }
        }
        // SceneView uses a frame clock; IMU/Hit events use Android's elapsed realtime clock.
        // Read the latter here so suspend and UI delays cannot restart an old hit animation.
        view.onFrame={ update(SystemClock.elapsedRealtimeNanos()) }
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
        // Keep the pivot's old world placement, but inherit the complete kick transform.
        val beater=Node(view.engine).apply { name="beater"; position=Position(0f,-0.24f,0.38f) }; pieces.getValue(Drum.KICK).addChildNode(beater); beaterNode=beater
        cylinder(beater,0.012f,0.3f,Position(0f,0.15f,0f),chrome,0.8f)
        cylinder(beater,0.045f,0.05f,Position(0f,0.31f,0f),skin)
        captureTransforms()
    }
    fun hit(event: Hit) { if(!destroyed) timeline.offer(event) }
    private fun update(time: Long) {
        if(destroyed) return
        val pulses=timeline.advance(time)
        snapshotProvider?.invoke()?.openness?.takeIf { it.isFinite() }?.let { openness=it }
        kit.scale=Scale(kitScale); kit.rotation=Rotation(0f,kitYaw,0f)
        // Reset inactive pieces too, since the bounded timeline expires old pulses itself.
        heads.forEach { (drum,head) -> if(drum !in pulses) {
            headRotation[drum]?.let { head.rotation=it }; headScale[drum]?.let { head.scale=it }; headBase[drum]?.let { head.position=it }
        } }
        if(Drum.KICK !in pulses) beaterNode?.rotation=beaterRotation
        pulses.forEach { (drum,event) ->
            val t=event.ageSeconds(time)
            val strength=event.velocity
            val head=heads[drum]
            if(drum in listOf(Drum.HAT,Drum.CRASH,Drum.RIDE)) head?.rotation=Rotation((headRotation[drum]?.x ?: 0f)+(sin(t*34)*exp(-t*4)*10*strength).toFloat(),headRotation[drum]?.y ?: 0f,(headRotation[drum]?.z ?: 0f)+(sin(t*25)*exp(-t*4)*5*strength).toFloat())
            else {
                headScale[drum]?.let { base -> head?.scale=Scale(base.x,base.y*(1.0-sin(t*28)*exp(-t*12)*0.18*strength).toFloat(),base.z) }
                headBase[drum]?.let { p -> head?.position=Position(p.x,p.y-(sin(t*28)*exp(-t*12)*0.008*strength).toFloat(),p.z) }
            }
            if(drum==Drum.KICK) beaterNode?.rotation=Rotation(beaterRotation.x+(-sin(t*22)*exp(-t*9)*40*strength).toFloat(),beaterRotation.y,beaterRotation.z)
        }
        hatTop?.position=Position(hatBase.x,hatBase.y+hatMotion.update(openness,time)*0.09f,hatBase.z)
    }
    fun setPiecePosition(drum: Drum,x: Float,y: Float,z: Float) {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "鼓件坐标无效" }
        val position=Position(x,y,z)
        val piece=pieces[drum] ?: return
        // Public layout coordinates belong to kit, even when a GLB inserts transform groups.
        piece.worldPosition=kit.getWorldPosition(position); bases[drum]=position
        if(heads[drum]===piece) { headBase[drum]=piece.position; if(drum==Drum.HAT)hatBase=piece.position }
        notifyLayoutChanged()
    }
    fun piecePosition(drum: Drum)=bases[drum] ?: Position()
    fun layoutPieces(): Map<Drum,PiecePose> = bases.mapValues { (_,p) -> PiecePose(p.x,p.y,p.z) }.toMap()
    private fun notifyLayoutChanged() { if(!destroyed) onLayoutChanged?.invoke(layoutPieces(),kitScale,kitYaw) }
    fun resetPlacement() {
        anchor?.removeChildNode(kit); anchor?.let { view.removeChildNode(it); it.destroy() }; anchor=null
        placed=!ar; if(!ar && kit.parent==null) view.addChildNode(kit)
        notifyLayoutChanged()
    }
    /** Worker prepares immutable bytes; this main-thread step allocates and publishes GPU resources. */
    fun importModel(file: File, validated: PreparedGlb?=null) {
        check(!destroyed) { "场景已退出，取消模型导入" }
        val prepared=validated ?: GlbValidator.prepare(file)
        // No unchecked disk read or external-resource resolver reaches the GPU.
        // Own the asset before loading resources, so a decoder exception can also retire it.
        val model=view.modelLoader.assetLoader.createAsset(prepared.buffer()) ?: error("GLB GPU 解析失败")
        var candidate: ModelNode?=null
        var previous: ModelNode?=null
        var previousFallback: List<Node> = emptyList()
        try {
            view.modelLoader.resourceLoader.loadResources(model)
            val node=ModelNode(model.instance,autoAnimate=false).also { candidate=it }
            val required=Drum.entries.filter { it!=Drum.CHICK }
            val mapping=required.associateWith { d -> node.nodes.singleOrNull { it.name.equals(d.name,true) }
                ?: error("GPU 模型缺少唯一 ${d.name} 节点") }
            val roots=mapping.values.toSet()
            require(roots.all { root -> var parent=root.parent; var independent=true
                while(parent!=null) { if(parent in roots)independent=false; parent=parent.parent }; independent } &&
                node.nodes.all { n -> listOf(n.position.x,n.position.y,n.position.z,n.rotation.x,n.rotation.y,n.rotation.z,n.scale.x,n.scale.y,n.scale.z).all { it.isFinite() } }) { "GLB GPU 节点结构或变换无效" }
            val newHeads=mapping.mapValues { (d,n) ->
                val suffix=if(d in listOf(Drum.HAT,Drum.CRASH,Drum.RIDE))"_cymbal" else "_head"
                node.nodes.firstOrNull { it.name.equals(d.name+suffix,true) && descendantOf(it,n) } ?: n
            }
            // Candidate is unattached: its world coordinates are exactly its future kit-local coordinates.
            val newBases=mapping.mapValues { (_,n) -> n.worldPosition }
            val newBeater=node.nodes.firstOrNull { it.name.equals("beater",true) }
            require(newBeater==null || descendantOf(newBeater,mapping.getValue(Drum.KICK))) { "beater 必须跟随 kick" }
            val newHeadBase=newHeads.mapValues { (_,n) -> n.position }
            val newHeadRotation=newHeads.mapValues { (_,n) -> n.rotation }
            val newHeadScale=newHeads.mapValues { (_,n) -> n.scale }
            val newHatBase=newHeads[Drum.HAT]?.position ?: Position()
            val newBeaterRotation=newBeater?.rotation ?: Rotation()
            // Candidate is fully ready before any live resource or transform is changed.
            mapping.forEach { (d,n) -> n.name="drum:${d.name}" }
            previous=imported
            previousFallback=if(previous==null)kit.childNodes.toList() else emptyList()
            kit.addChildNode(node)
            pieces.clear(); pieces.putAll(mapping); bases.clear(); bases.putAll(newBases)
            heads.clear(); heads.putAll(newHeads); headBase.clear(); headBase.putAll(newHeadBase)
            headRotation.clear(); headRotation.putAll(newHeadRotation); headScale.clear(); headScale.putAll(newHeadScale)
            hatTop=heads[Drum.HAT]; hatBase=newHatBase; beaterNode=newBeater; beaterRotation=newBeaterRotation
            timeline.clear(); imported=node; candidate=null
        } catch(error: Throwable) {
            // Do not remove the current model on a candidate validation/load failure.
            candidate?.let { kit.removeChildNode(it) }
            view.modelLoader.destroyModel(model)
            throw error
        }
        // Resource retirement happens after publication and cannot turn a successful swap into
        // an import failure (which would make the activity delete its new persistent model file).
        runCatching {
            previous?.let { kit.removeChildNode(it); view.modelLoader.destroyModel(it.model) }
            previousFallback.forEach(::destroyTree)
        }.onFailure { status("模型已加载；旧资源清理异常：${it.message}") }
        notifyLayoutChanged()
    }
    private fun descendantOf(node: Node,root: Node): Boolean {
        var parent=node.parent
        while(parent!=null) { if(parent===root)return true; parent=parent.parent }
        return false
    }
    private fun captureTransforms() {
        heads.forEach { (d,n) -> headBase[d]=n.position; headRotation[d]=n.rotation; headScale[d]=n.scale }
        hatBase=hatTop?.position ?: Position(); beaterRotation=beaterNode?.rotation ?: Rotation()
    }
    private fun destroyTree(node: Node) { node.childNodes.toList().forEach(::destroyTree); node.destroy() }
    fun destroy() = view.destroy()
    private fun destroyContent() {
        if(destroyed)return
        destroyed=true; view.onFrame=null; timeline.close(); snapshotProvider=null; onLayoutChanged=null
        (view as? ARSceneView)?.apply { onSessionUpdated=null; onSessionFailed=null }
        anchor?.let { view.removeChildNode(it); it.removeChildNode(kit); it.destroy() }; anchor=null
        view.removeChildNode(kit)
        imported?.let { kit.removeChildNode(it); view.modelLoader.destroyModel(it.model) }; imported=null
        destroyTree(kit)
    }
}
