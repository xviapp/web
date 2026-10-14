package app.xvi.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Removes what [BrowserPrefs.clearOnExit] names when the browser is quit. A quit that ends the process
 * before this finishes (swiping the browser away often does) leaves [BrowserPrefs.exitCleanupPending]
 * set, and the next start finishes the job before anything is shown.
 */
internal object ExitCleanup {
    const val TABS = "tabs"
    const val HISTORY = "history"
    const val SITE_DATA = "siteData"
    const val CACHE = "cache"
    val ALL = listOf(TABS, HISTORY, SITE_DATA, CACHE)

    /** Clears what is chosen; [done] runs on the main thread once all of it is gone. */
    fun run(context: Context, prefs: BrowserPrefs, store: BrowserStore, session: BrowserSession, done: () -> Unit = {}) {
        val chosen = prefs.clearOnExit
        val main = Handler(Looper.getMainLooper())
        var waiting = 1
        fun finished() { if (--waiting == 0) done() }
        if (TABS in chosen) prefs.saveTabs(emptyList(), 0)
        if (HISTORY in chosen) {
            prefs.saveClosed(emptyList())
            waiting++
            store.writeAsync { clearHistory(); main.post(::finished) }
        }
        if (SITE_DATA in chosen) { waiting++; session.clearWebsiteData(::finished) }
        // The cache is shared by every WebView of the app, so any instance can clear it. [context] is the
        // calling activity: WebView expects one, not the application context.
        if (CACHE in chosen) WebView(context).apply { clearCache(true); destroy() }
        finished()
    }
}
