package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class DrumLayoutHitConsistencyTest {
    private fun sample(t: Long,w: Double=0.0)=ImuSample("A",t,Vec3(0.0,0.0,9.80665),Vec3(w,0.0,0.0))
    @Test fun changedLayoutDisablesOldTargetsUntilExplicitRebinding() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.assign(Role.LEFT_HAND,"A")
        e.process(sample(0)); e.recenter(Role.LEFT_HAND); e.bindTarget(Role.LEFT_HAND,Drum.SNARE)
        e.replaceLayout(mapOf(Drum.SNARE to PiecePose(1f,0f,0f),Drum.HAT to PiecePose(-1f,0f,0f)),1f,0f)
        e.process(sample(10_000_000,8.0)); e.process(sample(20_000_000,3.0)); assertTrue(hits.isEmpty())
        assertTrue(e.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter)
        e.recenter(Role.LEFT_HAND); e.resetTargets(); assertTrue(e.snapshot().roles.getValue(Role.LEFT_HAND).targets.isEmpty())
        e.process(sample(30_000_000)); e.process(sample(40_000_000,8.0)); e.process(sample(50_000_000,3.0)); assertTrue(hits.isEmpty())
        e.bindTarget(Role.LEFT_HAND,Drum.HAT)
        e.process(sample(60_000_000)); e.process(sample(160_000_000,8.0)); e.process(sample(170_000_000,3.0))
        assertEquals(listOf(Drum.HAT),hits.map { it.drum })
    }
    @Test fun identicalCoordinatesWithNewArAnchorStillInvalidateTargets() {
        val e=DrumEngine {}; e.assign(Role.LEFT_HAND,"A"); e.process(sample(0)); e.recenter(Role.LEFT_HAND)
        e.replaceLayout(emptyMap(),1f,0f,true)
        assertEquals(1L,e.snapshot().layout.revision); assertTrue(e.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter)
    }
    @Test fun peakPoseAndEventTimeAreUsedInsteadOfDecelerationPose() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.assign(Role.LEFT_HAND,"A")
        e.process(sample(0)); e.recenter(Role.LEFT_HAND)
        e.roles.getValue(Role.LEFT_HAND).targets=mutableListOf(Target(Drum.SNARE,0.0,-0.08),Target(Drum.TOM1,0.0,-0.75))
        fun frame(t: Long,w: Double)=ImuFrame(ControllerIdentity("A","trace"),SessionId("s"),t,t+20_000_000,0,sample(t,w))
        // Start the frame session before recentering; entering a session invalidates prior neutral.
        e.process(frame(5_000_000,0.0)); e.recenter(Role.LEFT_HAND)
        e.process(frame(15_000_000,8.0)); e.process(frame(165_000_000,4.0))
        assertEquals(1,hits.size); assertEquals(Drum.SNARE,hits.single().drum)
        assertEquals(15_000_000L,hits.single().timeNs); assertEquals(185_000_000L,hits.single().receivedTimeNs)
    }
    @Test fun ambiguousOverlappingTargetsRejectStroke() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.assign(Role.LEFT_HAND,"A")
        e.process(sample(0)); e.recenter(Role.LEFT_HAND)
        e.roles.getValue(Role.LEFT_HAND).targets=mutableListOf(Target(Drum.SNARE,0.0,0.0),Target(Drum.TOM1,0.0,0.0))
        e.process(sample(10_000_000,8.0)); e.process(sample(20_000_000,3.0)); assertTrue(hits.isEmpty())
    }
}
