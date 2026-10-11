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
 * Settings remembered per site (host without `www.`). Ordinary browsing keeps them in the store; a
 * private session keeps its own changes in memory and otherwise reads the ordinary ones.
 */
internal class SiteSettings(private val store: BrowserStore) {
    private val saved = mutableMapOf<String, SiteSetting>()

    fun load(values: Map<String, SiteSetting>) { saved.putAll(values) }

    fun get(session: BrowserSession, url: String): SiteSetting {
        val site = BrowserStore.siteKey(url) ?: return SiteSetting()
        return session.siteSettings[site] ?: saved[site] ?: SiteSetting()
    }

    fun set(session: BrowserSession, url: String, setting: SiteSetting) {
        val site = BrowserStore.siteKey(url) ?: return
        if (session.isPrivate) { session.siteSettings[site] = setting; return }
        if (setting.isEmpty) saved.remove(site) else saved[site] = setting
        store.writeAsync { saveSiteSetting(site, setting) }
    }

    fun clear() {
        saved.clear()
        store.writeAsync { clearSiteSettings() }
    }
}
