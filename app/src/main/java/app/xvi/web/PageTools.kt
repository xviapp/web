package app.xvi.web

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import android.provider.Settings
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
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var readerArticle: ReaderExtractor.Article? = null
    private var readerTarget: BrowserTab? = null
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
        header.addView(ui.iconAction(R.drawable.ic_text_size_24, ui.str(R.string.reader_style)) { readerStyle(target) }, ui.tappable())
        panel.addView(header, ui.bar(48))
        val session = target.session
        val web = session.createWebView(host).apply {
            settings.javaScriptEnabled = false
            settings.blockLocalAccess()
            settings.textZoom = host.prefs.textZoom
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = ReaderClient(target)
        }
        panel.addView(web, ui.stretch())
        host.pageArea.addView(panel, ui.full())
        reader = panel; readerWeb = web; readerSession = session; readerArticle = article; readerTarget = target
        renderReader()
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

    /** Lays out the article again in the current [BrowserPrefs.readerStyle]. */
    private fun renderReader() {
        val web = readerWeb ?: return
        val article = readerArticle ?: return
        val target = readerTarget ?: return
        val style = host.prefs.readerStyle
        // The page's own colour behind it, so a reload never flashes another one.
        web.setBackgroundColor(ReaderExtractor.colors(style.tone, ui.palette.dark).background.toColorInt())
        web.loadDataWithBaseURL(target.url, ReaderExtractor.page(article, ui.palette.dark, BrowserInput.display(target.url), style), "text/html", "utf-8", null)
    }

    /** Colours, typeface and line spacing for the reader; each choice applies at once and is remembered. */
    private fun readerStyle(target: BrowserTab) {
        val sheet = host.sheet(ui.str(R.string.reader_style), target)
        fun <T> choice(icon: Int, title: Int, options: List<T>, label: (T) -> String, current: () -> T, apply: (T) -> ReaderExtractor.Style) {
            lateinit var row: MenuRow
            row = ui.menuRow(icon, ui.str(title), label(current())) {
                host.browserDialog(target).setTitle(title)
                    .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current())) { dialog, index ->
                        host.prefs.readerStyle = apply(options[index])
                        row.setSubtitle(label(current()))
                        renderReader()
                        dialog.dismiss()
                    }.show()
            }
            sheet.add(row.view)
        }
        val prefs = host.prefs
        choice(R.drawable.ic_palette_24, R.string.reader_tone, ReaderExtractor.Tone.entries, { ui.str(TONE_LABELS.getValue(it)) },
            { prefs.readerStyle.tone }) { prefs.readerStyle.copy(tone = it) }
        choice(R.drawable.ic_text_size_24, R.string.reader_font, listOf(false, true), { ui.str(if (it) R.string.reader_font_serif else R.string.reader_font_sans) },
            { prefs.readerStyle.serif }) { prefs.readerStyle.copy(serif = it) }
        choice(R.drawable.ic_article_24, R.string.reader_spacing, ReaderExtractor.Spacing.entries, { ui.str(SPACING_LABELS.getValue(it)) },
            { prefs.readerStyle.spacing }) { prefs.readerStyle.copy(spacing = it) }
        sheet.show()
    }

    fun closeReader() {
        val panel = reader ?: return
        reader = null
        readerArticle = null; readerTarget = null
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
    fun addToHome(target: BrowserTab) = pin(target, "page-" + target.url.hashCode().toUInt().toString(16)) { url, _ ->
        Intent(Intent.ACTION_VIEW, url.toUri(), host, BrowserActivity::class.java)
    }

    /** Pins a home screen icon that opens the page's site in a window of its own, without browser controls. */
    fun installApp(target: BrowserTab) {
        val id = WebAppActivity.shortcutId(target.url)
        // An app removed earlier keeps its disabled shortcut, which cannot be pinned again until enabled.
        runCatching { host.getSystemService(ShortcutManager::class.java).enableShortcuts(listOf(id)) }
        pin(target, id) { url, title -> WebAppActivity.launchIntent(host, url, title) }
    }

    /** Shows the page's site in a window of its own right away, without adding anything to the home screen. */
    fun openInWindow(target: BrowserTab) {
        val url = target.url
        if (!BrowserInput.isWebUrl(url) || target.isPrivate) return
        runCatching { host.startActivity(WebAppActivity.launchIntent(host, url, target.title.ifBlank { BrowserInput.display(url) })) }
    }

    /** Looks for the site's sharpest icon first, so the home screen does not show a blown-up tab icon. */
    private fun pin(target: BrowserTab, id: String, intent: (url: String, title: String) -> Intent) {
        val url = target.url
        if (!BrowserInput.isWebUrl(url) || target.isPrivate) return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(host)) { host.toast(R.string.shortcut_unsupported); return }
        val title = target.title.ifBlank { BrowserInput.display(url) }
        val web = target.view
        host.showSnackbar(ui.str(R.string.shortcut_preparing))
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            val proxy = host.network.proxy()
            host.lifecycleScope.launch {
                val found = web?.takeIf { target.view === it }?.let {
                    SiteIcons.best(it, url, { address -> runCatching { target.session.cookies().getCookie(address) }.getOrNull() },
                        proxy, it.settings.userAgentString)
                }
                if (host.isDestroyed) return@launch
                val icon = when {
                    found != null -> IconCompat.createWithAdaptiveBitmap(if (found.second) found.first.scale(LAUNCHER, LAUNCHER) else adaptive(found.first))
                    target.favicon != null -> IconCompat.createWithAdaptiveBitmap(adaptive(target.favicon!!))
                    else -> IconCompat.createWithResource(host, R.mipmap.ic_launcher)
                }
                requestPin(ShortcutInfoCompat.Builder(host, id)
                    .setShortLabel(title.take(24)).setLongLabel(title.take(60)).setIcon(icon)
                    .setIntent(intent(url, title))
                    .build())
            }
        }
    }

    /**
     * Asks the launcher to pin [shortcut] and says how it went. Many launchers refuse silently while the
     * app lacks their "create home screen shortcuts" permission, so when no confirmation arrives soon
     * after the user is back, the snackbar points at the app's system settings.
     */
    private fun requestPin(shortcut: ShortcutInfoCompat) {
        val action = "${host.packageName}.SHORTCUT_PINNED"
        var pinned = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (pinned) return
                pinned = true
                host.showSnackbar(ui.str(R.string.shortcut_added))
            }
        }
        ContextCompat.registerReceiver(host, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val callback = PendingIntent.getBroadcast(host, shortcut.id.hashCode(), Intent(action).setPackage(host.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val requested = runCatching { ShortcutManagerCompat.requestPinShortcut(host, shortcut, callback.intentSender) }.getOrDefault(false)
        if (!requested) {
            runCatching { host.unregisterReceiver(receiver) }
            host.toast(R.string.shortcut_unsupported)
            return
        }
        host.lifecycleScope.launch {
            try {
                // A confirmation dialog pauses the browser; the answer can only be judged once it is gone.
                delay(PIN_SETTLE_MS)
                host.withResumed { }
                delay(PIN_SETTLE_MS)
                if (!pinned) host.showSnackbar(ui.str(R.string.shortcut_maybe_blocked), ui.str(R.string.shortcut_open_settings)) {
                    runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${host.packageName}".toUri())) }
                }
                // A late confirmation still reports success.
                withTimeoutOrNull(PIN_LATE_MS) { while (!pinned) delay(500) }
            } finally {
                runCatching { host.unregisterReceiver(receiver) }
            }
        }
    }

    /**
     * Site icon centred on the browser's surface color, inside the part of an adaptive icon no launcher
     * mask crops. Small tab icons are enlarged by whole multiples without smoothing, so they stay crisp
     * pixel art instead of turning into a blur.
     */
    private fun adaptive(icon: Bitmap): Bitmap {
        val bitmap = createBitmap(LAUNCHER, LAUNCHER)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ui.palette.badgeSurface)
        val side = maxOf(icon.width, icon.height).coerceAtLeast(1)
        val smooth = side >= SiteIcons.MIN_SIZE
        val scale = if (smooth) SAFE_ZONE.toFloat() / side else (SAFE_ZONE / side).coerceAtLeast(1).toFloat()
        val width = (icon.width * scale).toInt().coerceAtLeast(1)
        val height = (icon.height * scale).toInt().coerceAtLeast(1)
        val scaled = icon.scale(width, height, filter = smooth)
        canvas.drawBitmap(scaled, ((LAUNCHER - width) / 2).toFloat(), ((LAUNCHER - height) / 2).toFloat(), null)
        return bitmap
    }

    fun savePage(target: BrowserTab) = host.downloads.savePage(target)

    private companion object {
        val TONE_LABELS = mapOf(ReaderExtractor.Tone.AUTO to R.string.reader_tone_auto, ReaderExtractor.Tone.LIGHT to R.string.reader_tone_light,
            ReaderExtractor.Tone.SEPIA to R.string.reader_tone_sepia, ReaderExtractor.Tone.DARK to R.string.reader_tone_dark)
        val SPACING_LABELS = mapOf(ReaderExtractor.Spacing.COMPACT to R.string.reader_spacing_compact,
            ReaderExtractor.Spacing.NORMAL to R.string.reader_spacing_normal, ReaderExtractor.Spacing.RELAXED to R.string.reader_spacing_relaxed)
        const val MAX_SOURCE = 512L * 1024
        const val LAUNCHER = SiteIcons.LAUNCHER_SIZE
        /** The middle 66 of an adaptive icon's 108 dp, which every launcher mask leaves whole. */
        const val SAFE_ZONE = LAUNCHER * 66 / 108
        const val PIN_SETTLE_MS = 1_500L
        const val PIN_LATE_MS = 60_000L
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
