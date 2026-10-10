package app.xvi.web

import java.net.URI
import java.net.URLEncoder

/** Only web URLs enter a tab; external schemes require a separate user action. */
object BrowserInput {
    const val DEFAULT_ENGINE = "必应"
    val engines = linkedMapOf(
        "必应" to "https://www.bing.com/search?q=",
        "百度" to "https://www.baidu.com/s?wd=",
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q="
    )
    /** OpenSearch suggestion endpoints; each answers `[query, [suggestion, …]]`. */
    val suggestEndpoints = mapOf(
        "必应" to "https://api.bing.com/osjson.aspx?query=",
        "百度" to "https://suggestion.baidu.com/su?action=opensearch&ie=utf-8&wd=",
        "Google" to "https://suggestqueries.google.com/complete/search?client=firefox&q=",
        "DuckDuckGo" to "https://duckduckgo.com/ac/?type=list&q="
    )

    /** Short form shown in the address bar while it is not being edited: the host without `www.`. */
    fun display(url: String): String {
        if (!isWebUrl(url)) return url
        return runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: url
    }

    fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrEmpty() && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun resolve(input: String, engine: String): String {
        val value = input.trim()
        if (value.isEmpty()) return ""
        if (isWebUrl(value)) return value
        val candidate = "https://$value"
        if (!value.any(Char::isWhitespace) && !value.contains("://") &&
            (value.substringBefore('/').contains('.') || value.substringBefore(':') == "localhost") && isWebUrl(candidate)) return candidate
        return (engines[engine] ?: engines.getValue(DEFAULT_ENGINE)) + URLEncoder.encode(value, "UTF-8")
    }
}
