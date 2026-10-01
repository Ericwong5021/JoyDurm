package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class MotionTraceReplayTest {
    @Test fun deliveryBatchingDoesNotChangeEventTimeAndStaleFramesNeverReplayHits() {
        fun trace(delay: Long): List<MotionTraceEvent> {
            fun frame(t: Long,w: Double)=MotionTraceEvent.Frame(ImuFrame(ControllerIdentity("A","trace"),SessionId("s"),t,t+delay,0,
                ImuSample("A",t,Vec3(0.0,0.0,9.80665),Vec3(w,0.0,0.0))))
            return listOf(MotionTraceEvent.Assign(Role.LEFT_HAND,"A"),frame(0,0.0),MotionTraceEvent.Recenter(Role.LEFT_HAND),
                MotionTraceEvent.Bind(Role.LEFT_HAND,Drum.SNARE),frame(10_000_000,8.0),frame(20_000_000,3.0))
        }
        fun replay(delay: Long): List<Hit> {
            val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.roles.getValue(Role.LEFT_HAND).targets.clear(); MotionTrace.replay(trace(delay),e); return hits
        }
        val live=replay(0); val batched=replay(50_000_000)
        assertEquals(1,live.size); assertEquals(live.map { it.drum to it.timeNs },batched.map { it.drum to it.timeNs })
        assertEquals(70_000_000L,batched.single().receivedTimeNs)
        // Initial valid setup followed by stale motion: no command relies on absent initial data.
        val stale=mutableListOf<Hit>(); val e=DrumEngine { stale.add(it) }
        MotionTrace.replay(trace(0).take(4),e)
        MotionTrace.replay(trace(500_000_000).takeLast(2),e)
        assertTrue(stale.isEmpty())
    }
    @Test fun boundedTraceReportsDroppedRecords() {
        val trace=MotionTrace(2); repeat(3) { trace.record(MotionTraceEvent.Assign(Role.LEFT_HAND,"$it")) }
        assertEquals(1L,trace.dropped); assertEquals(2,trace.snapshot().size)
    }
    @Test fun recordedLayoutOwnsItsGeometryCopy() {
        val geometry=mutableMapOf(Drum.SNARE to PiecePose(1f,2f,3f)); val trace=MotionTrace()
        trace.record(MotionTraceEvent.Layout(geometry,1f,0f)); geometry.clear()
        val layout=trace.snapshot().single() as MotionTraceEvent.Layout
        assertEquals(PiecePose(1f,2f,3f),layout.pieces[Drum.SNARE])
    }

}
