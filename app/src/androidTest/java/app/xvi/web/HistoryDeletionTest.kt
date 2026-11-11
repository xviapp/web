package app.xvi.web

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.database.sqlite.transaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run with -PwebIdSuffix=.verify; fixtures touch only their own hosts. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HistoryDeletionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host = "manual.history-delete.test"
    private val pdf = "https://$host/invoice.pdf?download=1#page=2"

    private fun seed(store: BrowserStore, url: String, title: String = "购物发票", time: Long = System.currentTimeMillis(), visits: Int = 7) {
        store.writableDatabase.insertWithOnConflict("history", null, ContentValues().apply {
            put("url", url); put("site", BrowserStore.siteKey(url).orEmpty()); put("title", title); put("updated", time); put("visits", visits)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun clean(store: BrowserStore) {
        store.writableDatabase.transaction {
            delete("history", "url LIKE ?", arrayOf("%history-delete.test%"))
            delete("bookmarks", "url LIKE ?", arrayOf("%history-delete.test%"))
            delete("icons", "site LIKE ?", arrayOf("%history-delete.test"))
        }
    }

    private fun fixture(block: (BrowserStore) -> Unit) = BrowserStore(context).use { store ->
        clean(store)
        try { block(store) } finally { clean(store) }
    }

    @Test fun removalFreezesThePreviewAndUndoKeepsNewerVisits() = fixture { store ->
        val first = "https://$host/first"
        val revisited = "https://$host/revisited"
        val sameTick = "https://$host/same-tick"
        seed(store, first, time = 100)
        seed(store, revisited, time = 100)
        seed(store, sameTick, time = 100)
        val preview = store.entries(false, host)
        seed(store, revisited, "New title", 200)
        seed(store, sameTick, time = 100, visits = 8)
        seed(store, "https://$host/new", time = 200)
        val removed = store.removeHistory(preview)
        assertEquals(listOf(first), removed.rows.map { it.getAsString("url") })
        assertEquals(3, store.entries(false, host).size)
        store.restore(removed)
        assertEquals(4, store.entries(false, host).size)
        assertEquals(8, store.entries(false, host).single { it.url == sameTick }.visits)
        assertEquals(7, store.backupRows().history.single { it.url == first }.visits)
        store.removeHistory(store.entries(false, host).filter { it.url == first }).also { deleted ->
            seed(store, first, "Visited after deletion", 300, 2)
            store.restore(deleted)
            val restored = store.backupRows().history.single { it.url == first }
            assertEquals("Visited after deletion", restored.title)
            assertEquals(300L, restored.updated)
            assertEquals(2, restored.visits)
        }
        assertTrue(store.removeHistory(emptyList()).isEmpty)
    }

    @Test fun largeRemovalRestoresRowsAndOnlyRemovesOrphanedIcons() = fixture { store ->
        val bookmarked = "https://bookmarked.history-delete.test/"
        val survivor = "https://shared.history-delete.test/keep"
        store.writableDatabase.transaction {
            repeat(1005) { seed(store, "https://$host/$it") }
            seed(store, bookmarked)
            seed(store, survivor)
            seed(store, "https://shared.history-delete.test/remove")
            store.bookmark("Keep bookmark", bookmarked)
            listOf(host, "bookmarked.history-delete.test", "shared.history-delete.test").forEach { site ->
                insert("icons", null, ContentValues().apply { put("site", site); put("png", byteArrayOf(1, 2)); put("updated", 100) })
            }
        }
        val preview = store.entries(false, "history-delete.test").filter { it.url != survivor }
        val removed = store.removeHistory(preview)
        assertEquals(1007, removed.rows.size)
        assertEquals(listOf(host), removed.icons.map { it.getAsString("site") })
        assertEquals(listOf(survivor), store.entries(false, "history-delete.test").map { it.url })
        assertTrue(store.isBookmarked(bookmarked))
        store.restore(removed)
        assertEquals(1008, store.entries(false, "history-delete.test").size)
        assertEquals(3, store.backupRows().icons.keys.count { it.endsWith("history-delete.test") })
    }

    @Test fun sqlHistoryFilteringKeepsLiteralUnicodeAndUrlMatching() = fixture { store ->
        listOf("Kelvin K", "İstanbul", "100%_off", "中文标题", "Äpfel", "ordinary").forEachIndexed { index, title ->
            seed(store, "https://$host/$index/invoice.pdf?download=1#page=2", title, 100L + index)
        }
        val entries = store.entries(false, host)
        val filters = listOf(
            HistoryFilter("k"), HistoryFilter("i"), HistoryFilter("%_"), HistoryFilter("中文"), HistoryFilter("ä"),
            HistoryFilter("https://$host/2", HistoryFilter.Mode.URL_PREFIX),
            HistoryFilter(".pdf", HistoryFilter.Mode.PATH_SUFFIX), HistoryFilter(host, HistoryFilter.Mode.DOMAIN),
            HistoryFilter(since = 103), HistoryFilter("ordinary", since = 106)
        )
        filters.forEach { filter ->
            assertEquals(filter.toString(), entries.filter(filter::matches), store.history(filter).filter { host in it.url })
        }
    }

    @Test fun recentSitesAreNotLostBehindHundredsOfVisitsToOneSite() = fixture { store ->
        store.writableDatabase.transaction {
            seed(store, "https://older.history-delete.test/", time = 1)
            repeat(250) { seed(store, "https://$host/$it", time = 10L + it) }
        }
        val recent = store.recentSites(500)
        assertTrue(recent.any { it.url == "https://older.history-delete.test/" })
        assertEquals(1, recent.count { it.url == "https://$host/" })
        assertEquals("https://$host/", recent.first { "history-delete.test" in it.url }.url)
    }

    @Test fun versionElevenMigrationBackfillsSiteKeysAndIndexes() {
        val name = "history-site-migration-test.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY,title TEXT,updated INTEGER,visits INTEGER)")
                db.execSQL("CREATE TABLE bookmarks(url TEXT PRIMARY KEY,title TEXT,updated INTEGER)")
                db.execSQL("INSERT INTO history VALUES ('https://www.migrate.test:8443/one','one',5,1)")
                db.execSQL("INSERT INTO bookmarks VALUES ('https://migrate.test:8443/two','two',2)")
                db.version = 11
            }
            BrowserStore(context, name).use { store ->
                for (table in listOf("history", "bookmarks")) {
                    store.readableDatabase.rawQuery("SELECT site FROM $table", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("migrate.test:8443", cursor.getString(0))
                    }
                    store.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM $table WHERE site=?", arrayOf("migrate.test:8443")).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertTrue(cursor.getString(3).contains("${table}_site"))
                    }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun allViews() = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.isShown }
    private fun label(text: String) = allViews().filterIsInstance<TextView>().lastOrNull { it.text.toString() == text }
    private fun await(scenario: ActivityScenario<BrowserActivity>, condition: (BrowserActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var done = false
            scenario.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(50)
        }
        fail("History UI did not reach the expected state")
    }
    private fun click(scenario: ActivityScenario<BrowserActivity>, text: String) = scenario.onActivity {
        assertTrue("Missing button: $text", requireNotNull(label(text)).performClick())
    }
    private fun query(scenario: ActivityScenario<BrowserActivity>, text: String) = scenario.onActivity {
        allViews().filterIsInstance<EditText>().last().setText(text)
        val button = allViews().filterIsInstance<TextView>().last { v -> v.text.startsWith("删除匹配记录") }
        assertFalse("An edit must invalidate the old preview immediately", button.isEnabled)
    }
    private fun count(scenario: ActivityScenario<BrowserActivity>, count: Int) = await(scenario) {
        label(it.getString(R.string.history_match_count, count)) != null
    }
    private fun pick(scenario: ActivityScenario<BrowserActivity>, index: Int) = scenario.onActivity {
        val list = allViews().filterIsInstance<AbsListView>().last()
        assertTrue(list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index)))
    }
    private fun withHistory(block: (ActivityScenario<BrowserActivity>, BrowserStore) -> Unit) = fixture { store ->
        seed(store, pdf)
        seed(store, "https://sub.$host/item", "子域名")
        seed(store, "https://not$host/?next=$host", "另一个网站")
        seed(store, "https://$host/old", "旧记录", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3))
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            await(scenario) { it.ready }
            scenario.onActivity { it.addTab("", true, it.normalSession); LibraryPanel(it, false).show() }
            await(scenario) { label(it.getString(R.string.history_filter_short)) != null }
            scenario.onActivity { assertNull(label(it.getString(R.string.history_delete_matches, store.entries(false).size))) }
            click(scenario, context.getString(R.string.history_filter_short))
            await(scenario) { label(it.getString(R.string.history_filter_loading)) == null }
            try { block(scenario, store) } finally { scenario.onActivity { BrowserSheet.dismissAll(it.root) } }
        }
    }

    @Test fun manualPreviewCanCancelDeleteAndUndoWithoutDeletingNewMatches() = withHistory { scenario, store ->
        scenario.onActivity { assertFalse(requireNotNull(label(it.getString(R.string.history_delete_matches, store.entries(false).size))).isEnabled) }
        query(scenario, "购物")
        count(scenario, 1)
        scenario.onActivity { assertNotNull("A URL match must show its full address", label(pdf)) }
        click(scenario, context.getString(R.string.history_delete_matches, 1))
        scenario.onActivity { assertNotNull(label(it.getString(R.string.history_delete_matches_confirm, 1, "时间：全部\n关键字包含：购物"))) }
        click(scenario, context.getString(R.string.action_cancel))
        assertTrue(store.entries(false).any { it.url == pdf })
        click(scenario, context.getString(R.string.history_delete_matches, 1))
        seed(store, "https://$host/new", "购物新记录")
        click(scenario, context.getString(R.string.action_delete))
        await(scenario) { store.entries(false).none { it.url == pdf } && label(it.getString(R.string.history_bulk_removed, 1)) != null }
        assertTrue(store.entries(false).any { it.url.endsWith("/new") })
        click(scenario, context.getString(R.string.action_undo))
        count(scenario, 2)
        assertTrue(store.entries(false).any { it.url == pdf })
        query(scenario, "")
        await(scenario) { label(it.getString(R.string.history_filter_loading)) == null }
        click(scenario, context.getString(R.string.history_time_range))
        pick(scenario, HistoryFilter.Range.ALL.ordinal)
        await(scenario) { label(it.getString(R.string.history_delete_matches, store.entries(false).size))?.isEnabled == true }
    }

    @Test fun domainSubdomainsTimeAndPathSuffixShareTheSamePreview() = withHistory { scenario, _ ->
        click(scenario, context.getString(R.string.history_match_choice, context.getString(R.string.history_match_keyword)))
        pick(scenario, HistoryFilter.Mode.DOMAIN.ordinal)
        query(scenario, host)
        count(scenario, 3)
        click(scenario, context.getString(R.string.history_time_range))
        pick(scenario, HistoryFilter.Range.TODAY.ordinal)
        count(scenario, 2)
        scenario.onActivity {
            val subdomains = allViews().filterIsInstance<CheckBox>().single()
            assertTrue(subdomains.isChecked)
            subdomains.performClick()
            assertFalse(subdomains.isChecked)
        }
        count(scenario, 1)
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(context.cacheDir, "history-filter-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        query(scenario, "*.history-delete.test")
        await(scenario) { label(it.getString(R.string.history_domain_invalid)) != null }
        click(scenario, context.getString(R.string.history_match_choice, context.getString(R.string.history_match_domain)))
        pick(scenario, HistoryFilter.Mode.PATH_SUFFIX.ordinal)
        query(scenario, ".pdf")
        count(scenario, 1)
        scenario.onActivity { assertNotNull(label(pdf)) }
        query(scenario, " ")
        await(scenario) { label(it.getString(R.string.history_filter_input_required)) != null }
    }
}
