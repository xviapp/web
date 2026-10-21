package app.xvi.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The filter lists in use and the [AdFilter] built from them, shared by every window. Lists ship with
 * the app so blocking works from the first page, and are refreshed weekly through the browser's own
 * network settings; a downloaded copy replaces the bundled one. Loading and updating run on one
 * background thread, and the filter in use is swapped only once a new one is complete.
 */
internal object AdBlock {
    /**
     * A filter list. Built-in lists ship as [asset] when on by default; the optional ones are only
     * downloaded once switched on.
     */
    class FilterList(val id: String, val url: String, val name: String, val asset: String? = null,
                     val builtIn: Boolean = false, val defaultOn: Boolean = false,
                     /** Copies of the same file, tried in order when [url] cannot be reached. */
                     val mirrors: List<String> = emptyList())
    /** Where an update stands, reported on the main thread while it runs. */
    sealed class Progress {
        class Waiting(val ids: List<String>) : Progress()
        /** [source] is the index into the list's address and its mirrors; [total] is -1 when unknown. */
        class Downloading(val id: String, val source: Int, val received: Long, val total: Long) : Progress()
        class Downloaded(val id: String) : Progress()
        class Failed(val id: String) : Progress()
        /** Every download is over; the filter is being rebuilt from the new lists. */
        object Applying : Progress()
    }

    /** What the settings show for a list: its title, rule count and when it was downloaded (0 when bundled). */
    class ListInfo(val name: String, val rules: Int, val updated: Long)

    /**
     * The official addresses come first. Some, filters.adtidy.org above all, are often unreachable from
     * mainland China, so each list also names its copies on jsDelivr and GitHub, published from the
     * same repositories.
     */
    private val BUILT_IN = listOf(
        FilterList("easylist", "https://easylist.to/easylist/easylist.txt", "EasyList", "adblock/easylist.txt", builtIn = true, defaultOn = true,
            mirrors = github("easylist/easylist", "gh-pages", "easylist.txt")),
        FilterList("easylistchina", "https://easylist-downloads.adblockplus.org/easylistchina.txt", "EasyList China", "adblock/easylistchina.txt",
            builtIn = true, defaultOn = true, mirrors = github("easylist/easylistchina", "master", "easylistchina.txt")),
        FilterList("easyprivacy", "https://easylist.to/easylist/easyprivacy.txt", "EasyPrivacy", builtIn = true,
            mirrors = github("easylist/easylist", "gh-pages", "easyprivacy.txt")),
        FilterList("adguard-chinese", "https://filters.adtidy.org/extension/ublock/filters/224.txt", "AdGuard Chinese filter", builtIn = true,
            mirrors = github("AdguardTeam/FiltersRegistry", "master", "platforms/extension/ublock/filters/224.txt")),
        FilterList("adguard-mobile", "https://filters.adtidy.org/extension/ublock/filters/11.txt", "AdGuard Mobile Ads filter", builtIn = true,
            mirrors = github("AdguardTeam/FiltersRegistry", "master", "platforms/extension/ublock/filters/11.txt")),
    )

    /** jsDelivr first, as it is the more reachable of the two in mainland China. */
    private fun github(repo: String, branch: String, path: String) = listOf(
        "https://cdn.jsdelivr.net/gh/$repo@$branch/$path",
        "https://raw.githubusercontent.com/$repo/$branch/$path",
    )
    const val CUSTOM = "custom"
    private const val UPDATE_INTERVAL = 7L * 24 * 60 * 60 * 1000
    private const val MAX_LIST = 15L * 1024 * 1024

    /** The filter in use; null until the first build finishes. Read from any thread. */
    @Volatile var filter: AdFilter? = null
        private set
    @Volatile var info: Map<String, ListInfo> = emptyMap()
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var requested = false

    fun lists(prefs: BrowserPrefs): List<FilterList> =
        BUILT_IN + prefs.adblockSubscriptions.map { FilterList(subscriptionId(it), it, BrowserInput.display(it)) }

    fun subscriptionId(url: String) = "sub-" + url.hashCode().toUInt().toString(16)

    /** Lists on by default stay on until switched off; the others only once switched on. */
    fun isEnabled(prefs: BrowserPrefs, list: FilterList) =
        if (list.defaultOn || !list.builtIn) list.id !in prefs.adblockOff else list.id in prefs.adblockOn

    fun setEnabled(prefs: BrowserPrefs, list: FilterList, on: Boolean) {
        if (list.defaultOn || !list.builtIn) prefs.adblockOff = if (on) prefs.adblockOff - list.id else prefs.adblockOff + list.id
        else prefs.adblockOn = if (on) prefs.adblockOn + list.id else prefs.adblockOn - list.id
    }

    /** Whether [list] has rules on the device, downloaded or bundled. */
    fun available(context: Context, list: FilterList) = list.asset != null || file(context.applicationContext, list.id).isFile

    /** Builds the filter once per process; later calls do nothing. */
    fun ensureLoaded(context: Context) {
        if (requested) return
        requested = true
        reload(context)
    }

    /** Rebuilds the filter from the current lists and custom rules; [done] runs on the main thread. */
    fun reload(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        requested = true
        worker.execute {
            rebuild(app)
            done?.let { main.post(it) }
        }
    }

    /**
     * Downloads the enabled lists through [proxy], all of them when [force] is set and otherwise only
     * those older than a week, then rebuilds; [only] limits it to those lists, whatever their age.
     * [done] gets the number of lists that failed.
     */
    fun update(context: Context, proxy: Proxy, force: Boolean, only: Set<String>? = null,
               progress: ((Progress) -> Unit)? = null, done: ((updated: Int, failed: Int) -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            val prefs = BrowserPrefs(app)
            val enabled = lists(prefs).filter { isEnabled(prefs, it) }
            // The bundled lists are recent on a fresh install, so the weekly cycle starts from then; lists
            // switched on that have nothing on the device yet are fetched right away.
            val missing = enabled.filter { !available(app, it) }.mapTo(HashSet()) { it.id }
            val firstRun = !force && only == null && prefs.adblockChecked == 0L
            if (firstRun) prefs.adblockChecked = System.currentTimeMillis()
            val scheduled = force || only != null || (!firstRun && System.currentTimeMillis() - prefs.adblockChecked >= UPDATE_INTERVAL)
            if (!scheduled && missing.isEmpty()) { done?.let { main.post { it(0, 0) } }; return@execute }
            fun report(step: Progress) { progress?.let { main.post { it(step) } } }
            val due = enabled.filter { list ->
                val file = file(app, list.id)
                when {
                    only != null -> list.id in only
                    force -> true
                    list.id in missing -> true
                    !scheduled -> false
                    else -> !file.exists() || System.currentTimeMillis() - file.lastModified() >= UPDATE_INTERVAL
                }
            }
            report(Progress.Waiting(due.map { it.id }))
            // An unreachable source should give way to the next one quickly.
            val client = BrowserHttp.through(proxy).newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
            var failed = 0
            var updated = 0
            due.forEach { list ->
                val file = file(app, list.id)
                val text = (listOf(list.url) + list.mirrors).withIndex().firstNotNullOfOrNull { (index, url) ->
                    report(Progress.Downloading(list.id, index, 0, -1))
                    download(client, url) { received, total -> report(Progress.Downloading(list.id, index, received, total)) }
                }
                file.parentFile?.mkdirs()
                val partial = File(file.path + ".part")
                if (text != null && runCatching { partial.writeText(text); partial.renameTo(file) }.getOrDefault(false)) {
                    updated++
                    report(Progress.Downloaded(list.id))
                } else {
                    failed++
                    report(Progress.Failed(list.id))
                }
            }
            if (failed == 0 && only == null && scheduled) prefs.adblockChecked = System.currentTimeMillis()
            if (updated > 0 || filter == null) { report(Progress.Applying); rebuild(app) }
            done?.let { main.post { it(updated, failed) } }
        }
    }

    /**
     * The list at [url], or null when it cannot be had or [accept] finds it is not a list of its kind.
     * [received] hears how much has arrived a few times a second; the total is -1 when the server does not say.
     */
    internal fun download(client: OkHttpClient, url: String, accept: (String) -> Boolean = ::looksLikeList,
                          received: (Long, Long) -> Unit = { _, _ -> }): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val total = response.body.contentLength()
            val source = response.body.source()
            val buffer = Buffer()
            var reported = 0L
            while (source.read(buffer, 64 * 1024) != -1L) {
                if (buffer.size > MAX_LIST) return@use null
                val now = System.currentTimeMillis()
                if (now - reported >= 150) { reported = now; received(buffer.size, total) }
            }
            received(buffer.size, total)
            buffer.readUtf8()
        }
    }.getOrNull()?.takeIf(accept)

    /** Forgets a removed subscription's download. */
    fun forget(context: Context, id: String) {
        val app = context.applicationContext
        worker.execute { file(app, id).delete() }
    }

    private fun rebuild(app: Context) {
        val prefs = BrowserPrefs(app)
        val infos = LinkedHashMap<String, ListInfo>()
        val texts = ArrayList<String>()
        lists(prefs).forEach { list ->
            val downloaded = file(app, list.id).takeIf { it.isFile }
            val text = runCatching { downloaded?.readText() ?: list.asset?.let { app.assets.open(it).bufferedReader().use { r -> r.readText() } } }.getOrNull()
            val name = text?.let(::title) ?: list.name
            val rules = text?.let(::countRules) ?: 0
            infos[list.id] = ListInfo(name, rules, downloaded?.lastModified() ?: 0L)
            if (text != null && isEnabled(prefs, list)) texts.add(text)
        }
        val custom = prefs.adblockCustomRules
        infos[CUSTOM] = ListInfo("", countRules(custom), 0L)
        texts.add(custom)
        val built = runCatching { AdFilter.build(texts.asSequence().flatMap { it.lineSequence() }) }.getOrNull() ?: return
        info = infos
        filter = built
    }

    private fun file(app: Context, id: String) = File(File(app.filesDir, "adblock"), "$id.txt")

    /** A filter list starts with its `[Adblock …]` header or with comments; anything else is a wrong page. */
    private fun looksLikeList(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        return first.startsWith("[Adblock", ignoreCase = true) || first.startsWith("!")
    }

    private fun title(text: String) = text.lineSequence().take(40)
        .firstOrNull { it.startsWith("! Title:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    private fun countRules(text: String) = text.lineSequence().count { line ->
        val trimmed = line.trim()
        trimmed.isNotEmpty() && !trimmed.startsWith("!") && !trimmed.startsWith("[")
    }
}
