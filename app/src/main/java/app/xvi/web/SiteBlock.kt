package app.xvi.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Parental control's site blocking, shared by every window: the [SiteFilter] built from the lists of
 * the categories switched on and the user's own additions and exceptions. The lists are not shipped
 * with the app; they are downloaded once a category is switched on and refreshed weekly, through the
 * browser's own network settings. Loading and updating run on one background thread, and the filter
 * in use is swapped only once a new one is complete.
 */
internal object SiteBlock {
    /**
     * A kind of site that can be blocked, with the list naming them. HaGeZi's lists in the wildcard
     * form, where each domain stands for its subdomains too; jsDelivr comes first, as it is the most
     * reachable in mainland China, then the same files on GitHub and Codeberg.
     */
    enum class Category(val key: String, val urls: List<String>) {
        ADULT("adult", hagezi("nsfw-onlydomains.txt")),
        GAMBLING("gambling", hagezi("gambling.medium-onlydomains.txt")),
        /** The user's own additions; not a list, and always applied. */
        CUSTOM("custom", emptyList());
    }

    /** The categories that come with a list. */
    val LISTS = Category.entries.filter { it.urls.isNotEmpty() }

    private fun hagezi(file: String) = listOf(
        "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/$file",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/$file",
        "https://codeberg.org/hagezi/mirror2/raw/branch/main/dns-blocklists/wildcard/$file",
    )
    private const val UPDATE_INTERVAL = 7L * 24 * 60 * 60 * 1000

    /** The filter in use; empty until the first build finishes. Read from any thread. */
    @Volatile var filter: SiteFilter = SiteFilter.EMPTY
        private set
    /** When each category's list on the device was downloaded; missing while it has none. */
    @Volatile var updated: Map<Category, Long> = emptyMap()
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var requested = false

    /** The category the site at [url] is blocked under, or null when it is not. */
    fun category(url: String): Category? {
        val key = filter.category(url) ?: return null
        return Category.entries.firstOrNull { it.key == key }
    }

    fun enabled(prefs: BrowserPrefs): List<Category> = LISTS.filter { it.key in prefs.siteBlockOn }

    fun setEnabled(prefs: BrowserPrefs, category: Category, on: Boolean) {
        prefs.siteBlockOn = if (on) prefs.siteBlockOn + category.key else prefs.siteBlockOn - category.key
    }

    /** Whether [category]'s list is on the device. */
    fun available(context: Context, category: Category) = file(context.applicationContext, category).isFile

    /** Builds the filter once per process; later calls do nothing. */
    fun ensureLoaded(context: Context) {
        if (requested) return
        requested = true
        reload(context)
    }

    /** Rebuilds the filter from the current choices; [done] runs on the main thread. */
    fun reload(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        requested = true
        worker.execute {
            rebuild(app)
            done?.let { main.post(it) }
        }
    }

    /**
     * Downloads the lists of the categories switched on through [proxy]: [only] those, whatever their
     * age, or otherwise those missing or older than a week, all of them when [force] is set. [done]
     * gets the categories that failed.
     */
    fun update(context: Context, proxy: Proxy, force: Boolean = false, only: Set<Category>? = null, done: ((failed: Set<Category>) -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            val now = System.currentTimeMillis()
            val due = enabled(BrowserPrefs(app)).filter { category ->
                val file = file(app, category)
                if (only != null) category in only else force || !file.isFile || now - file.lastModified() >= UPDATE_INTERVAL
            }
            val failed = HashSet<Category>()
            if (due.isNotEmpty()) {
                // An unreachable source should give way to the next one quickly.
                val client = BrowserHttp.through(proxy).newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
                due.forEach { category ->
                    val file = file(app, category)
                    val text = category.urls.firstNotNullOfOrNull { AdBlock.download(client, it, ::looksLikeList) }
                    file.parentFile?.mkdirs()
                    val partial = File(file.path + ".part")
                    if (text == null || !runCatching { partial.writeText(text); partial.renameTo(file) }.getOrDefault(false)) failed.add(category)
                }
                if (failed.size < due.size) rebuild(app)
            }
            done?.let { main.post { it(failed) } }
        }
    }

    private fun rebuild(app: Context) {
        val prefs = BrowserPrefs(app)
        val times = HashMap<Category, Long>()
        val lists = LISTS.mapNotNull { category ->
            val file = file(app, category).takeIf { it.isFile } ?: return@mapNotNull null
            times[category] = file.lastModified()
            if (category.key !in prefs.siteBlockOn) return@mapNotNull null
            runCatching { category.key to file.useLines(block = SiteFilter::hashes) }.getOrNull()
        }
        val custom = Category.CUSTOM.key to SiteFilter.hashes(ownSites(prefs.siteBlockCustom))
        filter = SiteFilter.build(lists + custom, SiteFilter.hashes(ownSites(prefs.siteBlockAllowed)))
        updated = times
    }

    /** Sites the user typed, one per line; `www.` is taken to mean the whole site. */
    fun ownSites(text: String): Sequence<String> =
        text.lineSequence().mapNotNull(SiteFilter::domain).map { it.removePrefix("www.") }

    private fun file(app: Context, category: Category) = File(File(app.filesDir, "siteblock"), "${category.key}.txt")

    /** A downloaded list names sites from its first lines on; a web page in its place does not. */
    private fun looksLikeList(text: String): Boolean {
        if (text.trimStart().startsWith("<")) return false
        return text.lineSequence().take(200).any { SiteFilter.domain(it) != null }
    }
}
