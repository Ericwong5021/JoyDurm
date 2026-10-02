package ai.joydurm.audio

import ai.joydurm.core.HatControl
import ai.joydurm.core.Hit

internal interface SampleBackend {
    fun play(sampleId: Int, gain: Float): Int
    fun stop(streamId: Int)
}

/** Caller owns synchronization. The backend never receives a synthetic CHICK for a disconnect. */
internal class VoicePlayback(
    private val slots: SampleSlots,
    private val backend: SampleBackend,
    private val clock: () -> Long,
    private val trace: AudioTrace,
    private val maxStreams: Int = 24
) {
    init { require(maxStreams > 0) }
    private data class Playing(val voice: SoundVoice, val startedNs: Long)
    private val playing = linkedMapOf<Int, Playing>()
    private var paused = true

    fun resume() { paused = false }
    fun pause(reason: String = "pause") {
        paused = true
        val now = clock()
        val stopped = playing.size
        stopAll()
        trace.recordControl(AudioControlTraceEntry(now, now, reason, stopped))
    }

    fun play(hit: Hit, bank: Int, volume: Float): Int {
        return playVoice(SoundVoice.fromHit(hit), hit.velocity, bank, volume, hit.timeNs)
    }

    fun playVoice(voice: SoundVoice, velocity: Float, bank: Int, volume: Float, actionTimeNs: Long): Int {
        if (voice == SoundVoice.HAT || voice == SoundVoice.CHICK) chokeAll(actionTimeNs, "closed-strike")
        var submit = clock()
        val outcome: AudioOutcome
        var stream = 0
        when {
            paused -> outcome = AudioOutcome.PAUSED
            !velocity.isFinite() || !volume.isFinite() -> outcome = AudioOutcome.INVALID_GAIN
            else -> {
                val id = slots.id("$bank:${voice.sampleName}")
                if (id == null) outcome = AudioOutcome.SAMPLE_NOT_READY
                else {
                    submit = clock()
                    stream = backend.play(id, (velocity * volume).coerceIn(0f, 1f))
                    outcome = if (stream == 0) AudioOutcome.BACKEND_REJECTED else AudioOutcome.PLAYED
                }
            }
        }
        val returned = clock()
        trace.record(AudioTraceEntry(voice, actionTimeNs, submit, returned, stream, outcome))
        if (stream != 0) {
            playing.entries.removeAll { returned - it.value.startedNs > 3_000_000_000L }
            // SoundPool is limited to 24 voices; bound our bookkeeping as well.
            if (playing.size >= maxStreams) {
                val oldest = playing.keys.first()
                backend.stop(oldest); playing.remove(oldest)
            }
            playing[stream] = Playing(voice, returned)
        }
        return stream
    }

    fun control(control: HatControl) {
        when (control) {
            is HatControl.ChokeAll -> chokeAll(control.timeNs, control.reason)
            is HatControl.Openness -> if (control.value.isFinite() && control.value <= 0.15f &&
                playing.values.any { it.voice.ringsOpenHat }) chokeAll(control.timeNs, "hat-closed")
        }
    }

    /** Stops every ringing half/open hat. Other drums and foot CHICK are independent. */
    fun chokeAll(actionTimeNs: Long = clock(), reason: String = "manual") {
        val submitted = clock()
        val hats = playing.filterValues { it.voice.ringsOpenHat }.keys
        hats.forEach { backend.stop(it); playing.remove(it) }
        trace.recordControl(AudioControlTraceEntry(actionTimeNs, submitted, reason, hats.size))
    }

    private fun stopAll() { playing.keys.forEach(backend::stop); playing.clear() }
}
