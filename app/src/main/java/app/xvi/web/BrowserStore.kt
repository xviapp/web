package app.xvi.web

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PageEntry(val url: String, val title: String)

enum class DownloadState {
    RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;
    val retryable get() = this == FAILED || this == CANCELLED || this == INTERRUPTED
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: FAILED }
}

data class DownloadEntry(
    val id: Long, val title: String, val uri: String, val state: DownloadState, val source: String = "",
    val referer: String = "", val received: Long = 0, val total: Long = -1
)

/** All browser data stays in this app's private database; proxy data is separate MMKV. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 3) {
    private val appName = context.getString(R.string.app_name)
    private val writer = Executors.newSingleThreadExecutor()
    private val visits = AtomicInteger()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,uri TEXT NOT NULL,state TEXT NOT NULL," +
            "source TEXT NOT NULL DEFAULT '',referer TEXT NOT NULL DEFAULT '',received INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1)")
        createV3(db)
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
    }
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS icons(site TEXT PRIMARY KEY,png BLOB NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_updated ON history(updated)")
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_updated ON bookmarks(updated)")
    }

    private fun remember(table: String, title: String, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
            put("url", url); put("title", title.ifBlank { url }); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bookmark(title: String, url: String) = remember("bookmarks", title, url)
    /** Recorded off the main thread; trimming runs occasionally rather than on every page. */
    fun visit(title: String, url: String) {
        writer.execute {
            remember("history", title, url)
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
        return readableDatabase.rawQuery("SELECT url,title FROM $table $filter ORDER BY updated DESC LIMIT ?", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PageEntry(cursor.getString(0), cursor.getString(1))) }
        }
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
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: continue
                val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: continue
                val site = siteKey(url) ?: continue
                if (!seen.add(site)) continue
                val home = Uri.Builder().scheme(scheme).authority(site).path("/").build().toString()
                result.add(PageEntry(home, title.trim().takeIf { it.isNotEmpty() && it != url } ?: site))
            }
        }
        return result
    }
    fun remove(bookmarks: Boolean, url: String) { writableDatabase.delete(if (bookmarks) "bookmarks" else "history", "url=?", arrayOf(url)) }
    fun removeSiteHistory(url: String) {
        val site = siteKey(url) ?: return
        val host = site.substringBefore(':')
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT url FROM history WHERE url LIKE ? ESCAPE '\\'", arrayOf("%${escapeLike(host)}%")).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }.filter { siteKey(it) == site }.forEach { db.delete("history", "url=?", arrayOf(it)) }
            db.delete("icons", "site=?", arrayOf(site))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Icons reveal visited sites, so only those still bookmarked survive. */
    fun clearHistory() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("history", null, null)
            val kept = entries(true).mapNotNull { siteKey(it.url) }.toSet()
            db.rawQuery("SELECT site FROM icons", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                .filter { it !in kept }.forEach { db.delete("icons", "site=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun saveIcon(url: String, icon: Bitmap) {
        val site = siteKey(url) ?: return
        val scaled = if (icon.width > ICON_SIZE || icon.height > ICON_SIZE) Bitmap.createScaledBitmap(icon, ICON_SIZE, ICON_SIZE, true) else icon
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
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1><META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\"><TITLE>${appName}书签</TITLE><H1>书签</H1><DL><p>\n")
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
        writableDatabase.beginTransaction()
        try {
            links.forEach { link ->
                val url = link.attr("href")
                if (BrowserInput.isWebUrl(url)) { bookmark(link.text(), url); count++ }
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return count
    }

    fun addDownload(title: String, uri: String, source: String = "", referer: String = ""): Long = writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
        put("title", title); put("uri", uri); put("state", DownloadState.RUNNING.name); put("source", source); put("referer", referer)
    })
    fun restartDownload(id: Long, uri: String) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("uri", uri); put("state", DownloadState.RUNNING.name); put("received", 0); put("total", -1)
        }, "id=?", arrayOf(id.toString()))
    }
    fun downloadProgress(id: Long, received: Long, total: Long) {
        writableDatabase.update("downloads", ContentValues().apply { put("received", received); put("total", total) }, "id=?", arrayOf(id.toString()))
    }
    fun downloadState(id: Long, state: DownloadState) { writableDatabase.update("downloads", ContentValues().apply { put("state", state.name) }, "id=?", arrayOf(id.toString())) }
    fun interruptDownloads(state: DownloadState = DownloadState.INTERRUPTED) {
        writableDatabase.update("downloads", ContentValues().apply { put("state", state.name) }, "state=?", arrayOf(DownloadState.RUNNING.name))
    }
    fun downloads(limit: Int = 200): List<DownloadEntry> = readableDatabase.rawQuery(
        "SELECT id,title,uri,state,source,referer,received,total FROM downloads ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c ->
        buildList { while (c.moveToNext()) add(DownloadEntry(c.getLong(0), c.getString(1), c.getString(2), DownloadState.parse(c.getString(3)), c.getString(4), c.getString(5), c.getLong(6), c.getLong(7))) }
    }
    fun removeDownload(id: Long) { writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }
    fun clearCompletedDownloads() { writableDatabase.delete("downloads", "state=?", arrayOf(DownloadState.COMPLETED.name)) }

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

        /** Host (without `www.`) plus explicit port; null for non-web URLs. */
        internal fun siteKey(url: String): String? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
            return host + port
        }
    }
}
