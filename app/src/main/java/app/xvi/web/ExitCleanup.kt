package app.xvi.web

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Removes what [BrowserPrefs.clearOnExit] names, from every profile, when the browser is quit: the
 * setting promises the data is gone, and data does not stop being there for belonging to another profile. A quit that ends the process
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
    fun run(context: Context, done: () -> Unit = {}) {
        val chosen = BrowserPrefs(context).clearOnExit
        val main = Handler(Looper.getMainLooper())
        var waiting = 1
        fun finished() { if (--waiting == 0) done() }
        BrowserProfiles.all(context).forEach { profile ->
            val prefs = BrowserPrefs(context, profile.id)
            // Holds no pages but the short-lived one below, so it needs no ending.
            val session = BrowserSession(namedProfile = profile.webProfile)
            if (TABS in chosen) prefs.saveTabs(emptyList(), 0)
            if (HISTORY in chosen) {
                prefs.saveClosed(emptyList())
                waiting++
                BrowserStore.get(context, profile.id).writeAsync { clearHistory(); main.post(::finished) }
            }
            if (SITE_DATA in chosen) { waiting++; session.clearWebsiteData(::finished) }
            // Every page of a profile shares its cache, so any of them can clear it. [context] is the
            // calling activity: WebView expects one, not the application context.
            if (CACHE in chosen) session.createWebView(context).let { it.clearCache(true); session.release(it) }
        }
        finished()
    }
}
