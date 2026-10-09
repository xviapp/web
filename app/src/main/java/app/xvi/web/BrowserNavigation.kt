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

    fun shouldCreateNewTabFromHome(homeVisible: Boolean, currentTabHasPage: Boolean): Boolean =
        homeVisible && currentTabHasPage

    fun shouldShowPageAfterPreparation(isCurrentTab: Boolean, homeVisible: Boolean): Boolean =
        isCurrentTab && !homeVisible

    fun shouldApplyPreparedNavigation(targetStillExists: Boolean, targetUrl: String, requestedUrl: String): Boolean =
        targetStillExists && targetUrl == requestedUrl

    fun tabCountLabel(count: Int): String = if (count >= 10) "10+" else count.coerceAtLeast(0).toString()

    /** A private tab may close back to ordinary tabs; the last ordinary tab never closes on back. */
    fun canCloseOnBack(privateTab: Boolean, groupCount: Int): Boolean = privateTab || groupCount > 1

    /**
     * Nearest remaining tab in the same mode after removing [removedIndex], preferring the one that
     * took its place. [groups] holds each remaining tab's private flag; null means the group is empty.
     */
    fun neighborInGroup(groups: List<Boolean>, removedIndex: Int, private: Boolean): Int? {
        val start = removedIndex.coerceIn(0, groups.size)
        return (start until groups.size).firstOrNull { groups[it] == private }
            ?: (start - 1 downTo 0).firstOrNull { groups[it] == private }
    }
}
