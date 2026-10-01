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
            hub.listenBridgeOnAvailablePort(token)
            val port=hub.diagnostics().bridgePort!!
            UdpClockTestSource(hub,port,token).use { source ->
                source.awaitVerifiedClock()
                assertTrue(frames.isEmpty())
                fun motion(seq: Long, sourceRead: Long, times: List<Long>) = JSONObject()
                    .put("v",2).put("type","motion").put("token",token).put("device","android-socket-simulator")
                    .put("name","UDP regression simulator").put("identityStable",false).put("identitySource","simulator")
                    .put("sessionId",session).put("seq",seq).put("timer",(seq*3).and(255)).put("sourceReadNs",sourceRead)
                    .put("samples",JSONArray().also { samples -> times.forEach { time ->
                        samples.put(JSONObject().put("sourceTimeNs",time).put("ax",0.0).put("ay",0.0).put("az",9.80665)
                            .put("gx",0.0).put("gy",0.0).put("gz",0.0))
                    } })
                // Prepare and warm JSON before the source clock starts. Cold serialization is
                // test setup, not a simulated 25 ms-old controller action.
                val packets=listOf(motion(0,0,listOf(0,0,0)),motion(1,0,listOf(0,0,0)))
                packets.forEach { it.toString().toByteArray(Charsets.UTF_8) }
                val sourceRead=SystemClock.elapsedRealtimeNanos()
                val times=(0..5).map { sourceRead-25_000_000L+it*5_000_000L }
                packets.forEachIndexed { batch,packet ->
                    packet.put("sourceReadNs",sourceRead)
                    repeat(3) { index -> packet.getJSONArray("samples").getJSONObject(index)
                        .put("sourceTimeNs",times[batch*3+index]) }
                }
                val firstSend=SystemClock.elapsedRealtimeNanos()
                source.send(packets[0])
                Thread.sleep(1)
                val secondSend=SystemClock.elapsedRealtimeNanos()
                source.send(packets[1])
                val allReceived=received.await(3,TimeUnit.SECONDS)
                // Snapshot after waiting: eager Assert arguments previously captured an
                // intermediate three-frame state instead of the actual failure state.
                val diagnostics=hub.diagnostics()
                val timing="firstSendAgeNs=${firstSend-sourceRead}, interBatchNs=${secondSend-firstSend}, " +
                    "frames=${frames.map { Triple(it.sourceTimeNs,it.receivedTimeNs,it.sample.timeNs) }}"
                assertTrue("Six source-timed frames were not delivered; $timing; diagnostics=$diagnostics",allReceived)
                assertEquals(times,frames.map { it.sourceTimeNs })
                assertEquals(6L,diagnostics.devices.single().sampleCount)
                assertEquals(6L,diagnostics.bridgeStatistics!!.deliveredSamples)
                assertEquals(0L,diagnostics.bridgeStatistics.staleSamples)
                assertTrue(frames.zipWithNext().all { (a,b) -> a.sample.timeNs < b.sample.timeNs })
                assertTrue(frames.all { it.clockErrorNs in 0..30_000_000L && it.session.value==session })
                val lateRead=SystemClock.elapsedRealtimeNanos()
                source.send(motion(2,lateRead,listOf(lateRead-500_000_000L)))
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
