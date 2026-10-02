package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test

class HatDisconnectChokeTest {
    @Test fun disconnectChokesOpenHatWithoutInventingFootHit() {
        val controls=mutableListOf<HatControl>(); val hits=mutableListOf<Hit>()
        val e=DrumEngine({ controls.add(it) }) { hits.add(it) }; e.assign(Role.LEFT_FOOT,"F")
        var t=0L
        fun feed(a: Double,w: Double=0.0) {
            t+=5_000_000L; val q=Quaternion.rotation(Vec3(a,0.0,0.0))
            e.process(ImuSample("F",t,q.conjugate().rotate(Vec3(0.0,0.0,9.80665)),Vec3(w,0.0,0.0)))
        }
        feed(0.0); e.recenter(Role.LEFT_FOOT); e.captureHatClosed()
        repeat(100) { feed((it+1)*0.005,1.0) }; e.captureHatOpen()
        repeat(100) { feed(0.5) }; assertTrue(e.openness>0.95)
        controls.clear(); hits.clear(); e.deviceLost("F")
        assertEquals(0f,e.openness); assertTrue(controls.single() is HatControl.ChokeAll); assertTrue(hits.isEmpty())
        assertFalse(e.snapshot().roles.getValue(Role.LEFT_FOOT).hatCalibrated)
    }
}
