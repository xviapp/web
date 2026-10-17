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

    /**
     * Remembered answers, because this runs per list row and per chrome refresh and addresses repeat
     * heavily in a session. The map is bounded and simply dropped once it grows past the limit.
     */
    private val displayed = Memo<String> { url ->
        if (!isWebUrl(url)) url
        else runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: url
    }

    /** Short form shown in the address bar while it is not being edited: the host without `www.`. */
    fun display(url: String): String = displayed[url] ?: url

    fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        isWebScheme(uri.scheme) && !uri.rawAuthority.isNullOrEmpty() && uri.rawUserInfo == null
    }.getOrDefault(false)

    /** Name of the user's own engine, offered once [customTemplate] is set. */
    const val CUSTOM_ENGINE = "自定义"

    /** Search address with `%s` where the encoded terms go; set from preferences. */
    @Volatile var customTemplate: String? = null

    /** A usable template is a web address with exactly one `%s`. */
    fun isValidTemplate(template: String): Boolean =
        template.split("%s").size == 2 && isWebUrl(template.replace("%s", "test"))

    /** Built-in engines plus the custom one when configured, in display order. */
    fun engineNames(): List<String> = engines.keys.toList() + listOfNotNull(CUSTOM_ENGINE.takeIf { customTemplate != null })

    fun isKnownEngine(name: String) = name in engineNames()

    /** The address [input] names directly, or null when it is a search. */
    fun directUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (isWebUrl(value)) return value
        val candidate = "https://$value"
        if (!value.any(Char::isWhitespace) && !value.contains("://") &&
            (value.substringBefore('/').contains('.') || value.substringBefore(':') == "localhost") && isWebUrl(candidate)) return candidate
        return null
    }

    fun searchUrl(terms: String, engine: String): String {
        val encoded = URLEncoder.encode(terms.trim(), "UTF-8")
        val template = customTemplate?.takeIf { engine == CUSTOM_ENGINE }
        if (template != null) return template.replace("%s", encoded)
        return (engines[engine] ?: engines.getValue(DEFAULT_ENGINE)) + encoded
    }

    /** Longest text taken from outside (shared, scanned, pasted); more is never a usable address or search. */
    const val MAX_INPUT = 8192

    /** [text] trimmed and capped at [MAX_INPUT]; null when nothing is left. */
    fun typed(text: String): String? = text.trim().take(MAX_INPUT).ifEmpty { null }

    /** http or https, the only schemes a tab loads. */
    fun isWebScheme(scheme: String?) = scheme != null && (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))

    fun resolve(input: String, engine: String): String {
        if (input.isBlank()) return ""
        return directUrl(input) ?: searchUrl(input, engine)
    }
}

/**
 * Answers of [compute] by key, for lookups that repeat heavily (per list row, per chrome refresh).
 * Bounded: once it holds [limit] keys it is simply dropped and starts over. Null answers are kept too.
 */
internal class Memo<V : Any>(private val limit: Int = 512, private val compute: (String) -> V?) {
    private val values = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<V>>()

    operator fun get(key: String): V? {
        values[key]?.let { return it.orElse(null) }
        val value = compute(key)
        if (values.size >= limit) values.clear()
        values[key] = java.util.Optional.ofNullable(value)
        return value
    }
}
