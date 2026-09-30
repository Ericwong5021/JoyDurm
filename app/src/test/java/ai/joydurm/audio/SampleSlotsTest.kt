package ai.joydurm.audio

import org.junit.Assert.*
import org.junit.Test

class SampleSlotsTest {
    @Test fun replacementDoesNotInterruptPlayableSample() {
        val slots=SampleSlots()
        slots.register("0:SNARE",1); slots.complete(1,true)
        slots.register("0:SNARE",2)
        assertEquals(1,slots.id("0:SNARE"))
        assertEquals(SampleSlots.Completion("0:SNARE",true,1),slots.complete(2,true))
        assertEquals(2,slots.id("0:SNARE"))
    }
    @Test fun failedReplacementPreservesOldSound() {
        val slots=SampleSlots()
        slots.register("0:SNARE",1); slots.complete(1,true)
        slots.register("0:SNARE",2)
        assertEquals(SampleSlots.Completion("0:SNARE",false,2),slots.complete(2,false))
        assertEquals(1,slots.id("0:SNARE"))
    }
    @Test fun lateCompletionCannotOverwriteNewerRequestEvenAfterFailure() {
        val slots=SampleSlots()
        slots.register("0:SNARE",1); slots.complete(1,true)
        slots.register("0:SNARE",2); slots.register("0:SNARE",3)
        slots.complete(3,false)
        assertEquals(SampleSlots.Completion("0:SNARE",false,2),slots.complete(2,true))
        assertEquals(1,slots.id("0:SNARE"))
    }
    @Test fun bankIsolationAndDuplicateCallback() {
        val slots=SampleSlots()
        slots.register("0:SNARE",1); slots.register("1:SNARE",2)
        slots.complete(2,true); slots.complete(1,true)
        assertEquals(2,slots.loadedCount); assertEquals(0,slots.pendingCount)
        assertNull(slots.complete(2,true))
        slots.clear(); assertNull(slots.id("1:SNARE")); assertEquals(0,slots.loadedCount)
    }
}
