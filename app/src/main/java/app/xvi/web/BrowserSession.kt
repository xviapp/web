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

/** Owns one website identity. Private profiles are never reused, even after a failed cleanup. */
// Private construction checks both required features; normal sessions never use profile APIs.
@SuppressLint("RequiresFeature")
@UiThread
class BrowserSession(val isPrivate: Boolean = false) {
    private val profileName = if (isPrivate) PRIVATE_PREFIX + UUID.randomUUID() else null
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

    init { require(!isPrivate || privateAvailable()) { "Private sessions are unavailable" } }

    fun createWebView(context: Context): BrowserWebView {
        check(!ended)
        return BrowserWebView(context).also { view ->
            try {
                profileName?.let {
                    WebViewCompat.setProfile(view, it)
                    profile = WebViewCompat.getProfile(view)
                    view.isSaveEnabled = false
                    view.isSaveFromParentEnabled = false
                    view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
        return if (isPrivate) checkNotNull(profile).cookieManager else CookieManager.getInstance()
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
        } else {
            android.webkit.WebStorage.getInstance().deleteAllData()
            cookies().removeAllCookies { CookieManager.getInstance().flush(); done() }
        }
    }

    fun release(view: WebView) {
        views.remove(view)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    /** Moves ownership too, so destroying the old window cannot destroy the moved page. */
    internal fun transfer(view: WebView, destination: BrowserSession) {
        check(!ended && !destination.ended && !isPrivate && !destination.isPrivate)
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
