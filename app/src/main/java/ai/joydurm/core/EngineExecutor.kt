package ai.joydurm.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/** Bounded FIFO. Commands never block the UI; rejection is visible in the returned future. */
class EngineExecutor(private val engine: DrumEngine, capacity: Int = 512,
                     private val queueClock: () -> Long = System::nanoTime): AutoCloseable {
    private class Work(val run: () -> Unit, val reject: () -> Unit)
    private val queue=ArrayBlockingQueue<Work>(capacity.also { require(it>0) })
    private val lifecycle=Any()
    private val overflow=AtomicBoolean()
    private val losses=AtomicReferenceArray<String?>(Role.entries.size)
    @Volatile private var accepting=true
    @Volatile private var published=engine.snapshot()
    val rejected=AtomicLong()
    val failures=AtomicLong()
    val terminated=CompletableFuture<Unit>()
    val pendingCount get() = queue.size
    private val worker=Thread({
        try {
            while(accepting || queue.isNotEmpty()) {
                val work=try { queue.take() } catch(_: InterruptedException) { continue }
                Role.entries.forEach { role -> losses.getAndSet(role.ordinal,null)?.let { engine.deviceLost(it) } }
                if(overflow.getAndSet(false)) engine.roles.values.mapNotNull { it.device }.distinct().forEach { engine.suspendMotion(it) }
                try { work.run() } catch(_: Throwable) { failures.incrementAndGet() }
                published=engine.snapshot()
            }
        } finally { terminated.complete(Unit) }
    },"JoyDurm-engine").apply { isDaemon=true; start() }
    fun snapshot(): EngineSnapshot = published
    /** Four role slots make disconnect control independent of a full sample/command queue. */
    fun deviceLost(device: String) {
        synchronized(lifecycle) {
            if(!accepting) return
            published.roles.forEach { (role,state) -> if(state.device==device) losses.set(role.ordinal,device) }
            // Empty queues need a wake-up; a full queue already guarantees the writer will drain losses.
            queue.offer(Work({},{}))
        }
    }

    private fun enqueue(work: Work): Boolean = synchronized(lifecycle) {
        if(!accepting || !queue.offer(work)) { rejected.incrementAndGet(); work.reject(); false } else true
    }
    private fun motion(device: String, process: () -> Unit): Boolean {
        val enqueued=queueClock()
        return enqueue(Work({
            // Never replay a queued gesture after a stalled writer; this clock measures queue dwell only.
            if(queueClock()-enqueued>250_000_000L) engine.suspendMotion(device) else process()
        },{ overflow.set(true) }))
    }
    fun submit(sample: ImuSample): Boolean = motion(sample.device) { engine.process(sample) }
    fun submit(frame: ImuFrame): Boolean = motion(frame.sample.device) { engine.process(frame) }
    fun <T> call(command: (DrumEngine) -> T): CompletableFuture<T> {
        val result=CompletableFuture<T>()
        enqueue(Work({
            try {
                val value=command(engine)
                published=engine.snapshot()
                result.complete(value)
            } catch(t: Throwable) { result.completeExceptionally(t) }
        },{ result.completeExceptionally(RejectedExecutionException("Engine queue closed or full")) }))
        return result
    }
    /** Discards pending work and resolves every pending command; does not wait on the calling thread. */
    override fun close() {
        synchronized(lifecycle) {
            if(!accepting) return
            accepting=false
            val discarded=ArrayList<Work>(); queue.drainTo(discarded); discarded.forEach { it.reject() }
            worker.interrupt()
        }
    }
}
