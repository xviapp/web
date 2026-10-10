package app.xvi.web

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.database.sqlite.transaction
import androidx.core.graphics.scale
import androidx.core.net.toUri
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

data class PageEntry(val url: String, val title: String)

/** Rows taken out by a removal, kept so the removal can be undone. */
class RemovedRows internal constructor(internal val table: String, internal val rows: List<ContentValues>, internal val icons: List<ContentValues> = emptyList()) {
    val isEmpty get() = rows.isEmpty()
}

enum class DownloadState {
    RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;
    val retryable get() = this == FAILED || this == CANCELLED || this == INTERRUPTED
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: FAILED }
}

data class DownloadEntry(
    val id: Long, val title: String, val uri: String, val state: DownloadState, val source: String = "",
    val referer: String = "", val received: Long = 0, val total: Long = -1,
    /** ETag or Last-Modified of the response, so an interrupted download can continue where it stopped. */
    val validator: String = ""
)

/** A remembered answer to a website's request; [origin] is scheme, host and port. */
data class SitePermission(val origin: String, val kind: String, val allowed: Boolean)

/** All browser data stays in this app's private database; proxy data is separate MMKV. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 6) {
    private val exportTitle = context.getString(R.string.bookmarks_export_title, context.getString(R.string.app_name))
    private val exportHeading = context.getString(R.string.bookmarks_title)
    private val writer = Executors.newSingleThreadExecutor()
    private val visits = AtomicInteger()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL,visits INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,uri TEXT NOT NULL,state TEXT NOT NULL," +
            "source TEXT NOT NULL DEFAULT '',referer TEXT NOT NULL DEFAULT '',received INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1)")
        createV3(db)
        createV5(db)
        createV6(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN referer TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN received INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN total INTEGER NOT NULL DEFAULT -1")
            // Version 2 stored display text as state; map it to stable codes.
            db.execSQL("UPDATE downloads SET state=CASE state WHEN '下载中' THEN 'INTERRUPTED' WHEN '已完成' THEN 'COMPLETED' " +
                "WHEN '已取消' THEN 'CANCELLED' WHEN '已中断，请重新下载' THEN 'INTERRUPTED' ELSE 'FAILED' END")
            createV3(db)
        }
        if (oldVersion < 4 && !hasColumn(db, "history", "visits")) {
            db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            // Bookmarks used to be listed newest first; keep that order as the starting arrangement.
            val urls = db.rawQuery("SELECT url FROM bookmarks ORDER BY updated DESC", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            urls.forEachIndexed { index, url -> db.execSQL("UPDATE bookmarks SET position=? WHERE url=?", arrayOf<Any>(index, url)) }
            createV5(db)
        }
        if (oldVersion < 6) createV6(db)
    }
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String) =
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == column } }
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS icons(site TEXT PRIMARY KEY,png BLOB NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_updated ON history(updated)")
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_updated ON bookmarks(updated)")
    }
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_position ON bookmarks(position)")
    }
    private fun createV6(db: SQLiteDatabase) {
        if (!hasColumn(db, "downloads", "validator")) db.execSQL("ALTER TABLE downloads ADD COLUMN validator TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS permissions(origin TEXT NOT NULL,kind TEXT NOT NULL,allowed INTEGER NOT NULL,PRIMARY KEY(origin,kind))")
    }

    /** Coroutine context for store work; one thread, so writes and the reads after them stay ordered. */
    val io = writer.asCoroutineDispatcher()

    /** Runs [block] on the store's single writer thread, in submission order. */
    fun write(block: BrowserStore.() -> Unit) { writer.execute { block() } }

    /** New bookmarks go first; one that already exists keeps its place. */
    fun bookmark(title: String, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO bookmarks(url,title,updated,position) VALUES(?,?,?," +
                "COALESCE((SELECT position FROM bookmarks WHERE url=?),(SELECT IFNULL(MIN(position),0)-1 FROM bookmarks)))",
            arrayOf<Any>(url, title.ifBlank { url }, System.currentTimeMillis(), url)
        )
    }
    /** Renames or moves a bookmark in place; false when [url] is not a web address. */
    fun updateBookmark(oldUrl: String, title: String, url: String): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        writableDatabase.transaction {
            if (url != oldUrl) delete("bookmarks", "url=?", arrayOf(url))
            val values = ContentValues().apply { put("url", url); put("title", title.ifBlank { url }); put("updated", System.currentTimeMillis()) }
            if (update("bookmarks", values, "url=?", arrayOf(oldUrl)) == 0) bookmark(title, url)
        }
        return true
    }
    /**
     * Arranges [urls] in this order across the places they already hold, so bookmarks left out
     * (beyond a partial list, or outside a search) stay where they are.
     */
    fun reorderBookmarks(urls: List<String>) {
        writableDatabase.transaction {
            val held = urls.distinct().chunked(500).flatMap { chunk ->
                rawQuery("SELECT url,position FROM bookmarks WHERE url IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
                }
            }.toMap()
            val present = urls.distinct().filter { it in held }
            present.zip(present.map { held.getValue(it) }.sorted()).forEach { (url, position) ->
                execSQL("UPDATE bookmarks SET position=? WHERE url=?", arrayOf<Any>(position, url))
            }
        }
    }
    /** Recorded off the main thread; trimming runs occasionally rather than on every page. */
    fun visit(title: String, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        writer.execute {
            writableDatabase.transaction {
                val now = System.currentTimeMillis()
                val name = title.ifBlank { url }
                execSQL("UPDATE history SET title=?,updated=?,visits=visits+1 WHERE url=?", arrayOf<Any>(name, now, url))
                if (DatabaseUtils.longForQuery(this, "SELECT changes()", null) == 0L) {
                    insert("history", null, ContentValues().apply { put("url", url); put("title", name); put("updated", now); put("visits", 1) })
                }
            }
            if (visits.incrementAndGet() % 100 == 1)
                writableDatabase.execSQL("DELETE FROM history WHERE url IN (SELECT url FROM history ORDER BY updated DESC LIMIT -1 OFFSET $HISTORY_LIMIT)")
        }
    }
    /** [limit] below zero returns every match. */
    fun entries(bookmarks: Boolean, query: String = "", limit: Int = -1): List<PageEntry> {
        val table = if (bookmarks) "bookmarks" else "history"
        val pattern = "%${escapeLike(query.trim())}%"
        val filter = if (query.isBlank()) "" else "WHERE title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\'"
        val args = if (query.isBlank()) arrayOf(limit.toString()) else arrayOf(pattern, pattern, limit.toString())
        val order = if (bookmarks) "position,updated DESC" else "updated DESC"
        return readableDatabase.rawQuery("SELECT url,title FROM $table $filter ORDER BY $order LIMIT ?", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PageEntry(cursor.getString(0), cursor.getString(1))) }
        }
    }
    /** Bookmarks and history matching [query], best first; history is skipped for private browsing. */
    internal fun suggestions(query: String, includeHistory: Boolean, limit: Int): Pair<List<SuggestionCandidate>, String?> {
        val pattern = "%${escapeLike(query.trim())}%"
        fun read(table: String, bookmarked: Boolean) = readableDatabase.rawQuery(
            "SELECT url,title,${if (bookmarked) "0" else "visits"},updated FROM $table WHERE title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' ORDER BY updated DESC LIMIT 300",
            arrayOf(pattern, pattern)
        ).use { c -> buildList { while (c.moveToNext()) add(SuggestionCandidate(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), bookmarked)) } }
        val marks = read("bookmarks", true)
        val marked = marks.mapTo(hashSetOf()) { it.url }
        val history = if (includeHistory) read("history", false).map { if (it.url in marked) it.copy(bookmarked = true) else it } else emptyList()
        val all = history + marks.filter { mark -> history.none { it.url == mark.url } }
        val now = System.currentTimeMillis()
        return SuggestionRanking.rank(all, query, now, limit) to SuggestionRanking.completion(all, query, now)
    }
    fun count(bookmarks: Boolean): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM ${if (bookmarks) "bookmarks" else "history"}", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }
    fun bookmarkedAmong(urls: Collection<String>): Set<String> = urls.chunked(500).flatMap { chunk ->
        readableDatabase.rawQuery("SELECT url FROM bookmarks WHERE url IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }.toSet()

    /** Most recent distinct sites; stops reading history as soon as [limit] sites are found. */
    fun recentSites(limit: Int = 5): List<PageEntry> {
        if (limit <= 0) return emptyList()
        val seen = hashSetOf<String>()
        val result = mutableListOf<PageEntry>()
        readableDatabase.rawQuery("SELECT url,title FROM history ORDER BY updated DESC", null).use { cursor ->
            while (result.size < limit && cursor.moveToNext()) {
                val url = cursor.getString(0)
                val title = cursor.getString(1)
                val uri = runCatching { url.toUri() }.getOrNull() ?: continue
                val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: continue
                val site = siteKey(url) ?: continue
                if (!seen.add(site)) continue
                val home = Uri.Builder().scheme(scheme).authority(site).path("/").build().toString()
                result.add(PageEntry(home, title.trim().takeIf { it.isNotEmpty() && it != url } ?: site))
            }
        }
        return result
    }
    private fun rows(table: String, where: String, args: Array<String>): List<ContentValues> =
        readableDatabase.rawQuery("SELECT * FROM $table WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(c, it) }) }
        }
    fun remove(bookmarks: Boolean, url: String): RemovedRows {
        val table = if (bookmarks) "bookmarks" else "history"
        return writableDatabase.transaction {
            val removed = rows(table, "url=?", arrayOf(url))
            delete(table, "url=?", arrayOf(url))
            RemovedRows(table, removed)
        }
    }
    fun removeSiteHistory(url: String): RemovedRows {
        val site = siteKey(url) ?: return RemovedRows("history", emptyList())
        val host = site.substringBefore(':')
        return writableDatabase.transaction {
            val removed = rows("history", "url LIKE ? ESCAPE '\\'", arrayOf("%${escapeLike(host)}%")).filter { siteKey(it.getAsString("url")) == site }
            removed.forEach { delete("history", "url=?", arrayOf(it.getAsString("url"))) }
            val icons = rows("icons", "site=?", arrayOf(site))
            delete("icons", "site=?", arrayOf(site))
            RemovedRows("history", removed, icons)
        }
    }
    /** Puts rows taken out by a removal back unchanged. */
    fun restore(removed: RemovedRows) {
        writableDatabase.transaction {
            removed.rows.forEach { insertWithOnConflict(removed.table, null, it, SQLiteDatabase.CONFLICT_REPLACE) }
            removed.icons.forEach { insertWithOnConflict("icons", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }
    /** Icons reveal visited sites, so only those still bookmarked survive. */
    fun clearHistory() {
        writableDatabase.transaction {
            delete("history", null, null)
            val kept = entries(true).mapNotNull { siteKey(it.url) }.toSet()
            rawQuery("SELECT site FROM icons", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                .filter { it !in kept }.forEach { delete("icons", "site=?", arrayOf(it)) }
        }
    }

    fun saveIcon(url: String, icon: Bitmap) {
        val site = siteKey(url) ?: return
        val scaled = if (icon.width > ICON_SIZE || icon.height > ICON_SIZE) icon.scale(ICON_SIZE, ICON_SIZE) else icon
        writer.execute {
            val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            writableDatabase.insertWithOnConflict("icons", null, ContentValues().apply {
                put("site", site); put("png", png); put("updated", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.execSQL("DELETE FROM icons WHERE site IN (SELECT site FROM icons ORDER BY updated DESC LIMIT -1 OFFSET $ICON_LIMIT)")
        }
    }
    /** Keyed by [siteKey]. */
    fun icons(urls: Collection<String>): Map<String, Bitmap> {
        val sites = urls.mapNotNull(::siteKey).distinct()
        if (sites.isEmpty()) return emptyMap()
        return sites.chunked(500).flatMap { chunk ->
            readableDatabase.rawQuery("SELECT site,png FROM icons WHERE site IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val png = cursor.getBlob(1)
                        BitmapFactory.decodeByteArray(png, 0, png.size)?.let { add(cursor.getString(0) to it) }
                    }
                }
            }
        }.toMap()
    }

    fun exportBookmarks(): String = buildString {
        val title = org.jsoup.nodes.Element("title").text(exportTitle).outerHtml()
        val heading = org.jsoup.nodes.Element("h1").text(exportHeading).outerHtml()
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1><META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">$title$heading<DL><p>\n")
        entries(true).forEach {
            val link = org.jsoup.nodes.Element("a").attr("href", it.url).text(it.title)
            append("<DT>").append(link.outerHtml()).append('\n')
        }
        append("</DL><p>")
    }
    fun importBookmarks(input: InputStream): Int {
        // Bound input size before parsing an untrusted document.
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_IMPORT_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_IMPORT_BYTES) { "Bookmark file exceeds 5 MB" }
        val links = Jsoup.parse(String(bytes, Charsets.UTF_8)).select("a[href]")
        var count = 0
        writableDatabase.transaction {
            // Each new bookmark goes first, so adding from the end keeps the file's order.
            links.reversed().forEach { link ->
                val url = link.attr("href")
                if (BrowserInput.isWebUrl(url)) { bookmark(link.text(), url); count++ }
            }
        }
        return count
    }

    fun addDownload(title: String, uri: String, source: String = "", referer: String = ""): Long = writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
        put("title", title); put("uri", uri); put("state", DownloadState.RUNNING.name); put("source", source); put("referer", referer)
    })
    /** A resumed download keeps its progress; otherwise it starts over. */
    fun restartDownload(id: Long, uri: String, resume: Boolean = false) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("uri", uri); put("state", DownloadState.RUNNING.name)
            if (!resume) { put("received", 0); put("total", -1); put("validator", "") }
        }, "id=?", arrayOf(id.toString()))
    }
    fun downloadValidator(id: Long, validator: String) {
        writableDatabase.update("downloads", ContentValues().apply { put("validator", validator) }, "id=?", arrayOf(id.toString()))
    }
    fun downloadProgress(id: Long, received: Long, total: Long) {
        writableDatabase.update("downloads", ContentValues().apply { put("received", received); put("total", total) }, "id=?", arrayOf(id.toString()))
    }
    fun downloadState(id: Long, state: DownloadState) { writableDatabase.update("downloads", ContentValues().apply { put("state", state.name) }, "id=?", arrayOf(id.toString())) }
    fun interruptDownloads(state: DownloadState = DownloadState.INTERRUPTED) {
        writableDatabase.update("downloads", ContentValues().apply { put("state", state.name) }, "state=?", arrayOf(DownloadState.RUNNING.name))
    }
    fun downloads(limit: Int = 200): List<DownloadEntry> = readableDatabase.rawQuery(
        "SELECT id,title,uri,state,source,referer,received,total,validator FROM downloads ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c ->
        buildList { while (c.moveToNext()) add(DownloadEntry(c.getLong(0), c.getString(1), c.getString(2), DownloadState.parse(c.getString(3)), c.getString(4), c.getString(5), c.getLong(6), c.getLong(7), c.getString(8))) }
    }
    fun removeDownload(id: Long): RemovedRows = writableDatabase.transaction {
        val removed = rows("downloads", "id=?", arrayOf(id.toString()))
        delete("downloads", "id=?", arrayOf(id.toString()))
        RemovedRows("downloads", removed)
    }
    fun clearCompletedDownloads() { writableDatabase.delete("downloads", "state=?", arrayOf(DownloadState.COMPLETED.name)) }

    fun permission(origin: String, kind: String): Boolean? =
        readableDatabase.rawQuery("SELECT allowed FROM permissions WHERE origin=? AND kind=?", arrayOf(origin, kind)).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }
    fun setPermission(origin: String, kind: String, allowed: Boolean) {
        writableDatabase.insertWithOnConflict("permissions", null, ContentValues().apply {
            put("origin", origin); put("kind", kind); put("allowed", if (allowed) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun permissions(origin: String): List<SitePermission> =
        readableDatabase.rawQuery("SELECT kind,allowed FROM permissions WHERE origin=? ORDER BY kind", arrayOf(origin)).use { c ->
            buildList { while (c.moveToNext()) add(SitePermission(origin, c.getString(0), c.getInt(1) != 0)) }
        }
    fun allowedOrigins(kind: String): List<String> =
        readableDatabase.rawQuery("SELECT origin FROM permissions WHERE kind=? AND allowed=1", arrayOf(kind)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    fun clearPermission(origin: String, kind: String) { writableDatabase.delete("permissions", "origin=? AND kind=?", arrayOf(origin, kind)) }
    fun clearPermissions() { writableDatabase.delete("permissions", null, null) }

    companion object {
        private const val HISTORY_LIMIT = 5000
        private const val ICON_LIMIT = 500
        private const val ICON_SIZE = 64
        private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        @Volatile private var instance: BrowserStore? = null

        /** One connection pool per process, shared by the activity and services. */
        fun get(context: Context): BrowserStore = instance ?: synchronized(this) {
            instance ?: BrowserStore(context.applicationContext).also { instance = it }
        }

        /** Escapes LIKE wildcards for use with `ESCAPE '\'`. */
        internal fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** `scheme://host[:port]` for web URLs, the unit websites are granted permissions by; null otherwise. */
        internal fun origin(url: String): String? {
            val uri = runCatching { url.toUri() }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }?.let { ":$it" }.orEmpty()
            return "$scheme://$host$port"
        }

        /** Host (without `www.`) plus explicit port; null for non-web URLs. */
        internal fun siteKey(url: String): String? {
            val uri = runCatching { url.toUri() }.getOrNull() ?: return null
            uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
            return host + port
        }
    }
}
