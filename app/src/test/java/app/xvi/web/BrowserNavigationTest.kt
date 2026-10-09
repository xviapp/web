package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun backClosesPrivateTabsButKeepsTheLastOrdinaryTab() {
        assertTrue(BrowserNavigation.canCloseOnBack(privateTab = true, groupCount = 1))
        assertFalse(BrowserNavigation.canCloseOnBack(privateTab = false, groupCount = 1))
        assertTrue(BrowserNavigation.canCloseOnBack(privateTab = false, groupCount = 2))
    }

    @Test fun closingATabSelectsTheNextTabInTheSameMode() {
        // Remaining tabs after closing index 1 from [normal, private, private, normal].
        val remaining = listOf(false, true, false)
        assertEquals(1, BrowserNavigation.neighborInGroup(remaining, removedIndex = 1, private = true))
        assertEquals(2, BrowserNavigation.neighborInGroup(remaining, removedIndex = 1, private = false))
    }

    @Test fun closingTheLastTabFallsBackToThePreviousTabInTheSameMode() {
        assertEquals(0, BrowserNavigation.neighborInGroup(listOf(false, true), removedIndex = 2, private = false))
        assertEquals(1, BrowserNavigation.neighborInGroup(listOf(false, true), removedIndex = 2, private = true))
    }

    @Test fun closingTheOnlyTabOfAModeNeverSelectsTheOtherMode() {
        assertNull(BrowserNavigation.neighborInGroup(listOf(true, true), removedIndex = 0, private = false))
        assertNull(BrowserNavigation.neighborInGroup(listOf(false), removedIndex = 1, private = true))
    }
}
