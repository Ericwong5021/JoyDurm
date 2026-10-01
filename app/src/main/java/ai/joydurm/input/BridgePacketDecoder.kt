package ai.joydurm.input

import ai.joydurm.core.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.UUID

data class BridgeDevice(val id: String, val name: String, val identity: ControllerIdentity,
    val identitySource: String, val session: SessionId, val endpoint: String, val vendorId: Int? = null, val productId: Int? = null)
data class BridgeBatch(val device: BridgeDevice, val frames: List<ImuFrame>, val roleHint: Role?)
data class BridgeSyncRequest(val endpoint: String, val json: String)
data class BridgeDecodeResult(val batches: List<BridgeBatch> = emptyList(), val lostDevices: List<String> = emptyList(),
    val syncRequests: List<BridgeSyncRequest> = emptyList())
data class BridgeStatistics(val acceptedPackets: Long, val rejectedPackets: Long, val deliveredSamples: Long,
    val staleSamples: Long, val duplicatePackets: Long, val reorderedPackets: Long, val missingPackets: Long,
    val unsynchronizedPackets: Long, val retiredSessionPackets: Long, val queueOverflows: Long,
    val rejectedClockExchanges: Long = 0, val lastRejection: String? = null,
    /** Cumulative matched exchanges that passed the clock mapper's unchanged bounds. */
    val acceptedClockExchanges: Long = 0,
    /** Endpoints whose clock remains valid at the explicitly supplied diagnostic time. */
    val synchronizedClockCount: Int = 0)

/** Serialized by its owner. Keeps source clocks, physical identity and sessions separate. */
class BridgePacketDecoder(private val token: String) {
    private data class Clock(val mapper: SampleClockMapper = SampleClockMapper(), var requestedAtNs: Long = Long.MIN_VALUE,
        var nonce: String? = null, var clientSendNs: Long = 0)
    private data class Pending(val seq: Long, val receivedNs: Long, val batch: BridgeBatch)
    private data class DeviceState(val device: BridgeDevice, val retired: MutableSet<String>,
        val pending: PriorityQueue<Pending> = PriorityQueue(compareBy<Pending> { it.seq }),
        var deliveredSeq: Long = -1, var greatestSeq: Long = -1, var latestSampleNs: Long = -1)
    private val clocks = linkedMapOf<String, Clock>()
    private val devices = mutableMapOf<String, DeviceState>()
    private var accepted = 0L; private var rejected = 0L; private var delivered = 0L
    private var stale = 0L; private var duplicates = 0L; private var reordered = 0L; private var missing = 0L
    private var unsynced = 0L; private var retired = 0L; private var overflows = 0L
    private var clockRejected = 0L; private var lastRejection: String? = null
    private var clockAccepted = 0L; private var observedNowNs = 0L

    /** Production diagnostics supply current elapsedRealtimeNanos; JVM traces use their injected clock. */
    fun statistics(nowNs: Long = observedNowNs) = BridgeStatistics(accepted,rejected,delivered,stale,duplicates,reordered,missing,unsynced,retired,overflows,
        clockRejected,lastRejection,clockAccepted,clocks.values.count { it.mapper.valid(nowNs) })
    fun receive(payload: ByteArray, endpoint: String, receivedNs: Long): BridgeDecodeResult {
        observedNowNs = maxOf(observedNowNs,receivedNs)
        val requests = mutableListOf<BridgeSyncRequest>()
        val lost = mutableListOf<String>()
        try {
            require(payload.size in 1..8192 && receivedNs >= 0)
            val json = JSONObject(String(payload, Charsets.UTF_8))
            require(long(json,"v") == 2L)
            require(MessageDigest.isEqual(json.getString("token").toByteArray(Charsets.UTF_8),token.toByteArray(Charsets.UTF_8)))
            require(endpoint.isNotBlank() && endpoint.length <= 256)
            if (!clocks.containsKey(endpoint) && clocks.size >= MAX_DEVICES) {
                val unused = clocks.keys.firstOrNull { key -> devices.values.none { it.device.endpoint == key } }
                    ?: error("Clock endpoint limit reached")
                clocks.remove(unused)
            }
            val clock = clocks.getOrPut(endpoint) { Clock() }
            if (json.optString("type") == "sync_reply") {
                require(clock.nonce != null && json.getString("nonce") == clock.nonce)
                val sent = long(json,"clientSendNs"); require(sent == clock.clientSendNs)
                if (!clock.mapper.synchronize(sent,long(json,"sourceReceiveNs"),long(json,"sourceSendNs"),receivedNs)) {
                    clockRejected++; error(clock.mapper.lastError ?: "Clock exchange rejected")
                }
                clockAccepted++
                clock.nonce = null
                return poll(receivedNs)
            }
            require(json.optString("type", "motion") == "motion")
            if (!clock.mapper.valid(receivedNs)) {
                unsynced++; rejected++; lastRejection = clock.mapper.lastError ?: "Clock unsynchronized; motion rejected"; requestSync(endpoint,clock,receivedNs)?.let(requests::add)
                return BridgeDecodeResult(syncRequests=requests)
            }
            requestSync(endpoint,clock,receivedNs)?.let(requests::add)
            val physical = json.getString("device"); require(Regex("[A-Za-z0-9._:@-]{1,120}").matches(physical))
            val sessionValue = json.getString("sessionId"); require(UUID.fromString(sessionValue).toString() == sessionValue.lowercase())
            val session = SessionId(sessionValue)
            val identityStable = json.get("identityStable").let { require(it is Boolean); it as Boolean }
            val identitySource = json.getString("identitySource"); require(identitySource.length in 1..80)
            require(!identityStable || identitySource in setOf("serial", "mac"))
            val id = if (identityStable) "bridge:$physical" else "bridge:$physical/session:$sessionValue"
            val identity = ControllerIdentity(physical,"bridge",identityStable,bindingId=id)
            val vendorId = if (json.has("vendorId")) long(json,"vendorId").also { require(it in 0..65535) }.toInt() else null
            val productId = if (json.has("productId")) long(json,"productId").also { require(it in 0..65535) }.toInt() else null
            val device = BridgeDevice(id,json.optString("name","Joy-Con").take(120),identity,identitySource,session,endpoint,vendorId,productId)
            val seq = long(json,"seq"); require(seq >= 0)
            val timer = long(json,"timer"); require(timer in 0..255)
            val sourceRead = long(json,"sourceReadNs"); require(sourceRead >= 0)
            val data = json.getJSONArray("samples"); require(data.length() in 1..3)
            var lastSource = -1L
            val frames = (0 until data.length()).map { index ->
                val item = data.getJSONObject(index); val source = long(item,"sourceTimeNs")
                require(source > lastSource && source <= sourceRead); lastSource = source
                val a = Vec3(number(item,"ax"),number(item,"ay"),number(item,"az"))
                val g = Vec3(number(item,"gx"),number(item,"gy"),number(item,"gz"))
                require(a.finite() && g.finite() && a.norm() <= 200 && g.norm() <= 100)
                val mapping = clock.mapper.map(source,receivedNs) ?: error("No synchronized clock")
                require(mapping.localTimeNs >= 0 && mapping.localTimeNs <= receivedNs)
                ImuFrame(identity,session,source,receivedNs,mapping.errorBoundNs,ImuSample(id,mapping.localTimeNs,a,g))
            }
            val role = if (identityStable) Role.entries.firstOrNull { it.name == json.optString("role") } else null
            if (frames.all { receivedNs - it.sample.timeNs > STALE_NS }) { stale += frames.size; error("Stale motion") }
            val previous = devices[physical]
            if (previous != null && sessionValue in previous.retired) { retired++; error("Retired session") }
            val state = if (previous == null || previous.device.session != session) {
                require(previous != null || devices.size < MAX_DEVICES)
                val history = previous?.retired ?: mutableSetOf()
                if (previous != null) {
                    require(history.size < MAX_RETIRED_SESSIONS)
                    history.add(previous.device.session.value); lost.add(previous.device.id)
                }
                DeviceState(device,history).also { devices[physical] = it }
            } else {
                require(previous.device.endpoint == endpoint && previous.device.identity == identity)
                previous
            }
            if (seq <= state.deliveredSeq || state.pending.any { it.seq == seq }) { duplicates++; error("Duplicate sequence") }
            if (seq < state.greatestSeq) reordered++
            if (state.pending.size >= MAX_PENDING) { overflows++; error("Reorder queue full") }
            state.greatestSeq = maxOf(state.greatestSeq,seq)
            state.pending.add(Pending(seq,receivedNs,BridgeBatch(device,frames,role))); accepted++
            val drained = poll(receivedNs)
            return drained.copy(lostDevices=lost,syncRequests=requests)
        } catch (e: Exception) {
            rejected++; lastRejection = e.message?.take(160) ?: e.javaClass.simpleName
            return BridgeDecodeResult(lostDevices=lost,syncRequests=requests)
        }
    }

    fun poll(nowNs: Long): BridgeDecodeResult {
        observedNowNs = maxOf(observedNowNs,nowNs)
        val batches = mutableListOf<BridgeBatch>()
        for (state in devices.values) {
            while (state.pending.isNotEmpty()) {
                val first = state.pending.peek() ?: break
                // A packet can wait at most 20 ms. New arrival does not refresh its age.
                if (nowNs - state.pending.minOf { it.receivedNs } < REORDER_NS) break
                state.pending.remove()
                if (state.deliveredSeq >= 0 && first.seq > state.deliveredSeq + 1) missing += first.seq - state.deliveredSeq - 1
                state.deliveredSeq = first.seq
                val valid = first.batch.frames.filter { frame ->
                    val t = frame.sample.timeNs
                    if (nowNs - t !in 0..STALE_NS || t <= state.latestSampleNs) { stale++; false }
                    else { state.latestSampleNs = t; true }
                }
                if (valid.isNotEmpty()) { delivered += valid.size; batches.add(first.batch.copy(frames=valid)) }
            }
        }
        return BridgeDecodeResult(batches=batches)
    }

    /** Owner dispatches after encoding, so bootstrap arrival/queue time is never t1. */
    fun dispatchSync(request: BridgeSyncRequest, readSendTimeNs: ()->Long, send: (ByteArray)->Unit): Boolean {
        val clock=clocks[request.endpoint] ?: return false
        val json=JSONObject(request.json)
        if(clock.nonce==null || json.getString("nonce")!=clock.nonce)return false
        val prefix="\"clientSendNs\":"
        val bytes=json.put("clientSendNs",Long.MAX_VALUE).toString().toByteArray(Charsets.UTF_8)
        val position=String(bytes,Charsets.US_ASCII).indexOf(prefix+Long.MAX_VALUE)+prefix.length
        check(position>=prefix.length)
        // All allocation/JSON work precedes the real send clock. Fixed-width JSON
        // numbers permit leading spaces, so stamping does not serialize again.
        val sent=readSendTimeNs()
        require(sent>=clock.clientSendNs && sent>=0)
        bytes.fill(' '.code.toByte(),position,position+19)
        var remaining=sent; var cursor=position+18
        do { bytes[cursor--]=('0'.code+(remaining%10).toInt()).toByte(); remaining/=10 } while(remaining>0)
        clock.clientSendNs=sent; clock.requestedAtNs=sent
        send(bytes)
        return true
    }

    private fun requestSync(endpoint: String, clock: Clock, nowNs: Long): BridgeSyncRequest? {
        val elapsed = if (clock.requestedAtNs == Long.MIN_VALUE) Long.MAX_VALUE else nowNs - clock.requestedAtNs
        if (elapsed < if (clock.nonce == null) SYNC_INTERVAL_NS else SYNC_RETRY_NS) return null
        clock.nonce = UUID.randomUUID().toString(); clock.clientSendNs = nowNs; clock.requestedAtNs = nowNs
        return BridgeSyncRequest(endpoint,JSONObject().put("v",2).put("type","sync").put("token",token)
            .put("nonce",clock.nonce).put("clientSendNs",nowNs).toString())
    }

    private fun long(json: JSONObject, key: String): Long {
        val value = json.get(key); require(value is Int || value is Long); return (value as Number).toLong()
    }
    private fun number(json: JSONObject, key: String): Double {
        val value = json.get(key); require(value is Number); return (value as Number).toDouble()
    }
    companion object {
        const val STALE_NS = 100_000_000L
        const val REORDER_NS = 20_000_000L
        private const val SYNC_INTERVAL_NS = 2_000_000_000L
        private const val SYNC_RETRY_NS = 1_000_000_000L
        private const val MAX_PENDING = 64
        private const val MAX_DEVICES = 16
        private const val MAX_RETIRED_SESSIONS = 64
    }
}
