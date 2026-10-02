package ai.joydurm.core

import java.util.Collections
import kotlin.math.*

/** Mutations belong to EngineExecutor's single writer; readers consume immutable snapshots. */
class DrumEngine(var onHatControl: (HatControl) -> Unit = {}, private val onHit: (Hit) -> Unit) {
    val roles = Role.entries.associateWith { RoleState() }
    var openness = 0f; private set
    var hatRange = Math.toRadians(30.0); private set
    var hatSign = 1.0; private set
    private val calibrators = mutableMapOf<Role,StationaryCalibrator>()
    private var revision=0L
    private var layout=DrumLayout()
    private var timeNs=0L
    init { resetTargets() }
    fun resetTargets() {
        // Templates describe the original arrangement only. Changed layouts require explicit rebinding.
        roles.values.forEach { it.targets.clear(); it.targetRevision=layout.revision; it.stroke.reset() }
        if(layout.revision==0L) {
            roles[Role.LEFT_HAND]!!.targets.addAll(listOf(Target(Drum.HAT,-0.55,0.0),Target(Drum.SNARE,0.0,-0.35),Target(Drum.TOM1,0.1,0.15),Target(Drum.CRASH,-0.5,0.55)))
            roles[Role.RIGHT_HAND]!!.targets.addAll(listOf(Target(Drum.SNARE,0.0,-0.35),Target(Drum.TOM2,0.2,0.15),Target(Drum.FLOOR,0.7,-0.2),Target(Drum.RIDE,0.65,0.35),Target(Drum.CRASH,0.25,0.6)))
        }
        changed()
    }
    fun replaceLayout(pieces: Map<Drum,PiecePose>, scale: Float, yaw: Float, forceRevision: Boolean = false) {
        require(scale.isFinite() && scale>0 && yaw.isFinite())
        require(pieces.values.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        if(!forceRevision && layout.pieces==pieces && layout.scale==scale && layout.yaw==yaw) return
        layout=DrumLayout(layout.revision+1,Collections.unmodifiableMap(LinkedHashMap(pieces)),scale,yaw)
        roles.values.forEach { it.targets.clear(); it.targetRevision=layout.revision; invalidate(it) }
        choke("layout changed"); changed()
    }
    fun assign(role: Role, device: String) {
        val state=roles.getValue(role)
        if(state.device==device) return
        roles.entries.filter { it.value.device==device }.forEach { (oldRole,old) ->
            invalidate(old); old.device=null; old.calibration=null; old.session=null; calibrators.remove(oldRole)
            if(oldRole==Role.LEFT_FOOT) choke("role reassigned")
        }
        calibrators.remove(role)
        invalidate(state); state.device=device; state.calibration=null; state.session=null; state.retiredSessions.clear()
        if(role==Role.LEFT_FOOT) choke("role assigned")
        changed()
    }
    fun unassign(role: Role) {
        val state=roles.getValue(role)
        calibrators.remove(role); invalidate(state)
        state.device=null; state.calibration=null; state.session=null; state.retiredSessions.clear()
        if(role==Role.LEFT_FOOT) choke("role unassigned")
        changed()
    }
    /** Queue loss breaks reliable integration even when the timestamp gap is short. */
    fun suspendMotion(device: String) {
        roles.entries.filter { it.value.device==device }.forEach { (role,state) ->
            invalidate(state); calibrators.remove(role)
            if(role==Role.LEFT_FOOT) choke("input queue overflow")
        }; changed()
    }
    fun deviceLost(device: String) = sessionLost(device)
    fun sessionLost(device: String, session: SessionId? = null) {
        roles.entries.filter { it.value.device==device }.forEach { (role,state) ->
            if(session!=null && state.session!=session) return@forEach
            state.session?.let { retire(state,it) }; state.session=null
            invalidate(state); calibrators.remove(role)
            if(role==Role.LEFT_FOOT) choke("controller lost")
        }
        changed()
    }
    private fun retire(state: RoleState, session: SessionId) {
        state.retiredSessions.add(session)
        // Session IDs are also rejected by the input layer; keep bounded diagnostic history here.
        if(state.retiredSessions.size>128) state.retiredSessions.remove(state.retiredSessions.first())
    }
    private fun invalidate(state: RoleState) {
        state.latest=null; state.filter.reset(); state.stroke.reset(); state.kick.reset(); state.hat.reset()
        state.neutralEpoch=null; state.needsRecenter=true; state.neutralQuaternion=Quaternion(); state.neutral=Attitude(0.0,0.0,0.0)
    }
    fun restoreRole(role: Role, device: String?, calibration: Calibration?, axis: Int, sign: Double,
                    threshold: Double, cooldownNs: Long, targets: List<Target>) {
        if(device!=null) assign(role,device)
        val state=roles.getValue(role)
        state.calibration=calibration
        tune(role,threshold,cooldownNs,axis,sign)
        state.targets=targets.filter { it.yaw.isFinite() && it.pitch.isFinite() }.toMutableList()
        state.targetRevision=layout.revision
        // Persisted physical tuning is safe; session neutral is intentionally never restored.
        invalidate(state); changed()
    }
    fun tune(role: Role, threshold: Double, cooldownNs: Long, axis: Int, sign: Double) {
        require(threshold.isFinite() && threshold>0 && cooldownNs in 0..2_000_000_000L && axis in 0..2 && sign.isFinite() && sign!=0.0)
        roles.getValue(role).apply {
            this.axis=axis; this.sign=if(sign<0) -1.0 else 1.0
            stroke.threshold=threshold; stroke.cooldownNs=cooldownNs; stroke.reset()
            kick.threshold=threshold; kick.cooldownNs=cooldownNs; kick.reset()
        }; changed()
    }
    /** Retained for migration only; active hi-hat mapping is defined by the two captured poses. */
    fun configureHat(range: Double, sign: Double) {
        require(range.isFinite() && range in 0.1..1.5 && sign.isFinite() && sign!=0.0)
        hatRange=range; hatSign=if(sign<0) -1.0 else 1.0; changed()
    }
    fun startCalibration(role: Role) {
        val state=roles.getValue(role)
        require(state.device!=null) { "请先分配手柄" }
        state.stroke.reset(); state.kick.reset(); state.needsRecenter=true; state.neutralEpoch=null
        calibrators[role]=StationaryCalibrator()
        if(role==Role.LEFT_FOOT) { state.hat.reset(); choke("calibration") }; changed()
    }
    fun cancelCalibration(role: Role) { calibrators.remove(role); roles.getValue(role).stroke.reset(); changed() }
    fun calibrationCount(role: Role) = calibrators[role]?.count ?: 0
    fun finishCalibration(role: Role): Calibration {
        val result=calibrators[role]?.finish() ?: error("尚未开始校准")
        calibrators.remove(role)
        roles.getValue(role).apply { calibration=result; invalidate(this) }
        changed(); return result
    }
    fun recenter(role: Role) {
        roles.getValue(role).apply {
            require(latest!=null) { "没有运动数据" }
            neutralQuaternion=filter.quaternion; neutral=filter.attitude; neutralEpoch=filter.epoch
            needsRecenter=false; stroke.reset(); kick.reset()
            if(role==Role.LEFT_FOOT) { hat.reset(); choke("recenter") }
        }; changed()
    }
    fun captureHatClosed() {
        val state=roles.getValue(Role.LEFT_FOOT)
        requireReady(state); state.hat.captureClosed(state.filter.quaternion); choke("closed captured"); changed()
    }
    fun captureHatOpen() {
        val state=roles.getValue(Role.LEFT_FOOT)
        requireReady(state); state.hat.captureOpen(state.filter.quaternion); changed()
    }
    private fun requireReady(state: RoleState) {
        require(state.latest!=null && !state.needsRecenter && state.neutralEpoch==state.filter.epoch) { "请先接收实时运动数据并重新归中" }
    }
    fun bindTarget(role: Role, drum: Drum) {
        require(role==Role.LEFT_HAND || role==Role.RIGHT_HAND)
        val state=roles.getValue(role); requireReady(state)
        require(layout.pieces.isEmpty() || drum in layout.pieces) { "鼓件不在当前布局中" }
        val a=relative(state)
        state.targets.removeAll { it.drum==drum }; state.targets.add(Target(drum,a.yaw,a.pitch)); state.targetRevision=layout.revision
        state.stroke.reset(); changed()
    }
    fun relative(state: RoleState): Attitude = (state.neutralQuaternion.conjugate()*state.filter.quaternion).normalized().attitude()
    fun process(frame: ImuFrame) {
        if(frame.clockErrorNs<0 || frame.clockErrorNs>30_000_000L || frame.receivedTimeNs-frame.sample.timeNs>250_000_000L || frame.sample.timeNs-frame.receivedTimeNs>frame.clockErrorNs) return
        if(frame.identity.bindingId!=frame.sample.device) return
        val state=roles.values.firstOrNull { it.device==frame.sample.device } ?: return
        if(frame.session in state.retiredSessions) return
        if(state.session!=frame.session) {
            state.session?.let { retire(state,it) }; invalidate(state); state.session=frame.session
            roles.entries.firstOrNull { it.value===state }?.key?.let { calibrators.remove(it) }
            if(state===roles[Role.LEFT_FOOT]) choke("new controller session")
        }
        processSample(frame.sample,frame.receivedTimeNs)
    }
    fun process(sample: ImuSample) = processSample(sample,sample.timeNs)
    private fun processSample(sample: ImuSample, receivedTimeNs: Long) {
        if(!sample.accel.finite() || !sample.gyro.finite()) return
        val role=roles.entries.firstOrNull { it.value.device==sample.device }?.key ?: return
        val state=roles.getValue(role)
        if(state.latest?.let { sample.timeNs<=it.timeNs } == true) return
        state.latest=sample; timeNs=max(timeNs,sample.timeNs)
        calibrators[role]?.add(sample)
        val epoch=state.filter.epoch
        state.filter.update(sample,state.calibration?.bias ?: Vec3())
        if(epoch!=state.filter.epoch) {
            state.needsRecenter=true; state.neutralEpoch=null; state.stroke.reset(); state.kick.reset(); state.hat.reset()
            if(role==Role.LEFT_FOOT) choke("orientation gap")
        }
        changed()
        if(calibrators.containsKey(role) || state.needsRecenter || state.neutralEpoch!=state.filter.epoch) return
        when(role) {
            Role.LEFT_FOOT -> {
                if(!state.hat.calibrated) return
                val update=state.hat.update(state.filter.quaternion,sample.timeNs)
                openness=update.openness; onHatControl(HatControl.Openness(openness,sample.timeNs))
                update.chick?.let { onHit(Hit(Drum.CHICK,it,openness,sample.timeNs,receivedTimeNs)) }
            }
            Role.RIGHT_FOOT -> {
                val gravity=state.filter.quaternion.conjugate().rotate(Vec3(0.0,0.0,9.80665))
                val down=(sample.accel-gravity).axis(state.axis)*state.sign
                state.kick.update(down,sample.timeNs)?.let { onHit(Hit(Drum.KICK,it,openness,sample.timeNs,receivedTimeNs)) }
            }
            else -> {
                if(state.targetRevision!=layout.revision) return
                val speed=(sample.gyro-(state.calibration?.bias ?: Vec3())).axis(state.axis)*state.sign
                state.stroke.update(speed,sample.timeNs,relative(state))?.let { strength ->
                    val ranked=state.targets.map { it to targetDistance(state.stroke.peakAttitude,it) }.sortedBy { it.second }
                    val best=ranked.firstOrNull() ?: return
                    val runner=ranked.getOrNull(1)?.second ?: Double.POSITIVE_INFINITY
                    // Explicit uncertainty margin: overlapping targets do not silently pick list order.
                    if(best.second>=4.0 || runner-best.second<0.35) return
                    val confidence=(1-best.second/4).coerceIn(0.0,1.0).toFloat()
                    onHit(Hit(best.first.drum,strength,openness,state.stroke.peakTimeNs,receivedTimeNs,confidence))
                }
            }
        }
    }
    private fun choke(reason: String) { openness=0f; onHatControl(HatControl.ChokeAll(timeNs,reason)) }
    fun trigger(drum: Drum, velocity: Float=0.8f, timeNs: Long=System.nanoTime()) {
        if(velocity.isFinite()) onHit(Hit(drum,velocity.coerceIn(0f,1f),openness,timeNs))
    }
    fun snapshot(): EngineSnapshot = EngineSnapshot(revision,layout,Collections.unmodifiableMap(roles.mapValues { (role,state) ->
        RoleSnapshot(state.device,state.latest?.timeNs,state.calibration,relative(state),state.filter.epoch,
            if(state.needsRecenter) OrientationStatus.NEEDS_RECENTER else OrientationStatus.READY,
            state.axis,state.sign,state.stroke.threshold,state.stroke.cooldownNs,state.targets.size,state.neutralQuaternion,
            state.latest,Collections.unmodifiableList(state.targets.toList()),calibrationCount(role),state.session,state.hat.calibrated)
    }),openness,timeNs,hatRange,hatSign)
    private fun changed() { revision++ }
    companion object {
        fun targetDistance(a: Attitude,t: Target): Double = (OrientationFilter.wrap(a.yaw-t.yaw)/0.45).pow(2)+((a.pitch-t.pitch)/0.4).pow(2)
    }
}
