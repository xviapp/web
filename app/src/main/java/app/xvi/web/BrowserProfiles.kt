package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.UiThread
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.util.UUID

/** The saved [ProfileRegistry] and what follows from changing it. */
@UiThread
internal object BrowserProfiles {
    /** Names the profile an intent or shortcut belongs to; without it, the choice is the receiver's. */
    const val EXTRA_PROFILE = "app.xvi.web.PROFILE"
    private const val FILE = "profiles"
    private const val KEY = "registry"

    /** Asks the WebView engine, so it is only called once another profile than the main one is involved. */
    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    private fun read(context: Context) = ProfileRegistry.decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    /** Written at once: a registry lost to a crash could leave a removed profile's data behind for good. */
    private fun write(context: Context, registry: ProfileRegistry) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, registry.encode()) }

    /** [id] if it can be opened here. A WebView too old for profiles keeps their data, and only the main profile works. */
    fun usable(context: Context, id: String): BrowserProfile? {
        val profile = read(context).find(id) ?: return null
        return profile.takeIf { it.isMain || supported }
    }

    private fun usableOrMain(context: Context, id: String) = usable(context, id) ?: BrowserProfile(BrowserProfile.MAIN, read(context).find(BrowserProfile.MAIN)?.name.orEmpty())

    /** What the browser shows. */
    fun active(context: Context) = usableOrMain(context, read(context).active)
    /** Where links, shares and searches from other apps open. */
    fun preferred(context: Context) = usableOrMain(context, read(context).preferred)

    fun all(context: Context): List<BrowserProfile> {
        val profiles = read(context).profiles
        return if (profiles.size == 1 || supported) profiles else profiles.filter { it.isMain }
    }

    fun label(context: Context, profile: BrowserProfile) = profile.name.ifBlank { context.getString(R.string.profile_main) }

    fun create(context: Context, name: String): BrowserProfile? {
        val id = UUID.randomUUID().toString()
        val next = read(context).create(id, name) ?: return null
        write(context, next)
        return next.find(id)
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        write(context, read(context).rename(id, name) ?: return false)
        return true
    }

    fun setActive(context: Context, id: String) = write(context, read(context).withActive(id))
    fun setPreferred(context: Context, id: String) = write(context, read(context).withPreferred(id))

    /**
     * Removes a profile that is not showing. Site data is deleted at once, closing its windows and icons
     * first. The name stays in [ProfileRegistry.removing], because a loaded WebView profile cannot be
     * deleted: [cleanupRemoved] finishes the job at the next process start.
     */
    fun remove(context: Context, id: String): Boolean {
        val registry = read(context)
        val profile = registry.find(id) ?: return false
        write(context, registry.remove(id) ?: return false)
        PageHost.closeWindows(id)
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .filter { it.intent.getStringExtra(EXTRA_PROFILE) == id }.map { it.id }
        if (pinned.isNotEmpty()) runCatching { ShortcutManagerCompat.disableShortcuts(context, pinned, context.getString(R.string.profile_shortcut_removed)) }
        BrowserSession(namedProfile = profile.webProfile).clearWebsiteData {}
        removeFiles(context, profile)
        return true
    }

    private fun removeFiles(context: Context, profile: BrowserProfile) {
        BrowserStore.forget(context, profile.id)
        BrowserPrefs.deleteTabs(context, profile.id)
        profile.webProfile?.let(UserScripts::forgetProfile)
    }

    /** Finishes what [remove] started. Nothing here loaded those profiles yet, so they can go now. */
    @SuppressLint("RequiresFeature") // Checked first; a profile can only be listed where the feature was there.
    fun cleanupRemoved(context: Context) {
        val registry = read(context)
        if (registry.removing.isEmpty() || !supported) return
        val store = ProfileStore.getInstance()
        val left = registry.removing.filterTo(LinkedHashSet()) { id ->
            val profile = BrowserProfile(id)
            removeFiles(context, profile)
            val name = checkNotNull(profile.webProfile)
            // Already gone is as good as deleted; one still in use is tried again at the next start.
            !runCatching { name !in store.allProfileNames || store.deleteProfile(name) }.getOrDefault(false)
        }
        write(context, registry.copy(removing = left))
    }
}
