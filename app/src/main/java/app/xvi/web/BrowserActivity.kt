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
import android.net.Uri
import android.os.Bundle
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
    private data class Tab(var url: String = "", var title: String = "新标签页", var desktop: Boolean = false,
        var view: WebView? = null, var state: Bundle? = null, var touched: Long = 0,
        var keepAlive: Boolean = false, var documentVersion: Long = 0, var restoreScroll: Pair<Int, Int>? = null)
    private val tabs = mutableListOf<Tab>()
    private var selected = 0
    private val tab get() = tabs.getOrNull(selected)
    private lateinit var store: BrowserStore
    private lateinit var network: BrowserProxyNetwork
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: TextView
    private lateinit var tabButtonHost: View
    private lateinit var proxyButton: ImageButton
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
    private var proxyMessagePrefix = ""
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
            proxyButton.imageTintList = ColorStateList.valueOf(if (proxyState == "failed" || proxyState == "reconnecting") Color.rgb(193, 89, 29) else accent)
            proxyButton.contentDescription = "网络代理：${proxyLabel()}"
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
        if (!BrowserDownloadService.running) store.interruptDownloads("已中断，请重新下载")
        network = BrowserProxyNetwork.get(this)
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        base = Color.parseColor(if (dark) "#171A21" else "#F6F8FC")
        surface = Color.parseColor(if (dark) "#242A35" else "#FFFFFF")
        ink = Color.parseColor(if (dark) "#EEF2FA" else "#1C2940")
        muted = Color.parseColor(if (dark) "#A0ADC3" else "#6A7890")
        accent = Color.parseColor(if (dark) "#9BB8FF" else "#285DE5")
        buildChrome()
        tabs.addAll(store.restoreTabs().map { Tab(it.url, it.title, it.desktop, keepAlive = it.keepAlive) })
        if (tabs.isEmpty()) tabs.add(Tab())
        selected = store.preferences.getInt("selected", 0).coerceIn(tabs.indices)
        initialIntent = if (savedInstanceState == null) externalInput(intent) else null
        network.observe(proxyObserver)
        network.prepare { state ->
            if (!isDestroyed) {
                ready = true
                proxyObserver.accept(state)
                showTab()
                initialIntent?.let { addTab(it) }
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
                    tab?.view?.canGoBack() == true -> tab?.view?.goBack()
                    tab?.url?.isNotEmpty() == true -> home()
                    tabs.size > 1 -> closeTab(selected)
                    else -> moveTaskToBack(true)
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
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
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
    private fun iconTextAction(resource: Int, description: String, click: () -> Unit) = label("", 14f).apply {
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(3)
        setCompoundDrawablesWithIntrinsicBounds(tintedIcon(resource), null, null, null)
        contentDescription = description
        tooltipText = description
        isFocusable = true
        background = selectableBackground()
        minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
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
        fun show() = showAction()
        fun dismiss() = dismissAction()
    }
    private fun haptic(view: View? = null, constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        (view ?: window.decorView).performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
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
                p.dimAmount = 0.45f
                runCatching {
                    (getSystemService(WINDOW_SERVICE) as? WindowManager)?.updateViewLayout(container, p)
                }
            }
        }
    }
    private fun sheet(title: String): Pair<PanelSurface, LinearLayout> {
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
        val header = row()
        header.addView(label(title, 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(iconAction(R.drawable.ic_close_24, "关闭") { surface.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
                    address.hint = "在 $name 搜索或输入网址"
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
        val work = Runnable {
            io.execute {
                val isBlank = query.isBlank()
                val items = if (isBlank) {
                    store.entries(false).take(6)
                } else {
                    val history = store.entries(false, query)
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
        if (::searchOverlay.isInitialized) searchOverlay.visibility = View.GONE
        address.clearFocus()
        hideKeyboard()
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
        val capsule = row().apply { background = rounded(surface); minimumHeight = dp(48) }
        proxyButton = iconAction(R.drawable.ic_proxy_24, "网络代理") { showProxy() }
        capsule.addView(proxyButton, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        capsule.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        clearButton = iconAction(R.drawable.ic_close_24, "清除地址") { address.text.clear() }
        clearButton.visibility = View.GONE
        capsule.addView(clearButton, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        capsule.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(capsule, LinearLayout.LayoutParams(-1, -2))
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
                            if (dx > dp(40) && selected > 0) {
                                haptic(this)
                                selected--
                                showTab()
                            } else if (dx < -dp(40) && selected < tabs.lastIndex) {
                                haptic(this)
                                selected++
                                showTab()
                            }
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
        forwardButton = iconAction(R.drawable.ic_arrow_forward_24, "前进") { tab?.view?.goForward() }
        val tabCounter = tabCounterAction("标签页") { showTabs() }
        listOf(backButton, forwardButton, iconAction(R.drawable.ic_home_24, "首页") { home() }, tabCounter, iconAction(R.drawable.ic_more_24, "菜单") { showMenu() }).forEach {
            bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun showHome() {
        hideSearchOverlay()
        hideFindInPage()
        setAddressBarVisible(true)
        setBottomBarVisible(true)
        pageHost.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val home = column().apply { setPadding(dp(28), dp(42), dp(28), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val title = label(getString(R.string.app_name), 38f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER }
        home.addView(title, LinearLayout.LayoutParams(-1, dp(70)))
        val slogan = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        slogan.addView(label(getString(R.string.home_slogan_zh), 14f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(26)))
        slogan.addView(label(getString(R.string.home_slogan_en), 12f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(22)))
        home.addView(slogan, LinearLayout.LayoutParams(-1, dp(48)))
        val search = label("搜索或输入网址", 16f, muted).apply {
            background = rounded(surface, 18); setPadding(dp(18), 0, dp(20), 0)
            setCompoundDrawablesWithIntrinsicBounds(tintedIcon(R.drawable.ic_search_24, muted), null, null, null)
            compoundDrawablePadding = dp(8)
            setOnClickListener { address.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT) }
        }
        home.addView(search, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(30); bottomMargin = dp(28) })
        val bookmarks = store.entries(true).take(4)
        val recent = store.recentSites(5)
        val shortcuts = bookmarks.ifEmpty {
            if (recent.isEmpty()) listOf(
                PageEntry("https://www.bing.com", "必应"), PageEntry("https://www.baidu.com", "百度"),
                PageEntry("https://www.wikipedia.org", "维基百科"), PageEntry("https://github.com", "GitHub")
            ) else emptyList()
        }
        if (shortcuts.isNotEmpty()) home.addView(sectionTitle(if (bookmarks.isNotEmpty()) "书签" else "常用入口"), LinearLayout.LayoutParams(-1, -2))
        shortcuts.chunked(4).forEach { group ->
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
            home.addView(strip, LinearLayout.LayoutParams(-1, -2))
        }
        val links = row().apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) }
        listOf(
            iconTextAction(R.drawable.ic_bookmark_24, "书签") { showLibrary(true) }.apply { text = "书签"; setTextColor(muted) },
            iconTextAction(R.drawable.ic_history_24, "历史") { showLibrary(false) }.apply { text = "历史"; setTextColor(muted) },
            iconTextAction(R.drawable.ic_download_24, "下载") { showDownloads() }.apply { text = "下载"; setTextColor(muted) },
        ).forEach { links.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        home.addView(links)
        if (recent.isNotEmpty()) {
            home.addView(sectionTitle("最近访问"), LinearLayout.LayoutParams(-1, -2))
            recent.take(5).forEach { entry ->
                val item = menuRow(R.drawable.ic_home_24, entry.title, domain(entry.url)) { navigate(entry.url) }
                item.contentDescription = "${entry.title}，${domain(entry.url)}，长按删除"
                item.setOnLongClickListener {
                    haptic(it)
                    confirm("删除这个网站的访问记录？") {
                        store.removeSiteHistory(entry.url)
                        showHome()
                    }
                    true
                }
                home.addView(item, LinearLayout.LayoutParams(-1, dp(62)))
            }
        }
        scroll.addView(home)
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }
    private fun persist() = store.saveTabs(tabs.map { SavedTab(it.url, it.title, it.desktop, it.keepAlive) }, selected)
    private fun updateChrome() {
        val url = tab?.url.orEmpty()
        if (!address.hasFocus()) address.setText(url)
        address.setCompoundDrawablesWithIntrinsicBounds(
            if (url.startsWith("https://", ignoreCase = true)) tintedIcon(R.drawable.ic_lock_24, muted) else null,
            null, null, null
        )
        address.compoundDrawablePadding = dp(5)
        clearButton.visibility = if (address.hasFocus() && address.text.isNotEmpty()) View.VISIBLE else View.GONE
        tabButton.text = if (tabs.size >= 10) "10+" else tabs.size.toString()
        tabButtonHost.contentDescription = "标签页，${tabs.size} 个"
        forwardButton.alpha = if (tab?.view?.canGoForward() == true) 1f else .3f
        backButton.alpha = if (tab?.url?.isNotEmpty() == true || tabs.size > 1) 1f else .3f
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
        tab?.let { release(it); it.url = ""; it.title = "新标签页"; it.state = null; it.restoreScroll = null }
        address.clearFocus(); hideKeyboard(); showHome(); updateChrome(); persist()
    }
    private fun navigate(input: String) {
        val url = BrowserInput.resolve(input, store.preferences.getString("engine", "必应") ?: "必应")
        if (url.isEmpty()) return
        hideFindInPage()
        hideSearchOverlay()
        address.clearFocus(); hideKeyboard()
        val target = tab ?: return
        load(target, url)
    }
    private fun load(target: Tab, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        target.url = url
        target.state = null
        target.restoreScroll = null
        if (!ready) { initialIntent = null; return }
        network.prepare {
            if (!isDestroyed && tabs.contains(target)) {
                val view = target.view ?: createWebView(target).also { target.view = it }
                view.loadUrl(url)
                if (tab === target) showTab(false)
                persist()
            }
        }
    }
    private fun addTab(url: String = "", activate: Boolean = true): Tab? {
        if (tabs.size >= 50) { toast("最多打开 50 个标签，请先关闭一些标签"); return null }
        val newTab = Tab()
        tabs.add(newTab)
        if (activate) {
            selected = tabs.lastIndex
            showTab()
        }
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
        release(tabs[index]); tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(Tab())
        if (index < selected) selected--
        selected = selected.coerceIn(tabs.indices)
        showTab(); persist()
    }
    private fun release(target: Tab) {
        if (findWebView === target.view) hideFindInPage()
        target.view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        target.view = null
    }
    private fun showTab(load: Boolean = true) {
        hideFullScreen()
        hideSearchOverlay()
        hideFindInPage()
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

    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimTabs(TabMemoryPolicy.Pressure.CRITICAL) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(target: Tab): WebView = WebView(this).apply {
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (BrowserInput.isWebUrl(request.url.toString())) return false
                if (request.isForMainFrame && request.hasGesture()) externalLink(request.url)
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
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
                if (BrowserInput.isWebUrl(url)) {
                    target.url = url; target.title = view.title?.takeIf { it.isNotBlank() } ?: url
                    store.visit(target.title, url); persist(); CookieManager.getInstance().flush()
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
                if (BrowserInput.isWebUrl(url)) {
                    target.url = url
                    if (tab === target) updateChrome()
                    persist()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && tab === target) { setBottomBarVisible(true); showPageError("页面暂时无法打开", "检查网络或代理配置，然后重试。") }
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (tab === target) { setBottomBarVisible(true); showPageError("网站证书无效", "连接已停止，请检查网址、系统时间或联系网站管理员。") }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (findWebView === view) hideFindInPage()
                target.view = null
                (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy()
                if (tab === target) { setBottomBarVisible(true); showPageError("页面已释放", "点按重试重新加载页面。") }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { if (tab === target) updateChrome() }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) { target.title = title; persist() } }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture || tabs.size >= 50) return false
                val child = addTab(activate = false) ?: return false
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
                catch (_: ActivityNotFoundException) { fileCallback?.onReceiveValue(null); fileCallback = null; toast("没有可用的文件选择器"); true }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = view; fullCallback = callback; root.visibility = View.GONE
                (window.decorView as android.view.ViewGroup).addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            override fun onHideCustomView() = hideFullScreen()
            override fun onPermissionRequest(request: PermissionRequest) { websitePermission(request) }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (permissionRequest === request) { permissionRequest = null; permissionDecision = null }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val site = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
                AlertDialog.Builder(this@BrowserActivity).setTitle("允许 $site 访问位置？")
                    .setPositiveButton("允许一次") { _, _ ->
                        askRuntimePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                            val allowed = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            callback.invoke(origin, allowed, false)
                        }
                    }.setNegativeButton("拒绝") { _, _ -> callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }.show()
            }
        }
        setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        setOnLongClickListener {
            val result = hitTestResult
            val url = result.extra
            if (url != null && BrowserInput.isWebUrl(url)) {
                haptic(this)
                val image = result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                AlertDialog.Builder(this@BrowserActivity).setTitle(if (image) "图片" else "链接")
                    .setItems(arrayOf("在新标签中打开", "复制链接", if (image) "保存图片" else "下载链接")) { _, which ->
                        when (which) { 0 -> addTab(url, activate = false); 1 -> copy(url); 2 -> download(url, settings.userAgentString, null, null) }
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
    private fun externalLink(uri: Uri) {
        if (uri.scheme?.lowercase() in setOf("file", "content", "javascript", "data", "about")) return
        AlertDialog.Builder(this).setTitle("打开其他应用？").setMessage("此网页希望通过 ${uri.scheme} 打开外部应用。")
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
    private fun websitePermission(request: PermissionRequest) {
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
        AlertDialog.Builder(this).setTitle("允许 $site 使用 $requested？")
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
        val (dialog, panel) = sheet("标签页")
        val summary = label("", 12f, muted)
        panel.addView(summary, LinearLayout.LayoutParams(-1, -2))
        val list = column()
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt()))
        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))

        fun renderTabs() {
            summary.text = "${tabs.size} 个标签 · 点按切换，图钉保持不释放"
            list.removeAllViews()
            tabs.forEachIndexed { index, item ->
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
                    item.keepAlive -> "保持页面 · ${domain(item.url)}"
                    item.view == null && item.url.isNotEmpty() -> "已休眠 · 点按恢复"
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
                    lastClosedTab = index to Tab(closedTab.url, closedTab.title, closedTab.desktop, keepAlive = closedTab.keepAlive)
                    closeTab(index)
                    renderTabs()
                    toast("已关闭「${closedTab.title.take(12)}」")
                }, LinearLayout.LayoutParams(dp(44), dp(44)))

                list.addView(line, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(4) })
            }

            actions.removeAllViews()
            actions.addView(textAction("新标签", true) { dialog.dismiss(); addTab() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            lastClosedTab?.let { (idx, closed) ->
                actions.addView(textAction("撤销关闭") {
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
                val selectedTab = tab
                tabs.filter { it !== selectedTab }.forEach(::release)
                tabs.clear(); tabs.add(selectedTab ?: Tab()); selected = 0; showTab(); persist()
                renderTabs()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            actions.addView(textAction("全部关闭") {
                tabs.forEach(::release); tabs.clear(); tabs.add(Tab()); selected = 0; showTab(); persist()
                renderTabs()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        }
        renderTabs()
        dialog.show()
    }
    private fun showMenu() {
        val (dialog, panel) = sheet(getString(R.string.app_name))
        val body = column()
        body.addView(sectionTitle("当前页面"))
        body.addView(menuRow(R.drawable.ic_bookmark_24, "添加书签", "保存当前网页到本机") {
            dialog.dismiss()
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { target ->
                val name = edit("书签名称").apply { setText(target.title) }
                AlertDialog.Builder(this).setTitle("添加书签").setView(name).setPositiveButton("保存") { _, _ -> store.bookmark(name.text.toString(), target.url); toast("已添加书签") }.setNegativeButton("取消", null).show()
            } ?: toast("当前页面无法添加书签")
        })
        body.addView(menuRow(R.drawable.ic_search_24, "页内查找", "在当前网页中查找文字") { dialog.dismiss(); findInPage() })
        body.addView(menuRow(R.drawable.ic_share_24, "分享链接", "发送当前网页地址") {
            dialog.dismiss()
            tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.url), "分享链接")) }
        })
        body.addView(menuRow(R.drawable.ic_copy_24, "复制链接", "复制当前网页地址") { dialog.dismiss(); copy(tab?.url.orEmpty()) })
        body.addView(menuRow(R.drawable.ic_desktop_24, if (tab?.desktop == true) "切换手机版" else "桌面模式", "为当前标签重新加载页面") {
            dialog.dismiss(); tab?.let { it.desktop = !it.desktop; release(it); it.state = null; showTab() }
        })
        body.addView(sectionTitle("浏览内容"))
        body.addView(menuRow(R.drawable.ic_bookmark_24, "书签", "查看、搜索或导入导出") { dialog.dismiss(); showLibrary(true) })
        body.addView(menuRow(R.drawable.ic_history_24, "历史记录", "查看最近访问的网页") { dialog.dismiss(); showLibrary(false) })
        body.addView(menuRow(R.drawable.ic_download_24, "下载", "查看进度、打开或重试") { dialog.dismiss(); showDownloads() })
        body.addView(sectionTitle("网络与应用"))
        body.addView(menuRow(R.drawable.ic_proxy_24, "网络代理", proxyLabel()) { dialog.dismiss(); showProxy() })
        body.addView(menuRow(R.drawable.ic_settings_24, "设置", "搜索引擎、外观和浏览数据") { dialog.dismiss(); showSettings() })
        panel.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.54f).toInt()))
        dialog.show()
    }
    private fun showLibrary(bookmarks: Boolean) {
        val (dialog, panel) = sheet(if (bookmarks) "书签" else "历史记录")
        panel.addView(label(if (bookmarks) "点按打开，长按删除；数据只保存在本机" else "点按打开，长按删除", 12f, muted), LinearLayout.LayoutParams(-1, -2))
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
                item.setOnLongClickListener {
                    haptic(it)
                    confirm("删除这条记录？") { store.remove(bookmarks, entry.url); refresh() }
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
        AlertDialog.Builder(this).setTitle("书签文件").setItems(arrayOf("导入 HTML 书签", "导出 HTML 书签")) { _, which ->
            val intent = if (which == 0) Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                else Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/html").putExtra(Intent.EXTRA_TITLE, "${getString(R.string.app_name)}书签.html")
            startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), if (which == 0) IMPORT else EXPORT)
        }.show()
    }
    private fun proxyLabel() = when (proxyState) { "running" -> "按规则分流"; "starting" -> "正在启动"; "reconnecting" -> "正在自动重连"; "failed" -> "故障，当前直连"; else -> "未开启" }
    private fun renderProxyMessage() {
        val view = proxyMessageView ?: return
        view.text = if (proxyMessage.isBlank()) "" else proxyMessagePrefix + proxyMessage
        view.visibility = if (proxyMessage.isBlank()) View.GONE else View.VISIBLE
        view.setTextColor(if (proxyState == "failed" || proxyState == "reconnecting") Color.rgb(193, 89, 29) else accent)
    }
    private fun showProxy() {
        val (dialog, panel) = sheet("网络代理")
        val stateColor = if (proxyState == "failed" || proxyState == "reconnecting") Color.rgb(193, 89, 29) else accent
        panel.addView(label(proxyLabel(), 17f, stateColor))
        val currentGuid = MmkvManager.getSelectServer()
        val currentRemark = currentGuid?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.ifBlank { null }
        val nodeName = currentRemark ?: if (EmbeddedProxy.hasProfile()) "默认节点" else "未选择节点"
        panel.addView(label("当前节点：$nodeName", 14f, ink).apply { setPadding(0, dp(2), 0, dp(4)) })
        panel.addView(label(if (EmbeddedProxy.hasProfile()) "网页和下载共用此代理入口，按规则分流" else "还没有选择节点，请先切换或导入节点", 12f, muted).apply { setPadding(0, 0, 0, dp(8)) })

        proxyMessagePrefix = ""
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
                toast("请先导入并选择节点")
                startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
            } else network.setEnabled(!enabled, proxyObserver)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        topActions.addView(textAction("切换节点") {
            val allGuids = MmkvManager.decodeAllServerList()
            if (allGuids.isEmpty()) {
                toast("暂无导入的节点，请在高级配置中添加")
            } else {
                val names = allGuids.map { guid ->
                    val remarks = MmkvManager.decodeServerConfig(guid)?.remarks?.ifBlank { null } ?: guid
                    if (guid == currentGuid) "$remarks（当前）" else remarks
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("选择代理节点").setItems(names) { _, which ->
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

        panel.addView(sectionTitle("配置与说明"))
        panel.addView(menuRow(R.drawable.ic_proxy_24, "节点、订阅与高级配置", "管理订阅、分流、DNS与完整配置") {
            dialog.dismiss(); startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
        })
        if (enabled || proxyState == "failed") panel.addView(menuRow(R.drawable.ic_refresh_24, "重试连接", "重新检查节点和浏览器代理入口") { dialog.dismiss(); network.setEnabled(true, proxyObserver) })
        panel.addView(menuRow(R.drawable.ic_settings_24, "连接说明", "了解应用内代理的范围和故障处理") { dialog.dismiss(); showProxyInfo() })
        dialog.show()
    }
    private fun showProxyInfo() {
        val (dialog, panel) = sheet("应用内代理")
        panel.addView(label("网页和文件下载使用同一个本机代理入口，按节点规则分流。", 15f))
        panel.addView(label("不会修改系统 VPN，也不会接管其他应用的网络。代理核心被回收或发生运行错误时，会按退避间隔自动重连。", 14f, muted).apply { setPadding(0, dp(12), 0, dp(12)) })
        panel.addView(label("规则阻止和普通网页错误不会触发重连，也不会绕过规则；多次核心重连失败后才恢复直连。", 14f, muted))
        proxyMessagePrefix = "当前信息："
        val currentMessageView = label("", 14f, Color.rgb(193, 89, 29)).apply { setPadding(0, dp(12), 0, dp(12)) }
        proxyMessageView = currentMessageView
        renderProxyMessage()
        panel.addView(currentMessageView, LinearLayout.LayoutParams(-1, -2))
        panel.addView(textAction("知道了", true) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
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
            AlertDialog.Builder(this).setTitle("搜索引擎").setSingleChoiceItems(engines, engines.indexOf(store.preferences.getString("engine", "必应"))) { choice, index ->
                store.preferences.edit().putString("engine", engines[index]).apply(); choice.dismiss()
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_search_24, "网页文字大小", "调整网页正文缩放比例") {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle("网页文字大小").setItems(arrayOf("较小 · 85%", "标准 · 100%", "较大 · 115%", "大 · 130%")) { _, index ->
                val value = intArrayOf(85, 100, 115, 130)[index]
                store.preferences.edit().putInt("textZoom", value).apply(); tabs.forEach { it.view?.settings?.textZoom = value }
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_home_24, "设为默认浏览器", "接管系统中的网页链接") {
            dialog.dismiss()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER))
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 91)
                else toast("当前已是默认浏览器")
            } else startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        })
        panel.addView(sectionTitle("外观与数据"))
        panel.addView(menuRow(R.drawable.ic_settings_24, "外观", "跟随系统、浅色或深色") {
            dialog.dismiss()
            val modes = arrayOf("system", "light", "dark")
            AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), modes.indexOf(getSharedPreferences("web-appearance", MODE_PRIVATE).getString("theme", "system"))) { choice, index ->
                persist(); getSharedPreferences("web-appearance", MODE_PRIVATE).edit().putString("theme", modes[index]).apply(); choice.dismiss(); recreate()
            }.show()
        })
        panel.addView(menuRow(R.drawable.ic_history_24, "清理浏览数据", "历史、缓存或网站登录状态") {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle("清理浏览数据").setItems(arrayOf("清空历史", "清理网页缓存", "清除网站登录与存储")) { _, index ->
                confirm(if (index == 2) "清除所有网站登录状态和本地存储？" else "确认清理？") {
                    when (index) {
                        0 -> store.clearHistory()
                        1 -> (tab?.view ?: WebView(this)).let { it.clearCache(true); if (it !== tab?.view) it.destroy() }
                        2 -> { CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }; WebStorage.getInstance().deleteAllData() }
                    }; toast("已清理")
                }
            }.show()
        })
        panel.addView(sectionTitle("关于"))
        panel.addView(menuRow(R.drawable.ic_more_24, "关于${getString(R.string.app_name)}", "版本、开源许可与内置组件") {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle("${getString(R.string.app_name)} 0.1.0").setMessage("轻量浏览，自由抵达。\n\n所有书签、历史和代理配置保存在本机。\n\n内嵌 v2rayNG ${com.v2ray.ang.BuildConfig.SOURCE_VERSION} 与 Xray 核心。").setPositiveButton("开源许可") { _, _ -> startActivity(Intent(this, com.v2ray.ang.embedded.LicenseActivity::class.java)) }.setNegativeButton("关闭", null).show()
        })
        dialog.show()
    }
    private fun download(url: String, ua: String?, disposition: String?, mime: String?) {
        if (!BrowserInput.isWebUrl(url)) { toast("暂不支持此类型的下载地址"); return }
        val filename = URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180)
        pendingDownload = Intent(this, BrowserDownloadService::class.java).putExtra("url", url).putExtra("ua", ua ?: WebSettings.getDefaultUserAgent(this))
            .putExtra("cookie", CookieManager.getInstance().getCookie(url)).putExtra("referer", tab?.url).putExtra("title", filename)
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
        count.text = "${entries.size} 个下载 · 点按打开或重试，长按删除已结束记录"
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
                confirm("删除下载记录「${entry.title.take(15)}」？") {
                    store.removeDownload(entry.id)
                    refreshDownloads(list, count, cancel, actions, dialog)
                }
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
                    requestNotificationPermission(); ContextCompat.startForegroundService(this, it); toast("已开始下载，可在菜单中查看")
                }; pendingDownload = null
            }
            EXPORT -> io.execute {
                val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(store.exportBookmarks()) } }
                runOnUiThread { toast(if (result.isSuccess) "书签已导出" else "导出失败，请重试") }
            }
            IMPORT -> io.execute {
                val result = runCatching { contentResolver.openInputStream(uri)!!.use(store::importBookmarks) }
                runOnUiThread { toast(result.fold({ "已导入 $it 条书签" }, { "导入失败：${it.message}" })); if (tab?.url?.isEmpty() == true) showHome() }
            }
        }
    }
    private fun copy(value: String) {
        if (value.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网址", value)); toast("已复制链接")
    }
    private fun confirm(message: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show() }
    private fun toast(message: String) { if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun externalInput(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf(BrowserInput::isWebUrl)
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.take(8192)
        else -> null
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); externalInput(intent)?.let { if (ready) addTab(it) else initialIntent = it } }
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
        tabs.forEach(::release); io.shutdown(); super.onDestroy()
    }
    companion object {
        private const val UPLOAD = 20
        private const val DOWNLOAD = 21
        private const val IMPORT = 22
        private const val EXPORT = 23
        private const val PERMISSIONS = 24
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
