package ai.joydurm.core

import kotlin.math.*

/** Two-point mounting calibration; the hinge lives in the closed controller's body frame. */
class HiHatController {
    private var closed: Quaternion? = null
    private var hinge = Vec3(1.0,0.0,0.0)
    private var range = 0.0
    private var lastTime: Long? = null
    private var previousRaw = 0.0
    private var openLatch = false
    private var lastChick = Long.MIN_VALUE/2
    var openness=0f; private set
    val calibrated get() = closed!=null && range>=Math.toRadians(6.0)
    data class Update(val openness: Float, val chick: Float?)
    fun reset() { closed=null; range=0.0; resetMotion() }
    fun resetMotion() { lastTime=null; previousRaw=0.0; openLatch=false; openness=0f; lastChick=Long.MIN_VALUE/2 }
    fun captureClosed(q: Quaternion) { closed=q.normalized(); range=0.0; resetMotion() }
    fun captureOpen(q: Quaternion) {
        val c=closed ?: error("请先标定踩镲闭合位置")
        val v=(c.conjugate()*q).rotationVector()
        require(v.norm() in Math.toRadians(6.0)..Math.toRadians(100.0)) { "全开位置与闭合位置角度不足或过大" }
        hinge=v.normalized(); range=v.norm(); resetMotion()
    }
    fun update(q: Quaternion, timeNs: Long): Update {
        if(!calibrated || lastTime?.let { timeNs<=it } == true) return Update(openness,null)
        val raw=((closed!!.conjugate()*q).rotationVector().dot(hinge)/range).coerceIn(0.0,1.0)
        val previous=lastTime
        val dt=previous?.let { (timeNs-it)/1e9 } ?: 0.0
        if(dt>0.3) { resetMotion(); lastTime=timeNs; return Update(openness,null) }
        val rate=if(dt>0) (previousRaw-raw)/dt else 0.0
        val alpha=if(dt>0) 1-exp(-dt/0.035) else 0.0
        openness=(openness+(raw-openness)*alpha).toFloat()
        if(raw>0.18) openLatch=true
        val closing=openLatch && raw<0.08
        // A slow closure chokes the hand sound but does not create a synthetic foot strike.
        val chick=if(closing && rate>=1.0 && timeNs-lastChick>=140_000_000L) {
            lastChick=timeNs; (rate/12).coerceIn(0.2,1.0).toFloat()
        } else null
        if(closing) openLatch=false
        previousRaw=raw; lastTime=timeNs
        return Update(openness,chick)
    }
}
