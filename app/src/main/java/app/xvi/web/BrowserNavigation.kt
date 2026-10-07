package app.xvi.web

internal enum class BrowserNavigationAction {
    NONE,
    WEB_BACK,
    SHOW_HOME,
    CLOSE_TAB,
    SHOW_PAGE,
    WEB_FORWARD
}

internal object BrowserNavigation {
    fun back(homeVisible: Boolean, canGoBack: Boolean, hasPage: Boolean, multipleTabs: Boolean): BrowserNavigationAction = when {
        homeVisible -> BrowserNavigationAction.NONE
        canGoBack -> BrowserNavigationAction.WEB_BACK
        hasPage -> BrowserNavigationAction.SHOW_HOME
        multipleTabs -> BrowserNavigationAction.CLOSE_TAB
        else -> BrowserNavigationAction.NONE
    }

    fun forward(homeVisible: Boolean, canGoForward: Boolean, hasPage: Boolean): BrowserNavigationAction = when {
        homeVisible && hasPage -> BrowserNavigationAction.SHOW_PAGE
        !homeVisible && canGoForward -> BrowserNavigationAction.WEB_FORWARD
        else -> BrowserNavigationAction.NONE
    }
}
