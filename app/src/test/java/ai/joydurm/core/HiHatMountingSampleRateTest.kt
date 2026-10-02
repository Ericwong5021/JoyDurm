package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class HiHatMountingSampleRateTest {
    private val closed=Quaternion.rotation(Vec3(0.4,-0.7,1.2))
    private fun pose(axis: Vec3, openness: Double)=closed*Quaternion.rotation(axis*(0.5*openness))
    private fun controller(axis: Vec3)=HiHatController().also { it.captureClosed(closed); it.captureOpen(pose(axis,1.0)) }
    @Test fun arbitraryMountingAxesOpenContinuously() {
        listOf(Vec3(1.0,0.0,0.0),Vec3(0.0,-1.0,0.0),Vec3(1.0,2.0,3.0).normalized()).forEach { axis ->
            val h=controller(axis); h.update(closed,0)
            repeat(200) { h.update(pose(axis,0.5),(it+1)*5_000_000L) }
            assertEquals(0.5f,h.openness,0.001f)
        }
    }
    @Test fun smoothingUsesElapsedTimeAcrossSampleRates() {
        fun run(stepNs: Long): Float {
            val axis=Vec3(0.0,1.0,0.0); val h=controller(axis); h.update(closed,0)
            var t=stepNs
            while(t<=400_000_000) { h.update(pose(axis,1.0),t); t+=stepNs }
            return h.openness
        }
        assertEquals(run(5_000_000),run(20_000_000),0.00001f)
    }
    @Test fun rapidClosureMakesOneChickWhileSlowClosureDoesNot() {
        val axis=Vec3(1.0,0.0,0.0)
        fun close(stepNs: Long): Int {
            val h=controller(axis); h.update(closed,0); h.update(pose(axis,1.0),20_000_000)
            var count=0
            repeat(20) { if(h.update(pose(axis,1-(it+1)/20.0),20_000_000+(it+1)*stepNs).chick!=null) count++ }
            return count
        }
        assertEquals(1,close(5_000_000)); assertEquals(0,close(100_000_000))
    }
    @Test(expected=IllegalArgumentException::class) fun almostIdenticalCalibrationPosesAreRejected() {
        val h=HiHatController(); h.captureClosed(closed); h.captureOpen(closed)
    }
}
