package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBookmarkPresentationTest {
    @Test fun collapsedHomeShowsOneRow() {
        assertEquals(0, HomeBookmarkPresentation.visibleCount(0, expanded = false))
        assertEquals(4, HomeBookmarkPresentation.visibleCount(10, expanded = false))
        assertTrue(HomeBookmarkPresentation.showsToggle(5))
    }

    @Test fun expandedHomeShowsAtMostFourRowsAndOffersMore() {
        assertEquals(16, HomeBookmarkPresentation.visibleCount(20, expanded = true))
        assertFalse(HomeBookmarkPresentation.showsMore(16, expanded = true))
        assertTrue(HomeBookmarkPresentation.showsMore(17, expanded = true))
    }
}
