package app.xvi.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
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

/** Navigation callbacks for one tab's WebView. Stale callbacks from a replaced view are ignored. */
internal class PageClient(private val host: BrowserActivity, private val target: BrowserTab) : WebViewClient() {
    private fun stale(view: WebView) = target.view !== view || target !in host.tabs

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (BrowserInput.isWebUrl(request.url.toString())) return false
        if (request.isForMainFrame && request.hasGesture()) host.externalLink(request.url, target)
        return true
    }
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (stale(view)) return
        target.documentVersion++
        if (BrowserInput.isWebUrl(url)) {
            target.url = url
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
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || stale(view) || host.tab !== target) return
        val (title, message) = when (error.errorCode) {
            ERROR_HOST_LOOKUP -> R.string.error_host_title to R.string.error_host_message
            ERROR_TIMEOUT, ERROR_CONNECT -> R.string.error_timeout_title to R.string.error_timeout_message
            ERROR_PROXY_AUTHENTICATION -> R.string.error_proxy_title to R.string.error_proxy_message
            ERROR_TOO_MANY_REQUESTS, ERROR_REDIRECT_LOOP -> R.string.error_redirect_title to R.string.error_redirect_message
            else -> R.string.error_generic_title to R.string.error_generic_message
        }
        host.showPageError(host.str(title), host.str(message))
    }
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        if (!stale(view) && host.tab === target) {
            val site = Uri.parse(error.url).host ?: error.url
            host.showPageError(host.str(R.string.error_ssl_title), host.str(R.string.error_ssl_message, site))
        }
    }
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (host.findWebView === view) host.hideFindInPage()
        if (target.view === view) target.view = null
        target.session.release(view)
        if (host.tab === target) host.showPageError(host.str(R.string.error_crash_title), host.str(R.string.error_crash_message))
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
        if (!isUserGesture || !host.tabManager.canAdd || target !in host.tabs) return false
        val child = host.addTab(activate = false, session = target.session) ?: return false
        val childView = host.createWebView(child).also { child.view = it }
        child.url = "about:blank"
        (resultMsg.obj as WebView.WebViewTransport).webView = childView
        resultMsg.sendToTarget()
        return true
    }
    override fun onCloseWindow(window: WebView) {
        host.tabs.indexOfFirst { it.view === window }.takeIf { it >= 0 }?.let { host.closeTab(it) }
    }
    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        host.fileCallback?.onReceiveValue(null)
        host.fileCallback = callback
        try { host.fileChooser.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE)) }
        catch (_: ActivityNotFoundException) { host.fileCallback?.onReceiveValue(null); host.fileCallback = null; host.toast(R.string.upload_unavailable) }
        return true
    }
    override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showFullScreen(view, callback)
    override fun onHideCustomView() = host.hideFullScreen()
    override fun onPermissionRequest(request: PermissionRequest) = host.websitePermission(request, target)
    override fun onPermissionRequestCanceled(request: PermissionRequest) = host.cancelWebsitePermission(request)
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        val site = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
        host.browserDialog(target).setTitle(host.str(R.string.permission_location_title, site))
            .setMessage(R.string.permission_once_message)
            .setPositiveButton(R.string.permission_allow_once) { _, _ ->
                host.askRuntimePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    val allowed = host.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    callback.invoke(origin, allowed, false)
                }
            }.setNegativeButton(R.string.permission_deny) { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }.show()
    }
}
