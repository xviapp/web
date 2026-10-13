package app.xvi.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri

/** Navigation callbacks for one tab's WebView. Stale callbacks from a replaced view are ignored. */
@SuppressLint("MissingOnRenderProcessGone") // Implemented below; the check misses Kotlin's non-null override.
internal class PageClient(private val host: PageHost, private val target: BrowserTab) : WebViewClient() {
    private fun stale(view: WebView) = target.view !== view || target !in host.tabs
    /** The document requests come from, for the filter's site and third-party checks; read on the network thread. */
    @Volatile private var document: String? = null

    /** Runs on WebView's network thread for every request; blocked ones get an empty answer. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.isForMainFrame) { document = url; target.blocked.set(0); return null }
        if (!target.adblock || !url.startsWith("http", ignoreCase = true)) return null
        val filter = AdBlock.filter ?: return null
        val page = document ?: return null
        if (!filter.blocks(url, page, AdFilter.requestTypes(url, request.requestHeaders["Accept"]))) return null
        target.blocked.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (BrowserInput.isWebUrl(request.url.toString())) {
            if (request.isForMainFrame) {
                val url = request.url.toString()
                if (!stale(view) && host.leavesWindow(target, request)) return true
                val from = target.upgradedFrom
                if (!stale(view) && from != null && request.isRedirect && HttpsFirst.isDowngrade(url, from)) {
                    view.stopLoading()
                    host.httpsUnavailable(target, from)
                    return true
                }
                val get = request.method.equals("GET", ignoreCase = true)
                // A redirect that needs no upgrade keeps the attempt it belongs to.
                val next = if (stale(view) || !get || (request.isRedirect && HttpsFirst.upgrade(url) == null)) url else host.preferHttps(target, url)
                target.navigating = next
                // Another site's settings (desktop mode, scripts, images) must be in place before it loads.
                if (!stale(view) && (host.applySiteSettings(target, view, next) || next != url) && get) {
                    view.loadUrl(next)
                    return true
                }
            }
            return false
        }
        // Downloads from links to page-made files arrive as blob: / data: navigations; WebView turns them
        // into downloads, and blocks top-level data: pages itself.
        if (request.url.scheme?.lowercase() in setOf("blob", "data")) return false
        if (request.isForMainFrame && request.hasGesture()) host.pages.externalLink(request.url, target)
        return true
    }
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (stale(view) || host.stopsAtStart(target, view, url)) return
        if (BrowserInput.isWebUrl(url)) document = url
        target.documentVersion++
        target.error = null
        // Back / forward reach a site without asking first; load it again with its settings.
        if (host.applySiteSettings(target, view, url)) view.post { if (target.view === view) view.reload() }
        if (BrowserInput.isWebUrl(url)) {
            target.url = url
            target.waiting = false
            if (host.tab === target) {
                // A page still starting after the user went home stays behind the start page.
                if (view.parent == null && host.showsPage(target)) host.attachPage(view)
                host.updateChrome()
            }
            host.schedulePersist()
        }
    }
    override fun onPageFinished(view: WebView, url: String) {
        if (stale(view)) return
        if (BrowserInput.isWebUrl(url)) {
            target.url = url; target.title = view.title?.takeIf { it.isNotBlank() } ?: url
            target.session.visit(host.store, target.title, url); host.schedulePersist()
        }
        if (host.tab === target) host.updateChrome()
        host.pages.pageReady(view)
        host.hideAds(target, view, again = true)
        host.pageFinished(target, view)
        target.restoreScroll?.let { position ->
            target.restoreScroll = null
            val document = target.documentVersion
            view.post { if (target.view === view && target.documentVersion == document) view.scrollTo(position.first, position.second) }
        }
    }
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (stale(view)) return
        if (BrowserInput.isWebUrl(url)) {
            target.url = url
            if (host.tab === target) host.updateChrome()
            host.schedulePersist()
        }
    }
    override fun onPageCommitVisible(view: WebView, url: String) {
        if (stale(view)) return
        target.upgradedFrom = null
        host.pages.pageReady(view)
        host.hideAds(target, view)
    }
    /** Background tabs keep their error, shown when the tab is next selected. */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || stale(view)) return
        // A name that does not resolve fails over plain HTTP too, so only other failures are put down to HTTPS.
        val attempted = target.upgradedFrom
        if (attempted != null && !host.network.holding() && error.errorCode != ERROR_HOST_LOOKUP) {
            host.httpsUnavailable(target, attempted)
            return
        }
        val (title, message) = if (host.network.holding()) R.string.error_held_title to R.string.error_held_message
        else when (error.errorCode) {
            ERROR_HOST_LOOKUP -> R.string.error_host_title to R.string.error_host_message
            ERROR_TIMEOUT, ERROR_CONNECT -> R.string.error_timeout_title to R.string.error_timeout_message
            ERROR_PROXY_AUTHENTICATION -> R.string.error_proxy_title to R.string.error_proxy_message
            ERROR_TOO_MANY_REQUESTS, ERROR_REDIRECT_LOOP -> R.string.error_redirect_title to R.string.error_redirect_message
            else -> R.string.error_generic_title to R.string.error_generic_message
        }
        host.showPageError(target, PageError(host.str(title), host.str(message)))
    }
    /**
     * Certificate errors stop the page. The user may continue to that host for the rest of the session;
     * the address bar then marks the connection as not secure. Failing sub-resources are only blocked.
     */
    @SuppressLint("WebViewClientOnReceivedSslError") // Proceeds only for hosts the user explicitly allowed after a warning.
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val site = error.url.toUri().host ?: error.url
        if (site in target.session.certificateExceptions) { handler.proceed(); return }
        handler.cancel()
        val mainFrame = error.url == target.navigating || error.url == view.url || error.url == view.originalUrl || error.url == target.url
        if (stale(view) || !mainFrame) return
        // A site tried over HTTPS only because of HTTPS-First may simply not have a certificate.
        target.upgradedFrom?.let { host.httpsUnavailable(target, it); return }
        host.showPageError(target, PageError(host.str(R.string.error_ssl_title), host.str(R.string.error_ssl_message, site),
            host.str(R.string.error_ssl_proceed) to {
                host.confirm(host.str(R.string.error_ssl_proceed_confirm, site), host.str(R.string.error_ssl_proceed), destructive = true) {
                    target.session.certificateExceptions.add(site)
                    if (BrowserInput.isWebUrl(error.url)) target.url = error.url
                    host.retryPage(target)
                }
            }))
    }
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, hostName: String, realm: String?) {
        if (stale(view)) { handler.cancel(); return }
        host.pages.httpAuth(target, handler, hostName, realm)
    }
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.pageGone(view)
        if (target.view === view) target.view = null
        target.session.release(view)
        if (host.tab === target) host.showPageError(target, PageError(host.str(R.string.error_crash_title), ""))
        return true
    }
}

/** Page UI requests: windows, uploads, full screen, permissions, titles and icons. */
internal class PageChromeClient(private val host: PageHost, private val target: BrowserTab) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, value: Int) { if (host.tab === target && target.view === view) host.updateProgress() }
    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank() && target.view === view) { target.title = title; host.schedulePersist() }
    }
    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        if (target.view !== view) return
        target.favicon = icon
        // Private sites leave no icon behind.
        if (!target.isPrivate && BrowserInput.isWebUrl(target.url)) host.store.saveIcon(target.url, icon)
    }
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (target !in host.tabs || !host.pages.popupAllowed(target, view, isUserGesture)) return false
        return host.openWindow(target, resultMsg)
    }
    override fun onCloseWindow(window: WebView) = host.closeWindow(window)
    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        host.uploads.choose(target, callback, params)
        return true
    }
    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult) =
        host.pages.scriptDialog(target, view, url, message, null, PageInteractions.ScriptDialog.ALERT, result)
    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult) =
        host.pages.scriptDialog(target, view, url, message, null, PageInteractions.ScriptDialog.CONFIRM, result)
    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult) =
        host.pages.scriptDialog(target, view, url, message, defaultValue.orEmpty(), PageInteractions.ScriptDialog.PROMPT, result)
    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult) =
        host.pages.scriptDialog(target, view, url, message, null, PageInteractions.ScriptDialog.LEAVE, result)
    override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showFullScreen(view, callback)
    override fun onHideCustomView() = host.hideFullScreen()
    override fun onPermissionRequest(request: PermissionRequest) = host.permissions.media(request, target)
    override fun onPermissionRequestCanceled(request: PermissionRequest) = host.permissions.cancel(request)
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
        host.permissions.location(origin, callback, target)
}
