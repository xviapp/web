package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
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

    init { require(!isPrivate || privateAvailable()) { "Private sessions are unavailable" } }

    fun createWebView(context: Context): WebView {
        check(!ended)
        return WebView(context).also { view ->
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

    fun visit(store: BrowserStore, title: String, url: String) {
        if (!ended && !isPrivate) { store.visit(title, url); cookies().flush() }
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

        /** Null when private sessions can start; otherwise a message for the user. */
        fun privateUnavailableReason(): String? {
            cleanupPreviousProcess()
            return when {
                !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
                    !WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA) ->
                    "当前 WebView 不支持无痕浏览，请更新 Android System WebView"
                !cleanupSucceeded -> "上次无痕数据未能清理，请完全退出浏览器后重试"
                else -> null
            }
        }

        fun privateAvailable(): Boolean = privateUnavailableReason() == null
    }
}
