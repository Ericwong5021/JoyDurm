package ai.joydurm.render

import ai.joydurm.core.Drum
import ai.joydurm.core.Hit
import org.junit.Assert.*
import org.junit.Test

class VisualTimelineTest {
    @Test fun delayedConsumptionUsesActionTimeAndDoesNotRestartAnimation() {
        val queue=HitAnimationQueue()
        queue.offer(Hit(Drum.SNARE,1f,0f,1_000_000_000L))
        val pulse=queue.advance(1_150_000_000L).getValue(Drum.SNARE)
        assertEquals(0.15,pulse.ageSeconds(1_150_000_000L),1e-9)
        assertTrue(queue.advance(3_100_000_000L).isEmpty())
    }
    @Test fun oldOrFutureActionsAreNotReplayedAfterPause() {
        val queue=HitAnimationQueue()
        queue.offer(Hit(Drum.KICK,1f,0f,0))
        queue.offer(Hit(Drum.SNARE,1f,0f,10_000_000_000L))
        assertTrue(queue.advance(5_000_000_000L).isEmpty())
    }
    @Test fun queueIsBoundedAndOutOfOrderActionsCannotReplaceNewerPulse() {
        val queue=HitAnimationQueue(4)
        repeat(20) { queue.offer(Hit(Drum.KICK,0.7f,0f,it*1_000_000L)) }
        assertEquals(4,queue.pendingCount)
        assertEquals(19_000_000L,queue.advance(20_000_000L).getValue(Drum.KICK).timeNs)
        queue.offer(Hit(Drum.KICK,1f,0f,18_000_000L))
        assertEquals(19_000_000L,queue.advance(21_000_000L).getValue(Drum.KICK).timeNs)
        queue.close(); queue.offer(Hit(Drum.KICK,1f,0f,22_000_000L)); assertEquals(0,queue.pendingCount)
    }
    @Test fun hatResponseIsIndependentOfFrameRate() {
        fun run(frames: Int): Float {
            val hat=HatVisualInterpolator(); hat.update(0f,0)
            for(i in 1..frames)hat.update(1f,i*1_000_000_000L/frames)
            return hat.update(1f,1_000_000_000L)
        }
        assertEquals(run(30),run(60),1e-6f); assertEquals(run(60),run(120),1e-6f)
        val hat=HatVisualInterpolator(); hat.update(0f,0)
        assertTrue(hat.update(1f,25_000_000L) in 0.63f..0.64f)
        assertTrue(hat.update(1f,50_000_000L)>0.86f)
        assertEquals(0f,hat.update(0f,5_000_000_000L),1e-6f)
    }
}
