package ai.joydurm.input

/** Four-timestamp clock exchange. No motion timestamp is replaced by arrival time. */
class SampleClockMapper(private val assumedDriftPpm: Long = DRIFT_ASSUMPTION_PPM) {
    data class Mapping(val localTimeNs: Long, val errorBoundNs: Long)
    private var lowerOffsetNs: Long? = null
    private var upperOffsetNs = 0L
    private var syncedAtNs = 0L
    var lastError: String? = null; private set
    init { require(assumedDriftPpm in 0..1000) }

    fun synchronize(clientSendNs: Long, sourceReceiveNs: Long, sourceSendNs: Long, clientReceiveNs: Long): Boolean {
        if (clientSendNs < 0 || sourceReceiveNs < 0 || sourceSendNs < sourceReceiveNs || clientReceiveNs < clientSendNs) { lastError = "Invalid sync timestamp order"; return false }
        val roundTrip = clientReceiveNs - clientSendNs - (sourceSendNs - sourceReceiveNs)
        if (roundTrip !in 0..MAX_ROUND_TRIP_NS) { lastError = "Sync RTT outside 0–30 ms; clock remains unverified"; return false }
        val lower = clientSendNs - sourceReceiveNs
        val upper = clientReceiveNs - sourceSendNs
        if (upper < lower) { lastError = "Invalid clock offset interval"; return false }
        // Use the most precise recent exchange. Expired clocks must be established again.
        if (!valid(clientReceiveNs) || upper - lower <= upperOffsetNs - (lowerOffsetNs ?: lower)) {
            lowerOffsetNs = lower; upperOffsetNs = upper; syncedAtNs = clientReceiveNs
        }
        lastError = null; return true
    }

    fun valid(nowNs: Long): Boolean {
        val lower = lowerOffsetNs ?: return false
        val elapsed = nowNs - syncedAtNs
        if (elapsed !in 0..VALID_FOR_NS) { lastError = "Clock expired; exchange required"; return false }
        if (upperOffsetNs - lower + 2*driftAllowance(elapsed) > MAX_ROUND_TRIP_NS) {
            lastError = "Clock error exceeds 30 ms under assumed ${assumedDriftPpm} ppm drift; exchange required"; return false
        }
        return true
    }
    fun map(sourceTimeNs: Long, nowNs: Long): Mapping? {
        if (sourceTimeNs < 0 || !valid(nowNs)) return null
        val lower = lowerOffsetNs ?: return null
        val drift = driftAllowance(nowNs - syncedAtNs)
        val local = runCatching { Math.subtractExact(Math.addExact(sourceTimeNs, lower),drift) }.getOrNull() ?: return null
        // The conservative lower bound cannot invent freshness or future samples.
        return Mapping(local, upperOffsetNs - lower + 2*drift)
    }

    private fun driftAllowance(elapsedNs: Long) = (elapsedNs*assumedDriftPpm + 999_999L)/1_000_000L

    companion object {
        const val MAX_ROUND_TRIP_NS = 30_000_000L
        const val VALID_FOR_NS = 10_000_000_000L
        /** Configuration assumption, not an independently measured hardware clock guarantee. */
        const val DRIFT_ASSUMPTION_PPM = 100L
    }
}
