package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBookmarkPresentationTest {
    @Test fun collapsedHomeShowsTwoRows() {
        assertEquals(0, HomeBookmarkPresentation.visibleCount(0, expanded = false))
        assertEquals(5, HomeBookmarkPresentation.visibleCount(5, expanded = false))
        assertEquals(8, HomeBookmarkPresentation.visibleCount(10, expanded = false))
    }

    /** Up to two rows show in full, so the toggle only appears once there is more to see. */
    @Test fun toggleOnlyBeyondTwoRows() {
        assertFalse(HomeBookmarkPresentation.showsToggle(8))
        assertTrue(HomeBookmarkPresentation.showsToggle(9))
    }

    @Test fun expandedHomeShowsAtMostFourRowsAndOffersMore() {
        assertEquals(16, HomeBookmarkPresentation.visibleCount(20, expanded = true))
        assertFalse(HomeBookmarkPresentation.showsMore(16, expanded = true))
        assertTrue(HomeBookmarkPresentation.showsMore(17, expanded = true))
    }
}
