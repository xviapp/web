package app.xvi.web

import java.io.OutputStream
import java.util.Arrays
import java.util.Locale

/**
 * Ad blocking rules in the Adblock Plus syntax that EasyList and most other filter lists use: request
 * rules that stop ads and trackers from loading, and element hiding rules that remove what is left on
 * the page. Built once from the list text, then read from any thread.
 *
 * Only the common part of the syntax is supported. Rules that need more than a browser without
 * extensions can do (scriptlets, procedural selectors, response rewriting, pop-up-only rules) are
 * skipped, never approximated, so an unsupported rule cannot block the wrong thing.
 *
 * Request rules are found without testing each one: rules for a whole host sit in a sorted array of
 * hashes, rules anchored to a host are filed under that host, and the rest under a word from their pattern
 * that any matching address must contain. What holds for every request of a page is worked out once.
 *
 * A built filter can be written out ([write]) and read back ([read]) in a fraction of the time the lists
 * take to parse, so a browser starting with unchanged lists need not parse them again.
 */
internal class AdFilter private constructor(
    private val block: Rules,
    private val allow: Rules,
    /** Selectors hidden only on a site (and its subdomains), by site. */
    private val specific: Map<String, List<String>>,
    /** Selectors a site keeps visible, by site. */
    private val unhidden: Map<String, Set<String>>,
    /** Generic selectors starting with a class or id, by that `.class` / `#id`. */
    private val keyed: Map<String, List<String>>,
    /** Generic selectors without a class or id to file them under; small enough to apply everywhere. */
    private val unkeyed: List<String>,
    /** Sites a generic selector does not apply to. */
    private val genericExcept: Map<String, Set<String>>,
    /** Generic selectors turned off everywhere. */
    private val genericOff: Set<String>,
    /** Number of rules in use. */
    val size: Int
) {
    /** Request rules of one kind (blocking or exceptions), filed for lookup. */
    private class Rules {
        /**
         * Hosts blocked outright (`||host^` without options), as sorted [SiteFilter.hash]es: the lists name
         * tens of thousands, which as strings in a set would take several times the memory.
         */
        private var hosts = LongArray(256)
        private var hostCount = 0
        val byHost = HashMap<String, MutableList<NetRule>>()
        val byToken = HashMap<String, MutableList<NetRule>>()
        val rest = ArrayList<NetRule>()

        fun addHost(host: String) {
            if (hostCount == hosts.size) hosts = hosts.copyOf(hostCount * 2)
            hosts[hostCount++] = SiteFilter.hash(host)
        }

        /** Done adding hosts: they are sorted for lookup. */
        fun seal() { hosts = hosts.copyOf(hostCount).also { it.sort() } }

        fun hasHost(domain: String) = hostCount > 0 && Arrays.binarySearch(hosts, SiteFilter.hash(domain)) >= 0

        fun write(out: Encoder) {
            out.number(hostCount)
            for (i in 0 until hostCount) out.long(hosts[i])
            for (filed in listOf(byHost, byToken)) out.map(filed) { list -> out.number(list.size); list.forEach { it.write(out) } }
            out.number(rest.size)
            rest.forEach { it.write(out) }
        }

        companion object {
            fun read(input: Decoder) = Rules().apply {
                hostCount = input.number()
                hosts = LongArray(hostCount) { input.long() }
                for (filed in listOf(byHost, byToken)) repeat(input.number()) { filed[input.string()] = NetRule.readAll(input) }
                rest.addAll(NetRule.readAll(input))
            }
        }
    }

    /** The page requests come from: its address as a request of its own, and its page-level exceptions as flags. */
    private class Page(val url: String, val self: Request, val base: String, val exceptions: Int)

    /** The page last asked about; WebView asks about one page's requests in long runs, from several threads. */
    @Volatile private var lastPage: Page? = null

    /**
     * Whether a request for [url], made by a page at [pageUrl], is blocked. [types] are the kinds of
     * request it may be ([requestTypes]); a rule limited to other kinds does not apply.
     */
    fun blocks(url: String, pageUrl: String, types: Int): Boolean {
        val page = page(pageUrl) ?: return false
        if (page.exceptions and DOCUMENT != 0) return false
        val host = hostOf(url) ?: return false
        val request = Request(url, host, page.self.host, baseDomain(host) != page.base)
        // `$genericblock` keeps only the rules written for particular sites.
        val blocking = find(block, request, types, siteRulesOnly = page.exceptions and GENERICBLOCK != 0) ?: return false
        if (blocking.important) return true
        return find(allow, request, types) == null
    }

    /**
     * Element hiding for the page at [pageUrl] whose elements carry the classes and ids in [keys]
     * (`.name`, `#name`), as a style sheet; null when nothing is hidden there.
     */
    fun hidingCss(pageUrl: String, keys: Collection<String>): String? {
        val page = page(pageUrl) ?: return null
        val exempt = page.exceptions
        if (exempt and (DOCUMENT or ELEMHIDE) != 0) return null
        val generic = exempt and GENERICHIDE == 0
        val domains = page.self.domains
        val kept = domains.flatMapTo(HashSet()) { unhidden[it].orEmpty() }
        val chosen = LinkedHashSet<String>()
        domains.forEach { domain -> specific[domain]?.forEach { if (it !in kept) chosen.add(it) } }
        if (generic) {
            fun add(selector: String) {
                if (selector in kept || selector in genericOff) return
                val except = genericExcept[selector]
                if (except != null && domains.any { it in except }) return
                chosen.add(selector)
            }
            unkeyed.forEach(::add)
            keys.forEach { key -> keyed[key]?.forEach(::add) }
        }
        if (chosen.isEmpty()) return null
        // One rule per selector: a selector the engine cannot parse then drops only itself.
        return chosen.joinToString("\n") { "$it{display:none!important}" }
    }

    /** The page at [pageUrl], with the page-level exceptions (`$document`, `$elemhide`, …) that apply to it. */
    private fun page(pageUrl: String): Page? {
        lastPage?.let { if (it.url == pageUrl) return it }
        val host = hostOf(pageUrl) ?: return null
        val self = Request(pageUrl, host, host, thirdParty = false)
        var flags = 0
        candidates(allow, self) { if (it.pageFlags != 0 && it.matches(self, ALL_TYPES)) flags = flags or it.pageFlags; false }
        return Page(pageUrl, self, baseDomain(host), flags).also { lastPage = it }
    }

    /** First rule matching [request]; with [siteRulesOnly], only rules limited to particular sites count. */
    private fun find(rules: Rules, request: Request, types: Int, siteRulesOnly: Boolean = false): NetRule? {
        if (!siteRulesOnly && request.domains.any(rules::hasHost)) return HOST_RULE
        var found: NetRule? = null
        candidates(rules, request) { rule ->
            (rule.pageFlags == 0 && (!siteRulesOnly || rule.domains.isNotEmpty()) && rule.matches(request, types)).also { if (it) found = rule }
        }
        return found
    }

    /** Hands [visit] the rules filed where [request] could match them, until it answers true. */
    private inline fun candidates(rules: Rules, request: Request, visit: (NetRule) -> Boolean) {
        for (domain in request.domains) rules.byHost[domain]?.let { list -> for (rule in list) if (visit(rule)) return }
        for (word in request.words) rules.byToken[word]?.let { list -> for (rule in list) if (visit(rule)) return }
        for (rule in rules.rest) if (visit(rule)) return
    }

    /** An address split into the parts rules look at; [pageHost] is the host of the page that asked for it. */
    private class Request(val url: String, val host: String, val pageHost: String, val thirdParty: Boolean) {
        val lower: String = url.lowercase(Locale.ROOT)
        /** [host] and each domain above it. */
        val domains = parents(host)
        private var found: Set<String>? = null
        /** The address's words, for the rules filed under one; worked out only once a lookup gets that far. */
        val words: Set<String> get() = found ?: tokens(lower).also { found = it }
    }

    /** One request rule. */
    private class NetRule(
        /** Pattern in the rule's own syntax, lowercased unless the rule matches case. */
        val pattern: String,
        val matchCase: Boolean,
        /** Set when the pattern is exactly the host the rule is filed under; the lookup then decides. */
        val hostOnly: Boolean,
        /** A `/regular expression/` rule's expression as written; null for a rule in the filter syntax. */
        val expression: String?,
        val types: Int,
        val thirdParty: Boolean?,
        val domains: List<String>,
        val notDomains: List<String>,
        val important: Boolean,
        /** Page-level exception flags; such a rule only answers what holds for a whole [page]. */
        val pageFlags: Int,
        /** Compiled on first use, since most rules are never tested that far; two threads compiling it at once is harmless. */
        @Volatile private var compiled: Regex? = null
    ) {
        fun matches(request: Request, requestTypes: Int): Boolean {
            if (pageFlags == 0 && types and requestTypes == 0) return false
            if (thirdParty != null && thirdParty != request.thirdParty) return false
            if (notDomains.isNotEmpty() && notDomains.any { onDomain(request.pageHost, it) }) return false
            if (domains.isNotEmpty() && domains.none { onDomain(request.pageHost, it) }) return false
            if (hostOnly) return true
            val regex = compiled ?: (expression?.let { expressionRegex(it, matchCase) } ?: Regex(toRegex(pattern))).also { compiled = it }
            return regex.containsMatchIn(if (matchCase) request.url else request.lower)
        }

        fun write(out: Encoder) {
            out.string(pattern)
            out.byte((if (matchCase) 1 else 0) or (if (hostOnly) 2 else 0) or (if (important) 4 else 0) or (if (expression != null) 8 else 0) or
                when (thirdParty) { true -> 16; false -> 32; null -> 0 })
            expression?.let(out::string)
            out.number(types)
            out.strings(domains)
            out.strings(notDomains)
            out.number(pageFlags)
        }

        companion object {
            fun readAll(input: Decoder): MutableList<NetRule> {
                val count = input.number()
                return ArrayList<NetRule>(count).apply { repeat(count) { add(read(input)) } }
            }

            private fun read(input: Decoder): NetRule {
                val pattern = input.string()
                val flags = input.byte()
                val expression = if (flags and 8 != 0) input.string() else null
                val types = input.number()
                val domains = input.list()
                val notDomains = input.list()
                val pageFlags = input.number()
                val thirdParty = when (flags and 48) { 16 -> true; 32 -> false; else -> null }
                return NetRule(pattern, flags and 1 != 0, flags and 2 != 0, expression, types, thirdParty, domains, notDomains, flags and 4 != 0, pageFlags)
            }
        }
    }

    /**
     * The filter as [read] takes it back: each distinct string once, then the rules as numbers into that
     * table. Strings the lists repeat (domains, selectors) are thus also shared once read back.
     */
    fun write(out: OutputStream) {
        val body = Encoder()
        body.number(size)
        block.write(body)
        allow.write(body)
        body.map(specific, body::strings)
        body.map(unhidden, body::strings)
        body.map(keyed, body::strings)
        body.strings(unkeyed)
        body.map(genericExcept, body::strings)
        body.strings(genericOff)
        val head = Encoder()
        head.number(FORMAT)
        head.number(body.table.size)
        body.table.keys.forEach(head::text)
        head.writeTo(out)
        body.writeTo(out)
    }

    /** Bytes being written. Whole numbers take as few as they need: seven bits to a byte, the high bit saying more follow. */
    private class Encoder {
        /** Each distinct string written, with its number, in the order first written. */
        val table = LinkedHashMap<String, Int>()
        private var bytes = ByteArray(1 shl 16)
        private var size = 0

        private fun room(more: Int) { if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more)) }
        fun byte(value: Int) { room(1); bytes[size++] = value.toByte() }
        fun number(value: Int) {
            var rest = value
            while (rest and 0x7f.inv() != 0) { byte(rest and 0x7f or 0x80); rest = rest ushr 7 }
            byte(rest)
        }
        fun long(value: Long) { room(8); for (shift in 56 downTo 0 step 8) bytes[size++] = (value ushr shift).toByte() }
        fun text(value: String) {
            val utf = value.toByteArray(Charsets.UTF_8)
            number(utf.size)
            room(utf.size)
            utf.copyInto(bytes, size)
            size += utf.size
        }
        fun string(value: String) = number(table.getOrPut(value) { table.size })
        fun strings(values: Collection<String>) { number(values.size); values.forEach(::string) }
        fun <V> map(map: Map<String, V>, value: (V) -> Unit) { number(map.size); map.forEach { (key, v) -> string(key); value(v) } }
        fun writeTo(out: OutputStream) = out.write(bytes, 0, size)
    }

    /** Reads what [Encoder] wrote, from [at]; reading past the end throws, so a cut-off file is never half taken. */
    private class Decoder(private val bytes: ByteArray, private var at: Int) {
        var table: Array<String> = emptyArray()

        fun byte() = bytes[at++].toInt() and 0xff
        fun number(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val next = byte()
                value = value or ((next and 0x7f) shl shift)
                if (next and 0x80 == 0) return value
                shift += 7
                require(shift < 32) { "malformed number" }
            }
        }
        fun long(): Long { var value = 0L; repeat(8) { value = (value shl 8) or byte().toLong() }; return value }
        fun text(): String {
            val length = number()
            require(length <= bytes.size - at) { "cut off" }
            return String(bytes, at, length, Charsets.UTF_8).also { at += length }
        }
        fun string() = table[number()]
        fun list(): List<String> { val count = number(); return if (count == 0) emptyList() else List(count) { string() } }
        fun set(): Set<String> { val count = number(); return HashSet<String>(count * 4 / 3 + 1).apply { repeat(count) { add(string()) } } }
        fun <V> map(value: () -> V): Map<String, V> {
            val count = number()
            return HashMap<String, V>(count * 4 / 3 + 1).apply { repeat(count) { val key = string(); put(key, value()) } }
        }
    }

    companion object {
        const val SCRIPT = 1
        const val IMAGE = 2
        const val STYLESHEET = 4
        const val XHR = 8
        const val SUBDOCUMENT = 16
        const val MEDIA = 32
        const val FONT = 64
        const val OBJECT = 128
        const val PING = 256
        const val WEBSOCKET = 512
        const val OTHER = 1024
        const val ALL_TYPES = 2047
        /** Kinds a request without a telling extension or Accept header may be. */
        private const val UNKNOWN = ALL_TYPES and (IMAGE or STYLESHEET or SUBDOCUMENT).inv()

        private const val DOCUMENT = 1
        private const val ELEMHIDE = 2
        private const val GENERICHIDE = 4
        private const val GENERICBLOCK = 8

        /** Stands for a `||host^` rule matched through the host set. */
        private val HOST_RULE = NetRule("", false, true, null, ALL_TYPES, null, emptyList(), emptyList(), false, 0)
        /** Version of what [write] produces; [read] takes no other. */
        private const val FORMAT = 1

        private val TYPE_NAMES = mapOf(
            "script" to SCRIPT, "image" to IMAGE, "stylesheet" to STYLESHEET, "css" to STYLESHEET,
            "xmlhttprequest" to XHR, "xhr" to XHR, "subdocument" to SUBDOCUMENT, "frame" to SUBDOCUMENT,
            "media" to MEDIA, "font" to FONT, "object" to OBJECT, "ping" to PING, "beacon" to PING,
            "websocket" to WEBSOCKET, "other" to OTHER, "all" to ALL_TYPES
        )
        private val PAGE_OPTIONS = mapOf(
            "document" to DOCUMENT, "doc" to DOCUMENT, "elemhide" to ELEMHIDE, "ehide" to ELEMHIDE,
            "generichide" to GENERICHIDE, "ghide" to GENERICHIDE, "genericblock" to GENERICBLOCK
        )
        /** Options this browser cannot honour; a rule carrying one is left out rather than misapplied. */
        private val UNSUPPORTED = setOf(
            "popup", "csp", "rewrite", "redirect", "redirect-rule", "removeparam", "queryprune", "replace",
            "header", "sitekey", "webrtc", "permissions", "urltransform", "denyallow", "to", "method",
            "badfilter", "empty", "mp4", "cookie", "stealth", "content", "jsinject", "extension", "urlblock",
            "inline-script", "inline-font", "strict1p", "strict3p", "1p-strict", "3p-strict"
        )
        /** Selector syntax only extensions understand. */
        private val PROCEDURAL = listOf(
            ":-abp-", ":contains(", ":has-text(", ":upward(", ":xpath(", ":remove(", ":style(", ":matches-css",
            ":min-text-length(", ":watch-attr(", ":others(", ":matches-path(", ":matches-attr(", ":matches-prop(",
            ":remove-attr(", ":remove-class(", ":nth-ancestor(", ":if(", ":if-not(", ":-ext-", "+js("
        )
        private val KEY = Regex("^[.#][A-Za-z_-][\\w-]*")
        private val EXTENDED = Regex("#[@?$%]{1,2}#")
        private val TOKEN = Regex("[a-z0-9%]+")

        /** Builds the filter from list lines; blank lines, comments and unsupported rules are skipped. */
        fun build(lines: Sequence<String>): AdFilter {
            val block = Rules()
            val allow = Rules()
            val specific = HashMap<String, MutableList<String>>()
            val unhidden = HashMap<String, MutableSet<String>>()
            val keyed = HashMap<String, MutableList<String>>()
            val unkeyed = ArrayList<String>()
            val genericExcept = HashMap<String, MutableSet<String>>()
            val genericOff = HashSet<String>()
            var size = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                val cosmetic = cosmeticSplit(line)
                if (cosmetic != null) {
                    val (domainList, exception, selector) = cosmetic
                    if (selector.isEmpty() || PROCEDURAL.any { selector.contains(it, ignoreCase = true) }) continue
                    val domains = domainList.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && !it.endsWith(".*") }
                    if (domainList.isNotEmpty() && domains.isEmpty()) continue
                    val included = domains.filter { !it.startsWith("~") }
                    val excluded = domains.filter { it.startsWith("~") }.map { it.substring(1) }
                    when {
                        exception && included.isEmpty() -> genericOff.add(selector)
                        exception -> included.forEach { unhidden.getOrPut(it) { HashSet() }.add(selector) }
                        included.isNotEmpty() -> {
                            included.forEach { specific.getOrPut(it) { ArrayList() }.add(selector) }
                            // `site.com,~m.site.com##.ad`: the excluded subdomain keeps the element.
                            excluded.forEach { unhidden.getOrPut(it) { HashSet() }.add(selector) }
                        }
                        else -> {
                            if (excluded.isNotEmpty()) genericExcept.getOrPut(selector) { HashSet() }.addAll(excluded)
                            val key = KEY.find(selector)?.value
                            if (key != null) keyed.getOrPut(key) { ArrayList() }.add(selector) else unkeyed.add(selector)
                        }
                    }
                    size++
                    continue
                }
                val exception = line.startsWith("@@")
                val rule = parseNetwork(if (exception) line.substring(2) else line) ?: continue
                val (net, host) = rule
                if (net.pageFlags != 0 && !exception) continue
                val into = if (exception) allow else block
                when {
                    host != null && net.hostOnly && net.isPlain() -> into.addHost(host)
                    host != null -> into.byHost.getOrPut(host) { ArrayList() }.add(net)
                    else -> {
                        val token = if (net.expression == null) bestToken(net.pattern) else null
                        if (token != null) into.byToken.getOrPut(token) { ArrayList() }.add(net) else into.rest.add(net)
                    }
                }
                size++
            }
            block.seal()
            allow.seal()
            return AdFilter(block, allow, specific, unhidden, keyed, unkeyed, genericExcept, genericOff, size)
        }

        /** A filter [write] wrote, from [offset] in [bytes]; throws when they hold anything else, or less. */
        fun read(bytes: ByteArray, offset: Int = 0): AdFilter {
            val input = Decoder(bytes, offset)
            require(input.number() == FORMAT) { "unknown format" }
            input.table = Array(input.number()) { input.text() }
            val size = input.number()
            val block = Rules.read(input)
            val allow = Rules.read(input)
            val specific = input.map(input::list)
            val unhidden = input.map(input::set)
            val keyed = input.map(input::list)
            val unkeyed = input.list()
            val genericExcept = input.map(input::set)
            val genericOff = input.set()
            return AdFilter(block, allow, specific, unhidden, keyed, unkeyed, genericExcept, genericOff, size)
        }

        private fun NetRule.isPlain() = types == ALL_TYPES && thirdParty == null && domains.isEmpty() && notDomains.isEmpty() && !important && pageFlags == 0

        /** `domains##selector` or `domains#@#selector` split up; null for request rules and extended syntax. */
        private fun cosmeticSplit(line: String): Triple<String, Boolean, String>? {
            val at = line.indexOf('#')
            if (at < 0) return null
            val hide = line.indexOf("##")
            val unhide = line.indexOf("#@#")
            val (index, exception, length) = when {
                unhide >= 0 && (hide < 0 || unhide < hide) -> Triple(unhide, true, 3)
                hide >= 0 -> Triple(hide, false, 2)
                // `#?#`, `#$#`, `#%#` and similar are extended syntax; they and anything else with `#` in the
                // domain part are not request rules either, unless the `#` sits after the options marker.
                EXTENDED.containsMatchIn(line) -> return Triple("", false, "")
                else -> return null
            }
            val domains = line.substring(0, index)
            // A request rule may contain `##` in its address; a domain list never has `/`, `|` or `^`.
            if (domains.any { it == '/' || it == '|' || it == '^' || it == '*' }) return null
            return Triple(domains, exception, line.substring(index + length).trim())
        }

        /** A request rule and, when it is anchored to a whole host, that host for filing. */
        private fun parseNetwork(text: String): Pair<NetRule, String?>? {
            var pattern = text
            var options = ""
            val dollar = text.lastIndexOf('$')
            val regexRule = text.startsWith("/") && text.length > 1 && (text.endsWith("/") || (dollar > 0 && text[dollar - 1] == '/'))
            if (dollar >= 0 && !(regexRule && text.endsWith("/"))) {
                options = text.substring(dollar + 1)
                pattern = text.substring(0, dollar)
            }
            var types = 0
            var notTypes = 0
            var thirdParty: Boolean? = null
            var domains = emptyList<String>()
            var notDomains = emptyList<String>()
            var important = false
            var matchCase = false
            var pageFlags = 0
            if (options.isNotEmpty()) for (option in options.split(',')) {
                val name = option.substringBefore('=').trim().lowercase(Locale.ROOT)
                val negated = name.startsWith("~")
                val bare = name.removePrefix("~")
                when {
                    bare in UNSUPPORTED -> return null
                    bare == "third-party" || bare == "3p" -> thirdParty = !negated
                    bare == "first-party" || bare == "1p" -> thirdParty = negated
                    bare == "domain" || bare == "from" -> {
                        val list = option.substringAfter('=').split('|').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && !it.endsWith(".*") }
                        domains = list.filter { !it.startsWith("~") }
                        notDomains = list.filter { it.startsWith("~") }.map { it.substring(1) }
                        if (domains.isEmpty() && notDomains.isEmpty()) return null
                    }
                    bare == "important" -> important = true
                    bare == "match-case" -> matchCase = true
                    bare in PAGE_OPTIONS -> if (negated) return null else pageFlags = pageFlags or PAGE_OPTIONS.getValue(bare)
                    bare in TYPE_NAMES -> if (negated) notTypes = notTypes or TYPE_NAMES.getValue(bare) else types = types or TYPE_NAMES.getValue(bare)
                    else -> return null
                }
            }
            val finalTypes = (if (types == 0) ALL_TYPES else types) and notTypes.inv()
            if (finalTypes == 0 && pageFlags == 0) return null
            if (pattern.isEmpty() || pattern == "*") { if (pageFlags == 0 && domains.isEmpty()) return null }
            val expression = if (regexRule && pattern.length > 2 && pattern.endsWith("/")) pattern.substring(1, pattern.length - 1) else null
            // An expression is compiled now to leave out one that does not compile; a filter read back trusts it.
            val regex = expression?.let { runCatching { expressionRegex(it, matchCase) }.getOrNull() ?: return null }
            val normalized = if (matchCase) pattern else pattern.lowercase(Locale.ROOT)
            var host: String? = null
            var hostOnly = false
            if (expression == null && normalized.startsWith("||")) {
                val end = normalized.indexOfFirst(2) { it == '^' || it == '/' || it == '*' || it == '|' || it == ':' || it == '?' }
                val name = normalized.substring(2, if (end < 0) normalized.length else end)
                val complete = end < 0 || normalized[end] == '^' || normalized[end] == '/'
                if (complete && name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
                    host = name.lowercase(Locale.ROOT).trimEnd('.')
                    val rest = if (end < 0) "" else normalized.substring(end)
                    hostOnly = rest.isEmpty() || rest == "^" || rest == "^|" || rest == "/" || rest == "^*" || rest == "/*"
                }
            }
            val rule = NetRule(normalized, matchCase, hostOnly, expression, finalTypes, thirdParty, domains, notDomains, important, pageFlags, regex)
            return rule to host
        }

        private fun expressionRegex(expression: String, matchCase: Boolean) =
            Regex(expression, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))

        private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
            for (i in from until length) if (predicate(this[i])) return i
            return -1
        }

        /**
         * The longest word a matching address must contain whole: letters and digits with a literal
         * boundary on both sides, so the address cannot continue the word where the rule has a wildcard.
         * Two letters are enough: rules such as `.com/ad/` would otherwise be tested on every request.
         */
        internal fun bestToken(pattern: String): String? {
            val anchoredStart = pattern.startsWith("|")
            val anchoredEnd = pattern.endsWith("|")
            var best: String? = null
            TOKEN.findAll(pattern.lowercase(Locale.ROOT)).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val before = if (start == 0) null else pattern[start - 1]
                val after = if (end >= pattern.length) null else pattern[end]
                val leftSafe = if (before == null) anchoredStart else before != '*'
                val rightSafe = if (after == null) anchoredEnd else after != '*'
                if (leftSafe && rightSafe && match.value.length >= 2 && match.value !in COMMON && match.value.length > (best?.length ?: 0)) best = match.value
            }
            return best
        }
        private val COMMON = setOf("http", "https", "www", "com", "net", "org", "html", "php")

        private fun tokens(url: String): Set<String> = TOKEN.findAll(url).mapTo(HashSet()) { it.value }

        /** The rule's pattern as a regular expression: `*` any text, `^` a separator, `|` and `||` anchors. */
        internal fun toRegex(pattern: String): String {
            val out = StringBuilder()
            var body = pattern
            when {
                body.startsWith("||") -> { out.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); body = body.substring(2) }
                body.startsWith("|") -> { out.append('^'); body = body.substring(1) }
            }
            val endAnchor = body.endsWith("|")
            if (endAnchor) body = body.dropLast(1)
            for (c in body) when (c) {
                '*' -> out.append(".*")
                '^' -> out.append("(?:[^\\w\\-.%]|$)")
                else -> if (c.isLetterOrDigit()) out.append(c) else out.append('\\').append(c)
            }
            if (endAnchor) out.append('$')
            return out.toString()
        }

        /** Kinds of request [url] may be, from its extension and Accept header. */
        fun requestTypes(url: String, accept: String?): Int {
            val path = url.substringBefore('#').substringBefore('?')
            val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            when (extension) {
                "js", "mjs" -> return SCRIPT
                "css" -> return STYLESHEET
                "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp" -> return IMAGE
                "woff", "woff2", "ttf", "otf", "eot" -> return FONT
                "mp4", "webm", "m3u8", "mp3", "m4a", "ogg", "ts", "mpd", "flv", "aac" -> return MEDIA
                "swf" -> return OBJECT
            }
            val type = accept?.lowercase(Locale.ROOT).orEmpty()
            return when {
                type.startsWith("image/") -> IMAGE
                type.startsWith("text/css") -> STYLESHEET
                type.startsWith("text/html") -> SUBDOCUMENT
                else -> UNKNOWN
            }
        }

        internal fun hostOf(url: String): String? {
            val scheme = url.indexOf("://")
            if (scheme <= 0) return null
            val start = scheme + 3
            var end = url.length
            for (i in start until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') { end = i; break }
            }
            val authority = url.substring(start, end).substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            return host.lowercase(Locale.ROOT).trimEnd('.').takeIf { it.isNotEmpty() }
        }

        /** [host] and each domain above it, most specific first. */
        private fun parents(host: String): List<String> {
            val out = ArrayList<String>(4)
            var rest = host
            while (true) {
                out.add(rest)
                val dot = rest.indexOf('.')
                if (dot < 0 || dot == rest.length - 1) break
                rest = rest.substring(dot + 1)
                if (!rest.contains('.')) { out.add(rest); break }
            }
            return out
        }

        private fun onDomain(host: String, domain: String) = host == domain || host.endsWith(".$domain")

        /**
         * The registrable part of [host], close enough to tell first parties from third: the last two
         * labels, or three under a country's second level such as `com.cn` or `co.uk`.
         */
        internal fun baseDomain(host: String): String {
            val labels = host.split('.')
            if (labels.size <= 2) return host
            val second = labels[labels.size - 2]
            val country = labels.last().length == 2
            val keep = if (country && second in SECOND_LEVEL) 3 else 2
            return labels.takeLast(keep).joinToString(".")
        }
        private val SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go", "gob", "mil")

        /** Classes and ids on the page, as `.name` / `#name`, for picking generic hiding rules. */
        const val PAGE_KEYS = "(() => { try { const s = new Set(); for (const e of document.querySelectorAll('[id],[class]')) {" +
            " if (e.id) s.add('#' + e.id); if (e.classList) for (const c of e.classList) s.add('.' + c); if (s.size > 4000) break; }" +
            " return JSON.stringify(Array.from(s)); } catch (_) { return '[]'; } })()"

        /**
         * Puts [css] into the page's own hiding style sheet, replacing what an earlier pass put there. Each pass
         * mostly finds the same rules, and an unchanged sheet is left alone so the page is not restyled for nothing.
         */
        fun injectScript(css: String): String = "(() => { try { const id = '__xvi_hide'; let s = document.getElementById(id);" +
            " if (!s) { s = document.createElement('style'); s.id = id; (document.head || document.documentElement).appendChild(s); }" +
            " const css = ${org.json.JSONObject.quote(css)}; if (s.textContent !== css) s.textContent = css; } catch (_) {} })()"
    }
}
