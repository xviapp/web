package app.xvi.web

/** One site's own settings; null fields follow the browser-wide defaults. */
data class SiteSetting(
    val desktop: Boolean? = null,
    val textZoom: Int? = null,
    val javascript: Boolean? = null,
    val images: Boolean? = null,
    /** False when ads are allowed on the site. */
    val adblock: Boolean? = null
) {
    val isEmpty get() = desktop == null && textZoom == null && javascript == null && images == null && adblock == null

    internal fun toValues(): Map<String, Int> = buildMap {
        desktop?.let { put(DESKTOP, if (it) 1 else 0) }
        textZoom?.let { put(TEXT_ZOOM, it) }
        javascript?.let { put(JAVASCRIPT, if (it) 1 else 0) }
        images?.let { put(IMAGES, if (it) 1 else 0) }
        adblock?.let { put(ADBLOCK, if (it) 1 else 0) }
    }

    companion object {
        private const val DESKTOP = "desktop"
        private const val TEXT_ZOOM = "textZoom"
        private const val JAVASCRIPT = "javascript"
        private const val IMAGES = "images"
        private const val ADBLOCK = "adblock"

        internal fun fromValues(values: Map<String, Int>) = SiteSetting(
            desktop = values[DESKTOP]?.let { it != 0 },
            textZoom = values[TEXT_ZOOM]?.takeIf { it in 50..300 },
            javascript = values[JAVASCRIPT]?.let { it != 0 },
            images = values[IMAGES]?.let { it != 0 },
            adblock = values[ADBLOCK]?.let { it != 0 }
        )
    }
}

/**
 * Choices for ordinary browsing that every window shares: each site's settings and the origins
 * allowed to open pop-ups. One copy per process, read from the store once; lookups are in memory on
 * the main thread. Every change goes through here, is written to the store and is announced to the
 * open windows, so none of them keeps applying an old value. A private session keeps its own setting
 * changes in memory and otherwise reads the ordinary ones.
 */
internal class SiteChoices private constructor(private val store: BrowserStore) {
    private val settings = HashMap<String, SiteSetting>()
    private val popups = HashSet<String>()
    /** Sites and origins changed before the first read finished; that read must not undo them. */
    private val early = HashSet<String>()
    private val earlyPopups = HashSet<String>()
    private var loaded = false
    /** Raised by every replacement, so a read that started before one is not applied over it. */
    private var generation = 0
    private var loading = false
    private val waiting = ArrayList<() -> Unit>()
    /** Windows to tell about a change: the site that changed, or null for all of them. */
    private val listeners = LinkedHashSet<(String?) -> Unit>()

    /** Runs [ready] once the stored choices are in memory: at once after the first window read them. */
    fun whenLoaded(ready: () -> Unit) {
        if (loaded) { ready(); return }
        waiting += ready
        if (loading) return
        loading = true
        val started = generation
        read { values, origins ->
            if (generation == started) {
                values.forEach { (site, setting) -> if (site !in early) settings[site] = setting }
                origins.forEach { if (it !in earlyPopups) popups += it }
            }
            early.clear(); earlyPopups.clear()
            loaded = true
            loading = false
            waiting.toList().also { waiting.clear() }.forEach { it() }
        }
    }

    /** Reads the stored choices again and replaces what is in memory, after a backup changed them. */
    fun reload(done: () -> Unit) {
        val started = ++generation
        read { values, origins -> if (generation == started) replace(values, origins); done() }
    }

    private fun replace(values: Map<String, SiteSetting>, origins: List<String>) {
        settings.clear(); settings.putAll(values)
        popups.clear(); popups.addAll(origins)
        early.clear(); earlyPopups.clear()
        loaded = true
        announce(null)
    }

    fun addListener(listener: (String?) -> Unit) { listeners += listener }
    fun removeListener(listener: (String?) -> Unit) { listeners -= listener }

    fun get(session: BrowserSession, url: String): SiteSetting {
        val site = BrowserStore.siteKey(url) ?: return SiteSetting()
        return session.siteSettings[site] ?: settings[site] ?: SiteSetting()
    }

    /** Saves [setting] for [url]'s site; returns the site, or null when the address has none. */
    fun set(session: BrowserSession, url: String, setting: SiteSetting): String? {
        val site = BrowserStore.siteKey(url) ?: return null
        if (session.isPrivate) { session.siteSettings[site] = setting; return site }
        if (setting.isEmpty) settings.remove(site) else settings[site] = setting
        if (!loaded) early += site
        store.writeAsync { saveSiteSetting(site, setting) }
        announce(site)
        return site
    }

    fun allowsPopups(origin: String?) = origin != null && origin in popups

    fun setPopups(origin: String, allowed: Boolean) {
        if (allowed) popups += origin else popups -= origin
        if (!loaded) earlyPopups += origin
        store.writeAsync { if (allowed) setPermission(origin, SitePermissions.POPUP, true) else clearPermission(origin, SitePermissions.POPUP) }
    }

    /** Forgets every site's settings and every remembered permission. */
    fun clear() {
        generation++
        settings.clear(); popups.clear()
        store.writeAsync { clearSiteSettings(); clearPermissions() }
        announce(null)
    }

    private fun announce(site: String?) = listeners.toList().forEach { it(site) }

    private fun read(apply: (Map<String, SiteSetting>, List<String>) -> Unit) {
        store.writeAsync {
            val values = siteSettings()
            val origins = allowedOrigins(SitePermissions.POPUP)
            main.post { apply(values, origins) }
        }
    }

    companion object {
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        @Volatile private var instance: SiteChoices? = null

        fun get(store: BrowserStore): SiteChoices = instance ?: synchronized(this) {
            instance ?: SiteChoices(store).also { instance = it }
        }
    }
}
