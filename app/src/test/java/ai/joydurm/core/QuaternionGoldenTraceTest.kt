package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuaternionGoldenTraceTest {
    private val gravity=Vec3(0.0,0.0,9.80665)
    private fun assertSame(expected: Quaternion, actual: Quaternion, tolerance: Double=1e-7) {
        assertTrue("quaternion error ${(expected.conjugate()*actual).rotationVector().norm()}",
            (expected.conjugate()*actual).rotationVector().norm()<tolerance)
    }
    private fun trajectory(dtNs: Long, phases: List<Pair<Vec3,Double>>): Quaternion {
        val f=OrientationFilter(); var q=Quaternion(); var t=0L
        f.update(ImuSample("A",t,gravity,Vec3()),Vec3())
        phases.forEach { (worldAxis,angle) ->
            val steps=(1_000_000_000L/dtNs).toInt()
            val bodyOmega=q.conjugate().rotate(worldAxis.normalized())*angle
            repeat(steps) {
                t+=dtNs; q=(q*Quaternion.rotation(bodyOmega*(dtNs/1e9))).normalized()
                f.update(ImuSample("A",t,q.conjugate().rotate(gravity),bodyOmega),Vec3())
            }
        }
        assertSame(q,f.quaternion)
        return f.quaternion
    }
    @Test fun sixtyDegreeTiltThenWorldYawHasNoFalseRoll() {
        val expected=Quaternion.rotation(Vec3(0.0,0.0,PI/2))*Quaternion.rotation(Vec3(PI/3,0.0,0.0))
        assertSame(expected,trajectory(5_000_000L,listOf(Vec3(1.0,0.0,0.0) to PI/3,Vec3(0.0,0.0,1.0) to PI/2)))
    }
    @Test fun upsideDownThenYawRemainsFiniteAndCorrect() {
        val expected=Quaternion.rotation(Vec3(0.0,0.0,0.8))*Quaternion.rotation(Vec3(0.0,PI,0.0))
        assertSame(expected,trajectory(10_000_000L,listOf(Vec3(0.0,1.0,0.0) to PI,Vec3(0.0,0.0,1.0) to 0.8)))
    }
    @Test fun yawCrossingPiUsesContinuousQuaternion() {
        assertSame(Quaternion.rotation(Vec3(0.0,0.0,3.8)),trajectory(5_000_000L,listOf(Vec3(0.0,0.0,1.0) to 3.8)))
    }
    @Test fun obliqueWorldAxisCompositionIsSampleRateIndependent() {
        val phases=listOf(Vec3(1.0,2.0,-1.0) to 1.2,Vec3(-2.0,1.0,3.0) to -0.8)
        assertSame(trajectory(5_000_000L,phases),trajectory(20_000_000L,phases))
    }
    @Test fun relativeRotationIsQuaternionComposition() {
        val e=DrumEngine {}; val s=e.roles.getValue(Role.LEFT_HAND)
        val neutral=Quaternion.rotation(Vec3(PI/3,0.0,0.0))
        s.filter.update(ImuSample("A",0,neutral.conjugate().rotate(gravity),Vec3()),Vec3())
        s.neutralQuaternion=neutral
        val world=Vec3(0.0,0.0,1.0); val body=neutral.conjugate().rotate(world)
        repeat(100) { i ->
            val q=Quaternion.rotation(world*((i+1)*PI/200))*neutral
            s.filter.update(ImuSample("A",(i+1)*10_000_000L,q.conjugate().rotate(gravity),body*(PI/2)),Vec3())
        }
        val expected=(neutral.conjugate()*Quaternion.rotation(world*(PI/2))*neutral).attitude()
        val actual=e.relative(s)
        assertEquals(expected.pitch,actual.pitch,1e-6); assertEquals(expected.roll,actual.roll,1e-6); assertEquals(expected.yaw,actual.yaw,1e-6)
    }
}
