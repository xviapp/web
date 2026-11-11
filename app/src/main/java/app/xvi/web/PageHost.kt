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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.net.Proxy
import java.util.function.Consumer

/**
 * A window that shows web pages. Everything a page can ask of the app lives here: its website identity,
 * downloads, uploads, permissions, script dialogs, full-screen video and the background session. The
 * browser adds tabs and its toolbar on top; a web app window shows one site with nothing around it.
 */
abstract class PageHost : AppCompatActivity() {
    /** Height of the controls at the window's bottom that panels stay above. */
    internal open fun sheetClearance() = 0

    /** The profile this window shows, fixed for the window's lifetime; see [chooseProfile]. */
    internal lateinit var profile: BrowserProfile
        private set
    internal lateinit var normalSession: BrowserSession
        private set
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
    internal val scriptInstall = UserScriptInstall(this)
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
    private var cutoutModeBeforeFullScreen: Int? = null
    /** Input fields whose privacy flags are already in place, so the IME is not restarted again. */
    private val inputPrivacy = java.util.WeakHashMap<EditText, Boolean>()

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
    /**
     * A new window whose first navigation leaves the browser (an `intent:` link) has nothing to show:
     * it closes, leaving no trace, and the tab that opened it asks about the app. Null for any other tab.
     */
    internal open fun closeBlankWindow(target: BrowserTab): BrowserTab? = null
    /** Opens [url] for a user script of [source]'s page; a window without tabs hands it over as it does links. */
    internal open fun openTab(source: BrowserTab, url: String, background: Boolean) = openInBackground(source, url)
    /** What opening a link from the long-press menu is called in this window. */
    internal open val backgroundLinkLabel: Int get() = R.string.link_open_new_tab
    internal open val canOpenPrivate: Boolean get() = false
    internal open fun openPrivate(url: String) = Unit
    /** Whether a page, rather than something of the app's own, fills the page area. */
    protected open val pageShowing: Boolean get() = true
    protected open fun onProxyChanged(state: BrowserProxyNetwork.Status) = Unit
    protected open fun onFullScreenChanged() = Unit
    protected open val immersiveMode: Boolean get() = fullView != null
    /** Window-specific WebView wiring, before the view is used. */
    internal open fun configureWebView(target: BrowserTab, web: BrowserWebView) = Unit
    /** Decides [profile], before anything of the window exists. */
    internal abstract fun chooseProfile(savedInstanceState: Bundle?): BrowserProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        profile = chooseProfile(savedInstanceState)
        normalSession = BrowserSession(namedProfile = profile.webProfile)
        prefs = BrowserPrefs(this, profile.id)
        delegate.localNightMode = prefs.theme.nightMode
        super.onCreate(savedInstanceState)
        store = BrowserStore.get(this, profile.id)
        BrowserInput.customTemplate = prefs.customEngine.takeIf(BrowserInput::isValidTemplate)
        // Removing the private profiles an earlier process left starts the WebView engine, which the first
        // frame does not need; it waits until that frame is up, unless a private tab asks for it sooner.
        window.decorView.post {
            BrowserSession.cleanupPreviousProcess(); BrowserProfiles.cleanupRemoved(applicationContext); BrowserProfiles.signShortcuts(applicationContext)
        }
        // Another window may still be using its captures and staged files.
        // A window restored after the system ended the process finds what that process left, too; only a
        // camera picture it may still be waiting for is recent enough to keep.
        if (live.isEmpty() && (savedInstanceState == null || !tempChecked)) {
            UploadController.clearOld(cacheDir, keepRecent = savedInstanceState != null)
            DownloadController.clearStaging(cacheDir)
        }
        tempChecked = true
        live.add(this)
        siteChoices.addListener(choicesChanged)
        // Pages that loaded before the first window read the choices get them now.
        siteChoices.whenLoaded { if (!isDestroyed) reapplySiteSettings() }
        if (!BrowserDownloadService.running) store.writeAsync { interruptDownloads() }
        network = BrowserProxyNetwork.get(this)
        ui = BrowserUi(this, BrowserPalette(this, prefs).also { it.themed(theme) })
        media.register(getSystemService(AudioManager::class.java))
        AdBlock.ensureLoaded(this)
        SiteBlock.ensureLoaded(this)
        UserScripts.ensureLoaded(this)
        UserScripts.whenReady(normalSession.scriptScope) {}
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
    /**
     * The address to load for [url]: asking for strict results when it is a search and parental control
     * wants that, then its HTTPS form when HTTPS-First applies, remembering the address before for a fallback.
     */
    internal fun addressToLoad(target: BrowserTab, url: String): String {
        val wanted = if (prefs.safeSearch) SafeSearch.apply(url) else url
        val upgraded = if (upgradesToHttps && HttpsFirst.host(wanted) !in target.session.httpAllowed) HttpsFirst.upgrade(wanted) else null
        target.upgradedFrom = upgraded?.let { wanted }
        return upgraded ?: wanted
    }
    /** [url] is on a site parental control blocks: the page stops before anything of it loads, and says why. */
    internal fun siteBlocked(target: BrowserTab, view: WebView, url: String, category: SiteBlock.Category) {
        view.stopLoading()
        target.url = url
        target.upgradedFrom = null
        val reason = if (category == SiteBlock.Category.CUSTOM) str(R.string.parental_blocked_custom)
            else str(R.string.parental_blocked_message, str(ParentalPanel.name(category)))
        showPageError(target, PageError(str(R.string.parental_blocked_title), reason, blockedSite = AdFilter.hostOf(url) ?: url))
    }
    /**
     * What shows in place of a blocked site: which site and why, and ways out. Trying again or copying
     * the address would lead nowhere, so neither is offered; [home] is the window's way home, if any.
     */
    internal fun blockedPage(error: PageError, home: (() -> Unit)?): View {
        val panel = ui.column().apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(24), dp(32), dp(24)) }
        val badge = FrameLayout(this).apply { background = ui.rounded(ColorUtils.setAlphaComponent(palette.accent, 0x24), 44) }
        badge.addView(ImageView(this).apply { setImageDrawable(ui.tintedIcon(R.drawable.ic_shield_24, palette.accent)) },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        panel.addView(badge, LinearLayout.LayoutParams(dp(88), dp(88)))
        panel.addView(ui.medium(ui.label(error.title, 21f)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(22) })
        error.blockedSite?.let { site ->
            val chip = ui.singleLine(ui.label(site, 14f), TextUtils.TruncateAt.MIDDLE).apply {
                background = ui.rounded(palette.badgeSurface, 14)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            panel.addView(chip, LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(12) })
        }
        panel.addView(ui.label(error.message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(16) })
        panel.addView(ui.textAction(str(R.string.parental_blocked_back), true) { onBackPressedDispatcher.onBackPressed() },
            LinearLayout.LayoutParams(dp(220), ui.wrap()).apply { topMargin = dp(28) })
        home?.let {
            panel.addView(ui.textAction(str(R.string.error_go_home)) { it() }.apply { setTextColor(palette.muted) },
                LinearLayout.LayoutParams(dp(220), ui.wrap()).apply { topMargin = dp(6) })
        }
        return panel
    }

    /**
     * Load or certificate failure card, matching [blockedPage] in structure while offering retry,
     * an optional secondary action (such as copying the URL or opening in the browser), any
     * session-scoped proceed action on [error], and [home] when available.
     */
    internal fun errorPage(
        target: BrowserTab,
        error: PageError,
        secondary: Pair<String, () -> Unit>? = null,
        home: (() -> Unit)? = null
    ): View {
        val panel = ui.column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(palette.surface)
        }
        val cautionTone = if (error.action != null) palette.warning else palette.accent
        val iconRes = if (error.action != null) R.drawable.ic_shield_24 else R.drawable.ic_info_24
        val badge = FrameLayout(this).apply { background = ui.rounded(ColorUtils.setAlphaComponent(cautionTone, 0x24), 44) }
        badge.addView(ImageView(this).apply { setImageDrawable(ui.tintedIcon(iconRes, cautionTone)) },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        panel.addView(badge, LinearLayout.LayoutParams(dp(88), dp(88)))
        panel.addView(ui.medium(ui.label(error.title, 21f)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(22) })
        if (BrowserInput.isWebUrl(target.url)) {
            val site = HttpsFirst.host(target.url) ?: BrowserInput.display(target.url)
            val chip = ui.singleLine(ui.label(site, 14f), TextUtils.TruncateAt.MIDDLE).apply {
                background = ui.rounded(palette.badgeSurface, 14)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            panel.addView(chip, LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(12) })
        }
        if (error.message.isNotEmpty()) {
            panel.addView(ui.label(error.message, 15f, palette.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(ui.wrap(), ui.wrap()).apply { topMargin = dp(16) })
        }
        val actions = ui.row().apply { gravity = Gravity.CENTER }
        actions.addView(ui.textAction(str(R.string.action_retry), true) { retryPage(target) }, ui.weighted())
        if (secondary != null) {
            actions.addView(ui.textAction(secondary.first) { secondary.second() }, ui.weighted().apply { leftMargin = dp(8) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(dp(260), ui.wrap()).apply { topMargin = dp(26) })
        error.action?.let { (label, action) ->
            panel.addView(ui.textAction(label) { action() }.apply { setTextColor(palette.danger) },
                LinearLayout.LayoutParams(dp(260), ui.wrap()).apply { topMargin = dp(6) })
        }
        home?.let {
            panel.addView(ui.textAction(str(R.string.error_go_home)) { it() }.apply { setTextColor(palette.muted) },
                LinearLayout.LayoutParams(dp(260), ui.wrap()).apply { topMargin = dp(6) })
        }
        return panel
    }
    /** HTTPS failed for [original]; explains that and offers the plain HTTP address, for this session. */
    internal fun httpsUnavailable(target: BrowserTab, original: String, downgradeTo: String? = null) {
        target.upgradedFrom = null
        val site = HttpsFirst.host(original) ?: original
        showPageError(target, PageError(str(R.string.error_https_title), str(R.string.error_https_message, site),
            str(R.string.error_https_proceed) to {
                target.session.httpAllowed.add(site)
                // `example.com` sending plain HTTP visitors to `www.example.com` would otherwise be upgraded,
                // and sent back down, once more there.
                downgradeTo?.let(HttpsFirst::host)?.let(target.session.httpAllowed::add)
                load(target, original)
            }))
    }

    /**
     * [source] is the tab that asked for the dialog; a background private page still hides its dialog.
     * Dialogs take the panels' surface and rounding, so pure black and the other palettes reach them too.
     */
    internal fun browserDialog(source: BrowserTab? = tab): AlertDialog.Builder = object : AlertDialog.Builder(this) {
        override fun create(): AlertDialog = super.create().also {
            if (source?.isPrivate == true || isPrivateMode) { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); privateDialogs.add(it) }
            // The inset matches the default dialog background's, so the width on screen stays the same.
            it.window?.setBackgroundDrawable(InsetDrawable(ui.rounded(palette.surface, 24), ui.dp(16)))
        }
    }
    private var hostMemo: Pair<String, String?>? = null
    /** HTTPS without an ignored certificate error, and not showing a load failure. */
    internal fun isSecure(target: BrowserTab?): Boolean {
        val url = target?.url.orEmpty()
        if (!url.startsWith("https://", ignoreCase = true) || target?.error != null) return false
        // Only the address is remembered: the same page may be open in a session that ignored a certificate error.
        val host = hostMemo?.takeIf { it.first == url }?.second
            ?: runCatching { java.net.URI(url).host }.getOrNull().also { hostMemo = url to it }
        return host != null && host !in target?.session?.certificateExceptions.orEmpty()
    }

    /** Dialogs about private pages, closed when the private lock takes over. */
    private val privateDialogs: MutableSet<AlertDialog> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    internal fun dismissPrivateDialogs() {
        privateDialogs.toList().forEach { if (it.isShowing) runCatching { it.dismiss() } }
        privateDialogs.clear()
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
    internal fun applySiteSettings(target: BrowserTab, web: BrowserWebView, url: String): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        val site = siteChoices.get(target.session, url)
        val desktop = site.desktop ?: false
        val javascript = site.javascript ?: true
        val images = site.images ?: true
        web.settings.textZoom = site.textZoom ?: prefs.textZoom
        target.desktop = desktop
        target.adblock = prefs.adblock && site.adblock != false
        if (!siteSettingsDiffer(web, site)) return false
        web.pageUserAgent.apply(desktop)
        web.settings.javaScriptEnabled = javascript
        web.settings.loadsImagesAutomatically = images
        return true
    }

    /**
     * Whether [url]'s site wants [web] in another mode (desktop, JavaScript, images), which only a fresh load
     * takes up. Navigation callbacks ask this rather than switching at once: changing the user agent while
     * WebView is still deciding on a navigation crashes it.
     */
    internal fun siteSettingsDiffer(target: BrowserTab, web: BrowserWebView, url: String): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        return siteSettingsDiffer(web, siteChoices.get(target.session, url))
    }

    private fun siteSettingsDiffer(web: BrowserWebView, site: SiteSetting): Boolean =
        web.pageUserAgent.isDesktop != (site.desktop ?: false) ||
            web.settings.javaScriptEnabled != (site.javascript ?: true) || web.settings.loadsImagesAutomatically != (site.images ?: true)

    /**
     * Runs [action] on [web] once the WebView callback that asks has returned, unless the tab moved on in
     * between: another view, another load started by the browser, or another document.
     */
    internal fun afterCallback(target: BrowserTab, web: WebView, action: () -> Unit) {
        val navigation = target.navigation
        val document = target.documentVersion
        web.post { if (target.view === web && target.navigation == navigation && target.documentVersion == document) action() }
    }

    /**
     * [applySiteSettings] from inside a WebView callback: a switch of mode waits for [afterCallback], as the
     * user agent cannot change there, and then [switched] runs.
     */
    internal fun applySiteSettingsInCallback(target: BrowserTab, web: BrowserWebView, url: String, switched: () -> Unit = {}) {
        if (!siteSettingsDiffer(target, web, url)) applySiteSettings(target, web, url)
        else afterCallback(target, web) { if (applySiteSettings(target, web, url)) switched() }
    }

    /** Applies the ad blocking switch and each site's choice to the open pages; they keep what already loaded. */
    internal fun refreshAdblock() {
        tabs.forEach { item -> item.adblock = prefs.adblock && siteChoices.get(item.session, item.url).adblock != false }
    }

    /** Stop existing page writers before deletion, and resume visible pages only once it finishes. */
    internal fun clearSiteData(session: BrowserSession, url: String, done: (Boolean) -> Unit) {
        val site = SiteData.domain(url) ?: run { done(false); return }
        clearWithPagesUnloaded(session, site, strict = true, clear = { finished -> session.clearSiteData(url, finished) }, done = done)
    }

    /**
     * Every site's data in [session]'s identity, with its pages in all windows unloaded first, so none
     * writes its cookies or storage back. A page slow to unload does not stop the deletion.
     */
    internal fun clearWebsiteData(session: BrowserSession, done: (Boolean) -> Unit) =
        clearWithPagesUnloaded(session, null, strict = false, clear = { finished -> session.clearWebsiteData(finished) }, done = done)

    private fun clearWithPagesUnloaded(session: BrowserSession, site: String?, strict: Boolean, clear: (() -> Unit) -> Unit, done: (Boolean) -> Unit) {
        val pending = mutableListOf<Pair<BrowserSession, WebView>>()
        val resume = suspendSitePages(session, site, pending)
        fun delete(ready: Boolean) {
            if (strict && !ready) { resume(); done(false); return }
            try {
                clear { resume(); done(true) }
            } catch (_: RuntimeException) {
                resume()
                done(false)
            }
        }
        if (pending.isEmpty()) { delete(true); return }
        var remaining = pending.size
        var unloaded = true
        pending.forEach { (owner, web) -> owner.releaseForSiteDataClear(web) { ready ->
            unloaded = unloaded && ready
            if (--remaining == 0) delete(unloaded)
        } }
    }

    /** The returned continuation also restores pages after a synchronous deletion failure. */
    private fun suspendSitePages(session: BrowserSession, site: String?, pending: MutableList<Pair<BrowserSession, WebView>>): () -> Unit {
        val resumptions = mutableListOf<() -> Unit>()
        live.toList().filter { !it.isDestroyed }.forEach { window ->
            val affected = window.tabs.filter { item ->
                val sameSession = if (session.isPrivate) item.session === session
                    else !item.isPrivate && item.session.namedProfile == session.namedProfile
                sameSession && (site == null || SiteData.domain(item.url) == site)
            }
            affected.forEach { item ->
                item.navigation++
                item.documentVersion++
                item.state = null
                item.restoreScroll = null
                item.waiting = false
                item.view?.let { web ->
                    item.view = null
                    window.pageGone(web)
                    (web.parent as? ViewGroup)?.removeView(web)
                    pending += item.session to web
                }
            }
            affected.firstOrNull(window::showsPage)?.let { item ->
                val navigation = item.navigation
                resumptions += {
                    if (!window.isDestroyed && item in window.tabs && item.navigation == navigation &&
                        item.view == null && window.showsPage(item)) window.retryPage(item)
                }
            }
        }
        return { resumptions.forEach { it() } }
    }

    /** Browser-wide settings changed: every open window applies them to its pages. */
    internal fun preferencesChanged() = live.toList().forEach { if (!it.isDestroyed) it.applyPreferences() }

    private fun applyPreferences() {
        refreshAdblock()
        reapplySiteSettings()
        applyPageDarkening()
        if (prefs.forceZoom) tabs.forEach { item -> item.view?.let(pages::pageReady) }
    }

    private val adKeys by lazy { assets.open("ad-keys.js").bufferedReader().use { it.readText() } }
    private val adScans = java.util.WeakHashMap<WebView, Triple<Long, Long, String>>()

    /**
     * Hides ads in bounded passes; later calls consume only new or changed elements. [again] makes
     * another, [last] pass a little later, after which the page stops being watched until the next call.
     */
    internal fun hideAds(target: BrowserTab, web: WebView, again: Boolean = false, last: Boolean = false) {
        if (!target.adblock || target.view !== web) return
        val filter = AdBlock.filter ?: return
        val page = web.url?.takeIf(BrowserInput::isWebUrl) ?: return
        val document = target.documentVersion
        fun current() = !isDestroyed && target in tabs && target.adblock && target.view === web && target.documentVersion == document && web.url == page && AdBlock.filter === filter
        val pass = Triple(document, filter.revision, page)
        if (again) main.postDelayed({ if (current()) hideAds(target, web, last = true) }, ADS_AGAIN_MS)
        if (adScans[web] == pass) return
        adScans[web] = pass
        lifecycleScope.launch {
            try {
                // However the page answers, a pass ends: 50 batches cover a hundred thousand elements.
                var batches = 0
                while (current() && batches++ < MAX_AD_BATCHES) {
                    val raw = web.readString("($adKeys)(${filter.revision}, ${org.json.JSONObject.quote(page)})") ?: break
                    if (!current()) break
                    val result = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: break
                    val keys = result.optJSONArray("keys")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    }.orEmpty()
                    val css = withContext(kotlinx.coroutines.Dispatchers.Default) { filter.hidingCss(page, keys, result.optBoolean("first")) }
                    if (!current()) break
                    val more = result.optBoolean("more")
                    // Only a batch the page confirms taking lets the next one be asked for.
                    if (web.readString(AdFilter.injectScript(css.orEmpty(), filter.revision, page, done = !more)) != "ok") break
                    if (!more) break
                    kotlinx.coroutines.yield()
                }
                if (last && current()) web.evaluate("($adKeys)(${filter.revision}, ${org.json.JSONObject.quote(page)}, true)")
            } finally {
                if (adScans[web] == pass) adScans.remove(web)
            }
        }
    }

    /**
     * Background keep-alive runs only while media plays in any window. Starting is only attempted
     * while visible, as Android requires.
     */
    internal fun syncKeepAlive() {
        if (!::prefs.isInitialized) return
        val wanted = live.any { it.media.playing }
        val intent = Intent(this, BrowserKeepAliveService::class.java)
        if (wanted && !BrowserKeepAliveService.running) {
            // An answer that comes in just after the window left still gets a try: Android before 12
            // allows it, and later ones refuse it here without harm.
            val started = runCatching { ContextCompat.startForegroundService(this, intent) }.isSuccess
            if (started && timersPaused) anyPage()?.let { timersPaused = false; it.resumeTimers() }
        } else if (!wanted && BrowserKeepAliveService.running) stopService(intent)
    }

    /** Lets pages without their own dark theme be darkened while the browser is dark. */
    internal fun applyPageDarkening(web: WebView? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val allowed = palette.dark && prefs.darkPages
        (web?.let(::listOf) ?: tabs.mapNotNull { it.view }).forEach { WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, allowed) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun createWebView(target: BrowserTab): BrowserWebView = target.session.createWebView(this).apply {
        // Timers left paused when no page outlived the last window must not hold up the next page.
        if (timersPaused) { timersPaused = false; resumeTimers() }
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
        }
        pageUserAgent.apply(target.desktop)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(this, PAGE_START_SCRIPT + pages.touchTargetScript, setOf("*")) }
        }
        UserScripts.attach(this, target.session)
        target.session.cookies().setAcceptCookie(true)
        target.session.cookies().setAcceptThirdPartyCookies(this, false)
        bindWebView(target, this)
    }

    /** Reconnects a live page's callbacks when its owning window changes, without reloading it. */
    internal fun bindWebView(target: BrowserTab, web: BrowserWebView) = with(web) {
        rehost(this@PageHost)
        onPull = { distance, release -> pullToRefresh(target, this, distance, release) }
        onSwipeDown = null
        showAi = { false }
        onAskAi = null
        webViewClient = PageClient(this@PageHost, target, this)
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

    /** Prepares scripts and profile values before the first document, then prepares its network route. */
    internal fun preparePage(target: BrowserTab, done: (BrowserProxyNetwork.Status) -> Unit) {
        UserScripts.whenReady(target.session.scriptScope) {
            if (!isDestroyed && target in tabs) network.prepare { status -> if (!isDestroyed) done(status) }
        }
    }

    internal suspend fun aiProxy(base: String): Proxy {
        if (DeepSeek.isDefault(base)) return Proxy.NO_PROXY
        return suspendCancellableCoroutine { continuation ->
            network.prepare { if (continuation.isActive) continuation.resume(network.proxy()) }
        }
    }

    /** Holds reloads until scripts and networking are ready; repeated requests collapse into the latest. */
    internal fun reloadWhenReady(target: BrowserTab, web: WebView, reload: () -> Unit = web::reload) {
        target.waiting = true
        val navigation = ++target.navigation
        if (proxyConnecting && !showsWaitingReason) toast(R.string.proxy_waiting)
        if (tab === target) updateProgress()
        preparePage(target) {
            if (isDestroyed || target.navigation != navigation || target.view !== web) return@preparePage
            target.waiting = false
            reload()
            if (tab === target) updateProgress()
        }
    }

    /** The tab whose video is in full screen. */
    internal var fullOwner: BrowserTab? = null
        private set
    internal fun showFullScreen(view: View, callback: WebChromeClient.CustomViewCallback, owner: BrowserTab? = null) {
        if (fullView != null) { callback.onCustomViewHidden(); return }
        fullOwner = owner
        val holder = VideoGestures(ui, window, getSystemService(AudioManager::class.java)).apply { hold(view) }
        fullView = holder; fullCallback = callback; root.visibility = View.GONE
        (window.decorView as ViewGroup).addView(holder, ViewGroup.LayoutParams(ui.fill(), ui.fill()))
        updateSystemBars()
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
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(view != null && !privateStaysInWindow).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    /** A floating window would show private video outside the private lock. */
    private val privateStaysInWindow get() = tab?.isPrivate == true && prefs.lockPrivate
    private val supportsPictureInPicture by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private fun updatePictureInPicture() {
        if (supportsPictureInPicture) runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 and later enter on their own (auto-enter); older systems are asked here.
        if (fullView != null && !privateStaysInWindow && Build.VERSION.SDK_INT < 31 && supportsPictureInPicture) runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Closing the floating window stops the activity; the video leaves full screen with it.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) hideFullScreen()
    }
    internal fun hideFullScreen() {
        val wasFull = fullView != null
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it); (it as? VideoGestures)?.release() }
        // The page behind the callback may be gone already, after its renderer crashed.
        fullView = null; fullOwner = null; fullCallback?.let { runCatching { it.onCustomViewHidden() } }; fullCallback = null
        if (!wasFull) return
        updatePictureInPicture()
        root.visibility = View.VISIBLE
        updateSystemBars()
        onFullScreenChanged()
    }

    /** Video and browser full screen share the bars, but can be exited independently. */
    protected fun updateSystemBars() {
        if (Build.VERSION.SDK_INT >= 28) {
            val attributes = window.attributes
            if (immersiveMode && cutoutModeBeforeFullScreen == null) {
                cutoutModeBeforeFullScreen = attributes.layoutInDisplayCutoutMode
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attributes
            } else if (!immersiveMode) cutoutModeBeforeFullScreen?.let {
                attributes.layoutInDisplayCutoutMode = it
                window.attributes = attributes
                cutoutModeBeforeFullScreen = null
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = if (immersiveMode) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (immersiveMode) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveMode) updateSystemBars()
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

    private var recreating = false
    override fun recreate() {
        recreating = true
        super.recreate()
    }

    internal fun toast(message: String) {
        if (isDestroyed) return
        if (recreating || isFinishing || isChangingConfigurations || !::root.isInitialized ||
            !root.isAttachedToWindow || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        main.post {
            if (isDestroyed) return@post
            if (recreating || isFinishing || isChangingConfigurations || !root.isAttachedToWindow ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            } else {
                BrowserSheet.activeOver(root)?.showSnackbar(message) ?: showSnackbar(message)
            }
        }
    }
    internal fun toast(@StringRes message: Int) = toast(str(message))

    override fun onStart() {
        super.onStart()
        AdBlock.updateIfDue(this)
        windowsStarted++
        if (timersPaused) anyPage()?.let { timersPaused = false; it.resumeTimers() }
        syncKeepAlive()
    }
    /**
     * With no window on screen and no media kept playing, the pages' timers stop: unseen pages would go on
     * waking the phone. They are process-wide, so they wait for the last window to go.
     */
    override fun onStop() {
        windowsStarted--
        if (windowsStarted == 0 && !isChangingConfigurations && !BrowserKeepAliveService.running) {
            anyPage()?.let { it.pauseTimers(); timersPaused = true }
        }
        super.onStop()
    }
    override fun onResume() { super.onResume(); tabs.forEach { it.view?.onResume() } }
    override fun onPause() {
        if (::prefs.isInitialized) normalSession.cookies().flush()
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
        // This window's media was released above; another window's may still keep the service.
        syncKeepAlive()
        siteChoices.removeListener(choicesChanged)
        normalSession.end()
        if (cleanupOnLastWindow && live.isEmpty()) {
            cleanupOnLastWindow = false
            ExitCleanup.run(this) { BrowserPrefs(applicationContext).exitCleanupPending = false }
        }
        super.onDestroy()
    }

    internal companion object {
        /** Every open window, so the background session and cached files account for all of them. */
        private val live = mutableSetOf<PageHost>()
        /** The browser was quit with data to clear on exit; that happens once the last window is gone. */
        internal var cleanupOnLastWindow = false
        /** Set once this process looked at the temporary files a window leaves. */
        private var tempChecked = false
        /** Windows between their start and stop; the pages' timers run while there is one. */
        private var windowsStarted = 0
        /** Set while the pages' timers are paused, until a window starts or a page is made. */
        private var timersPaused = false

        /** Closes the windows of a profile being removed; the browser itself never shows one. */
        fun closeWindows(profile: String) = live.filter { it.profile.id == profile }.forEach { it.finishAndRemoveTask() }

        /** Any page of any window, to reach the timers all pages share. */
        private fun anyPage(): WebView? = live.firstNotNullOfOrNull { host -> host.tabs.firstNotNullOfOrNull { it.view } }
        /** Second element hiding pass, for content pages add after loading. */
        private const val ADS_AGAIN_MS = 3_000L
        private const val MAX_AD_BATCHES = 50
        /** How long a pulled reload may take to start loading before its spinner gives up. */
        private const val PULL_START_MS = 1_500L

        /**
         * Runs before page scripts. Pages often revoke a `blob:` address right after clicking it, before
         * the browser can read the file, so revoking waits a minute; the name in a link's `download`
         * attribute is kept for `blob:` and `data:` links, since the download request does not carry it.
         * The file behind each `blob:` address is kept as well: pages whose security policy leaves `blob:`
         * out of `connect-src` (grok.com) refuse to fetch it, but the file itself can still be read.
         * Pages that make and drop files quickly (photo albums, chats, PDF viewers) would hold a minute's
         * worth of them; past 50 MB waiting, the oldest go at once, never the one just revoked.
         */
        const val PAGE_START_SCRIPT = "(() => { try { if (window.__xviDownloadNames) return; const names = new Map();" +
            " Object.defineProperty(window, '__xviDownloadNames', { value: names });" +
            " const edited = new WeakSet(); Object.defineProperty(window, '__xviEdited', { value: edited });" +
            " document.addEventListener('input', e => { try { const t = e.composedPath ? e.composedPath()[0] : e.target; if (t) edited.add(t); } catch (_) {} }, true);" +
            " const blobs = new Map(); Object.defineProperty(window, '__xviBlobs', { value: blobs });" +
            // Files a page never revokes stay kept only up to 100 MB and 200 of them, the oldest going first.
            " let kept = 0; const forget = u => { const b = blobs.get(u); if (b) { kept -= b.size; blobs.delete(u); } };" +
            " const create = URL.createObjectURL; URL.createObjectURL = function (o) { const u = create.apply(URL, arguments); try { if (o instanceof Blob) { blobs.set(u, o); kept += o.size;" +
            " for (const [old] of blobs) { if (kept <= 104857600 && blobs.size <= 200) break; if (old !== u && !waiting.has(old)) forget(old); } } } catch (_) {} return u; };" +
            " const keep = a => { try { if (a && a.hasAttribute('download') && /^(blob|data):/i.test(a.href) && a.href.length < 1048576) { names.set(a.href, a.getAttribute('download') || ''); if (names.size > 50) names.delete(names.keys().next().value); } } catch (_) {} };" +
            " const click = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () { keep(this); return click.apply(this, arguments); };" +
            " document.addEventListener('click', e => { keep(e.target && e.target.closest ? e.target.closest('a[download]') : null); }, true);" +
            " const revoke = URL.revokeObjectURL, waiting = new Map(); let held = 0;" +
            " const release = u => { const w = waiting.get(u); if (!w) return; waiting.delete(u); held -= w.size; clearTimeout(w.timer); forget(u); revoke.call(URL, u); };" +
            " URL.revokeObjectURL = function (u) { if (waiting.has(u)) return; const b = blobs.get(u), size = b ? b.size : 0;" +
            " waiting.set(u, { size, timer: setTimeout(() => release(u), 60000) }); held += size;" +
            " for (const [old] of waiting) { if (held <= 52428800 || old === u) break; release(old); } };" +
            " } catch (_) {} })();"
    }
}
