package app.xvi.web

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.*
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One browser tab. Ordinary tabs persist their address; private tabs live only in memory. */
internal class BrowserTab(
    var url: String = "", var title: String = "", var desktop: Boolean = false,
    var view: WebView? = null, var state: Bundle? = null, var touched: Long = 0,
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
    var dialogsBlocked: Long = -1, var dialogDocument: Long = -1, var dialogCount: Int = 0
) : TabManager.Entry {
    override val isPrivate get() = session.isPrivate
    override val isBlank get() = url.isEmpty() && view == null
    /** The AI assistant's conversation about the page showing when it was opened. */
    var ai: AiConversation? = null
    /** Whether ads are blocked for the page; read on WebView's network thread. */
    @Volatile var adblock = true
    /** Requests blocked on the current page. */
    val blocked = java.util.concurrent.atomic.AtomicInteger()
}

/** What went wrong loading a page, with an optional extra action (label to action) for the error screen. */
internal class PageError(val title: String, val message: String, val action: Pair<String, () -> Unit>? = null)

/** A closed tab that can be reopened from the tab panel; [tab] keeps its history while still in memory. */
internal class ClosedTab(val tab: BrowserTab, val time: Long = System.currentTimeMillis())

/** Native browser chrome. Untrusted pages never receive an Android JavaScript bridge. */
class BrowserActivity : PageHost() {
    internal var privateSession: BrowserSession? = null
    internal val tabManager = TabManager { BrowserTab(session = normalSession) }
    override val tabs: List<BrowserTab> get() = tabManager.tabs
    override val tab: BrowserTab? get() = tabManager.current
    override val isPrivateMode get() = tabManager.isPrivateMode
    private var themeRecreatePending = false

    internal val pageTools = PageTools(this)
    internal val backups = Backups(this)
    internal val aiConfig by lazy { AiConfig(this) }
    internal val assistant = AiPanel(this)
    internal lateinit var unlock: DeviceUnlock
    /** Private tabs need the phone's unlock before they show again; see [BrowserPrefs.lockPrivate]. */
    private var privateLocked = false
    /** Hides a private tab while the unlock is asked for. */
    private var lockCover: View? = null
    /** Gives up on the unlock instead of letting back act on what the cover hides. */
    private val lockBack = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { leavePrivateLock(deliberate = true) } }
    /** Set while something this window started is due to answer, so coming back from it does not lock. */
    private var awaitingResult = false
    /** Most recent first; private entries disappear with their session. */
    internal val recentlyClosed = ArrayDeque<ClosedTab>()

    private lateinit var content: FrameLayout
    internal lateinit var pageArea: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    /** Says why the page has not started loading yet, next to the progress bar, with a way out. */
    private lateinit var waitingRow: LinearLayout
    private lateinit var waitingStop: TextView
    private lateinit var tabButton: TextView
    private lateinit var tabButtonIcon: ImageView
    private lateinit var tabButtonHost: View
    private lateinit var siteButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var scanButton: ImageButton
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
    private lateinit var secureIcon: Drawable
    private lateinit var insecureIcon: Drawable
    /** Last secure-window state applied. */
    private var secureWindow: Boolean? = null
    /** Host of the page in front for [isSecure]; address parsing is not free and this runs per event. */
    private var hostMemo: Pair<String, String?>? = null
    internal var tabsPanel: TabsPanel? = null
    internal var proxyPanel: ProxyPanel? = null
    private var chromeCollapsed = false
    private var progressShowing: Boolean? = null
    private var keyboardShown = false
    internal var homeVisible = true
        private set
    @VisibleForTesting internal var ready = false
    private var initialIntent: String? = null
    @VisibleForTesting internal var trimming = false
    private val protectionProbe by lazy { assets.open("tab-protection.js").bufferedReader().use { it.readText() } }
    private val memoryCheck = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            trimTabs(currentMemoryPressure())
            media.probe(allPages = media.everSawMedia)
            main.postDelayed(this, 15_000)
        }
    }
    private val persistLater = Runnable { if (!isDestroyed) persist() }
    override val showsWaitingReason = true
    override fun onProxyChanged(state: BrowserProxyNetwork.Status) {
        homePage.updateProxy()
        updateWaitingRow()
        proxyPanel?.render(state)
    }
    /** Innermost interface layer that is open, which a back press closes before the page sees it. */
    private fun openLayer() = PageShell.topLayer(
        fullScreen = fullView != null,
        reader = pageTools.readerVisible,
        findInPage = findBar.isVisible,
        searchOverlay = searchOverlay.isVisible,
        addressFocused = address.hasFocus(),
        homeSearchFocused = homePage.input.hasFocus()
    )

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (openLayer()) {
                PageShell.Layer.FULLSCREEN -> hideFullScreen()
                PageShell.Layer.READER -> pageTools.closeReader()
                PageShell.Layer.FIND_IN_PAGE -> hideFindInPage()
                PageShell.Layer.SEARCH -> hideSearch()
                PageShell.Layer.NONE -> when (BrowserNavigation.back(
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

    internal val bookmarkImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(store.io) {
                runCatching { requireNotNull(contentResolver.openInputStream(uri)).use(store::importBookmarks) }
            }
            toast(result.fold({ str(R.string.bookmarks_imported, it) }, { str(R.string.bookmarks_import_failed) }))
            refreshHome()
        }
    }
    internal val bookmarkExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val html = withContext(store.io) { store.exportBookmarks() }
            val result = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(html) } }
            }
            toast(if (result.isSuccess) R.string.bookmarks_exported else R.string.bookmarks_export_failed)
        }
    }
    /** Opens a scanned address, or offers to search or copy other text. */
    internal val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(ScannerActivity.EXTRA_TEXT)?.let(BrowserInput::typed) ?: return@registerForActivityResult
        if (BrowserInput.directUrl(text) != null && !text.any(Char::isWhitespace)) { navigate(text); return@registerForActivityResult }
        browserDialog().setTitle(R.string.scan_result_title).setMessage(text)
            .setPositiveButton(R.string.scan_result_search) { _, _ -> navigate(text) }
            .setNeutralButton(R.string.link_copy_text) { _, _ -> copy(text) }
            .setNegativeButton(R.string.action_cancel, null).show()
    }
    internal fun scan() {
        try { scanner.launch(Intent(this, ScannerActivity::class.java)) } catch (_: ActivityNotFoundException) { toast(R.string.scan_camera_failed) }
    }
    internal var onBrowserRoleResult: (() -> Unit)? = null
    internal val browserRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBrowserRoleResult?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlock = DeviceUnlock(this)
        buildChrome()
        // A quit that ended the process before its cleanup finished is made up for before anything shows.
        if (savedInstanceState == null && prefs.exitCleanupPending) ExitCleanup.run(this, prefs, store, normalSession)
        prefs.exitCleanupPending = prefs.clearOnExit.isNotEmpty()
        val saved = prefs.restoreTabs().map { BrowserTab(it.url, it.title, it.desktop, session = normalSession, keepAlive = it.keepAlive) }
        prefs.restoreClosed().forEach { recentlyClosed.addLast(ClosedTab(BrowserTab(it.url, it.title, it.desktop, session = normalSession), it.time)) }
        tabManager.restore(saved, prefs.selectedTab)
        updateTabCounter()
        initialIntent = if (savedInstanceState == null) externalInput(intent) else null
        watchProxy()
        network.prepare { state ->
            if (!isDestroyed) {
                ready = true
                proxyObserver.accept(state)
                if (prefs.adblock) AdBlock.update(this, network.proxy(), force = false)
                UserScripts.update(this, network.proxy(), force = false)
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

    /**
     * True once [buildChrome] has run. Every method that can be reached from a page callback or a
     * lifecycle callback checks this one flag instead of asking whether each view is initialized,
     * which is what made the ordering inside [buildChrome] load-bearing.
     */
    private val chromeReady get() = ::snackbar.isInitialized

    internal fun sheet(@StringRes title: Int, headerAction: ((BrowserSheet) -> View)? = null) =
        BrowserSheet(ui, root, str(title), { isPrivateMode }, headerAction)
    /** Panel about [source]'s page; it stays out of screenshots while that page, or the browser, is private. */
    internal fun sheet(title: String, source: BrowserTab, headerAction: ((BrowserSheet) -> View)? = null) =
        BrowserSheet(ui, root, title, { source.isPrivate || isPrivateMode }, headerAction)

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
    private val applyInset = Runnable { applyContentInset() }
    private fun applyContentInset() {
        val inset = if (chromeCollapsed) 0 else chrome.height
        val params = content.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != inset) { params.bottomMargin = inset; content.layoutParams = params }
    }

    private fun attachSearchInput(input: EditText, overlayTop: () -> Int) {
        input.setSelectAllOnFocus(true)
        searchOverlay.attach(input)
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expandChrome()
                // Editing starts from the full address; the bar otherwise shows only the host.
                if (input === address && !homeVisible) tab?.url?.let { address.setText(it); address.selectAll() }
                // Keep the page visible while its address is being edited; suggestions appear once it changes.
                searchOverlay.show(input, overlayTop(), showSuggestions = input !== address || homeVisible)
                syncAddressActions()
                homePage.syncActions()
                updateChrome()
                // The first tap can focus the field without opening the IME on some devices.
                // Request it after the focus-driven toolbar update has settled.
                input.post {
                    if (input.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            } else if (searchOverlay.input === input) hideSearch()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                navigate(input.text.toString(), searchOverlay.engine); true
            } else false
        }
        ui.onTextChanged(input) { if (input === address) syncAddressActions() }
    }

    internal fun hideSearch() {
        // Reachable from the window insets listener, which can fire while the chrome is still being built.
        if (!chromeReady) return
        val input = searchOverlay.input
        searchOverlay.hide()
        input?.clearFocus()
        address.clearFocus()
        hideKeyboard(input ?: address)
        syncAddressActions()
        homePage.syncActions()
        updateChrome()
    }

    /** While editing, clear and scan share the refresh/stop slot: clear for typed text, scan when empty. */
    private fun syncAddressActions() {
        val editing = address.hasFocus()
        clearButton.fadeVisible(editing && address.text.isNotEmpty())
        scanButton.fadeVisible(editing && address.text.isEmpty())
    }

    internal fun findInPage() {
        val web = tab?.view ?: return
        expandChrome()
        addressRow.isVisible = false
        findBar.show(web)
        updateBackCallback()
    }
    internal fun hideFindInPage() {
        if (chromeReady) findBar.hide()
    }

    private fun buildChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
        refreshIcon = ui.tintedIcon(R.drawable.ic_refresh_24)!!
        stopIcon = ui.tintedIcon(R.drawable.ic_close_24)!!
        ghostIcon = ui.tintedIcon(R.drawable.ic_ghost_24, palette.accent)!!
        tabsIcon = ui.tintedIcon(R.drawable.ic_tabs_24)!!
        privateTabsIcon = ui.tintedIcon(R.drawable.ic_ghost_counter_24, palette.accent)!!
        secureIcon = ui.tintedIcon(R.drawable.ic_lock_24, palette.muted)!!
        insecureIcon = ui.tintedIcon(R.drawable.ic_info_24, palette.muted)!!

        root = FrameLayout(this).apply {
            setBackgroundColor(palette.base)
            // When a focused page is removed (tab switch, new window, reader closed), focus lands here
            // instead of on the address field, which would open the search overlay by itself.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // Closing the keyboard also ends address editing, whether suggestions are visible or not.
            val imeShown = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboardShown && !imeShown && searchOverlay.input != null) hideSearch()
            keyboardShown = imeShown
            // The padding already makes room for the bars and the keyboard. Passing the full insets on
            // would let WebView shrink its visual viewport by the keyboard a second time, and pages that
            // watch it (Gemini) read the jump as the keyboard closing and drop focus.
            insets.inset(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        }
        content = FrameLayout(this)
        pageArea = FrameLayout(this)
        content.addView(pageArea, ui.full())
        content.addView(buildPullIndicator(), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        searchOverlay = SearchOverlay(this)
        content.addView(searchOverlay.view, ui.full())
        root.addView(content, ui.full())

        chrome = ui.column().apply { setBackgroundColor(palette.base) }
        root.addView(chrome, ui.anchored(Gravity.BOTTOM))
        chrome.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // One reusable runnable, removed before reposting, so a burst of layout passes cannot queue up.
            if (bottom - top != oldBottom - oldTop) { content.removeCallbacks(applyInset); content.post(applyInset) }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(palette.accent); indeterminateTintList = progressTintList; visibility = View.INVISIBLE
        }
        chrome.addView(progress, ui.bar(2))
        waitingStop = ui.asButton(ui.medium(ui.label(str(R.string.waiting_stop), 14f, palette.accent))).apply {
            gravity = Gravity.CENTER
            background = ui.selectable(10)
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = str(R.string.page_stop)
            setOnClickListener { tab?.let(::stopOrReload) }
        }
        waitingRow = ui.row().apply {
            setPadding(dp(20), 0, dp(6), 0)
            visibility = View.GONE
            addView(ui.label(str(R.string.proxy_waiting), 13f, palette.muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, ui.weighted())
            addView(waitingStop, ui.sized(ui.wrap(), ui.wrap()))
        }
        chrome.addView(waitingRow, ui.wide())

        addressRow = ui.row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        // While not editing, a sideways swipe on the address switches tabs, as on the bottom toolbar.
        val addressCapsule = SwipeBar(this, dp(16), dp(40), { !address.hasFocus() }) { swipeTab(it) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(palette.surface)
            minimumHeight = dp(48)
        }
        siteButton = ui.iconAction(R.drawable.ic_lock_24, str(R.string.site_info), palette.muted) {
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { SiteInfoPanel(this, it).show() }
        }
        addressCapsule.addView(siteButton, ui.box(48, 48))
        address = ui.edit("").apply {
            background = null; setPadding(0, 0, 0, 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            compoundDrawablePadding = dp(5)
            minimumHeight = dp(48)
        }
        addressCapsule.addView(address, ui.weighted())
        clearButton = ui.iconAction(R.drawable.ic_cancel_24, str(R.string.address_clear), palette.muted) { address.text.clear() }.apply { visibility = View.GONE }
        addressCapsule.addView(clearButton, ui.tappable())
        scanButton = ui.iconAction(R.drawable.ic_qr_24, str(R.string.scan_title), palette.muted) { hideSearch(); scan() }.apply { visibility = View.GONE }
        addressCapsule.addView(scanButton, ui.tappable())
        refreshButton = ui.iconAction(R.drawable.ic_refresh_24, str(R.string.page_refresh)) { tab?.let(::stopOrReload) }
        addressCapsule.addView(refreshButton, ui.tappable())
        addressRow.addView(addressCapsule, ui.wide())
        chrome.addView(addressRow)
        attachSearchInput(address) { 0 }
        homePage = HomePage(this).apply { updateProxy() }
        attachSearchInput(homePage.input) { homePage.inputBottomIn(content) }

        findBar = FindBar(ui) {
            addressRow.isVisible = !homeVisible
            updateBackCallback()
        }
        chrome.addView(findBar.view, ui.wide().apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
        bottomBar = SwipeBar(this, dp(16), dp(40)) { swipeTab(it) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        backButton = ui.iconAction(R.drawable.ic_arrow_back_24, str(R.string.nav_back)) { onBackPressedDispatcher.onBackPressed() }
        forwardButton = ui.iconAction(R.drawable.ic_arrow_forward_24, str(R.string.nav_forward)) {
            when (forwardAction()) {
                BrowserNavigationAction.SHOW_PAGE -> showTab()
                BrowserNavigationAction.WEB_FORWARD -> tab?.view?.goForward()
                else -> Unit
            }
        }
        backButton.setOnLongClickListener { showHistory(it, back = true) }
        forwardButton.setOnLongClickListener { showHistory(it, back = false) }
        val homeButton = ui.iconAction(R.drawable.ic_home_24, str(R.string.nav_home)) { home() }
        val menuButton = ui.iconAction(R.drawable.ic_more_24, str(R.string.nav_menu)) { MenuPanel(this).show() }
        listOf(backButton, forwardButton, homeButton, tabCounterAction(), menuButton).forEach {
            bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        chrome.addView(bottomBar)
        snackbar = BrowserSnackbar(ui, root) { if (chrome.isVisible && !chromeCollapsed) chrome.height else 0 }
        setContentView(root)
    }

    /** The pages behind (or ahead of) the current one, nearest first, so several steps take one tap. */
    private fun showHistory(anchor: View, back: Boolean): Boolean {
        val target = tab?.takeIf { !homeVisible } ?: return false
        val web = target.view ?: return false
        val history = web.copyBackForwardList()
        val current = history.currentIndex
        val indices = (if (back) (current - 1 downTo 0) else (current + 1 until history.size)).take(MAX_HISTORY_ITEMS)
        if (indices.isEmpty()) return false
        haptic(anchor, HapticFeedbackConstants.LONG_PRESS)
        val actions = indices.map { index ->
            val item = history.getItemAtIndex(index)
            val label = item.title.orEmpty().ifBlank { BrowserInput.display(item.url.orEmpty()) }
            label to {
                if (tab === target && target.view === web) {
                    pageTools.closeReader()
                    web.goBackOrForward(index - current)
                }
            }
        }
        actionsDialog(str(if (back) R.string.nav_back else R.string.nav_forward), actions, target)
        return true
    }

    private fun tabCounterAction() = ui.asButton(FrameLayout(this)).apply {
        tabButtonHost = this
        minimumWidth = dp(48)
        minimumHeight = dp(50)
        foreground = ui.borderless()
        tabButtonIcon = ImageView(this@BrowserActivity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(tabButtonIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        tabButton = ui.medium(ui.label("", 9f)).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(tabButton, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { TabsPanel(this@BrowserActivity).show() }
    }

    /** Short in-app message with an optional action, placed above the toolbar. */
    override fun showSnackbar(message: String, action: String?, onAction: (() -> Unit)?) = snackbar.show(message, action, onAction)

    /** Ends address editing and find-in-page, then brings the toolbar back before showing something else. */
    private fun clearPageOverlays() {
        if (searchOverlay.input != null) hideSearch()
        hideFindInPage()
        expandChrome()
    }

    override fun showsPage(target: BrowserTab) = tab === target && !homeVisible
    override fun attachPage(web: View) {
        (web.parent as? ViewGroup)?.removeView(web)
        pageArea.removeAllViews()
        pageArea.addView(web, ui.full())
    }

    private fun showHome() {
        pageTools.closeReader()
        homeVisible = true
        clearPageOverlays()
        addressRow.isVisible = false
        hideProgress()
        attachPage(homePage.render())
        updateChrome()
    }
    internal fun refreshHome() { if (homeVisible && !homePage.input.hasFocus()) showHome() }

    internal fun persist() {
        main.removeCallbacks(persistLater)
        val (ordinary, active) = tabManager.ordinarySnapshot()
        prefs.saveTabs(ordinary.map { SavedTab(it.url, it.title, it.desktop, it.keepAlive) }, active)
        prefs.saveClosed(recentlyClosed.filter { !it.tab.isPrivate }.map { SavedTab(it.tab.url, it.tab.title, it.tab.desktop, time = it.time) })
    }
    /** Coalesces the bursts of page callbacks into one write. */
    override fun schedulePersist() { main.removeCallbacks(persistLater); main.postDelayed(persistLater, 500) }

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
        privateLocked = false
        updatePrivateLock()
        tabManager.dropClosed(true)
        recentlyClosed.removeAll { it.tab.isPrivate }
        downloads.dropPrivatePending()
        session.end { success -> if (!success) toast(R.string.private_cleanup_retry) }
        if (themeRecreatePending && !isFinishing && !isDestroyed) {
            themeRecreatePending = false
            pageArea.post { if (!isDestroyed) { persist(); recreate() } }
        }
    }
    /** Keeps back/forward history in memory so an undone close restores it. */
    private fun retire(target: BrowserTab) {
        target.view?.let { keepState(target, it) }
        release(target)
    }
    /** Saves [web]'s history and scroll position on [target], so the page can come back; false when it could not. */
    private fun keepState(target: BrowserTab, web: WebView): Boolean {
        val state = Bundle()
        if (!runCatching { web.saveState(state) != null }.getOrDefault(false)) return false
        target.state = state
        target.restoreScroll = web.scrollX to web.scrollY
        return true
    }
    /** Keeps closed pages for reopening later; blank tabs are not worth listing. */
    private fun remember(closed: List<BrowserTab>) {
        closed.filter { it.url.isNotEmpty() }.forEach { tab -> recentlyClosed.removeAll { it.tab === tab }; recentlyClosed.addFirst(ClosedTab(tab)) }
        while (recentlyClosed.size > MAX_RECENTLY_CLOSED) recentlyClosed.removeLast()
    }
    /** Reopens a tab from the recently closed list, with its history when it is still in memory. */
    internal fun reopen(entry: ClosedTab) {
        recentlyClosed.remove(entry)
        val closed = entry.tab
        if (closed in tabs) { selectTab(closed); return }
        if (closed.isPrivate && closed.session !== privateSession) { persist(); return }
        tabManager.forget(closed)
        if (tabManager.add(closed, activate = true) == null) { recentlyClosed.addFirst(entry); toast(R.string.tabs_limit); return }
        closed.error = null
        showTab()
        tabsPanel?.render()
    }
    internal fun closeGroup(private: Boolean, except: BrowserTab? = null, keep: (BrowserTab) -> Boolean = { false }) {
        val closing = tabs.filter { it.isPrivate == private && it !== except && !keep(it) }
        remember(closing)
        closing.forEach(::retire)
        tabManager.closeGroup(private, except, keep)
        finishEmptyPrivateSession()
        showTab()
        tabsPanel?.render()
    }
    internal fun closeTab(index: Int, offerUndo: Boolean = false) {
        val closing = tabs[index]
        remember(listOf(closing))
        retire(closing)
        tabManager.close(index)
        finishEmptyPrivateSession()
        showTab()
        tabsPanel?.render()
        if (offerUndo && tabManager.lastClosed != null) {
            showSnackbar(str(R.string.tab_closed, displayTitle(closing).take(20)), str(R.string.action_undo)) { undoCloseTabs() }
        }
    }
    /** Called only once the destination window exists and can take ownership of the live page. */
    internal fun takeForWindow(target: BrowserTab, destination: BrowserSession): BrowserTab? {
        val index = tabs.indexOf(target)
        if (isDestroyed || index < 0 || target.isPrivate) return null
        hideFullScreen()
        if (findBar.web === target.view) hideFindInPage()
        target.view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            target.session.transfer(it, destination)
        }
        target.session = destination
        target.navigation++
        tabManager.take(index)
        showTab()
        tabsPanel?.render()
        media.probe()
        return target
    }
    internal fun undoCloseTabs() {
        val placeholder = tabManager.lastClosed?.placeholder
        if (tabManager.undoClose() == null) { toast(R.string.tabs_limit); return }
        if (placeholder != null && placeholder !in tabs) release(placeholder)
        recentlyClosed.removeAll { it.tab in tabs }
        showTab()
        tabsPanel?.render()
    }
    private fun updateTabCounter() {
        if (!chromeReady) return
        val private = isPrivateMode
        val count = tabManager.count(private)
        tabButton.text = BrowserNavigation.tabCountLabel(count)
        tabButtonIcon.setImageDrawable(if (private) privateTabsIcon else tabsIcon)
        tabButton.setTextColor(if (private) palette.accent else palette.ink)
        tabButtonHost.describe(str(if (private) R.string.tabs_counter_private else R.string.tabs_counter, count))
    }
    internal fun proxyLabel() = str(when (proxyRoute) {
        BrowserProxyNetwork.Route.PROXY -> R.string.proxy_running
        BrowserProxyNetwork.Route.CHECKING -> R.string.proxy_checking
        BrowserProxyNetwork.Route.DIRECT -> R.string.proxy_direct
        BrowserProxyNetwork.Route.VPN -> R.string.proxy_vpn_bypassed
        BrowserProxyNetwork.Route.OFF -> R.string.proxy_off
    })

    override fun updateChrome() {
        updatePrivateLock()
        val url = tab?.url.orEmpty()
        val private = isPrivateMode
        val hint = searchOverlay.hint(private)
        if (searchOverlay.input !== address) address.hint = hint
        address.contentDescription = str(if (private) R.string.address_private else R.string.address)
        applyInputPrivacy(address, private)
        // Repeat add/clear calls are cheap but not free, so the window is only touched when it changes.
        if (secureWindow != private) {
            secureWindow = private
            if (private || tab?.isPrivate == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val editing = address.hasFocus()
        if (!editing) address.setText(if (homeVisible) "" else BrowserInput.display(url))
        // Private mode has its own ghost, so plain HTTP never looks secure; the lock opens site details.
        address.setCompoundDrawablesWithIntrinsicBounds(if (private) ghostIcon else null, null, null, null)
        val secure = isSecure(tab)
        siteButton.fadeVisible(!editing && !homeVisible && BrowserInput.isWebUrl(url))
        // Without the lock in front, the text would sit against the capsule's rounded edge.
        val inset = if (siteButton.isVisible) 0 else dp(16)
        if (address.paddingStart != inset) address.setPaddingRelative(inset, 0, 0, 0)
        // While editing, the clear button takes the refresh/stop slot so only one X ever shows.
        refreshButton.fadeVisible(!editing)
        siteButton.setImageDrawable(if (secure) secureIcon else insecureIcon)
        siteButton.describe(str(if (secure) R.string.site_info_secure else R.string.site_info_insecure))
        updateTabCounter()
        setNavEnabled(forwardButton, forwardAction() != BrowserNavigationAction.NONE)
        val closable = BrowserNavigation.canCloseOnBack(private, tabManager.count(private))
        setNavEnabled(backButton, !homeVisible && (url.isNotEmpty() || closable))
        progressShowing = null
        updateProgress()
        updateBackCallback()
    }
    private fun forwardAction() = BrowserNavigation.forward(
        homeVisible = homeVisible,
        canGoForward = tab?.view?.canGoForward() == true,
        hasPage = tab?.url?.isNotEmpty() == true
    )

    /** HTTPS without an ignored certificate error, and not showing a load failure. */
    internal fun isSecure(target: BrowserTab?): Boolean {
        val url = target?.url.orEmpty()
        if (!url.startsWith(HTTPS, ignoreCase = true) || target?.error != null) return false
        // Only the address is remembered: the same page may be open in a session that ignored a certificate error.
        val host = hostMemo?.takeIf { it.first == url }?.second
            ?: runCatching { java.net.URI(url).host }.getOrNull().also { hostMemo = url to it }
        return host != null && host !in target?.session?.certificateExceptions.orEmpty()
    }
    private fun stopOrReload(target: BrowserTab) {
        val web = target.view
        when {
            // Nothing has loaded yet (a tab restored at startup): dropping the wait would only leave a blank page.
            target.waiting && web?.url == null -> toast(R.string.proxy_waiting)
            // A navigation still waiting for the proxy is dropped; the address goes back to the page shown.
            target.waiting -> {
                target.waiting = false
                target.navigation++
                web?.url?.takeIf(BrowserInput::isWebUrl)?.let { target.url = it }
                updateChrome()
            }
            web?.url == null -> load(target, target.url)
            web.progress < 100 -> web.stopLoading()
            else -> reloadWhenReady(target, web)
        }
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
        if (!chromeReady) return
        backCallback.isEnabled = PageShell.handlesBack(openLayer(), pageVisible = !homeVisible, sdk = Build.VERSION.SDK_INT)
    }
    override fun updateProgress() {
        syncPullRefresh()
        val current = tab
        val url = current?.url.orEmpty()
        val waiting = current?.waiting == true
        val loading = current?.view?.progress ?: 100
        val showing = !homeVisible && url.isNotEmpty() && (waiting || loading < 100)
        progress.isIndeterminate = waiting
        // Animated so WebView's coarse steps (10, 65, 100) read as steady movement.
        if (!waiting) progress.setProgress(loading, progressShowing == true)
        updateWaitingRow()
        if (showing == progressShowing) return
        progressShowing = showing
        progress.animate().cancel()
        if (showing) { progress.alpha = 1f; progress.visibility = View.VISIBLE }
        // Finishing fades out once the bar has run to its end, rather than vanishing short of it.
        else if (progress.isVisible) progress.animate().alpha(0f).setStartDelay(120).setDuration(250)
            .withEndAction { progress.visibility = View.INVISIBLE; progress.alpha = 1f }.start()
        refreshButton.setImageDrawable(if (showing) stopIcon else refreshIcon)
        refreshButton.describe(str(if (showing) R.string.page_stop else R.string.page_refresh))
    }
    private fun updateWaitingRow() {
        if (!::waitingRow.isInitialized) return
        val current = tab
        waitingRow.isVisible = current?.waiting == true && proxyConnecting && !homeVisible
        // A tab restored at startup has no page to go back to, so its wait cannot be dropped.
        waitingStop.isVisible = current?.view?.url != null
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
    override fun load(target: BrowserTab, url: String) {
        if (BrowserInput.isWebUrl(url) && !scriptInstall.intercept(target, url)) loadPage(target, preferHttps(target, url))
    }
    private fun loadPage(target: BrowserTab, url: String) {
        target.url = url
        target.state = null
        target.restoreScroll = null
        target.error = null
        if (tab === target) {
            pageTools.closeReader()
            homePage.closeFolders()
            homeVisible = false
            showWaiting(target)
        }
        if (!ready) { initialIntent = null; return }
        target.waiting = true
        val navigation = ++target.navigation
        updateProgress()
        network.prepare {
            if (!isDestroyed && target.navigation == navigation && BrowserNavigation.shouldApplyPreparedNavigation(target in tabs, target.url, url)) {
                target.waiting = false
                val view = target.view ?: createWebView(target).also { target.view = it }
                applySiteSettings(target, view, url)
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
        clearPageOverlays()
        addressRow.isVisible = true
        if (target.view == null) {
            val placeholder = ui.column().apply {
                gravity = Gravity.CENTER
                setBackgroundColor(palette.surface)
                if (proxyConnecting) addView(ui.label(str(R.string.proxy_waiting), 14f, palette.muted).apply { gravity = Gravity.CENTER })
            }
            attachPage(placeholder)
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
    override fun openWindow(source: BrowserTab, resultMsg: Message): Boolean {
        if (!tabManager.canAdd) return false
        val child = addTab(activate = false, session = source.session) ?: return false
        val childView = createWebView(child).also { child.view = it }
        child.url = "about:blank"
        (resultMsg.obj as WebView.WebViewTransport).webView = childView
        resultMsg.sendToTarget()
        // New windows open in front, like a normal link click.
        selectTab(child)
        return true
    }
    override fun closeWindow(window: WebView) {
        tabs.indexOfFirst { it.view === window }.takeIf { it >= 0 }?.let { closeTab(it) }
    }
    /** Opens [url] behind the current page, so several links can be queued; the snackbar switches to it. */
    override fun openInBackground(source: BrowserTab, url: String) {
        if (source !in tabs) return
        val opened = addTab(url, activate = false, session = source.session) ?: return
        showSnackbar(str(R.string.link_opened_background), str(R.string.action_switch)) { if (opened in tabs) selectTab(opened) }
    }
    override fun openTab(source: BrowserTab, url: String, background: Boolean) {
        if (source !in tabs) return
        if (background) openInBackground(source, url) else addTab(url, session = source.session)
    }
    override val canOpenPrivate get() = true
    override fun openPrivate(url: String) { obtainPrivateSession()?.let { addTab(url, session = it) } }
    override fun pageGone(view: WebView) { if (findBar.web === view) hideFindInPage() }
    override val reopenIntent get() = Intent(this, BrowserActivity::class.java)

    private fun release(target: BrowserTab) {
        if (findBar.web === target.view) hideFindInPage()
        target.view?.let { target.session.release(it) }
        target.view = null
    }
    internal fun selectTab(target: BrowserTab) { tabManager.select(target); showTab() }
    /** Switches to the neighbouring tab; the new page slides in from the side the swipe moved away from. */
    private fun swipeTab(step: Int) {
        val next = tabManager.neighbor(step) ?: return
        haptic(bottomBar)
        tabManager.select(next)
        showTab()
        pageArea.animate().cancel()
        pageArea.translationX = step * dp(48).toFloat()
        pageArea.alpha = 0.5f
        pageArea.animate().translationX(0f).alpha(1f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
    }
    internal fun showTab(load: Boolean = true) {
        hideFullScreen()
        pageTools.closeReader()
        clearPageOverlays()
        homePage.closeFolders()
        homeVisible = false
        addressRow.isVisible = true
        val target = tab ?: return
        // Detach background views without blanket onPause(), which can interrupt media.
        target.touched = SystemClock.elapsedRealtime()
        if (target.url.isEmpty()) showHome()
        else {
            val fresh = target.view == null
            val web = target.view ?: createWebView(target).also { target.view = it }
            attachPage(web); web.onResume()
            target.error?.let { renderPageError(target, it) }
            if (fresh && load) {
                target.waiting = true
                val navigation = ++target.navigation
                network.prepare {
                    if (!isDestroyed && target.navigation == navigation && target in tabs && target.view === web) {
                        target.waiting = false
                        applySiteSettings(target, web, target.url)
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
                    if (!isDestroyed && safe && eligible() && keepState(candidate, web)) {
                        release(candidate)
                        released++
                    }
                    next()
                }
            } catch (_: RuntimeException) {
                if (!answered) { answered = true; main.removeCallbacks(timeout); next() }
            }
        }
        next()
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

    /** Adds a backup's tabs that are not open yet, behind the current ones; returns how many were added. */
    internal fun restoreTabs(saved: List<SavedTab>): Int {
        // Only ordinary tabs count: a page open just in a private tab would be gone once that tab closes.
        val open = tabs.filter { !it.isPrivate }.mapTo(HashSet()) { it.url }
        val added = saved.filter { it.url !in open }.count { tab ->
            tabManager.add(BrowserTab(tab.url, tab.title, tab.desktop, session = normalSession), activate = false) != null
        }
        updateTabCounter()
        tabsPanel?.render()
        persist()
        return added
    }

    /**
     * Applies settings a backup restored to what is open. A new theme needs the activity again, which
     * waits while a private session is open, as when the system theme changes.
     */
    internal fun settingsRestored(themeChanged: Boolean) {
        BrowserInput.customTemplate = prefs.customEngine.takeIf(BrowserInput::isValidTemplate)
        preferencesChanged()
        updateChrome()
        refreshHome()
        persist()
        if (!themeChanged) return
        if (privateSession == null) recreate() else themeRecreatePending = true
    }

    /** Applies a theme from settings; recreating ends any private session, so callers confirm first. */
    internal fun applyTheme(theme: BrowserPrefs.Theme) { prefs.theme = theme; persist(); recreate() }

    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimTabs(TabMemoryPolicy.Pressure.CRITICAL) }

    override val pageShowing get() = !homeVisible

    override fun configureWebView(target: BrowserTab, web: BrowserWebView) {
        // Scrolling in one direction adds up; turning around starts over, so slow or uneven scrolling
        // does not make the toolbar flicker in and out.
        var travel = 0
        web.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (tab !== target || target.url.isEmpty() || fullView != null || findBar.isVisible) return@setOnScrollChangeListener
            val dy = scrollY - oldScrollY
            if (dy == 0) return@setOnScrollChangeListener
            travel = if ((dy > 0) == (travel > 0)) travel + dy else dy
            when {
                scrollY < dp(20) -> setChromeCollapsed(false)
                travel > dp(24) && scrollY > dp(60) -> setChromeCollapsed(true)
                travel < -dp(24) -> setChromeCollapsed(false)
            }
        }
        web.onSwipeDown = {
            if (tab === target && !homeVisible && fullView == null) expandChrome()
        }
        web.showAi = { aiConfig.enabled }
        web.onAskAi = { finish -> assistant.selection(target, web, finish) }
    }

    /** Records the failure on [target]; it shows now if the tab is in front, otherwise when it is selected. */
    override fun showPageError(target: BrowserTab, error: PageError) {
        target.error = error
        target.waiting = false
        if (tab === target && !homeVisible) { renderPageError(target, error); updateChrome() }
    }
    private fun renderPageError(target: BrowserTab, error: PageError) {
        val title = error.title
        val message = error.message
        // Suggestions stay: the error may arrive while the user is typing a new address.
        hideFindInPage()
        expandChrome()
        addressRow.isVisible = true
        val panel = ui.column().apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(24), dp(30), dp(24)) }
        panel.addView(ui.label(title, 22f).apply { gravity = Gravity.CENTER })
        if (message.isNotEmpty()) panel.addView(ui.label(message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        panel.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))
        val actions = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        actions.addView(ui.textAction(str(R.string.action_retry), true) { retryPage(target) }, ui.weighted())
        if (BrowserInput.isWebUrl(target.url)) {
            actions.addView(ui.textAction(str(R.string.error_copy_url)) { copy(target.url) }, ui.weighted().apply { leftMargin = dp(8) })
        }
        panel.addView(actions, ui.wide())
        error.action?.let { (label, action) ->
            panel.addView(ui.textAction(label) { action() }.apply { setTextColor(palette.danger) }, ui.wide().apply { topMargin = dp(6) })
        }
        panel.addView(ui.textAction(str(R.string.error_go_home)) { home() }.apply { setTextColor(palette.muted) }, ui.wide().apply { topMargin = dp(6) })
        attachPage(panel)
        hideProgress()
    }
    private fun hideProgress() {
        progress.animate().cancel()
        progress.visibility = View.INVISIBLE
        progress.alpha = 1f
        progressShowing = false
    }
    override fun retryPage(target: BrowserTab) {
        target.error = null
        if (tab !== target) return
        val web = target.view ?: run { showTab(); return }
        attachPage(web)
        reloadWhenReady(target, web) { if (web.url == target.url) web.reload() else web.loadUrl(target.url) }
        updateChrome()
    }
    override fun onFullScreenChanged() {
        if (fullView == null) expandChrome()
        updateBackCallback()
    }


    private fun externalInput(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf(BrowserInput::isWebUrl)
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
        else -> null
    }?.let(BrowserInput::typed)
    /** Set while a gesture that started on a view continues outside it, such as dragging a bookmark. */
    internal var touchInterceptor: ((MotionEvent) -> Boolean)? = null
    /** Set from a touch down that only ended address editing, so the rest of that gesture goes nowhere. */
    private var endingEdit = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchInterceptor?.let { return it(ev) }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) endingEdit = editingOverPage(ev).also { if (it) hideSearch() }
        if (!endingEdit) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) endingEdit = false
        return true
    }
    /**
     * A touch on the page while its address is edited above it: it ends editing, as back would, rather
     * than reaching a link the reader did not mean to follow.
     */
    private fun editingOverPage(ev: MotionEvent): Boolean {
        if (!chromeReady || searchOverlay.input !== address || searchOverlay.isVisible || homeVisible) return false
        val x = ev.rawX.toInt(); val y = ev.rawY.toInt()
        fun View.contains() = Rect().also { getGlobalVisibleRect(it) }.contains(x, y)
        return pageArea.contains() && !chrome.contains()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        externalInput(intent)?.let { tabsPanel?.dismiss(); if (ready) addTab(it, session = normalSession) else initialIntent = it }
    }
    override fun onStart() {
        super.onStart()
        main.post(memoryCheck)
    }
    override fun onResume() {
        super.onResume()
        awaitingResult = false
        if (chromeReady && fullView == null) {
            // App switches can interrupt a toolbar animation; restore usable controls immediately.
            chrome.animate().cancel()
            chromeCollapsed = false
            chrome.translationY = 0f
            chrome.visibility = View.VISIBLE
            applyContentInset()
        }
    }
    override fun onStop() {
        main.removeCallbacks(memoryCheck)
        // The unlock itself, and pickers or the camera opened from a page, cover the window without leaving it.
        if (!unlock.asking && !awaitingResult && !isChangingConfigurations && prefs.lockPrivate && privateSession != null) {
            privateLocked = true
            if (prefs.lockPrivatePausesMedia) media.silence(tabs.filter { it.isPrivate })
            // Coming back shows ordinary tabs; private ones are a deliberate switch away, behind the unlock.
            if (isPrivateMode) switchGroup(false)
        }
        super.onStop()
    }
    @Deprecated("Overridden only to notice launches made through activity result contracts.")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        super.startActivityForResult(intent, requestCode, options)
        awaitingResult = true
    }

    /** Covers a private tab brought to the front while locked, and asks for the unlock. */
    private fun updatePrivateLock() {
        if (!chromeReady) return
        val covered = privateLocked && isPrivateMode
        if (covered == (lockCover != null)) return
        // Screen readers would otherwise still read out what the cover hides.
        val hidden = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        root.children.forEach { it.importantForAccessibility = hidden }
        if (!covered) {
            lockCover?.let(root::removeView)
            lockCover = null
            lockBack.remove()
            return
        }
        BrowserSheet.dismissAll(root)
        val cover = ui.column().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(palette.base)
            isClickable = true
            isFocusable = true
            // Above what the window draws over the page itself, such as the snackbar.
            elevation = dp(32).toFloat()
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(ImageView(this@BrowserActivity).apply {
                setImageDrawable(ui.tintedIcon(R.drawable.ic_lock_24, palette.accent))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, ui.box(40, 40))
            addView(ui.medium(ui.label(str(R.string.private_locked_title), 18f)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
                markHeading()
            })
        }
        root.addView(cover, ui.full())
        lockCover = cover
        // Registered last, so it comes before the callbacks of anything open underneath.
        onBackPressedDispatcher.addCallback(this, lockBack)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) unlockPrivate()
    }
    /** Private tabs are hidden behind the lock, so nothing should show them now. */
    internal val privateCovered get() = lockCover != null
    private fun unlockPrivate() = unlock.confirm(str(R.string.unlock_private), cancelled = { deliberate -> if (privateCovered) leavePrivateLock(deliberate) }) {
        privateLocked = false
        updatePrivateLock()
    }
    /**
     * Leaves the lock cover for ordinary tabs. The private tabs wait for the next attempt, unless the
     * unlock was turned down or failed (not merely interrupted) and [BrowserPrefs.lockPrivateCloseOnFail]
     * is on: then they all close, silently and with no undo, ending the private session.
     */
    private fun leavePrivateLock(deliberate: Boolean) {
        if (deliberate && prefs.lockPrivateCloseOnFail) closeGroup(true) else switchGroup(false)
    }
    override fun onPause() {
        if (prefsReady) persist()
        super.onPause()
    }
    override fun onDestroy() {
        pageTools.closeReader()
        proxyPanel?.dismiss()
        if (chromeReady) searchOverlay.destroy()
        if (isFinishing && prefsReady && prefs.clearOnExit.isNotEmpty()) {
            ExitCleanup.run(this, prefs, store, normalSession) { prefs.exitCleanupPending = false }
        }
        tabs.forEach(::release); tabManager.tabs.clear(); finishEmptyPrivateSession(); super.onDestroy()
    }

    private companion object {
        const val MAX_RECENTLY_CLOSED = 15
        const val MAX_HISTORY_ITEMS = 12
        const val HTTPS = "https://"
    }
}

/** Toolbar that switches tabs on a horizontal swipe while keeping its buttons tappable. */
@SuppressLint("ViewConstructor") // Built in code only.
private class SwipeBar(
    context: Context, private val slop: Int, private val distance: Int,
    private val enabled: () -> Boolean = { true }, private val onSwipe: (Int) -> Unit
) : LinearLayout(context) {
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                if (enabled() && isSidewaysDrag(ev.x - startX, ev.y - startY, slop)) {
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
