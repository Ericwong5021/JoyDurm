package ai.joydurm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.joydurm.core.ImuFrame
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real Android socket, input HandlerThread, clock exchange and sample accounting. */
@RunWith(AndroidJUnit4::class)
class ControllerHubSocketIntegrationTest {
    @Test(timeout = 15_000) fun twoBurstedBatchesDeliverAllSixSourceFramesAndDelayedMotionIsRejected() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val frames=CopyOnWriteArrayList<ImuFrame>(); val received=CountDownLatch(6)
        val hub=ControllerHub(context,{}, {})
        hub.onFrame={ frame -> frames.add(frame); received.countDown() }
        val token="android-socket-test-0123456789abcdef"
        val session=UUID.randomUUID().toString()
        try {
            hub.start()
            val port=DatagramSocket(0).use { it.localPort }
            hub.listenBridge(port,token)
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { sender ->
                sender.soTimeout=2_000
                fun send(json: JSONObject) {
                    val bytes=json.toString().toByteArray(Charsets.UTF_8)
                    sender.send(DatagramPacket(bytes,bytes.size,InetAddress.getLoopbackAddress(),port))
                }
                // This bootstrap cannot deliver motion before its source clock is established.
                send(JSONObject().put("v",2).put("type","motion").put("token",token))
                val syncPacket=DatagramPacket(ByteArray(8193),8193); sender.receive(syncPacket)
                val sourceReceive=SystemClock.elapsedRealtimeNanos()
                assertEquals(port,syncPacket.port)
                val sync=JSONObject(String(syncPacket.data,0,syncPacket.length,Charsets.UTF_8))
                assertEquals("sync",sync.getString("type")); assertTrue(frames.isEmpty())
                send(JSONObject().put("v",2).put("type","sync_reply").put("token",token)
                    .put("nonce",sync.getString("nonce")).put("clientSendNs",sync.getLong("clientSendNs"))
                    .put("sourceReceiveNs",sourceReceive).put("sourceSendNs",SystemClock.elapsedRealtimeNanos()))
                fun motion(seq: Long, sourceRead: Long, times: List<Long>) = JSONObject()
                    .put("v",2).put("type","motion").put("token",token).put("device","android-socket-simulator")
                    .put("name","UDP regression simulator").put("identityStable",false).put("identitySource","simulator")
                    .put("sessionId",session).put("seq",seq).put("timer",(seq*3).and(255)).put("sourceReadNs",sourceRead)
                    .put("samples",JSONArray().also { samples -> times.forEach { time ->
                        samples.put(JSONObject().put("sourceTimeNs",time).put("ax",0.0).put("ay",0.0).put("az",9.80665)
                            .put("gx",0.0).put("gy",0.0).put("gz",0.0))
                    } })
                val sourceRead=SystemClock.elapsedRealtimeNanos()
                val times=(0..5).map { sourceRead-25_000_000L+it*5_000_000L }
                send(motion(0,sourceRead,times.take(3)))
                Thread.sleep(1)
                send(motion(1,sourceRead,times.drop(3)))
                assertTrue("Six source-timed frames were not delivered; diagnostics=${hub.diagnostics()}",received.await(3,TimeUnit.SECONDS))
                assertEquals(times,frames.map { it.sourceTimeNs })
                assertEquals(6L,hub.diagnostics().devices.single().sampleCount)
                assertTrue(frames.zipWithNext().all { (a,b) -> a.sample.timeNs < b.sample.timeNs })
                assertTrue(frames.all { it.clockErrorNs in 0..30_000_000L && it.session.value==session })
                val lateRead=SystemClock.elapsedRealtimeNanos()
                send(motion(2,lateRead,listOf(lateRead-500_000_000L)))
                val deadline=SystemClock.elapsedRealtime()+1_000
                while ((hub.diagnostics().bridgeStatistics?.staleSamples ?: 0)==0L && SystemClock.elapsedRealtime()<deadline) Thread.sleep(10)
                assertEquals(1L,hub.diagnostics().bridgeStatistics!!.staleSamples)
                assertEquals(6,frames.size)
                assertEquals("HARDWARE_PENDING",JSONObject(hub.capabilityReport()).getString("status"))
            }
        } finally { hub.stop() }
        assertFalse(hub.diagnostics().running)
    }
}
