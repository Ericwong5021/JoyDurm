package ai.joydurm.core

import kotlin.math.floor
import kotlin.math.roundToLong

data class BeatDeadline(val timeNs: Long, val beatIndex: Long, val skippedBeats: Long)

/**
 * Absolute elapsed-realtime schedule. A late poll emits the latest due beat once,
 * never bursts old clicks. BPM changes preserve the fractional position of the
 * current interval, so a halfway change remains halfway to the following beat.
 * The owner must serialize access and schedule against nextDeadlineNs.
 */
class AbsoluteBeatClock(bpm: Int = 100) {
    var bpm: Int = bpm; private set
    init { require(bpm in 20..400) }
    private var epochNs = 0L
    private var nextBeat = 0L
    var running = false; private set
    private val periodNs get() = 60_000_000_000.0 / bpm

    val nextDeadlineNs: Long? get() = if (running) deadline(nextBeat) else null

    fun start(nowNs: Long) { epochNs = nowNs; nextBeat = 0; running = true }
    fun stop() { running = false }

    fun setBpm(value: Int, nowNs: Long) {
        require(value in 20..400)
        if (running) {
            val phase = (nowNs - epochNs).toDouble() / periodNs
            val newPeriod = 60_000_000_000.0 / value
            epochNs = nowNs - (phase * newPeriod).roundToLong()
        }
        bpm = value
    }

    fun poll(nowNs: Long): BeatDeadline? {
        if (!running || nowNs < deadline(nextBeat)) return null
        var latest = maxOf(nextBeat, floor((nowNs - epochNs).toDouble() / periodNs).toLong())
        // Integer nanosecond rounding can put a rounded boundary just above now.
        while (latest > nextBeat && deadline(latest) > nowNs) latest--
        val result = BeatDeadline(deadline(latest), latest, latest - nextBeat)
        nextBeat = latest + 1
        return result
    }

    private fun deadline(beat: Long) = epochNs + (beat * periodNs).roundToLong()
}
