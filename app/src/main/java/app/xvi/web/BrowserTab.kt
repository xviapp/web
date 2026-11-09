package app.xvi.web

import android.graphics.Bitmap
import android.os.Bundle

/** One browser tab. Ordinary tabs persist their address; private tabs live only in memory. */
internal class BrowserTab(
    var url: String = "", var title: String = "", var desktop: Boolean = false,
    var view: BrowserWebView? = null, var state: Bundle? = null, var touched: Long = 0,
    var session: BrowserSession, var keepAlive: Boolean = false, var documentVersion: Long = 0,
    var restoreScroll: Pair<Int, Int>? = null, var favicon: Bitmap? = null,
    /** Navigation requested but held until the network (proxy) is ready. */
    var waiting: Boolean = false,
    /** Counts held navigations; one prepared after another started or after a stop is dropped. */
    var navigation: Int = 0,
    /** Failure of the last main-frame load, shown whenever the tab is selected until it loads again. */
    var error: PageError? = null,
    /** Main-frame address being navigated to, before the page starts. */
    var navigating: String? = null,
    /** The plain HTTP address being tried over HTTPS, until the page commits or the attempt fails. */
    var upgradedFrom: String? = null,
    /** Script dialogs: the document that the user silenced, and how many the current document showed. */
    var dialogsBlocked: Long = -1, var dialogDocument: Long = -1, var dialogCount: Int = 0,
    /** The document that last asked, without a tap, to open another app, and the page's tap count then. */
    var externalAsked: Long = -1, var externalAskedTaps: Long = -1
) : TabManager.Entry {
    override val isPrivate get() = session.isPrivate
    override val isBlank get() = url.isEmpty() && view == null
    /** The AI assistant's conversation about the page showing when it was opened. */
    var ai: AiConversation? = null
    /** Whether ads are blocked for the page; read on WebView's network thread. */
    @Volatile var adblock = true
    /** Requests blocked on the current page. */
    val blocked = java.util.concurrent.atomic.AtomicInteger()
    /** The web page the view has moved to; [url] already names a page still being asked for. */
    var shownPage: String? = null
}

/**
 * What went wrong loading a page, with an optional extra action (label to action) for the error screen.
 * [blockedSite] is set when parental control blocked the site, which has a page of its own.
 */
internal class PageError(val title: String, val message: String, val action: Pair<String, () -> Unit>? = null, val blockedSite: String? = null)
