package ai.joydurm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.input.ControllerHub
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ControllerHubLifecycleIntegrationTest {
    @Test(timeout = 20_000) fun restartCyclesAndRealUdpClockExchangeDoNotDeadlockOrClaimHardware() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = AtomicInteger()
        val received = CountDownLatch(3)
        val hub = ControllerHub(context, { samples.incrementAndGet(); received.countDown() }, {})
        val token = "integration-test-token"
        try {
            repeat(5) {
                hub.start()
                hub.listenBridgeOnAvailablePort(token)
                assertTrue(hub.diagnostics().bridgePort!! in 1024..65535)
                assertTrue(hub.diagnostics().bridgeRunning)
                assertEquals("HARDWARE_PENDING", JSONObject(hub.capabilityReport()).getString("status"))
                hub.stop()
                assertFalse(hub.diagnostics().running)
                assertFalse(hub.diagnostics().bridgeRunning)
            }
            hub.start()
            hub.listenBridgeOnAvailablePort(token)
            val port = hub.diagnostics().bridgePort!!
            UdpClockTestSource(hub, port, token).use { source ->
                source.awaitVerifiedClock()
                assertEquals(0, samples.get())
                val frames = JSONArray()
                repeat(3) { index ->
                    frames.put(JSONObject().put("sourceTimeNs", 0L)
                        .put("ax", 0.0).put("ay", 0.0).put("az", 9.80665)
                        .put("gx", 0.0).put("gy", 0.0).put("gz", 0.0))
                }
                val packet=PreparedMotionPacket(JSONObject().put("v", 2).put("type", "motion").put("token", token)
                    .put("device", "instrumentation-simulator").put("sessionId", UUID.randomUUID().toString())
                    .put("identityStable", false).put("identitySource", "simulator")
                    .put("seq", 0L).put("timer", 1).put("sourceReadNs", 0L).put("samples", frames))
                val offsets=longArrayOf(-10_000_000,-5_000_000,0)
                val sourceRead = SystemClock.elapsedRealtimeNanos()
                source.send(packet.stamp(sourceRead,offsets))
                val allReceived=received.await(3, TimeUnit.SECONDS)
                assertTrue("Three source-timed UDP samples were not delivered; diagnostics=${hub.diagnostics()}", allReceived)
                assertEquals(3, samples.get())
                assertEquals("HARDWARE_PENDING", JSONObject(hub.capabilityReport()).getString("status"))
            }
        } finally {
            hub.stop()
        }
    }
}
