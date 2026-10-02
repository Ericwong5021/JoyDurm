package ai.joydurm.render

import ai.joydurm.core.Drum
import ai.joydurm.core.Hit
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.exp

data class VisualPulse(val timeNs: Long, val velocity: Float) {
    fun ageSeconds(nowNs: Long) = ((nowNs - timeNs)/1e9).coerceAtLeast(0.0)
}

/** All times use elapsedRealtimeNanos, including after a delayed UI handoff. */
class HitAnimationQueue(capacity: Int = 128, private val durationNs: Long = 2_000_000_000L) {
    private val pending = ArrayBlockingQueue<Hit>(capacity)
    private val active = mutableMapOf<Drum,VisualPulse>()
    @Volatile private var closed = false
    val pendingCount get() = pending.size
    fun offer(event: Hit) {
        if(closed || !event.velocity.isFinite() || event.timeNs < 0) return
        if(!pending.offer(event)) { pending.poll(); pending.offer(event) }
        if(closed) pending.clear()
    }
    fun advance(nowNs: Long): Map<Drum,VisualPulse> {
        while(true) {
            val event = pending.poll() ?: break
            val age = nowNs - event.timeNs
            if(age < -5_000_000L || age > durationNs) continue
            val drum = if(event.drum == Drum.CHICK) Drum.HAT else event.drum
            if(event.timeNs >= (active[drum]?.timeNs ?: Long.MIN_VALUE))
                active[drum] = VisualPulse(event.timeNs, event.velocity.coerceIn(0f,1f))
        }
        active.entries.removeAll { nowNs - it.value.timeNs > durationNs }
        return active.toMap()
    }
    fun clear() { pending.clear(); active.clear() }
    fun close() { closed = true; clear() }
}

/** Time-based visual smoothing: 30/60/120 Hz rendering has the same response. */
class HatVisualInterpolator(private val timeConstantSeconds: Double = 0.025) {
    private var lastNs: Long? = null
    private var value = 0f
    fun update(target: Float, nowNs: Long): Float {
        require(target.isFinite())
        val bounded = target.coerceIn(0f,1f)
        val previous = lastNs
        if(previous == null) { lastNs = nowNs; value = bounded; return value }
        if(nowNs <= previous) return value
        val dt = (nowNs-previous)/1e9
        value += ((bounded-value)*(1-exp(-dt/timeConstantSeconds))).toFloat()
        lastNs = nowNs
        return value
    }
}
