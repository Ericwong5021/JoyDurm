package ai.joydurm.core

/** Transport-independent sample accounting. Callers serialize access. */
class InputHealth(private val startedTimeNs: Long? = null) {
    var lastSampleTimeNs: Long? = null; private set
    var lastReceivedTimeNs: Long? = null; private set
    var sampleCount = 0L; private set
    var lastError: String? = null; private set
    private var lossReported = false
    fun accept(sample: ImuSample, nowNs: Long): Boolean {
        if (!sample.accel.finite() || !sample.gyro.finite() || sample.accel.norm()>200 || sample.gyro.norm()>100 ||
            sample.timeNs<0 || sample.timeNs>nowNs || nowNs-sample.timeNs>STALE_NS || lastSampleTimeNs?.let { sample.timeNs<=it } == true) return false
        lastSampleTimeNs=sample.timeNs; lastReceivedTimeNs=nowNs; sampleCount++; lastError=null; lossReported=false
        return true
    }
    fun fresh(nowNs: Long) = lastReceivedTimeNs?.let { nowNs-it in 0..STALE_NS } == true &&
        lastSampleTimeNs?.let { nowNs-it in 0..STALE_NS } == true
    fun expire(nowNs: Long): Boolean {
        if(lossReported || fresh(nowNs)) return false
        if(lastReceivedTimeNs==null && (startedTimeNs==null || nowNs-startedTimeNs<=STALE_NS)) return false
        lossReported=true; lastError="超过 3 秒未收到有效 IMU 样本"; return true
    }
    fun disconnect(reason: String) { lastError=reason; lastReceivedTimeNs=null; lossReported=true }
    companion object { const val STALE_NS=3_000_000_000L }
}
