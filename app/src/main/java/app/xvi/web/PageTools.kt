package app.xvi.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume

/** Menu actions on the current page: reader view, source, translation, printing, shortcuts and saving. */
internal class PageTools(private val host: BrowserActivity) {
    private val ui get() = host.ui
    private var reader: View? = null
    private var readerWeb: WebView? = null
    private var readerSession: BrowserSession? = null
    val readerVisible get() = reader != null

    /** Shows the page's main text in a plain, readable layout. Scripts never run in the reader. */
    fun openReader(target: BrowserTab) {
        val web = target.view ?: return
        host.lifecycleScope.launch {
            val html = web.readString("document.documentElement ? document.documentElement.outerHTML : ''")
            if (host.tab !== target || target.view !== web) return@launch
            val article = html?.let { withContext(Dispatchers.Default) { runCatching { ReaderExtractor.extract(it, target.url) }.getOrNull() } }
            if (article == null) { host.toast(R.string.reader_unavailable); return@launch }
            showReader(target, article)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showReader(target: BrowserTab, article: ReaderExtractor.Article) {
        closeReader()
        val palette = ui.palette
        val panel = ui.column().apply { setBackgroundColor(palette.surface); isClickable = true }
        val header = ui.row().apply { setPadding(ui.dp(4), 0, ui.dp(8), 0); setBackgroundColor(palette.base) }
        header.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.reader_close)) { closeReader() }, ui.tappable())
        header.addView(ui.medium(ui.singleLine(ui.label(ui.str(R.string.reader_title), 16f))), ui.weighted())
        panel.addView(header, ui.bar(48))
        val session = target.session
        val web = session.createWebView(host).apply {
            setBackgroundColor(palette.surface)
            settings.javaScriptEnabled = false
            settings.blockLocalAccess()
            settings.textZoom = host.prefs.textZoom
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = ReaderClient(target)
        }
        web.loadDataWithBaseURL(target.url, ReaderExtractor.page(article, palette.dark, BrowserInput.display(target.url)), "text/html", "utf-8", null)
        panel.addView(web, ui.stretch())
        host.pageHost.addView(panel, ui.full())
        reader = panel; readerWeb = web; readerSession = session
        host.updateChrome()
    }

    /** Links in the reader open in the tab; a crashed reader simply closes. */
    @SuppressLint("MissingOnRenderProcessGone") // Implemented below; the check misses Kotlin's non-null override.
    private inner class ReaderClient(private val target: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (BrowserInput.isWebUrl(url) && target in host.tabs) { closeReader(); host.load(target, url) }
            return true
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (readerWeb === view) { readerWeb = null; reader?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; reader = null }
            readerSession?.let { runCatching { it.release(view) } }
            return true
        }
    }

    fun closeReader() {
        val panel = reader ?: return
        reader = null
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        readerWeb?.let { web -> readerSession?.let { session -> runCatching { session.release(web) } } }
        readerWeb = null; readerSession = null
        host.updateChrome()
    }

    /** The page as the server sent it, fetched again through the browser's network settings. */
    fun viewSource(target: BrowserTab) {
        val url = target.url
        if (!BrowserInput.isWebUrl(url)) return
        val cookie = runCatching { target.session.cookies().getCookie(url) }.getOrNull()
        val agent = target.view?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(host)
        host.toast(R.string.source_loading)
        host.network.prepare { _ ->
            val proxy = host.network.proxy()
            host.lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val client = BrowserHttp.through(proxy)
                        val request = Request.Builder().url(url).header("User-Agent", agent).apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }.build()
                        client.newCall(request).execute().use { response ->
                            val source = response.body.source()
                            source.request(MAX_SOURCE)
                            val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_SOURCE))
                            val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            String(bytes, charset) to (bytes.size.toLong() >= MAX_SOURCE)
                        }
                    }.getOrNull()
                }
                if (host.isDestroyed) return@launch
                if (text == null) { host.toast(R.string.source_failed); return@launch }
                showSource(target, url, text.first, text.second)
            }
        }
    }

    private fun showSource(target: BrowserTab, url: String, source: String, truncated: Boolean) {
        val sheet = host.sheet(ui.str(R.string.source_title), target)
        sheet.add(ui.singleLine(ui.label(if (truncated) ui.str(R.string.source_truncated, url) else url, 12f, ui.palette.muted)))
        val text = ui.label(source, 12f).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
        }
        sheet.add(ScrollView(host).apply { addView(text) }, ui.stretch())
        val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        actions.addView(ui.textAction(ui.str(R.string.source_copy)) { host.copy(source, target.isPrivate) }, ui.weighted())
        actions.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, ui.weighted().apply { leftMargin = ui.dp(8) })
        sheet.add(actions)
        sheet.show()
    }

    /** Opens the page through the translation service, in the same tab. */
    fun translate(target: BrowserTab) {
        if (!BrowserInput.isWebUrl(target.url)) return
        host.load(target, "https://translate.google.com/translate?sl=auto&tl=zh-CN&hl=zh-CN&u=" + URLEncoder.encode(target.url, "UTF-8"))
    }

    /** System print dialog, which can also save the page as a PDF. */
    fun print(target: BrowserTab) {
        val web = target.view ?: return
        val name = target.title.ifBlank { BrowserInput.display(target.url) }.take(80)
        runCatching {
            host.getSystemService(PrintManager::class.java).print(name, web.createPrintDocumentAdapter(name),
                PrintAttributes.Builder().build())
        }.onFailure { host.toast(R.string.print_failed) }
    }

    /** Pins a home screen shortcut that opens the page in the browser. */
    fun addToHome(target: BrowserTab) {
        val url = target.url
        if (!BrowserInput.isWebUrl(url) || target.isPrivate) return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(host)) { host.toast(R.string.shortcut_unsupported); return }
        val title = target.title.ifBlank { BrowserInput.display(url) }
        val icon = target.favicon?.let { IconCompat.createWithAdaptiveBitmap(adaptive(it)) }
            ?: IconCompat.createWithResource(host, R.mipmap.ic_launcher)
        val shortcut = ShortcutInfoCompat.Builder(host, "page-" + url.hashCode().toUInt().toString(16))
            .setShortLabel(title.take(24)).setLongLabel(title.take(60)).setIcon(icon)
            .setIntent(Intent(Intent.ACTION_VIEW, url.toUri(), host, BrowserActivity::class.java))
            .build()
        if (!ShortcutManagerCompat.requestPinShortcut(host, shortcut, null)) host.toast(R.string.shortcut_unsupported)
    }

    /** Site icon centred on the browser's surface color, sized for an adaptive launcher icon. */
    private fun adaptive(icon: Bitmap): Bitmap {
        val size = 216
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ui.palette.badgeSurface)
        val inner = 96
        val scaled = icon.scale(inner, inner)
        canvas.drawBitmap(scaled, ((size - inner) / 2).toFloat(), ((size - inner) / 2).toFloat(), null)
        return bitmap
    }

    fun savePage(target: BrowserTab) = host.downloads.savePage(target)

    private companion object {
        const val MAX_SOURCE = 512L * 1024
    }
}

/** Raw JSON result of [script] in the page; null when it returned nothing or did not answer in time. */
internal suspend fun WebView.evaluate(script: String, timeoutMs: Long = 10_000): String? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { continuation ->
        try {
            evaluateJavascript(script) { raw -> if (continuation.isActive) continuation.resume(raw?.takeIf { it != "null" }) }
        } catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
    }
}

/** String result of [script] in the page, or null when it gave something else or did not answer. */
internal suspend fun WebView.readString(script: String, timeoutMs: Long = 10_000): String? =
    evaluate(script, timeoutMs)?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }

/** No device files, no content providers, and no plain HTTP inside an HTTPS page. */
internal fun WebSettings.blockLocalAccess() {
    allowFileAccess = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}
