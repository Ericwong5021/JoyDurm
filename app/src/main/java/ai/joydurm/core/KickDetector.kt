package ai.joydurm.core

import kotlin.math.abs

/** Signed, gravity-free acceleration along the configured mounting axis (m/s²). */
class KickDetector(var threshold: Double = 2.2, var cooldownNs: Long = 90_000_000L) {
    enum class Phase { REST, LIFT, FIRED, RECOVERY }
    var phase = Phase.REST; private set
    private var previous: Long? = null
    private var lastHit = Long.MIN_VALUE / 2
    private var liftTime = 0L
    fun reset() { phase=Phase.REST; previous=null; lastHit=Long.MIN_VALUE/2 }
    fun update(projectedAcceleration: Double, timeNs: Long): Float? {
        if(!projectedAcceleration.isFinite() || previous?.let { timeNs<=it } == true) return null
        if(previous?.let { timeNs-it>300_000_000L } == true) reset()
        previous=timeNs
        val quiet=abs(projectedAcceleration)<threshold*0.25
        when(phase) {
            Phase.REST -> if(projectedAcceleration < -threshold*0.35) { phase=Phase.LIFT; liftTime=timeNs }
            Phase.LIFT -> {
                if(timeNs-liftTime>1_000_000_000L) phase=Phase.REST
                else if(projectedAcceleration>threshold && timeNs-lastHit>=cooldownNs) {
                    phase=Phase.FIRED; lastHit=timeNs
                    return (0.25+(projectedAcceleration-threshold)/(3*threshold)).coerceIn(0.1,1.0).toFloat()
                }
            }
            Phase.FIRED -> if(quiet) phase=Phase.RECOVERY
            Phase.RECOVERY -> if(quiet && timeNs-lastHit>=cooldownNs) phase=Phase.REST
        }
        return null
    }
}
