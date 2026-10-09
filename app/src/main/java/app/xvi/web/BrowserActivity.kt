package app.xvi.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.v2ray.ang.embedded.EmbeddedProxy
import com.v2ray.ang.handler.MmkvManager
import java.util.function.Consumer

/** Native browser chrome. Untrusted pages never receive an Android JavaScript bridge. */
class BrowserActivity : AppCompatActivity() {
    private class Tab(var url: String = "", var title: String = "新标签页", var desktop: Boolean = false,
        var view: WebView? = null, var state: Bundle? = null, var touched: Long = 0,
        val session: BrowserSession, var keepAlive: Boolean = false, var documentVersion: Long = 0, var restoreScroll: Pair<Int, Int>? = null)
    private val normalSession = BrowserSession()
    private var privateSession: BrowserSession? = null
    private var lastNormalTab: Tab? = null
    private var themeRecreatePending = false
    private val tabs = mutableListOf<Tab>()
    private var selected = 0
    private val tab get() = tabs.getOrNull(selected)
    private val isPrivateMode get() = tab?.session?.isPrivate == true
    private lateinit var store: BrowserStore
    private lateinit var network: BrowserProxyNetwork
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var homeSearchLayer: FrameLayout
    private lateinit var homeSearchCapsule: LinearLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var addressCapsule: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: TextView
    private lateinit var tabButtonIcon: ImageView
    private lateinit var tabButtonHost: View
    private lateinit var proxyButton: ImageButton
    private lateinit var addressProxyButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var bottomBar: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findStatus: TextView
    private lateinit var searchOverlay: View
    private lateinit var searchResults: LinearLayout
    private lateinit var searchClipboardCard: View
    private lateinit var searchClipboardText: TextView
    private var findTextWatcher: TextWatcher? = null
    private var findWebView: WebView? = null
    private var lastClosedTab: Pair<Int, Tab>? = null
    private var tabsPanelRefresh: (() -> Unit)? = null
    private var tabsPanelDismiss: (() -> Unit)? = null
    private var bottomBarVisible = true
    private var searchGeneration = 0L
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var base = 0
    private var surface = 0
    private var ink = 0
    private var muted = 0
    private var accent = 0
    private var dark = false
    private var proxyState = "off"
    private var proxyMessage = ""
    private var proxyMessageView: TextView? = null
    private var homeVisible = true
    private var homeSearchPlaceholder: View? = null
    private var homeSearchOverlayTop = 0
    private var ready = false
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permissionRequest: PermissionRequest? = null
    private var permissionDecision: (() -> Unit)? = null
    private var pendingDownload: Intent? = null
    private var initialIntent: String? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val memoryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trimming = false
    private val protectionProbe by lazy { assets.open("tab-protection.js").bufferedReader().use { it.readText() } }
    private val memoryCheck = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            trimTabs(currentMemoryPressure())
            memoryHandler.postDelayed(this, 15_000)
        }
    }
    private val proxyObserver = Consumer<ProxyState> { state ->
        if (!isDestroyed) {
            proxyState = state.text("phase")
            proxyMessage = state.text("message")
            updateProxyButton()
            renderProxyMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = getSharedPreferences("web-appearance", MODE_PRIVATE).getString("theme", "system")
        delegate.localNightMode = when (appearance) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        store = BrowserStore(this)
        BrowserSession.cleanupPreviousProcess()
        if (!BrowserDownloadService.running) store.interruptDownloads("已中断，请重新下载")
        network = BrowserProxyNetwork.get(this)
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        base = Color.parseColor(if (dark) "#171A21" else "#F6F8FC")
        surface = Color.parseColor(if (dark) "#242A35" else "#FFFFFF")
        ink = Color.parseColor(if (dark) "#EEF2FA" else "#1C2940")
        muted = Color.parseColor(if (dark) "#A0ADC3" else "#6A7890")
        accent = Color.parseColor(if (dark) "#9BB8FF" else "#285DE5")
        buildChrome()
        tabs.addAll(store.restoreTabs().map { Tab(it.url, it.title, it.desktop, session = normalSession, keepAlive = it.keepAlive) })
        if (tabs.isEmpty()) tabs.add(Tab(session = normalSession))
        selected = store.preferences.getInt("selected", 0).coerceIn(tabs.indices)
        lastNormalTab = tab
        updateTabCounter()
        initialIntent = if (savedInstanceState == null) externalInput(intent) else null
        network.observe(proxyObserver)
        network.prepare { state ->
            if (!isDestroyed) {
                ready = true
                proxyObserver.accept(state)
                val external = initialIntent
                when {
                    external != null -> addTab(external, session = normalSession)
                    savedInstanceState != null -> showTab()
                    homeVisible -> showHome()
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
                    ::findBar.isInitialized && findBar.visibility == View.VISIBLE -> hideFindInPage()
                    ::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE -> hideSearchOverlay()
                    address.hasFocus() -> hideSearchOverlay()
                    else -> when (BrowserNavigation.back(
                        homeVisible = homeVisible,
                        canGoBack = tab?.view?.canGoBack() == true,
                        hasPage = tab?.url?.isNotEmpty() == true,
                        multipleTabs = BrowserNavigation.canCloseOnBack(isPrivateMode, tabs.count { it.session.isPrivate == isPrivateMode })
                    )) {
                        BrowserNavigationAction.WEB_BACK -> tab?.view?.goBack()
                        BrowserNavigationAction.SHOW_HOME -> home()
                        BrowserNavigationAction.CLOSE_TAB -> closeTab(selected)
                        else -> Unit
                    }
                }
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(text: String, size: Float = 15f, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    private fun selectableBackground(): Drawable? {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
    }
    private fun tintedIcon(resource: Int, color: Int = ink): Drawable? =
        AppCompatResources.getDrawable(this, resource)?.mutate()?.also { DrawableCompat.setTint(it, color) }
    private fun iconAction(resource: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(tintedIcon(resource))
        imageTintList = ColorStateList.valueOf(ink)
        background = selectableBackground()
        contentDescription = description
        tooltipText = description
        isFocusable = true
        minimumWidth = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { click() }
    }
    private fun tabCounterAction(description: String, click: () -> Unit) = FrameLayout(this).apply {
        tabButtonHost = this
        minimumWidth = dp(48)
        minimumHeight = dp(50)
        isFocusable = true
        contentDescription = description
        tooltipText = description
        foreground = selectableBackground()

        val frame = ImageView(this@BrowserActivity).apply {
            setImageDrawable(tintedIcon(R.drawable.ic_tabs_24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tabButtonIcon = frame
        addView(frame, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))

        val count = label("", 9f, ink).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tabButton = count
        addView(count, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { click() }
    }
    private fun textAction(text: String, primary: Boolean = false, click: () -> Unit) = label(text, 14f, if (primary) base else ink).apply {
        gravity = Gravity.CENTER
        background = rounded(if (primary) accent else base, 14)
        minimumHeight = dp(46)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isFocusable = true
        setOnClickListener { click() }
    }
    private fun sectionTitle(text: String) = label(text, 12f, muted).apply {
        setPadding(dp(4), dp(14), dp(4), dp(5))
    }
    private fun domain(url: String): String = runCatching {
        Uri.parse(url).host?.removePrefix("www.")?.ifBlank { null } ?: url
    }.getOrDefault(url)
    private class PanelSurface(private val showAction: () -> Unit, private val dismissAction: () -> Unit) {
        var onDismiss: (() -> Unit)? = null
        fun show() = showAction()
        fun dismiss() = dismissAction()
        fun didDismiss() { onDismiss?.invoke(); onDismiss = null }
    }
    private fun haptic(view: View? = null, constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (!::store.isInitialized || !store.preferences.getBoolean(HAPTICS_PREF, false)) return
        (view ?: window.decorView).performHapticFeedback(constant)
    }
    private fun deleteSurface() = if (dark) Color.rgb(70, 35, 43) else Color.rgb(255, 235, 238)
    private fun deleteAccent() = if (dark) Color.rgb(255, 145, 150) else Color.rgb(198, 40, 40)
    private fun markDeleteTarget(view: View, highlighted: Boolean) {
        view.background = if (highlighted) rounded(deleteSurface(), 12) else selectableBackground()
    }
    /** [source] is the tab that asked for the dialog; a background private page still hides its dialog. */
    private fun browserDialog(source: Tab? = tab): AlertDialog.Builder = object : AlertDialog.Builder(this) {
        override fun create(): AlertDialog = super.create().also {
            if (source?.session?.isPrivate == true || isPrivateMode) it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    private fun confirmDelete(name: String, action: () -> Unit, onCancel: () -> Unit = {}) {
        val displayName = name.trim().ifBlank { "未命名记录" }.take(80)
        val dialog = browserDialog()
            .setTitle("删除记录")
            .setMessage("确定删除「$displayName」？")
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .setPositiveButton("删除") { _, _ -> action() }
            .create()
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(deleteAccent()) }
        dialog.show()
    }
    private fun setBottomBarVisible(visible: Boolean) {
        if (bottomBarVisible == visible || fullView != null || (::findBar.isInitialized && findBar.visibility == View.VISIBLE)) return
        bottomBarVisible = visible
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
    }
    private fun setAddressBarVisible(visible: Boolean) {
        if (!::addressRow.isInitialized || fullView != null) return
        if (!visible && ::findBar.isInitialized && findBar.visibility == View.VISIBLE) return
        val next = if (visible) View.VISIBLE else View.GONE
        if (addressRow.visibility == next) return
        if (!visible && address.hasFocus()) {
            address.clearFocus()
            hideKeyboard()
        }
        addressRow.visibility = next
    }
    private fun placeAddressRowAtBottom() {
        if (!::root.isInitialized || !::addressRow.isInitialized || !::progress.isInitialized) return
        (addressRow.parent as? ViewGroup)?.removeView(addressRow)
        val progressIndex = root.indexOfChild(progress)
        root.addView(addressRow, (progressIndex + 1).coerceAtMost(root.childCount))
    }
    private fun setSearchOverlayTop(top: Int?) {
        if (!::searchOverlay.isInitialized) return
        val params = (searchOverlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(-1, -1)
        params.width = -1
        params.height = -1
        params.leftMargin = 0
        params.rightMargin = 0
        params.bottomMargin = 0
        params.topMargin = top?.coerceAtLeast(0) ?: 0
        searchOverlay.layoutParams = params
    }
    private fun focusHomeSearch() {
        if (!homeVisible || !::homeSearchLayer.isInitialized) return
        val placeholder = homeSearchPlaceholder ?: return
        val location = IntArray(2)
        val contentLocation = IntArray(2)
        placeholder.getLocationOnScreen(location)
        content.getLocationOnScreen(contentLocation)
        homeSearchCapsule.layoutParams = FrameLayout.LayoutParams(
            placeholder.width.coerceAtLeast(1), placeholder.height.coerceAtLeast(dp(58))
        ).apply {
            leftMargin = (location[0] - contentLocation[0]).coerceAtLeast(0)
            topMargin = (location[1] - contentLocation[1]).coerceAtLeast(0)
        }
        homeSearchOverlayTop = (location[1] - contentLocation[1] + placeholder.height).coerceAtLeast(0)
        (address.parent as? ViewGroup)?.removeView(address)
        (clearButton.parent as? ViewGroup)?.removeView(clearButton)
        homeSearchCapsule.addView(address, 1, LinearLayout.LayoutParams(0, dp(48), 1f))
        homeSearchCapsule.addView(clearButton, 2, LinearLayout.LayoutParams(dp(48), dp(48)))
        homeSearchLayer.visibility = View.VISIBLE
        setSearchOverlayTop(homeSearchOverlayTop)
        address.requestFocus()
        address.postDelayed({
            if (!isDestroyed && address.hasFocus()) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
    }
    private fun restoreAddressToChrome() {
        if (!::homeSearchCapsule.isInitialized || address.parent !== homeSearchCapsule) return
        (address.parent as? ViewGroup)?.removeView(address)
        (clearButton.parent as? ViewGroup)?.removeView(clearButton)
        addressCapsule.addView(address, 1, LinearLayout.LayoutParams(0, dp(48), 1f))
        addressCapsule.addView(clearButton, 2, LinearLayout.LayoutParams(dp(48), dp(48)))
        homeSearchLayer.visibility = View.GONE
        homeSearchOverlayTop = 0
    }
    private fun proxyStateColor() = when (proxyState) {
        "running" -> if (dark) Color.rgb(133, 209, 143) else Color.rgb(46, 125, 50)
        "starting" -> if (dark) Color.rgb(255, 202, 40) else Color.rgb(245, 124, 0)
        "reconnecting" -> if (dark) Color.rgb(255, 183, 77) else Color.rgb(230, 81, 0)
        "failed" -> if (dark) Color.rgb(255, 145, 150) else Color.rgb(198, 40, 40)
        else -> muted
    }
    private fun updateProxyButton() {
        val color = ColorStateList.valueOf(proxyStateColor())
        if (::proxyButton.isInitialized) {
            proxyButton.imageTintList = color
            proxyButton.contentDescription = "网络代理：${proxyLabel()}"
        }
        if (::addressProxyButton.isInitialized) {
            addressProxyButton.imageTintList = color
            addressProxyButton.contentDescription = "网络代理：${proxyLabel()}"
        }
    }
    private fun showPanel(popup: PopupWindow) {
        setBottomBarVisible(true)
        val width = bottomBar.width.coerceAtLeast(root.width - root.paddingLeft - root.paddingRight)
        val panelContent = popup.contentView
        panelContent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.88f).toInt(), View.MeasureSpec.AT_MOST)
        )
        val height = panelContent.measuredHeight
        popup.width = width
        popup.height = height
        popup.showAsDropDown(bottomBar, 0, -bottomBar.height - height)
        panelContent.post {
            val container = panelContent.rootView
            val p = container.layoutParams as? WindowManager.LayoutParams
            if (p != null) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                if (isPrivateMode) p.flags = p.flags or WindowManager.LayoutParams.FLAG_SECURE
                p.dimAmount = 0.45f
                runCatching {
                    (getSystemService(WINDOW_SERVICE) as? WindowManager)?.updateViewLayout(container, p)
                }
            }
        }
    }
    private fun sheet(title: String, headerAction: ((PanelSurface) -> View)? = null): Pair<PanelSurface, LinearLayout> {
        val panel = column().apply {
            background = rounded(surface, 24)
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }
        val popup = PopupWindow(panel, 1, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(16).toFloat()
            animationStyle = android.R.style.Animation_Dialog
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val surface = PanelSurface({ showPanel(popup) }, { popup.dismiss() })
        popup.setOnDismissListener { surface.didDismiss() }
        val header = row()
        header.addView(label(title, 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(headerAction?.invoke(surface) ?: iconAction(R.drawable.ic_close_24, "关闭") { surface.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        return surface to panel
    }
    private fun buildSearchOverlay() {
        val overlay = column().apply {
            setBackgroundColor(base)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        searchClipboardCard = row().apply {
            background = rounded(surface, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            visibility = View.GONE
            val clipIcon = ImageView(this@BrowserActivity).apply {
                setImageDrawable(tintedIcon(R.drawable.ic_copy_24, accent))
            }
            addView(clipIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
            searchClipboardText = label("", 14f, ink).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(4), 0)
            }
            addView(searchClipboardText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("前往", 13f, accent).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        }
        overlay.addView(searchClipboardCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val enginesRow = row().apply { setPadding(0, dp(4), 0, dp(8)) }
        val currentEngine = store.preferences.getString("engine", "必应") ?: "必应"
        val engineViews = mutableListOf<TextView>()
        fun updateEnginePills(selectedName: String) {
            engineViews.forEach { pill ->
                val active = pill.text == selectedName
                pill.setTextColor(if (active) base else muted)
                pill.background = rounded(if (active) accent else surface, 12)
            }
        }
        BrowserInput.engines.keys.forEach { name ->
            val pill = label(name, 12f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusable = true
                setOnClickListener {
                    store.preferences.edit().putString("engine", name).apply()
                    updateEnginePills(name)
                    address.hint = if (isPrivateMode) "无痕 · 在 $name 搜索或输入网址" else "在 $name 搜索或输入网址"
                    val query = address.text.toString().trim()
                    if (query.isNotEmpty()) updateSearchMatches(query)
                }
            }
            engineViews.add(pill)
            enginesRow.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        updateEnginePills(currentEngine)
        overlay.addView(enginesRow)

        searchResults = column()
        val scroll = ScrollView(this).apply {
            addView(searchResults)
            isFillViewport = true
        }
        overlay.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        searchOverlay = overlay
    }
    private fun updateSearchMatches(query: String) {
        if (!::searchResults.isInitialized) return
        pendingSearch?.let(searchHandler::removeCallbacks)
        val generation = ++searchGeneration
        val private = isPrivateMode
        val work = Runnable {
            io.execute {
                val isBlank = query.isBlank()
                val items = if (isBlank) {
                    if (private) emptyList() else store.entries(false).take(6)
                } else {
                    val history = if (private) emptyList() else store.entries(false, query)
                    val bookmarks = store.entries(true, query)
                    (bookmarks + history).distinctBy { it.url }.take(15)
                }
                val bookmarkUrls = store.entries(true).asSequence().map { it.url }.toHashSet()
                val matches = items.map { it to bookmarkUrls.contains(it.url) }
                val currentEngine = store.preferences.getString("engine", "必应") ?: "必应"
                runOnUiThread {
                    if (isDestroyed || generation != searchGeneration || !::searchOverlay.isInitialized ||
                        searchOverlay.visibility != View.VISIBLE || address.text.toString().trim() != query) return@runOnUiThread
                    renderSearchMatches(query, currentEngine, matches)
                }
            }
        }
        pendingSearch = work
        searchHandler.postDelayed(work, 80L)
    }
    private fun renderSearchMatches(query: String, currentEngine: String, matches: List<Pair<PageEntry, Boolean>>) {
        searchResults.removeAllViews()
        val isBlank = query.isBlank()
        if (matches.isEmpty()) {
            if (!isBlank) {
                val searchItem = menuRow(R.drawable.ic_search_24, "在 $currentEngine 搜索「$query」", "按回车开始搜索") {
                    navigate(query)
                }
                searchResults.addView(searchItem, LinearLayout.LayoutParams(-1, dp(60)))
            }
            return
        }
        matches.forEach { (entry, isBm) ->
            val item = row().apply {
                minimumHeight = dp(56)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = selectableBackground()
                isFocusable = true
                addView(ImageView(this@BrowserActivity).apply {
                    setImageDrawable(tintedIcon(if (isBm) R.drawable.ic_bookmark_24 else R.drawable.ic_history_24, if (isBm) accent else muted))
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                val copy = column().apply { setPadding(dp(8), 0, dp(4), 0) }
                copy.addView(label(entry.title, 14f, ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                copy.addView(label(domain(entry.url), 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                val fillBtn = iconAction(R.drawable.ic_arrow_back_24, "填入地址栏") {
                    address.setText(entry.url)
                    address.setSelection(entry.url.length)
                }.apply { rotation = 135f }
                addView(fillBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
                setOnClickListener { navigate(entry.url) }
            }
            searchResults.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        }
    }
    private fun showSearchOverlay() {
        if (!::searchOverlay.isInitialized) return
        setBottomBarVisible(true)
        setSearchOverlayTop(if (homeVisible && homeSearchLayer.visibility == View.VISIBLE) homeSearchOverlayTop else null)
        searchOverlay.visibility = View.VISIBLE
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrBlank() && clip != address.text.toString()) {
            searchClipboardCard.visibility = View.VISIBLE
            searchClipboardText.text = clip
            searchClipboardCard.setOnClickListener { navigate(clip) }
        } else {
            searchClipboardCard.visibility = View.GONE
        }
        updateSearchMatches(address.text.toString().trim())
    }
    private fun hideSearchOverlay() {
        pendingSearch?.let(searchHandler::removeCallbacks)
        pendingSearch = null
        searchGeneration++
        setSearchOverlayTop(null)
        if (::searchOverlay.isInitialized) searchOverlay.visibility = View.GONE
        address.clearFocus()
        hideKeyboard()
        restoreAddressToChrome()
        if (tab?.url.isNullOrEmpty()) {
            placeAddressRowAtBottom()
            addressRow.visibility = View.GONE
        }
        updateChrome()
    }
    private fun buildFindBar() {
        findBar = row().apply {
            background = rounded(surface, 16)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        findInput = edit("在网页中查找").apply {
            background = null
            setPadding(dp(4), dp(4), dp(4), dp(4))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        findStatus = label("", 12f, muted).apply {
            setPadding(dp(4), 0, dp(4), 0)
        }
        val prevBtn = iconAction(R.drawable.ic_arrow_up_24, "上一处") { findWebView?.findNext(false) }
        val nextBtn = iconAction(R.drawable.ic_arrow_down_24, "下一处") { findWebView?.findNext(true) }
        val closeFindBtn = iconAction(R.drawable.ic_close_24, "关闭查找") { hideFindInPage() }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        findBar.addView(findStatus, LinearLayout.LayoutParams(-2, -2))
        findBar.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(closeFindBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
    }
    private fun detachFindSession() {
        findTextWatcher?.let { watcher ->
            if (::findInput.isInitialized) findInput.removeTextChangedListener(watcher)
        }
        findTextWatcher = null
        findWebView?.let {
            it.clearMatches()
            it.setFindListener(null)
        }
        findWebView = null
    }
    private fun findInPage() {
        val web = tab?.view ?: return
        detachFindSession()
        setBottomBarVisible(true)
        setAddressBarVisible(true)
        addressRow.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findStatus.text = ""
        findWebView = web
        web.setFindListener { active, total, done ->
            if (done && findWebView === web) findStatus.text = if (total == 0) "无结果" else "${active + 1}/$total"
        }
        findTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (findWebView === web && tab?.view === web) web.findAllAsync(s.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        findInput.addTextChangedListener(findTextWatcher)
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (findWebView === web) web.findNext(true)
                true
            } else false
        }
        // The menu is a focusable PopupWindow. Wait for its dismissal to finish
        // before asking the activity window to show the IME.
        findInput.postDelayed({
            if (!isDestroyed && findBar.visibility == View.VISIBLE) {
                findInput.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 300L)
    }
    private fun hideFindInPage() {
        if (!::findBar.isInitialized) return
        detachFindSession()
        if (findBar.visibility != View.VISIBLE) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
        findInput.setOnEditorActionListener(null)
        findBar.visibility = View.GONE
        addressRow.visibility = View.VISIBLE
    }
    private fun menuRow(icon: Int, title: String, subtitle: String? = null, click: () -> Unit) = row().apply {
        minimumHeight = dp(if (subtitle == null) 56 else 66)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = selectableBackground()
        isFocusable = true
        addView(ImageView(this@BrowserActivity).apply { setImageDrawable(tintedIcon(icon, accent)); contentDescription = null }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val copy = column()
        copy.addView(label(title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        subtitle?.let { copy.addView(label(it, 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { click() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun edit(hint: String) = EditText(this).apply {
        this.hint = hint; setTextColor(ink); setHintTextColor(muted); textSize = 16f; isSingleLine = true
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    private fun buildChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        root = column().apply { setBackgroundColor(base) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        content = FrameLayout(this)
        pageHost = FrameLayout(this)
        content.addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        buildSearchOverlay()
        content.addView(searchOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent); visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        addressRow = row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        addressCapsule = row().apply { background = rounded(surface); minimumHeight = dp(48) }
        addressProxyButton = iconAction(R.drawable.ic_proxy_24, "网络代理") { showProxy() }
        addressCapsule.addView(addressProxyButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2) })
        proxyButton = iconAction(R.drawable.ic_star_24, "网络代理") { showProxy() }
        address = edit("搜索或输入网址").apply {
            background = null; setPadding(0, 0, 0, 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showSearchOverlay()
                    updateChrome()
                } else hideSearchOverlay()
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    navigate(text.toString()); true
                } else false
            }
        }
        addressCapsule.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        clearButton = iconAction(R.drawable.ic_close_24, "清除地址") { address.text.clear() }
        clearButton.visibility = View.GONE
        addressCapsule.addView(clearButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (address.hasFocus()) {
                    clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    updateSearchMatches(s?.toString().orEmpty().trim())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshButton = iconAction(R.drawable.ic_refresh_24, "刷新或停止加载") {
            tab?.view?.let { if (it.progress < 100) it.stopLoading() else network.prepare { _ -> if (!isDestroyed) it.reload() } }
        }
        addressCapsule.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        homeSearchLayer = FrameLayout(this).apply { visibility = View.GONE }
        homeSearchCapsule = row().apply {
            background = rounded(surface, 18)
            minimumHeight = dp(58)
            setPadding(dp(18), 0, dp(12), 0)
            addView(ImageView(this@BrowserActivity).apply {
                setImageDrawable(tintedIcon(R.drawable.ic_search_24, muted))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        homeSearchLayer.addView(homeSearchCapsule, FrameLayout.LayoutParams(-1, dp(58)).apply {
            leftMargin = dp(28); rightMargin = dp(28); topMargin = dp(190)
        })
        content.addView(homeSearchLayer, FrameLayout.LayoutParams(-1, -1))
        addressRow.addView(addressCapsule, LinearLayout.LayoutParams(-1, -2))
        root.addView(addressRow)
        buildFindBar()
        root.addView(findBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
        bottomBar = object : LinearLayout(this) {
            private var startX = 0f
            private var startY = 0f
            private var isSwiping = false

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.x; startY = ev.y; isSwiping = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        if (Math.abs(dx) > dp(16) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            isSwiping = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                return isSwiping
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        if (isSwiping) {
                            val dx = ev.x - startX
                            val group = tabs.indices.filter { tabs[it].session === tab?.session }
                            val position = group.indexOf(selected)
                            val next = when {
                                dx > dp(40) -> group.getOrNull(position - 1)
                                dx < -dp(40) -> group.getOrNull(position + 1)
                                else -> null
                            }
                            if (next != null) { haptic(this); selected = next; showTab() }
                            isSwiping = false
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> isSwiping = false
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        backButton = iconAction(R.drawable.ic_arrow_back_24, "后退") { onBackPressedDispatcher.onBackPressed() }
        forwardButton = iconAction(R.drawable.ic_arrow_forward_24, "前进") {
            when (BrowserNavigation.forward(
                homeVisible = homeVisible,
                canGoForward = tab?.view?.canGoForward() == true,
                hasPage = tab?.url?.isNotEmpty() == true
            )) {
                BrowserNavigationAction.SHOW_PAGE -> showTab()
                BrowserNavigationAction.WEB_FORWARD -> tab?.view?.goForward()
                else -> Unit
            }
        }
        val tabCounter = tabCounterAction("标签页") { showTabs() }
        listOf(backButton, forwardButton, iconAction(R.drawable.ic_home_24, "首页") { home() }, tabCounter, iconAction(R.drawable.ic_more_24, "菜单") { showMenu() }).forEach {
            bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun showHome() {
        homeVisible = true
        hideSearchOverlay()
        hideFindInPage()
        placeAddressRowAtBottom()
        addressRow.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        setBottomBarVisible(true)
        pageHost.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val home = column().apply { setPadding(dp(28), dp(42), dp(28), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val header = FrameLayout(this).apply { setPadding(0, 0, dp(6), 0) }
        val title = label(if (isPrivateMode) "无痕浏览" else getString(R.string.app_name), 38f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER }
        header.addView(title, FrameLayout.LayoutParams(-1, dp(70)))
        (proxyButton.parent as? ViewGroup)?.removeView(proxyButton)
        header.addView(proxyButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { marginEnd = dp(6) })
        home.addView(header, LinearLayout.LayoutParams(-1, dp(70)))
        val slogan = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        slogan.addView(label(getString(R.string.home_slogan_zh), 14f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(26)))
        slogan.addView(label(getString(R.string.home_slogan_en), 12f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(22)))
        home.addView(slogan, LinearLayout.LayoutParams(-1, dp(48)))
        val search = label("搜索或输入网址", 16f, muted).apply {
            background = rounded(surface, 18); setPadding(dp(18), 0, dp(20), 0)
            setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_search_24, muted), null, null, null)
            compoundDrawablePadding = dp(8)
            setOnClickListener { focusHomeSearch() }
        }
        homeSearchPlaceholder = search
        home.addView(search, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(30); bottomMargin = dp(28) })
        val bookmarks = store.entries(true)
        if (isPrivateMode) home.addView(label("关闭所有无痕标签后清除本次浏览数据。\n书签和下载文件会保留；网站及代理仍可能记录访问。", 13f, muted))
        val recent = if (isPrivateMode) emptyList() else store.recentSites(5)
        val shortcuts = bookmarks.ifEmpty {
            if (recent.isEmpty()) listOf(
                PageEntry("https://www.bing.com", "必应"), PageEntry("https://www.baidu.com", "百度"),
                PageEntry("https://www.wikipedia.org", "维基百科"), PageEntry("https://github.com", "GitHub")
            ) else emptyList()
        }
        if (shortcuts.isNotEmpty()) {
            val shortcutSection = column()
            shortcutSection.addView(sectionTitle(if (bookmarks.isNotEmpty()) "书签" else "常用入口"), LinearLayout.LayoutParams(-1, -2))
            val shortcutRows = column()
            val shortcutActions = row().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
            }
            shortcutSection.addView(shortcutRows, LinearLayout.LayoutParams(-1, -2))
            shortcutSection.addView(shortcutActions, LinearLayout.LayoutParams(-1, dp(50)))
            var expanded = false
            fun renderShortcuts() {
                shortcutRows.removeAllViews()
                val visible = if (bookmarks.isEmpty()) shortcuts
                else bookmarks.take(HomeBookmarkPresentation.visibleCount(bookmarks.size, expanded))
                visible.chunked(4).forEach { group ->
                    val strip = row()
                    group.forEach { entry ->
                        val item = column().apply { gravity = Gravity.CENTER; setPadding(dp(3), dp(6), dp(3), dp(12)) }
                        val badge = label(entry.title.take(1), 23f, accent).apply { gravity = Gravity.CENTER; background = rounded(surface, 19) }
                        item.addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))
                        item.addView(label(entry.title, 12f, muted).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(34)))
                        item.setOnClickListener { navigate(entry.url) }
                        item.contentDescription = entry.title
                        strip.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    repeat(4 - group.size) { strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                    shortcutRows.addView(strip, LinearLayout.LayoutParams(-1, -2))
                }
                shortcutActions.removeAllViews()
                if (HomeBookmarkPresentation.showsToggle(bookmarks.size)) {
                    shortcutActions.visibility = View.VISIBLE
                    shortcutActions.addView(textAction(if (expanded) "收起" else "展开") {
                        expanded = !expanded
                        renderShortcuts()
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    if (HomeBookmarkPresentation.showsMore(bookmarks.size, expanded)) {
                        shortcutActions.addView(textAction("更多") { showLibrary(true) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
                    }
                } else shortcutActions.visibility = View.GONE
            }
            renderShortcuts()
            home.addView(shortcutSection, LinearLayout.LayoutParams(-1, -2))
        }
        if (recent.isNotEmpty()) {
            home.addView(sectionTitle("最近访问"), LinearLayout.LayoutParams(-1, -2))
            recent.take(5).forEach { entry ->
                val item = menuRow(R.drawable.ic_home_24, entry.title, domain(entry.url)) { navigate(entry.url) }
                markDeleteTarget(item, false)
                item.contentDescription = "${entry.title}，${domain(entry.url)}，长按删除"
                item.setOnLongClickListener {
                    haptic(it)
                    markDeleteTarget(item, true)
                    confirmDelete(entry.title, {
                        store.removeSiteHistory(entry.url)
                        showHome()
                    }, { markDeleteTarget(item, false) })
                    true
                }
                home.addView(item, LinearLayout.LayoutParams(-1, dp(62)))
            }
        }
        scroll.addView(home)
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }
    private fun persist() {
        val ordinary = tabs.filter { !it.session.isPrivate }
        val active = ordinary.indexOf(lastNormalTab).coerceAtLeast(0)
        store.saveTabs(ordinary.map { SavedTab(it.url, it.title, it.desktop, it.keepAlive) }, active)
    }
    private fun switchGroup(private: Boolean) {
        val existing = if (!private && tabs.contains(lastNormalTab)) lastNormalTab else tabs.lastOrNull { it.session.isPrivate == private }
        if (existing != null) { selected = tabs.indexOf(existing); showTab(); return }
        if (tabs.size >= 50) { toast("最多打开 50 个标签，请先关闭一些标签"); return }
        addTab(session = if (private) obtainPrivateSession() ?: return else normalSession)
    }
    private fun obtainPrivateSession(): BrowserSession? {
        BrowserSession.privateUnavailableReason()?.let { toast(it); return null }
        return privateSession ?: BrowserSession(true).also { privateSession = it }
    }
    private fun finishEmptyPrivateSession() {
        val session = privateSession ?: return
        if (tabs.any { it.session === session }) return
        privateSession = null
        if (lastClosedTab?.second?.session === session) lastClosedTab = null
        if (pendingDownload?.getBooleanExtra("private", false) == true) pendingDownload = null
        session.end { success -> if (!success) toast("无痕数据清理未完成，将在下次启动时重试") }
        if (themeRecreatePending && !isFinishing && !isDestroyed) {
            themeRecreatePending = false
            pageHost.post { if (!isDestroyed) { persist(); recreate() } }
        }
    }
    private fun closeGroup(private: Boolean, except: Tab? = null) {
        val current = tab
        val removed = tabs.filter { it.session.isPrivate == private && it !== except }
        removed.forEach(::release)
        tabs.removeAll(removed.toSet())
        ensureOrdinaryTab()
        selected = tabs.indexOf(except).takeIf { it >= 0 } ?: tabs.indexOf(current).takeIf { it >= 0 } ?: ordinaryIndex()
        finishEmptyPrivateSession()
        showTab(); persist()
    }
    /** Ordinary browsing always keeps one tab, so closing tabs never silently enters private mode. */
    private fun ensureOrdinaryTab() { if (tabs.none { !it.session.isPrivate }) tabs.add(Tab(session = normalSession)) }
    private fun ordinaryIndex() = tabs.indexOf(lastNormalTab).takeIf { it >= 0 } ?: tabs.indexOfLast { !it.session.isPrivate }
    private fun updateTabCounter() {
        if (!::tabButton.isInitialized) return
        val private = isPrivateMode
        val count = tabs.count { it.session.isPrivate == private }
        tabButton.text = BrowserNavigation.tabCountLabel(count)
        tabButtonIcon.setImageDrawable(tintedIcon(if (private) R.drawable.ic_ghost_counter_24 else R.drawable.ic_tabs_24, if (private) accent else ink))
        tabButton.setTextColor(if (private) accent else ink)
        tabButtonHost.contentDescription = "${if (private) "无痕" else "普通"}标签页，$count 个"
        tabButtonHost.tooltipText = tabButtonHost.contentDescription
    }
    private fun updateChrome() {
        val url = tab?.url.orEmpty()
        val private = isPrivateMode
        address.hint = if (private) "无痕搜索或输入网址" else "搜索或输入网址"
        address.contentDescription = if (private) "无痕浏览地址栏" else "地址栏"
        val imeOptions = if (private) address.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            else address.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        if (imeOptions != address.imeOptions) {
            address.imeOptions = imeOptions
            // A focused field keeps its old input connection until the keyboard restarts it.
            if (address.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(address)
        }
        address.importantForAutofill = if (private) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_AUTO
        if (private) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!address.hasFocus()) address.setText(if (homeVisible) "" else url)
        // The lock only means HTTPS; private mode gets its own ghost so plain HTTP never looks secure.
        val indicators = listOfNotNull(
            if (private) tintedIcon(R.drawable.ic_ghost_24, accent) else null,
            if (url.startsWith("https://", ignoreCase = true)) tintedIcon(R.drawable.ic_lock_24, muted) else null
        )
        address.setCompoundDrawablesWithIntrinsicBounds(
            if (indicators.size < 2) indicators.firstOrNull() else LayerDrawable(indicators.toTypedArray()).apply {
                val offset = indicators[0].intrinsicWidth + dp(4)
                setLayerInset(0, 0, 0, offset, 0); setLayerInset(1, offset, 0, 0, 0)
            },
            null, null, null
        )
        address.compoundDrawablePadding = dp(5)
        clearButton.visibility = if (address.hasFocus() && address.text.isNotEmpty()) View.VISIBLE else View.GONE
        updateTabCounter()
        val canReturnToPage = homeVisible && url.isNotEmpty()
        val canGoForward = canReturnToPage || (!homeVisible && tab?.view?.canGoForward() == true)
        forwardButton.alpha = if (canGoForward) 1f else .3f
        val closable = BrowserNavigation.canCloseOnBack(private, tabs.count { it.session.isPrivate == private })
        backButton.alpha = if (!homeVisible && (tab?.url?.isNotEmpty() == true || closable)) 1f else .3f
        val loading = tab?.view?.progress ?: 100
        progress.progress = loading
        progress.visibility = if (url.isNotEmpty() && loading < 100) View.VISIBLE else View.INVISIBLE
        refreshButton.setImageDrawable(tintedIcon(if (loading < 100 && url.isNotEmpty()) R.drawable.ic_close_24 else R.drawable.ic_refresh_24))
        refreshButton.contentDescription = if (loading < 100 && url.isNotEmpty()) "停止加载" else "刷新页面"
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0) }
    private fun home() {
        hideFindInPage()
        hideSearchOverlay()
        address.clearFocus(); hideKeyboard(); showHome(); updateChrome(); persist()
    }
    private fun navigate(input: String) {
        val url = BrowserInput.resolve(input, store.preferences.getString("engine", "必应") ?: "必应")
        if (url.isEmpty()) return
        hideFindInPage()
        hideSearchOverlay()
        address.clearFocus(); hideKeyboard()
        val target = tab ?: return
        if (BrowserNavigation.shouldCreateNewTabFromHome(homeVisible, target.url.isNotEmpty())) addTab(url)
        else load(target, url)
    }
    private fun load(target: Tab, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        if (tab === target) homeVisible = false
        target.url = url
        target.state = null
        target.restoreScroll = null
        if (!ready) { initialIntent = null; return }
        network.prepare {
            if (!isDestroyed && BrowserNavigation.shouldApplyPreparedNavigation(tabs.contains(target), target.url, url)) {
                val view = target.view ?: createWebView(target).also { target.view = it }
                view.loadUrl(url)
                if (BrowserNavigation.shouldShowPageAfterPreparation(tab === target, homeVisible)) showTab(false)
                persist()
            }
        }
    }
    private fun addTab(url: String = "", activate: Boolean = true, session: BrowserSession = tab?.session ?: normalSession): Tab? {
        if (tabs.size >= 50) { toast("最多打开 50 个标签，请先关闭一些标签"); return null }
        val newTab = Tab(session = session)
        tabs.add(newTab)
        if (activate) {
            selected = tabs.lastIndex
            showTab()
        } else updateTabCounter()
        tabsPanelRefresh?.invoke()
        if (url.isNotEmpty()) {
            val resolved = BrowserInput.resolve(url, store.preferences.getString("engine", "必应") ?: "必应")
            if (resolved.isNotEmpty()) {
                if (activate) navigate(resolved) else load(newTab, resolved)
            }
        }
        persist()
        return newTab
    }
    private fun closeTab(index: Int) {
        val closing = tabs[index]
        val current = tab
        release(closing); tabs.removeAt(index)
        ensureOrdinaryTab()
        selected = if (closing === current) {
            BrowserNavigation.neighborInGroup(tabs.map { it.session.isPrivate }, index, closing.session.isPrivate) ?: ordinaryIndex()
        } else tabs.indexOf(current)
        finishEmptyPrivateSession()
        showTab(); persist()
        tabsPanelRefresh?.invoke()
    }
    private fun release(target: Tab) {
        if (findWebView === target.view) hideFindInPage()
        target.view?.let { target.session.release(it) }
        target.view = null
    }
    private fun showTab(load: Boolean = true) {
        tab?.takeIf { !it.session.isPrivate }?.let { lastNormalTab = it }
        hideFullScreen()
        hideSearchOverlay()
        hideFindInPage()
        homeVisible = false
        placeAddressRowAtBottom()
        setAddressBarVisible(true)
        setBottomBarVisible(true)
        val target = tab ?: return
        // Detach background views without blanket onPause(), which can interrupt media.
        target.touched = android.os.SystemClock.elapsedRealtime()
        pageHost.removeAllViews()
        if (target.url.isEmpty()) showHome()
        else {
            val fresh = target.view == null
            val web = target.view ?: createWebView(target).also { target.view = it }
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            pageHost.addView(web, FrameLayout.LayoutParams(-1, -1)); web.onResume()
            if (fresh && load) network.prepare {
                if (!isDestroyed && tabs.contains(target) && target.view === web) {
                    if (target.state?.let { web.restoreState(it) } == null) web.loadUrl(target.url)
                    target.state = null
                }
            }
        }
        updateChrome()
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
    private fun trimTabs(pressure: TabMemoryPolicy.Pressure) {
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
                    android.os.SystemClock.elapsedRealtime() - touched, pressure)
            if (!eligible()) { next(); return }
            var answered = false
            val timeout = Runnable { if (!answered) { answered = true; next() } }
            memoryHandler.postDelayed(timeout, 750)
            try {
                web!!.evaluateJavascript(protectionProbe) { result ->
                    if (answered) return@evaluateJavascript
                    answered = true
                    memoryHandler.removeCallbacks(timeout)
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
                if (!answered) { answered = true; memoryHandler.removeCallbacks(timeout); next() }
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
        val night = when (getSharedPreferences("web-appearance", MODE_PRIVATE).getString("theme", "system")) {
            "light" -> false
            "dark" -> true
            else -> newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        if (night == dark) return
        // Recreating ends the private session, so keep current colors until its last tab closes.
        if (privateSession == null) { persist(); recreate() } else themeRecreatePending = true
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimTabs(TabMemoryPolicy.Pressure.CRITICAL) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(target: Tab): WebView = target.session.createWebView(this).apply {
        setBackgroundColor(surface)
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true; setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = store.preferences.getInt("textZoom", 100)
            if (target.desktop) userAgentString = DESKTOP_UA
        }
        if (dark) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
            }
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
        }
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (tab !== target || target.url.isEmpty() || fullView != null || (::findBar.isInitialized && findBar.visibility == View.VISIBLE)) return@setOnScrollChangeListener
            val dy = scrollY - oldScrollY
            if (dy > 30 && scrollY > dp(60)) {
                setAddressBarVisible(false)
                setBottomBarVisible(false)
            } else if (dy < -30 || scrollY < dp(20)) {
                setAddressBarVisible(true)
                setBottomBarVisible(true)
            }
        }
        target.session.cookies().setAcceptCookie(true)
        target.session.cookies().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (BrowserInput.isWebUrl(request.url.toString())) return false
                if (request.isForMainFrame && request.hasGesture()) externalLink(request.url, target)
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (target.view !== view || !tabs.contains(target)) return
                target.documentVersion++
                if (BrowserInput.isWebUrl(url)) {
                    target.url = url
                    if (tab === target) {
                        if (view.parent == null) { pageHost.removeAllViews(); pageHost.addView(view, FrameLayout.LayoutParams(-1, -1)) }
                        updateChrome()
                    }
                    persist()
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (target.view !== view || !tabs.contains(target)) return
                if (BrowserInput.isWebUrl(url)) {
                    target.url = url; target.title = view.title?.takeIf { it.isNotBlank() } ?: url
                    target.session.visit(store, target.title, url); persist()
                }
                if (tab === target) updateChrome()
                target.restoreScroll?.let { position ->
                    target.restoreScroll = null
                    val document = target.documentVersion
                    view.post {
                        if (target.view === view && target.documentVersion == document) view.scrollTo(position.first, position.second)
                    }
                }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (target.view !== view || !tabs.contains(target)) return
                if (BrowserInput.isWebUrl(url)) {
                    target.url = url
                    if (tab === target) updateChrome()
                    persist()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && tab === target) { setBottomBarVisible(true); showPageError("页面暂时无法打开", "请检查连接后重试。") }
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (tab === target) { setBottomBarVisible(true); showPageError("网站证书无效", "连接已停止，请检查网址后重试。") }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (findWebView === view) hideFindInPage()
                target.view = null
                target.session.release(view)
                if (tab === target) { setBottomBarVisible(true); showPageError("页面需要重新加载", "点按重试即可。") }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { if (tab === target) updateChrome() }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) { target.title = title; persist() } }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture || tabs.size >= 50) return false
                if (!tabs.contains(target)) return false
                val child = addTab(activate = false, session = target.session) ?: return false
                val childView = createWebView(child).also { child.view = it }
                child.url = "about:blank"
                (resultMsg.obj as WebView.WebViewTransport).webView = childView
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { tabs.indexOfFirst { it.view === window }.takeIf { it >= 0 }?.let { closeTab(it) } }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = callback
                return try { startActivityForResult(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE), UPLOAD); true }
                catch (_: ActivityNotFoundException) { fileCallback?.onReceiveValue(null); fileCallback = null; toast("无法选择文件"); true }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = view; fullCallback = callback; root.visibility = View.GONE
                (window.decorView as android.view.ViewGroup).addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            override fun onHideCustomView() = hideFullScreen()
            override fun onPermissionRequest(request: PermissionRequest) { websitePermission(request, target) }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (permissionRequest === request) { permissionRequest = null; permissionDecision = null }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val site = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
                browserDialog(target).setTitle("允许 $site 访问位置？")
                    .setPositiveButton("允许一次") { _, _ ->
                        askRuntimePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                            val allowed = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            callback.invoke(origin, allowed, false)
                        }
                    }.setNegativeButton("拒绝") { _, _ -> callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }.show()
            }
        }
        setDownloadListener { url, ua, disposition, mime, _ -> download(target, url, ua, disposition, mime) }
        setOnLongClickListener {
            val result = hitTestResult
            val url = result.extra
            if (url != null && BrowserInput.isWebUrl(url)) {
                haptic(this)
                val image = result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                browserDialog(target).setTitle(if (image) "图片" else "链接")
                    .setItems(arrayOf("在新标签中打开", "复制链接", if (image) "保存图片" else "下载链接")) { _, which ->
                        when (which) { 0 -> if (tabs.contains(target)) addTab(url, activate = false, session = target.session); 1 -> copy(url, target.session.isPrivate); 2 -> download(target, url, settings.userAgentString, null, null) }
                    }.show()
                true
            } else false
        }
    }
    private fun showPageError(title: String, message: String) {
        hideFindInPage()
        setAddressBarVisible(true)
        setBottomBarVisible(true)
        pageHost.removeAllViews()
        val panel = column().apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(24), dp(30), dp(24)) }
        panel.addView(label(title, 22f).apply { gravity = Gravity.CENTER })
        panel.addView(label(message, 15f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) })
        val actions = row().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        actions.addView(textAction("重试", true) { tab?.let { release(it) }; showTab() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (proxyState == "failed" || proxyState == "starting" || proxyState == "reconnecting") {
            actions.addView(textAction("检查代理") { showProxy() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        if (BrowserInput.isWebUrl(tab?.url.orEmpty())) {
            actions.addView(textAction("复制网址") { copy(tab?.url.orEmpty()) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(textAction("返回首页") { home() }.apply { setTextColor(muted) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        pageHost.addView(panel, FrameLayout.LayoutParams(-1, -1)); progress.visibility = View.INVISIBLE
    }
    private fun hideFullScreen() {
        fullView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        if (::root.isInitialized) root.visibility = View.VISIBLE
        setAddressBarVisible(true)
        setBottomBarVisible(true)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
    private fun externalLink(uri: Uri, source: Tab) {
        if (uri.scheme?.lowercase() in setOf("file", "content", "javascript", "data", "about")) return
        browserDialog(source).setTitle("打开其他应用？").setMessage("此网页想打开其他应用。")
            .setPositiveButton("打开") { _, _ ->
                try {
                    val parsed = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
                    val destination = parsed.data ?: return@setPositiveButton
                    if (destination?.scheme?.lowercase() in setOf("file", "content", "javascript", "data")) return@setPositiveButton
                    val target = Intent(Intent.ACTION_VIEW, destination).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(parsed.`package`)
                    startActivity(target)
                } catch (_: Exception) { toast("未找到可以打开链接的应用") }
            }.setNegativeButton("取消", null).show()
    }
    private fun websitePermission(request: PermissionRequest, source: Tab) {
        permissionRequest?.deny(); permissionRequest = request
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMicrophone = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val supported = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (supported.isEmpty() || request.origin.scheme != "https") { request.deny(); permissionRequest = null; return }
        val requested = when {
            wantsCamera && wantsMicrophone -> "摄像头和麦克风"
            wantsCamera -> "摄像头"
            else -> "麦克风"
        }
        val site = request.origin.host ?: request.origin.toString()
        browserDialog(source).setTitle("允许 $site 使用 $requested？")
            .setMessage("仅本次访问有效")
            .setPositiveButton("允许一次") { _, _ ->
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
            }.setNegativeButton("拒绝") { _, _ -> request.deny(); permissionRequest = null }
            .setOnCancelListener { request.deny(); permissionRequest = null }.show()
    }
    private fun askRuntimePermissions(permissions: Array<String>, done: () -> Unit) {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) done()
        else { permissionDecision = done; requestPermissions(permissions, PERMISSIONS) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) { val done = permissionDecision; permissionDecision = null; done?.invoke() }
    }

    private fun showTabs() {
        var privateGroup = isPrivateMode
        lateinit var modeButton: ImageButton
        val (dialog, panel) = sheet("标签页") { surface ->
            iconAction(R.drawable.ic_ghost_24, "切换无痕浏览") {
                haptic()
                surface.dismiss()
                switchGroup(!isPrivateMode)
                showTabs()
            }.also { modeButton = it }
        }
        val summary = label("", 12f, muted)
        panel.addView(summary, LinearLayout.LayoutParams(-1, -2))
        val list = column()
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt()))
        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))

        fun renderTabs() {
            if (tabs.none { it.session.isPrivate == privateGroup }) privateGroup = isPrivateMode
            modeButton.imageTintList = ColorStateList.valueOf(if (privateGroup) accent else muted)
            modeButton.isSelected = privateGroup
            modeButton.contentDescription = if (privateGroup) "切换普通浏览" else "切换无痕浏览"
            modeButton.tooltipText = modeButton.contentDescription
            summary.text = "${if (privateGroup) "无痕" else "普通"} · ${tabs.count { it.session.isPrivate == privateGroup }} 个标签 · 点按切换"
            list.removeAllViews()
            tabs.forEachIndexed { index, item ->
                if (item.session.isPrivate != privateGroup) return@forEachIndexed
                val isSelected = index == selected
                val line = row().apply {
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    background = if (isSelected) rounded(if (dark) Color.rgb(42, 57, 84) else Color.rgb(235, 241, 255), 16) else selectableBackground()
                    isFocusable = true
                    setOnClickListener {
                        haptic(it)
                        selected = index
                        showTab()
                        dialog.dismiss()
                    }
                }
                val initial = (item.title.take(1).ifBlank { domain(item.url).take(1).uppercase() }).ifBlank { "页" }
                val badge = label(initial, 13f, if (isSelected) accent else muted).apply {
                    gravity = Gravity.CENTER
                    background = rounded(if (dark) Color.rgb(36, 42, 53) else Color.rgb(240, 243, 248), 12)
                }
                line.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })

                val info = column().apply { gravity = Gravity.CENTER_VERTICAL }
                info.addView(label(item.title.ifBlank { "新标签页" }, 15f, if (isSelected) accent else ink).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val status = when {
                    item.keepAlive -> "已固定 · ${domain(item.url)}"
                    item.view == null && item.url.isNotEmpty() -> "已暂停 · 点按恢复"
                    item.url.isEmpty() -> "新标签页"
                    else -> domain(item.url)
                }
                info.addView(label(status, 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                line.addView(info, LinearLayout.LayoutParams(0, dp(58), 1f))

                val pin = iconAction(R.drawable.ic_pin_24, if (item.keepAlive) "取消保持页面" else "保持页面") {
                    haptic()
                    item.keepAlive = !item.keepAlive
                    persist()
                    renderTabs()
                }
                pin.imageTintList = ColorStateList.valueOf(if (item.keepAlive) accent else muted)
                line.addView(pin, LinearLayout.LayoutParams(dp(44), dp(44)))

                line.addView(iconAction(R.drawable.ic_close_24, "关闭标签") {
                    haptic()
                    val closedTab = item
                    lastClosedTab = index to Tab(closedTab.url, closedTab.title, closedTab.desktop, session = closedTab.session, keepAlive = closedTab.keepAlive)
                    closeTab(index)
                    renderTabs()
                    toast(if (closedTab.session.isPrivate) "已关闭无痕标签" else "已关闭「${closedTab.title.take(12)}」")
                }, LinearLayout.LayoutParams(dp(44), dp(44)))

                list.addView(line, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(4) })
            }

            actions.removeAllViews()
            actions.addView(textAction("新标签", true) { dialog.dismiss(); addTab() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            lastClosedTab?.takeIf { it.second.session.isPrivate == privateGroup }?.let { (idx, closed) ->
                actions.addView(textAction("撤销关闭") {
                    if (tabs.size >= 50) { toast("最多打开 50 个标签"); return@textAction }
                    val insert = idx.coerceIn(0, tabs.size)
                    tabs.add(insert, closed)
                    selected = insert
                    lastClosedTab = null
                    showTab()
                    persist()
                    renderTabs()
                    toast("已恢复标签页")
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            }
            actions.addView(textAction("关闭其他") {
                closeGroup(privateGroup, tab)
                renderTabs()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            actions.addView(textAction("全部关闭") {
                closeGroup(privateGroup)
                renderTabs()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        }
        tabsPanelRefresh = { renderTabs() }
        tabsPanelDismiss = { dialog.dismiss() }
        dialog.onDismiss = { tabsPanelRefresh = null; tabsPanelDismiss = null }
        renderTabs()
        dialog.show()
    }
    private fun showMenu() {
        val (dialog, panel) = sheet(getString(R.string.app_name))
        val body = column()
        body.addView(sectionTitle("当前页面"))
        body.addView(menuRow(R.drawable.ic_bookmark_24, "添加书签") {
            dialog.dismiss()
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { target ->
                val name = edit("书签名称").apply { setText(target.title) }
                browserDialog().setTitle("添加书签").setMessage(if (target.session.isPrivate) "书签会保留在普通浏览中" else null).setView(name).setPositiveButton("保存") { _, _ -> store.bookmark(name.text.toString(), target.url); toast("已添加书签") }.setNegativeButton("取消", null).show()
            } ?: toast("当前页面无法添加书签")
        })
        body.addView(menuRow(R.drawable.ic_search_24, "页内查找") { dialog.dismiss(); findInPage() })
        body.addView(menuRow(R.drawable.ic_share_24, "分享链接") {
            dialog.dismiss()
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.url), "分享链接")) }
        })
        body.addView(menuRow(R.drawable.ic_copy_24, "复制链接") { dialog.dismiss(); copy(tab?.url.orEmpty()) })
        body.addView(menuRow(R.drawable.ic_desktop_24, if (tab?.desktop == true) "切换手机版" else "桌面模式") {
            dialog.dismiss(); tab?.let { it.desktop = !it.desktop; release(it); it.state = null; showTab() }
        })
        body.addView(sectionTitle("浏览内容"))
        body.addView(menuRow(R.drawable.ic_ghost_24, if (isPrivateMode) "返回普通浏览" else "无痕浏览") {
            dialog.dismiss(); switchGroup(!isPrivateMode)
        })
        body.addView(menuRow(R.drawable.ic_bookmark_24, "书签") { dialog.dismiss(); showLibrary(true) })
        body.addView(menuRow(R.drawable.ic_history_24, "历史记录") { dialog.dismiss(); showLibrary(false) })
        body.addView(menuRow(R.drawable.ic_download_24, "下载") { dialog.dismiss(); showDownloads() })
        body.addView(sectionTitle("设置"))
        body.addView(menuRow(R.drawable.ic_proxy_24, "网络代理", proxyLabel()) { dialog.dismiss(); showProxy() })
        body.addView(menuRow(R.drawable.ic_settings_24, "设置") { dialog.dismiss(); showSettings() })
        panel.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.54f).toInt()))
        dialog.show()
    }
    private fun showLibrary(bookmarks: Boolean) {
        val (dialog, panel) = sheet(if (bookmarks) "书签" else "历史记录")
        panel.addView(label("点按打开，长按删除", 12f, muted), LinearLayout.LayoutParams(-1, -2))
        val search = edit("搜索名称或网址").apply { background = rounded(base, 12); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val list = column()
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.52f).toInt()))
        var entries = store.entries(bookmarks)
        fun refresh() {
            entries = store.entries(bookmarks, search.text.toString())
            list.removeAllViews()
            if (entries.isEmpty()) {
                list.addView(label(if (bookmarks) "还没有书签" else "还没有浏览记录", 16f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(48), dp(8), dp(48)) }, LinearLayout.LayoutParams(-1, -2))
            } else entries.forEach { entry ->
                val item = menuRow(if (bookmarks) R.drawable.ic_bookmark_24 else R.drawable.ic_history_24, entry.title, domain(entry.url)) {
                    dialog.dismiss(); navigate(entry.url)
                }
                markDeleteTarget(item, false)
                item.setOnLongClickListener {
                    haptic(it)
                    markDeleteTarget(item, true)
                    confirmDelete(entry.title, { store.remove(bookmarks, entry.url); refresh() }, { markDeleteTarget(item, false) })
                    true
                }
                list.addView(item, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(3) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        if (bookmarks) actions.addView(textAction("导入 / 导出") { dialog.dismiss(); bookmarkFiles() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        else actions.addView(textAction("清空历史") { confirm("清空浏览历史？") { store.clearHistory(); refresh() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(textAction("完成", true) { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))
        refresh(); dialog.show()
    }
    private fun bookmarkFiles() {
        browserDialog().setTitle("书签文件").setItems(arrayOf("导入 HTML 书签", "导出 HTML 书签")) { _, which ->
            val intent = if (which == 0) Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                else Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/html").putExtra(Intent.EXTRA_TITLE, "${getString(R.string.app_name)}书签.html")
            startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), if (which == 0) IMPORT else EXPORT)
        }.show()
    }
    private fun proxyLabel() = when (proxyState) { "running" -> "已连接"; "starting" -> "正在连接"; "reconnecting" -> "正在重连"; "failed" -> "连接失败"; else -> "未连接" }
    private fun renderProxyMessage() {
        val view = proxyMessageView ?: return
        view.text = proxyMessage
        view.visibility = if (proxyMessage.isBlank()) View.GONE else View.VISIBLE
        view.setTextColor(proxyStateColor())
    }
    private fun showProxy() {
        val (dialog, panel) = sheet("网络代理")
        val stateColor = proxyStateColor()
        panel.addView(label(proxyLabel(), 17f, stateColor))
        val currentGuid = MmkvManager.getSelectServer()
        val currentRemark = currentGuid?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.ifBlank { null }
        val nodeName = currentRemark ?: if (EmbeddedProxy.hasProfile()) "默认节点" else "未选择节点"
        panel.addView(label("当前节点：$nodeName", 14f, ink).apply { setPadding(0, dp(2), 0, dp(4)) })
        if (!EmbeddedProxy.hasProfile()) panel.addView(label("请先选择节点", 12f, muted).apply { setPadding(0, 0, 0, dp(8)) })

        val messageView = label("", 13f, stateColor).apply { setPadding(0, 0, 0, dp(8)) }
        proxyMessageView = messageView
        renderProxyMessage()
        panel.addView(messageView, LinearLayout.LayoutParams(-1, -2))

        val latencyView = label("", 12f, muted).apply { setPadding(0, 0, 0, dp(8)); visibility = View.GONE }
        panel.addView(latencyView, LinearLayout.LayoutParams(-1, -2))

        val enabled = EmbeddedProxy.isEnabled()
        val topActions = row().apply { setPadding(0, 0, 0, dp(8)) }
        topActions.addView(textAction(if (enabled) "关闭代理" else "开启代理", !enabled) {
            dialog.dismiss()
            if (!enabled && !EmbeddedProxy.hasProfile()) {
                toast("请先添加并选择节点")
                startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
            } else network.setEnabled(!enabled, proxyObserver)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        topActions.addView(textAction("切换节点") {
            val allGuids = MmkvManager.decodeAllServerList()
            if (allGuids.isEmpty()) {
                toast("暂无节点，请先添加")
            } else {
                val names = allGuids.map { guid ->
                    val remarks = MmkvManager.decodeServerConfig(guid)?.remarks?.ifBlank { null } ?: guid
                    if (guid == currentGuid) "$remarks（当前）" else remarks
                }.toTypedArray()
                browserDialog().setTitle("选择代理节点").setItems(names) { _, which ->
                    val picked = allGuids[which]
                    MmkvManager.setSelectServer(picked)
                    haptic()
                    toast("已选择节点")
                    if (enabled) network.reload(proxyObserver)
                    dialog.dismiss()
                    showProxy()
                }.show()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })

        if (enabled && proxyState == "running") {
            topActions.addView(textAction("测速") {
                latencyView.visibility = View.VISIBLE
                latencyView.setTextColor(muted)
                latencyView.text = "正在测速..."
                io.execute {
                    val start = System.currentTimeMillis()
                    val ok = runCatching {
                        val conn = java.net.URL("http://cp.cloudflare.com/generate_204").openConnection(network.proxy()) as java.net.HttpURLConnection
                        try {
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            conn.responseCode == 204
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrDefault(false)
                    val elapsed = System.currentTimeMillis() - start
                    runOnUiThread {
                        if (!isDestroyed) {
                            latencyView.text = if (ok) "连接正常 · 延迟 ${elapsed}ms" else "测速失败（请检查节点）"
                            latencyView.setTextColor(if (ok) Color.rgb(46, 125, 50) else Color.rgb(193, 89, 29))
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        }
        panel.addView(topActions, LinearLayout.LayoutParams(-1, -2))

        panel.addView(sectionTitle("配置"))
        panel.addView(menuRow(R.drawable.ic_proxy_24, "节点设置") {
            dialog.dismiss(); startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
        })
        if (enabled || proxyState == "failed") panel.addView(menuRow(R.drawable.ic_refresh_24, "重新连接") { dialog.dismiss(); network.setEnabled(true, proxyObserver) })
        dialog.show()
    }
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
    }
    private fun showSettings() {
        val (dialog, panel) = sheet("设置")
        panel.addView(sectionTitle("浏览"))
        panel.addView(menuRow(R.drawable.ic_search_24, "搜索引擎", store.preferences.getString("engine", "必应")) {
            dialog.dismiss()
            val engines = BrowserInput.engines.keys.toTypedArray()
            browserDialog().setTitle("搜索引擎").setSingleChoiceItems(engines, engines.indexOf(store.preferences.getString("engine", "必应"))) { choice, index ->
                store.preferences.edit().putString("engine", engines[index]).apply(); choice.dismiss()
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_search_24, "网页文字大小", "调整文字大小") {
            dialog.dismiss()
            browserDialog().setTitle("网页文字大小").setItems(arrayOf("较小 · 85%", "标准 · 100%", "较大 · 115%", "大 · 130%")) { _, index ->
                val value = intArrayOf(85, 100, 115, 130)[index]
                store.preferences.edit().putInt("textZoom", value).apply(); tabs.forEach { it.view?.settings?.textZoom = value }
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_home_24, "设为默认浏览器", "使用星野打开网页") {
            dialog.dismiss()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER))
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 91)
                else toast("当前已是默认浏览器")
            } else startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        })
        panel.addView(sectionTitle("外观与数据"))
        panel.addView(menuRow(R.drawable.ic_settings_24, "震动反馈", if (store.preferences.getBoolean(HAPTICS_PREF, false)) "已开启 · 点击关闭" else "已关闭 · 点击开启") {
            val enabled = !store.preferences.getBoolean(HAPTICS_PREF, false)
            store.preferences.edit().putBoolean(HAPTICS_PREF, enabled).apply()
            dialog.dismiss()
            toast(if (enabled) "已开启震动反馈" else "已关闭震动反馈")
        })
        panel.addView(menuRow(R.drawable.ic_settings_24, "外观", "跟随系统、浅色或深色") {
            dialog.dismiss()
            val modes = arrayOf("system", "light", "dark")
            browserDialog().setTitle("外观").setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), modes.indexOf(getSharedPreferences("web-appearance", MODE_PRIVATE).getString("theme", "system"))) { choice, index ->
                choice.dismiss()
                fun apply() { getSharedPreferences("web-appearance", MODE_PRIVATE).edit().putString("theme", modes[index]).apply(); persist(); recreate() }
                if (privateSession == null) apply() else confirm("切换外观会关闭所有无痕标签并清除本次无痕数据，继续？") { apply() }
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_history_24, "清理浏览数据", "清理历史和网站数据") {
            dialog.dismiss()
            val privateData = isPrivateMode
            browserDialog().setTitle("清理浏览数据").setItems(arrayOf("清空历史", "清理网页缓存", if (privateData) "清除本次无痕网站数据" else "清除网站数据")) { _, index ->
                confirm(when {
                    index != 2 -> "确认清理？"
                    privateData -> "清除本次无痕会话的网站数据？普通浏览的数据不受影响。"
                    else -> "清除普通浏览的所有网站数据？"
                }) {
                    when (index) {
                        0 -> store.clearHistory()
                        1 -> {
                            val session = tab?.session ?: normalSession
                            val web = tab?.view ?: session.createWebView(this)
                            web.clearCache(true)
                            if (web !== tab?.view) session.release(web)
                        }
                        2 -> {
                            (tab?.session ?: normalSession).clearWebsiteData { toast("已清理") }
                        }
                    }; if (index != 2) toast("已清理")
                }
            }.show()
        })
        panel.addView(sectionTitle("关于"))
        panel.addView(menuRow(R.drawable.ic_more_24, "关于${getString(R.string.app_name)}", "版本与许可") {
            dialog.dismiss()
            browserDialog().setTitle("${getString(R.string.app_name)} 0.2.0").setMessage("${getString(R.string.home_slogan_zh)}\n${getString(R.string.home_slogan_en)}").setPositiveButton("开源许可") { _, _ -> startActivity(Intent(this, com.v2ray.ang.embedded.LicenseActivity::class.java)) }.setNegativeButton("关闭", null).show()
        })
        dialog.show()
    }
    private fun download(source: Tab, url: String, ua: String?, disposition: String?, mime: String?) {
        if (!tabs.contains(source)) return
        if (!BrowserInput.isWebUrl(url)) { toast("暂不支持此类型的下载地址"); return }
        val filename = URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180)
        pendingDownload = Intent(this, BrowserDownloadService::class.java).putExtra("url", url).putExtra("ua", ua ?: WebSettings.getDefaultUserAgent(this))
            .putExtra("cookie", source.session.cookies().getCookie(url)).putExtra("referer", source.url).putExtra("title", filename)
            .putExtra("private", source.session.isPrivate)
        if (source.session.isPrivate) toast("下载文件会保留，不保存浏览器下载记录")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime?.takeIf { it.contains('/') } ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE, filename), DOWNLOAD)
    }
    private fun retryDownload(entry: DownloadEntry) {
        if (!BrowserInput.isWebUrl(entry.source)) { toast("原始地址不可用，请从网页重新下载"); return }
        val intent = Intent(this, BrowserDownloadService::class.java)
            .putExtra("url", entry.source)
            .putExtra("destination", entry.uri)
            .putExtra("ua", WebSettings.getDefaultUserAgent(this))
            .putExtra("title", entry.title)
        requestNotificationPermission()
        ContextCompat.startForegroundService(this, intent)
        toast("已重新开始下载")
    }
    private fun showDownloads() {
        val (dialog, panel) = sheet("下载")
        val count = label("", 12f, muted)
        panel.addView(count)
        val list = column()
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.52f).toInt()))
        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        lateinit var cancel: View
        cancel = textAction("取消进行中") {
            startService(Intent(this, BrowserDownloadService::class.java).setAction("cancel"))
            toast("正在取消下载")
            list.postDelayed({ refreshDownloads(list, count, cancel, actions, dialog) }, 300)
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(textAction("刷新", true) { refreshDownloads(list, count, cancel, actions, dialog) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))
        refreshDownloads(list, count, cancel, actions, dialog)
        dialog.show()
    }
    private fun refreshDownloads(list: LinearLayout, count: TextView, cancel: View, actions: LinearLayout? = null, dialog: PanelSurface? = null) {
        val entries = store.downloads()
        count.text = "${entries.size} 个下载 · 长按删除已结束记录"
        cancel.visibility = if (entries.any { it.state == "下载中" }) View.VISIBLE else View.GONE
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(label("还没有下载记录", 16f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(48), dp(8), dp(48)) }, LinearLayout.LayoutParams(-1, -2))
            actions?.let { act ->
                act.removeAllViews()
                act.addView(textAction("刷新", true) { refreshDownloads(list, count, cancel, actions, dialog) }, LinearLayout.LayoutParams(0, dp(48), 1f))
                act.addView(textAction("完成") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            }
            return
        }
        entries.forEach { entry ->
            val action = when (entry.state) {
                "已完成" -> {
                    {
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), contentResolver.getType(Uri.parse(entry.uri)) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: Exception) { toast("文件已移动或没有可用的打开方式") }
                    }
                }
                "下载中" -> ({ toast("正在下载：${entry.title}") })
                else -> ({ retryDownload(entry); dialog?.dismiss(); Unit })
            }
            val item = menuRow(R.drawable.ic_download_24, entry.title, entry.state, action)
            item.setOnLongClickListener {
                haptic(it)
                if (entry.state == "下载中") {
                    toast("请先取消下载，再删除记录")
                    return@setOnLongClickListener true
                }
                markDeleteTarget(item, true)
                confirmDelete(entry.title, {
                    store.removeDownload(entry.id)
                    refreshDownloads(list, count, cancel, actions, dialog)
                }, { markDeleteTarget(item, false) })
                true
            }
            list.addView(item, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(3) })
        }
        actions?.let { act ->
            act.removeAllViews()
            act.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (entries.any { it.state == "已完成" }) {
                act.addView(textAction("清空已完成") {
                    store.clearCompletedDownloads()
                    refreshDownloads(list, count, cancel, actions, dialog)
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            }
            act.addView(textAction("完成", true) { dialog?.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        }
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPLOAD) {
            val uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)?.filter { it.scheme == "content" }?.toTypedArray()
            fileCallback?.onReceiveValue(uris); fileCallback = null; return
        }
        if (resultCode != Activity.RESULT_OK || data?.data == null) { if (requestCode == DOWNLOAD) pendingDownload = null; return }
        val uri = data.data!!
        runCatching {
            if (requestCode == IMPORT) contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        when (requestCode) {
            DOWNLOAD -> {
                pendingDownload?.putExtra("destination", uri.toString())?.let {
                    requestNotificationPermission(); ContextCompat.startForegroundService(this, it)
                    toast(if (it.getBooleanExtra("private", false)) "已开始无痕下载，可在通知中查看进度" else "已开始下载，可在菜单中查看")
                } ?: run {
                    // The private session ended while the picker was open; remove the empty file it created.
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                    toast("无痕会话已结束，下载已取消")
                }; pendingDownload = null
            }
            EXPORT -> io.execute {
                val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(store.exportBookmarks()) } }
                runOnUiThread { toast(if (result.isSuccess) "书签已导出" else "导出失败，请重试") }
            }
            IMPORT -> io.execute {
                val result = runCatching { contentResolver.openInputStream(uri)!!.use(store::importBookmarks) }
                runOnUiThread { toast(result.fold({ "已导入 $it 条书签" }, { "导入失败，请检查文件" })); if (tab?.url?.isEmpty() == true) showHome() }
            }
        }
    }
    @SuppressLint("InlinedApi") // Systems before Android 13 ignore the sensitive-content extra.
    private fun copy(value: String, sensitive: Boolean = isPrivateMode) {
        if (value.isBlank()) return
        val clip = ClipData.newPlainText("网址", value)
        // Keeps private links out of the clipboard preview and keyboard clipboard history.
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip); toast("已复制链接")
    }
    private fun confirm(message: String, action: () -> Unit) { browserDialog().setMessage(message).setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show() }
    private fun toast(message: String) { if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun externalInput(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf(BrowserInput::isWebUrl)
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.take(8192)
        else -> null
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); externalInput(intent)?.let { tabsPanelDismiss?.invoke(); if (ready) addTab(it, session = normalSession) else initialIntent = it } }
    override fun onStart() {
        super.onStart()
        memoryHandler.post(memoryCheck)
        ContextCompat.startForegroundService(this, Intent(this, BrowserKeepAliveService::class.java))
    }
    override fun onStop() { memoryHandler.removeCallbacks(memoryCheck); super.onStop() }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() { if (::store.isInitialized) { persist(); CookieManager.getInstance().flush() }; if (!BrowserKeepAliveService.running) tabs.forEach { it.view?.onPause() }; super.onPause() }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); permissionRequest?.deny()
        if (::network.isInitialized) network.unobserve(proxyObserver)
        memoryHandler.removeCallbacksAndMessages(null)
        searchHandler.removeCallbacksAndMessages(null)
        pendingSearch = null
        searchGeneration++
        tabs.forEach(::release); tabs.clear(); finishEmptyPrivateSession(); normalSession.end(); io.shutdown(); super.onDestroy()
    }
    companion object {
        private const val UPLOAD = 20
        private const val DOWNLOAD = 21
        private const val IMPORT = 22
        private const val EXPORT = 23
        private const val PERMISSIONS = 24
        private const val HAPTICS_PREF = "haptics"
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
