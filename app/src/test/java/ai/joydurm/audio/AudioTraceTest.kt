package ai.joydurm.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioTraceTest {
    @Test fun boundsMeasurementsButRetainsFailureCounters() {
        val trace = AudioTrace(2)
        trace.record(AudioTraceEntry(SoundVoice.KICK, 10, 20, 21, 0, AudioOutcome.BACKEND_REJECTED))
        trace.record(AudioTraceEntry(SoundVoice.KICK, 20, 30, 31, 1, AudioOutcome.PLAYED))
        trace.record(AudioTraceEntry(SoundVoice.SNARE, 30, 45, 46, 2, AudioOutcome.PLAYED))
        val snapshot = trace.snapshot()
        assertEquals(listOf(20L, 30L), snapshot.entries.map { it.sourceActionTimeNs })
        assertEquals(1L, snapshot.overwrittenEntries)
        assertEquals(1L, snapshot.failedSubmissions)
        assertEquals(2L, snapshot.counts[AudioOutcome.PLAYED])
    }

    @Test fun reportsSubmissionPercentilesAndExportsActualThreeTimestamps() {
        val trace = AudioTrace()
        listOf(10L, 20L, 100L).forEachIndexed { i, delay ->
            trace.record(AudioTraceEntry(SoundVoice.SNARE, 1_000, 1_000 + delay, 1_005 + delay, i + 1, AudioOutcome.PLAYED))
        }
        trace.record(AudioTraceEntry(SoundVoice.KICK, 2_000, 1_000, 1_005, 4, AudioOutcome.PLAYED))
        assertEquals(20L, trace.snapshot().actionToSubmitPercentileNs(0.5))
        assertEquals(100L, trace.snapshot().actionToSubmitPercentileNs(0.95))
        val output = File.createTempFile("audio-trace", ".csv")
        try {
            trace.export(output)
            val csv = output.readText()
            assertTrue(csv.contains("SNARE,1000,1010,1015,1,PLAYED"))
            assertTrue(csv.contains("acoustic onset is not measured"))
            assertTrue(csv.contains("# count_PLAYED,4"))
        } finally { output.delete() }
    }

    @Test fun controlsAreBoundedIndependentlyAndCsvEscapesReason() {
        val trace = AudioTrace(1)
        trace.record(AudioTraceEntry(SoundVoice.HAT_OPEN, 10, 11, 12, 1, AudioOutcome.PLAYED))
        trace.recordControl(AudioControlTraceEntry(20, 21, "first", 1))
        trace.recordControl(AudioControlTraceEntry(30, 31, "focus,\"loss\"\n", 2))
        assertEquals(1, trace.snapshot().entries.size)
        assertEquals(1, trace.snapshot().controls.size)
        assertEquals(1L, trace.snapshot().overwrittenControls)
        assertTrue(trace.csv().contains("# control,30,31,\"focus,'loss' \",2"))
    }

    @Test fun submissionTimeIsCapturedAroundBackendCallWithInjectedClock() {
        var now = 1_000L
        val slots = SampleSlots()
        slots.register("0:KICK", 1); slots.complete(1, true)
        val trace = AudioTrace()
        val backend = object : SampleBackend {
            override fun play(sampleId: Int, gain: Float): Int { now += 7; return 42 }
            override fun stop(streamId: Int) = Unit
        }
        val playback = VoicePlayback(slots, backend, { now }, trace)
        playback.resume()
        playback.playVoice(SoundVoice.KICK, 1f, 0, 1f, 950)
        assertEquals(AudioTraceEntry(SoundVoice.KICK, 950, 1_000, 1_007, 42, AudioOutcome.PLAYED), trace.snapshot().entries.single())
    }
}
