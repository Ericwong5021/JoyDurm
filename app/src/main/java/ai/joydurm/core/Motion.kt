package ai.joydurm.core

import kotlin.math.*

data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(v: Vec3) = Vec3(x+v.x, y+v.y, z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x, y-v.y, z-v.z)
    operator fun times(s: Double) = Vec3(x*s, y*s, z*s)
    fun norm() = sqrt(x*x+y*y+z*z)
    fun dot(v: Vec3) = x*v.x+y*v.y+z*v.z
    fun cross(v: Vec3) = Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
    fun normalized() = if(norm()>1e-10) this*(1/norm()) else Vec3(0.0,0.0,1.0)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun axis(i: Int) = when(i) { 0 -> x; 1 -> y; else -> z }
}
data class ImuSample(val device: String, val timeNs: Long, val accel: Vec3, val gyro: Vec3)
enum class Role(val label: String) { LEFT_HAND("左手"), RIGHT_HAND("右手"), LEFT_FOOT("左脚 / 踩镲"), RIGHT_FOOT("右脚 / 地鼓") }
enum class Drum(val label: String, val midi: Int) {
    KICK("地鼓",36), SNARE("军鼓",38), TOM1("高嗵",48), TOM2("中嗵",45), FLOOR("落地嗵",41), HAT("踩镲",42), CRASH("吊镲",49), RIDE("叮叮镲",51), CHICK("踩镲闭合",44)
}
data class Hit(val drum: Drum, val velocity: Float, val openness: Float, val timeNs: Long,
    val receivedTimeNs: Long = timeNs, val confidence: Float = 1f)
data class Calibration(val bias: Vec3, val gravity: Vec3, val samples: Int)
data class Attitude(val pitch: Double, val roll: Double, val yaw: Double)

/** Right handed body to world rotation, gyro in rad/s, acceleration in m/s². */
data class Quaternion(val w: Double=1.0,val x: Double=0.0,val y: Double=0.0,val z: Double=0.0) {
    operator fun times(q: Quaternion) = Quaternion(w*q.w-x*q.x-y*q.y-z*q.z,
        w*q.x+x*q.w+y*q.z-z*q.y,w*q.y-x*q.z+y*q.w+z*q.x,w*q.z+x*q.y-y*q.x+z*q.w)
    fun conjugate() = Quaternion(w,-x,-y,-z)
    fun normalized(): Quaternion { val n=sqrt(w*w+x*x+y*y+z*z); return if(n>1e-12)Quaternion(w/n,x/n,y/n,z/n) else Quaternion() }
    fun rotate(v: Vec3): Vec3 { val r=this*Quaternion(0.0,v.x,v.y,v.z)*conjugate(); return Vec3(r.x,r.y,r.z) }
    fun rotationVector(): Vec3 {
        val q=normalized().let { if(it.w<0) Quaternion(-it.w,-it.x,-it.y,-it.z) else it }
        val n=sqrt(q.x*q.x+q.y*q.y+q.z*q.z)
        return if(n<1e-10) Vec3() else Vec3(q.x,q.y,q.z)*(2*atan2(n,q.w)/n)
    }
    fun attitude(): Attitude {
        val g=conjugate().rotate(Vec3(0.0,0.0,1.0))
        return Attitude(atan2(-g.y,sqrt(g.x*g.x+g.z*g.z)),atan2(g.x,g.z),
            atan2(2*(w*z+x*y),1-2*(y*y+z*z)))
    }
    companion object {
        fun rotation(v: Vec3): Quaternion { val angle=v.norm(); if(angle<1e-12)return Quaternion(); val s=sin(angle/2)/angle; return Quaternion(cos(angle/2),v.x*s,v.y*s,v.z*s) }
        fun fromGravity(accel: Vec3): Quaternion {
            val a=accel.normalized(); val target=Vec3(0.0,0.0,1.0); val d=a.dot(target)
            if(d < -0.999999)return Quaternion(0.0,1.0,0.0,0.0)
            val c=a.cross(target); return Quaternion(1+d,c.x,c.y,c.z).normalized()
        }
    }
}

/** Six axis fusion; absolute yaw and free XYZ position remain unobservable. */
class OrientationFilter {
    var quaternion=Quaternion(); private set
    val attitude get()=quaternion.attitude()
    var epoch=OrientationEpoch(0); private set
    private var last: Long?=null
    fun reset() { last=null; quaternion=Quaternion(); epoch=OrientationEpoch(epoch.value+1) }
    fun update(s: ImuSample,bias: Vec3): Attitude {
        if(!s.accel.finite() || !s.gyro.finite())return attitude
        val previous=last
        if(previous!=null && s.timeNs<=previous)return attitude
        if(previous==null || s.timeNs-previous>300_000_000L) {
            if(previous!=null)epoch=OrientationEpoch(epoch.value+1)
            quaternion=Quaternion.fromGravity(s.accel); last=s.timeNs; return attitude
        }
        val dt=(s.timeNs-previous)/1e9; last=s.timeNs
        val gyro=s.gyro-bias
        // Exact exponential body rotation avoids integrating body axes as Euler axes.
        quaternion=(quaternion*Quaternion.rotation(gyro*dt)).normalized()
        if(abs(s.accel.norm()-9.80665)<1.5) {
            val predicted=quaternion.conjugate().rotate(Vec3(0.0,0.0,1.0))
            val error=s.accel.normalized().cross(predicted)
            quaternion=(quaternion*Quaternion.rotation(error*(2.0*dt))).normalized()
        }
        return attitude
    }
    companion object { fun wrap(v: Double): Double=atan2(sin(v),cos(v)) }
}

class StationaryCalibrator {
    private val samples = ArrayList<ImuSample>()
    val count get() = samples.size
    fun add(s: ImuSample) {
        if(!s.accel.finite() || !s.gyro.finite()) return
        val previous=samples.lastOrNull()
        if(previous!=null && (previous.device!=s.device || s.timeNs<=previous.timeNs)) return
        // A missing transport interval is not a continuous stationary calibration.
        if(previous!=null && s.timeNs-previous.timeNs>300_000_000L) samples.clear()
        if(samples.size==1200) samples.removeAt(0)
        samples.add(s)
    }
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
    var peakTimeNs: Long = 0; private set
    var peakAttitude: Attitude = Attitude(0.0,0.0,0.0); private set
    fun reset() { active=false; peak=0.0; started=0L; lastHit=Long.MIN_VALUE/2; lastSample=null; armed=true }
    fun update(speed: Double, t: Long, attitude: Attitude = Attitude(0.0,0.0,0.0)): Float? {
        if(!speed.isFinite() || (lastSample?.let { t <= it } == true)) return null
        lastSample=t
        if(!active && speed <= threshold*0.35) armed=true
        if(!active && armed && speed > threshold && t-lastHit >= cooldownNs) { active=true; armed=false; peak=speed; started=t; peakTimeNs=t; peakAttitude=attitude }
        if(!active) return null
        if(speed>peak) { peak=speed; peakTimeNs=t; peakAttitude=attitude }
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
    var neutralQuaternion: Quaternion = Quaternion(),
    var neutralEpoch: OrientationEpoch? = null,
    var needsRecenter: Boolean = true,
    var targetRevision: Long = 0,
    val kick: KickDetector = KickDetector(),
    val hat: HiHatController = HiHatController(),
    var axis: Int = 0, var sign: Double = 1.0,
    var session: SessionId? = null,
    val retiredSessions: MutableSet<SessionId> = linkedSetOf(),
    var targets: MutableList<Target> = mutableListOf()
)
