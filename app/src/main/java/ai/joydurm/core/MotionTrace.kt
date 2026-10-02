package ai.joydurm.core

import java.util.Collections

/** Pure, deterministic trace records. Source/event time remains separate from delivery time. */
sealed interface MotionTraceEvent {
    data class Frame(val frame: ImuFrame): MotionTraceEvent
    data class Assign(val role: Role, val device: String): MotionTraceEvent
    data class Recenter(val role: Role): MotionTraceEvent
    data class Lost(val device: String, val session: SessionId? = null): MotionTraceEvent
    data class Bind(val role: Role, val drum: Drum): MotionTraceEvent
    data class Layout(val pieces: Map<Drum,PiecePose>, val scale: Float, val yaw: Float): MotionTraceEvent
}
class MotionTrace(private val capacity: Int = 4096) {
    private val events=ArrayDeque<MotionTraceEvent>()
    init { require(capacity>0) }
    var dropped=0L; private set
    fun record(event: MotionTraceEvent) {
        if(events.size==capacity) { events.removeFirst(); dropped++ }
        val captured=if(event is MotionTraceEvent.Layout) event.copy(pieces=Collections.unmodifiableMap(LinkedHashMap(event.pieces))) else event
        events.addLast(captured)
    }
    fun snapshot(): List<MotionTraceEvent> = events.toList()
    companion object {
        /** Intended for offline tests; live adapters enqueue these operations through EngineExecutor. */
        fun replay(events: Iterable<MotionTraceEvent>, engine: DrumEngine) {
            events.forEach { event -> when(event) {
                is MotionTraceEvent.Frame -> engine.process(event.frame)
                is MotionTraceEvent.Assign -> engine.assign(event.role,event.device)
                is MotionTraceEvent.Recenter -> engine.recenter(event.role)
                is MotionTraceEvent.Lost -> engine.sessionLost(event.device,event.session)
                is MotionTraceEvent.Bind -> engine.bindTarget(event.role,event.drum)
                is MotionTraceEvent.Layout -> engine.replaceLayout(event.pieces,event.scale,event.yaw)
            } }
        }
    }
}
