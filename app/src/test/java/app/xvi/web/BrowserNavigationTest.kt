package app.xvi.web

import org.junit.Assert.assertEquals
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
}
