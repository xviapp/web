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
            put("url", url); put("title", title); put("updated", time); put("visits", visits)
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
        scenario.onActivity { assertTrue(allViews().filterIsInstance<CheckBox>().single().performClick()) }
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
