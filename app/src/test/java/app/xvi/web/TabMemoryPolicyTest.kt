package app.xvi.web

import org.junit.Assert.*
import org.junit.Test
import app.xvi.web.TabMemoryPolicy.Pressure.*

class TabMemoryPolicyTest {
    @Test fun healthyMemoryDoesNotEvictEvenFiftyTabs() {
        assertEquals(NORMAL, TabMemoryPolicy.pressure(false, 4000, 300, 8000, 100, 1000))
        assertEquals(0, TabMemoryPolicy.batchSize(50, NORMAL))
    }
    @Test fun detectsSystemAndAppHeapPressure() {
        assertEquals(CRITICAL, TabMemoryPolicy.pressure(true, 4000, 300, 8000, 100, 1000))
        assertEquals(MODERATE, TabMemoryPolicy.pressure(false, 700, 300, 8000, 100, 1000))
        assertEquals(MODERATE, TabMemoryPolicy.pressure(false, 4000, 300, 8000, 750, 1000))
        assertEquals(CRITICAL, TabMemoryPolicy.pressure(false, 4000, 300, 8000, 900, 1000))
    }
    @Test fun selectedPinnedAndLoadingTabsAreNeverCandidates() {
        for (pressure in listOf(MODERATE, CRITICAL)) {
            assertFalse(TabMemoryPolicy.eligible(true, false, false, 60000, pressure))
            assertFalse(TabMemoryPolicy.eligible(false, true, false, 60000, pressure))
            assertFalse(TabMemoryPolicy.eligible(false, false, true, 60000, pressure))
        }
    }
    @Test fun moderatePressureGivesRecentlyUsedTabsAGracePeriod() {
        assertFalse(TabMemoryPolicy.eligible(false, false, false, 29999, MODERATE))
        assertTrue(TabMemoryPolicy.eligible(false, false, false, 30000, MODERATE))
        assertTrue(TabMemoryPolicy.eligible(false, false, false, 0, CRITICAL))
        assertFalse(TabMemoryPolicy.eligible(false, false, false, 60000, NORMAL))
    }
    @Test fun pressureReclaimsABatchInsteadOfForcingThreeInstances() {
        assertEquals(2, TabMemoryPolicy.batchSize(8, MODERATE))
        assertEquals(4, TabMemoryPolicy.batchSize(8, CRITICAL))
        assertEquals(0, TabMemoryPolicy.batchSize(1, CRITICAL))
        assertEquals(0, TabMemoryPolicy.batchSize(0, CRITICAL))
    }
}
