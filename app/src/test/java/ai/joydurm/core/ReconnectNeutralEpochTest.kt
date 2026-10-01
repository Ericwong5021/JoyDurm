package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class ReconnectNeutralEpochTest {
    private fun sample(t: Long, speed: Double=0.0)=ImuSample("A",t,Vec3(0.0,0.0,9.80665),Vec3(speed,0.0,0.0))
    private fun ready(hits: MutableList<Hit>): DrumEngine = DrumEngine { hits.add(it) }.also {
        it.assign(Role.LEFT_HAND,"A"); it.roles.getValue(Role.LEFT_HAND).targets.clear(); it.process(sample(0)); it.recenter(Role.LEFT_HAND); it.bindTarget(Role.LEFT_HAND,Drum.SNARE)
        assertFalse(it.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter)
    }
    @Test fun lostControllerRequiresNewNeutralAndDoesNotHitBeforeRecenter() {
        val hits=mutableListOf<Hit>(); val e=ready(hits)
        val old=e.snapshot().roles.getValue(Role.LEFT_HAND).epoch
        e.deviceLost("A"); e.process(sample(1_000_000_000)); e.process(sample(1_010_000_000,8.0)); e.process(sample(1_020_000_000,3.0))
        val state=e.snapshot().roles.getValue(Role.LEFT_HAND)
        assertTrue(state.needsRecenter); assertNotEquals(old,state.epoch); assertTrue(hits.isEmpty())
        e.recenter(Role.LEFT_HAND)
        e.process(sample(1_030_000_000)); e.process(sample(1_040_000_000,8.0)); e.process(sample(1_050_000_000,3.0))
        assertEquals(1,hits.size)
    }
    @Test fun longGapInvalidatesNeutralAndPendingStroke() {
        val hits=mutableListOf<Hit>(); val e=ready(hits)
        e.process(sample(10_000_000,8.0)); e.process(sample(500_000_000,3.0))
        assertTrue(e.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter); assertTrue(hits.isEmpty())
    }
    @Test fun persistedTargetsCannotRestoreSessionNeutralAfterRestart() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }
        e.restoreRole(Role.LEFT_HAND,"A",null,0,1.0,2.2,90_000_000L,listOf(Target(Drum.SNARE,0.0,0.0)))
        e.process(sample(0)); e.process(sample(10_000_000,8.0)); e.process(sample(20_000_000,3.0))
        assertTrue(e.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter); assertTrue(hits.isEmpty())
    }
    @Test fun retiredSessionCannotReappearAfterNewSession() {
        val e=DrumEngine {}; e.assign(Role.LEFT_HAND,"A")
        fun frame(session: String,t: Long)=ImuFrame(ControllerIdentity("A","trace"),SessionId(session),t,t,0,sample(t))
        e.process(frame("one",0)); e.recenter(Role.LEFT_HAND)
        e.sessionLost("A",SessionId("one")); e.process(frame("two",100))
        e.recenter(Role.LEFT_HAND); val epoch=e.snapshot().roles.getValue(Role.LEFT_HAND).epoch
        e.process(frame("one",200))
        val s=e.snapshot().roles.getValue(Role.LEFT_HAND)
        assertEquals(SessionId("two"),s.session); assertEquals(100L,s.latestTimeNs); assertEquals(epoch,s.epoch); assertFalse(s.needsRecenter)
    }
    @Test fun transportBindingCanBeNamespacedWithoutLosingPhysicalIdentity() {
        val e=DrumEngine {}; val binding="android:descriptor/session:uuid"
        e.assign(Role.LEFT_HAND,binding)
        val identity=ControllerIdentity("android:descriptor","system",false,binding)
        val s=sample(10).copy(device=binding)
        e.process(ImuFrame(identity,SessionId("uuid"),10,10,0,s))
        assertEquals(10L,e.snapshot().roles.getValue(Role.LEFT_HAND).latestTimeNs)
        e.recenter(Role.LEFT_HAND)
        assertFalse(e.snapshot().roles.getValue(Role.LEFT_HAND).needsRecenter)
    }

}
