package app.xvi.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/**
 * A window that shows web pages. Everything a page can ask of the app lives here: its website identity,
 * downloads, uploads, permissions, script dialogs, full-screen video and the background session. The
 * browser adds tabs and its toolbar on top; a web app window shows one site with nothing around it.
 */
abstract class PageHost : AppCompatActivity() {
    internal val normalSession = BrowserSession()
    internal abstract val tabs: List<BrowserTab>
    internal abstract val tab: BrowserTab?
    internal open val isPrivateMode: Boolean get() = false

    internal lateinit var store: BrowserStore
    internal lateinit var prefs: BrowserPrefs
    internal lateinit var network: BrowserProxyNetwork
    internal lateinit var ui: BrowserUi
    /** False only while [onCreate] has not run yet. */
    protected val prefsReady get() = ::prefs.isInitialized
    internal val palette get() = ui.palette
    protected val main = Handler(Looper.getMainLooper())
    internal val permissions = SitePermissions(this)
    internal val downloads = DownloadController(this)
    internal val pages = PageInteractions(this)
    internal val uploads = UploadController(this)
    /** Live media state for the background notification and the lock screen. */
    internal val media = MediaProbe(this)
    /** Site settings and pop-up choices, shared with every other window. */
    internal val siteChoices by lazy { SiteChoices.get(store) }
    /** Re-applies a change another window (or this one) made: one site, or every site for null. */
    private val choicesChanged: (String?) -> Unit = { site ->
        if (!isDestroyed) reapplySiteSettings { site == null || BrowserStore.siteKey(it.url) == site }
    }

    internal lateinit var root: FrameLayout
    protected lateinit var pullIndicator: ImageView
    protected var fullView: View? = null
        private set
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** Input fields whose privacy flags are already in place, so the IME is not restarted again. */
    private val inputPrivacy = java.util.WeakHashMap<EditText, Boolean>()
    private val desktopUserAgent by lazy { UserAgents.desktop(WebSettings.getDefaultUserAgent(this)) }

    internal var proxyRoute = BrowserProxyNetwork.Route.OFF
        private set
    /** The proxy is being checked for the first time, so a page may wait for that reason alone. */
    internal val proxyConnecting get() = proxyRoute == BrowserProxyNetwork.Route.CHECKING
    private var proxyObservation: BrowserProxyNetwork.Observation? = null
    protected val proxyObserver = Consumer<BrowserProxyNetwork.Status> { state -> runOnUiThread {
        if (!isDestroyed) {
            proxyRoute = state.route
            onProxyChanged(state)
        }
    } }

    // What each kind of window does with its pages.

    /** Puts [web] alone in the page area. */
    internal abstract fun attachPage(web: View)
    internal abstract fun updateChrome()
    /** Progress-only refresh; called for every progress tick. */
    internal abstract fun updateProgress()
    /** The window explains a wait for the proxy where the page loads, so no toast is needed. */
    protected open val showsWaitingReason = false
    internal abstract fun showPageError(target: BrowserTab, error: PageError)
    /** Reloads in the same WebView, keeping the tab's back/forward history. */
    internal abstract fun retryPage(target: BrowserTab)
    internal abstract fun load(target: BrowserTab, url: String)
    /** A page opens a window; false refuses it. */
    internal abstract fun openWindow(source: BrowserTab, resultMsg: Message): Boolean
    internal abstract fun closeWindow(window: WebView)
    /** Opens [url] without leaving [source], from the long-press menu. */
    internal abstract fun openInBackground(source: BrowserTab, url: String)
    internal abstract fun showSnackbar(message: String, action: String? = null, onAction: (() -> Unit)? = null)
    /** Brings this window back, from the media notification. */
    internal abstract val reopenIntent: Intent
    internal open fun schedulePersist() = Unit
    /** Whether [target]'s page is what the window shows, rather than only its selected tab. */
    internal open fun showsPage(target: BrowserTab) = tab === target
    /** [view] stopped working; anything still pointing at it lets go. */
    internal open fun pageGone(view: WebView) = Unit
    /** A finished main-frame document. */
    internal open fun pageFinished(target: BrowserTab, view: WebView) = Unit
    /** True when the navigation was handed elsewhere instead of loading in [target]. */
    internal open fun leavesWindow(target: BrowserTab, request: WebResourceRequest): Boolean = false
    /**
     * Last look at a document starting in [target], for loads that never ask [leavesWindow] (a new
     * window's first page); true when it was stopped and handed elsewhere.
     */
    internal open fun stopsAtStart(target: BrowserTab, view: WebView, url: String): Boolean = false
    /** What opening a link from the long-press menu is called in this window. */
    internal open val backgroundLinkLabel: Int get() = R.string.link_open_new_tab
    internal open val canOpenPrivate: Boolean get() = false
    internal open fun openPrivate(url: String) = Unit
    /** Whether a page, rather than something of the app's own, fills the page area. */
    protected open val pageShowing: Boolean get() = true
    protected open fun onProxyChanged(state: BrowserProxyNetwork.Status) = Unit
    protected open fun onFullScreenChanged() = Unit
    /** Window-specific WebView wiring, before the view is used. */
    internal open fun configureWebView(target: BrowserTab, web: BrowserWebView) = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BrowserPrefs(this)
        delegate.localNightMode = prefs.theme.nightMode
        super.onCreate(savedInstanceState)
        store = BrowserStore.get(this)
        BrowserInput.customTemplate = prefs.customEngine.takeIf(BrowserInput::isValidTemplate)
        BrowserSession.cleanupPreviousProcess()
        // Another window may still be using its captures and staged files.
        if (savedInstanceState == null && live.isEmpty()) { UploadController.clearOld(cacheDir); DownloadController.clearStaging(cacheDir) }
        live.add(this)
        siteChoices.addListener(choicesChanged)
        // Pages that loaded before the first window read the choices get them now.
        siteChoices.whenLoaded { if (!isDestroyed) reapplySiteSettings() }
        if (!BrowserDownloadService.running) store.writeAsync { interruptDownloads() }
        network = BrowserProxyNetwork.get(this)
        ui = BrowserUi(this, BrowserPalette(this, prefs).also { it.themed(theme) })
        media.register(getSystemService(AudioManager::class.java))
        AdBlock.ensureLoaded(this)
    }

    /** Starts following the proxy; call once the window's views exist. */
    protected fun watchProxy() { proxyObservation = network.observe(proxyObserver) }

    internal fun str(@StringRes id: Int, vararg args: Any): String = getString(id, *args)
    protected fun dp(value: Int) = ui.dp(value)
    internal fun domain(url: String): String = BrowserInput.display(url)
    internal fun displayTitle(target: BrowserTab) = target.title.ifBlank { if (target.url.isEmpty()) str(R.string.tab_new) else domain(target.url) }

    internal fun haptic(view: View? = null, constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (!::prefs.isInitialized || !prefs.haptics) return
        (view ?: window.decorView).performHapticFeedback(constant)
    }
    /** Whether plain HTTP addresses are tried over HTTPS first in this window. */
    protected open val upgradesToHttps: Boolean get() = prefs.httpsFirst
    /** The address to load for [url]: its HTTPS form when HTTPS-First applies, remembering [url] for a fallback. */
    internal fun preferHttps(target: BrowserTab, url: String): String {
        val upgraded = if (upgradesToHttps && HttpsFirst.host(url) !in target.session.httpAllowed) HttpsFirst.upgrade(url) else null
        target.upgradedFrom = upgraded?.let { url }
        return upgraded ?: url
    }
    /** HTTPS failed for [original]; explains that and offers the plain HTTP address, for this session. */
    internal fun httpsUnavailable(target: BrowserTab, original: String) {
        target.upgradedFrom = null
        val site = HttpsFirst.host(original) ?: original
        showPageError(target, PageError(str(R.string.error_https_title), str(R.string.error_https_message, site),
            str(R.string.error_https_proceed) to {
                target.session.httpAllowed.add(site)
                load(target, original)
            }))
    }

    /**
     * [source] is the tab that asked for the dialog; a background private page still hides its dialog.
     * Dialogs take the panels' surface and rounding, so pure black and the other palettes reach them too.
     */
    internal fun browserDialog(source: BrowserTab? = tab): AlertDialog.Builder = object : AlertDialog.Builder(this) {
        override fun create(): AlertDialog = super.create().also {
            if (source?.isPrivate == true || isPrivateMode) it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // The inset matches the default dialog background's, so the width on screen stays the same.
            it.window?.setBackgroundDrawable(InsetDrawable(ui.rounded(palette.surface, 24), ui.dp(16)))
        }
    }
    internal fun confirm(message: String, positive: String = str(R.string.action_confirm), destructive: Boolean = false, action: () -> Unit) {
        val dialog = browserDialog().setMessage(message).setPositiveButton(positive) { _, _ -> action() }.setNegativeButton(R.string.action_cancel, null).create()
        if (destructive) dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.danger) }
        dialog.show()
    }

    internal fun applyInputPrivacy(input: EditText, private: Boolean) {
        // Called for every chrome refresh; an unchanged field keeps its input connection untouched.
        if (inputPrivacy[input] == private) return
        inputPrivacy[input] = private
        val options = if (private) input.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            else input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        if (options != input.imeOptions) {
            input.imeOptions = options
            // A focused field keeps its old input connection until the keyboard restarts it.
            if (input.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(input)
        }
        input.importantForAutofill = if (private) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_AUTO
    }

    /** Desktop mode is remembered for the page's site. */
    internal fun toggleDesktop(target: BrowserTab) {
        val current = siteChoices.get(target.session, target.url)
        updateSiteSetting(target, current.copy(desktop = (!target.desktop).takeIf { it }))
    }

    /** Saves [setting] for [target]'s site and applies it, reloading when the page must load again. */
    internal fun updateSiteSetting(target: BrowserTab, setting: SiteSetting) {
        val site = siteChoices.set(target.session, target.url, setting) ?: return
        // An ordinary change reaches every window through [choicesChanged]; a private one stays in its session.
        if (target.isPrivate) reapplySiteSettings { BrowserStore.siteKey(it.url) == site && it.session === target.session }
        schedulePersist()
    }

    /** Applies the current site settings to the live pages [which] selects, reloading those that need it. */
    internal fun reapplySiteSettings(which: (BrowserTab) -> Boolean = { true }) {
        tabs.filter(which).forEach { item -> item.view?.let { web -> if (applySiteSettings(item, web, item.url)) web.reload() } }
    }

    /**
     * Applies the settings of [url]'s site to [web]: desktop mode, text size, JavaScript and images.
     * Returns true when something changed that only takes effect once the page loads again.
     */
    internal fun applySiteSettings(target: BrowserTab, web: WebView, url: String): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        val site = siteChoices.get(target.session, url)
        val desktop = site.desktop ?: false
        val javascript = site.javascript ?: true
        val images = site.images ?: true
        web.settings.textZoom = site.textZoom ?: prefs.textZoom
        target.desktop = desktop
        target.adblock = prefs.adblock && site.adblock != false
        val browserView = web as? BrowserWebView
        val changed = (browserView?.desktop ?: false) != desktop || web.settings.javaScriptEnabled != javascript ||
            web.settings.loadsImagesAutomatically != images
        if (!changed) return false
        web.settings.userAgentString = if (desktop) desktopUserAgent else null
        browserView?.desktop = desktop
        web.settings.javaScriptEnabled = javascript
        web.settings.loadsImagesAutomatically = images
        return true
    }

    /** Applies the ad blocking switch and each site's choice to the open pages; they keep what already loaded. */
    internal fun refreshAdblock() {
        tabs.forEach { item -> item.adblock = prefs.adblock && siteChoices.get(item.session, item.url).adblock != false }
    }

    /** Browser-wide settings changed: every open window applies them to its pages. */
    internal fun preferencesChanged() = live.toList().forEach { if (!it.isDestroyed) it.applyPreferences() }

    private fun applyPreferences() {
        refreshAdblock()
        reapplySiteSettings()
        applyPageDarkening()
        if (prefs.forceZoom) tabs.forEach { item -> item.view?.let(pages::pageReady) }
    }

    /**
     * Hides what is left of ads on [web]'s page: element hiding rules for the site, and generic rules
     * for the classes and ids the page actually uses. Runs when the page first shows and when it
     * finishes; [again] repeats it a little later for content the page adds on its own.
     */
    internal fun hideAds(target: BrowserTab, web: WebView, again: Boolean = false) {
        if (!target.adblock || target.view !== web) return
        val filter = AdBlock.filter ?: return
        val page = web.url?.takeIf(BrowserInput::isWebUrl) ?: return
        val document = target.documentVersion
        fun current() = !isDestroyed && target.view === web && target.documentVersion == document
        runCatching {
            web.evaluateJavascript(AdFilter.PAGE_KEYS) { raw ->
                if (!current()) return@evaluateJavascript
                val keys = runCatching { org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                    ?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
                lifecycleScope.launch {
                    val css = withContext(kotlinx.coroutines.Dispatchers.Default) { filter.hidingCss(page, keys) } ?: return@launch
                    if (current()) runCatching { web.evaluateJavascript(AdFilter.injectScript(css), null) }
                }
            }
        }
        if (again) main.postDelayed({ if (current()) hideAds(target, web) }, ADS_AGAIN_MS)
    }

    /**
     * Background keep-alive runs only while media plays in any window. Starting is only attempted
     * while visible, as Android requires.
     */
    internal fun syncKeepAlive() {
        if (!::prefs.isInitialized) return
        val wanted = live.any { it.media.playing }
        val intent = Intent(this, BrowserKeepAliveService::class.java)
        if (wanted && !BrowserKeepAliveService.running && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { ContextCompat.startForegroundService(this, intent) }
        } else if (!wanted && BrowserKeepAliveService.running) stopService(intent)
    }

    /** Lets pages without their own dark theme be darkened while the browser is dark. */
    internal fun applyPageDarkening(web: WebView? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val allowed = palette.dark && prefs.darkPages
        (web?.let(::listOf) ?: tabs.mapNotNull { it.view }).forEach { WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, allowed) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun createWebView(target: BrowserTab): WebView = target.session.createWebView(this).apply {
        setBackgroundColor(palette.surface)
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            blockLocalAccess()
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true; setSupportMultipleWindows(true)
            // Untapped pop-ups reach onCreateWindow so they can be blocked with a notice instead of silently.
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            textZoom = prefs.textZoom
            if (target.desktop) userAgentString = desktopUserAgent
        }
        desktop = target.desktop
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(this, PAGE_START_SCRIPT, setOf("*")) }
        }
        onPull = { distance, release -> pullToRefresh(target, this, distance, release) }
        target.session.cookies().setAcceptCookie(true)
        target.session.cookies().setAcceptThirdPartyCookies(this, false)
        webViewClient = PageClient(this@PageHost, target)
        webChromeClient = PageChromeClient(this@PageHost, target)
        setDownloadListener { url, ua, disposition, mime, _ -> downloads.download(target, url, ua, disposition, mime) }
        setOnLongClickListener { pages.longPress(target, this) }
        configureWebView(target, this)
        // Applied once the view is fully configured, never while its settings are still being written.
        applyPageDarkening(this)
    }

    /** The indicator [pullToRefresh] moves; it sits at the top of the page area. */
    protected fun buildPullIndicator(): ImageView = ImageView(this).apply {
        setImageDrawable(ui.tintedIcon(R.drawable.ic_refresh_24, palette.accent))
        scaleType = ImageView.ScaleType.CENTER
        background = ui.rounded(palette.surface, 20)
        elevation = dp(4).toFloat()
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        pullIndicator = this
    }

    /** Pull down at the top of a page to reload it. */
    private fun pullToRefresh(target: BrowserTab, web: WebView, distance: Float, release: Boolean) {
        if (tab !== target || target.view !== web || !pageShowing || fullView != null) { stopPullSpinner(); pullIndicator.visibility = View.INVISIBLE; return }
        // The page is already reloading from an earlier pull; its spinner stays put.
        if (pullRefreshing === target) return
        val threshold = dp(64).toFloat()
        val pulled = (distance * 0.5f).coerceAtMost(dp(96).toFloat())
        pullIndicator.animate().cancel()
        if (!release) {
            pullIndicator.visibility = View.VISIBLE
            pullIndicator.translationY = pulled - dp(40)
            pullIndicator.alpha = (pulled / threshold).coerceIn(0f, 1f)
            pullIndicator.rotation = pulled * 3
            pullIndicator.imageAlpha = if (pulled >= threshold) 255 else 140
            return
        }
        if (pulled >= threshold) {
            haptic(web, CONFIRM_HAPTIC)
            startPullRefresh(target)
            reloadWhenReady(target, web)
            return
        }
        hidePullIndicator()
    }

    /** The tab a pull reloaded, while its indicator keeps spinning; null once the reload is over. */
    private var pullRefreshing: BrowserTab? = null
    /** Whether that reload has been seen loading, so the progress it starts from does not end it. */
    private var pullRefreshStarted = false
    private var pullSpinner: android.animation.ObjectAnimator? = null
    /** Ends a spinner whose reload never started, such as one the page refused or stopped at once. */
    private val pullRefreshTimeout = Runnable { if (!pullRefreshStarted && pullRefreshing?.waiting == false) endPullRefresh() }

    /** Rests the indicator below the top of the page and spins it until [syncPullRefresh] sees the reload end. */
    private fun startPullRefresh(target: BrowserTab) {
        pullRefreshing = target
        pullRefreshStarted = false
        pullIndicator.imageAlpha = 255
        pullIndicator.animate().translationY(dp(16).toFloat()).alpha(1f).setDuration(150).start()
        val from = pullIndicator.rotation
        pullSpinner = android.animation.ObjectAnimator.ofFloat(pullIndicator, View.ROTATION, from, from + 360f).apply {
            duration = 800
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    /**
     * Keeps a pull's spinner only while the tab it reloaded is shown and still waiting or loading;
     * windows call this from [updateProgress].
     */
    protected fun syncPullRefresh() {
        val target = pullRefreshing ?: return
        val view = target.view
        if (tab !== target || view == null || !pageShowing || fullView != null || target.error != null) { endPullRefresh(); return }
        if (target.waiting) return
        if (view.progress < 100) { pullRefreshStarted = true; return }
        if (pullRefreshStarted) endPullRefresh()
        else { pullIndicator.removeCallbacks(pullRefreshTimeout); pullIndicator.postDelayed(pullRefreshTimeout, PULL_START_MS) }
    }

    private fun endPullRefresh() {
        if (pullRefreshing == null) return
        stopPullSpinner()
        hidePullIndicator()
    }

    private fun stopPullSpinner() {
        pullRefreshing = null
        pullIndicator.removeCallbacks(pullRefreshTimeout)
        pullSpinner?.cancel()
        pullSpinner = null
    }

    private fun hidePullIndicator() {
        pullIndicator.animate().translationY(-dp(40).toFloat()).alpha(0f).setDuration(180)
            .withEndAction { pullIndicator.visibility = View.INVISIBLE }.start()
    }

    /**
     * Reloads [web] once the network is ready. Until then the tab shows it is waiting, so a reload
     * asked for while the proxy connects is visibly held rather than silently queued, and repeated
     * asks collapse into the latest one instead of reloading once per ask.
     */
    internal fun reloadWhenReady(target: BrowserTab, web: WebView, reload: () -> Unit = web::reload) {
        target.waiting = true
        val navigation = ++target.navigation
        if (proxyConnecting && !showsWaitingReason) toast(R.string.proxy_waiting)
        if (tab === target) updateProgress()
        network.prepare {
            if (isDestroyed || target.navigation != navigation || target.view !== web) return@prepare
            target.waiting = false
            reload()
            if (tab === target) updateProgress()
        }
    }

    internal fun showFullScreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullView != null) { callback.onCustomViewHidden(); return }
        val holder = VideoGestures(ui, window, getSystemService(AudioManager::class.java)).apply { hold(view) }
        fullView = holder; fullCallback = callback; root.visibility = View.GONE
        (window.decorView as ViewGroup).addView(holder, ViewGroup.LayoutParams(ui.fill(), ui.fill()))
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        updatePictureInPicture()
        onFullScreenChanged()
    }

    /** Full-screen video continues in a floating window when the user leaves the app. */
    private fun pictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val view = fullView
        if (view != null && view.width > 0 && view.height > 0) {
            val ratio = view.width.toFloat() / view.height
            // The system only accepts ratios between 1:2.39 and 2.39:1.
            if (ratio in 0.42f..2.39f) builder.setAspectRatio(android.util.Rational(view.width, view.height))
            val bounds = android.graphics.Rect()
            if (view.getGlobalVisibleRect(bounds)) builder.setSourceRectHint(bounds)
        } else builder.setAspectRatio(android.util.Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(view != null).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    private val supportsPictureInPicture by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private fun updatePictureInPicture() {
        if (supportsPictureInPicture) runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 and later enter on their own (auto-enter); older systems are asked here.
        if (fullView != null && Build.VERSION.SDK_INT < 31 && supportsPictureInPicture) runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Closing the floating window stops the activity; the video leaves full screen with it.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) hideFullScreen()
    }
    internal fun hideFullScreen() {
        val wasFull = fullView != null
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it); (it as? VideoGestures)?.release() }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        if (!wasFull) return
        updatePictureInPicture()
        root.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        onFullScreenChanged()
    }

    internal fun share(url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), str(R.string.link_share)))
    }
    @SuppressLint("InlinedApi") // Systems before Android 13 ignore the sensitive-content extra.
    internal fun copy(value: String, sensitive: Boolean = isPrivateMode) {
        if (value.isBlank()) return
        val clip = ClipData.newPlainText(str(R.string.clip_label), value)
        // Keeps private links out of the clipboard preview and keyboard clipboard history.
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < 33) toast(R.string.link_copied)
    }
    internal fun toast(message: String) { if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    internal fun toast(@StringRes message: Int) = toast(str(message))

    override fun onStart() {
        super.onStart()
        syncKeepAlive()
    }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() {
        if (::prefs.isInitialized) CookieManager.getInstance().flush()
        // The floating video window keeps playing.
        if (!isInPictureInPictureMode && !BrowserKeepAliveService.running) {
            tabs.forEach { it.view?.onPause() }
            // Media that started since the last check still gets to keep playing.
            media.probe { if (BrowserKeepAliveService.running) tabs.forEach { it.view?.onResume() } }
        }
        super.onPause()
    }
    /** Subclasses release their pages first; whatever is left goes with the session here. */
    override fun onDestroy() {
        // Open panels are listed in a static set until they close; closing them here lets this window go.
        if (::root.isInitialized) BrowserSheet.dismissAll(root)
        uploads.release(); permissions.release()
        media.unregister(getSystemService(AudioManager::class.java))
        media.release()
        MediaControl.release(media)
        proxyObservation?.close()
        proxyObservation = null
        main.removeCallbacksAndMessages(null)
        live.remove(this)
        siteChoices.removeListener(choicesChanged)
        normalSession.end()
        super.onDestroy()
    }

    internal companion object {
        /** Every open window, so the background session and cached files account for all of them. */
        private val live = mutableSetOf<PageHost>()
        /** Second element hiding pass, for content pages add after loading. */
        private const val ADS_AGAIN_MS = 3_000L
        /** How long a pulled reload may take to start loading before its spinner gives up. */
        private const val PULL_START_MS = 1_500L

        /**
         * Runs before page scripts. Pages often revoke a `blob:` address right after clicking it, before
         * the browser can read the file, so revoking waits a minute; the name in a link's `download`
         * attribute is kept for `blob:` and `data:` links, since the download request does not carry it.
         * The file behind each `blob:` address is kept as well: pages whose security policy leaves `blob:`
         * out of `connect-src` (grok.com) refuse to fetch it, but the file itself can still be read.
         */
        const val PAGE_START_SCRIPT = "(() => { try { if (window.__xviDownloadNames) return; const names = new Map();" +
            " Object.defineProperty(window, '__xviDownloadNames', { value: names });" +
            " const blobs = new Map(); Object.defineProperty(window, '__xviBlobs', { value: blobs });" +
            " const create = URL.createObjectURL; URL.createObjectURL = function (o) { const u = create.apply(URL, arguments); try { if (o instanceof Blob) blobs.set(u, o); } catch (_) {} return u; };" +
            " const keep = a => { try { if (a && a.hasAttribute('download') && /^(blob|data):/i.test(a.href) && a.href.length < 1048576) { names.set(a.href, a.getAttribute('download') || ''); if (names.size > 50) names.delete(names.keys().next().value); } } catch (_) {} };" +
            " const click = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () { keep(this); return click.apply(this, arguments); };" +
            " document.addEventListener('click', e => { keep(e.target && e.target.closest ? e.target.closest('a[download]') : null); }, true);" +
            " const revoke = URL.revokeObjectURL; URL.revokeObjectURL = function (u) { setTimeout(() => { blobs.delete(u); revoke.call(URL, u); }, 60000); };" +
            " } catch (_) {} })();"
    }
}
