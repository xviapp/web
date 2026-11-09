package app.xvi.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

/**
 * The filter lists in use and the [AdFilter] built from them, shared by every window. Enabled lists are
 * downloaded on the first connection and refreshed weekly through the browser's network settings.
 * Downloaded copies keep working offline. Loading and updating run on one
 * background thread, and the filter in use is swapped only once a new one is complete. The filter last
 * built is kept in a file of its own, so a start with the same lists reads it instead of parsing them.
 */
internal object AdBlock {
    /** A known or user-added list; only enabled lists are downloaded. */
    class FilterList(val id: String, val url: String, val name: String,
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

    /** What the settings show for a list: its title, rule count and download time (0 when missing). */
    class ListInfo(val name: String, val rules: Int, val updated: Long)

    /**
     * The official addresses come first. Some, filters.adtidy.org above all, are often unreachable from
     * mainland China, so each list also names its copies on jsDelivr and GitHub, published from the
     * same repositories.
     */
    private val BUILT_IN = listOf(
        FilterList("easylist", "https://easylist.to/easylist/easylist.txt", "EasyList", builtIn = true, defaultOn = true,
            mirrors = github("easylist/easylist", "gh-pages", "easylist.txt")),
        FilterList("easylistchina", "https://easylist-downloads.adblockplus.org/easylistchina.txt", "EasyList China",
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

    /** The filter in use; null until the first build finishes. Read from any thread. */
    @Volatile var filter: AdFilter? = null
        private set
    @Volatile var info: Map<String, ListInfo> = emptyMap()
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var requested = false
    private var automaticUpdating = false
    private var automaticPending = false
    private val downloading = HashSet<String>()
    private val failedDownloads = HashSet<String>()
    private val listeners = LinkedHashSet<() -> Unit>()

    val isUpdating get() = downloading.isNotEmpty()
    fun isDownloading(id: String) = id in downloading
    fun downloadFailed(id: String) = id in failedDownloads
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun announce() = listeners.toList().forEach { it() }

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

    /** Whether [list] has been downloaded. Partial downloads are never used. */
    fun available(context: Context, list: FilterList) = file(context.applicationContext, list.id).isFile

    /** Builds the filter once per process; later calls do nothing. */
    fun ensureLoaded(context: Context) {
        if (requested) return
        requested = true
        reload(context)
        val app = context.applicationContext
        // The callback belongs to the process, not a window. A first offline launch must not leave
        // blocking empty until the browser is restarted; capability changes also cover captive portals.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) updateIfDue(app)
                    }
                }, main)
        }
        updateIfDue(app)
    }

    /** Checks on connection, resume and enabling; concurrent automatic checks share one update. */
    fun updateIfDue(context: Context) {
        val app = context.applicationContext
        if (!BrowserPrefs(app).adblock || !online(app)) return
        if (automaticUpdating) { automaticPending = true; return }
        automaticUpdating = true
        val network = BrowserProxyNetwork.get(app)
        network.prepare {
            if (!BrowserPrefs(app).adblock || !online(app)) { automaticFinished(app); return@prepare }
            update(app, network.proxy(), force = false) { _, _ -> automaticFinished(app) }
        }
    }

    private fun automaticFinished(app: Context) {
        automaticUpdating = false
        if (automaticPending) { automaticPending = false; updateIfDue(app) }
    }

    private fun online(app: Context): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Rebuilds the filter from the current lists and custom rules; [done] runs on the main thread. */
    fun reload(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        requested = true
        worker.execute {
            rebuild(app)
            main.post { announce(); done?.invoke() }
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
            val now = System.currentTimeMillis()
            fun report(step: Progress) { progress?.let { main.post { it(step) } } }
            val due = enabled.filter { list ->
                val file = file(app, list.id)
                when {
                    only != null -> list.id in only
                    force -> true
                    else -> !file.isFile || now - file.lastModified() >= UPDATE_INTERVAL
                }
            }
            if (due.isEmpty()) { done?.let { main.post { it(0, 0) } }; return@execute }
            val ids = due.map { it.id }
            main.post { downloading.addAll(ids); failedDownloads.removeAll(ids.toSet()); announce() }
            report(Progress.Waiting(due.map { it.id }))
            // An unreachable source should give way to the next one quickly.
            val client = BrowserHttp.through(proxy).newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
            var failed = 0
            val failures = HashSet<String>()
            var updated = 0
            due.forEach { list ->
                val file = file(app, list.id)
                val text = (listOf(list.url) + list.mirrors).withIndex().firstNotNullOfOrNull { (index, url) ->
                    report(Progress.Downloading(list.id, index, 0, -1))
                    FilterListDownload.download(client, url, ::looksLikeList) { received, total -> report(Progress.Downloading(list.id, index, received, total)) }
                }
                if (text != null && FilterListDownload.save(file, text)) {
                    updated++
                    report(Progress.Downloaded(list.id))
                } else {
                    failed++
                    failures += list.id
                    report(Progress.Failed(list.id))
                }
            }
            if (updated > 0 || filter == null) { report(Progress.Applying); rebuild(app) }
            main.post {
                downloading.removeAll(ids.toSet())
                failedDownloads.addAll(failures)
                announce()
                done?.invoke(updated, failed)
            }
        }
    }

    /** Forgets a removed subscription's download. */
    fun forget(context: Context, id: String) {
        val app = context.applicationContext
        worker.execute { file(app, id).delete() }
    }

    private fun rebuild(app: Context) {
        val prefs = BrowserPrefs(app)
        val lists = lists(prefs)
        val enabled = lists.filter { isEnabled(prefs, it) }.mapTo(HashSet()) { it.id }
        val custom = prefs.adblockCustomRules
        val key = cacheKey(app, lists, enabled, custom)
        Cache.read(cacheFile(app), key)?.let { (infos, cached) -> info = infos; filter = cached; return }
        val infos = LinkedHashMap<String, ListInfo>()
        val texts = ArrayList<String>()
        lists.forEach { list ->
            val downloaded = file(app, list.id).takeIf { it.isFile }
            val text = runCatching { downloaded?.readText() }.getOrNull()
            val name = text?.let(::title) ?: list.name
            val rules = text?.let(::countRules) ?: 0
            infos[list.id] = ListInfo(name, rules, downloaded?.lastModified() ?: 0L)
            if (text != null && list.id in enabled) texts.add(text)
        }
        infos[CUSTOM] = ListInfo("", countRules(custom), 0L)
        texts.add(custom)
        val built = runCatching { AdFilter.build(texts.asSequence().flatMap { it.lineSequence() }) }.getOrNull() ?: return
        info = infos
        filter = built
        Cache.write(cacheFile(app), key, infos, built)
    }

    /**
     * What the filter and list details are made from: this installation, each downloaded file and
     * switch, and the custom rules. The install timestamp also invalidates caches of older parsers.
     */
    private fun cacheKey(app: Context, lists: List<FilterList>, enabled: Set<String>, custom: String): String {
        val installed = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val inputs = buildString {
            append(installed)
            lists.forEach { list ->
                val file = file(app, list.id)
                append('\n').append(list.id).append(' ').append(list.url).append(' ').append(list.id in enabled)
                if (file.isFile) append(' ').append(file.length()).append(' ').append(file.lastModified())
            }
            append('\n').append(custom)
        }
        return MessageDigest.getInstance("SHA-256").digest(inputs.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * The file the filter last built is kept in: what it was made from, the lists' details, the filter, and a
     * checksum of the filter, so a file damaged in storage means parsing the lists again rather than misreading
     * them. Plain Kotlin, so it is covered by JVM tests.
     */
    internal object Cache {
        /** The lists' details and the filter kept for [key]; null when none is, or it does not read back whole. */
        fun read(file: File, key: String): Pair<Map<String, ListInfo>, AdFilter>? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != key) return null
                val infos = LinkedHashMap<String, ListInfo>()
                repeat(input.readInt()) { infos[input.readUTF()] = ListInfo(input.readUTF(), input.readInt(), input.readLong()) }
                val rest = input.readBytes()
                val length = rest.size - Long.SIZE_BYTES
                if (length < 0) return null
                val stored = (length until rest.size).fold(0L) { value, i -> (value shl 8) or (rest[i].toLong() and 0xff) }
                if (CRC32().apply { update(rest, 0, length) }.value != stored) return null
                infos to AdFilter.read(rest)
            }
        }.getOrNull()

        /** Keeps what was built from [key]'s inputs for the next start; failing only means parsing again then. */
        fun write(file: File, key: String, infos: Map<String, ListInfo>, filter: AdFilter) {
            val partial = File(file.path + ".part")
            runCatching {
                file.parentFile?.mkdirs()
                DataOutputStream(partial.outputStream().buffered()).use { out ->
                    out.writeUTF(key)
                    out.writeInt(infos.size)
                    infos.forEach { (id, list) -> out.writeUTF(id); out.writeUTF(list.name); out.writeInt(list.rules); out.writeLong(list.updated) }
                    val checked = CheckedOutputStream(out, CRC32())
                    filter.write(checked)
                    out.writeLong(checked.checksum.value)
                }
                if (!partial.renameTo(file)) partial.delete()
            }.onFailure { partial.delete() }
        }
    }

    private fun file(app: Context, id: String) = File(File(app.filesDir, "adblock"), "$id.txt")
    /** Derived from the lists alone, so the system may clear it like any cache; the lists are parsed again then. */
    private fun cacheFile(app: Context) = File(File(app.cacheDir, "adblock"), "filter.bin")

    /** A filter list starts with its `[Adblock …]` header or with comments; anything else is a wrong page. */
    private fun looksLikeList(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        return first.startsWith("[Adblock", ignoreCase = true) || first.startsWith("!")
    }

    private fun title(text: String) = text.lineSequence().take(40)
        .firstOrNull { it.startsWith("! Title:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }?.take(200)

    private fun countRules(text: String) = text.lineSequence().count { line ->
        val trimmed = line.trim()
        trimmed.isNotEmpty() && !trimmed.startsWith("!") && !trimmed.startsWith("[")
    }
}
