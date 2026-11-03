package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * Owns one website identity: WebView's default profile, the profile named [namedProfile], or a private
 * one. Private profiles are never reused, even after a failed cleanup; a private session started from a
 * named profile keeps [namedProfile] only so it can read what that profile's user scripts stored.
 */
// Private and named sessions are only made once the required features were checked; sessions of the
// main profile never use profile APIs.
@SuppressLint("RequiresFeature")
@UiThread
class BrowserSession(val isPrivate: Boolean = false, internal val namedProfile: String? = null) {
    private val profileName = if (isPrivate) PRIVATE_PREFIX + UUID.randomUUID() else namedProfile
    private var profile: Profile? = null
    private val views = mutableSetOf<WebView>()
    private var ended = false
    /** Sites allowed to open pop-ups without a tap, for this session only. */
    val popupSites = mutableSetOf<String>()
    /** Site settings changed in this private session; never written to disk. */
    internal val siteSettings = mutableMapOf<String, SiteSetting>()
    /** Hosts whose certificate errors the user chose to ignore, for this session only. */
    val certificateExceptions = mutableSetOf<String>()
    /** Hosts the user chose to reach over plain HTTP after HTTPS failed, for this session only. */
    val httpAllowed = mutableSetOf<String>()
    /**
     * Values user scripts stored, by script. In a private session they are never written to disk; in a
     * named profile's [scriptScope] they are what its pages see, and [UserScripts] saves them.
     */
    internal val scriptValues = mutableMapOf<String, MutableMap<String, String>>()

    init { require(!isPrivate || privateAvailable()) { "Private sessions are unavailable" } }

    /**
     * Whose user script values this session's pages use: null for the main profile, else one session per
     * named profile however many windows have it open, so those windows see each other's changes.
     */
    internal val scriptScope: BrowserSession? get() = if (isPrivate) this else namedProfile?.let(::shared)
    /** The scope a private session's values start from; null for the main profile. */
    internal val parentScriptScope: BrowserSession? get() = namedProfile?.let(::shared)

    /** The WebView profile of a named session; works before it has a page, as a download retry needs. */
    private fun named(): Profile = profile ?: ProfileStore.getInstance().getOrCreateProfile(checkNotNull(namedProfile)).also { profile = it }

    fun createWebView(context: Context): BrowserWebView {
        check(!ended)
        return BrowserWebView(context).also { view ->
            try {
                profileName?.let {
                    WebViewCompat.setProfile(view, it)
                    profile = WebViewCompat.getProfile(view)
                    if (isPrivate) {
                        view.isSaveEnabled = false
                        view.isSaveFromParentEnabled = false
                        view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    }
                }
                views.add(view)
            } catch (error: RuntimeException) {
                view.destroy()
                throw error
            }
        }
    }

    fun cookies(): CookieManager {
        check(!ended)
        return when {
            isPrivate -> checkNotNull(profile).cookieManager
            namedProfile != null -> named().cookieManager
            else -> CookieManager.getInstance()
        }
    }

    /** Cookies are flushed when the browser pauses, not after every page. */
    fun visit(store: BrowserStore, title: String, url: String) {
        if (!ended && !isPrivate) store.visit(title, url)
    }

    fun clearWebsiteData(done: () -> Unit) {
        check(!ended)
        if (isPrivate) {
            val current = profile
            if (current == null) done()
            else WebStorageCompat.deleteBrowsingData(current.webStorage, done)
        } else if (namedProfile != null) {
            WebStorageCompat.deleteBrowsingData(named().webStorage, done)
        } else {
            android.webkit.WebStorage.getInstance().deleteAllData()
            cookies().removeAllCookies { CookieManager.getInstance().flush(); done() }
        }
    }

    /** Forgets the location answers WebView keeps on its own for this identity. */
    fun clearLocationAnswers() {
        check(!ended)
        when {
            isPrivate -> profile?.geolocationPermissions?.clearAll()
            namedProfile != null -> named().geolocationPermissions.clearAll()
            else -> android.webkit.GeolocationPermissions.getInstance().clearAll()
        }
    }

    fun release(view: WebView) {
        views.remove(view)
        UserScripts.detach(view)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    /** Moves ownership too, so destroying the old window cannot destroy the moved page. */
    internal fun transfer(view: WebView, destination: BrowserSession) {
        check(!ended && !destination.ended && !isPrivate && !destination.isPrivate && namedProfile == destination.namedProfile)
        check(views.remove(view))
        destination.views.add(view)
        destination.popupSites.addAll(popupSites)
        destination.certificateExceptions.addAll(certificateExceptions)
        destination.httpAllowed.addAll(httpAllowed)
    }

    /** Also releases paused/background WebViews. Completion means website data deletion finished. */
    fun end(done: (Boolean) -> Unit = {}) {
        if (ended) return
        ended = true
        views.toList().forEach(::release)
        val oldProfile = profile
        profile = null
        if (!isPrivate || oldProfile == null) { done(true); return }
        val name = oldProfile.name
        try {
            oldProfile.geolocationPermissions.clearAll()
            WebStorageCompat.deleteBrowsingData(oldProfile.webStorage) {
                // Destroyed WebViews detach asynchronously; deleting a still-loaded profile throws and
                // leaves the empty profile for the next process start.
                Handler(Looper.getMainLooper()).post {
                    runCatching { ProfileStore.getInstance().deleteProfile(name) }
                    done(true)
                }
            }
        } catch (_: RuntimeException) {
            // Leave the profile name on disk for deletion before profiles load next process start.
            done(false)
        }
    }

    companion object {
        private const val PRIVATE_PREFIX = "xvi-private-"
        private var startupCleaned = false
        private var cleanupSucceeded = false
        private val sharedScopes = HashMap<String, BrowserSession>()
        private fun shared(name: String) = sharedScopes.getOrPut(name) { BrowserSession(namedProfile = name) }
        internal fun forgetShared(name: String) { sharedScopes.remove(name) }

        /** Call before creating any private WebView. Loaded profiles cannot be deleted. */
        fun cleanupPreviousProcess() {
            if (startupCleaned) return
            startupCleaned = true
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            cleanupSucceeded = runCatching {
                val profiles = ProfileStore.getInstance()
                profiles.allProfileNames.filter { it.startsWith(PRIVATE_PREFIX) }.forEach { profiles.deleteProfile(it) }
            }.isSuccess
        }

        /** Null when private sessions can start; otherwise a message resource for the user. */
        @StringRes fun privateUnavailableReason(): Int? {
            cleanupPreviousProcess()
            return when {
                !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
                    !WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA) ->
                    R.string.private_unsupported
                !cleanupSucceeded -> R.string.private_cleanup_pending
                else -> null
            }
        }

        fun privateAvailable(): Boolean = privateUnavailableReason() == null
    }
}
