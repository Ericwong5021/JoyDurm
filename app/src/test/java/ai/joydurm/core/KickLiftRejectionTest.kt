package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class KickLiftRejectionTest {
    @Test fun twoHundredLiftOnlyTracesCannotFire() {
        val d=KickDetector(); var t=0L
        repeat(200) {
            listOf(0.0,-4.0,-6.0,-2.0,0.0,0.1,0.0).forEach { t+=20_000_000; assertNull(d.update(it,t)) }
            t+=1_100_000_000; assertNull(d.update(0.0,t))
        }
    }
    @Test fun eachLiftThenStompFiresOnceAndRejectsBounce() {
        val d=KickDetector(); var t=0L; var count=0
        repeat(200) {
            listOf(0.0,-4.0,-2.0,8.0,6.0,-3.0,5.0,0.0,0.0).forEach { t+=20_000_000; if(d.update(it,t)!=null)count++ }
            repeat(10) { t+=20_000_000; assertNull(d.update(0.0,t)) }
        }
        assertEquals(200,count)
    }
    @Test fun mountingAxisAndSignActuallyChangeTheEngineDecision() {
        fun hits(axis: Int, sign: Double): Int {
            val hits=mutableListOf<Hit>(); val e=DrumEngine { hits.add(it) }
            e.assign(Role.RIGHT_FOOT,"F"); e.tune(Role.RIGHT_FOOT,2.2,90_000_000,axis,sign)
            e.process(ImuSample("F",0,Vec3(0.0,0.0,9.80665),Vec3())); e.recenter(Role.RIGHT_FOOT)
            listOf(0.0,-5.0,-3.0,8.0,6.0,0.0).forEachIndexed { i,v ->
                e.process(ImuSample("F",(i+1)*5_000_000L,Vec3(v,0.0,9.80665),Vec3()))
            }
            return hits.size
        }
        assertEquals(1,hits(0,1.0)); assertEquals(0,hits(1,1.0)); assertEquals(0,hits(0,-1.0))
    }
    @Test fun slowLoweringAndMissingIntervalCannotCompleteAStomp() {
        val d=KickDetector(); d.update(-5.0,0)
        assertNull(d.update(0.5,50_000_000)); assertNull(d.update(1.0,100_000_000))
        assertNull(d.update(8.0,500_000_000))
    }
}
