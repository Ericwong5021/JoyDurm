package ai.joydurm.input

import ai.joydurm.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Bounded actual accepted frames. User recenter, assignment and lifecycle commands are not recorded. */
class FrameRecording(private val capacity: Int = 4000) {
    data class Snapshot(val frames: List<ImuFrame>, val receivedCount: Long, val droppedCount: Long)
    private val retained = ArrayDeque<ImuFrame>()
    private var receivedCount = 0L
    private var droppedCount = 0L
    init { require(capacity in 1..MAX_FRAMES) }

    @Synchronized fun record(frame: ImuFrame) {
        if (retained.size == capacity) { retained.removeFirst(); droppedCount++ }
        retained.addLast(frame); receivedCount++
    }
    @Synchronized fun snapshot() = Snapshot(retained.toList(),receivedCount,droppedCount)
    @Synchronized fun clear() { retained.clear(); receivedCount = 0; droppedCount = 0 }

    fun exportJson(): String {
        val snapshot = snapshot()
        val frames = JSONArray()
        snapshot.frames.forEach { frame ->
            frames.put(JSONObject().put("physicalId",frame.identity.physicalId).put("transport",frame.identity.transport)
                .put("identityStable",frame.identity.stable).put("bindingId",frame.identity.bindingId).put("sessionId",frame.session.value)
                .put("sourceTimeNs",frame.sourceTimeNs).put("mappedLocalTimeNs",frame.sample.timeNs)
                .put("receivedTimeNs",frame.receivedTimeNs).put("clockErrorNs",frame.clockErrorNs)
                .put("ax",frame.sample.accel.x).put("ay",frame.sample.accel.y).put("az",frame.sample.accel.z)
                .put("gx",frame.sample.gyro.x).put("gy",frame.sample.gyro.y).put("gz",frame.sample.gyro.z))
        }
        return JSONObject().put("schema",1).put("status",if (snapshot.frames.isEmpty()) "HARDWARE_PENDING" else "FRAME_OBSERVATIONS_ONLY")
            .put("hardwareVerified",false).put("accelerationUnit","m/s^2").put("angularVelocityUnit","rad/s")
            .put("localClock","elapsedRealtimeNanos").put("receivedCount",snapshot.receivedCount)
            .put("retainedCount",snapshot.frames.size).put("droppedCount",snapshot.droppedCount)
            .put("scope","Accepted IMU frames only; role assignment, calibration, recenter and lifecycle commands are not included")
            .put("frames",frames).toString(2)
    }

    /** Frame-only replay. The caller establishes the engine's roles/calibration/epochs explicitly. */
    fun replay(consume: (ImuFrame)->Unit) { snapshot().frames.forEach(consume) }

    companion object {
        private const val MAX_FRAMES = 20_000
        private const val MAX_JSON_BYTES = 16_000_000
        fun decodeJson(json: String): List<ImuFrame> {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
            val root = JSONObject(json)
            require(root.getInt("schema") == 1 && root.getString("accelerationUnit") == "m/s^2" && root.getString("angularVelocityUnit") == "rad/s")
            val frames = root.getJSONArray("frames"); require(frames.length() <= MAX_FRAMES)
            return (0 until frames.length()).map { index ->
                val f = frames.getJSONObject(index)
                val physical = text(f,"physicalId"); val transport = text(f,"transport"); val binding = text(f,"bindingId")
                val stable = f.get("identityStable").let { require(it is Boolean); it as Boolean }
                val session = SessionId(text(f,"sessionId"))
                val source = long(f,"sourceTimeNs"); val local = long(f,"mappedLocalTimeNs")
                val received = long(f,"receivedTimeNs"); val error = long(f,"clockErrorNs")
                require(source >= 0 && local >= 0 && received >= local && error in 0..SampleClockMapper.MAX_ROUND_TRIP_NS)
                val a = Vec3(f.getDouble("ax"),f.getDouble("ay"),f.getDouble("az"))
                val g = Vec3(f.getDouble("gx"),f.getDouble("gy"),f.getDouble("gz"))
                require(a.finite() && g.finite() && a.norm() <= 200 && g.norm() <= 100)
                ImuFrame(ControllerIdentity(physical,transport,stable,binding),session,source,received,error,ImuSample(binding,local,a,g))
            }
        }
        private fun text(json: JSONObject, key: String): String = json.getString(key).also { require(it.length in 1..256) }
        private fun long(json: JSONObject, key: String): Long {
            val value=json.get(key); require(value is Int || value is Long); return (value as Number).toLong()
        }
    }
}
