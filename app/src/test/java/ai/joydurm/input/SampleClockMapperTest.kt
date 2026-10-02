package ai.joydurm.input

import org.junit.Assert.*
import org.junit.Test

class SampleClockMapperTest {
    @Test fun offsetIntervalConservativelyMapsSourceWithoutInventingFreshness() {
        val mapper=SampleClockMapper()
        assertTrue(mapper.synchronize(1_000_000_000L,5_001_000_000L,5_002_000_000L,1_004_000_000L))
        val m=mapper.map(5_020_000_000L,1_030_000_000L)!!
        assertEquals(1_018_997_400L,m.localTimeNs); assertEquals(3_005_200L,m.errorBoundNs)
    }
    @Test fun clockAgeIncreasesUncertaintyAndOverBudgetClocksRequireAnotherExchange() {
        val mapper=SampleClockMapper()
        assertTrue(mapper.synchronize(1_000_000_000L,1_000_000_000L,1_000_000_000L,1_029_000_000L))
        val initial=mapper.map(1_010_000_000L,1_029_000_000L)!!
        val later=mapper.map(1_010_000_000L,5_029_000_000L)!!
        assertTrue(later.errorBoundNs>initial.errorBoundNs); assertTrue(later.localTimeNs<initial.localTimeNs)
        assertNull(mapper.map(1_010_000_000L,7_029_000_000L)); assertTrue(mapper.lastError!!.contains("assumed 100 ppm"))
    }
    @Test fun invalidHighLatencyAndExpiredExchangesDoNotGrantClockValidity() {
        val mapper=SampleClockMapper()
        assertFalse(mapper.synchronize(10L,20L,19L,30L)); assertNull(mapper.map(20L,30L))
        assertFalse(mapper.synchronize(0,0,0,31_000_000L))
        assertTrue(mapper.synchronize(100L,100L,100L,100L))
        assertNull(mapper.map(101L,SampleClockMapper.VALID_FOR_NS+101L))
    }
}
