package app.xvi.web

import kotlin.math.exp
import kotlin.math.ln

/** A history or bookmark row considered for an address-bar suggestion. */
internal data class SuggestionCandidate(
    val url: String, val title: String, val visits: Int = 0, val updated: Long = 0, val bookmarked: Boolean = false
)

/** Orders address-bar matches so typed hosts, frequent and recent sites come first. */
internal object SuggestionRanking {
    private const val DAY = 24 * 60 * 60 * 1000.0

    /** Address without scheme or `www.`, lowercased: what people actually type. */
    fun bare(url: String): String = url.substringAfter("://").removePrefix("www.").lowercase()
    private fun host(url: String) = bare(url).substringBefore('/').substringBefore('?').substringBefore('#')

    fun score(candidate: SuggestionCandidate, query: String, now: Long): Double {
        val q = query.trim().lowercase()
        val bare = bare(candidate.url)
        val host = host(candidate.url)
        val title = candidate.title.lowercase()
        var score = when {
            host.startsWith(q) -> 100.0
            bare.startsWith(q) -> 80.0
            host.contains(".$q") || host.contains("-$q") -> 50.0
            title.startsWith(q) -> 40.0
            title.contains(q) -> 25.0
            bare.contains(q) -> 15.0
            else -> 0.0
        }
        if (score == 0.0) return 0.0
        if (candidate.bookmarked) score += 20
        score += 10 * ln(1.0 + candidate.visits)
        score += 20 * exp(-(now - candidate.updated).coerceAtLeast(0) / DAY / 14)
        // Among equal matches, the site's front page is the likelier target.
        score -= minOf(10.0, (bare.length - host.length) / 10.0)
        return score
    }

    fun rank(candidates: List<SuggestionCandidate>, query: String, now: Long, limit: Int): List<SuggestionCandidate> =
        candidates.map { it to score(it, query, now) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.url }.take(limit)

    /**
     * Host to complete inline while typing, such as `github.com` for `gi`; null when the query is not a
     * host prefix. The result always starts with [query], ignoring case.
     */
    fun completion(candidates: List<SuggestionCandidate>, query: String, now: Long): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty() || q != query.lowercase() || q.any { it.isWhitespace() || it == '/' || it.code > 127 }) return null
        return candidates.filter { host(it.url).startsWith(q) && host(it.url).length > q.length }
            .maxByOrNull { score(it, q, now) }?.let { host(it.url) }
    }
}
