package ai.joydurm

import android.util.Xml
import android.util.Base64
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Emit portable JUnit XML when CI invokes the exact prebuilt test APK via am instrument. */
class SmokeXmlListener : RunListener() {
    private data class Test(val description: Description, val started: Long, var duration: Long = 0, var failure: Failure? = null)
    private val tests = linkedMapOf<String, Test>()
    private var probe: MainThreadProbe? = null
    override fun testRunStarted(description: Description) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test-reports").apply { mkdirs() }
        probe = MainThreadProbe(directory, InstrumentationRegistry.getArguments().getString("joydurmRunId") ?: "missing")
    }
    override fun testStarted(description: Description) {
        probe?.testName = description.displayName
        tests[description.displayName] = Test(description, System.nanoTime())
    }
    override fun testFailure(failure: Failure) { tests[failure.description.displayName]?.failure = failure }
    override fun testFinished(description: Description) { tests[description.displayName]?.let { it.duration = System.nanoTime() - it.started } }
    override fun testRunFinished(result: Result) {
        probe?.close()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test-reports").apply { mkdirs() }
        File(directory, "smoke-tests.xml").outputStream().use { stream ->
            val xml = Xml.newSerializer().apply { setOutput(stream, "UTF-8"); startDocument("UTF-8", true) }
            xml.startTag(null, "testsuite").attribute(null, "name", "JoyDurm Android smoke")
                .attribute(null, "runId", InstrumentationRegistry.getArguments().getString("joydurmRunId") ?: "missing")
                .attribute(null, "tests", result.runCount.toString()).attribute(null, "failures", result.failureCount.toString())
                .attribute(null, "skipped", result.ignoreCount.toString()).attribute(null, "time", (result.runTime / 1000.0).toString())
            tests.values.forEach { test ->
                xml.startTag(null, "testcase").attribute(null, "classname", test.description.className)
                    .attribute(null, "name", test.description.methodName).attribute(null, "time", (test.duration / 1e9).toString())
                test.failure?.let { xml.startTag(null, "failure").attribute(null, "message", it.message ?: "Failure").text(it.trace).endTag(null, "failure") }
                xml.endTag(null, "testcase")
            }
            xml.endTag(null, "testsuite").endDocument()
        }
        // Keep the actual files on the already-open instrumentation channel. New adb
        // connections can fail after Android finishes and kills the test process.
        // This runs after the same assertions and XML writer, before runner teardown.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("joydurmRunId") ?: "missing"
        listOf("smoke-tests.xml", "ordinary-3d-screen.png", "ordinary-3d-visibility.json", "thread-stalls.txt",
            "ui-failed-play.png", "ui-failed-welcome.png", "ui-failed-place.png", "ui-failed-sound_check.png",
            "ordinary-3d-render-probe.json", "ordinary-3d-surface.png", "ui-play-render-probe.json", "ui-play-surface.png", "assignment-permission-denied.png", "assignment-address-selected-no-imu.png", "assignment-test-fixture-paired-no-imu.png").forEach { name ->
            val file = File(directory, name)
            if (file.isFile && file.length() in 1..8_388_608L) {
                val bytes = file.readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                }
                val chunkSize = 12_288
                val count = (bytes.size + chunkSize - 1) / chunkSize
                repeat(count) { index ->
                    val offset = index * chunkSize
                    val encoded = Base64.encodeToString(bytes, offset, minOf(chunkSize, bytes.size - offset), Base64.NO_WRAP)
                    val evidence = JSONObject().put("schema", 1).put("runId", runId).put("file", name)
                        .put("bytes", bytes.size).put("sha256", digest).put("index", index).put("count", count).put("data", encoded)
                    instrumentation.sendStatus(2, Bundle().apply { putString("joydurmEvidence", evidence.toString()) })
                }
            }
        }
    }
}
