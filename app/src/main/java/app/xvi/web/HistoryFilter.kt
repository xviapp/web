package app.xvi.web

import java.time.Instant
import java.util.TimeZone
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One manual history search. Text is literal and case-insensitive; a domain is a whole host. */
internal data class HistoryFilter(
    val query: String = "",
    val mode: Mode = Mode.KEYWORD,
    val since: Long = 0,
    val includeSubdomains: Boolean = true
) {
    enum class Mode { KEYWORD, URL_CONTAINS, URL_PREFIX, PATH_SUFFIX, DOMAIN }

    enum class Range {
        HOUR, TODAY, WEEK, ALL;

        fun since(now: Long, zone: TimeZone = TimeZone.getDefault()): Long = when (this) {
            HOUR -> now - 60 * 60_000L
            TODAY -> HistoryDays.startOfDay(now, zone)
            WEEK -> Instant.ofEpochMilli(now).atZone(zone.toZoneId()).toLocalDate().minusDays(6)
                .atStartOfDay(zone.toZoneId()).toInstant().toEpochMilli()
            ALL -> 0
        }
    }

    private val text = query.trim()
    private val domain = if (mode == Mode.DOMAIN) domain(text) else null
    val isValid: Boolean get() = when {
        mode == Mode.KEYWORD -> true
        text.isEmpty() -> false
        mode == Mode.DOMAIN -> domain != null
        else -> true
    }

    fun matches(entry: PageEntry): Boolean {
        if (!isValid || entry.time < since) return false
        if (text.isEmpty()) return true
        return when (mode) {
            Mode.KEYWORD -> entry.title.contains(text, ignoreCase = true) || entry.url.contains(text, ignoreCase = true)
            Mode.URL_CONTAINS -> entry.url.contains(text, ignoreCase = true)
            Mode.URL_PREFIX -> entry.url.startsWith(text, ignoreCase = true)
            Mode.PATH_SUFFIX -> entry.url.toHttpUrlOrNull()?.pathSegments?.joinToString("/", prefix = "/")
                ?.endsWith(text, ignoreCase = true) == true
            Mode.DOMAIN -> {
                val host = entry.url.toHttpUrlOrNull()?.host?.trimEnd('.') ?: return false
                host == domain || includeSubdomains && host.endsWith(".$domain")
            }
        }
    }

    /** LIKE folds ASCII only. Non-ASCII rows bypass the prefilter for ASCII queries, and
     * non-ASCII caseful queries use the exact Kotlin matcher after the indexed time filter. */
    internal fun sqlText(): Pair<String, List<String>> {
        if (text.isEmpty() || mode !in setOf(Mode.KEYWORD, Mode.URL_CONTAINS, Mode.URL_PREFIX)) return "" to emptyList()
        if (text.any { it.code > 127 && it.lowercaseChar() != it.uppercaseChar() }) return "" to emptyList()
        val pattern = (if (mode == Mode.URL_PREFIX) "" else "%") + BrowserStore.escapeLike(text) + "%"
        val columns = if (mode == Mode.KEYWORD) listOf("title", "url") else listOf("url")
        val clauses = columns.map { "$it LIKE ? ESCAPE '\\'" }.toMutableList()
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) columns.forEach { clauses += "$it GLOB '*[^ -~]*'" }
        return clauses.joinToString(" OR ") to List(columns.size) { pattern }
    }

    private fun domain(value: String): String? {
        if (value.isEmpty() || value.any { it.isWhitespace() || it in "/\\:?#@[]*%" }) return null
        val host = "https://${value.removeSuffix(".")}/".toHttpUrlOrNull()?.host ?: return null
        if (host.length > 253 || host.split('.').any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }) return null
        return host
    }
}
