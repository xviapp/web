package app.xvi.web

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * The `// ==UserScript==` block at the top of a user script, and the rules that follow from it: which
 * addresses it runs on, which origins WebView needs to be given its code, and whether a newer copy is
 * an update. Plain Kotlin so the rules are covered by JVM tests; the page side applies the same
 * patterns, compiled here, so the two never disagree.
 */
internal data class UserScriptMeta(
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val excludeMatches: List<String> = emptyList(),
    val runAt: RunAt = RunAt.IDLE,
    val noFrames: Boolean = false,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    /** `@resource` name to address, fetched with the script like its requires. */
    val resources: Map<String, String> = emptyMap(),
    /** `@connect` hosts the script asks to reach with GM_xmlhttpRequest besides the page's own. */
    val connects: List<String> = emptyList(),
    val updateUrl: String? = null,
    val downloadUrl: String? = null,
    /** The block itself, for `GM_info.scriptMetaStr`. */
    val block: String = "",
) {
    enum class RunAt(val key: String) {
        START("document-start"), END("document-end"), IDLE("document-idle");
        companion object { fun parse(value: String?) = entries.firstOrNull { it.key == value?.trim()?.lowercase(Locale.ROOT) } ?: IDLE }
    }

    /** One compiled address rule: a regular expression both Java and JavaScript read the same way. */
    data class Pattern(val source: String, val ignoreCase: Boolean)

    /** Same name and namespace is the same script, so installing it again replaces it. */
    val id: String get() = MessageDigest.getInstance("SHA-256").digest("$namespace\n$name".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    /** Grants this browser does not provide; the script installs, but whatever needs them fails. */
    val unsupportedGrants: List<String> get() = grants.filter { it != "none" && it !in SUPPORTED_GRANTS }

    /**
     * Whether a request to [host] needs no question: the page's own host, a `@connect` host or one of
     * its subdomains, any host for `@connect *`, or a host the user already allowed for the script.
     */
    fun mayConnect(host: String, pageHost: String?, granted: Set<String> = emptySet()): Boolean {
        val target = host.lowercase(Locale.ROOT).trimEnd('.')
        if (target.isEmpty()) return false
        if (target == pageHost?.lowercase(Locale.ROOT)) return true
        return (connects + granted).any { entry ->
            val bare = entry.substringAfter("://").substringBefore('/').substringBefore(':').lowercase(Locale.ROOT).trimEnd('.')
            when (bare) {
                "*" -> true
                "self" -> target == pageHost?.lowercase(Locale.ROOT)
                "" -> false
                else -> target == bare || target.endsWith(".$bare")
            }
        }
    }

    /** Whether the script names any address at all; one that names none runs nowhere. */
    val hasTargets: Boolean get() = matches.isNotEmpty() || includes.isNotEmpty()

    val runPatterns: List<Pattern> get() = matches.mapNotNull(::matchPattern) + includes.mapNotNull(::globPattern)
    val skipPatterns: List<Pattern> get() = excludeMatches.mapNotNull(::matchPattern) + excludes.mapNotNull(::globPattern)

    /** Whether the script runs on [url]; the fragment never counts. */
    fun runsOn(url: String): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        val address = url.substringBefore('#')
        fun List<Pattern>.any() = any { pattern -> runCatching { pattern.regex().containsMatchIn(address) }.getOrDefault(false) }
        return runPatterns.any() && !skipPatterns.any()
    }

    /**
     * Origins WebView needs to hand the script to: each `@match` scheme and host as an origin rule,
     * or every origin as soon as one address cannot be narrowed down that way. A rule without a port
     * only covers the default one while a match pattern covers any, so local addresses, where other
     * ports are common, go to every origin too and the page's own check decides.
     */
    fun originRules(): Set<String> {
        if (includes.isNotEmpty()) return ALL_ORIGINS
        val rules = LinkedHashSet<String>()
        matches.forEach { match ->
            if (match == "<all_urls>") return ALL_ORIGINS
            val parts = MATCH.matchEntire(match) ?: return@forEach
            val (scheme, host) = parts.destructured
            val schemes = when (scheme) { "*" -> listOf("http", "https"); "http", "https" -> listOf(scheme); else -> return@forEach }
            val bare = host.substringBefore(':').lowercase(Locale.ROOT)
            val port = host.substringAfter(':', "").takeIf { it.isNotEmpty() }
            if (bare.isEmpty()) return@forEach
            if (bare.removePrefix("*.").contains('*') || port == "*" || port == null && LocalAddress.isLocal(bare.removePrefix("*."))) return ALL_ORIGINS
            // "*.example.com" leaves out example.com itself, which a match pattern includes.
            val hosts = if (bare.startsWith("*.")) listOf(bare.removePrefix("*."), bare) else listOf(bare)
            schemes.forEach { s -> hosts.forEach { h -> rules.add("$s://$h" + (port?.let { ":$it" } ?: "")) } }
        }
        return rules
    }

    companion object {
        val ALL_ORIGINS = setOf("*")
        /**
         * What a script receives, in the order of its wrapper's parameters; the page runtime hands them
         * over by these names.
         */
        val API = listOf(
            "GM_info", "GM", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues", "GM_addStyle", "GM_log", "unsafeWindow",
            "GM_addValueChangeListener", "GM_removeValueChangeListener", "GM_xmlhttpRequest", "GM_registerMenuCommand", "GM_unregisterMenuCommand",
            "GM_openInTab", "GM_setClipboard", "GM_notification", "GM_download", "GM_getResourceText", "GM_getResourceURL",
        )
        val SUPPORTED_GRANTS = API.filter { it != "GM" }.toSet() + setOf(
            "GM.info", "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues", "GM.addStyle", "GM.addValueChangeListener",
            "GM.removeValueChangeListener", "GM.xmlHttpRequest", "GM.registerMenuCommand", "GM.unregisterMenuCommand", "GM.openInTab",
            "GM.setClipboard", "GM.notification", "GM.download", "GM.getResourceUrl", "GM.getResourceText", "window.close", "window.focus",
        )
        private const val START = "// ==UserScript=="
        private const val END = "// ==/UserScript=="
        private val MATCH = Regex("""^(\*|https?|file|ftp)://([^/]*)(/.*)$""")
        private val LINE = Regex("""^//\s*@([\w:.-]+)(?:\s+(.*))?$""")

        /** The script's metadata, or null when [code] has no block or the block names no script. */
        fun parse(code: String, locale: Locale = Locale.getDefault()): UserScriptMeta? {
            val text = code.removePrefix("\uFEFF")
            val start = text.indexOf(START).takeIf { it >= 0 } ?: return null
            val end = text.indexOf(END, start).takeIf { it >= 0 } ?: return null
            val block = text.substring(start, end + END.length)
            val values = LinkedHashMap<String, MutableList<String>>()
            block.lineSequence().drop(1).forEach { raw ->
                val found = LINE.matchEntire(raw.trim()) ?: return@forEach
                values.getOrPut(found.groupValues[1].lowercase(Locale.ROOT)) { mutableListOf() }.add(found.groupValues[2].trim())
            }
            fun all(key: String) = values[key].orEmpty().filter { it.isNotEmpty() }
            fun one(key: String) = all(key).firstOrNull()
            // A name in the reader's language when the script has one: zh-CN, then zh, then the plain name.
            fun localized(key: String): String? {
                val tags = listOf(locale.toLanguageTag(), "${locale.language}-${locale.country}", locale.language).map { it.lowercase(Locale.ROOT) }.distinct()
                return tags.firstNotNullOfOrNull { one("$key:$it") } ?: one(key)
            }
            val name = localized("name")?.take(MAX_NAME) ?: return null
            return UserScriptMeta(
                name = name,
                namespace = one("namespace").orEmpty().take(MAX_NAME),
                version = one("version").orEmpty().take(64),
                description = localized("description").orEmpty().take(500),
                author = one("author").orEmpty().take(MAX_NAME),
                matches = all("match").take(MAX_RULES),
                includes = all("include").take(MAX_RULES),
                excludes = all("exclude").take(MAX_RULES),
                excludeMatches = all("exclude-match").take(MAX_RULES),
                runAt = RunAt.parse(one("run-at")),
                noFrames = values.containsKey("noframes"),
                grants = all("grant").distinct(),
                requires = all("require").filter(BrowserInput::isWebUrl).distinct().take(MAX_RULES),
                resources = all("resource").mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    parts.getOrNull(1)?.trim()?.takeIf(BrowserInput::isWebUrl)?.let { parts[0] to it }
                }.take(MAX_RULES).toMap(),
                connects = all("connect").map { it.lowercase(Locale.ROOT) }.distinct().take(MAX_RULES),
                updateUrl = one("updateurl")?.takeIf(BrowserInput::isWebUrl),
                downloadUrl = one("downloadurl")?.takeIf(BrowserInput::isWebUrl),
                block = block,
            )
        }

        /** Whether [url] names a user script to install rather than a page to show. */
        fun isScriptUrl(url: String): Boolean {
            if (!BrowserInput.isWebUrl(url)) return false
            val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
            return path.lowercase(Locale.ROOT).endsWith(".user.js")
        }

        /**
         * A `@match` pattern: `scheme://host/path`, where a `*` scheme is http or https, a `*.` host
         * also covers the bare domain, and `*` in the path is anything. The path covers the query too.
         */
        fun matchPattern(match: String): Pattern? {
            if (match == "<all_urls>") return Pattern("^https?://", false)
            val parts = MATCH.matchEntire(match.trim()) ?: return null
            val (scheme, host, path) = parts.destructured
            val schemeRule = when (scheme) { "*" -> "https?"; "http", "https" -> scheme; else -> return null }
            val bare = host.substringBefore(':')
            val port = host.substringAfter(':', "")
            val hostRule = when {
                bare == "*" -> "[^/:]+"
                bare.startsWith("*.") -> "(?:[^/:]+\\.)?" + escape(bare.removePrefix("*."))
                bare.contains('*') -> return null
                bare.isEmpty() -> return null
                else -> escape(bare)
            }
            val portRule = when (port) { "" -> "(?::\\d+)?"; "*" -> "(?::\\d+)?"; else -> ":" + escape(port) }
            return Pattern("^$schemeRule://$hostRule$portRule" + glob(path) + "$", true)
        }

        /**
         * An `@include` or `@exclude`: a glob where `*` is anything, matched against the whole address,
         * or a regular expression between slashes. `.tld` stands for any top-level domain.
         */
        fun globPattern(include: String): Pattern? {
            val text = include.trim()
            if (text.isEmpty()) return null
            if (text.length > 2 && text.startsWith("/") && text.endsWith("/")) {
                val source = text.substring(1, text.length - 1)
                return Pattern(source, true).takeIf { runCatching { Regex(source) }.isSuccess }
            }
            if (text == "*") return Pattern("^https?://", false)
            val parts = text.split(".tld")
            return Pattern("^" + parts.joinToString("\\.[a-z.]{2,}", transform = ::glob) + "$", true)
        }

        /** `*` is anything; everything else stands for itself. */
        private fun glob(text: String) = text.split("*").joinToString(".*", transform = ::escape)

        /** Escapes a literal the same way for Java and JavaScript, which `Regex.escape`'s `\Q…\E` would not. */
        private fun escape(text: String) = buildString { text.forEach { if (it in SPECIAL) append('\\'); append(it) } }
        private const val SPECIAL = "\\^$.|?*+()[]{}/"

        /**
         * Whether [candidate] is newer than [installed]: dotted parts compared one by one, numbers as
         * numbers, so 1.10 follows 1.9, and a missing part counts as zero.
         */
        fun isNewer(candidate: String, installed: String): Boolean = compareVersions(candidate, installed) > 0

        fun compareVersions(a: String, b: String): Int {
            val left = a.trim().split('.', '-', '_')
            val right = b.trim().split('.', '-', '_')
            for (i in 0 until maxOf(left.size, right.size)) {
                val x = left.getOrElse(i) { "0" }
                val y = right.getOrElse(i) { "0" }
                val nx = x.takeWhile(Char::isDigit)
                val ny = y.takeWhile(Char::isDigit)
                val numbers = (nx.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO).compareTo(ny.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO)
                if (numbers != 0) return numbers
                val rest = x.removePrefix(nx).compareTo(y.removePrefix(ny))
                if (rest != 0) return rest.coerceIn(-1, 1)
            }
            return 0
        }

        private const val MAX_NAME = 200
        private const val MAX_RULES = 200
    }
}

internal fun UserScriptMeta.Pattern.regex() = if (ignoreCase) Regex(source, RegexOption.IGNORE_CASE) else Regex(source)

/**
 * An installed script: its code, the `@require` files it was installed with (fetched once, so the
 * script never loads code from elsewhere while it runs), and the user's choices. Values the script
 * stores are kept apart, since they change far more often.
 */
internal data class UserScript(
    val code: String,
    val meta: UserScriptMeta,
    /** `@require` address to its contents, in the order the script lists them. */
    val requires: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** Runs in private tabs too; off by default. */
    val allowPrivate: Boolean = false,
    /** Where the script was installed from, for updates when it names no address of its own. */
    val source: String? = null,
    /** `@resource` name to what was fetched for it. */
    val resources: Map<String, Resource> = emptyMap(),
    /** Hosts the user allowed the script to reach although its `@connect` does not name them. */
    val allowedHosts: Set<String> = emptySet(),
    val installed: Long = 0,
    val updated: Long = 0,
    /** When an update was last looked for. */
    val checked: Long = 0,
) {
    /** A fetched `@resource`: its media type and contents in base64, as pages take it. */
    data class Resource(val mime: String, val data: String)

    val id: String get() = meta.id
    /** Where to look for a newer version: the script's own update address, else where it came from. */
    val updateCheckUrl: String? get() = meta.updateUrl ?: meta.downloadUrl ?: source

    fun toJson(): JSONObject = JSONObject().put("code", code).put("requires", JSONObject(requires)).put("enabled", enabled)
        .put("allowPrivate", allowPrivate).put("source", source ?: JSONObject.NULL).put("installed", installed).put("updated", updated).put("checked", checked)
        .put("resources", JSONObject().apply { resources.forEach { (name, r) -> put(name, JSONObject().put("mime", r.mime).put("data", r.data)) } })
        .put("allowedHosts", org.json.JSONArray(allowedHosts.toList()))

    companion object {
        const val MAX_CODE = 5 * 1024 * 1024
        const val MAX_REQUIRE = 3 * 1024 * 1024
        const val MAX_RESOURCE = 3 * 1024 * 1024
        /** Everything one script stores, counted in characters of its JSON values. */
        const val MAX_VALUES = 2 * 1024 * 1024
        const val MAX_KEY = 1024

        /** A script as [toJson] wrote it, or null when it is not one; read from disk and from backups. */
        fun fromJson(json: JSONObject): UserScript? {
            val code = json.optString("code").takeIf { it.length <= MAX_CODE } ?: return null
            val meta = UserScriptMeta.parse(code) ?: return null
            val stored = json.optJSONObject("requires")
            val requires = meta.requires.mapNotNull { url -> stored?.optString(url, null)?.takeIf { it.length <= MAX_REQUIRE }?.let { url to it } }.toMap()
            val storedResources = json.optJSONObject("resources")
            val resources = meta.resources.keys.mapNotNull { name ->
                val r = storedResources?.optJSONObject(name) ?: return@mapNotNull null
                val data = r.optString("data").takeIf { it.length <= MAX_RESOURCE * 4 / 3 + 4 } ?: return@mapNotNull null
                name to Resource(r.optString("mime").ifBlank { "application/octet-stream" }.take(100), data)
            }.toMap()
            val hosts = json.optJSONArray("allowedHosts")?.let { a -> (0 until minOf(a.length(), 200)).mapNotNull { a.optString(it).takeIf { h -> h.isNotBlank() && h.length <= 253 } } }
            return UserScript(code, meta, requires, enabled = json.optBoolean("enabled", true), allowPrivate = json.optBoolean("allowPrivate"),
                source = json.optString("source").takeIf(BrowserInput::isWebUrl), resources = resources, allowedHosts = hosts.orEmpty().toSet(),
                installed = json.optLong("installed"), updated = json.optLong("updated"), checked = json.optLong("checked"))
        }

        /** Stored values as written to disk and backups; anything that is not a short key and JSON text is dropped. */
        fun valuesFromJson(json: JSONObject?): Map<String, String> {
            json ?: return emptyMap()
            var total = 0
            val values = LinkedHashMap<String, String>()
            json.keys().forEach { key ->
                val value = json.opt(key) as? String ?: return@forEach
                if (key.length > MAX_KEY) return@forEach
                total += key.length + value.length
                if (total <= MAX_VALUES) values[key] = value
            }
            return values
        }
    }
}
