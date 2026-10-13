package app.xvi.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backup reads back as written, and a file from elsewhere keeps only what the browser could have made. */
class BrowserBackupTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2)

    private fun sample(secrets: BrowserBackup.Secrets? = null) = BrowserBackup.Backup(
        created = 1_700_000_000_000, appVersion = "0.3.5",
        settings = JSONObject().put("engine", "必应").put("adblock", false).put("theme", "dark"),
        ai = BrowserBackup.Ai(enabled = false, model = "deepseek-chat"),
        tabs = listOf(SavedTab("https://a.org/", "A", desktop = true), SavedTab("https://b.org/x", "B")),
        rows = BrowserBackup.Rows(
            bookmarks = listOf(BrowserBackup.Bookmark("https://a.org/", "A", 5, position = 3), BrowserBackup.Bookmark("https://c.org/", "C", 6, listOf("工作", "项目"), 1)),
            history = listOf(BrowserBackup.Visit("https://b.org/x", "B", 9, 3)),
            siteSettings = mapOf("a.org" to mapOf("desktop" to 1, "textZoom" to 130)),
            permissions = listOf(BrowserBackup.Permission("https://a.org", SitePermissions.CAMERA, true)),
            icons = mapOf("a.org" to png),
            folders = listOf(BrowserBackup.Folder(listOf("工作"), 2), BrowserBackup.Folder(listOf("工作", "项目"), 3), BrowserBackup.Folder(listOf("空目录"), 4))
        ),
        secrets = secrets
    )

    @Test fun everythingReadsBackAsWritten() {
        val back = BrowserBackup.decode(BrowserBackup.encode(sample()))
        assertEquals(1_700_000_000_000, back.created)
        assertEquals("必应", back.settings.getString("engine"))
        assertEquals(BrowserBackup.Ai(false, "deepseek-chat"), back.ai)
        assertEquals(listOf("https://a.org/", "https://b.org/x"), back.tabs.map { it.url })
        assertTrue(back.tabs[0].desktop)
        assertEquals(sample().rows.bookmarks, back.rows.bookmarks)
        assertEquals(sample().rows.folders, back.rows.folders)
        assertEquals(sample().rows.history, back.rows.history)
        assertEquals(mapOf("a.org" to mapOf("desktop" to 1, "textZoom" to 130)), back.rows.siteSettings)
        assertEquals(sample().rows.permissions, back.rows.permissions)
        assertArrayEquals(png, back.rows.icons.getValue("a.org"))
        assertNull(back.secrets)
    }

    @Test fun secretsAreOnlyThereWhenIncluded() {
        val secrets = BrowserBackup.Secrets("https://sub.example/token", 720, 10, "sk-123")
        assertEquals(secrets, BrowserBackup.decode(BrowserBackup.encode(sample(secrets))).secrets)
        assertTrue(!BrowserBackup.encode(sample()).contains("secrets"))
    }

    @Test fun otherFilesAreRefused() {
        listOf("", "not json", "{}", """{"format":"something-else","version":1}""", """{"format":"xvi-browser-backup","version":99}""").forEach { text ->
            val failed = runCatching { BrowserBackup.decode(text) }.exceptionOrNull()
            assertTrue("accepted: $text", failed is IllegalArgumentException)
        }
    }

    /** Entries the browser could never have written are dropped one by one; the rest still restores. */
    @Test fun malformedEntriesAreDropped() {
        val root = JSONObject(BrowserBackup.encode(sample()))
        root.getJSONArray("tabs").put(JSONObject().put("url", "javascript:alert(1)")).put(JSONObject().put("url", "file:///sdcard/x"))
        root.getJSONArray("bookmarks").put(JSONObject().put("url", "content://x/y").put("title", "bad"))
        root.getJSONArray("history").put("not an object")
        root.getJSONObject("siteSettings").put("Bad Site!", JSONObject().put("desktop", 1))
            .put("ok.org", JSONObject().put("desktop", "yes").put("unknown", 1))
        root.getJSONArray("permissions").put(JSONObject().put("origin", "https://a.org").put("kind", "contacts").put("allowed", true))
            .put(JSONObject().put("origin", "javascript:x").put("kind", "camera").put("allowed", true))
        root.getJSONObject("icons").put("b.org", java.util.Base64.getEncoder().encodeToString("<svg/>".toByteArray()))
        root.put("secrets", JSONObject().put("proxyUrl", "ftp://x").put("aiKey", ""))
        val back = BrowserBackup.decode(root.toString())
        assertEquals(2, back.tabs.size)
        assertEquals(2, back.rows.bookmarks.size)
        assertEquals(1, back.rows.history.size)
        assertEquals(setOf("a.org"), back.rows.siteSettings.keys)
        assertEquals(1, back.rows.permissions.size)
        assertEquals(setOf("a.org"), back.rows.icons.keys)
        assertNull(back.secrets)
    }

    @Test fun repeatedAndExcessEntriesAreCapped() {
        val root = JSONObject(BrowserBackup.encode(sample()))
        val tabs = JSONArray()
        repeat(80) { tabs.put(JSONObject().put("url", "https://t$it.org/")) }
        repeat(3) { tabs.put(JSONObject().put("url", "https://t1.org/")) }
        root.put("tabs", tabs)
        assertEquals(TabManager.MAX_TABS, BrowserBackup.decode(root.toString()).tabs.size)
    }

    /** Folders were first written by name alone, one level deep; such backups still restore them. */
    @Test fun singleLevelFoldersStillRead() {
        val root = JSONObject(BrowserBackup.encode(sample()))
        root.put("bookmarkFolders", JSONArray().put(JSONObject().put("name", "旧目录").put("position", 1)))
        root.put("bookmarks", JSONArray().put(JSONObject().put("url", "https://a.org/").put("title", "A").put("folder", "旧目录")))
        val back = BrowserBackup.decode(root.toString())
        assertEquals(listOf(BrowserBackup.Folder(listOf("旧目录"), 1)), back.rows.folders)
        assertEquals(listOf("旧目录"), back.rows.bookmarks.single().folder)
    }
}
