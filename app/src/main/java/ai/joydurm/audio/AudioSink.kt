package ai.joydurm.audio

import ai.joydurm.core.HatControl
import ai.joydurm.core.Hit

/** The engine emits controls separately from audible hits. */
interface AudioSink {
    fun play(hit: Hit): Int
    fun control(control: HatControl)
    fun chokeAll()
    fun pause()
    fun resume(): Boolean
}
