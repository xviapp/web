package app.xvi.web

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * One file holding what a person would miss on a new phone: bookmarks, history, open tabs, site
 * settings and permissions, site icons, user scripts with what they stored, and the browser's
 * settings, including the proxy address. The AI key is a secret and only included when asked for.
 * Downloads, private browsing and state tied to this device stay out.
 *
 * A backup file comes from outside the app, so reading one checks every entry and keeps only what
 * this browser could have written itself. Plain Kotlin and org.json, so it is covered by JVM tests.
 */
internal object BrowserBackup {
    const val FORMAT = "xvi-browser-backup"
    const val VERSION = 1
    /** Largest file read back; a full backup of the most the browser keeps is far smaller. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * [folder] names the folders holding the bookmark, outermost first, empty outside any. [position]
     * orders bookmarks and [Folder]s together; only how positions compare matters.
     */
    data class Bookmark(val url: String, val title: String, val updated: Long, val folder: List<String> = emptyList(), val position: Long = 0)
    /** [path] names the folder and those holding it, outermost first. */
    data class Folder(val path: List<String>, val position: Long = 0)
    data class Visit(val url: String, val title: String, val updated: Long, val visits: Int)
    data class Permission(val origin: String, val kind: String, val allowed: Boolean)
    data class Ai(val enabled: Boolean, val model: String, val base: String = DeepSeek.BASE)
    /** The AI key; null in a backup made without secrets. */
    data class Secrets(val aiKey: String)
    /** An installed user script with the values it stored. */
    data class Script(val script: UserScript, val values: Map<String, String> = emptyMap())

    /** What the database holds for a backup. */
    data class Rows(
        val bookmarks: List<Bookmark>,
        val history: List<Visit>,
        val siteSettings: Map<String, Map<String, Int>>,
        val permissions: List<Permission>,
        val icons: Map<String, ByteArray>,
        /** Every folder, so empty ones and the arrangement survive too. */
        val folders: List<Folder> = emptyList()
    )

    class Backup(
        val created: Long,
        val appVersion: String,
        val settings: JSONObject,
        val ai: Ai?,
        val tabs: List<SavedTab>,
        val rows: Rows,
        val secrets: Secrets?,
        val scripts: List<Script> = emptyList()
    )

    fun encode(backup: Backup): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("created", backup.created)
        put("app", backup.appVersion)
        put("settings", backup.settings)
        backup.ai?.let { put("ai", JSONObject().put("enabled", it.enabled).put("model", it.model).put("base", it.base)) }
        put("tabs", JSONArray().apply { backup.tabs.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("desktop", it.desktop)) } })
        val rows = backup.rows
        put("bookmarkFolders", JSONArray().apply { rows.folders.forEach { put(JSONObject().put("path", JSONArray(it.path)).put("position", it.position)) } })
        put("bookmarks", JSONArray().apply {
            rows.bookmarks.forEach {
                put(JSONObject().put("url", it.url).put("title", it.title).put("updated", it.updated).put("position", it.position)
                    .apply { if (it.folder.isNotEmpty()) put("folder", JSONArray(it.folder)) })
            }
        })
        put("history", JSONArray().apply {
            rows.history.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("updated", it.updated).put("visits", it.visits)) }
        })
        put("siteSettings", JSONObject().apply { rows.siteSettings.forEach { (site, values) -> put(site, JSONObject(values)) } })
        put("permissions", JSONArray().apply {
            rows.permissions.forEach { put(JSONObject().put("origin", it.origin).put("kind", it.kind).put("allowed", it.allowed)) }
        })
        put("icons", JSONObject().apply { rows.icons.forEach { (site, png) -> put(site, Base64.getEncoder().encodeToString(png)) } })
        put("userScripts", JSONArray().apply { backup.scripts.forEach { put(it.script.toJson().put("values", JSONObject(it.values))) } })
        backup.secrets?.let { s ->
            put("secrets", JSONObject().apply {
                put("aiKey", s.aiKey)
            })
        }
    }.toString()

    /** Reads a backup, dropping anything malformed; throws [IllegalArgumentException] when it is not one. */
    fun decode(text: String): Backup {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalArgumentException("not JSON")
        require(root.optString("format") == FORMAT) { "not a backup" }
        require(root.optInt("version", 0) in 1..VERSION) { "unsupported version" }
        val settings = root.optJSONObject("settings") ?: JSONObject()
        // Older backups have no address: they were made for DeepSeek.
        val ai = root.optJSONObject("ai")?.let {
            Ai(it.optBoolean("enabled", true), it.optString("model").trim().take(100), DeepSeek.normalizeBase(it.optString("base")) ?: DeepSeek.BASE)
        }
        val tabs = root.objects("tabs").mapNotNull { o ->
            val url = o.webUrl() ?: return@mapNotNull null
            SavedTab(url, o.text("title"), o.optBoolean("desktop"))
        }.distinctBy { it.url }.take(TabManager.MAX_TABS)
        // Older backups list bookmarks in order without positions, and have no folders.
        val bookmarks = root.objects("bookmarks").mapIndexedNotNull { index, o ->
            Bookmark(o.webUrl() ?: return@mapIndexedNotNull null, o.text("title"), o.optLong("updated").coerceAtLeast(0), o.folderPath("folder"),
                o.optLong("position", index.toLong()))
        }.distinctBy { it.url }.take(MAX_BOOKMARKS)
        val folders = root.objects("bookmarkFolders").take(MAX_FOLDERS).mapIndexedNotNull { index, o ->
            // Single-level folders were written by name.
            val path = if (o.has("path")) o.folderPath("path") else o.folderPath("name")
            Folder(path.ifEmpty { return@mapIndexedNotNull null }, o.optLong("position", index.toLong()))
        }.distinctBy { it.path }
        val history = root.objects("history").mapNotNull { o ->
            Visit(o.webUrl() ?: return@mapNotNull null, o.text("title"), o.optLong("updated").coerceAtLeast(0), o.optInt("visits", 1).coerceIn(1, 1_000_000))
        }.distinctBy { it.url }.sortedByDescending { it.updated }.take(MAX_HISTORY)
        val siteSettings = root.optJSONObject("siteSettings")?.let { all ->
            all.keys().asSequence().filter(::isSiteKey).take(MAX_SITES).mapNotNull { site ->
                val values = all.optJSONObject(site) ?: return@mapNotNull null
                val ints = values.keys().asSequence().filter { it in SITE_SETTING_NAMES }.mapNotNull { name -> (values.opt(name) as? Int)?.let { name to it } }.toMap()
                if (ints.isEmpty()) null else site to ints
            }.toMap()
        }.orEmpty()
        val permissions = root.objects("permissions").mapNotNull { o ->
            val origin = o.optString("origin").takeIf(::isOrigin) ?: return@mapNotNull null
            val kind = o.optString("kind").takeIf { it in PERMISSION_KINDS } ?: return@mapNotNull null
            Permission(origin, kind, o.optBoolean("allowed"))
        }.distinctBy { it.origin to it.kind }.take(MAX_PERMISSIONS)
        val icons = root.optJSONObject("icons")?.let { all ->
            all.keys().asSequence().filter(::isSiteKey).take(MAX_ICONS).mapNotNull { site ->
                val png = runCatching { Base64.getDecoder().decode(all.optString(site)) }.getOrNull()
                    ?.takeIf { it.size in 8..MAX_ICON_BYTES && it.isPng() } ?: return@mapNotNull null
                site to png
            }.toMap()
        }.orEmpty()
        // Older backups also hold the embedded proxy's subscription address, which is no proxy address.
        val secrets = root.optJSONObject("secrets")?.optString("aiKey")
            ?.takeIf { it.isNotBlank() && it.length <= 512 }?.let(::Secrets)
        // Scripts are read the way the app reads its own files: one that does not parse is left out.
        val scripts = root.objects("userScripts").take(MAX_SCRIPTS).mapNotNull { o ->
            UserScript.fromJson(o)?.let { Script(it, UserScript.valuesFromJson(o.optJSONObject("values"))) }
        }.distinctBy { it.script.id }
        return Backup(root.optLong("created"), root.optString("app").take(40), settings, ai, tabs,
            Rows(bookmarks, history, siteSettings, permissions, icons, folders), secrets, scripts)
    }

    private fun JSONObject.objects(name: String): List<JSONObject> =
        optJSONArray(name)?.let { array -> (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { array.optJSONObject(it) } }.orEmpty()

    private fun JSONObject.webUrl() = optString("url").takeIf { it.length <= MAX_URL && BrowserInput.isWebUrl(it) }
    private fun JSONObject.text(name: String) = optString(name).take(MAX_TITLE)
    /** A path of folder names, or a single name as single-level folders were written. */
    private fun JSONObject.folderPath(name: String): List<String> {
        val names = optJSONArray(name)?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: listOf(optString(name))
        return names.map { it.trim().take(BrowserStore.MAX_FOLDER_NAME) }.filter { it.isNotEmpty() }.take(BrowserStore.MAX_FOLDER_DEPTH)
    }

    /** A site as the browser files settings and icons: host without `www.`, perhaps with a port. */
    private fun isSiteKey(value: String) = value.length in 1..260 && SITE_KEY.matches(value)
    private fun isOrigin(value: String) = value.length <= 300 && ORIGIN.matches(value)
    private fun ByteArray.isPng() = this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte()

    private val SITE_KEY = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?|\\[[0-9a-f:.]+](:[0-9]{1,5})?")
    private val ORIGIN = Regex("https?://([a-z0-9]([a-z0-9.-]*[a-z0-9])?|\\[[0-9a-f:.]+])(:[0-9]{1,5})?")
    private val SITE_SETTING_NAMES = setOf("desktop", "textZoom", "javascript", "images", "adblock")
    private val PERMISSION_KINDS = setOf(SitePermissions.CAMERA, SitePermissions.MICROPHONE, SitePermissions.LOCATION, SitePermissions.POPUP)
    private const val MAX_ENTRIES = 100_000
    private const val MAX_BOOKMARKS = 20_000
    private const val MAX_FOLDERS = 1_000
    private const val MAX_HISTORY = 5_000
    private const val MAX_SITES = 10_000
    private const val MAX_PERMISSIONS = 10_000
    private const val MAX_ICONS = 500
    private const val MAX_SCRIPTS = 200
    private const val MAX_ICON_BYTES = 256 * 1024
    private const val MAX_URL = 8192
    private const val MAX_TITLE = 500
}
