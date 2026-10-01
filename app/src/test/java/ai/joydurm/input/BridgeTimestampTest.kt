package ai.joydurm.input

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeTimestampTest {
    @Test fun twoThreeSampleBatchesArrivingOneMillisecondApartKeepAllSixSourceTimes() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        val times=(0..5).map { BASE+OFFSET-25_000_000L+it*5_000_000L }
        d.receive(motion(0,times.take(3)),ENDPOINT,BASE)
        d.receive(motion(1,times.drop(3)),ENDPOINT,BASE+1_000_000L)
        val frames=d.poll(BASE+21_000_000L).batches.flatMap { it.frames }
        assertEquals(times,frames.map { it.sourceTimeNs })
        assertEquals(times.mapIndexed { index,time -> time-OFFSET-if(index<3) 0 else 100 },frames.map { it.sample.timeNs })
        assertEquals(6L,d.statistics().deliveredSamples)
    }
    @Test fun unsynchronizedMotionCannotEnterEngine() {
        val d=BridgePacketDecoder(TOKEN)
        val r=d.receive(motion(0,listOf(BASE+OFFSET)),ENDPOINT,BASE)
        assertEquals(1,r.syncRequests.size); assertTrue(r.batches.isEmpty())
        assertTrue(d.poll(BASE+25_000_000L).batches.isEmpty())
        assertEquals(1L,d.statistics().unsynchronizedPackets)
    }
    @Test fun delayedPacketNeverBecomesFresh() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        d.receive(motion(4,listOf(BASE+OFFSET-500_000_000L)),ENDPOINT,BASE)
        assertTrue(d.poll(BASE+25_000_000L).batches.isEmpty()); assertEquals(1L,d.statistics().staleSamples)
    }
    @Test fun twentyMillisecondWindowRepairsReorderAndSuppressesDuplicate() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        d.receive(motion(2,listOf(BASE+OFFSET-5_000_000L)),ENDPOINT,BASE)
        d.receive(motion(1,listOf(BASE+OFFSET-10_000_000L)),ENDPOINT,BASE+2_000_000L)
        d.receive(motion(2,listOf(BASE+OFFSET-5_000_000L)),ENDPOINT,BASE+3_000_000L)
        val result=d.poll(BASE+21_000_000L)
        assertEquals(listOf(BASE-10_000_200L,BASE-5_000_000L),result.batches.flatMap { it.frames }.map { it.sample.timeNs })
        assertEquals(1L,d.statistics().duplicatePackets); assertEquals(1L,d.statistics().reorderedPackets)
    }
    @Test fun newSessionRetiresOldSessionAndEmitsLoss() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        d.receive(motion(7,listOf(BASE+OFFSET-10_000_000L)),ENDPOINT,BASE); d.poll(BASE+21_000_000L)
        val new=d.receive(motion(0,listOf(BASE+OFFSET+20_000_000L),NEW_SESSION),ENDPOINT,BASE+25_000_000L)
        assertEquals(listOf("bridge:serial-unit-1"),new.lostDevices)
        d.receive(motion(8,listOf(BASE+OFFSET+25_000_000L)),ENDPOINT,BASE+26_000_000L)
        val frames=d.poll(BASE+50_000_000L).batches.flatMap { it.frames }
        assertEquals(1,frames.size); assertEquals(NEW_SESSION,frames.single().session.value)
        assertEquals(1L,d.statistics().retiredSessionPackets)
    }
    @Test fun timerWrapIsIndependentOfMonotonicSequenceAndSourceTime() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        val before=JSONObject(String(motion(85,listOf(BASE+OFFSET-5_000_000L)))).put("timer",255).toString().toByteArray()
        val after=JSONObject(String(motion(86,listOf(BASE+OFFSET)))).put("timer",2).toString().toByteArray()
        d.receive(before,ENDPOINT,BASE); d.receive(after,ENDPOINT,BASE+1_000_000L)
        assertEquals(2,d.poll(BASE+21_000_000L).batches.flatMap { it.frames }.size)
    }
    @Test fun unstableIdentityCannotUseRoleHintOrSurviveSessionBinding() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        d.receive(motion(0,listOf(BASE+OFFSET),stable=false),ENDPOINT,BASE)
        val b=d.poll(BASE+21_000_000L).batches.single()
        assertNull(b.roleHint); assertFalse(b.device.identity.stable)
        assertTrue(b.device.id.endsWith("/session:$SESSION")); assertEquals(b.device.id,b.device.identity.bindingId)
    }
    @Test fun highRoundTripRejectsClockAndExplainsReason() {
        val d=BridgePacketDecoder(TOKEN)
        val request=d.receive(motion(0,listOf(BASE+OFFSET)),ENDPOINT,BASE).syncRequests.single()
        d.receive(syncReply(request.json),ENDPOINT,BASE+31_000_000L)
        assertEquals(1L,d.statistics().rejectedClockExchanges)
        assertTrue(d.statistics().lastRejection!!.contains("30 ms"))
        assertTrue(d.receive(motion(1,listOf(BASE+OFFSET)),ENDPOINT,BASE+32_000_000L).batches.isEmpty())
    }
    @Test fun delayedSyncDispatchUsesActualSendClockAndKeepsStaleActionsRejected() {
        val d=BridgePacketDecoder(TOKEN)
        val request=d.receive(motion(0,listOf(BASE+OFFSET)),ENDPOINT,BASE).syncRequests.single()
        val sent=BASE+150_000_000L // input queue/encoding cost, before network transmission
        var dispatched: ByteArray?=null
        assertTrue(d.dispatchSync(request,{ sent }) { dispatched=it })
        val wire=String(dispatched!!,Charsets.UTF_8)
        assertEquals(sent,JSONObject(wire).getLong("clientSendNs"))
        // An echo of the bootstrap-arrival timestamp cannot authenticate the new exchange.
        d.receive(syncReply(request.json),ENDPOINT,sent+1_000_000L)
        assertEquals(0L,d.statistics().acceptedClockExchanges)
        d.receive(syncReply(wire),ENDPOINT,sent+2_000_000L)
        assertEquals(1L,d.statistics().acceptedClockExchanges)
        assertEquals(1,d.statistics(sent+2_000_000L).synchronizedClockCount)
        val source=sent+OFFSET+3_000_000L
        d.receive(motion(1,listOf(source)),ENDPOINT,sent+4_000_000L)
        val frame=d.poll(sent+25_000_000L).batches.single().frames.single()
        assertEquals(source,frame.sourceTimeNs)
        assertEquals(sent+3_000_000L-200L,frame.sample.timeNs)
        d.receive(motion(2,listOf(source-500_000_000L)),ENDPOINT,sent+26_000_000L)
        assertTrue(d.poll(sent+50_000_000L).batches.isEmpty())
        assertEquals(1L,d.statistics().staleSamples)
    }
    @Test fun invalidRangesMalformedAndOversizedPayloadsAreRejected() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        val j=JSONObject(String(motion(0,listOf(BASE+OFFSET))))
        j.getJSONArray("samples").getJSONObject(0).put("gx",200.0)
        d.receive(j.toString().toByteArray(),ENDPOINT,BASE)
        d.receive(ByteArray(8193),ENDPOINT,BASE)
        d.receive("bad json".toByteArray(),ENDPOINT,BASE)
        assertTrue(d.poll(BASE+25_000_000L).batches.isEmpty())
        assertEquals(4L,d.statistics().rejectedPackets) // initial unsynchronized packet plus three invalid packets
    }
    @Test fun fiftyMillisecondDelayedMotionIsValidButHundredMillisecondOldMotionIsDropped() {
        val d=BridgePacketDecoder(TOKEN); synchronize(d)
        d.receive(motion(0,listOf(BASE+OFFSET-50_000_000L)),ENDPOINT,BASE)
        assertEquals(1,d.poll(BASE+21_000_000L).batches.flatMap { it.frames }.size)
        d.receive(motion(1,listOf(BASE+OFFSET-101_000_000L)),ENDPOINT,BASE)
        assertTrue(d.poll(BASE+25_000_000L).batches.isEmpty())
    }
    @Test fun reconnectAcrossManyEphemeralPortsDoesNotExhaustClockTable() {
        val d=BridgePacketDecoder(TOKEN)
        repeat(24) { index ->
            val endpoint="127.0.0.1:${42000+index}"; val now=BASE+index*50_000_000L
            synchronize(d,now,endpoint)
            val session=java.util.UUID.nameUUIDFromBytes("session-$index".toByteArray()).toString()
            val result=d.receive(motion(0,listOf(now+OFFSET),session),endpoint,now)
            if (index>0) assertEquals(listOf("bridge:serial-unit-1"),result.lostDevices)
            assertEquals(1,d.poll(now+21_000_000L).batches.flatMap { it.frames }.size)
        }
        assertEquals(24L,d.statistics().deliveredSamples)
    }
    @Test fun readinessRequiresSuccessfulExchangeAndCurrentClockValidity() {
        val d=BridgePacketDecoder(TOKEN)
        val first=d.receive(motion(0,listOf(BASE+OFFSET)),ENDPOINT,BASE).syncRequests.single()
        d.receive(syncReply(first.json),ENDPOINT,BASE+31_000_000L)
        assertEquals(0L,d.statistics(BASE+31_000_000L).acceptedClockExchanges)
        assertEquals(0,d.statistics(BASE+31_000_000L).synchronizedClockCount)
        val retryTime=BASE+2_000_000_000L
        val retry=d.receive(motion(0,listOf(retryTime+OFFSET)),ENDPOINT,retryTime).syncRequests.single()
        d.receive(syncReply(retry.json),ENDPOINT,retryTime)
        assertEquals(1L,d.statistics(retryTime).acceptedClockExchanges)
        assertEquals(1,d.statistics(retryTime).synchronizedClockCount)
        val expired=retryTime+SampleClockMapper.VALID_FOR_NS+1
        assertEquals(1L,d.statistics(expired).acceptedClockExchanges)
        assertEquals(0,d.statistics(expired).synchronizedClockCount)
    }
}
