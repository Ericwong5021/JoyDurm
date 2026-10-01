package ai.joydurm.ui

import ai.joydurm.core.Drum
import ai.joydurm.core.Role

enum class StagePage { WELCOME, CONNECT, ROLES, HANDS, HAT, PLACE, SOUND_CHECK, PLAY, KIT, DEVICES, SETTINGS }

data class StageRole(
    val role: Role,
    val deviceName: String? = null,
    val bound: Boolean = false,
    val live: Boolean = false,
    val calibrated: Boolean = false,
    val needsRecenter: Boolean = true,
    val targetCount: Int = 0,
    val samples: Long = 0,
    val ageMs: Long? = null,
    val error: String? = null,
    val threshold: Double = 2.2,
)

data class StageState(
    val roles: List<StageRole>,
    val openness: Float = 0f,
    val hatRangeDegrees: Double = 30.0,
    val hatClosedCaptured: Boolean = false,
    val hatCalibrated: Boolean = false,
    val arEnabled: Boolean = false,
    val placed: Boolean = false,
    val kitIndex: Int = 0,
    val volumePercent: Int = 80,
    val bpm: Int = 100,
    val metronome: Boolean = false,
    val motionDeviceCount: Int = 0,
    val lastStatus: String = "",
    val outputDescription: String = "由 Android 系统选择",
) {
    fun role(role: Role) = roles.first { it.role == role }
}

/** UI commands only; MainActivity owns transport, engine, audio and renderer mutations. */
interface StageActions {
    fun navigate(page: StagePage)
    fun back()
    fun pairBluetooth()
    fun connectionDetails()
    fun assign(role: Role)
    fun calibrate(role: Role)
    fun recenter(role: Role)
    fun tune(role: Role)
    fun bindTarget(role: Role)
    fun captureHatClosed()
    fun captureHatOpen()
    fun toggleAr()
    fun editLayout()
    fun resetPlacement()
    fun trigger(drum: Drum)
    fun setKit(index: Int)
    fun setVolume(percent: Int)
    fun setTempo(bpm: Int)
    fun toggleMetronome()
    fun importSound()
    fun exportAudioTrace()
    fun importModel()
    fun exportImu()
    fun capabilityReport()
    fun systemAudioSettings()
    fun setSensitivity(role: Role, threshold: Double)
    fun openLegacyCalibration()
    fun completeOnboarding()
}
