package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class AbsoluteBeatClockTest {
    @Test fun tenMinutesOfInjectedSchedulingJitterDoNotAccumulateDrift() {
        val clock = AbsoluteBeatClock(137)
        val start = 1_000_000_000_000L
        clock.start(start)
        val period = 60_000_000_000.0 / 137
        val lateness = longArrayOf(0, 3_000_000, 52_000_000, 120_000_000)
        for (beat in 0..1370) {
            val expected = start + (beat * period).roundToLong()
            assertEquals(expected, clock.nextDeadlineNs)
            val emitted = clock.poll(expected + lateness[beat % lateness.size])!!
            assertEquals(beat.toLong(), emitted.beatIndex)
            assertEquals(expected, emitted.timeNs)
            assertEquals(0L, emitted.skippedBeats)
            assertNull(clock.poll(expected + lateness[beat % lateness.size]))
        }
        assertEquals(start + 600_000_000_000L, start + (1370 * period).roundToLong())
    }

    @Test fun longStallSkipsMissedBeatsInsteadOfBurstingOldClicks() {
        val clock = AbsoluteBeatClock(120)
        clock.start(0)
        assertEquals(BeatDeadline(0, 0, 0), clock.poll(0))
        assertEquals(BeatDeadline(2_000_000_000, 4, 3), clock.poll(2_100_000_000))
        assertNull(clock.poll(2_100_000_000))
        assertEquals(2_500_000_000L, clock.nextDeadlineNs)
    }

    @Test fun bpmChangePreservesFractionalPhaseOfCurrentBeat() {
        val clock = AbsoluteBeatClock(120)
        clock.start(0)
        clock.poll(0)
        clock.setBpm(240, 250_000_000)
        assertEquals(375_000_000L, clock.nextDeadlineNs)
        assertNull(clock.poll(374_999_999))
        assertEquals(BeatDeadline(375_000_000, 1, 0), clock.poll(375_000_000))
        assertEquals(625_000_000L, clock.nextDeadlineNs)
        clock.setBpm(60, 500_000_000)
        assertEquals(1_000_000_000L, clock.nextDeadlineNs)
    }

    @Test fun roundingAtNonintegralPeriodNeverEmitsEarlyOrMissesBoundary() {
        val clock = AbsoluteBeatClock(137)
        clock.start(0)
        repeat(100) {
            val deadline = clock.nextDeadlineNs!!
            assertNull(clock.poll(deadline - 1))
            assertEquals(deadline, clock.poll(deadline)!!.timeNs)
        }
    }

    @Test fun stoppingAndRestartingBeginsNewPhaseWithoutOldBeats() {
        val clock = AbsoluteBeatClock()
        assertNull(clock.nextDeadlineNs)
        assertNull(clock.poll(100))
        clock.start(100)
        clock.poll(100)
        clock.stop()
        assertNull(clock.nextDeadlineNs)
        assertNull(clock.poll(1_000_000_000))
        clock.setBpm(200, 1_000_000_000)
        clock.start(1_000_000_000)
        assertEquals(BeatDeadline(1_000_000_000, 0, 0), clock.poll(1_000_000_000))
        assertEquals(1_300_000_000L, clock.nextDeadlineNs)
    }
}
