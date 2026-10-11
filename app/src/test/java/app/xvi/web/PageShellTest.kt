package app.xvi.web

import app.xvi.web.PageShell.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A back press closes the innermost open interface layer before it reaches the page. The order is
 * what makes full-screen video and the reader behave, and nothing in the browser chrome states it,
 * so it is pinned down here.
 */
class PageShellTest {
    @Test fun nothingOpenLeavesThePressToThePage() {
        assertEquals(Layer.NONE, PageShell.topLayer(false, false, false, false, false, false))
    }

    @Test fun everyLayerIsRecognisedOnItsOwn() {
        assertEquals(Layer.FULLSCREEN, PageShell.topLayer(true, false, false, false, false, false))
        assertEquals(Layer.READER, PageShell.topLayer(false, true, false, false, false, false))
        assertEquals(Layer.FIND_IN_PAGE, PageShell.topLayer(false, false, true, false, false, false))
        assertEquals(Layer.SEARCH, PageShell.topLayer(false, false, false, true, false, false))
        assertEquals(Layer.SEARCH, PageShell.topLayer(false, false, false, false, true, false))
        assertEquals(Layer.SEARCH, PageShell.topLayer(false, false, false, false, false, true))
    }

    /** Full-screen video must leave full screen even with the find bar open behind it. */
    @Test fun theInnermostLayerWins() {
        assertEquals(Layer.FULLSCREEN, PageShell.topLayer(true, true, true, true, true, true))
        assertEquals(Layer.READER, PageShell.topLayer(false, true, true, true, true, true))
        assertEquals(Layer.FIND_IN_PAGE, PageShell.topLayer(false, false, true, true, true, true))
        assertEquals(Layer.SEARCH, PageShell.topLayer(false, false, false, true, true, true))
    }

    @Test fun theBrowserClaimsBackWhileOneOfItsOwnLayersIsOpen() {
        listOf(Layer.FULLSCREEN, Layer.READER, Layer.FIND_IN_PAGE, Layer.SEARCH).forEach {
            assertTrue(it.name, PageShell.handlesBack(it, pageVisible = false, sdk = 34))
        }
    }

    @Test fun aShowingPageOrAnOlderSystemAlsoClaimsBack() {
        assertTrue("page showing on a new system", PageShell.handlesBack(Layer.NONE, pageVisible = true, sdk = 34))
        // Android 12 and later handles it, which keeps the predictive-back animation.
        assertFalse("start page on a new system", PageShell.handlesBack(Layer.NONE, pageVisible = false, sdk = 34))
        assertTrue("start page on an older system", PageShell.handlesBack(Layer.NONE, pageVisible = false, sdk = 30))
        assertTrue("page showing on an older system", PageShell.handlesBack(Layer.NONE, pageVisible = true, sdk = 30))
    }
}
