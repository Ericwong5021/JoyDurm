package ai.joydurm.input

import ai.joydurm.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameRecordingTest {
    private fun frame(index: Int) = ImuFrame(ControllerIdentity("physical-unit","bridge",true,"bridge:physical-unit"),
        SessionId(SESSION),50_000_000_000L+index*5_000_000L,10_002_000_000L+index*5_000_000L,2_000_000L,
        ImuSample("bridge:physical-unit",10_000_000_000L+index*5_000_000L,Vec3(1.0,2.0,9.4),Vec3(0.1,-0.2,0.3)))
    @Test fun roundTripPreservesSourceAndLocalClocksIdentitySessionAndSiUnits() {
        val r=FrameRecording(); (0..2).forEach { r.record(frame(it)) }
        assertEquals((0..2).map(::frame),FrameRecording.decodeJson(r.exportJson()))
    }
    @Test fun boundedRecordingKeepsNewestFramesAndReportsDrops() {
        val r=FrameRecording(2); (0..3).forEach { r.record(frame(it)) }
        assertEquals(listOf(frame(2),frame(3)),r.snapshot().frames)
        assertEquals(4L,r.snapshot().receivedCount); assertEquals(2L,r.snapshot().droppedCount)
        val replay=mutableListOf<ImuFrame>(); r.replay(replay::add); assertEquals(r.snapshot().frames,replay)
    }
    @Test fun emptyRecordingHasNoHardwareAcceptanceClaim() {
        val json=JSONObject(FrameRecording().exportJson())
        assertEquals("HARDWARE_PENDING",json.getString("status")); assertFalse(json.getBoolean("hardwareVerified"))
        assertEquals(0,json.getJSONArray("frames").length())
    }
    @Test fun importerRejectsWrongUnitsAndFutureMappedTimestamp() {
        val r=FrameRecording(); r.record(frame(0))
        val units=JSONObject(r.exportJson()).put("angularVelocityUnit","deg/s")
        assertTrue(runCatching { FrameRecording.decodeJson(units.toString()) }.isFailure)
        val bad=JSONObject(r.exportJson()); bad.getJSONArray("frames").getJSONObject(0).put("mappedLocalTimeNs",Long.MAX_VALUE)
        assertTrue(runCatching { FrameRecording.decodeJson(bad.toString()) }.isFailure)
    }
}
