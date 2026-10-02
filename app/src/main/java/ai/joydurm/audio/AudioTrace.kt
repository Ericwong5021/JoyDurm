package ai.joydurm.audio

import java.io.File
import java.util.ArrayDeque

enum class AudioOutcome { PLAYED, SAMPLE_NOT_READY, BACKEND_REJECTED, PAUSED, INVALID_GAIN }

/** All times use the Android elapsed-realtime clock after source-clock mapping. */
data class AudioTraceEntry(
    val voice: SoundVoice,
    val sourceActionTimeNs: Long,
    val submittedTimeNs: Long,
    val returnedTimeNs: Long,
    val streamId: Int,
    val outcome: AudioOutcome
)

data class AudioControlTraceEntry(
    val sourceActionTimeNs: Long,
    val submittedTimeNs: Long,
    val reason: String,
    val stoppedStreams: Int
)

data class AudioTraceSnapshot(
    val entries: List<AudioTraceEntry>,
    val counts: Map<AudioOutcome, Long>,
    val overwrittenEntries: Long,
    val controls: List<AudioControlTraceEntry> = emptyList(),
    val overwrittenControls: Long = 0
) {
    val failedSubmissions get() = (counts[AudioOutcome.BACKEND_REJECTED] ?: 0) +
        (counts[AudioOutcome.SAMPLE_NOT_READY] ?: 0) + (counts[AudioOutcome.INVALID_GAIN] ?: 0)

    /** This measures submission only; SoundPool return does not measure acoustic onset. */
    fun actionToSubmitPercentileNs(percentile: Double): Long? {
        require(percentile in 0.0..1.0)
        val values = entries.asSequence()
            .filter { it.outcome == AudioOutcome.PLAYED && it.submittedTimeNs >= it.sourceActionTimeNs }
            .map { it.submittedTimeNs - it.sourceActionTimeNs }.sorted().toList()
        return values.takeIf { it.isNotEmpty() }?.get(
            kotlin.math.ceil(percentile * (values.size - 1)).toInt()
        )
    }
}

/** Bounded measurements with lifetime counters, independent of Android for replay tests. */
class AudioTrace(private val capacity: Int = 4096) {
    init { require(capacity > 0) }
    private val entries = ArrayDeque<AudioTraceEntry>(capacity)
    private val controls = ArrayDeque<AudioControlTraceEntry>(capacity)
    private val counts = mutableMapOf<AudioOutcome, Long>()
    private var overwritten = 0L
    private var overwrittenControls = 0L

    @Synchronized fun record(entry: AudioTraceEntry) {
        if (entries.size == capacity) { entries.removeFirst(); overwritten++ }
        entries.addLast(entry)
        counts[entry.outcome] = (counts[entry.outcome] ?: 0) + 1
    }

    @Synchronized fun recordControl(entry: AudioControlTraceEntry) {
        if (controls.size == capacity) { controls.removeFirst(); overwrittenControls++ }
        controls.addLast(entry)
    }

    @Synchronized fun snapshot() = AudioTraceSnapshot(
        entries.toList(), counts.toMap(), overwritten, controls.toList(), overwrittenControls
    )

    fun csv(): String {
        val snapshot = snapshot()
        return buildString {
            append("# SoundPool submission trace; acoustic onset is not measured\n")
            append("# overwritten_entries,${snapshot.overwrittenEntries}\n")
            append("# overwritten_controls,${snapshot.overwrittenControls}\n")
            AudioOutcome.entries.forEach { append("# count_${it.name},${snapshot.counts[it] ?: 0}\n") }
            append("voice,source_action_time_ns,submitted_time_ns,returned_time_ns,stream_id,outcome\n")
            snapshot.entries.forEach {
                append("${it.voice.name},${it.sourceActionTimeNs},${it.submittedTimeNs},${it.returnedTimeNs},${it.streamId},${it.outcome.name}\n")
            }
            append("# control_source_action_time_ns,submitted_time_ns,reason,stopped_streams\n")
            snapshot.controls.forEach {
                val reason = it.reason.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
                append("# control,${it.sourceActionTimeNs},${it.submittedTimeNs},\"$reason\",${it.stoppedStreams}\n")
            }
        }
    }

    fun export(file: File) { file.writeText(csv(), Charsets.UTF_8) }
}
