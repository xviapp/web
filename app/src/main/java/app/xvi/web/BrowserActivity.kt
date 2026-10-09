package app.xvi.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.SearchManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState
import java.util.concurrent.Executors
import java.util.function.Consumer

/** One browser tab. Ordinary tabs persist their address; private tabs live only in memory. */
internal class BrowserTab(
    var url: String = "", var title: String = "", var desktop: Boolean = false,
    var view: WebView? = null, var state: Bundle? = null, var touched: Long = 0,
    val session: BrowserSession, var keepAlive: Boolean = false, var documentVersion: Long = 0,
    var restoreScroll: Pair<Int, Int>? = null, var favicon: Bitmap? = null
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
    internal val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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
    private lateinit var clearButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var bottomBar: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findStatus: TextView
    private lateinit var snackbar: LinearLayout
    private lateinit var snackbarText: TextView
    private lateinit var snackbarAction: TextView
    internal lateinit var searchOverlay: SearchOverlay
    private lateinit var homePage: HomePage
    private lateinit var refreshIcon: Drawable
    private lateinit var stopIcon: Drawable
    private lateinit var lockIcon: Drawable
    private lateinit var ghostIcon: Drawable
    private lateinit var tabsIcon: Drawable
    private lateinit var privateTabsIcon: Drawable
    private var findTextWatcher: android.text.TextWatcher? = null
    internal var findWebView: WebView? = null
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
    private var permissionRequest: PermissionRequest? = null
    private var permissionDecision: (() -> Unit)? = null
    private var pendingDownload: Intent? = null
    private var initialIntent: String? = null
    @VisibleForTesting internal var trimming = false
    private val protectionProbe by lazy { assets.open("tab-protection.js").bufferedReader().use { it.readText() } }
    private val desktopUserAgent by lazy { UserAgents.desktop(WebSettings.getDefaultUserAgent(this)) }
    private val memoryCheck = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            trimTabs(currentMemoryPressure())
            main.postDelayed(this, 15_000)
        }
    }
    private val persistLater = Runnable { if (!isDestroyed) persist() }
    private val hideSnackbar = Runnable { if (::snackbar.isInitialized) snackbar.animate().alpha(0f).setDuration(150).withEndAction { snackbar.visibility = View.GONE }.start() }
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
        }
    } }

    internal val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)?.filter { it.scheme == "content" }?.toTypedArray()
        fileCallback?.onReceiveValue(uris); fileCallback = null
    }
    private val downloadPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val request = pendingDownload
        pendingDownload = null
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (request == null) {
            // The private session ended while the picker was open; remove the empty file it created.
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            toast(R.string.download_private_ended)
            return@registerForActivityResult
        }
        startDownloadService(request.putExtra(BrowserDownloadService.EXTRA_DESTINATION, uri.toString()))
        toast(if (request.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false)) R.string.download_started_private else R.string.download_started)
    }
    internal val bookmarkImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        io.execute {
            val result = runCatching { contentResolver.openInputStream(uri)!!.use(store::importBookmarks) }
            runOnUiThread { toast(result.fold({ str(R.string.bookmarks_imported, it) }, { str(R.string.bookmarks_import_failed) })); if (homeVisible) refreshHome() }
        }
    }
    internal val bookmarkExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) return@registerForActivityResult
        io.execute {
            val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(store.exportBookmarks()) } }
            runOnUiThread { toast(if (result.isSuccess) R.string.bookmarks_exported else R.string.bookmarks_export_failed) }
        }
    }
    private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val done = permissionDecision; permissionDecision = null; done?.invoke()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    internal var onBrowserRoleResult: (() -> Unit)? = null
    internal val browserRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBrowserRoleResult?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BrowserPrefs(this)
        delegate.localNightMode = prefs.theme.nightMode
        super.onCreate(savedInstanceState)
        store = BrowserStore.get(this)
        BrowserSession.cleanupPreviousProcess()
        if (!BrowserDownloadService.running) io.execute { store.interruptDownloads() }
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullView != null -> hideFullScreen()
                    findBar.visibility == View.VISIBLE -> hideFindInPage()
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
                        else -> Unit
                    }
                }
            }
        })
    }

    internal fun str(@StringRes id: Int, vararg args: Any): String = getString(id, *args)
    private fun dp(value: Int) = ui.dp(value)
    internal fun domain(url: String): String = runCatching { Uri.parse(url).host?.removePrefix("www.")?.ifBlank { null } ?: url }.getOrDefault(url)
    internal fun displayTitle(target: BrowserTab) = target.title.ifBlank { if (target.url.isEmpty()) str(R.string.tab_new) else domain(target.url) }

    internal fun haptic(view: View? = null, constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (!::prefs.isInitialized || !prefs.haptics) return
        (view ?: window.decorView).performHapticFeedback(constant)
    }
    internal fun markDeleteTarget(view: View, highlighted: Boolean) {
        view.background = if (highlighted) ui.rounded(palette.dangerSurface, 12) else ui.selectable()
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
    internal fun confirmDelete(name: String, action: () -> Unit, onCancel: () -> Unit = {}) {
        val displayName = name.trim().ifBlank { str(R.string.record_untitled) }.take(80)
        val dialog = browserDialog()
            .setTitle(R.string.record_delete_title)
            .setMessage(str(R.string.record_delete_message, displayName))
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.action_delete) { _, _ -> action() }
            .create()
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.danger) }
        dialog.show()
    }
    internal fun sheet(@StringRes title: Int, headerAction: ((BrowserSheet) -> View)? = null) =
        BrowserSheet(ui, root, str(title), { isPrivateMode }, headerAction)

    /** Slides the address and toolbar away while reading; they return on upward scroll or any chrome action. */
    private fun setChromeCollapsed(collapsed: Boolean) {
        if (collapsed == chromeCollapsed) return
        if (collapsed && (fullView != null || findBar.visibility == View.VISIBLE || address.hasFocus())) return
        chromeCollapsed = collapsed
        chrome.animate().cancel()
        if (collapsed) {
            chrome.animate().translationY(chrome.height.toFloat()).setDuration(160)
                .withEndAction { if (chromeCollapsed) chrome.visibility = View.GONE }.start()
        } else {
            chrome.visibility = View.VISIBLE
            chrome.animate().translationY(0f).setDuration(160).start()
        }
    }
    private fun expandChrome() = setChromeCollapsed(false)

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
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expandChrome()
                searchOverlay.show(input, overlayTop())
                updateChrome()
            } else if (searchOverlay.input === input) hideSearch()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                navigate(input.text.toString(), searchOverlay.engine); true
            } else false
        }
        ui.onTextChanged(input) { if (input.hasFocus()) searchOverlay.query(it) }
    }

    internal fun hideSearch() {
        val input = searchOverlay.input
        searchOverlay.hide()
        input?.clearFocus()
        address.clearFocus()
        hideKeyboard(input ?: address)
        clearButton.visibility = View.GONE
        updateChrome()
    }

    private fun buildFindBar() {
        findBar = ui.row().apply {
            background = ui.rounded(palette.surface, 16)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        findInput = ui.edit(str(R.string.find_hint)).apply {
            background = null
            setPadding(dp(4), dp(4), dp(4), dp(4))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        findStatus = ui.label("", 12f, palette.muted).apply { setPadding(dp(4), 0, dp(4), 0) }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        findBar.addView(findStatus, LinearLayout.LayoutParams(-2, -2))
        findBar.addView(ui.iconAction(R.drawable.ic_arrow_up_24, str(R.string.find_previous)) { findWebView?.findNext(false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(ui.iconAction(R.drawable.ic_arrow_down_24, str(R.string.find_next)) { findWebView?.findNext(true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(ui.iconAction(R.drawable.ic_close_24, str(R.string.find_close)) { hideFindInPage() }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }
    private fun detachFindSession() {
        findTextWatcher?.let { findInput.removeTextChangedListener(it) }
        findTextWatcher = null
        findWebView?.let { it.clearMatches(); it.setFindListener(null) }
        findWebView = null
    }
    internal fun findInPage() {
        val web = tab?.view ?: return
        detachFindSession()
        expandChrome()
        addressRow.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findStatus.text = ""
        findWebView = web
        web.setFindListener { active, total, done ->
            if (done && findWebView === web) findStatus.text = if (total == 0) str(R.string.find_none) else "${active + 1}/$total"
        }
        findTextWatcher = ui.onTextChanged(findInput) { if (findWebView === web && tab?.view === web) web.findAllAsync(it) }
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { if (findWebView === web) web.findNext(true); true } else false
        }
        // The menu is a focusable PopupWindow. Wait for its dismissal to finish before asking for the IME.
        findInput.postDelayed({
            if (!isDestroyed && findBar.visibility == View.VISIBLE) {
                findInput.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 300L)
    }
    internal fun hideFindInPage() {
        if (!::findBar.isInitialized) return
        detachFindSession()
        if (findBar.visibility != View.VISIBLE) return
        hideKeyboard(findInput)
        findInput.setOnEditorActionListener(null)
        findBar.visibility = View.GONE
        addressRow.visibility = if (homeVisible) View.GONE else View.VISIBLE
    }

    private fun buildChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !palette.dark; isAppearanceLightNavigationBars = !palette.dark }
        refreshIcon = ui.tintedIcon(R.drawable.ic_refresh_24)!!
        stopIcon = ui.tintedIcon(R.drawable.ic_close_24)!!
        lockIcon = ui.tintedIcon(R.drawable.ic_lock_24, palette.muted)!!
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
        val frame = ui.column()
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this)
        pageHost = FrameLayout(this)
        content.addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        searchOverlay = SearchOverlay(this)
        content.addView(searchOverlay.view, FrameLayout.LayoutParams(-1, -1))
        frame.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        chrome = ui.column().apply { setBackgroundColor(palette.base) }
        frame.addView(chrome, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(palette.accent); visibility = View.INVISIBLE
        }
        chrome.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        addressRow = ui.row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        val addressCapsule = ui.row().apply { background = ui.rounded(palette.surface); minimumHeight = dp(48) }
        addressProxyButton = ui.iconAction(R.drawable.ic_proxy_24, str(R.string.proxy_title)) { showProxy() }
        addressCapsule.addView(addressProxyButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2) })
        address = ui.edit("").apply {
            background = null; setPadding(0, 0, 0, 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            compoundDrawablePadding = dp(5)
        }
        addressCapsule.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        clearButton = ui.iconAction(R.drawable.ic_close_24, str(R.string.address_clear), palette.muted) { address.text.clear() }.apply { visibility = View.GONE }
        addressCapsule.addView(clearButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        ui.onTextChanged(address) { if (address.hasFocus()) clearButton.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        refreshButton = ui.iconAction(R.drawable.ic_refresh_24, str(R.string.page_refresh)) {
            tab?.view?.let { if (it.progress < 100) it.stopLoading() else network.prepare { _ -> if (!isDestroyed) it.reload() } }
        }
        addressCapsule.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(addressCapsule, LinearLayout.LayoutParams(-1, -2))
        chrome.addView(addressRow)
        attachSearchInput(address) { 0 }
        homePage = HomePage(this)
        attachSearchInput(homePage.input) { homePage.inputBottomIn(content) }

        buildFindBar()
        chrome.addView(findBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
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
        buildSnackbar()
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

    private fun buildSnackbar() {
        snackbar = ui.row().apply {
            background = ui.rounded(palette.snackbar, 14)
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(4), dp(4), dp(4))
            minimumHeight = dp(48)
            visibility = View.GONE
        }
        snackbarText = ui.label("", 14f, palette.onSnackbar).apply { maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        snackbar.addView(snackbarText, LinearLayout.LayoutParams(0, -2, 1f))
        snackbarAction = ui.asButton(ui.medium(ui.label("", 14f, palette.snackbarAction))).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = ui.selectable(10)
        }
        snackbar.addView(snackbarAction, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(snackbar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
    }

    /** Short in-app message with an optional action, placed above the toolbar. */
    internal fun showSnackbar(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        main.removeCallbacks(hideSnackbar)
        snackbar.animate().cancel()
        snackbarText.text = message
        snackbarAction.text = action
        snackbarAction.visibility = if (action == null) View.GONE else View.VISIBLE
        snackbarAction.setOnClickListener { main.removeCallbacks(hideSnackbar); hideSnackbar.run(); onAction?.invoke() }
        (snackbar.layoutParams as FrameLayout.LayoutParams).bottomMargin = (if (chrome.visibility == View.VISIBLE) chrome.height else 0) + dp(8)
        snackbar.requestLayout()
        snackbar.alpha = 0f
        snackbar.visibility = View.VISIBLE
        snackbar.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hideSnackbar, if (action == null) 3000L else 5000L)
    }

    private fun showHome() {
        homeVisible = true
        if (searchOverlay.isVisible) hideSearch()
        hideFindInPage()
        addressRow.visibility = View.GONE
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
        if (pendingDownload?.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false) == true) pendingDownload = null
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
        if (!address.hasFocus()) address.setText(if (homeVisible) "" else url)
        // The lock only means HTTPS; private mode gets its own ghost so plain HTTP never looks secure.
        val indicators = listOfNotNull(if (private) ghostIcon else null, if (url.startsWith("https://", ignoreCase = true)) lockIcon else null)
        address.setCompoundDrawablesWithIntrinsicBounds(
            if (indicators.size < 2) indicators.firstOrNull() else LayerDrawable(indicators.toTypedArray()).apply {
                val offset = indicators[0].intrinsicWidth + dp(4)
                setLayerInset(0, 0, 0, offset, 0); setLayerInset(1, offset, 0, 0, 0)
            }, null, null, null)
        updateTabCounter()
        val canForward = (homeVisible && url.isNotEmpty()) || (!homeVisible && tab?.view?.canGoForward() == true)
        setNavEnabled(forwardButton, canForward)
        val closable = BrowserNavigation.canCloseOnBack(private, tabManager.count(private))
        setNavEnabled(backButton, !homeVisible && (url.isNotEmpty() || closable))
        progressShowing = null
        updateProgress()
    }
    private fun setNavEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else .3f
    }
    /** Progress-only refresh; called for every progress tick, so it avoids the rest of the chrome. */
    internal fun updateProgress() {
        val url = tab?.url.orEmpty()
        val loading = tab?.view?.progress ?: 100
        val showing = !homeVisible && url.isNotEmpty() && loading < 100
        progress.progress = loading
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
        if (tab === target) homeVisible = false
        target.url = url
        target.state = null
        target.restoreScroll = null
        if (!ready) { initialIntent = null; return }
        network.prepare {
            if (!isDestroyed && BrowserNavigation.shouldApplyPreparedNavigation(target in tabs, target.url, url)) {
                val view = target.view ?: createWebView(target).also { target.view = it }
                view.loadUrl(url)
                if (BrowserNavigation.shouldShowPageAfterPreparation(tab === target, homeVisible)) showTab(false)
                persist()
            }
        }
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
        if (findWebView === target.view) hideFindInPage()
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
        addressRow.visibility = View.VISIBLE
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
            if (fresh && load) network.prepare {
                if (!isDestroyed && target in tabs && target.view === web) {
                    if (target.state?.let { web.restoreState(it) } == null) web.loadUrl(target.url)
                    target.state = null
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
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = prefs.textZoom
            if (target.desktop) userAgentString = desktopUserAgent
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, if (palette.dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (palette.dark && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
        }
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (tab !== target || target.url.isEmpty() || fullView != null || findBar.visibility == View.VISIBLE) return@setOnScrollChangeListener
            val dy = scrollY - oldScrollY
            if (dy > 30 && scrollY > dp(60)) setChromeCollapsed(true)
            else if (dy < -30 || scrollY < dp(20)) setChromeCollapsed(false)
        }
        target.session.cookies().setAcceptCookie(true)
        target.session.cookies().setAcceptThirdPartyCookies(this, false)
        webViewClient = PageClient(this@BrowserActivity, target)
        webChromeClient = PageChromeClient(this@BrowserActivity, target)
        setDownloadListener { url, ua, disposition, mime, _ -> download(target, url, ua, disposition, mime) }
        setOnLongClickListener { linkActions(target, this) }
    }

    private fun linkActions(target: BrowserTab, web: WebView): Boolean {
        val result = web.hitTestResult
        val url = result.extra?.takeIf(BrowserInput::isWebUrl) ?: return false
        haptic(web)
        val image = result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val actions = buildList<Pair<String, () -> Unit>> {
            add(str(R.string.link_open_background) to {
                if (target in tabs) addTab(url, activate = false, session = target.session)?.let { opened ->
                    showSnackbar(str(R.string.link_opened_background), str(R.string.action_switch)) { if (opened in tabs) selectTab(opened) }
                }
            })
            if (!target.isPrivate) add(str(R.string.link_open_private) to { obtainPrivateSession()?.let { addTab(url, session = it) } })
            if (image) add(str(R.string.image_open) to { if (target in tabs) addTab(url, session = target.session) })
            add(str(R.string.link_copy) to { copy(url, target.isPrivate) })
            add(str(R.string.link_share) to { share(url) })
            if (image) add(str(R.string.image_save) to { saveImage(target, url) })
            else add(str(R.string.link_download) to { download(target, url, web.settings.userAgentString, null, null) })
        }
        val title = ui.label(url, 13f, palette.muted).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        browserDialog(target).setCustomTitle(title).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }.show()
        return true
    }

    internal fun showPageError(title: String, message: String) {
        hideFindInPage()
        expandChrome()
        addressRow.visibility = View.VISIBLE
        pageHost.removeAllViews()
        val panel = ui.column().apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(24), dp(30), dp(24)) }
        panel.addView(ui.label(title, 22f).apply { gravity = Gravity.CENTER })
        panel.addView(ui.label(message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) })
        val actions = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        actions.addView(ui.textAction(str(R.string.action_retry), true) { retryPage() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (proxyPhase == ProxyPhase.FAILED || proxyPhase == ProxyPhase.STARTING || proxyPhase == ProxyPhase.RECONNECTING) {
            actions.addView(ui.textAction(str(R.string.error_check_proxy)) { showProxy() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        if (BrowserInput.isWebUrl(tab?.url.orEmpty())) {
            actions.addView(ui.textAction(str(R.string.error_copy_url)) { copy(tab?.url.orEmpty()) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(ui.textAction(str(R.string.error_go_home)) { home() }.apply { setTextColor(palette.muted) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
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
    }
    internal fun hideFullScreen() {
        val wasFull = fullView != null
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        if (!wasFull) return
        root.visibility = View.VISIBLE
        expandChrome()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    internal fun externalLink(uri: Uri, source: BrowserTab) {
        val blocked = setOf("file", "content", "javascript", "data", "about")
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme in blocked) return
        val parsed = runCatching { if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri) }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")?.takeIf(BrowserInput::isWebUrl)
        val destination = parsed.data?.takeIf { it.scheme?.lowercase() !in blocked }
        // Only the data and package survive; components and extras from the page are dropped.
        val target = destination?.let { Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(parsed.`package`) }
        if (target == null) {
            if (fallback != null && source in tabs) load(source, fallback) else toast(R.string.external_no_app)
            return
        }
        val app = runCatching { packageManager.resolveActivity(target, 0)?.takeIf { it.activityInfo.packageName != packageName }?.loadLabel(packageManager)?.toString() }.getOrNull()
        val dialog = browserDialog(source).setTitle(R.string.external_title)
            .setMessage(if (app != null) str(R.string.external_message_app, app) else str(R.string.external_message_scheme, destination.scheme.orEmpty()))
            .setPositiveButton(R.string.external_open) { _, _ ->
                try { startActivity(target) }
                catch (_: ActivityNotFoundException) {
                    if (fallback != null && source in tabs) load(source, fallback) else toast(R.string.external_no_app)
                }
            }.setNegativeButton(R.string.action_cancel, null)
        if (fallback != null) dialog.setNeutralButton(R.string.external_stay) { _, _ -> if (source in tabs) load(source, fallback) }
        dialog.show()
    }
    internal fun websitePermission(request: PermissionRequest, source: BrowserTab) {
        permissionRequest?.deny(); permissionRequest = request
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMicrophone = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val supported = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (supported.isEmpty() || request.origin.scheme != "https") { request.deny(); permissionRequest = null; return }
        val requested = str(when {
            wantsCamera && wantsMicrophone -> R.string.permission_camera_microphone
            wantsCamera -> R.string.permission_camera
            else -> R.string.permission_microphone
        })
        val site = request.origin.host ?: request.origin.toString()
        browserDialog(source).setTitle(str(R.string.permission_media_title, site, requested))
            .setMessage(R.string.permission_once_message)
            .setPositiveButton(R.string.permission_allow_once) { _, _ ->
                val permissions = buildList {
                    if (wantsCamera) add(Manifest.permission.CAMERA)
                    if (wantsMicrophone) add(Manifest.permission.RECORD_AUDIO)
                }.toTypedArray()
                askRuntimePermissions(permissions) {
                    if (permissionRequest === request) {
                        val allowed = supported.filter { resource ->
                            checkSelfPermission(if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        }
                        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                        permissionRequest = null
                    }
                }
            }.setNegativeButton(R.string.permission_deny) { _, _ -> request.deny(); permissionRequest = null }
            .setOnCancelListener { request.deny(); permissionRequest = null }.show()
    }
    internal fun cancelWebsitePermission(request: PermissionRequest) {
        if (permissionRequest === request) { permissionRequest = null; permissionDecision = null }
    }
    internal fun askRuntimePermissions(permissions: Array<String>, done: () -> Unit) {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) done()
        else { permissionDecision = done; runtimePermissions.launch(permissions) }
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    internal fun showProxy() = ProxyPanel(this).show()

    private fun downloadRequest(url: String, title: String, private: Boolean, ua: String?, cookie: String?, referer: String?): Intent =
        Intent(this, BrowserDownloadService::class.java)
            .putExtra(BrowserDownloadService.EXTRA_URL, url)
            .putExtra(BrowserDownloadService.EXTRA_UA, ua ?: WebSettings.getDefaultUserAgent(this))
            .putExtra(BrowserDownloadService.EXTRA_COOKIE, cookie)
            .putExtra(BrowserDownloadService.EXTRA_REFERER, referer)
            .putExtra(BrowserDownloadService.EXTRA_TITLE, title)
            .putExtra(BrowserDownloadService.EXTRA_PRIVATE, private)
    private fun safeFileName(url: String, disposition: String?, mime: String?) =
        URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180)
    private fun pickDownloadDestination(request: Intent, title: String, mime: String?) {
        pendingDownload = request
        try {
            downloadPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime?.takeIf { it.contains('/') } ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE, title))
        } catch (_: ActivityNotFoundException) { pendingDownload = null; toast(R.string.download_no_picker) }
    }
    private fun startDownloadService(request: Intent) {
        requestNotificationPermission()
        ContextCompat.startForegroundService(this, request)
    }
    internal fun download(source: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?) {
        if (source !in tabs) return
        if (!BrowserInput.isWebUrl(url)) { toast(R.string.download_unsupported); return }
        val filename = safeFileName(url, disposition, mime)
        if (source.isPrivate) toast(R.string.download_private_note)
        pickDownloadDestination(downloadRequest(url, filename, source.isPrivate, ua, source.session.cookies().getCookie(url), source.url), filename, mime)
    }
    /** Images go straight to Pictures/<app name> when the type is known; otherwise the picker asks. */
    private fun saveImage(source: BrowserTab, url: String) {
        if (source !in tabs) return
        val filename = safeFileName(url, null, null)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
        if (Build.VERSION.SDK_INT < 29 || mime?.startsWith("image/") != true) {
            download(source, url, source.view?.settings?.userAgentString, null, mime)
            return
        }
        val folder = "${android.os.Environment.DIRECTORY_PICTURES}/${str(R.string.app_name)}"
        val destination = runCatching {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            })
        }.getOrNull() ?: run { download(source, url, source.view?.settings?.userAgentString, null, mime); return }
        startDownloadService(downloadRequest(url, filename, source.isPrivate, source.view?.settings?.userAgentString, source.session.cookies().getCookie(url), source.url)
            .putExtra(BrowserDownloadService.EXTRA_DESTINATION, destination.toString()))
        toast(str(R.string.image_saving, folder))
    }
    /** Reuses the original file when it still exists; otherwise asks for a new location. */
    internal fun retryDownload(entry: DownloadEntry) {
        if (!BrowserInput.isWebUrl(entry.source)) { toast(R.string.download_retry_unavailable); return }
        val request = downloadRequest(entry.source, entry.title, false, null, normalSession.cookies().getCookie(entry.source), entry.referer)
            .putExtra(BrowserDownloadService.EXTRA_RECORD, entry.id)
        val existing = Uri.parse(entry.uri)
        val writable = runCatching { contentResolver.openFileDescriptor(existing, "w")?.close() != null }.getOrDefault(false)
        if (writable) {
            startDownloadService(request.putExtra(BrowserDownloadService.EXTRA_DESTINATION, existing.toString()))
            toast(R.string.download_restarted)
        } else pickDownloadDestination(request, entry.title, null)
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
        ContextCompat.startForegroundService(this, Intent(this, BrowserKeepAliveService::class.java))
    }
    override fun onStop() { main.removeCallbacks(memoryCheck); super.onStop() }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() {
        if (::prefs.isInitialized) { persist(); CookieManager.getInstance().flush() }
        if (!BrowserKeepAliveService.running) tabs.forEach { it.view?.onPause() }
        super.onPause()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); permissionRequest?.deny()
        proxyObservation?.close()
        proxyObservation = null
        proxyPanel?.dismiss()
        main.removeCallbacksAndMessages(null)
        if (::searchOverlay.isInitialized) searchOverlay.destroy()
        tabs.forEach(::release); tabManager.tabs.clear(); finishEmptyPrivateSession(); normalSession.end(); io.shutdown(); super.onDestroy()
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
