package app.xvi.web

/**
 * Search result addresses rewritten to ask the engine for its strict filter, which hides explicit
 * results and images: Google (`safe=active`), Bing (`adlt=strict`) and DuckDuckGo (`kp=1`). Other
 * addresses, and engines without such a switch, are left as they are.
 */
internal object SafeSearch {
    fun apply(url: String): String {
        val host = AdFilter.hostOf(url) ?: return url
        if (!url.startsWith("http", ignoreCase = true)) return url
        val path = path(url)
        val (name, value) = when {
            GOOGLE.matches(host) && path.startsWith("/search") -> "safe" to "active"
            host in BING && path.endsWith("/search") -> "adlt" to "strict"
            host in DUCKDUCKGO && query(url).split('&').any { it.startsWith("q=") } -> "kp" to "1"
            else -> return url
        }
        return withParameter(url, name, value)
    }

    /** [url] with [name] set to [value] alone in its query, keeping the rest and any fragment. */
    private fun withParameter(url: String, name: String, value: String): String {
        val fragment = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
        val base = url.removeSuffix(fragment)
        val start = base.indexOf('?')
        val address = if (start < 0) base else base.substring(0, start)
        val kept = query(base).split('&').filter { it.isNotEmpty() && it.substringBefore('=') != name }
        return address + "?" + (kept + "$name=$value").joinToString("&") + fragment
    }

    private fun query(url: String): String {
        val start = url.indexOf('?')
        return if (start < 0) "" else url.substring(start + 1).substringBefore('#')
    }

    private fun path(url: String): String {
        val rest = url.substring(url.indexOf("://") + 3)
        val slash = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (slash < 0 || rest[slash] != '/') return "/"
        return rest.substring(slash).substringBefore('?').substringBefore('#')
    }

    private val GOOGLE = Regex("""^(www\.)?google(\.[a-z]{2,3}){1,2}$""")
    private val BING = setOf("bing.com", "www.bing.com", "cn.bing.com")
    private val DUCKDUCKGO = setOf("duckduckgo.com", "www.duckduckgo.com", "html.duckduckgo.com", "lite.duckduckgo.com")
}
