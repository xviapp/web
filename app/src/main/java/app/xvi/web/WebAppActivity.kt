package app.xvi.web

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

/**
 * One installed site in a window of its own, like an app: no address bar, toolbar or tabs, and its own
 * entry in recent apps. Links to other sites open in the browser; pop-ups the site opens (sign-in
 * windows) show over it until they close. Nothing here is saved as browser tabs, though visits still
 * count as history, and the site shares the browser's sign-ins.
 */
class WebAppActivity : PageHost() {
    /** The installed address; the window's site follows from it. */
    private lateinit var start: String
    /** The site's own page first, then the pop-ups it opened, the newest in front. */
    private val stack = mutableListOf<BrowserTab>()
    override val tabs: List<BrowserTab> get() = stack
    override val tab: BrowserTab? get() = stack.lastOrNull()
    private val home: BrowserTab? get() = stack.firstOrNull()
    /**
     * Pages kept to the site: its own page, and windows opened from its links (`target="_blank"`).
     * Windows a script opens (sign-in pop-ups) are left free to pass through other sites.
     */
    private val scoped = mutableSetOf<BrowserTab>()

    private lateinit var pageArea: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var menuButton: ImageButton
    private lateinit var snackbar: BrowserSnackbar
    private var progressShowing: Boolean? = null
    private var menuShown = false
    private val hideMenu = Runnable { setMenuShown(false) }
    private var label = ""
    private var icon: Bitmap? = null

    /** Back goes through the page's history, then closes pop-ups, then the window itself. */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val current = tab
            when {
                fullView != null -> hideFullScreen()
                current?.view?.canGoBack() == true -> current.view?.goBack()
                current != null && current !== home -> closePopup(current)
                else -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent?.dataString?.takeIf { BrowserInput.isWebUrl(it) && WebAppScope.of(it) != null }
        if (address == null) { finish(); return }
        start = address
        label = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { BrowserInput.display(address) }
        buildWindow()
        describeTask()
        lifecycleScope.launch {
            val stored = withContext(store.io) { store.icons(listOf(address))[BrowserStore.siteKey(address)] }
            if (icon == null && stored != null) { icon = stored; describeTask() }
        }
        val page = BrowserTab(session = normalSession)
        stack.add(page)
        scoped.add(page)
        watchProxy()
        onBackPressedDispatcher.addCallback(this, backCallback)
        val resumed = savedInstanceState?.getString(STATE_URL)?.takeIf { WebAppScope.contains(address, it) }
        network.prepare { state ->
            if (!isDestroyed) { proxyObserver.accept(state); load(page, resumed ?: address) }
        }
        setMenuShown(true)
    }

    private fun buildWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply {
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        val content = FrameLayout(this)
        pageArea = FrameLayout(this)
        content.addView(pageArea, ui.full())
        content.addView(buildPullIndicator(), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            indeterminateTintList = progressTintList
            visibility = View.INVISIBLE
        }
        content.addView(progress, FrameLayout.LayoutParams(ui.fill(), dp(2), Gravity.TOP))
        // The only control on screen: it shows when the window opens and when the page scrolls back up.
        menuButton = ui.iconAction(R.drawable.ic_more_24, str(R.string.nav_menu)) { showMenu() }.apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ui.rounded(palette.surface, 20)
            foreground = ui.borderless()
            elevation = dp(3).toFloat()
            alpha = 0f
            visibility = View.INVISIBLE
        }
        content.addView(menuButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        root.addView(content, ui.full())
        snackbar = BrowserSnackbar(ui, root)
        setContentView(root)
        applyBarColor(palette.base)
    }

    private fun setMenuShown(shown: Boolean) {
        main.removeCallbacks(hideMenu)
        if (shown) main.postDelayed(hideMenu, MENU_VISIBLE_MS)
        if (shown == menuShown) return
        menuShown = shown
        menuButton.animate().cancel()
        if (shown) {
            menuButton.visibility = View.VISIBLE
            menuButton.animate().alpha(1f).setDuration(160).start()
        } else menuButton.animate().alpha(0f).setDuration(160).withEndAction { if (!menuShown) menuButton.visibility = View.INVISIBLE }.start()
    }

    private fun showMenu() {
        val page = tab ?: return
        val sheet = BrowserSheet(ui, root, label, { false })
        fun row(icon: Int, title: Int, action: () -> Unit) = sheet.add(ui.menuRow(icon, str(title)) { sheet.dismiss(); action() }.view)
        row(R.drawable.ic_refresh_24, R.string.page_refresh) { retryPage(page) }
        if (BrowserInput.isWebUrl(page.url)) {
            row(R.drawable.ic_copy_24, R.string.link_copy) { copy(page.url) }
            row(R.drawable.ic_share_24, R.string.link_share) { share(page.url) }
            row(R.drawable.ic_language_24, R.string.webapp_open_in_browser) { openInBrowser(page.url) }
        }
        sheet.show()
    }

    /** The browser takes over [url] in a tab of its own. */
    private fun openInBrowser(url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri(), this, BrowserActivity::class.java)) }
    }

    /** The site's colour behind the system bars, with icons that stay readable on it. */
    private fun applyBarColor(color: Int) {
        root.setBackgroundColor(color)
        val light = WebAppScope.isLight(color)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** Name and icon in recent apps. */
    @Suppress("DEPRECATION") // The builder only takes resource icons; site icons are bitmaps.
    private fun describeTask() = setTaskDescription(ActivityManager.TaskDescription(label, icon))

    override fun attachPage(web: View) {
        (web.parent as? ViewGroup)?.removeView(web)
        pageArea.removeAllViews()
        pageArea.addView(web, ui.full())
    }

    override fun updateChrome() = updateProgress()

    override fun updateProgress() {
        if (!::progress.isInitialized) return
        val current = tab
        val waiting = current?.waiting == true
        val loading = current?.view?.progress ?: 100
        val showing = current?.error == null && (waiting || loading < 100)
        progress.isIndeterminate = waiting
        if (!waiting) progress.progress = loading
        if (showing == progressShowing) return
        progressShowing = showing
        progress.visibility = if (showing) View.VISIBLE else View.INVISIBLE
    }

    override fun load(target: BrowserTab, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        if (target === home && !WebAppScope.contains(start, url)) { openInBrowser(url); return }
        target.url = url
        target.error = null
        target.waiting = true
        updateProgress()
        network.prepare {
            if (isDestroyed || target !in stack || target.url != url) return@prepare
            target.waiting = false
            val view = target.view ?: createWebView(target).also { target.view = it }
            applySiteSettings(target, view, url)
            view.loadUrl(url)
            if (tab === target) attachPage(view)
            updateProgress()
        }
    }

    /** Pages kept to the site hand other sites to the browser; a script's pop-up may pass through them to sign in. */
    override fun leavesWindow(target: BrowserTab, request: WebResourceRequest): Boolean {
        if (target !in scoped) return false
        val url = request.url.toString()
        if (!WebAppScope.leaves(start, url, request.isRedirect)) return false
        handOff(target, url)
        return true
    }

    /** A window opened from a link starts loading without asking [leavesWindow] first. */
    override fun stopsAtStart(target: BrowserTab, view: WebView, url: String): Boolean {
        if (target === home || target !in scoped || !BrowserInput.isWebUrl(url) || WebAppScope.contains(start, url)) return false
        view.stopLoading()
        handOff(target, url)
        return true
    }

    /** Sends [url] to the browser; a window opened only to show it closes again. */
    private fun handOff(target: BrowserTab, url: String) {
        openInBrowser(url)
        if (target !== home && !BrowserInput.isWebUrl(target.url)) main.post { if (target in stack) closePopup(target) }
    }

    override fun showPageError(target: BrowserTab, error: PageError) {
        target.error = error
        target.waiting = false
        if (tab === target) renderPageError(target, error)
        updateProgress()
    }

    private fun renderPageError(target: BrowserTab, error: PageError) {
        val panel = ui.column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(24), dp(30), dp(24))
            setBackgroundColor(palette.surface)
        }
        panel.addView(ui.label(error.title, 22f).apply { gravity = Gravity.CENTER })
        if (error.message.isNotEmpty()) panel.addView(ui.label(error.message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        panel.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))
        val actions = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        actions.addView(ui.textAction(str(R.string.action_retry), true) { retryPage(target) }, ui.weighted())
        if (BrowserInput.isWebUrl(target.url)) {
            actions.addView(ui.textAction(str(R.string.webapp_open_in_browser)) { openInBrowser(target.url) }, ui.weighted().apply { leftMargin = dp(8) })
        }
        panel.addView(actions, ui.wide())
        error.action?.let { (title, action) ->
            panel.addView(ui.textAction(title) { action() }.apply { setTextColor(palette.danger) }, ui.wide().apply { topMargin = dp(6) })
        }
        attachPage(panel)
    }

    override fun retryPage(target: BrowserTab) {
        target.error = null
        if (tab !== target) return
        val web = target.view ?: run { load(target, target.url); return }
        attachPage(web)
        reloadWhenReady(target, web) { if (web.url == target.url) web.reload() else web.loadUrl(target.url) }
        updateProgress()
    }

    override fun openWindow(source: BrowserTab, resultMsg: Message): Boolean {
        // A link opening a new window is still a link: another site goes to the browser before any window opens.
        val hit = source.view?.hitTestResult
        val fromLink = hit?.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit?.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val link = hit?.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && BrowserInput.isWebUrl(it) }
        if (link != null && source in scoped && !WebAppScope.contains(start, link)) { openInBrowser(link); return false }
        if (stack.size > MAX_POPUPS) return false
        val child = BrowserTab(url = "about:blank", session = normalSession)
        val view = createWebView(child).also { child.view = it }
        (resultMsg.obj as WebView.WebViewTransport).webView = view
        resultMsg.sendToTarget()
        stack.add(child)
        // An image link names only the image, so where it leads is judged when the new window starts loading.
        if (fromLink && source in scoped) scoped.add(child)
        attachPage(view)
        updateProgress()
        return true
    }

    /** The site's own page cannot close the window; a pop-up closing itself goes back to its opener. */
    override fun closeWindow(window: WebView) {
        stack.firstOrNull { it.view === window && it !== home }?.let(::closePopup)
    }

    private fun closePopup(target: BrowserTab) {
        stack.remove(target)
        scoped.remove(target)
        target.view?.let { target.session.release(it) }
        target.view = null
        val current = tab ?: return
        val error = current.error
        if (error != null) renderPageError(current, error) else current.view?.let(::attachPage)
        updateProgress()
    }

    override fun openInBackground(source: BrowserTab, url: String) = openInBrowser(url)
    override val backgroundLinkLabel get() = R.string.webapp_open_in_browser

    override fun showSnackbar(message: String, action: String?, onAction: (() -> Unit)?) = snackbar.show(message, action, onAction)

    override val reopenIntent get() = launchIntent(this, start, label)

    override fun configureWebView(target: BrowserTab, web: BrowserWebView) {
        web.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (tab !== target || fullView != null) return@setOnScrollChangeListener
            val dy = scrollY - oldScrollY
            if (dy < -30) setMenuShown(true) else if (dy > 30) setMenuShown(false)
        }
    }

    /** The site's `theme-color` tints the system bars; its icon stands for the window in recent apps. */
    override fun pageFinished(target: BrowserTab, view: WebView) {
        if (target !== home) return
        runCatching {
            view.evaluateJavascript(THEME_COLOR) { raw ->
                if (isDestroyed || target.view !== view) return@evaluateJavascript
                val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                applyBarColor(WebAppScope.themeColor(value) ?: palette.base)
            }
        }
        target.favicon?.takeIf { it !== icon }?.let { icon = it; describeTask() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        home?.url?.takeIf(BrowserInput::isWebUrl)?.let { outState.putString(STATE_URL, it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!prefsReady || !::start.isInitialized) return
        val night = when (prefs.theme) {
            BrowserPrefs.Theme.LIGHT -> false
            BrowserPrefs.Theme.DARK -> true
            BrowserPrefs.Theme.SYSTEM -> newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        if (night != palette.dark) recreate()
    }

    override fun onDestroy() {
        stack.forEach { item -> item.view?.let { item.session.release(it) }; item.view = null }
        stack.clear()
        scoped.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "app.xvi.web.WEB_APP_TITLE"
        private const val STATE_URL = "url"
        private const val MAX_POPUPS = 5
        private const val MENU_VISIBLE_MS = 3_000L
        /** The page's `theme-color` for the current colour scheme, if it gives one. */
        private const val THEME_COLOR = "(() => { try { for (const m of document.querySelectorAll('meta[name=\"theme-color\"]')) {" +
            " const q = m.getAttribute('media'); if (!q || matchMedia(q).matches) return m.content || ''; } } catch (_) {} return ''; })()"

        /**
         * Opens [url] as its own window. The address is the window's identity: opening the same one again
         * returns to its window instead of starting another.
         */
        internal fun launchIntent(context: Context, url: String, title: String): Intent =
            Intent(Intent.ACTION_VIEW, url.toUri(), context, WebAppActivity::class.java).putExtra(EXTRA_TITLE, title)

        internal fun shortcutId(url: String) = SHORTCUT_PREFIX + url.hashCode().toUInt().toString(16)
        private const val SHORTCUT_PREFIX = "app-"

        /** Web apps still on the home screen. */
        internal fun installed(context: Context) =
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.id.startsWith(SHORTCUT_PREFIX) && it.isEnabled }
    }
}
