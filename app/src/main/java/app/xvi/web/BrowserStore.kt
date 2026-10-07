package app.xvi.web

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InputStream

data class PageEntry(val url: String, val title: String)
data class SavedTab(val url: String, val title: String, val desktop: Boolean = false, val keepAlive: Boolean = false)
data class DownloadEntry(val id: Long, val title: String, val uri: String, val state: String)

/** All browser data stays in this app's private database; proxy data is separate MMKV. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 1) {
    val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,uri TEXT NOT NULL,state TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun remember(table: String, title: String, url: String) {
        if (!BrowserInput.isWebUrl(url)) return
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
            put("url", url); put("title", title.ifBlank { url }); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bookmark(title: String, url: String) = remember("bookmarks", title, url)
    fun visit(title: String, url: String) {
        remember("history", title, url)
        writableDatabase.execSQL("DELETE FROM history WHERE url IN (SELECT url FROM history ORDER BY updated DESC LIMIT -1 OFFSET 5000)")
    }
    fun entries(bookmarks: Boolean, query: String = ""): List<PageEntry> {
        val table = if (bookmarks) "bookmarks" else "history"
        return readableDatabase.rawQuery("SELECT url,title FROM $table WHERE title LIKE ? OR url LIKE ? ORDER BY updated DESC", arrayOf("%$query%", "%$query%")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PageEntry(cursor.getString(0), cursor.getString(1))) }
        }
    }
    fun remove(bookmarks: Boolean, url: String) { writableDatabase.delete(if (bookmarks) "bookmarks" else "history", "url=?", arrayOf(url)) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }
    fun saveTabs(tabs: List<SavedTab>, selected: Int) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("desktop", it.desktop).put("keepAlive", it.keepAlive)) }
        preferences.edit().putString("tabs", array.toString()).putInt("selected", selected).apply()
    }
    fun restoreTabs(): List<SavedTab> = runCatching {
        val array = JSONArray(preferences.getString("tabs", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            SavedTab(it.optString("url").takeIf { url -> BrowserInput.isWebUrl(url) } ?: "", it.optString("title"), it.optBoolean("desktop"), it.optBoolean("keepAlive"))
        }.take(50)
    }.getOrDefault(emptyList())
    fun exportBookmarks(): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1><META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\"><TITLE>行间书签</TITLE><H1>书签</H1><DL><p>\n")
        entries(true).forEach {
            val link = org.jsoup.nodes.Element("a").attr("href", it.url).text(it.title)
            append("<DT>").append(link.outerHtml()).append('\n')
        }
        append("</DL><p>")
    }
    fun importBookmarks(input: InputStream): Int {
        // Bound input size before parsing an untrusted document.
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 5 * 1024 * 1024) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= 5 * 1024 * 1024) { "书签文件不能超过 5 MB" }
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
    fun addDownload(title: String, uri: String): Long = writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
        put("title", title); put("uri", uri); put("state", "下载中")
    })
    fun downloadState(id: Long, state: String) { writableDatabase.update("downloads", ContentValues().apply { put("state", state) }, "id=?", arrayOf(id.toString())) }
    fun interruptDownloads(state: String) { writableDatabase.update("downloads", ContentValues().apply { put("state", state) }, "state=?", arrayOf("下载中")) }
    fun downloads(): List<DownloadEntry> = readableDatabase.rawQuery("SELECT id,title,uri,state FROM downloads ORDER BY id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(DownloadEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) }
    }
}
