package ai.joydurm.audio

import ai.joydurm.core.Drum
import ai.joydurm.core.HatControl
import ai.joydurm.core.Hit
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackTest {
    private class FakeBackend : SampleBackend {
        data class Played(val sample: Int, val gain: Float)
        val played = mutableListOf<Played>()
        val stopped = mutableListOf<Int>()
        var reject = false
        override fun play(sampleId: Int, gain: Float): Int {
            played += Played(sampleId, gain)
            return if (reject) 0 else played.size
        }
        override fun stop(streamId: Int) { stopped += streamId }
    }

    private class Fixture {
        var now = 1_000L
        val backend = FakeBackend()
        val slots = SampleSlots()
        val trace = AudioTrace()
        val playback = VoicePlayback(slots, backend, { now }, trace)
        init {
            SoundVoice.entries.forEachIndexed { i, voice ->
                slots.register("0:${voice.name}", i + 1); slots.complete(i + 1, true)
            }
            playback.resume()
        }
        fun play(voice: SoundVoice, volume: Float = 0.5f) = playback.playVoice(voice, 0.8f, 0, volume, 900)
    }

    @Test fun distinctClosedHalfOpenAndChickSamplesArePlayable() {
        val f = Fixture()
        val voices = listOf(SoundVoice.HAT, SoundVoice.HAT_HALF, SoundVoice.HAT_OPEN, SoundVoice.CHICK)
        voices.forEach { assertTrue(f.play(it) > 0) }
        assertEquals(4, f.backend.played.map { it.sample }.distinct().size)
        assertTrue(voices.all { it in SoundVoice.importable })
        assertEquals(Drum.entries.map { it.name }.toSet(),
            SoundVoice.importable.filter { it != SoundVoice.HAT_HALF && it != SoundVoice.HAT_OPEN }.map { it.name }.toSet())
        assertFalse(SoundVoice.CLICK in SoundVoice.importable)
    }

    @Test fun hitSelectsVoiceByContinuousOpenness() {
        val f = Fixture()
        listOf(0f, 0.4f, 1f).forEach { f.playback.play(Hit(Drum.HAT, 0.8f, it, 900), 0, 0.5f) }
        assertEquals(listOf(SoundVoice.HAT, SoundVoice.HAT_HALF, SoundVoice.HAT_OPEN),
            f.trace.snapshot().entries.map { it.voice })
    }

    @Test fun closingFootStopsOpenTailsWithoutReplayingChick() {
        val f = Fixture()
        val hat = f.play(SoundVoice.HAT_OPEN)
        val snare = f.play(SoundVoice.SNARE)
        val half = f.play(SoundVoice.HAT_HALF)
        f.playback.control(HatControl.Openness(0f, 950))
        assertEquals(listOf(hat, half), f.backend.stopped)
        assertFalse(snare in f.backend.stopped)
        assertEquals(3, f.backend.played.size)
        assertEquals(950L, f.trace.snapshot().controls.last().sourceActionTimeNs)
    }

    @Test fun explicitChickChokesThenPlaysExactlyOneFootVoice() {
        val f = Fixture()
        val hat = f.play(SoundVoice.HAT_OPEN)
        f.playback.play(Hit(Drum.CHICK, 0.7f, 0f, 950), 0, 0.5f)
        assertEquals(listOf(hat), f.backend.stopped)
        assertEquals(2, f.backend.played.size)
        assertEquals(SoundVoice.CHICK, f.trace.snapshot().entries.last().voice)
    }

    @Test fun pauseStopsAllVoicesAndResumeDoesNotReplayAnyOfThem() {
        val f = Fixture()
        val hat = f.play(SoundVoice.HAT_OPEN)
        val snare = f.play(SoundVoice.SNARE)
        f.playback.pause("focus-loss")
        assertEquals(listOf(hat, snare), f.backend.stopped)
        assertEquals(0, f.play(SoundVoice.KICK))
        assertEquals(2, f.backend.played.size)
        assertEquals(AudioOutcome.PAUSED, f.trace.snapshot().entries.last().outcome)
        f.playback.resume()
        assertEquals(2, f.backend.played.size)
        assertTrue(f.play(SoundVoice.KICK) > 0)
        assertEquals(3, f.backend.played.size)
    }

    @Test fun missingDecodedSamplesAndBackendZeroAreCounted() {
        val f = Fixture()
        assertEquals(0, f.playback.playVoice(SoundVoice.KICK, 0.8f, 1, 0.5f, 900))
        f.backend.reject = true
        assertEquals(0, f.play(SoundVoice.KICK))
        val snapshot = f.trace.snapshot()
        assertEquals(1L, snapshot.counts[AudioOutcome.SAMPLE_NOT_READY])
        assertEquals(1L, snapshot.counts[AudioOutcome.BACKEND_REJECTED])
        assertEquals(2L, snapshot.failedSubmissions)
        assertTrue(f.backend.stopped.isEmpty())
    }

    @Test fun gainRejectsNonfiniteValuesAndBoundsPlayback() {
        val f = Fixture()
        assertEquals(0, f.play(SoundVoice.KICK, Float.NaN))
        assertEquals(0, f.playback.playVoice(SoundVoice.KICK, Float.POSITIVE_INFINITY, 0, 0.5f, 900))
        f.playback.playVoice(SoundVoice.KICK, 2f, 0, 2f, 900)
        assertEquals(1f, f.backend.played.single().gain, 0f)
        assertEquals(2L, f.trace.snapshot().counts[AudioOutcome.INVALID_GAIN])
    }

    @Test fun failureInOneHatSlotKeepsOtherVoicesAndBankIsolated() {
        val f = Fixture()
        val old = f.slots.id("0:HAT_HALF")
        f.slots.register("0:HAT_HALF", 101)
        f.slots.complete(101, false)
        f.slots.register("1:HAT_OPEN", 102)
        f.slots.complete(102, true)
        f.play(SoundVoice.HAT_HALF)
        f.play(SoundVoice.HAT_OPEN)
        f.playback.playVoice(SoundVoice.HAT_OPEN, 0.8f, 1, 0.5f, 900)
        assertEquals(old, f.backend.played[0].sample)
        assertEquals(f.slots.id("0:HAT_OPEN"), f.backend.played[1].sample)
        assertEquals(102, f.backend.played[2].sample)
    }

    @Test fun voiceBookkeepingIsBoundedDuringSustainedPlayback() {
        val f = Fixture()
        repeat(100) { f.play(SoundVoice.HAT_OPEN) }
        assertEquals(76, f.backend.stopped.size)
        f.playback.pause()
        assertEquals(100, f.backend.stopped.size)
    }
}
