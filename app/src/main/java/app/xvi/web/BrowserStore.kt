package app.xvi.web

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.database.sqlite.transaction
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [time] is when the page was last visited or the bookmark last changed, in epoch milliseconds;
 * [folder] is the bookmark's folder, or [BrowserStore.ROOT_FOLDER].
 */
data class PageEntry(val url: String, val title: String, val time: Long = 0, val folder: Long = BrowserStore.ROOT_FOLDER)

/**
 * A bookmark folder inside [parent], [BrowserStore.ROOT_FOLDER] at the top level; [count] is how many
 * bookmarks and folders it holds directly.
 */
data class BookmarkFolder(val id: Long, val name: String, val count: Int = 0, val parent: Long = BrowserStore.ROOT_FOLDER)

/** Rows taken out by a removal, kept so the removal can be undone. */
class RemovedRows internal constructor(
    internal val table: String, internal val rows: List<ContentValues>, internal val icons: List<ContentValues> = emptyList(),
    internal val folders: List<ContentValues> = emptyList()
) {
    val isEmpty get() = rows.isEmpty() && folders.isEmpty()
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
    val validator: String = "",
    /** Why the last attempt failed, as [DownloadFailure.code] wrote it. */
    val error: String = ""
)

/** A remembered answer to a website's request; [origin] is scheme, host and port. */
data class SitePermission(val origin: String, val kind: String, val allowed: Boolean)

/** All browser data stays in this app's private database; proxy data is separate MMKV. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 11) {
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
        createV7(db)
        createV8(db)
        createV9(db)
        createV11(db)
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
            val urls = db.rawQuery("SELECT url FROM bookmarks ORDER BY updated DESC", null).use { c -> c.rows { getString(0) } }
            urls.forEachIndexed { index, url -> db.execSQL("UPDATE bookmarks SET position=? WHERE url=?", arrayOf<Any>(index, url)) }
            createV5(db)
        }
        if (oldVersion < 6) createV6(db)
        if (oldVersion < 7) createV7(db)
        if (oldVersion < 8) createV8(db)
        if (oldVersion < 9) createV9(db)
        if (oldVersion < 10) {
            // Folders and bookmarks now share one order; keep the folders ahead, where they were shown.
            val start = DatabaseUtils.longForQuery(db, "SELECT IFNULL(MIN(position),0) FROM bookmarks", null)
            val ids = db.rawQuery("SELECT id FROM folders ORDER BY position,id", null).use { c -> c.rows { getLong(0) } }
            ids.forEachIndexed { index, id -> db.execSQL("UPDATE folders SET position=? WHERE id=?", arrayOf<Any>(start - ids.size + index, id)) }
        }
        if (oldVersion < 11) createV11(db)
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
    private fun createV7(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS site_settings(site TEXT NOT NULL,name TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(site,name))")
    }
    private fun createV8(db: SQLiteDatabase) {
        if (!hasColumn(db, "downloads", "error")) db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT NOT NULL DEFAULT ''")
    }
    private fun createV9(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,position INTEGER NOT NULL DEFAULT 0)")
        if (!hasColumn(db, "bookmarks", "folder")) db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder INTEGER NOT NULL DEFAULT $ROOT_FOLDER")
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_folder ON bookmarks(folder)")
    }
    private fun createV11(db: SQLiteDatabase) {
        if (!hasColumn(db, "folders", "parent")) db.execSQL("ALTER TABLE folders ADD COLUMN parent INTEGER NOT NULL DEFAULT $ROOT_FOLDER")
        db.execSQL("CREATE INDEX IF NOT EXISTS folders_parent ON folders(parent)")
    }
    private fun createV6(db: SQLiteDatabase) {
        if (!hasColumn(db, "downloads", "validator")) db.execSQL("ALTER TABLE downloads ADD COLUMN validator TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS permissions(origin TEXT NOT NULL,kind TEXT NOT NULL,allowed INTEGER NOT NULL,PRIMARY KEY(origin,kind))")
    }

    /**
     * The single database thread, shared by [writeAsync], [write] and every read that callers hand to
     * `withContext`. Keeping writes and the reads that follow them on one thread is what makes the
     * ordering visible to callers. Reads are synchronous, so they must never run on the main thread.
     */
    val io = writer.asCoroutineDispatcher()

    /**
     * Queues [block] on the store's writer thread and returns at once. Use it where the caller is not a
     * coroutine and nothing depends on the result, such as a page callback recording a finished write.
     *
     * Never call this from inside a coroutine that already runs on [io]: queueing work behind yourself
     * and then waiting for it would deadlock. Use [write] there.
     */
    fun writeAsync(block: BrowserStore.() -> Unit) { writer.execute { block() } }

    /**
     * Runs [block] on the store's writer thread and waits, for coroutine callers that need the write to
     * have happened — a panel reloading the rows it just changed, for example. [block] is not suspending,
     * so it cannot queue more work behind itself.
     */
    suspend fun write(block: BrowserStore.() -> Unit) = withContext(io) { block() }

    /**
     * New bookmarks go first in [folder] (the top level by default). One that already exists keeps its
     * place, and its folder unless [folder] names another, where it then goes first.
     */
    fun bookmark(title: String, url: String, folder: Long? = null) {
        if (!BrowserInput.isWebUrl(url)) return
        writableDatabase.transaction {
            val held = rawQuery("SELECT position,folder FROM bookmarks WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
            val target = existingFolder(this, folder ?: held?.second ?: ROOT_FOLDER)
            val position = held?.takeIf { it.second == target }?.first ?: firstPosition(this)
            insertWithOnConflict("bookmarks", null, ContentValues().apply {
                put("url", url); put("title", title.ifBlank { url }); put("updated", System.currentTimeMillis()); put("position", position); put("folder", target)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    /**
     * Renames or moves a bookmark in place, and into [folder] when given, where it goes first; false
     * when [url] is not a web address.
     */
    fun updateBookmark(oldUrl: String, title: String, url: String, folder: Long? = null): Boolean {
        if (!BrowserInput.isWebUrl(url)) return false
        writableDatabase.transaction {
            if (url != oldUrl) delete("bookmarks", "url=?", arrayOf(url))
            val current = rawQuery("SELECT folder FROM bookmarks WHERE url=?", arrayOf(oldUrl)).use { if (it.moveToFirst()) it.getLong(0) else null }
            val values = ContentValues().apply { put("url", url); put("title", title.ifBlank { url }); put("updated", System.currentTimeMillis()) }
            val target = folder?.let { existingFolder(this, it) }
            if (target != null && current != null && target != current) { values.put("folder", target); values.put("position", firstPosition(this)) }
            if (update("bookmarks", values, "url=?", arrayOf(oldUrl)) == 0) bookmark(title, url, folder)
        }
        return true
    }
    /**
     * Puts [item], a bookmark ([PageEntry]) or folder ([BookmarkFolder]), first in [folder]; false when a
     * folder cannot go there, being that folder or holding it, or sharing a name with one there.
     */
    fun moveInto(item: Any, folder: Long): Boolean = when (item) {
        is PageEntry -> { moveBookmark(item.url, folder); true }
        is BookmarkFolder -> moveFolder(item.id, folder)
        else -> false
    }
    /** Puts the bookmark for [url] first in [folder]; the row as it was comes back so the move can be undone. */
    fun moveBookmark(url: String, folder: Long): RemovedRows = writableDatabase.transaction {
        val before = rows("bookmarks", "url=?", arrayOf(url))
        update("bookmarks", ContentValues().apply { put("folder", existingFolder(this@transaction, folder)); put("position", firstPosition(this@transaction)) },
            "url=?", arrayOf(url))
        RemovedRows("bookmarks", before)
    }
    /** Arranges bookmarks alone, as [arrange] does. */
    fun reorderBookmarks(urls: List<String>) = arrange(urls.map { PageEntry(it, "") })
    /**
     * Arranges [items] — bookmarks ([PageEntry]) and folders ([BookmarkFolder]), which share one order —
     * in this order across the places they already hold, so items left out (beyond a partial list,
     * outside a search or in another folder) stay where they are.
     */
    fun arrange(items: List<Any>) {
        val order = items.mapNotNull {
            when (it) {
                is PageEntry -> "bookmarks" to it.url
                is BookmarkFolder -> "folders" to it.id.toString()
                else -> null
            }
        }.distinct()
        writableDatabase.transaction {
            val held = order.groupBy({ it.first }, { it.second }).flatMap { (table, keys) ->
                val key = if (table == "folders") "id" else "url"
                keys.chunked(500).flatMap { chunk ->
                    rawQuery("SELECT $key,position FROM $table WHERE $key IN (${placeholders(chunk)})", chunk.toTypedArray()).use { c ->
                        c.rows { (table to getString(0)) to getLong(1) }
                    }
                }
            }.toMap()
            val present = order.filter { it in held }
            present.zip(present.map { held.getValue(it) }.sorted()).forEach { (item, position) ->
                val key = if (item.first == "folders") "id" else "url"
                execSQL("UPDATE ${item.first} SET position=? WHERE $key=?", arrayOf<Any>(position, item.second))
            }
        }
    }

    /**
     * What [folder] holds directly: folders ([BookmarkFolder]) and bookmarks ([PageEntry]) in their
     * shared order. [limit] below zero returns them all.
     */
    fun children(folder: Long = ROOT_FOLDER, limit: Int = -1): List<Any> = readableDatabase.rawQuery(
        "SELECT 0,id,name,'',0,position,$FOLDER_COUNT,parent FROM folders WHERE parent=? " +
            "UNION ALL SELECT 1,0,title,url,updated,position,0,folder FROM bookmarks WHERE folder=? " +
            "ORDER BY 6,1,5 DESC LIMIT ?", arrayOf(folder.toString(), folder.toString(), limit.toString())
    ).use { c ->
        c.rows {
            if (getInt(0) == 0) BookmarkFolder(getLong(1), getString(2), getInt(6), getLong(7))
            else PageEntry(getString(3), getString(2), getLong(4), getLong(7))
        }
    }
    fun childCount(folder: Long = ROOT_FOLDER): Int = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT (SELECT COUNT(*) FROM folders WHERE parent=?1)+(SELECT COUNT(*) FROM bookmarks WHERE folder=?1)", arrayOf(folder.toString())).toInt()

    /** Every folder at every level, each level in its arranged order. */
    fun folders(): List<BookmarkFolder> = readableDatabase.rawQuery(
        "SELECT id,name,$FOLDER_COUNT,parent FROM folders ORDER BY position,id", null
    ).use { c -> c.rows { BookmarkFolder(getLong(0), getString(1), getInt(2), getLong(3)) } }
    /** The folder named [name] inside [parent], made first there when there is none yet. */
    fun addFolder(name: String, parent: Long = ROOT_FOLDER): Long =
        writableDatabase.transaction { folderId(this, name, existingFolder(this, parent)) { firstPosition(this) } }
    /** False when another folder beside it already has [name]. */
    fun renameFolder(id: Long, name: String): Boolean = writableDatabase.transaction {
        val clean = name.trim().take(MAX_FOLDER_NAME)
        val parent = DatabaseUtils.longForQuery(this, "SELECT IFNULL(MAX(parent),$ROOT_FOLDER) FROM folders WHERE id=?", arrayOf(id.toString()))
        if (clean.isEmpty() || nameTaken(this, clean, parent, except = id)) false
        else { update("folders", ContentValues().apply { put("name", clean) }, "id=?", arrayOf(id.toString())); true }
    }
    /**
     * Puts the folder first inside [parent]. False when that is the folder itself or inside it, or a
     * folder there already has its name.
     */
    fun moveFolder(id: Long, parent: Long): Boolean = writableDatabase.transaction {
        val target = existingFolder(this, parent)
        val name = rawQuery("SELECT name FROM folders WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        if (name == null || target == id || target in descendants(this, id) || nameTaken(this, name, target, except = id)) false
        else {
            update("folders", ContentValues().apply { put("parent", target); put("position", firstPosition(this@transaction)) }, "id=?", arrayOf(id.toString()))
            true
        }
    }
    /** Removes the folder together with everything inside it, at every level. */
    fun removeFolder(id: Long): RemovedRows = writableDatabase.transaction {
        val ids = (listOf(id) + descendants(this, id)).map(Long::toString)
        val folders = ids.flatMap { rows("folders", "id=?", arrayOf(it)) }
        val marks = ids.flatMap { rows("bookmarks", "folder=?", arrayOf(it)) }
        ids.forEach { delete("bookmarks", "folder=?", arrayOf(it)); delete("folders", "id=?", arrayOf(it)) }
        RemovedRows("bookmarks", marks, folders = folders)
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
    /** [limit] below zero returns every match; a [folder] limits bookmarks to that one, null reads them all. */
    fun entries(bookmarks: Boolean, query: String = "", limit: Int = -1, folder: Long? = null): List<PageEntry> {
        val table = table(bookmarks)
        val filters = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val pattern = "%${escapeLike(query.trim())}%"
            filters += "(title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\')"
            args += listOf(pattern, pattern)
        }
        if (bookmarks && folder != null) { filters += "folder=?"; args += folder.toString() }
        val filter = if (filters.isEmpty()) "" else filters.joinToString(" AND ", "WHERE ")
        val order = if (bookmarks) "position,updated DESC" else "updated DESC"
        val columns = if (bookmarks) "url,title,updated,folder" else "url,title,updated,$ROOT_FOLDER"
        return readableDatabase.rawQuery("SELECT $columns FROM $table $filter ORDER BY $order LIMIT ?", (args + limit.toString()).toTypedArray()).use { cursor ->
            cursor.rows { PageEntry(getString(0), getString(1), getLong(2), getLong(3)) }
        }
    }
    /** Bookmarks and history matching [query], best first; history is skipped for private browsing. */
    internal fun suggestions(query: String, includeHistory: Boolean, limit: Int): Pair<List<SuggestionCandidate>, String?> {
        val pattern = "%${escapeLike(query.trim())}%"
        fun read(table: String, bookmarked: Boolean) = readableDatabase.rawQuery(
            "SELECT url,title,${if (bookmarked) "0" else "visits"},updated FROM $table " +
                "WHERE title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' ORDER BY updated DESC LIMIT 300",
            arrayOf(pattern, pattern)
        ).use { c -> c.rows { SuggestionCandidate(getString(0), getString(1), getInt(2), getLong(3), bookmarked) } }
        val marks = read("bookmarks", true)
        val marked = marks.mapTo(hashSetOf()) { it.url }
        val history = if (includeHistory) read("history", false).map { if (it.url in marked) it.copy(bookmarked = true) else it } else emptyList()
        val all = history + marks.filter { mark -> history.none { it.url == mark.url } }
        val now = System.currentTimeMillis()
        return SuggestionRanking.rank(all, query, now, limit) to SuggestionRanking.completion(all, query, now)
    }
    /** A [folder] counts only the bookmarks in it. */
    fun count(bookmarks: Boolean, folder: Long? = null): Int {
        val filter = if (bookmarks && folder != null) " WHERE folder=$folder" else ""
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM ${table(bookmarks)}$filter", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

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
                val scheme = uri.scheme?.lowercase()?.takeIf(BrowserInput::isWebScheme) ?: continue
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
            c.rows { ContentValues().also { DatabaseUtils.cursorRowToContentValues(this, it) } }
        }
    fun remove(bookmarks: Boolean, url: String): RemovedRows {
        val table = table(bookmarks)
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
            removed.folders.forEach { insertWithOnConflict("folders", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
            removed.rows.forEach { insertWithOnConflict(removed.table, null, it, SQLiteDatabase.CONFLICT_REPLACE) }
            removed.icons.forEach { insertWithOnConflict("icons", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }
    /**
     * Removes history visited at or after [since] (all of it by default). Icons reveal visited sites, so
     * only those still bookmarked or still in history survive.
     */
    fun clearHistory(since: Long = 0) {
        writableDatabase.transaction {
            if (since <= 0) delete("history", null, null) else delete("history", "updated>=?", arrayOf(since.toString()))
            val kept = (entries(true) + entries(false)).mapNotNull { siteKey(it.url) }.toSet()
            rawQuery("SELECT site FROM icons", null).use { it.rows { getString(0) } }
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
            readableDatabase.rawQuery("SELECT site,png FROM icons WHERE site IN (${placeholders(chunk)})", chunk.toTypedArray()).use { cursor ->
                cursor.rows { getBlob(1).let { png -> BitmapFactory.decodeByteArray(png, 0, png.size) }?.let { getString(0) to it } }
            }
        }.filterNotNull().toMap()
    }

    fun exportBookmarks(): String {
        fun items(folder: Long, depth: Int): List<BookmarkHtml.Item> = children(folder).mapNotNull {
            when (it) {
                is PageEntry -> BookmarkHtml.Item.Page(it)
                is BookmarkFolder -> BookmarkHtml.Item.Folder(it.name, if (depth < MAX_FOLDER_DEPTH) items(it.id, depth + 1) else emptyList())
                else -> null
            }
        }
        return BookmarkHtml.write(exportTitle, exportHeading, items(ROOT_FOLDER, 1))
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
        val links = BookmarkHtml.read(String(bytes, Charsets.UTF_8)).filter { BrowserInput.isWebUrl(it.url) }
        writableDatabase.transaction {
            // Each new bookmark and folder goes first, so adding from the end keeps the file's order.
            val folders = FolderPaths(this) { firstPosition(this) }
            links.reversed().forEach { link -> bookmark(link.title, link.url, folders.id(link.folders)) }
        }
        return links.size
    }

    fun addDownload(title: String, uri: String, source: String = "", referer: String = ""): Long = writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
        put("title", title); put("uri", uri); put("state", DownloadState.RUNNING.name); put("source", source); put("referer", referer)
    })
    /** A resumed download keeps its progress; otherwise it starts over. */
    fun restartDownload(id: Long, uri: String, resume: Boolean = false) = updateDownload(id) {
        put("uri", uri); put("state", DownloadState.RUNNING.name); put("error", "")
        if (!resume) { put("received", 0); put("total", -1); put("validator", "") }
    }
    fun downloadValidator(id: Long, validator: String) = updateDownload(id) { put("validator", validator) }
    fun downloadProgress(id: Long, received: Long, total: Long) = updateDownload(id) { put("received", received); put("total", total) }
    fun downloadState(id: Long, state: DownloadState, error: String = "") = updateDownload(id) { put("state", state.name); put("error", error) }
    private fun updateDownload(id: Long, values: ContentValues.() -> Unit) {
        writableDatabase.update("downloads", ContentValues().apply(values), "id=?", arrayOf(id.toString()))
    }
    fun interruptDownloads() {
        writableDatabase.update("downloads", ContentValues().apply { put("state", DownloadState.INTERRUPTED.name) }, "state=?", arrayOf(DownloadState.RUNNING.name))
    }
    fun downloads(limit: Int = 200): List<DownloadEntry> = readableDatabase.rawQuery(
        "SELECT id,title,uri,state,source,referer,received,total,validator,error FROM downloads ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c ->
        c.rows {
            DownloadEntry(getLong(0), getString(1), getString(2), DownloadState.parse(getString(3)), getString(4), getString(5),
                getLong(6), getLong(7), getString(8), getString(9))
        }
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
            c.rows { SitePermission(origin, getString(0), getInt(1) != 0) }
        }
    fun allowedOrigins(kind: String): List<String> =
        readableDatabase.rawQuery("SELECT origin FROM permissions WHERE kind=? AND allowed=1", arrayOf(kind)).use { c -> c.rows { getString(0) } }
    /** Every remembered answer, grouped by site. */
    fun allPermissions(): List<SitePermission> =
        readableDatabase.rawQuery("SELECT origin,kind,allowed FROM permissions ORDER BY origin,kind", null).use { c ->
            c.rows { SitePermission(getString(0), getString(1), getInt(2) != 0) }
        }
    fun clearPermission(origin: String, kind: String) { writableDatabase.delete("permissions", "origin=? AND kind=?", arrayOf(origin, kind)) }
    fun clearPermissions() { writableDatabase.delete("permissions", null, null) }

    /** Every site's own settings, keyed by [siteKey]. */
    fun siteSettings(): Map<String, SiteSetting> {
        val values = mutableMapOf<String, MutableMap<String, Int>>()
        readableDatabase.rawQuery("SELECT site,name,value FROM site_settings", null).use { c ->
            while (c.moveToNext()) values.getOrPut(c.getString(0)) { mutableMapOf() }[c.getString(1)] = c.getInt(2)
        }
        return values.mapValues { (_, v) -> SiteSetting.fromValues(v) }
    }
    fun saveSiteSetting(site: String, setting: SiteSetting) {
        writableDatabase.transaction {
            delete("site_settings", "site=?", arrayOf(site))
            setting.toValues().forEach { (name, value) ->
                insert("site_settings", null, ContentValues().apply { put("site", site); put("name", name); put("value", value) })
            }
        }
    }
    fun clearSiteSettings() { writableDatabase.delete("site_settings", null, null) }

    /** Everything a backup takes from the database: folders and bookmarks in order, history, site choices and icons. */
    internal fun backupRows(): BrowserBackup.Rows {
        val db = readableDatabase
        val all = folders()
        val paths = all.associate { it.id to BookmarkTree.path(all, it.id) }
        val bookmarks = db.rawQuery("SELECT url,title,updated,folder,position FROM bookmarks ORDER BY position,updated DESC", null).use { c ->
            c.rows { BrowserBackup.Bookmark(getString(0), getString(1), getLong(2), paths[getLong(3)].orEmpty(), getLong(4)) }
        }
        val folders = db.rawQuery("SELECT id,position FROM folders ORDER BY position,id", null).use { c ->
            c.rows { BrowserBackup.Folder(paths[getLong(0)].orEmpty(), getLong(1)) }
        }.filter { it.path.isNotEmpty() }
        val history = db.rawQuery("SELECT url,title,updated,visits FROM history ORDER BY updated DESC", null).use { c ->
            c.rows { BrowserBackup.Visit(getString(0), getString(1), getLong(2), getInt(3)) }
        }
        val settings = HashMap<String, MutableMap<String, Int>>()
        db.rawQuery("SELECT site,name,value FROM site_settings", null).use { c ->
            while (c.moveToNext()) settings.getOrPut(c.getString(0)) { mutableMapOf() }[c.getString(1)] = c.getInt(2)
        }
        val permissions = db.rawQuery("SELECT origin,kind,allowed FROM permissions", null).use { c ->
            c.rows { BrowserBackup.Permission(getString(0), getString(1), getInt(2) != 0) }
        }
        val icons = db.rawQuery("SELECT site,png FROM icons", null).use { c -> c.rows { getString(0) to getBlob(1) } }.toMap()
        return BrowserBackup.Rows(bookmarks, history, settings, permissions, icons, folders)
    }

    /**
     * Merges a backup's rows into the database in one transaction. Folders, bookmarks and history already
     * here stay, and the backup's own are added after them, a folder joining the one here of its name;
     * for a page in both, history keeps the later visit. A site's settings and permissions are taken from
     * the backup; icons already here are kept.
     */
    internal fun restoreRows(rows: BrowserBackup.Rows) {
        writableDatabase.transaction {
            // The backup's folders and bookmarks keep their shared order, placed after everything here.
            val start = DatabaseUtils.longForQuery(this,
                "SELECT MAX(IFNULL((SELECT MAX(position) FROM bookmarks),-1),IFNULL((SELECT MAX(position) FROM folders),-1))+1", null)
            val base = (rows.folders.map { it.position } + rows.bookmarks.map { it.position }).minOrNull() ?: 0
            val placed = rows.folders.associate { it.path to start + it.position - base }
            val folders = FolderPaths(this) { path -> placed[path] ?: start }
            // Parents first, so each folder lands in place before what it holds.
            rows.folders.sortedBy { it.path.size }.forEach { folders.id(it.path) }
            rows.bookmarks.forEach { mark ->
                val values = ContentValues().apply {
                    put("url", mark.url); put("title", mark.title.ifBlank { mark.url }); put("updated", mark.updated); put("position", start + mark.position - base)
                    put("folder", folders.id(mark.folder))
                }
                insertWithOnConflict("bookmarks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            rows.history.forEach { visit ->
                val title = visit.title.ifBlank { visit.url }
                // Update first, then insert: upserts need a newer SQLite than older supported Android versions ship.
                execSQL("UPDATE history SET title=CASE WHEN ?>updated THEN ? ELSE title END,updated=MAX(updated,?),visits=MAX(visits,?) WHERE url=?",
                    arrayOf<Any>(visit.updated, title, visit.updated, visit.visits, visit.url))
                if (DatabaseUtils.longForQuery(this, "SELECT changes()", null) == 0L) {
                    insert("history", null, ContentValues().apply { put("url", visit.url); put("title", title); put("updated", visit.updated); put("visits", visit.visits) })
                }
            }
            execSQL("DELETE FROM history WHERE url IN (SELECT url FROM history ORDER BY updated DESC LIMIT -1 OFFSET $HISTORY_LIMIT)")
            rows.siteSettings.forEach { (site, values) ->
                delete("site_settings", "site=?", arrayOf(site))
                values.forEach { (name, value) -> insert("site_settings", null, ContentValues().apply { put("site", site); put("name", name); put("value", value) }) }
            }
            rows.permissions.forEach { setPermission(it.origin, it.kind, it.allowed) }
            rows.icons.forEach { (site, png) ->
                insertWithOnConflict("icons", null, ContentValues().apply { put("site", site); put("png", png); put("updated", System.currentTimeMillis()) },
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    companion object {
        /** The folder of bookmarks not in any folder. */
        const val ROOT_FOLDER = 0L
        internal const val MAX_FOLDER_NAME = 100
        /** Deepest folders go, so a crafted file cannot nest without end. */
        internal const val MAX_FOLDER_DEPTH = 20
        /** How many bookmarks and folders a row of `folders` holds directly. */
        private const val FOLDER_COUNT = "((SELECT COUNT(*) FROM bookmarks WHERE folder=folders.id)+(SELECT COUNT(*) FROM folders f WHERE f.parent=folders.id))"
        private const val HISTORY_LIMIT = 5000
        private const val ICON_LIMIT = 500
        private const val ICON_SIZE = 64
        private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        @Volatile private var instance: BrowserStore? = null

        /** One connection pool per process, shared by the activity and services. */
        fun get(context: Context): BrowserStore = instance ?: synchronized(this) {
            instance ?: BrowserStore(context.applicationContext).also { instance = it }
        }

        private fun table(bookmarks: Boolean) = if (bookmarks) "bookmarks" else "history"

        /** Ahead of every bookmark and folder. */
        private fun firstPosition(db: SQLiteDatabase) = DatabaseUtils.longForQuery(db,
            "SELECT MIN(IFNULL((SELECT MIN(position) FROM bookmarks),0),IFNULL((SELECT MIN(position) FROM folders),0))-1", null)

        /** [id] when that folder exists, otherwise the top level. */
        private fun existingFolder(db: SQLiteDatabase, id: Long) =
            if (id != ROOT_FOLDER && DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM folders WHERE id=?", arrayOf(id.toString())) > 0) id else ROOT_FOLDER

        /** The folder named [name] inside [parent], made at [position] when there is none yet. */
        private fun folderId(db: SQLiteDatabase, name: String, parent: Long, position: () -> Long): Long {
            val clean = name.trim().take(MAX_FOLDER_NAME).ifEmpty { return parent }
            db.rawQuery("SELECT id FROM folders WHERE name=? AND parent=?", arrayOf(clean, parent.toString())).use { if (it.moveToFirst()) return it.getLong(0) }
            return db.insertOrThrow("folders", null, ContentValues().apply { put("name", clean); put("parent", parent); put("position", position()) })
        }

        private fun nameTaken(db: SQLiteDatabase, name: String, parent: Long, except: Long) =
            DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM folders WHERE name=? AND parent=? AND id<>?", arrayOf(name, parent.toString(), except.toString())) > 0

        /** Every folder inside [id], at any depth. */
        private fun descendants(db: SQLiteDatabase, id: Long): Set<Long> {
            val found = linkedSetOf<Long>()
            var level = listOf(id)
            while (level.isNotEmpty()) {
                level = level.flatMap { parent ->
                    db.rawQuery("SELECT id FROM folders WHERE parent=?", arrayOf(parent.toString())).use { c -> c.rows { getLong(0) } }
                }.filter(found::add)
            }
            return found
        }

        /**
         * Finds or makes the folder at a path of names from the top level, remembering each one; a new
         * folder goes at [position], given its full path.
         */
        private class FolderPaths(private val db: SQLiteDatabase, private val position: (List<String>) -> Long) {
            private val known = HashMap<List<String>, Long>()
            fun id(path: List<String>): Long {
                val clean = path.map { it.trim().take(MAX_FOLDER_NAME) }.filter { it.isNotEmpty() }.take(MAX_FOLDER_DEPTH)
                if (clean.isEmpty()) return ROOT_FOLDER
                return known.getOrPut(clean) { folderId(db, clean.last(), id(clean.dropLast(1))) { position(clean) } }
            }
        }

        /** `?,?,…` for an `IN (…)` list of [values]. */
        private fun placeholders(values: List<*>) = values.joinToString(",") { "?" }

        /** Every remaining row, read by [read]. */
        private fun <T> Cursor.rows(read: Cursor.() -> T): List<T> = buildList { while (moveToNext()) add(read()) }

        /** Escapes LIKE wildcards for use with `ESCAPE '\'`. */
        internal fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** `scheme://host[:port]` for web URLs, the unit websites are granted permissions by; null otherwise. */
        internal fun origin(url: String): String? {
            val uri = runCatching { url.toUri() }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf(BrowserInput::isWebScheme) ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }?.let { ":$it" }.orEmpty()
            return "$scheme://$host$port"
        }

        /** Remembered, because this runs once per list row while scrolling a recycling adapter. */
        private val siteKeys = Memo { url ->
            runCatching {
                val uri = url.toUri()
                if (!BrowserInput.isWebScheme(uri.scheme)) return@runCatching null
                val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                host + uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
            }.getOrNull()
        }

        /** Host (without `www.`) plus explicit port; null for non-web URLs. */
        internal fun siteKey(url: String): String? = siteKeys[url]
    }
}
