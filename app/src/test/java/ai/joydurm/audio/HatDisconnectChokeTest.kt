package ai.joydurm.audio

import ai.joydurm.core.HatControl
import org.junit.Assert.*
import org.junit.Test

/** Checks the audio effect of an engine's independent disconnect control. */
class HatDisconnectChokeTest {
    @Test fun disconnectStopsRingingHatAndNeverSynthesizesFootStrike() {
        val slots = SampleSlots()
        slots.register("0:HAT_OPEN", 1); slots.complete(1, true)
        slots.register("0:SNARE", 2); slots.complete(2, true)
        val played = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val backend = object : SampleBackend {
            override fun play(sampleId: Int, gain: Float): Int { played += sampleId; return played.size }
            override fun stop(streamId: Int) { stopped += streamId }
        }
        val trace = AudioTrace()
        val playback = VoicePlayback(slots, backend, { 1_000L }, trace)
        playback.resume()
        playback.playVoice(SoundVoice.HAT_OPEN, 1f, 0, 1f, 900)
        playback.playVoice(SoundVoice.SNARE, 1f, 0, 1f, 950)
        playback.control(HatControl.ChokeAll(990, "left-foot-disconnected"))
        assertEquals(listOf(1), stopped)
        assertEquals(listOf(1, 2), played)
        assertEquals(2, trace.snapshot().entries.size)
        assertEquals(AudioControlTraceEntry(990, 1_000, "left-foot-disconnected", 1), trace.snapshot().controls.single())
        // Repeated loss notification is idempotent, and a new hit is independent.
        playback.control(HatControl.ChokeAll(995, "left-foot-disconnected"))
        assertEquals(listOf(1), stopped)
        playback.playVoice(SoundVoice.HAT_OPEN, 1f, 0, 1f, 999)
        assertEquals(listOf(1, 2, 1), played)
        assertTrue(trace.snapshot().entries.none { it.voice == SoundVoice.CHICK })
    }
}
