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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri

/** Navigation callbacks for one tab's WebView. Stale callbacks from a replaced view are ignored. */
@SuppressLint("MissingOnRenderProcessGone") // Implemented below; the check misses Kotlin's non-null override.
internal class PageClient(private val host: BrowserActivity, private val target: BrowserTab) : WebViewClient() {
    private fun stale(view: WebView) = target.view !== view || target !in host.tabs

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (BrowserInput.isWebUrl(request.url.toString())) {
            if (request.isForMainFrame) {
                val url = request.url.toString()
                target.navigating = url
                // Another site's settings (desktop mode, scripts, images) must be in place before it loads.
                if (!stale(view) && host.applySiteSettings(target, view, url) && request.method.equals("GET", ignoreCase = true)) {
                    view.loadUrl(url)
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
        if (stale(view)) return
        target.documentVersion++
        target.error = null
        // Back / forward reach a site without asking first; load it again with its settings.
        if (host.applySiteSettings(target, view, url)) view.post { if (target.view === view) view.reload() }
        if (BrowserInput.isWebUrl(url)) {
            target.url = url
            target.waiting = false
            if (host.tab === target) {
                if (view.parent == null) { host.pageHost.removeAllViews(); host.pageHost.addView(view, FrameLayout.LayoutParams(-1, -1)) }
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
        if (!stale(view)) host.pages.pageReady(view)
    }
    /** Background tabs keep their error, shown when the tab is next selected. */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || stale(view)) return
        val (title, message) = when (error.errorCode) {
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
        if (host.findBar.web === view) host.hideFindInPage()
        if (target.view === view) target.view = null
        target.session.release(view)
        if (host.tab === target) host.showPageError(target, PageError(host.str(R.string.error_crash_title), ""))
        return true
    }
}

/** Page UI requests: windows, uploads, full screen, permissions, titles and icons. */
internal class PageChromeClient(private val host: BrowserActivity, private val target: BrowserTab) : WebChromeClient() {
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
        if (!host.tabManager.canAdd || target !in host.tabs || !host.pages.popupAllowed(target, view, isUserGesture)) return false
        val child = host.addTab(activate = false, session = target.session) ?: return false
        val childView = host.createWebView(child).also { child.view = it }
        child.url = "about:blank"
        (resultMsg.obj as WebView.WebViewTransport).webView = childView
        resultMsg.sendToTarget()
        // New windows open in front, like a normal link click.
        host.selectTab(child)
        return true
    }
    override fun onCloseWindow(window: WebView) {
        host.tabs.indexOfFirst { it.view === window }.takeIf { it >= 0 }?.let { host.closeTab(it) }
    }
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
