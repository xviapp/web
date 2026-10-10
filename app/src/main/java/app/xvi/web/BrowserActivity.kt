package app.xvi.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.SearchManager
import android.content.*
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/** One browser tab. Ordinary tabs persist their address; private tabs live only in memory. */
internal class BrowserTab(
    var url: String = "", var title: String = "", var desktop: Boolean = false,
    var view: WebView? = null, var state: Bundle? = null, var touched: Long = 0,
    val session: BrowserSession, var keepAlive: Boolean = false, var documentVersion: Long = 0,
    var restoreScroll: Pair<Int, Int>? = null, var favicon: Bitmap? = null,
    /** Navigation requested but held until the network (proxy) is ready. */
    var waiting: Boolean = false
) : TabManager.Entry {
    override val isPrivate get() = session.isPrivate
    override val isBlank get() = url.isEmpty() && view == null
}

/** Native browser chrome. Untrusted pages never receive an Android JavaScript bridge. */
class BrowserActivity : AppCompatActivity() {
    internal val normalSession = BrowserSession()
    internal var privateSession: BrowserSession? = null
    internal val tabManager = TabManager { BrowserTab(session = normalSession) }
    internal val tabs: List<BrowserTab> get() = tabManager.tabs
    internal val tab: BrowserTab? get() = tabManager.current
    internal val isPrivateMode get() = tabManager.isPrivateMode
    private var themeRecreatePending = false

    internal lateinit var store: BrowserStore
    internal lateinit var prefs: BrowserPrefs
    internal lateinit var network: BrowserProxyNetwork
    internal lateinit var ui: BrowserUi
    private val palette get() = ui.palette
    private val main = Handler(Looper.getMainLooper())
    internal val permissions = SitePermissions(this)
    internal val downloads = DownloadController(this)
    internal val pages = PageInteractions(this)

    internal lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    internal lateinit var pageHost: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: TextView
    private lateinit var tabButtonIcon: ImageView
    private lateinit var tabButtonHost: View
    private lateinit var addressProxyButton: ImageButton
    private lateinit var siteButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var bottomBar: LinearLayout
    internal lateinit var findBar: FindBar
    private lateinit var snackbar: BrowserSnackbar
    internal lateinit var searchOverlay: SearchOverlay
    private lateinit var homePage: HomePage
    private lateinit var refreshIcon: Drawable
    private lateinit var stopIcon: Drawable
    private lateinit var ghostIcon: Drawable
    private lateinit var tabsIcon: Drawable
    private lateinit var privateTabsIcon: Drawable
    internal var tabsPanel: TabsPanel? = null
    internal var proxyPanel: ProxyPanel? = null
    private var chromeCollapsed = false
    private var progressShowing: Boolean? = null
    internal var proxyPhase = ProxyPhase.OFF
        private set
    internal var proxyMessage = ""
        private set
    internal var homeVisible = true
        private set
    @VisibleForTesting internal var ready = false
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    internal var fileCallback: ValueCallback<Array<Uri>>? = null
    private var initialIntent: String? = null
    private var mediaPlaying = false
    @VisibleForTesting internal var trimming = false
    private val protectionProbe by lazy { assets.open("tab-protection.js").bufferedReader().use { it.readText() } }
    private val desktopUserAgent by lazy { UserAgents.desktop(WebSettings.getDefaultUserAgent(this)) }
    private val memoryCheck = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            trimTabs(currentMemoryPressure())
            probeMedia()
            main.postDelayed(this, 15_000)
        }
    }
    private val persistLater = Runnable { if (!isDestroyed) persist() }
    private var proxyObservation: com.v2ray.ang.api.Observation? = null
    internal var proxyState: ProxyState? = null
        private set
    private val proxyObserver = Consumer<ProxyState> { state -> runOnUiThread {
        if (!isDestroyed) {
            proxyState = state
            proxyPhase = state.phase
            proxyMessage = ProxyPresentation.message(this, state)
            updateProxyButton()
            proxyPanel?.render(state)
            syncKeepAlive()
        }
    } }
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                fullView != null -> hideFullScreen()
                findBar.isVisible -> hideFindInPage()
                searchOverlay.isVisible || address.hasFocus() || homePage.input.hasFocus() -> hideSearch()
                else -> when (BrowserNavigation.back(
                    homeVisible = homeVisible,
                    canGoBack = tab?.view?.canGoBack() == true,
                    hasPage = tab?.url?.isNotEmpty() == true,
                    multipleTabs = BrowserNavigation.canCloseOnBack(isPrivateMode, tabManager.count(isPrivateMode))
                )) {
                    BrowserNavigationAction.WEB_BACK -> tab?.view?.goBack()
                    BrowserNavigationAction.SHOW_HOME -> home()
                    BrowserNavigationAction.CLOSE_TAB -> closeTab(tabManager.selected, offerUndo = true)
                    BrowserNavigationAction.LEAVE -> moveTaskToBack(true)
                    else -> Unit
                }
            }
        }
    }

    internal val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)?.filter { it.scheme == "content" }?.toTypedArray()
        fileCallback?.onReceiveValue(uris); fileCallback = null
    }
    internal val bookmarkImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(store.io) { runCatching { contentResolver.openInputStream(uri)!!.use(store::importBookmarks) } }
            toast(result.fold({ str(R.string.bookmarks_imported, it) }, { str(R.string.bookmarks_import_failed) }))
            refreshHome()
        }
    }
    internal val bookmarkExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val html = withContext(store.io) { store.exportBookmarks() }
            val result = withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(html) } } }
            toast(if (result.isSuccess) R.string.bookmarks_exported else R.string.bookmarks_export_failed)
        }
    }
    internal var onBrowserRoleResult: (() -> Unit)? = null
    internal val browserRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBrowserRoleResult?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BrowserPrefs(this)
        delegate.localNightMode = prefs.theme.nightMode
        super.onCreate(savedInstanceState)
        store = BrowserStore.get(this)
        BrowserSession.cleanupPreviousProcess()
        if (!BrowserDownloadService.running) store.write { interruptDownloads() }
        network = BrowserProxyNetwork.get(this)
        ui = BrowserUi(this, BrowserPalette(this))
        buildChrome()
        val saved = prefs.restoreTabs().map { BrowserTab(it.url, it.title, it.desktop, session = normalSession, keepAlive = it.keepAlive) }
        tabManager.restore(saved, prefs.selectedTab)
        updateTabCounter()
        initialIntent = if (savedInstanceState == null) externalInput(intent) else null
        proxyObservation = network.observe(proxyObserver)
        network.prepare { state ->
            if (!isDestroyed) {
                ready = true
                proxyObserver.accept(state)
                val external = initialIntent
                when {
                    external != null -> addTab(external, session = normalSession)
                    savedInstanceState != null -> showTab()
                    homeVisible -> Unit // Already rendered below.
                    else -> showTab()
                }
                initialIntent = null
            }
        }
        // Render the offline home immediately, while network setup completes.
        showHome()
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    internal fun str(@StringRes id: Int, vararg args: Any): String = getString(id, *args)
    private fun dp(value: Int) = ui.dp(value)
    internal fun domain(url: String): String = BrowserInput.display(url)
    internal fun displayTitle(target: BrowserTab) = target.title.ifBlank { if (target.url.isEmpty()) str(R.string.tab_new) else domain(target.url) }

    internal fun haptic(view: View? = null, constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (!::prefs.isInitialized || !prefs.haptics) return
        (view ?: window.decorView).performHapticFeedback(constant)
    }
    /** [source] is the tab that asked for the dialog; a background private page still hides its dialog. */
    internal fun browserDialog(source: BrowserTab? = tab): AlertDialog.Builder = object : AlertDialog.Builder(this) {
        override fun create(): AlertDialog = super.create().also {
            if (source?.isPrivate == true || isPrivateMode) it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    internal fun confirm(message: String, positive: String = str(R.string.action_confirm), destructive: Boolean = false, action: () -> Unit) {
        val dialog = browserDialog().setMessage(message).setPositiveButton(positive) { _, _ -> action() }.setNegativeButton(R.string.action_cancel, null).create()
        if (destructive) dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.danger) }
        dialog.show()
    }
    internal fun sheet(@StringRes title: Int, headerAction: ((BrowserSheet) -> View)? = null) =
        BrowserSheet(ui, root, str(title), { isPrivateMode }, headerAction)

    /**
     * The toolbar floats over the page. While it shows, the page area stops above it; to hide it, the
     * page first grows underneath and then the toolbar slides away, and the reverse to show it, so the
     * page is resized once per change and no empty strip ever appears.
     */
    private fun setChromeCollapsed(collapsed: Boolean) {
        if (collapsed == chromeCollapsed) return
        if (collapsed && (fullView != null || findBar.isVisible || address.hasFocus())) return
        chromeCollapsed = collapsed
        chrome.animate().cancel()
        if (collapsed) {
            applyContentInset()
            chrome.animate().translationY(chrome.height.toFloat()).setDuration(160)
                .withEndAction { if (chromeCollapsed) chrome.visibility = View.INVISIBLE }.start()
        } else {
            chrome.visibility = View.VISIBLE
            chrome.animate().translationY(0f).setDuration(160).withEndAction { if (!chromeCollapsed) applyContentInset() }.start()
        }
    }
    private fun expandChrome() = setChromeCollapsed(false)
    private fun applyContentInset() {
        val inset = if (chromeCollapsed) 0 else chrome.height
        val params = content.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != inset) { params.bottomMargin = inset; content.layoutParams = params }
    }

    internal fun applyInputPrivacy(input: EditText, private: Boolean) {
        val options = if (private) input.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            else input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        if (options != input.imeOptions) {
            input.imeOptions = options
            // A focused field keeps its old input connection until the keyboard restarts it.
            if (input.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(input)
        }
        input.importantForAutofill = if (private) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_AUTO
    }

    private fun attachSearchInput(input: EditText, overlayTop: () -> Int) {
        input.setSelectAllOnFocus(true)
        searchOverlay.attach(input)
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expandChrome()
                // Editing starts from the full address; the bar otherwise shows only the host.
                if (input === address && !homeVisible) tab?.url?.let { address.setText(it); address.selectAll() }
                searchOverlay.show(input, overlayTop())
                updateChrome()
            } else if (searchOverlay.input === input) hideSearch()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                navigate(input.text.toString(), searchOverlay.engine); true
            } else false
        }
        ui.onTextChanged(input) { if (input === address && address.hasFocus()) clearButton.isVisible = it.isNotEmpty() }
    }

    internal fun hideSearch() {
        val input = searchOverlay.input
        searchOverlay.hide()
        input?.clearFocus()
        address.clearFocus()
        hideKeyboard(input ?: address)
        clearButton.isVisible = false
        updateChrome()
    }

    internal fun findInPage() {
        val web = tab?.view ?: return
        expandChrome()
        addressRow.isVisible = false
        findBar.show(web)
        updateBackCallback()
    }
    internal fun hideFindInPage() {
        if (::findBar.isInitialized) findBar.hide()
    }

    private fun buildChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !palette.dark; isAppearanceLightNavigationBars = !palette.dark }
        refreshIcon = ui.tintedIcon(R.drawable.ic_refresh_24)!!
        stopIcon = ui.tintedIcon(R.drawable.ic_close_24)!!
        ghostIcon = ui.tintedIcon(R.drawable.ic_ghost_24, palette.accent)!!
        tabsIcon = ui.tintedIcon(R.drawable.ic_tabs_24)!!
        privateTabsIcon = ui.tintedIcon(R.drawable.ic_ghost_counter_24, palette.accent)!!

        root = FrameLayout(this).apply { setBackgroundColor(palette.base) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        content = FrameLayout(this)
        pageHost = FrameLayout(this)
        content.addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        searchOverlay = SearchOverlay(this)
        content.addView(searchOverlay.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        chrome = ui.column().apply { setBackgroundColor(palette.base) }
        root.addView(chrome, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        chrome.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) content.post { applyContentInset() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(palette.accent); indeterminateTintList = progressTintList; visibility = View.INVISIBLE
        }
        chrome.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        addressRow = ui.row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        val addressCapsule = ui.row().apply { background = ui.rounded(palette.surface); minimumHeight = dp(48) }
        addressProxyButton = ui.iconAction(R.drawable.ic_star_24, str(R.string.proxy_title)) { showProxy() }
        addressCapsule.addView(addressProxyButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2) })
        siteButton = ui.iconAction(R.drawable.ic_lock_24, str(R.string.site_info), palette.muted) {
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { SiteInfoPanel(this, it).show() }
        }.apply { setPadding(dp(12), dp(12), dp(4), dp(12)) }
        addressCapsule.addView(siteButton, LinearLayout.LayoutParams(dp(40), dp(48)))
        address = ui.edit("").apply {
            background = null; setPadding(0, 0, 0, 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            compoundDrawablePadding = dp(5)
            minimumHeight = dp(48)
        }
        addressCapsule.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        clearButton = ui.iconAction(R.drawable.ic_close_24, str(R.string.address_clear), palette.muted) { address.text.clear() }.apply { visibility = View.GONE }
        addressCapsule.addView(clearButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        refreshButton = ui.iconAction(R.drawable.ic_refresh_24, str(R.string.page_refresh)) {
            tab?.view?.let { if (it.progress < 100) it.stopLoading() else network.prepare { _ -> if (!isDestroyed) it.reload() } }
        }
        addressCapsule.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(addressCapsule, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(addressRow)
        attachSearchInput(address) { 0 }
        homePage = HomePage(this)
        attachSearchInput(homePage.input) { homePage.inputBottomIn(content) }

        findBar = FindBar(ui) {
            addressRow.isVisible = !homeVisible
            updateBackCallback()
        }
        chrome.addView(findBar.view, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
        bottomBar = SwipeBar(this, dp(16), dp(40)) { step ->
            tabManager.neighbor(step)?.let { haptic(bottomBar); tabManager.select(it); showTab() }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        backButton = ui.iconAction(R.drawable.ic_arrow_back_24, str(R.string.nav_back)) { onBackPressedDispatcher.onBackPressed() }
        forwardButton = ui.iconAction(R.drawable.ic_arrow_forward_24, str(R.string.nav_forward)) {
            when (BrowserNavigation.forward(homeVisible = homeVisible, canGoForward = tab?.view?.canGoForward() == true, hasPage = tab?.url?.isNotEmpty() == true)) {
                BrowserNavigationAction.SHOW_PAGE -> showTab()
                BrowserNavigationAction.WEB_FORWARD -> tab?.view?.goForward()
                else -> Unit
            }
        }
        listOf(backButton, forwardButton, ui.iconAction(R.drawable.ic_home_24, str(R.string.nav_home)) { home() }, tabCounterAction(), ui.iconAction(R.drawable.ic_more_24, str(R.string.nav_menu)) { MenuPanel(this).show() }).forEach {
            bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        chrome.addView(bottomBar)
        snackbar = BrowserSnackbar(ui, root) { if (chrome.isVisible && !chromeCollapsed) chrome.height else 0 }
        setContentView(root)
    }

    private fun tabCounterAction() = ui.asButton(FrameLayout(this)).apply {
        tabButtonHost = this
        minimumWidth = dp(48)
        minimumHeight = dp(50)
        foreground = ui.borderless()
        tabButtonIcon = ImageView(this@BrowserActivity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(tabButtonIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        tabButton = ui.label("", 9f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(tabButton, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { TabsPanel(this@BrowserActivity).show() }
    }

    /** Short in-app message with an optional action, placed above the toolbar. */
    internal fun showSnackbar(message: String, action: String? = null, onAction: (() -> Unit)? = null) = snackbar.show(message, action, onAction)

    private fun showHome() {
        homeVisible = true
        if (searchOverlay.isVisible) hideSearch()
        hideFindInPage()
        addressRow.isVisible = false
        progress.visibility = View.INVISIBLE
        progressShowing = false
        expandChrome()
        pageHost.removeAllViews()
        pageHost.addView(homePage.render(), FrameLayout.LayoutParams(-1, -1))
        updateChrome()
    }
    internal fun refreshHome() { if (homeVisible && !homePage.input.hasFocus()) showHome() }

    internal fun persist() {
        main.removeCallbacks(persistLater)
        val (ordinary, active) = tabManager.ordinarySnapshot()
        prefs.saveTabs(ordinary.map { SavedTab(it.url, it.title, it.desktop, it.keepAlive) }, active)
    }
    /** Coalesces the bursts of page callbacks into one write. */
    internal fun schedulePersist() { main.removeCallbacks(persistLater); main.postDelayed(persistLater, 500) }

    internal fun switchGroup(private: Boolean) {
        tabManager.groupTarget(private)?.let { tabManager.select(it); showTab(); return }
        if (!tabManager.canAdd) { toast(R.string.tabs_limit); return }
        addTab(session = if (private) obtainPrivateSession() ?: return else normalSession)
    }
    internal fun obtainPrivateSession(): BrowserSession? {
        BrowserSession.privateUnavailableReason()?.let { toast(it); return null }
        return privateSession ?: BrowserSession(true).also { privateSession = it }
    }
    private fun finishEmptyPrivateSession() {
        val session = privateSession ?: return
        if (tabs.any { it.session === session }) return
        privateSession = null
        tabManager.dropClosed(true)
        downloads.dropPrivatePending()
        session.end { success -> if (!success) toast(R.string.private_cleanup_retry) }
        if (themeRecreatePending && !isFinishing && !isDestroyed) {
            themeRecreatePending = false
            pageHost.post { if (!isDestroyed) { persist(); recreate() } }
        }
    }
    /** Keeps back/forward history in memory so an undone close restores it. */
    private fun retire(target: BrowserTab) {
        target.view?.let { web ->
            val state = Bundle()
            if (runCatching { web.saveState(state) != null }.getOrDefault(false)) {
                target.state = state
                target.restoreScroll = web.scrollX to web.scrollY
            }
        }
        release(target)
    }
    internal fun closeGroup(private: Boolean, except: BrowserTab? = null) {
        tabs.filter { it.isPrivate == private && it !== except }.forEach(::retire)
        tabManager.closeGroup(private, except)
        finishEmptyPrivateSession()
        showTab(); persist()
        tabsPanel?.render()
    }
    internal fun closeTab(index: Int, offerUndo: Boolean = false) {
        val closing = tabs[index]
        retire(closing)
        tabManager.close(index)
        finishEmptyPrivateSession()
        showTab(); persist()
        tabsPanel?.render()
        if (offerUndo && tabManager.lastClosed != null) {
            showSnackbar(str(R.string.tab_closed, displayTitle(closing).take(20)), str(R.string.action_undo)) { undoCloseTabs() }
        }
    }
    internal fun undoCloseTabs() {
        val placeholder = tabManager.lastClosed?.placeholder
        if (tabManager.undoClose() == null) { toast(R.string.tabs_limit); return }
        if (placeholder != null && placeholder !in tabs) release(placeholder)
        showTab(); persist()
        tabsPanel?.render()
    }
    private fun updateTabCounter() {
        if (!::tabButton.isInitialized) return
        val private = isPrivateMode
        val count = tabManager.count(private)
        tabButton.text = BrowserNavigation.tabCountLabel(count)
        tabButtonIcon.setImageDrawable(if (private) privateTabsIcon else tabsIcon)
        tabButton.setTextColor(if (private) palette.accent else palette.ink)
        tabButtonHost.contentDescription = str(if (private) R.string.tabs_counter_private else R.string.tabs_counter, count)
        tabButtonHost.tooltipText = tabButtonHost.contentDescription
    }
    private fun updateProxyButton() {
        val color = ColorStateList.valueOf(palette.proxy(proxyPhase))
        val description = str(R.string.proxy_button_description, proxyLabel())
        listOf(addressProxyButton, homePage.proxyButton).forEach { it.imageTintList = color; it.contentDescription = description; it.tooltipText = description }
    }
    internal fun proxyLabel() = str(when (proxyPhase) {
        ProxyPhase.RUNNING -> R.string.proxy_running
        ProxyPhase.STARTING -> R.string.proxy_starting
        ProxyPhase.RECONNECTING -> R.string.proxy_reconnecting_label
        ProxyPhase.FAILED -> R.string.proxy_failed
        ProxyPhase.OFF -> R.string.proxy_off
    })

    internal fun updateChrome() {
        val url = tab?.url.orEmpty()
        val private = isPrivateMode
        val hint = searchOverlay.hint(private)
        if (searchOverlay.input !== address) address.hint = hint
        address.contentDescription = str(if (private) R.string.address_private else R.string.address)
        applyInputPrivacy(address, private)
        if (private) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val editing = address.hasFocus()
        if (!editing) address.setText(if (homeVisible) "" else BrowserInput.display(url))
        // Private mode has its own ghost, so plain HTTP never looks secure; the lock opens site details.
        address.setCompoundDrawablesWithIntrinsicBounds(if (private) ghostIcon else null, null, null, null)
        val secure = url.startsWith("https://", ignoreCase = true)
        siteButton.isVisible = !editing && !homeVisible && BrowserInput.isWebUrl(url)
        siteButton.setImageDrawable(ui.tintedIcon(if (secure) R.drawable.ic_lock_24 else R.drawable.ic_info_24, palette.muted))
        siteButton.contentDescription = str(if (secure) R.string.site_info_secure else R.string.site_info_insecure)
        siteButton.tooltipText = siteButton.contentDescription
        updateTabCounter()
        val canForward = (homeVisible && url.isNotEmpty()) || (!homeVisible && tab?.view?.canGoForward() == true)
        setNavEnabled(forwardButton, canForward)
        val closable = BrowserNavigation.canCloseOnBack(private, tabManager.count(private))
        setNavEnabled(backButton, !homeVisible && (url.isNotEmpty() || closable))
        progressShowing = null
        updateProgress()
        updateBackCallback()
    }
    private fun setNavEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else .3f
    }
    /**
     * With nothing to go back to, the system handles back itself (Android 12+ moves the browser to the
     * background and shows the predictive-back animation); older systems are sent to the background here.
     */
    private fun updateBackCallback() {
        if (!::findBar.isInitialized) return
        val handled = fullView != null || findBar.isVisible || searchOverlay.isVisible || address.hasFocus() ||
            homePage.input.hasFocus() || !homeVisible
        backCallback.isEnabled = handled || Build.VERSION.SDK_INT < 31
    }
    /** Progress-only refresh; called for every progress tick, so it avoids the rest of the chrome. */
    internal fun updateProgress() {
        val current = tab
        val url = current?.url.orEmpty()
        val waiting = current?.waiting == true
        val loading = current?.view?.progress ?: 100
        val showing = !homeVisible && url.isNotEmpty() && (waiting || loading < 100)
        progress.isIndeterminate = waiting
        if (!waiting) progress.progress = loading
        if (showing == progressShowing) return
        progressShowing = showing
        progress.visibility = if (showing) View.VISIBLE else View.INVISIBLE
        refreshButton.setImageDrawable(if (showing) stopIcon else refreshIcon)
        refreshButton.contentDescription = str(if (showing) R.string.page_stop else R.string.page_refresh)
        refreshButton.tooltipText = refreshButton.contentDescription
    }
    private fun hideKeyboard(view: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0) }
    private fun home() {
        hideFindInPage()
        hideSearch()
        showHome(); persist()
    }
    internal fun navigate(input: String, engine: String = prefs.engine) {
        val url = BrowserInput.resolve(input, engine)
        if (url.isEmpty()) return
        hideFindInPage()
        hideSearch()
        val target = tab ?: return
        if (BrowserNavigation.shouldCreateNewTabFromHome(homeVisible, target.url.isNotEmpty())) addTab(url)
        else load(target, url)
    }
    internal fun load(target: BrowserTab, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        target.url = url
        target.state = null
        target.restoreScroll = null
        if (tab === target) {
            homeVisible = false
            showWaiting(target)
        }
        if (!ready) { initialIntent = null; return }
        target.waiting = true
        updateProgress()
        network.prepare {
            if (!isDestroyed && BrowserNavigation.shouldApplyPreparedNavigation(target in tabs, target.url, url)) {
                target.waiting = false
                val view = target.view ?: createWebView(target).also { target.view = it }
                view.loadUrl(url)
                if (BrowserNavigation.shouldShowPageAfterPreparation(tab === target, homeVisible)) showTab(false)
                persist()
            }
        }
    }
    /**
     * Shows the requested address right away. A tab without a page yet gets an empty surface, saying
     * so when the proxy is still connecting, until the network is ready to load it.
     */
    private fun showWaiting(target: BrowserTab) {
        if (searchOverlay.isVisible) hideSearch()
        hideFindInPage()
        expandChrome()
        addressRow.isVisible = true
        if (target.view == null) {
            pageHost.removeAllViews()
            val connecting = proxyPhase == ProxyPhase.STARTING || proxyPhase == ProxyPhase.RECONNECTING
            val placeholder = ui.column().apply {
                gravity = Gravity.CENTER
                setBackgroundColor(palette.surface)
                if (connecting) addView(ui.label(str(R.string.proxy_waiting), 14f, palette.muted).apply { gravity = Gravity.CENTER })
            }
            pageHost.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        }
        updateChrome()
    }
    internal fun addTab(url: String = "", activate: Boolean = true, session: BrowserSession = tab?.session ?: normalSession): BrowserTab? {
        val newTab = tabManager.add(BrowserTab(session = session), activate) ?: run { toast(R.string.tabs_limit); return null }
        if (activate) showTab() else updateTabCounter()
        tabsPanel?.render()
        if (url.isNotEmpty()) {
            val resolved = BrowserInput.resolve(url, prefs.engine)
            if (resolved.isNotEmpty()) { if (activate) navigate(resolved) else load(newTab, resolved) }
        }
        persist()
        return newTab
    }
    private fun release(target: BrowserTab) {
        if (findBar.web === target.view) hideFindInPage()
        target.view?.let { target.session.release(it) }
        target.view = null
    }
    internal fun selectTab(target: BrowserTab) { tabManager.select(target); showTab() }
    internal fun showTab(load: Boolean = true) {
        hideFullScreen()
        if (searchOverlay.isVisible) hideSearch()
        hideFindInPage()
        homeVisible = false
        expandChrome()
        addressRow.isVisible = true
        val target = tab ?: return
        // Detach background views without blanket onPause(), which can interrupt media.
        target.touched = SystemClock.elapsedRealtime()
        pageHost.removeAllViews()
        if (target.url.isEmpty()) showHome()
        else {
            val fresh = target.view == null
            val web = target.view ?: createWebView(target).also { target.view = it }
            (web.parent as? ViewGroup)?.removeView(web)
            pageHost.addView(web, FrameLayout.LayoutParams(-1, -1)); web.onResume()
            if (fresh && load) {
                target.waiting = true
                network.prepare {
                    if (!isDestroyed && target in tabs && target.view === web) {
                        target.waiting = false
                        if (target.state?.let { web.restoreState(it) } == null) web.loadUrl(target.url)
                        target.state = null
                        if (tab === target) updateProgress()
                    }
                }
            }
            updateChrome()
        }
        trimTabs(currentMemoryPressure())
        persist()
    }
    internal fun toggleDesktop(target: BrowserTab) {
        target.desktop = !target.desktop
        target.view?.let {
            it.settings.userAgentString = if (target.desktop) desktopUserAgent else null
            it.reload()
        }
        persist()
    }

    private fun currentMemoryPressure(): TabMemoryPolicy.Pressure {
        val info = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return TabMemoryPolicy.pressure(info.lowMemory, info.availMem, info.threshold, info.totalMem,
            runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory())
    }

    /** Probe one candidate at a time; uncertain or stale answers must never discard a page. */
    @VisibleForTesting internal fun trimTabs(pressure: TabMemoryPolicy.Pressure) {
        if (trimming || isDestroyed) return
        val limit = TabMemoryPolicy.batchSize(tabs.count { it.view != null }, pressure)
        if (limit == 0) return
        val candidates = java.util.ArrayDeque(tabs.filter { it.view != null && it !== tab }.sortedBy { it.touched })
        trimming = true
        var released = 0
        fun next() {
            if (isDestroyed || released >= limit || candidates.isEmpty()) { trimming = false; return }
            val candidate = candidates.removeFirst()
            val web = candidate.view
            val touched = candidate.touched
            val document = candidate.documentVersion
            fun eligible(): Boolean = web != null && candidate.view === web && tabs.any { it === candidate } &&
                candidate.touched == touched && candidate.documentVersion == document &&
                TabMemoryPolicy.eligible(candidate === tab, candidate.keepAlive, web.progress < 100,
                    SystemClock.elapsedRealtime() - touched, pressure)
            if (!eligible()) { next(); return }
            var answered = false
            val timeout = Runnable { if (!answered) { answered = true; next() } }
            main.postDelayed(timeout, 750)
            try {
                web!!.evaluateJavascript(protectionProbe) { result ->
                    if (answered) return@evaluateJavascript
                    answered = true
                    main.removeCallbacks(timeout)
                    val safe = runCatching { org.json.JSONObject(result).optBoolean("reclaimable", false) }.getOrDefault(false)
                    if (!isDestroyed && safe && eligible()) {
                        val state = Bundle()
                        if (runCatching { web.saveState(state) != null }.getOrDefault(false)) {
                            candidate.state = state
                            candidate.restoreScroll = web.scrollX to web.scrollY
                            release(candidate)
                            released++
                        }
                    }
                    next()
                }
            } catch (_: RuntimeException) {
                if (!answered) { answered = true; main.removeCallbacks(timeout); next() }
            }
        }
        next()
    }

    /**
     * Background keep-alive runs only when something needs it: the proxy, playing media or the user's
     * "keep running" setting. Starting is only attempted while visible, as Android requires.
     */
    internal fun syncKeepAlive() {
        if (!::prefs.isInitialized) return
        val wanted = prefs.keepBackground || proxyPhase != ProxyPhase.OFF || mediaPlaying
        val intent = Intent(this, BrowserKeepAliveService::class.java)
        if (wanted && !BrowserKeepAliveService.running && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { ContextCompat.startForegroundService(this, intent) }
        } else if (!wanted && BrowserKeepAliveService.running) stopService(intent)
    }

    /** Asks live pages whether audio or video is playing; [done] runs after every page answered. */
    private fun probeMedia(done: (() -> Unit)? = null) {
        val live = tabs.mapNotNull { it.view }
        if (live.isEmpty()) { mediaPlaying = false; syncKeepAlive(); done?.invoke(); return }
        var waiting = live.size
        var playing = false
        live.forEach { web ->
            runCatching {
                web.evaluateJavascript(MEDIA_PROBE) { result ->
                    if (result == "true") playing = true
                    if (--waiting == 0 && !isDestroyed) { mediaPlaying = playing; syncKeepAlive(); done?.invoke() }
                }
            }.onFailure { if (--waiting == 0 && !isDestroyed) { mediaPlaying = playing; syncKeepAlive(); done?.invoke() } }
        }
    }

    /** Lets pages without their own dark theme be darkened while the browser is dark. */
    internal fun applyPageDarkening(web: WebView? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val allowed = palette.dark && prefs.darkPages
        (web?.let(::listOf) ?: tabs.mapNotNull { it.view }).forEach { WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, allowed) }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> TabMemoryPolicy.Pressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_MODERATE -> TabMemoryPolicy.Pressure.MODERATE
            else -> currentMemoryPressure() // UI_HIDDEN alone does not mean memory is low.
        }
        trimTabs(pressure)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = when (prefs.theme) {
            BrowserPrefs.Theme.LIGHT -> false
            BrowserPrefs.Theme.DARK -> true
            BrowserPrefs.Theme.SYSTEM -> newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        if (night == palette.dark) return
        // Recreating ends the private session, so keep current colors until its last tab closes.
        if (privateSession == null) { persist(); recreate() } else themeRecreatePending = true
    }

    /** Applies a theme from settings; recreating ends any private session, so callers confirm first. */
    internal fun applyTheme(theme: BrowserPrefs.Theme) { prefs.theme = theme; persist(); recreate() }

    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimTabs(TabMemoryPolicy.Pressure.CRITICAL) }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun createWebView(target: BrowserTab): WebView = target.session.createWebView(this).apply {
        setBackgroundColor(palette.surface)
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true; setSupportMultipleWindows(true)
            // Untapped pop-ups reach onCreateWindow so they can be blocked with a notice instead of silently.
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = prefs.textZoom
            if (target.desktop) userAgentString = desktopUserAgent
        }
        applyPageDarkening(this)
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (tab !== target || target.url.isEmpty() || fullView != null || findBar.isVisible) return@setOnScrollChangeListener
            val dy = scrollY - oldScrollY
            if (dy > 30 && scrollY > dp(60)) setChromeCollapsed(true)
            else if (dy < -30 || scrollY < dp(20)) setChromeCollapsed(false)
        }
        target.session.cookies().setAcceptCookie(true)
        target.session.cookies().setAcceptThirdPartyCookies(this, false)
        webViewClient = PageClient(this@BrowserActivity, target)
        webChromeClient = PageChromeClient(this@BrowserActivity, target)
        setDownloadListener { url, ua, disposition, mime, _ -> downloads.download(target, url, ua, disposition, mime) }
        setOnLongClickListener { pages.longPress(target, this) }
    }

    internal fun showPageError(title: String, message: String) {
        hideFindInPage()
        expandChrome()
        tab?.waiting = false
        addressRow.isVisible = true
        pageHost.removeAllViews()
        val panel = ui.column().apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(24), dp(30), dp(24)) }
        panel.addView(ui.label(title, 22f).apply { gravity = Gravity.CENTER })
        if (message.isNotEmpty()) panel.addView(ui.label(message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        panel.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))
        val actions = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        actions.addView(ui.textAction(str(R.string.action_retry), true) { retryPage() }, LinearLayout.LayoutParams(0, -2, 1f))
        if (proxyPhase == ProxyPhase.FAILED || proxyPhase == ProxyPhase.STARTING || proxyPhase == ProxyPhase.RECONNECTING) {
            actions.addView(ui.textAction(str(R.string.error_check_proxy)) { showProxy() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        }
        if (BrowserInput.isWebUrl(tab?.url.orEmpty())) {
            actions.addView(ui.textAction(str(R.string.error_copy_url)) { copy(tab?.url.orEmpty()) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(ui.textAction(str(R.string.error_go_home)) { home() }.apply { setTextColor(palette.muted) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        pageHost.addView(panel, FrameLayout.LayoutParams(-1, -1))
        progress.visibility = View.INVISIBLE
        progressShowing = false
    }
    /** Reloads in the same WebView, keeping the tab's back/forward history. */
    private fun retryPage() {
        val target = tab ?: return
        val web = target.view ?: run { showTab(); return }
        (web.parent as? ViewGroup)?.removeView(web)
        pageHost.removeAllViews()
        pageHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        network.prepare {
            if (!isDestroyed && target.view === web) { if (web.url == target.url) web.reload() else web.loadUrl(target.url) }
        }
        updateChrome()
    }
    internal fun showFullScreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullView != null) { callback.onCustomViewHidden(); return }
        fullView = view; fullCallback = callback; root.visibility = View.GONE
        (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        updateBackCallback()
    }
    internal fun hideFullScreen() {
        val wasFull = fullView != null
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        if (!wasFull) return
        root.visibility = View.VISIBLE
        expandChrome()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        updateBackCallback()
    }

    internal fun showProxy() = ProxyPanel(this).show()

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
    private fun externalInput(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf(BrowserInput::isWebUrl)
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
        else -> null
    }?.trim()?.take(8192)?.ifEmpty { null }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        externalInput(intent)?.let { tabsPanel?.dismiss(); if (ready) addTab(it, session = normalSession) else initialIntent = it }
    }
    override fun onStart() {
        super.onStart()
        main.post(memoryCheck)
        syncKeepAlive()
    }
    override fun onStop() { main.removeCallbacks(memoryCheck); super.onStop() }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() {
        if (::prefs.isInitialized) { persist(); CookieManager.getInstance().flush() }
        if (!BrowserKeepAliveService.running) {
            tabs.forEach { it.view?.onPause() }
            // Media that started since the last check still gets to keep playing.
            probeMedia { if (BrowserKeepAliveService.running) tabs.forEach { it.view?.onResume() } }
        }
        super.onPause()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); permissions.release()
        proxyObservation?.close()
        proxyObservation = null
        proxyPanel?.dismiss()
        main.removeCallbacksAndMessages(null)
        if (::searchOverlay.isInitialized) searchOverlay.destroy()
        tabs.forEach(::release); tabManager.tabs.clear(); finishEmptyPrivateSession(); normalSession.end(); super.onDestroy()
    }

    private companion object {
        const val MEDIA_PROBE = "(() => { try { return Array.from(document.querySelectorAll('audio,video')).some(m => !m.paused && !m.ended); } catch (_) { return false; } })()"
    }
}

/** Toolbar that switches tabs on a horizontal swipe while keeping its buttons tappable. */
@SuppressLint("ViewConstructor") // Built in code only.
private class SwipeBar(context: Context, private val slop: Int, private val distance: Int, private val onSwipe: (Int) -> Unit) : LinearLayout(context) {
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(ev.y - startY) * 1.5f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return swiping
    }
    @SuppressLint("ClickableViewAccessibility") // Swiping is a shortcut; the tab list remains the accessible path.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> if (swiping) {
                val dx = ev.x - startX
                when {
                    dx > distance -> onSwipe(-1)
                    dx < -distance -> onSwipe(1)
                }
                swiping = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return super.onTouchEvent(ev)
    }
}
