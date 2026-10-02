package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class ExecutorStressTest {
    @Test fun fourConcurrentProducersKeepBoundedQueueAndImmutableSnapshots() {
        val engine=DrumEngine {}; val executor=EngineExecutor(engine,32)
        try {
            Role.entries.forEach { role -> executor.call { it.assign(role,role.name) }.get(2,TimeUnit.SECONDS) }
            val old=executor.snapshot()
            val start=CountDownLatch(1)
            val producerFailures=ConcurrentLinkedQueue<Throwable>()
            val producers=Role.entries.map { role -> Thread {
                try { start.await()
                repeat(5000) { i ->
                    executor.submit(ImuSample(role.name,i*5_000_000L,Vec3(0.0,0.0,9.80665),Vec3()))
                    assertTrue(executor.pendingCount<=32)
                } } catch(t: Throwable) { producerFailures.add(t) }
            }.apply { start() } }
            start.countDown(); producers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertTrue(producerFailures.toString(),producerFailures.isEmpty())
            // A command has explicit overload feedback; retry after the producer burst has ended.
            var barrier=executor.call { it.snapshot() }
            val deadline=System.nanoTime()+2_000_000_000L
            while(barrier.isCompletedExceptionally && System.nanoTime()<deadline) { Thread.yield(); barrier=executor.call { it.snapshot() } }
            val current=barrier.get(2,TimeUnit.SECONDS)
            assertTrue(current.revision>old.revision); assertTrue(old.roles.values.all { it.latest==null })
            assertTrue(executor.rejected.get()>0)
            assertEquals(0L,executor.failures.get())
        } finally { executor.close(); executor.terminated.get(2,TimeUnit.SECONDS) }
    }
    @Test fun slowCommandDoesNotBlockSubmittingUiAndClosedQueueResolvesPendingFutures() {
        val executor=EngineExecutor(DrumEngine {},2); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val blocking=executor.call { entered.countDown(); release.await(); 1 }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val pending=executor.call { 2 }; executor.call { 3 }
        val rejected=executor.call { 4 }; assertTrue(rejected.isCompletedExceptionally)
        executor.close(); release.countDown()
        try { pending.get(2,TimeUnit.SECONDS); fail("pending command should be rejected at close") } catch(_: ExecutionException) {}
        // close interrupts active work. Its future resolves success or exception, never hangs.
        runCatching { blocking.get(2,TimeUnit.SECONDS) }
        executor.terminated.get(2,TimeUnit.SECONDS)
        assertTrue(executor.call { 5 }.isCompletedExceptionally)
    }
    @Test fun stalledWriterDropsQueuedGestureInsteadOfPlayingItLate() {
        val hits=mutableListOf<Hit>(); val engine=DrumEngine { hits.add(it) }
        engine.assign(Role.LEFT_HAND,"A"); engine.roles.getValue(Role.LEFT_HAND).targets.clear()
        engine.process(ImuSample("A",0,Vec3(0.0,0.0,9.80665),Vec3()))
        engine.recenter(Role.LEFT_HAND); engine.bindTarget(Role.LEFT_HAND,Drum.SNARE)
        val clock=AtomicLong(); val executor=EngineExecutor(engine,16,clock::get)
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        try {
            executor.call { entered.countDown(); release.await() }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue(executor.submit(ImuSample("A",10_000_000,Vec3(0.0,0.0,9.80665),Vec3(8.0,0.0,0.0))))
            assertTrue(executor.submit(ImuSample("A",20_000_000,Vec3(0.0,0.0,9.80665),Vec3(3.0,0.0,0.0))))
            clock.set(1_000_000_000); release.countDown()
            val state=executor.call { it.snapshot() }.get(2,TimeUnit.SECONDS)
            assertTrue(state.roles.getValue(Role.LEFT_HAND).needsRecenter); assertTrue(hits.isEmpty())
        } finally { release.countDown(); executor.close(); executor.terminated.get(2,TimeUnit.SECONDS) }
    }

    @Test fun disconnectControlSurvivesAFullCommandQueue() {
        val controls=mutableListOf<HatControl>(); val engine=DrumEngine({ controls.add(it) }) {}
        engine.assign(Role.LEFT_FOOT,"F"); controls.clear()
        val executor=EngineExecutor(engine,1); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        try {
            executor.call { entered.countDown(); release.await() }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val queued=executor.call { 1 }; assertTrue(executor.call { 2 }.isCompletedExceptionally)
            executor.deviceLost("F"); release.countDown(); queued.get(2,TimeUnit.SECONDS)
            val state=executor.call { it.snapshot() }.get(2,TimeUnit.SECONDS)
            assertTrue(state.roles.getValue(Role.LEFT_FOOT).needsRecenter)
            assertEquals(1,controls.count { it is HatControl.ChokeAll })
        } finally { release.countDown(); executor.close(); executor.terminated.get(2,TimeUnit.SECONDS) }
    }

}
