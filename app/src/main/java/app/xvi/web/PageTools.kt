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
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONTokener
import org.jsoup.nodes.Element
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
    private var readerLabel: android.widget.TextView? = null
    /** What the rules found for the reader's page, to go back to when the AI fails. */
    private var ruleArticle: ReaderExtractor.Article? = null
    /** The AI choosing the main text for the reader, while it works. */
    private var aiJob: Job? = null
    /** AI choices by page address and outline, so reading the same page again costs nothing. */
    private val aiChoices = object : LinkedHashMap<String, AiReader.Choice>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiReader.Choice>) = size > 20
    }
    val readerVisible get() = reader != null

    /** Shows the page's main text in a plain, readable layout. Scripts never run in the reader. */
    fun openReader(target: BrowserTab) {
        val web = target.view ?: return
        host.lifecycleScope.launch {
            val html = web.readString(ReaderExtractor.PAGE_HTML)
            if (host.tab !== target || target.view !== web) return@launch
            val article = html?.let { withContext(Dispatchers.Default) { runCatching { ReaderExtractor.extract(it, target.url) }.getOrNull() } }
            if (article != null) { showReader(target, article); return@launch }
            if (host.aiConfig.enabled) host.showSnackbar(ui.str(R.string.reader_unavailable), ui.str(R.string.reader_ai_offer)) { aiReader(target) }
            else host.toast(R.string.reader_unavailable)
        }
    }

    /**
     * Reader view with the AI choosing which of the page's blocks are the main text; the text itself
     * comes from the page unchanged. A reader already open for the page stays if the AI fails.
     */
    fun aiReader(target: BrowserTab) {
        val web = target.view ?: return
        val config = host.aiConfig
        aiJob?.cancel()
        aiJob = host.lifecycleScope.launch {
            val base = config.baseUrl
            val key = config.apiKey
            if (!config.enabled || key == null) { host.toast(R.string.ai_needs_key); return@launch }
            val document = target.documentVersion
            fun current() = host.tab === target && target.view === web && target.documentVersion == document
            val html = web.readString(ReaderExtractor.PAGE_HTML)
            if (!current()) return@launch
            val outline = html?.let { withContext(Dispatchers.Default) { runCatching { AiReader.outline(it, target.url) }.getOrNull() } }
            if (outline == null) { host.toast(R.string.reader_unavailable); return@launch }
            val cacheKey = target.url + "\n" + outline.listing.hashCode()
            val cached = if (target.isPrivate) null else aiChoices[cacheKey]
            if (cached == null) {
                if (!AiSendConsent(host).confirm(target, base)) return@launch
                // The dialog can outlive the page or the settings it named.
                if (!config.enabled || config.baseUrl != base || config.apiKey != key || !current()) { host.toast(R.string.ai_send_changed); return@launch }
            }
            showReader(target, ReaderExtractor.Article(outline.title, Element("p").addClass("site").text(ui.str(R.string.reader_ai_working)).outerHtml()), ai = true)
            val choice = cached ?: try {
                val proxy = host.aiProxy(base)
                if (!config.enabled || config.baseUrl != base || config.apiKey != key || !current()) { host.toast(R.string.ai_send_changed); return@launch }
                val answer = StringBuilder()
                DeepSeek.chat(base, key, config.model, listOf(DeepSeek.Message(DeepSeek.Message.SYSTEM, AiReader.SYSTEM),
                    DeepSeek.Message(DeepSeek.Message.USER, AiReader.request(outline))), proxy = proxy) { answer.append(it) }
                AiReader.parse(answer.toString(), outline.blocks.size)
            } catch (failure: DeepSeek.Failure) {
                aiFailed(target, host.assistant.problem(failure))
                return@launch
            }
            val article = choice?.let { withContext(Dispatchers.Default) { AiReader.article(outline, it) } }
            if (choice == null || article == null) { aiFailed(target, ui.str(R.string.reader_ai_failed)); return@launch }
            if (!target.isPrivate) aiChoices[cacheKey] = choice
            if (readerTarget !== target) return@launch
            readerArticle = article
            renderReader()
        }
    }

    /** Puts back the reader the AI was asked to improve on, or closes the one it opened, and says why. */
    private fun aiFailed(target: BrowserTab, message: String) {
        if (readerTarget === target) {
            val previous = ruleArticle
            if (previous != null) { readerArticle = previous; readerLabel?.text = ui.str(R.string.reader_title); renderReader() } else closeReader()
        }
        host.toast(message)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showReader(target: BrowserTab, article: ReaderExtractor.Article, ai: Boolean = false) {
        if (!ai) ruleArticle = article
        // Already open for this page: only what it shows changes.
        if (readerTarget === target && readerWeb != null) {
            readerArticle = article
            readerLabel?.text = ui.str(if (ai) R.string.reader_ai_title else R.string.reader_title)
            renderReader()
            return
        }
        removeReader()
        if (!ai) ruleArticle = article
        val palette = ui.palette
        val panel = FrameLayout(host).apply { setBackgroundColor(palette.surface); isClickable = true }
        val header = ui.row().apply {
            setPadding(ui.dp(4), 0, ui.dp(8), 0)
            setBackgroundColor(palette.base)
            elevation = ui.dp(2).toFloat()
        }
        header.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.reader_close)) { closeReader() }, ui.tappable())
        val label = ui.medium(ui.singleLine(ui.label(ui.str(if (ai) R.string.reader_ai_title else R.string.reader_title), 16f)))
        header.addView(label, ui.weighted())
        if (host.aiConfig.enabled) header.addView(ui.iconAction(R.drawable.ic_ai_24, ui.str(R.string.reader_ai)) { aiReader(target) }, ui.tappable())
        header.addView(ui.iconAction(R.drawable.ic_text_size_24, ui.str(R.string.reader_style)) { readerStyle(target) }, ui.tappable())
        var headerCollapsed = false
        fun setHeaderCollapsed(collapsed: Boolean) {
            if (collapsed == headerCollapsed) return
            headerCollapsed = collapsed
            header.animate().cancel()
            header.animate().translationY(if (collapsed) -ui.dp(48).toFloat() else 0f).setDuration(160).start()
            host.setReaderChromeCollapsed(collapsed)
        }
        val session = target.session
        val web = session.createWebView(host).apply {
            settings.javaScriptEnabled = false
            settings.blockLocalAccess()
            settings.textZoom = host.prefs.textZoom
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = ReaderClient(target)
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val dy = scrollY - oldScrollY
                if (scrollY <= ui.dp(24) || dy < -30) setHeaderCollapsed(false)
                else if (dy > 30 && scrollY > ui.dp(48)) setHeaderCollapsed(true)
            }
        }
        panel.addView(web, ui.full())
        panel.addView(header, FrameLayout.LayoutParams(ui.fill(), ui.dp(48), Gravity.TOP))
        host.pageArea.addView(panel, ui.full())
        reader = panel; readerWeb = web; readerSession = session; readerArticle = article; readerTarget = target; readerLabel = label
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

    /** Colours, text size, typeface and line spacing for the reader; every tap updates the open article live. */
    private fun readerStyle(target: BrowserTab) {
        val sheet = host.sheet(ui.str(R.string.reader_style), target)
        val prefs = host.prefs
        val palette = ui.palette

        // 1. Background tone swatches
        sheet.add(ui.sectionTitle(ui.str(R.string.reader_tone)))
        val toneRow = ui.row()
        val toneChips = mutableMapOf<ReaderExtractor.Tone, TextView>()
        fun syncTones() {
            val active = prefs.readerStyle.tone
            toneChips.forEach { (tone, chip) ->
                val colors = ReaderExtractor.colors(tone, palette.dark)
                val selected = tone == active
                chip.isSelected = selected
                chip.setTextColor(colors.ink.toColorInt())
                chip.background = ui.rounded(colors.background.toColorInt(), 14).apply {
                    setStroke(ui.dp(if (selected) 2 else 1), if (selected) palette.accent else ColorUtils.setAlphaComponent(palette.muted, 96))
                }
            }
        }
        ReaderExtractor.Tone.entries.forEachIndexed { index, tone ->
            val chip = ui.asButton(ui.medium(ui.singleLine(ui.label(ui.str(TONE_LABELS.getValue(tone)), 13f)))).apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(48)
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                setOnClickListener {
                    if (prefs.readerStyle.tone == tone) return@setOnClickListener
                    host.haptic(this)
                    prefs.readerStyle = prefs.readerStyle.copy(tone = tone)
                    syncTones()
                    renderReader()
                }
            }
            toneChips[tone] = chip
            toneRow.addView(chip, ui.weighted().apply { if (index > 0) marginStart = ui.dp(8) })
        }
        syncTones()
        sheet.add(toneRow)

        // 2. Live text-size stepper
        sheet.add(ui.sectionTitle(ui.str(R.string.settings_text_size)))
        val zoomValue = ui.medium(ui.label("", 15f)).apply { gravity = Gravity.CENTER }
        lateinit var smaller: TextView
        lateinit var larger: TextView
        fun syncZoom() {
            val z = prefs.textZoom
            zoomValue.text = ui.str(R.string.site_text_size_value, z)
            smaller.isEnabled = z > MIN_READER_ZOOM
            smaller.alpha = if (smaller.isEnabled) 1f else 0.4f
            larger.isEnabled = z < MAX_READER_ZOOM
            larger.alpha = if (larger.isEnabled) 1f else 0.4f
        }
        fun stepZoom(delta: Int) {
            val next = (prefs.textZoom + delta).coerceIn(MIN_READER_ZOOM, MAX_READER_ZOOM)
            if (next == prefs.textZoom) return
            host.haptic()
            prefs.textZoom = next
            readerWeb?.settings?.textZoom = next
            host.preferencesChanged()
            syncZoom()
        }
        smaller = ui.textAction("A−") { stepZoom(-READER_ZOOM_STEP) }.apply { describe(ui.str(R.string.reader_text_smaller)) }
        larger = ui.textAction("A+") { stepZoom(READER_ZOOM_STEP) }.apply { describe(ui.str(R.string.reader_text_larger)) }
        syncZoom()
        sheet.add(ui.row().apply {
            addView(smaller, ui.weighted())
            addView(zoomValue, ui.weighted())
            addView(larger, ui.weighted())
        })

        // 3. Segmented helper for font and spacing
        fun <T> segmented(title: Int, options: List<T>, label: (T) -> String, typeface: ((T) -> Typeface)? = null, current: () -> T, apply: (T) -> ReaderExtractor.Style) {
            sheet.add(ui.sectionTitle(ui.str(title)))
            val row = ui.row().apply {
                background = ui.rounded(palette.base, 14)
                setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            }
            val views = mutableMapOf<T, TextView>()
            fun sync() {
                val active = current()
                views.forEach { (option, view) ->
                    val selected = option == active
                    view.isSelected = selected
                    view.background = if (selected) ui.pressable(palette.selectedSurface, 12) else ui.selectable(12)
                    view.setTextColor(if (selected) palette.accent else palette.ink)
                }
            }
            options.forEach { option ->
                val item = ui.asButton(ui.medium(ui.singleLine(ui.label(label(option), 14f)))).apply {
                    gravity = Gravity.CENTER
                    minimumHeight = ui.dp(48)
                    typeface?.invoke(option)?.let { this.typeface = it }
                    setOnClickListener {
                        if (current() == option) return@setOnClickListener
                        host.haptic(this)
                        prefs.readerStyle = apply(option)
                        sync()
                        renderReader()
                    }
                }
                views[option] = item
                row.addView(item, ui.weighted())
            }
            sync()
            sheet.add(row)
        }

        segmented(R.string.reader_font, listOf(false, true),
            label = { ui.str(if (it) R.string.reader_font_serif else R.string.reader_font_sans) },
            typeface = { if (it) Typeface.SERIF else Typeface.SANS_SERIF },
            current = { prefs.readerStyle.serif }) { prefs.readerStyle.copy(serif = it) }
        segmented(R.string.reader_spacing, ReaderExtractor.Spacing.entries,
            label = { ui.str(SPACING_LABELS.getValue(it)) },
            current = { prefs.readerStyle.spacing }) { prefs.readerStyle.copy(spacing = it) }
        sheet.show()
    }

    fun closeReader() {
        aiJob?.cancel()
        aiJob = null
        removeReader()
    }

    private fun removeReader() {
        val panel = reader ?: return
        reader = null
        readerArticle = null; readerTarget = null; readerLabel = null; ruleArticle = null
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        readerWeb?.let { web -> readerSession?.let { session -> runCatching { session.release(web) } } }
        readerWeb = null; readerSession = null
        host.setReaderChromeCollapsed(false)
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
                        val client = BrowserHttp.forPage(proxy, url)
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
        if (host.hidesPrivate(target)) return
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
    fun addToHome(target: BrowserTab) = pin(target, "page-" + (if (host.profile.isMain) target.url else host.profile.id + target.url).hashCode().toUInt().toString(16)) { url, _ ->
        // An icon made in a profile opens there, unlike links from other apps.
        BrowserProfiles.sign(host, Intent(Intent.ACTION_VIEW, url.toUri(), host, BrowserActivity::class.java), host.profile.id)
    }

    /** Pins a home screen icon that opens the page's site in a window of its own, without browser controls. */
    fun installApp(target: BrowserTab) {
        val id = WebAppActivity.shortcutId(target.url, host.profile.id)
        // An app removed earlier keeps its disabled shortcut, which cannot be pinned again until enabled.
        runCatching { host.getSystemService(ShortcutManager::class.java).enableShortcuts(listOf(id)) }
        pin(target, id) { url, title -> WebAppActivity.launchIntent(host, url, title, host.profile.id) }
    }

    /** Moves the current page into its own window, without adding anything to the home screen. */
    fun openInWindow(target: BrowserTab) = WebAppTransfer.open(host, target)

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
        const val MIN_READER_ZOOM = 70
        const val MAX_READER_ZOOM = 175
        const val READER_ZOOM_STEP = 15
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
