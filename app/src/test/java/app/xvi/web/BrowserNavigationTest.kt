package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    @Test fun backAtHomeDoesNotLeaveTheApp() {
        assertEquals(
            BrowserNavigationAction.NONE,
            BrowserNavigation.back(homeVisible = true, canGoBack = true, hasPage = true, multipleTabs = true)
        )
    }

    @Test fun backFromPageWithoutWebHistoryShowsHome() {
        assertEquals(
            BrowserNavigationAction.SHOW_HOME,
            BrowserNavigation.back(homeVisible = false, canGoBack = false, hasPage = true, multipleTabs = false)
        )
    }

    @Test fun forwardFromHomeRestoresTheRetainedPage() {
        assertEquals(
            BrowserNavigationAction.SHOW_PAGE,
            BrowserNavigation.forward(homeVisible = true, canGoForward = false, hasPage = true)
        )
    }

    @Test fun forwardOnPageUsesWebViewHistory() {
        assertEquals(
            BrowserNavigationAction.WEB_FORWARD,
            BrowserNavigation.forward(homeVisible = false, canGoForward = true, hasPage = true)
        )
    }

    @Test fun openingUrlFromHomeWithRetainedPageCreatesANewTab() {
        assertTrue(BrowserNavigation.shouldCreateNewTabFromHome(homeVisible = true, currentTabHasPage = true))
        assertFalse(BrowserNavigation.shouldCreateNewTabFromHome(homeVisible = true, currentTabHasPage = false))
    }

    @Test fun stalePreparationCannotReopenPageAfterHomeOrTabSwitch() {
        assertFalse(BrowserNavigation.shouldShowPageAfterPreparation(isCurrentTab = true, homeVisible = true))
        assertFalse(BrowserNavigation.shouldShowPageAfterPreparation(isCurrentTab = false, homeVisible = false))
        assertTrue(BrowserNavigation.shouldShowPageAfterPreparation(isCurrentTab = true, homeVisible = false))
        assertFalse(BrowserNavigation.shouldApplyPreparedNavigation(true, "https://new.example", "https://old.example"))
        assertTrue(BrowserNavigation.shouldApplyPreparedNavigation(true, "https://new.example", "https://new.example"))
    }

    @Test fun tabCounterShowsTheCurrentCount() {
        assertEquals("1", BrowserNavigation.tabCountLabel(1))
        assertEquals("10+", BrowserNavigation.tabCountLabel(10))
        assertEquals("10+", BrowserNavigation.tabCountLabel(25))
    }
}
