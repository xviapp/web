package app.xvi.web

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bookmarks or history. Rows are recycled, and searches run off the main thread. Bookmarks reorder by
 * long-press and drag; history is grouped by the day of the last visit.
 */
internal class LibraryPanel(private val host: BrowserActivity, private val bookmarks: Boolean) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(if (bookmarks) R.string.bookmarks_title else R.string.history_title)
    private var loading: Job? = null
    private var entries = emptyList<PageEntry>()
    /** What the list shows: entries, and for history a day heading (String) before each day's entries. */
    private var rows = emptyList<Any>()
    private val icons = mutableMapOf<String, Bitmap>()
    private val actions = LibraryActions(host, sheet::showSnackbar) { refresh(0); host.refreshHome() }
    private val search = ui.edit(ui.str(R.string.library_search)).apply {
        background = ui.rounded(palette.base, 12); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        minimumHeight = ui.dp(48)
    }
    private val empty = ui.emptyState("").apply { visibility = View.GONE }
    private var drag: DragReorder? = null
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 1 else 0
        override fun isEnabled(position: Int) = rows[position] is PageEntry
        override fun areAllItemsEnabled() = bookmarks
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val heading = rows[position] as? String
            if (heading != null) return (convertView as? android.widget.TextView ?: ui.sectionTitle("").apply {
                layoutParams = android.widget.AbsListView.LayoutParams(ui.fill(), ui.wrap())
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }).apply { text = heading }
            val row = convertView?.tag as? MenuRow ?: ui.menuRow(R.drawable.ic_history_24, "", "").also {
                it.view.tag = it
                it.view.layoutParams = android.widget.AbsListView.LayoutParams(ui.fill(), ui.wrap())
            }
            val entry = rows[position] as PageEntry
            row.title.text = entry.title
            row.setSubtitle(host.domain(entry.url))
            val icon = BrowserStore.siteKey(entry.url)?.let(icons::get)
            if (icon != null) {
                row.icon.setImageBitmap(icon)
                row.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                row.icon.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            } else {
                row.icon.setImageDrawable(ui.tintedIcon(if (bookmarks) R.drawable.ic_bookmark_24 else R.drawable.ic_history_24, if (bookmarks) palette.accent else palette.muted))
                row.icon.scaleType = ImageView.ScaleType.CENTER
                row.icon.setPadding(0, 0, 0, 0)
            }
            row.view.background = ui.selectable()
            row.view.alpha = if (position == drag?.dragging) 0f else 1f
            row.view.translationX = 0f; row.view.translationY = 0f
            row.view.contentDescription = ui.str(R.string.library_row_description, entry.title, host.domain(entry.url))
            return row.view
        }
    }

    fun show() {
        sheet.add(search, ui.wide().apply { topMargin = ui.dp(4); bottomMargin = ui.dp(8) })
        val list = ListView(host).apply {
            adapter = this@LibraryPanel.adapter
            divider = null
            selector = Color.TRANSPARENT.toDrawable()
            isVerticalScrollBarEnabled = true
            setOnItemClickListener { _, _, position, _ -> (rows.getOrNull(position) as? PageEntry)?.let { sheet.dismiss(); host.navigate(it.url) } }
            setOnItemLongClickListener { _, view, position, _ ->
                val entry = rows.getOrNull(position) as? PageEntry ?: return@setOnItemLongClickListener false
                drag?.start(view, position) ?: run { host.haptic(view); actions.history(entry, sheet::dismiss) }
                true
            }
        }
        if (bookmarks) drag = DragReorder(host, list, slotAt = { x, y -> list.pointToPosition(x.toInt(), y.toInt()) },
            visible = { list.firstVisiblePosition..list.lastVisiblePosition },
            viewAt = { index -> list.getChildAt(index - list.firstVisiblePosition) },
            move = { from, to -> entries = entries.toMutableList().apply { add(to, removeAt(from)) }; rows = entries },
            redraw = adapter::notifyDataSetChanged, scroll = { list.scrollListBy(it) }, vertical = true) { index, moved ->
            val entry = entries.getOrNull(index) ?: return@DragReorder
            if (!moved) return@DragReorder actions.bookmark(entry, sheet::dismiss)
            // Search results are reordered among themselves; the rest keep their places.
            val order = entries.map { it.url }
            host.lifecycleScope.launch {
                withContext(host.store.io) { host.store.reorderBookmarks(order) }
                host.refreshHome()
            }
        }
        sheet.add(list, ui.stretch())
        sheet.add(empty)
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        if (bookmarks) buttons.addView(ui.textAction(ui.str(R.string.bookmarks_files)) { bookmarkFiles() }, ui.weighted())
        else buttons.addView(ui.textAction(ui.str(R.string.history_delete_range)) { deleteRange() }, ui.weighted())
        buttons.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, ui.weighted().apply { leftMargin = ui.dp(8) })
        sheet.add(buttons)
        ui.onTextChanged(search) { refresh(150) }
        sheet.onDismiss { loading?.cancel() }
        refresh(0)
        sheet.show()
    }

    private fun refresh(wait: Long) {
        loading?.cancel()
        val query = search.text.toString()
        loading = host.lifecycleScope.launch {
            delay(wait)
            val store = host.store
            val (found, loaded) = withContext(store.io) {
                val found = store.entries(bookmarks, query)
                val missing = found.map { it.url }.filter { BrowserStore.siteKey(it) !in icons }
                found to (if (missing.isEmpty()) emptyMap() else store.icons(missing))
            }
            icons.putAll(loaded)
            entries = found
            rows = if (bookmarks) found else HistoryDays.group(found, System.currentTimeMillis(), ::dayLabel)
            adapter.notifyDataSetChanged()
            empty.text = ui.str(when {
                query.isNotBlank() -> R.string.library_no_match
                bookmarks -> R.string.bookmarks_empty
                else -> R.string.history_empty
            })
            empty.isVisible = found.isEmpty()
        }
    }

    private fun dayLabel(day: HistoryDays.Day): String = when (day.daysAgo) {
        0 -> ui.str(R.string.history_today)
        1 -> ui.str(R.string.history_yesterday)
        else -> android.text.format.DateUtils.formatDateTime(host, day.start,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                (if (day.sameYear) android.text.format.DateUtils.FORMAT_NO_YEAR else android.text.format.DateUtils.FORMAT_SHOW_YEAR))
    }

    /** Deletes the history of a recent period, or all of it, after confirming. */
    private fun deleteRange() {
        val now = System.currentTimeMillis()
        val ranges = listOf(
            ui.str(R.string.history_range_hour) to now - 60 * 60 * 1000L,
            ui.str(R.string.history_range_today) to HistoryDays.startOfDay(now),
            ui.str(R.string.history_range_week) to HistoryDays.startOfDay(now) - 6 * 24 * 60 * 60 * 1000L,
            ui.str(R.string.history_range_all) to 0L
        )
        host.browserDialog().setTitle(R.string.history_delete_range).setItems(ranges.map { it.first }.toTypedArray()) { _, which ->
            val (label, since) = ranges[which]
            host.confirm(ui.str(R.string.history_delete_confirm, label), ui.str(R.string.action_delete), destructive = true) {
                host.lifecycleScope.launch {
                    withContext(host.store.io) { host.store.clearHistory(since) }
                    refresh(0); host.refreshHome()
                }
            }
        }.show()
    }

    private fun bookmarkFiles() {
        host.browserDialog().setTitle(R.string.bookmarks_files)
            .setItems(arrayOf(ui.str(R.string.bookmarks_import), ui.str(R.string.bookmarks_export))) { _, which ->
                sheet.dismiss()
                try {
                    if (which == 0) host.bookmarkImport.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain", "*/*"))
                    else host.bookmarkExport.launch(ui.str(R.string.bookmarks_export_name, ui.str(R.string.app_name)))
                } catch (_: android.content.ActivityNotFoundException) { host.toast(R.string.download_no_picker) }
            }.show()
    }
}
