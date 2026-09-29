package ai.joydurm.core

import kotlin.math.*

class DrumEngine(private val onHit: (Hit) -> Unit) {
    val roles = Role.entries.associateWith { RoleState() }
    @Volatile var openness = 0f; private set
    var hatRange = Math.toRadians(30.0)
    var hatSign = 1.0
    private val calibrators = mutableMapOf<Role,StationaryCalibrator>()
    private var lastChick = 0L
    init { resetTargets() }
    @Synchronized fun resetTargets() {
        roles[Role.LEFT_HAND]!!.targets = mutableListOf(Target(Drum.HAT,-0.55,0.0),Target(Drum.SNARE,0.0,-0.35),Target(Drum.TOM1,0.1,0.15),Target(Drum.CRASH,-0.5,0.55))
        roles[Role.RIGHT_HAND]!!.targets = mutableListOf(Target(Drum.SNARE,0.0,-0.35),Target(Drum.TOM2,0.2,0.15),Target(Drum.FLOOR,0.7,-0.2),Target(Drum.RIDE,0.65,0.35),Target(Drum.CRASH,0.25,0.6))
    }
    @Synchronized fun assign(role: Role, device: String) {
        if(roles[role]!!.device == device) return
        roles.entries.filter { it.value.device==device }.forEach { (oldRole, old) ->
            deviceLost(device); calibrators.remove(oldRole); old.device=null; old.calibration=null
        }
        calibrators.remove(role)
        if(role == Role.LEFT_FOOT) openness=0f
        roles[role]!!.apply { this.device=device; latest=null; filter.reset(); stroke.reset(); calibration=null; neutral=Attitude(0.0,0.0,0.0) }
    }
    @Synchronized fun deviceLost(device: String) {
        roles.entries.filter { it.value.device==device }.forEach { (role,state) ->
            state.latest=null; state.filter.reset(); state.stroke.reset(); calibrators.remove(role)
            if(role==Role.LEFT_FOOT) openness=0f
        }
    }
    @Synchronized fun startCalibration(role: Role) { calibrators[role]=StationaryCalibrator() }
    @Synchronized fun cancelCalibration(role: Role) { calibrators.remove(role) }
    @Synchronized fun calibrationCount(role: Role) = calibrators[role]?.count ?: 0
    @Synchronized fun finishCalibration(role: Role): Calibration {
        val result = calibrators.remove(role)?.finish() ?: error("尚未开始校准")
        roles[role]!!.apply { calibration=result; filter.reset(); stroke.reset(); neutral=Attitude(0.0,0.0,0.0) }
        return result
    }
    @Synchronized fun recenter(role: Role) { roles[role]!!.apply { neutral=filter.attitude; stroke.reset() } }
    @Synchronized fun bindTarget(role: Role, drum: Drum) {
        require(role==Role.LEFT_HAND || role==Role.RIGHT_HAND)
        val state=roles[role]!!
        require(state.latest != null) { "没有运动数据" }
        val a=relative(state)
        state.targets.removeAll { it.drum==drum }; state.targets.add(Target(drum,a.yaw,a.pitch))
    }
    fun relative(s: RoleState): Attitude = Attitude(s.filter.attitude.pitch-s.neutral.pitch,
        s.filter.attitude.roll-s.neutral.roll,OrientationFilter.wrap(s.filter.attitude.yaw-s.neutral.yaw))
    @Synchronized fun process(sample: ImuSample) {
        if(!sample.accel.finite() || !sample.gyro.finite()) return
        val role=roles.entries.firstOrNull { it.value.device==sample.device }?.key ?: return
        val state=roles[role]!!
        if(state.latest?.let { sample.timeNs<=it.timeNs } == true) return
        if(state.latest?.let { sample.timeNs-it.timeNs>300_000_000L } == true) state.stroke.reset()
        state.latest=sample
        calibrators[role]?.add(sample)
        state.filter.update(sample,state.calibration?.bias ?: Vec3())
        if(calibrators.containsKey(role)) return
        val a=relative(state)
        when(role) {
            Role.LEFT_FOOT -> {
                val raw=((a.pitch*hatSign-Math.toRadians(2.0))/(hatRange.coerceAtLeast(0.1))).coerceIn(0.0,1.0).toFloat()
                val previous=openness
                openness += (raw-openness)*0.35f
                if(previous>0.12f && openness<0.12f && sample.timeNs-lastChick>140_000_000L) {
                    lastChick=sample.timeNs; onHit(Hit(Drum.CHICK,0.6f,openness,sample.timeNs))
                }
            }
            Role.RIGHT_FOOT -> {
                // Dynamic acceleration magnitude; rest is approximately zero, a stomp is positive.
                val speed=max(0.0,abs(sample.accel.norm()-9.80665))
                state.stroke.update(speed,sample.timeNs)?.let { onHit(Hit(Drum.KICK,it,openness,sample.timeNs)) }
            }
            else -> {
                val speed=(sample.gyro-(state.calibration?.bias ?: Vec3())).axis(state.axis)*state.sign
                state.stroke.update(speed,sample.timeNs)?.let { strength ->
                    val target=state.targets.minByOrNull { targetDistance(a,it) } ?: return
                    // Refuse distant gestures rather than silently snapping every stroke to a drum.
                    if(targetDistance(a,target)<4.0) onHit(Hit(target.drum,strength,openness,sample.timeNs))
                }
            }
        }
    }
    fun trigger(drum: Drum, velocity: Float=0.8f, timeNs: Long=System.nanoTime()) = onHit(Hit(drum,velocity.coerceIn(0f,1f),openness,timeNs))
    companion object {
        fun targetDistance(a: Attitude,t: Target): Double = (OrientationFilter.wrap(a.yaw-t.yaw)/0.45).pow(2)+((a.pitch-t.pitch)/0.4).pow(2)
    }
}
