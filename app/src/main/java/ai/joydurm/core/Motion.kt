package ai.joydurm.core

import kotlin.math.*

data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(v: Vec3) = Vec3(x+v.x, y+v.y, z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x, y-v.y, z-v.z)
    operator fun times(s: Double) = Vec3(x*s, y*s, z*s)
    fun norm() = sqrt(x*x+y*y+z*z)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun axis(i: Int) = when(i) { 0 -> x; 1 -> y; else -> z }
}
data class ImuSample(val device: String, val timeNs: Long, val accel: Vec3, val gyro: Vec3)
enum class Role(val label: String) { LEFT_HAND("左手"), RIGHT_HAND("右手"), LEFT_FOOT("左脚 / 踩镲"), RIGHT_FOOT("右脚 / 地鼓") }
enum class Drum(val label: String, val midi: Int) {
    KICK("地鼓",36), SNARE("军鼓",38), TOM1("高嗵",48), TOM2("中嗵",45), FLOOR("落地嗵",41), HAT("踩镲",42), CRASH("吊镲",49), RIDE("叮叮镲",51), CHICK("踩镲闭合",44)
}
data class Hit(val drum: Drum, val velocity: Float, val openness: Float, val timeNs: Long)
data class Calibration(val bias: Vec3, val gravity: Vec3, val samples: Int)
data class Attitude(val pitch: Double, val roll: Double, val yaw: Double)

/** Units everywhere: m/s², rad/s, monotonic ns. No double integration of acceleration. */
class OrientationFilter {
    var attitude = Attitude(0.0,0.0,0.0); private set
    private var last: Long? = null
    fun reset() { last = null; attitude = Attitude(0.0,0.0,0.0) }
    fun update(s: ImuSample, bias: Vec3): Attitude {
        val a = s.accel
        val pitch = atan2(-a.y, sqrt(a.x*a.x+a.z*a.z))
        val roll = atan2(a.x,a.z)
        val previous = last
        if(previous != null && s.timeNs <= previous) return attitude
        val dt = if(previous == null) 0.0 else ((s.timeNs-previous)/1e9).coerceIn(0.0,0.05)
        if(previous == null || s.timeNs-previous > 300_000_000L) attitude = Attitude(pitch,roll,0.0)
        last = s.timeNs
        val g = s.gyro-bias
        // Gravity correction only while near 1g: fast strokes must not tilt the estimate.
        val alpha = if(abs(a.norm()-9.80665)<1.5) exp(-dt/0.5) else 1.0
        attitude = Attitude(alpha*(attitude.pitch-g.x*dt)+(1-alpha)*pitch,
            alpha*(attitude.roll-g.y*dt)+(1-alpha)*roll, wrap(attitude.yaw+g.z*dt))
        return attitude
    }
    companion object { fun wrap(v: Double): Double = atan2(sin(v),cos(v)) }
}

class StationaryCalibrator {
    private val samples = ArrayList<ImuSample>()
    val count get() = samples.size
    fun add(s: ImuSample) { if(s.accel.finite() && s.gyro.finite() && (samples.lastOrNull()?.let { it.device == s.device && s.timeNs > it.timeNs } != false)) samples.add(s) }
    fun finish(): Calibration {
        require(samples.size >= 100) { "样本不足：保持静止至少 2 秒" }
        require(samples.last().timeNs-samples.first().timeNs >= 1_500_000_000L) { "采样时间不足" }
        val n = samples.size.toDouble()
        val bias = samples.fold(Vec3()) { v,s -> v+s.gyro } * (1/n)
        val gravity = samples.fold(Vec3()) { v,s -> v+s.accel } * (1/n)
        val variance = samples.sumOf { (it.gyro-bias).norm().pow(2) } / n
        val av = samples.sumOf { (it.accel-gravity).norm().pow(2) } / n
        require(variance < 0.008 && av < 0.3 && bias.norm()<0.3) { "校准时手柄移动了，请重新静置" }
        require(gravity.norm() in 8.0..11.5) { "加速度单位或数据异常" }
        return Calibration(bias,gravity,samples.size)
    }
}

/** Tracks one downward stroke, fires at deceleration, rearms after a quiet interval. */
class StrokeDetector(var threshold: Double = 2.2, var cooldownNs: Long = 90_000_000L) {
    private var active = false
    private var peak = 0.0
    private var lastHit = Long.MIN_VALUE/2
    private var started = 0L
    private var lastSample: Long? = null
    private var armed = true
    fun reset() { active=false; peak=0.0; started=0L; lastHit=Long.MIN_VALUE/2; lastSample=null; armed=true }
    fun update(speed: Double, t: Long): Float? {
        if(!speed.isFinite() || (lastSample?.let { t <= it } == true)) return null
        lastSample=t
        if(!active && speed <= threshold*0.35) armed=true
        if(!active && armed && speed > threshold && t-lastHit >= cooldownNs) { active=true; armed=false; peak=speed; started=t }
        if(!active) return null
        peak = max(peak,speed)
        if(t-started > 450_000_000L) { active=false; return null }
        if(speed < peak*0.72) {
            active=false; lastHit=t
            return ((peak-threshold)/(threshold*3)+0.25).coerceIn(0.1,1.0).toFloat()
        }
        return null
    }
}

data class Target(val drum: Drum, val yaw: Double, val pitch: Double)
data class RoleState(
    var device: String? = null,
    var calibration: Calibration? = null,
    val filter: OrientationFilter = OrientationFilter(),
    val stroke: StrokeDetector = StrokeDetector(),
    var latest: ImuSample? = null,
    var neutral: Attitude = Attitude(0.0,0.0,0.0),
    var axis: Int = 0, var sign: Double = 1.0,
    var targets: MutableList<Target> = mutableListOf()
)
