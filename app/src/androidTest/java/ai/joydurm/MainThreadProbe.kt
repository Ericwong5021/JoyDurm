package ai.joydurm

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/** Test-only diagnostics. A stalled runner still fails its original assertions and gate. */
internal class MainThreadProbe(directory: File, runId: String) : AutoCloseable {
    private val report = File(directory, "thread-stalls.txt").apply {
        writeText("runId=$runId\nJava main-thread heartbeat; native ART dumps requested on a stall.\n")
    }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var testName = "before first test"
    @Volatile private var stopped = false
    @Volatile private var awaiting = false
    @Volatile private var acknowledgedAt = SystemClock.elapsedRealtime()
    private val heartbeat = Runnable { acknowledgedAt = SystemClock.elapsedRealtime(); awaiting = false }
    private val worker = Thread({
        var reported = false
        while (!stopped) {
            if (!awaiting) {
                acknowledgedAt = SystemClock.elapsedRealtime()
                awaiting = true
                handler.post(heartbeat)
                reported = false
            }
            val delay = SystemClock.elapsedRealtime() - acknowledgedAt
            if (awaiting && delay >= 3_000 && !reported) {
                reported = true
                val stacks = Thread.getAllStackTraces().entries.sortedBy { it.key.name }
                val text = buildString {
                    append("\nSTALLED test=$testName elapsedMs=${SystemClock.elapsedRealtime()} heartbeatDelayMs=$delay\n")
                    stacks.forEach { (thread, frames) ->
                        append("\"${thread.name}\" state=${thread.state}\n")
                        frames.take(40).forEach { append("  at $it\n") }
                    }
                }
                report.appendText(text)
                Log.e("JoyDurmThreadProbe", text)
                // SIGQUIT targets only this test process; ART retains JNI/native stacks too.
                Process.sendSignal(Process.myPid(), 3)
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
    }, "JoyDurm-test-main-probe").apply { isDaemon = true; start() }

    override fun close() {
        stopped = true
        handler.removeCallbacks(heartbeat)
        worker.interrupt()
    }
}
