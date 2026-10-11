package app.xvi.web

/**
 * What a back press does when something in the browser's own interface is open: full-screen video,
 * the reader, find-in-page, or an address field being edited. Each of those is dismissed first, and
 * only when none of them is open does the press reach the page and its tab, which
 * [BrowserNavigation.back] decides.
 *
 * The order matters and is invisible in the code that applies it, so it is stated here once and
 * covered by tests.
 */
internal object PageShell {
    enum class Layer {
        NONE,
        FULLSCREEN,
        READER,
        FIND_IN_PAGE,
        /** The address bar or the start page's search field is being edited, with suggestions over the page. */
        SEARCH
    }

    /** Innermost open layer, which a back press closes before anything else. */
    fun topLayer(
        fullScreen: Boolean,
        reader: Boolean,
        findInPage: Boolean,
        searchOverlay: Boolean,
        addressFocused: Boolean,
        homeSearchFocused: Boolean
    ): Layer = when {
        fullScreen -> Layer.FULLSCREEN
        reader -> Layer.READER
        findInPage -> Layer.FIND_IN_PAGE
        searchOverlay || addressFocused || homeSearchFocused -> Layer.SEARCH
        else -> Layer.NONE
    }

    /**
     * Whether the system should handle back itself. The browser only claims it while one of its own
     * layers is open, or while a page rather than the start page is showing, so Android 12 and later
     * keeps its predictive-back animation for leaving the app from the start page.
     */
    fun handlesBack(layer: Layer, pageVisible: Boolean, sdk: Int): Boolean = layer != Layer.NONE || pageVisible || sdk < 31
}
