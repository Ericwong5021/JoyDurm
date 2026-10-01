package ai.joydurm

import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File

/** Emit portable JUnit XML when CI invokes the exact prebuilt test APK via am instrument. */
class SmokeXmlListener : RunListener() {
    private data class Test(val description: Description, val started: Long, var duration: Long = 0, var failure: Failure? = null)
    private val tests = linkedMapOf<String, Test>()
    override fun testStarted(description: Description) { tests[description.displayName] = Test(description, System.nanoTime()) }
    override fun testFailure(failure: Failure) { tests[failure.description.displayName]?.failure = failure }
    override fun testFinished(description: Description) { tests[description.displayName]?.let { it.duration = System.nanoTime() - it.started } }
    override fun testRunFinished(result: Result) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test-reports").apply { mkdirs() }
        File(directory, "smoke-tests.xml").outputStream().use { stream ->
            val xml = Xml.newSerializer().apply { setOutput(stream, "UTF-8"); startDocument("UTF-8", true) }
            xml.startTag(null, "testsuite").attribute(null, "name", "JoyDurm Android smoke")
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
    }
}
