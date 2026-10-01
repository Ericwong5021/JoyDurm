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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
                val port = DatagramSocket(0).use { it.localPort }
                hub.listenBridge(port, token)
                assertTrue(hub.diagnostics().bridgeRunning)
                assertEquals("HARDWARE_PENDING", JSONObject(hub.capabilityReport()).getString("status"))
                hub.stop()
                assertFalse(hub.diagnostics().running)
                assertFalse(hub.diagnostics().bridgeRunning)
            }
            hub.start()
            val port = DatagramSocket(0).use { it.localPort }
            hub.listenBridge(port, token)
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { socket ->
                socket.soTimeout = 3_000
                fun send(json: JSONObject) {
                    val bytes = json.toString().toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
                }
                // Unsynchronized motion is rejected and answered at the sender's real UDP source port.
                send(JSONObject().put("v", 2).put("type", "motion").put("token", token))
                val packet = DatagramPacket(ByteArray(8192), 8192)
                socket.receive(packet)
                assertEquals(port, packet.port)
                val sync = JSONObject(String(packet.data, 0, packet.length))
                assertEquals("sync", sync.getString("type"))
                val sourceReceive = SystemClock.elapsedRealtimeNanos()
                send(JSONObject().put("v", 2).put("type", "sync_reply").put("token", token)
                    .put("nonce", sync.getString("nonce")).put("clientSendNs", sync.getLong("clientSendNs"))
                    .put("sourceReceiveNs", sourceReceive).put("sourceSendNs", SystemClock.elapsedRealtimeNanos()))
                val sourceRead = SystemClock.elapsedRealtimeNanos()
                val frames = JSONArray()
                repeat(3) { index ->
                    frames.put(JSONObject().put("sourceTimeNs", sourceRead - (2 - index) * 5_000_000L)
                        .put("ax", 0.0).put("ay", 0.0).put("az", 9.80665)
                        .put("gx", 0.0).put("gy", 0.0).put("gz", 0.0))
                }
                send(JSONObject().put("v", 2).put("type", "motion").put("token", token)
                    .put("device", "instrumentation-simulator").put("sessionId", UUID.randomUUID().toString())
                    .put("identityStable", false).put("identitySource", "simulator")
                    .put("seq", 0L).put("timer", 1).put("sourceReadNs", sourceRead).put("samples", frames))
                assertTrue("Three source-timed UDP samples were not delivered", received.await(3, TimeUnit.SECONDS))
                assertEquals(3, samples.get())
                assertEquals("HARDWARE_PENDING", JSONObject(hub.capabilityReport()).getString("status"))
            }
        } finally {
            hub.stop()
        }
    }
}
