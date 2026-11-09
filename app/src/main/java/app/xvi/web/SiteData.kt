package app.xvi.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The registrable domain used by WebView's site-data deletion, including all its subdomains. */
internal object SiteData {
    fun domain(url: String): String? = url.toHttpUrlOrNull()?.let { it.topPrivateDomain() ?: it.host }
}
