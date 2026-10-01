package ai.joydurm.core

data class ControllerIdentity(val physicalId: String, val transport: String, val stable: Boolean = true,
    val bindingId: String = physicalId)
@JvmInline value class SessionId(val value: String)
@JvmInline value class OrientationEpoch(val value: Long)
/** All local times use Android elapsedRealtimeNanos (JVM traces use an injected clock). */
data class ImuFrame(val identity: ControllerIdentity, val session: SessionId,
    val sourceTimeNs: Long, val receivedTimeNs: Long, val clockErrorNs: Long, val sample: ImuSample)
sealed interface HatControl {
    data class Openness(val value: Float, val timeNs: Long): HatControl
    data class ChokeAll(val timeNs: Long, val reason: String): HatControl
}
data class PiecePose(val x: Float, val y: Float, val z: Float)
data class DrumLayout(val revision: Long = 0, val pieces: Map<Drum,PiecePose> = emptyMap(),
    val scale: Float = 1f, val yaw: Float = 0f)
enum class OrientationStatus { NEEDS_RECENTER, READY }
data class RoleSnapshot(val device: String?, val latestTimeNs: Long?, val calibration: Calibration?,
    val attitude: Attitude, val epoch: OrientationEpoch, val status: OrientationStatus,
    val axis: Int, val sign: Double, val threshold: Double, val cooldownNs: Long,
    val targetCount: Int, val neutral: Quaternion, val latest: ImuSample? = null,
    val targets: List<Target> = emptyList(), val calibrationCount: Int = 0,
    val session: SessionId? = null, val hatCalibrated: Boolean = false) {
    val needsRecenter get() = status == OrientationStatus.NEEDS_RECENTER
}
data class EngineSnapshot(val revision: Long, val layout: DrumLayout, val roles: Map<Role,RoleSnapshot>,
    val openness: Float, val timeNs: Long, val hatRange: Double = Math.toRadians(30.0), val hatSign: Double = 1.0)
typealias HitEvent = Hit
