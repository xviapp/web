package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
    /** Proves [EXTRA_PROFILE] came from this app: the browser is exported, so any app or `intent:` link can name a profile. */
    private const val EXTRA_SIGNATURE = "app.xvi.web.PROFILE_SIGNATURE"
    private const val FILE = "profiles"
    private const val KEY = "registry"
    private const val SECRET = "intentSecret"
    private const val SHORTCUTS_SIGNED = "shortcutsSigned"

    private fun secret(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(SECRET, null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val made = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        prefs.edit(commit = true) { putString(SECRET, android.util.Base64.encodeToString(made, android.util.Base64.NO_WRAP)) }
        return made
    }

    private fun signature(context: Context, id: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret(context), "HmacSHA256"))
        return android.util.Base64.encodeToString(mac.doFinal(id.toByteArray()), android.util.Base64.NO_WRAP)
    }

    /** Names [id] as the profile [intent] opens in, in a way other apps cannot copy for another profile. */
    fun sign(context: Context, intent: Intent, id: String): Intent =
        intent.putExtra(EXTRA_PROFILE, id).putExtra(EXTRA_SIGNATURE, signature(context, id))

    /** The profile [intent] names, only when this app named it. */
    fun named(context: Context, intent: Intent): String? {
        val id = intent.getStringExtra(EXTRA_PROFILE) ?: return null
        val given = intent.getStringExtra(EXTRA_SIGNATURE) ?: return null
        return id.takeIf { java.security.MessageDigest.isEqual(given.toByteArray(), signature(context, id).toByteArray()) }
    }

    /** Home screen icons made before intents were signed get their signature, once. */
    fun signShortcuts(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SHORTCUTS_SIGNED, false)) return
        runCatching {
            val unsigned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).filter { info ->
                info.intent.getStringExtra(EXTRA_PROFILE) != null && !info.intent.hasExtra(EXTRA_SIGNATURE)
            }
            // An update replaces only what it sets, so the icon and labels stay as they were.
            val updated = unsigned.map { info ->
                val intent = Intent(info.intent)
                androidx.core.content.pm.ShortcutInfoCompat.Builder(context, info.id).setShortLabel(info.shortLabel)
                    .setIntent(sign(context, intent, checkNotNull(intent.getStringExtra(EXTRA_PROFILE)))).build()
            }
            if (updated.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, updated)
        }
        prefs.edit { putBoolean(SHORTCUTS_SIGNED, true) }
    }

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
        // Its downloads stop first; they write to its store and carry its cookies.
        if (BrowserDownloadService.running) listOf(false, true).forEach { private ->
            runCatching {
                context.startService(BrowserDownloadService.cancelIntent(context).putExtra(EXTRA_PROFILE, profile.id)
                    .putExtra(BrowserDownloadService.EXTRA_PRIVATE, private))
            }
        }
        BrowserStore.forget(context, profile.id)?.let(SiteChoices::forget)
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
