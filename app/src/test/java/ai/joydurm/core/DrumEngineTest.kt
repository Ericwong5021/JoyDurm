package ai.joydurm.core

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

class DrumEngineTest {
    @Test fun strokeFiresOnceAndRejectsRebound() {
        val d=StrokeDetector(2.0)
        assertNull(d.update(0.0,0)); assertNull(d.update(3.0,10_000_000)); assertNull(d.update(8.0,20_000_000))
        assertNotNull(d.update(4.0,30_000_000)); assertNull(d.update(-8.0,40_000_000)); assertNull(d.update(5.0,50_000_000))
        assertNull(d.update(0.0,100_000_000)); assertNull(d.update(4.0,150_000_000)); assertNotNull(d.update(1.0,160_000_000))
    }
    @Test fun stationaryCalibrationRemovesGyroBias() {
        val c=StationaryCalibrator()
        repeat(400) { i -> c.add(ImuSample("L",i*5_000_000L,Vec3(0.0,0.0,9.80665),Vec3(0.01,-0.02,0.03))) }
        val r=c.finish(); assertEquals(0.01,r.bias.x,0.000001); assertEquals(400,r.samples)
    }
    @Test(expected=IllegalArgumentException::class) fun movingCalibrationIsRejected() {
        val c=StationaryCalibrator()
        repeat(400) { i -> c.add(ImuSample("L",i*5_000_000L,Vec3(0.0,0.0,9.8+sin(i.toDouble())*3),Vec3(sin(i.toDouble()),0.0,0.0))) }; c.finish()
    }
    @Test fun parserPreservesThreeSampleTimingAndUnits() {
        val report=ByteArray(49); report[0]=0x30
        repeat(3) { i -> report[13+i*12+4]=0; report[13+i*12+5]=0x10 }
        val data=JoyConProtocol.parse(report,"L",1_000_000_000)
        assertEquals(3,data.size); assertEquals(990_000_000L,data[0].timeNs); assertEquals(9.80665,data[0].accel.z,0.00001)
        assertTrue(JoyConProtocol.parse(report.copyOf(48),"L",0).isEmpty())
    }
    @Test fun assigningSameDeviceTransfersRole() {
        val e=DrumEngine {}; e.assign(Role.LEFT_HAND,"L"); e.assign(Role.LEFT_FOOT,"L")
        assertNull(e.roles[Role.LEFT_HAND]!!.device); assertEquals("L",e.roles[Role.LEFT_FOOT]!!.device)
    }
    @Test fun yawDistanceWrapsAtPiBoundary() {
        val d=DrumEngine.targetDistance(Attitude(0.0,0.0,PI-0.01),Target(Drum.SNARE,-PI+0.01,0.0))
        assertTrue(d<0.01)
    }
    @Test fun footAngleOpensHatThenClosingProducesChick() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.assign(Role.LEFT_FOOT,"F")
        var t=1_000_000_000L
        repeat(250) { t+=5_000_000; e.process(ImuSample("F",t,Vec3(0.0,-9.80665*sin(0.5),9.80665*cos(0.5)),Vec3())) }
        assertTrue(e.openness>0.75f)
        repeat(300) { t+=5_000_000; e.process(ImuSample("F",t,Vec3(0.0,0.0,9.80665),Vec3())) }
        assertTrue(e.openness<0.15f); assertEquals(1,hits.count { it.drum==Drum.CHICK })
    }
    @Test fun rejectsNonFiniteAndOutOfOrderMotion() {
        val e=DrumEngine {}; e.assign(Role.RIGHT_HAND,"R")
        e.process(ImuSample("R",100,Vec3(0.0,0.0,9.8),Vec3()))
        e.process(ImuSample("R",99,Vec3(),Vec3())); e.process(ImuSample("R",101,Vec3(Double.NaN,0.0,0.0),Vec3()))
        assertEquals(100L,e.roles[Role.RIGHT_HAND]!!.latest!!.timeNs)
    }
    @Test fun stoppedFootDoesNotProduceRepeatedKick() {
        val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }; e.assign(Role.RIGHT_FOOT,"F")
        repeat(1000) { e.process(ImuSample("F",1_000_000_000+it*5_000_000L,Vec3(0.0,0.0,9.80665),Vec3())) }
        assertTrue(hits.isEmpty())
    }
    @Test fun sustainedMotionCannotRearmWithoutQuiet() {
        val d=StrokeDetector(2.0)
        d.update(8.0,0); assertNotNull(d.update(4.0,10_000_000))
        assertNull(d.update(8.0,200_000_000)); assertNull(d.update(4.0,210_000_000))
        d.update(0.0,220_000_000); d.update(8.0,230_000_000)
        assertNotNull(d.update(4.0,240_000_000))
    }
    @Test fun reassignmentDropsPendingCalibration() {
        val e=DrumEngine {}; e.assign(Role.LEFT_HAND,"A"); e.startCalibration(Role.LEFT_HAND)
        e.process(ImuSample("A",1,Vec3(0.0,0.0,9.8),Vec3()))
        e.assign(Role.LEFT_HAND,"B"); assertEquals(0,e.calibrationCount(Role.LEFT_HAND))
    }
    @Test fun lossClearsLiveMotionButRetainsBinding() {
        val e=DrumEngine {}; e.assign(Role.LEFT_FOOT,"F")
        e.process(ImuSample("F",1,Vec3(0.0,-8.0,5.0),Vec3()))
        e.deviceLost("F")
        assertNull(e.roles[Role.LEFT_FOOT]!!.latest); assertEquals("F",e.roles[Role.LEFT_FOOT]!!.device)
        assertEquals(0f,e.openness)
    }
    @Test fun gravityAndGyroUseConsistentTiltSigns() {
        val f=OrientationFilter()
        f.update(ImuSample("F",0,Vec3(0.0,0.0,9.80665),Vec3()),Vec3())
        val a=f.update(ImuSample("F",10_000_000,Vec3(0.0,0.0,12.0),Vec3(1.0,1.0,0.0)),Vec3())
        assertEquals(-0.01,a.pitch,0.000001); assertEquals(-0.01,a.roll,0.000001)
    }
    @Test fun calibratorIgnoresDuplicateAndOtherDeviceSamples() {
        val c=StationaryCalibrator()
        val s=ImuSample("A",10,Vec3(0.0,0.0,9.80665),Vec3())
        c.add(s); c.add(s); c.add(s.copy(device="B",timeNs=20))
        assertEquals(1,c.count)
    }
    @Test fun healthRequiresRealFreshSamplesAndSignalsLossOnce() {
        val h=InputHealth(); assertFalse(h.fresh(1_000))
        val sample=ImuSample("A",1_000,Vec3(0.0,0.0,9.8),Vec3())
        assertTrue(h.accept(sample,1_000)); assertFalse(h.accept(sample,1_001)); assertEquals(1L,h.sampleCount)
        assertFalse(h.accept(sample.copy(timeNs=2_000),1_500))
        assertTrue(h.expire(3_000_002_000)); assertFalse(h.expire(3_000_003_000))
        assertFalse(h.fresh(3_000_003_000))
        assertTrue(h.accept(sample.copy(timeNs=4_000_000_000),4_000_000_000))
        assertTrue(h.fresh(4_000_000_000)); assertNull(h.lastError)
    }
    @Test fun calibrationGapRestartsStationaryWindow() {
        val c=StationaryCalibrator()
        repeat(400) { c.add(ImuSample("A",it*5_000_000L,Vec3(0.0,0.0,9.8),Vec3())) }
        c.add(ImuSample("A",3_000_000_000,Vec3(0.0,0.0,9.8),Vec3()))
        assertEquals(1,c.count)
    }
    @Test fun failedCalibrationCanContinueCollecting() {
        val e=DrumEngine {}; e.assign(Role.LEFT_HAND,"A"); e.startCalibration(Role.LEFT_HAND)
        e.process(ImuSample("A",1,Vec3(0.0,0.0,9.8),Vec3()))
        try { e.finishCalibration(Role.LEFT_HAND); fail("expected insufficient samples") } catch(_: IllegalArgumentException) {}
        assertEquals(1,e.calibrationCount(Role.LEFT_HAND))
    }
    @Test fun openedTransportWithoutMotionExpiresAsUnavailable() {
        val h=InputHealth(1_000)
        assertFalse(h.fresh(2_000)); assertFalse(h.expire(2_000))
        assertTrue(h.expire(3_000_002_000)); assertNotNull(h.lastError)
        assertEquals(0L,h.sampleCount); assertNull(h.lastSampleTimeNs)
        assertFalse(h.expire(4_000_000_000))
    }
    @Test fun delayedBatchCannotMakeOldMotionFresh() {
        val h=InputHealth()
        val sample=ImuSample("A",1_000,Vec3(0.0,0.0,9.8),Vec3())
        assertFalse(h.accept(sample,3_000_002_000))
        assertEquals(0L,h.sampleCount); assertNull(h.lastSampleTimeNs)
        assertFalse(h.fresh(3_000_002_000))
        assertTrue(h.accept(sample,3_000_001_000))
        assertTrue(h.fresh(3_000_001_000))
        assertFalse(h.fresh(3_000_001_001))
        assertTrue(h.expire(3_000_001_001))
    }
}
