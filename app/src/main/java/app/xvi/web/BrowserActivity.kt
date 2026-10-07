package app.xvi.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.embedded.EmbeddedProxy
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
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: TextView
    private lateinit var proxyButton: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var refreshButton: TextView
    private var base = 0
    private var surface = 0
    private var ink = 0
    private var muted = 0
    private var accent = 0
    private var dark = false
    private var proxyState = "off"
    private var proxyMessage = ""
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
            proxyButton.text = when (proxyState) { "running" -> "●"; "starting" -> "◌"; "failed" -> "!"; else -> "○" }
            proxyButton.setTextColor(if (proxyState == "failed") Color.rgb(193, 89, 29) else accent)
            proxyButton.contentDescription = "网络代理：${proxyLabel()}"
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
                    address.hasFocus() -> { address.clearFocus(); hideKeyboard(); updateChrome() }
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
    private fun action(text: String, description: String, click: () -> Unit) = label(text, 22f).apply {
        gravity = Gravity.CENTER; contentDescription = description; isFocusable = true
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attrs.getDrawable(0); attrs.recycle()
        setOnClickListener { click() }
        setOnLongClickListener { toast(description); true }
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
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent); visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        val addressRow = row().apply { setPadding(dp(10), dp(6), dp(10), 0) }
        val capsule = row().apply { background = rounded(surface); minimumHeight = dp(48) }
        proxyButton = action("○", "网络代理") { showProxy() }
        capsule.addView(proxyButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        address = edit("搜索或输入网址").apply {
            background = null; setPadding(0, 0, 0, 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    navigate(text.toString()); true
                } else false
            }
        }
        capsule.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        refreshButton = action("↻", "刷新或停止加载") {
            tab?.view?.let { if (it.progress < 100) it.stopLoading() else network.prepare { _ -> if (!isDestroyed) it.reload() } }
        }
        capsule.addView(refreshButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        addressRow.addView(capsule, LinearLayout.LayoutParams(-1, -2))
        root.addView(addressRow)
        val toolbar = row().apply { setPadding(dp(6), 0, dp(6), 0) }
        backButton = action("‹", "后退") { onBackPressedDispatcher.onBackPressed() }
        forwardButton = action("›", "前进") { tab?.view?.goForward() }
        tabButton = action("1", "标签页") { showTabs() }
        listOf(backButton, forwardButton, action("⌂", "首页") { home() }, tabButton, action("☰", "菜单") { showMenu() }).forEach {
            toolbar.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        root.addView(toolbar)
        setContentView(root)
    }

    private fun showHome() {
        content.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val home = column().apply { setPadding(dp(28), dp(42), dp(28), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val title = label("行间", 38f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER }
        home.addView(title, LinearLayout.LayoutParams(-1, dp(70)))
        home.addView(label("从这里，去往任何地方。", 14f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(36)))
        val search = label("搜索或输入网址", 16f, muted).apply {
            background = rounded(surface, 18); setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { address.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT) }
        }
        home.addView(search, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(30); bottomMargin = dp(28) })
        val shortcuts = store.entries(true).take(8).ifEmpty {
            listOf(PageEntry("https://www.bing.com", "必应"), PageEntry("https://www.baidu.com", "百度"), PageEntry("https://www.wikipedia.org", "维基百科"), PageEntry("https://github.com", "GitHub"))
        }
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
        listOf("书签" to { showLibrary(true) }, "历史" to { showLibrary(false) }, "下载" to { showDownloads() }).forEach { (name, click) ->
            links.addView(label(name, 14f, muted).apply { gravity = Gravity.CENTER; setOnClickListener { click() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        home.addView(links)
        scroll.addView(home)
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }
    private fun persist() = store.saveTabs(tabs.map { SavedTab(it.url, it.title, it.desktop, it.keepAlive) }, selected)
    private fun updateChrome() {
        if (!address.hasFocus()) address.setText(tab?.url.orEmpty())
        tabButton.text = tabs.size.toString()
        forwardButton.alpha = if (tab?.view?.canGoForward() == true) 1f else .3f
        backButton.alpha = if (tab?.url?.isNotEmpty() == true || tabs.size > 1) 1f else .3f
        val loading = tab?.view?.progress ?: 100
        progress.progress = loading
        progress.visibility = if (tab?.url?.isNotEmpty() == true && loading < 100) View.VISIBLE else View.INVISIBLE
        refreshButton.text = if (loading < 100 && tab?.url?.isNotEmpty() == true) "×" else "↻"
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0) }
    private fun home() {
        tab?.let { release(it); it.url = ""; it.title = "新标签页"; it.state = null; it.restoreScroll = null }
        address.clearFocus(); hideKeyboard(); showHome(); updateChrome(); persist()
    }
    private fun navigate(input: String) {
        val url = BrowserInput.resolve(input, store.preferences.getString("engine", "必应") ?: "必应")
        if (url.isEmpty()) return
        address.clearFocus(); hideKeyboard()
        val target = tab ?: return
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
    private fun addTab(url: String = "") {
        if (tabs.size >= 50) { toast("最多打开 50 个标签，请先关闭一些标签"); return }
        tabs.add(Tab()); selected = tabs.lastIndex
        showTab()
        if (url.isNotEmpty()) navigate(url)
        persist()
    }
    private fun closeTab(index: Int) {
        release(tabs[index]); tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add(Tab())
        if (index < selected) selected--
        selected = selected.coerceIn(tabs.indices)
        showTab(); persist()
    }
    private fun release(target: Tab) {
        target.view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        target.view = null
    }
    private fun showTab(load: Boolean = true) {
        hideFullScreen()
        val target = tab ?: return
        // Detach background views without blanket onPause(), which can interrupt media.
        target.touched = android.os.SystemClock.elapsedRealtime()
        content.removeAllViews()
        if (target.url.isEmpty()) showHome()
        else {
            val fresh = target.view == null
            val web = target.view ?: createWebView(target).also { target.view = it }
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            content.addView(web, FrameLayout.LayoutParams(-1, -1)); web.onResume()
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
                        if (view.parent == null) { content.removeAllViews(); content.addView(view, FrameLayout.LayoutParams(-1, -1)) }
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
                if (request.isForMainFrame && tab === target) showPageError("页面暂时无法打开", "检查网络或代理配置，然后重试。")
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (tab === target) showPageError("网站证书无效", "连接已停止，请检查网址、系统时间或联系网站管理员。")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                target.view = null
                (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy()
                if (tab === target) showPageError("页面已释放", "点按重试重新加载页面。")
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { if (tab === target) updateChrome() }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) { target.title = title; persist() } }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture || tabs.size >= 50) return false
                addTab()
                val child = tab ?: return false
                val childView = createWebView(child).also { child.view = it }
                child.url = "about:blank"
                (resultMsg.obj as WebView.WebViewTransport).webView = childView
                resultMsg.sendToTarget(); showTab(false)
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
                AlertDialog.Builder(this@BrowserActivity).setTitle("允许网站访问位置？").setMessage(origin)
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
                val image = result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                AlertDialog.Builder(this@BrowserActivity).setTitle(if (image) "图片" else "链接")
                    .setItems(arrayOf("在新标签中打开", "复制链接", if (image) "保存图片" else "下载链接")) { _, which ->
                        when (which) { 0 -> addTab(url); 1 -> copy(url); 2 -> download(url, settings.userAgentString, null, null) }
                    }.show()
                true
            } else false
        }
    }
    private fun showPageError(title: String, message: String) {
        content.removeAllViews()
        val panel = column().apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(24), dp(30), dp(24)) }
        panel.addView(label(title, 22f))
        panel.addView(label(message, 15f, muted).apply { setPadding(0, dp(18), 0, dp(18)) })
        panel.addView(Button(this).apply { text = "重试"; setOnClickListener { tab?.let { release(it) }; showTab() } })
        content.addView(panel, FrameLayout.LayoutParams(-1, -1)); progress.visibility = View.INVISIBLE
    }
    private fun hideFullScreen() {
        fullView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        if (::root.isInitialized) root.visibility = View.VISIBLE
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
        val supported = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (supported.isEmpty() || request.origin.scheme != "https") { request.deny(); permissionRequest = null; return }
        AlertDialog.Builder(this).setTitle("允许网站使用${if (supported.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) "摄像头" else "麦克风"}？")
            .setMessage(request.origin.toString()).setPositiveButton("允许一次") { _, _ ->
                val permissions = supported.map { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO }.toTypedArray()
                askRuntimePermissions(permissions) {
                    if (permissionRequest === request) {
                        val allowed = supported.filter { checkSelfPermission(if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
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
        val panel = column()
        val list = column()
        val dialog = AlertDialog.Builder(this).setTitle("标签页 · 长按保留").setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("新标签") { _, _ -> addTab() }.setNegativeButton("完成", null).create()
        tabs.forEachIndexed { index, item ->
            val line = row().apply { setPadding(dp(16), dp(4), dp(8), dp(4)) }
            val status = if (item.keepAlive) " · 保留" else if (item.view == null && item.url.isNotEmpty()) " · 已休眠" else ""
            line.addView(label((if (index == selected) "●  " else "") + item.title + status, 15f, if (index == selected) accent else ink).apply {
                maxLines = 2; setOnClickListener { selected = index; showTab(); dialog.dismiss() }
                setOnLongClickListener {
                    item.keepAlive = !item.keepAlive; persist(); dialog.dismiss(); showTabs(); true
                }
            }, LinearLayout.LayoutParams(0, dp(60), 1f))
            line.addView(action("×", "关闭标签") { closeTab(index); dialog.dismiss(); showTabs() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            list.addView(line)
        }
        panel.addView(list); dialog.show()
    }
    private fun showMenu() {
        val items = arrayOf("添加书签", "书签", "历史记录", "下载", "页内查找", "分享链接", "复制链接", if (tab?.desktop == true) "切换手机版" else "桌面模式", "网络代理", "设置")
        AlertDialog.Builder(this).setTitle("行间").setItems(items) { _, which ->
            when (which) {
                0 -> tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { target ->
                    val name = edit("书签名称").apply { setText(target.title) }
                    AlertDialog.Builder(this).setTitle("添加书签").setView(name).setPositiveButton("保存") { _, _ -> store.bookmark(name.text.toString(), target.url); toast("已添加书签") }.setNegativeButton("取消", null).show()
                }
                1 -> showLibrary(true)
                2 -> showLibrary(false)
                3 -> showDownloads()
                4 -> findInPage()
                5 -> tab?.takeIf { BrowserInput.isWebUrl(it.url) }?.let { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.url), "分享链接")) }
                6 -> copy(tab?.url.orEmpty())
                7 -> tab?.let { it.desktop = !it.desktop; release(it); it.state = null; showTab() }
                8 -> showProxy()
                9 -> showSettings()
            }
        }.show()
    }
    private fun showLibrary(bookmarks: Boolean) {
        val panel = column()
        val search = edit("搜索名称或网址")
        panel.addView(search)
        val list = ListView(this)
        panel.addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        var entries = store.entries(bookmarks)
        fun refresh() {
            entries = store.entries(bookmarks, search.text.toString())
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map { "${it.title}\n${it.url}" })
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (bookmarks) "书签 · 长按删除" else "历史 · 长按删除").setView(panel)
            .setNegativeButton("关闭", null)
        if (bookmarks) dialog.setPositiveButton("导入 / 导出") { _, _ -> bookmarkFiles() }
        else dialog.setPositiveButton("清空") { _, _ -> confirm("清空浏览历史？") { store.clearHistory(); showLibrary(false) } }
        val window = dialog.create()
        list.setOnItemClickListener { _, _, index, _ -> window.dismiss(); navigate(entries[index].url) }
        list.setOnItemLongClickListener { _, _, index, _ -> confirm("删除这条记录？") { store.remove(bookmarks, entries[index].url); refresh() }; true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh(); window.show()
    }
    private fun bookmarkFiles() {
        AlertDialog.Builder(this).setTitle("书签文件").setItems(arrayOf("导入 HTML 书签", "导出 HTML 书签")) { _, which ->
            val intent = if (which == 0) Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                else Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/html").putExtra(Intent.EXTRA_TITLE, "行间书签.html")
            startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), if (which == 0) IMPORT else EXPORT)
        }.show()
    }
    private fun findInPage() {
        val web = tab?.view ?: return
        val panel = column()
        val query = edit("查找文字")
        val count = label("", 14f, muted).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        panel.addView(query); panel.addView(count)
        web.setFindListener { active, total, done -> if (done) count.text = if (total == 0) "没有匹配项" else "${active + 1} / $total" }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { web.findAllAsync(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val dialog = AlertDialog.Builder(this).setTitle("页内查找").setView(panel).setPositiveButton("下一处", null).setNeutralButton("上一处", null).setNegativeButton("完成", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { web.findNext(true) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { web.findNext(false) }
        }
        dialog.setOnDismissListener { web.clearMatches(); web.setFindListener(null) }; dialog.show()
    }
    private fun proxyLabel() = when (proxyState) { "running" -> "按规则分流"; "starting" -> "正在启动"; "failed" -> "故障，当前直连"; else -> "未开启" }
    private fun showProxy() {
        AlertDialog.Builder(this).setTitle("网络代理 · ${proxyLabel()}")
            .setItems(arrayOf(if (EmbeddedProxy.isEnabled()) "关闭代理" else "开启代理", "节点、订阅与完整配置", "重试连接", "连接说明")) { _, which ->
                when (which) {
                    0 -> {
                        val enable = !EmbeddedProxy.isEnabled()
                        if (enable && !EmbeddedProxy.hasProfile()) {
                            toast("请先导入并选择节点")
                            startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
                        } else {
                            requestNotificationPermission()
                            network.setEnabled(enable, proxyObserver)
                        }
                    }
                    1 -> startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
                    2 -> network.setEnabled(true, proxyObserver)
                    3 -> AlertDialog.Builder(this).setTitle("应用内代理").setMessage("网页和文件下载使用同一个代理入口，按节点规则分流。不会修改系统 VPN。\n\n核心故障时恢复直连；节点连接失败时保留失败，不绕过规则。\n\n${proxyMessage}").setPositiveButton("知道了", null).show()
                }
            }.show()
    }
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
    }
    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("设置").setItems(arrayOf("搜索引擎", "外观", "网页文字大小", "清理浏览数据", "设为默认浏览器", "关于行间")) { _, which ->
            when (which) {
                0 -> {
                    val engines = BrowserInput.engines.keys.toTypedArray()
                    AlertDialog.Builder(this).setTitle("搜索引擎").setSingleChoiceItems(engines, engines.indexOf(store.preferences.getString("engine", "必应"))) { dialog, index ->
                        store.preferences.edit().putString("engine", engines[index]).apply(); dialog.dismiss()
                    }.show()
                }
                1 -> {
                    val modes = arrayOf("system", "light", "dark")
                    AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), modes.indexOf(getSharedPreferences("web-appearance", MODE_PRIVATE).getString("theme", "system"))) { dialog, index ->
                        persist(); getSharedPreferences("web-appearance", MODE_PRIVATE).edit().putString("theme", modes[index]).apply(); dialog.dismiss(); recreate()
                    }.show()
                }
                2 -> AlertDialog.Builder(this).setTitle("网页文字大小").setItems(arrayOf("较小 · 85%", "标准 · 100%", "较大 · 115%", "大 · 130%")) { _, index ->
                    val value = intArrayOf(85, 100, 115, 130)[index]
                    store.preferences.edit().putInt("textZoom", value).apply(); tabs.forEach { it.view?.settings?.textZoom = value }
                }.show()
                3 -> AlertDialog.Builder(this).setTitle("清理浏览数据").setItems(arrayOf("清空历史", "清理网页缓存", "清除网站登录与存储")) { _, index ->
                    confirm(if (index == 2) "清除所有网站登录状态和本地存储？" else "确认清理？") {
                        when (index) {
                            0 -> store.clearHistory()
                            1 -> (tab?.view ?: WebView(this)).let { it.clearCache(true); if (it !== tab?.view) it.destroy() }
                            2 -> { CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }; WebStorage.getInstance().deleteAllData() }
                        }; toast("已清理")
                    }
                }.show()
                4 -> {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val roles = getSystemService(android.app.role.RoleManager::class.java)
                        if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER))
                            startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 91)
                        else toast("当前已是默认浏览器")
                    } else startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
                5 -> AlertDialog.Builder(this).setTitle("行间 0.1.0").setMessage("轻量浏览，自由抵达。\n\n所有书签、历史和代理配置保存在本机。\n\n内嵌 v2rayNG ${com.v2ray.ang.BuildConfig.SOURCE_VERSION} 与 Xray 核心。").setPositiveButton("开源许可") { _, _ -> startActivity(Intent(this, com.v2ray.ang.embedded.LicenseActivity::class.java)) }.setNegativeButton("关闭", null).show()
            }
        }.show()
    }
    private fun download(url: String, ua: String?, disposition: String?, mime: String?) {
        if (!BrowserInput.isWebUrl(url)) { toast("暂不支持此类型的下载地址"); return }
        val filename = URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180)
        pendingDownload = Intent(this, BrowserDownloadService::class.java).putExtra("url", url).putExtra("ua", ua ?: WebSettings.getDefaultUserAgent(this))
            .putExtra("cookie", CookieManager.getInstance().getCookie(url)).putExtra("referer", tab?.url).putExtra("title", filename)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime?.takeIf { it.contains('/') } ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE, filename), DOWNLOAD)
    }
    private fun showDownloads() {
        val entries = store.downloads()
        AlertDialog.Builder(this).setTitle("下载 · ${entries.size}").setItems(entries.map { "${it.title}\n${it.state}" }.toTypedArray()) { _, index ->
            val entry = entries[index]
            if (entry.state == "已完成") {
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), contentResolver.getType(Uri.parse(entry.uri)) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: Exception) { toast("文件已移动或没有可用的打开方式") }
            } else toast(entry.state)
        }.setPositiveButton("刷新") { _, _ -> showDownloads() }.setNegativeButton("关闭", null).show()
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
    override fun onStart() { super.onStart(); memoryHandler.post(memoryCheck) }
    override fun onStop() { memoryHandler.removeCallbacks(memoryCheck); super.onStop() }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() { if (::store.isInitialized) { persist(); CookieManager.getInstance().flush() }; tabs.forEach { it.view?.onPause() }; super.onPause() }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); permissionRequest?.deny()
        if (::network.isInitialized) network.unobserve(proxyObserver)
        memoryHandler.removeCallbacksAndMessages(null)
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
