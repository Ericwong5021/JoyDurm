package ai.joydurm.input

import android.hardware.Sensor
import android.os.Build
import android.view.InputDevice
import ai.joydurm.core.ImuFrame
import org.json.JSONArray
import org.json.JSONObject

/** Evidence export, not a compatibility claim. Contains only controller sensor observations. */
class CapabilityProbe {
    private data class Observation(val metadata: JSONObject, var count: Long = 0, var firstNs: Long = 0,
        var lastNs: Long = 0, var receivedNs: Long = 0, var minIntervalNs: Long = Long.MAX_VALUE,
        var maxIntervalNs: Long = 0, var clockErrorNs: Long = 0)
    private val observations = linkedMapOf<String,Observation>()

    fun androidDevice(id: String, session: String, device: InputDevice) {
        val metadata = JSONObject().put("id",id).put("physicalIdentityStable",false).put("identitySource","android_descriptor")
            .put("sessionId",session).put("name",device.name).put("descriptor",device.descriptor)
            .put("vendorId",device.vendorId).put("productId",device.productId).put("androidDeviceId",device.id)
            .put("transport","android_controller_sensor")
        val sensors = JSONArray()
        if (Build.VERSION.SDK_INT >= 31) runCatching { device.sensorManager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList()).forEach { sensor ->
            sensors.put(JSONObject().put("type",sensor.type).put("name",sensor.name).put("vendor",sensor.vendor)
                .put("version",sensor.version).put("minDelayUs",sensor.minDelay).put("maxDelayUs",sensor.maxDelay)
                .put("resolution",sensor.resolution.toDouble()).put("maximumRange",sensor.maximumRange.toDouble()))
        }
        metadata.put("controllerSensors",sensors)
        observations[id] = Observation(metadata)
        trim()
    }

    fun bridgeDevice(device: BridgeDevice) {
        if (observations[device.id]?.metadata?.optString("sessionId") == device.session.value) return
        observations[device.id] = Observation(JSONObject().put("id",device.id).put("physicalId",device.identity.physicalId)
            .put("physicalIdentityStable",device.identity.stable).put("identitySource",device.identitySource)
            .put("sessionId",device.session.value).put("name",device.name).put("transport","bridge")
            .put("vendorId",device.vendorId ?: JSONObject.NULL).put("productId",device.productId ?: JSONObject.NULL)
            .put("endpoint",device.endpoint))
        trim()
    }

    fun rawDevice(id: String, session: String, path: String) {
        observations[id] = Observation(JSONObject().put("id",id).put("sessionId",session).put("transport","raw_hid")
            .put("path",path).put("physicalIdentityStable",false).put("identitySource","unverified_device_node"))
        trim()
    }

    fun record(frame: ImuFrame) {
        val o = observations[frame.sample.device] ?: return
        if (o.count == 0L) o.firstNs = frame.sourceTimeNs else {
            val dt = frame.sourceTimeNs - o.lastNs
            if (dt > 0) { o.minIntervalNs = minOf(o.minIntervalNs,dt); o.maxIntervalNs = maxOf(o.maxIntervalNs,dt) }
        }
        o.count++; o.lastNs = frame.sourceTimeNs; o.receivedNs = frame.receivedTimeNs
        o.clockErrorNs = maxOf(o.clockErrorNs,frame.clockErrorNs)
    }

    fun report(bridge: BridgeStatistics? = null): String {
        val data = JSONArray()
        observations.values.forEach { o ->
            val json = JSONObject(o.metadata.toString()).put("acceptedSamples",o.count).put("firstSourceTimeNs",o.firstNs)
                .put("lastSourceTimeNs",o.lastNs).put("lastReceivedTimeNs",o.receivedNs).put("maximumClockErrorNs",o.clockErrorNs)
                .put("observedSampleHz",if (o.count > 1 && o.lastNs > o.firstNs) (o.count - 1) * 1e9 / (o.lastNs - o.firstNs) else JSONObject.NULL)
                .put("minSampleIntervalNs",if (o.minIntervalNs == Long.MAX_VALUE) JSONObject.NULL else o.minIntervalNs)
                .put("maxSampleIntervalNs",o.maxIntervalNs)
            data.put(json)
        }
        val actual = observations.values.count { it.count > 0 && it.metadata.optString("identitySource") != "simulator" }
        val stableIds=observations.values.filter { it.count>0 && it.metadata.optBoolean("physicalIdentityStable") }
            .map { it.metadata.optString("physicalId") }.filter { it.isNotBlank() }.distinct()
        return JSONObject().put("schema",1).put("status",if (actual == 0) "HARDWARE_PENDING" else "OBSERVATIONS_ONLY")
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("release",Build.VERSION.RELEASE).put("observedControllerStreams",actual).put("distinctStablePhysicalIds",JSONArray(stableIds))
            .put("hardwareVerified",false).put("streamCountIsPhysicalControllerProof",false).put("devices",data)
            .put("requiredHardwareAcceptance","1 to 4 individually verified physical controllers, 10 minute continuous recording")
            .put("rawHidPermissionBoundary","Only accessible /dev/hidraw nodes; ordinary APK install does not grant access; no root requested")
            .put("bridgeClock",JSONObject().put("maximumRoundTripMs",30).put("staleActionMs",100).put("reorderWindowMs",20)
                .put("clockValiditySeconds",10).put("exchangeIntervalSeconds",2).put("assumedClockDriftPpm",100)
                .put("clockDriftHardwareVerified",false)
                .put("unsynchronizedPackets",bridge?.unsynchronizedPackets ?: 0).put("rejectedClockExchanges",bridge?.rejectedClockExchanges ?: 0)
                .put("acceptedClockExchanges",bridge?.acceptedClockExchanges ?: 0).put("synchronizedClockCount",bridge?.synchronizedClockCount ?: 0)
                .put("lastRejection",bridge?.lastRejection ?: JSONObject.NULL))
            .toString(2)
    }
    private fun trim() { while (observations.size > 64) observations.remove(observations.keys.first()) }
}
