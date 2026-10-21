package app.xvi.web

import java.util.Arrays
import java.util.Locale

/**
 * Whole sites blocked by category, built from domain lists in the hosts (`0.0.0.0 example.com`), plain
 * domain or `||example.com^` form. A listed domain blocks its subdomains too, and an allowed domain wins
 * over every category. Built once, then read from any thread.
 *
 * Lists of several hundred thousand sites are kept as sorted 64-bit hashes of the domains, a few
 * megabytes where the names themselves would take tens; two domains sharing a hash is vanishingly unlikely.
 */
internal class SiteFilter private constructor(
    /** Category name to the hashes of its domains, sorted. */
    private val categories: List<Pair<String, LongArray>>,
    private val allowed: LongArray
) {
    val isEmpty get() = categories.isEmpty()

    /** Number of sites listed under [category]. */
    fun size(category: String) = categories.firstOrNull { it.first == category }?.second?.size ?: 0

    /** The category of the site at [url], or null when it is not blocked. Only web addresses are. */
    fun category(url: String): String? {
        if (categories.isEmpty() || !url.startsWith("http", ignoreCase = true)) return null
        val host = AdFilter.hostOf(url) ?: return null
        val hashes = suffixes(host).map(::hash)
        if (hashes.any { Arrays.binarySearch(allowed, it) >= 0 }) return null
        return categories.firstOrNull { (_, sites) -> hashes.any { Arrays.binarySearch(sites, it) >= 0 } }?.first
    }

    companion object {
        val EMPTY = SiteFilter(emptyList(), LongArray(0))

        /** [categories] in the order a site is put down to the first that lists it; empty ones are left out. */
        fun build(categories: List<Pair<String, LongArray>>, allowed: LongArray = LongArray(0)) =
            SiteFilter(categories.filter { it.second.isNotEmpty() }, allowed)

        /** The domains in [lines], hashed and sorted for [build]; lines that name none are skipped. */
        fun hashes(lines: Sequence<String>): LongArray {
            var out = LongArray(1024)
            var count = 0
            lines.forEach { line ->
                val domain = domain(line) ?: return@forEach
                if (count == out.size) out = out.copyOf(count * 2)
                out[count++] = hash(domain)
            }
            return out.copyOf(count).also { it.sort() }
        }

        /**
         * The domain a list line names, lowercased, or null for comments, headers, local names and rules
         * that are about more than a whole site. An address pasted whole stands for its host.
         */
        fun domain(line: String): String? {
            val text = line.trim()
            if (text.isEmpty() || text[0] == '#' || text[0] == '!' || text[0] == '[') return null
            val tokens = text.substringBefore(" #").substringBefore("\t#").trim().split(WHITESPACE)
            var name = when {
                tokens.size == 1 -> tokens[0]
                tokens.size == 2 && tokens[0] in ADDRESSES -> tokens[1]
                else -> return null
            }
            if (name.startsWith("||")) {
                if (!name.endsWith("^")) return null
                name = name.substring(2, name.length - 1)
            } else if (name.contains("://")) name = AdFilter.hostOf(name) ?: return null
            name = name.lowercase(Locale.ROOT).trimEnd('.').removePrefix("*.")
            if (name in LOCAL || !valid(name)) return null
            return name
        }

        /** [host] and each domain above it, up to the top-level one. */
        private fun suffixes(host: String): List<String> {
            val out = ArrayList<String>(4)
            var start = 0
            while (true) {
                out.add(host.substring(start))
                val dot = host.indexOf('.', start)
                if (dot < 0 || dot == host.length - 1) return out
                start = dot + 1
            }
        }

        private fun valid(name: String): Boolean {
            if (name.isEmpty() || name.length > 253 || name[0] == '.' || name.contains("..")) return false
            return name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
        }

        /** FNV-1a, then mixed so that similar names spread over the whole range. */
        private fun hash(name: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in name) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            h = h xor (h ushr 33); h *= -0xae502812aa7333L
            h = h xor (h ushr 33); h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }

        private val WHITESPACE = Regex("\\s+")
        private val ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::0", "::1")
        private val LOCAL = setOf("0.0.0.0", "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
    }
}
