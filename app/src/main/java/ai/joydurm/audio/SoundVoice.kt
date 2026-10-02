package ai.joydurm.audio

import ai.joydurm.core.Drum
import ai.joydurm.core.Hit

/** Sample identity is separate from the drum that triggers it. */
enum class SoundVoice(val label: String, val drum: Drum? = null) {
    KICK("地鼓", Drum.KICK), SNARE("军鼓", Drum.SNARE),
    TOM1("高嗵", Drum.TOM1), TOM2("中嗵", Drum.TOM2), FLOOR("落地嗵", Drum.FLOOR),
    HAT("闭镲", Drum.HAT), HAT_HALF("半开镲", Drum.HAT), HAT_OPEN("全开镲", Drum.HAT),
    CRASH("吊镲", Drum.CRASH), RIDE("叮叮镲", Drum.RIDE), CHICK("踩镲闭合", Drum.CHICK),
    CLICK("节拍器");

    val sampleName get() = name
    val ringsOpenHat get() = this == HAT_HALF || this == HAT_OPEN

    companion object {
        val importable = entries.filter { it != CLICK }
        fun fromDrum(drum: Drum): SoundVoice = valueOf(drum.name)
        fun fromHit(hit: Hit): SoundVoice = if (hit.drum == Drum.HAT) {
            when {
                hit.openness > 0.65f -> HAT_OPEN
                hit.openness > 0.15f -> HAT_HALF
                else -> HAT
            }
        } else fromDrum(hit.drum)
    }
}
