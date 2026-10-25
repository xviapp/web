package app.xvi.web

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bookmarks or history. Rows are recycled, and searches run off the main thread. Each level of the
 * bookmarks lists its folders and bookmarks in one order; a folder opens in place, a level at a time,
 * and a search covers every folder. Folders and bookmarks reorder by long-press and drag, and one
 * dropped on a folder goes into it; history is grouped by the day of the last visit.
 */
internal class LibraryPanel(private val host: BrowserActivity, private val bookmarks: Boolean, opened: BookmarkFolder? = null) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(if (bookmarks) R.string.bookmarks_title else R.string.history_title)
    private var loading: Job? = null
    /** The bookmark folder being shown; null for the top level. */
    private var folder = opened
    /** Every folder, as of the last load, to find the one holding [folder]. */
    private var folders = emptyList<BookmarkFolder>()
    /**
     * What the list shows: entries, among them bookmark folders ([BookmarkFolder]) at the top level, and
     * for history a day heading (String) before each day's entries.
     */
    private var rows = emptyList<Any>()
    private val icons = mutableMapOf<String, Bitmap>()
    /** Sites whose icon has been asked for, so a row drawn again does not ask twice. */
    private val iconsAsked = HashSet<String>()
    /** Addresses of rows drawn without their icon, fetched together once the pass that drew them is over. */
    private val iconsWanted = LinkedHashSet<String>()
    /** A long history thus decodes the icons of the rows looked at, not of all five thousand. */
    private val fetchIcons = Runnable {
        val urls = iconsWanted.toList()
        iconsWanted.clear()
        if (urls.isEmpty() || !sheet.isShowing) return@Runnable
        host.lifecycleScope.launch {
            val loaded = withContext(host.store.io) { host.store.icons(urls) }
            if (loaded.isEmpty() || !sheet.isShowing) return@launch
            icons.putAll(loaded)
            adapter.notifyDataSetChanged()
        }
    }
    private val actions = LibraryActions(host, sheet::showSnackbar) { refresh(0); host.refreshHome() }
    private val search = ui.edit(ui.str(R.string.library_search)).apply {
        background = ui.rounded(palette.base, 12); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        minimumHeight = ui.dp(48)
    }
    private val empty = ui.emptyState("").apply { visibility = View.GONE }
    /** Heading of an open folder, leading back to the one holding it. */
    private val crumb = ui.menuRow(R.drawable.ic_arrow_back_24, "", ui.str(R.string.bookmark_folder_back), palette.muted) {
        open(folders.firstOrNull { it.id == folder?.parent })
    }.apply {
        view.visibility = View.GONE
    }
    private lateinit var list: ListView
    private var drag: DragReorder? = null
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 1 else 0
        override fun isEnabled(position: Int) = rows[position] !is String
        override fun areAllItemsEnabled() = bookmarks
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val heading = rows[position] as? String
            if (heading != null) return (convertView as? TextView ?: ui.sectionTitle("").apply {
                layoutParams = AbsListView.LayoutParams(ui.fill(), ui.wrap())
                markHeading()
            }).apply { text = heading }
            val row = convertView?.tag as? MenuRow ?: ui.menuRow(R.drawable.ic_history_24, "", "").also {
                it.view.tag = it
                it.view.layoutParams = AbsListView.LayoutParams(ui.fill(), ui.wrap())
            }
            row.view.background = ui.selectable()
            row.view.alpha = if (position == drag?.dragging) 0f else 1f
            row.view.translationX = 0f; row.view.translationY = 0f
            val shelf = rows[position] as? BookmarkFolder
            if (shelf != null) {
                row.title.text = shelf.name
                row.setSubtitle(ui.str(R.string.bookmark_folder_count, shelf.count))
                symbol(row, R.drawable.ic_folder_24, palette.accent)
                row.view.contentDescription = ui.str(R.string.bookmark_folder_description, shelf.name, shelf.count)
                return row.view
            }
            val entry = rows[position] as PageEntry
            row.title.text = entry.title
            row.setSubtitle(host.domain(entry.url))
            val icon = BrowserStore.siteKey(entry.url)?.let(icons::get)
            if (icon != null) row.setSiteIcon(icon, ui.dp(12))
            else {
                wantIcon(entry.url)
                if (bookmarks) symbol(row, R.drawable.ic_bookmark_24, palette.accent) else symbol(row, R.drawable.ic_history_24, palette.muted)
            }
            row.view.contentDescription = ui.str(R.string.library_row_description, entry.title, host.domain(entry.url))
            return row.view
        }
    }

    private fun wantIcon(url: String) {
        val site = BrowserStore.siteKey(url) ?: return
        if (!iconsAsked.add(site)) return
        iconsWanted.add(url)
        list.removeCallbacks(fetchIcons)
        list.post(fetchIcons)
    }

    private fun symbol(row: MenuRow, @DrawableRes icon: Int, color: Int) {
        row.icon.setImageDrawable(ui.tintedIcon(icon, color))
        row.icon.scaleType = ImageView.ScaleType.CENTER
        row.icon.setPadding(0, 0, 0, 0)
    }

    fun show() {
        sheet.add(search, ui.wide().apply { topMargin = ui.dp(4); bottomMargin = ui.dp(8) })
        sheet.add(crumb.view)
        list = ListView(host).apply {
            adapter = this@LibraryPanel.adapter
            divider = null
            selector = Color.TRANSPARENT.toDrawable()
            isVerticalScrollBarEnabled = true
            setOnItemClickListener { _, _, position, _ ->
                when (val item = rows.getOrNull(position)) {
                    is PageEntry -> { sheet.dismiss(); host.navigate(item.url) }
                    is BookmarkFolder -> open(item)
                }
            }
            setOnItemLongClickListener { _, view, position, _ ->
                val entry = rows.getOrNull(position)?.takeIf { it !is String } ?: return@setOnItemLongClickListener false
                drag?.start(view, position) ?: run { host.haptic(view); actions.history(entry as PageEntry, sheet::dismiss) }
                true
            }
        }
        if (bookmarks) drag = DragReorder(host, list, slotAt = { x, y -> list.pointToPosition(x.toInt(), y.toInt()) },
            visible = { list.firstVisiblePosition..list.lastVisiblePosition },
            viewAt = { index -> list.getChildAt(index - list.firstVisiblePosition) },
            move = { from, to -> rows = rows.toMutableList().apply { add(to, removeAt(from)) } },
            redraw = adapter::notifyDataSetChanged, scroll = { list.scrollListBy(it) },
            canHold = { from, to -> rows.getOrNull(from) != null && rows.getOrNull(to) is BookmarkFolder },
            into = { from, to -> actions.fileInto(rows[from], rows[to] as BookmarkFolder) }) { index, moved ->
            // Search results are reordered among themselves; the rest keep their places.
            val item = rows.getOrNull(index) ?: return@DragReorder
            if (moved) {
                val order = rows.toList()
                host.lifecycleScope.launch {
                    withContext(host.store.io) { host.store.arrange(order) }
                    host.refreshHome()
                }
            } else when (item) {
                is PageEntry -> actions.bookmark(item, sheet::dismiss)
                is BookmarkFolder -> actions.folder(item) { open(item) }
            }
        }
        sheet.add(list, ui.stretch())
        sheet.add(empty)
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        if (bookmarks) {
            buttons.addView(ui.textAction(ui.str(R.string.bookmarks_files)) { bookmarkFiles() }, ui.weighted())
            buttons.addView(ui.textAction(ui.str(R.string.bookmark_folder_new)) { actions.newFolder(folder?.id ?: BrowserStore.ROOT_FOLDER) }, ui.weighted().apply { leftMargin = ui.dp(8) })
        } else buttons.addView(ui.textAction(ui.str(R.string.history_delete_range)) { deleteRange() }, ui.weighted())
        buttons.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, ui.weighted().apply { leftMargin = ui.dp(8) })
        sheet.add(buttons)
        ui.onTextChanged(search) { refresh(150) }
        sheet.onDismiss { loading?.cancel() }
        refresh(0)
        sheet.show()
    }

    /** Shows what [target] holds, or the top level for null. */
    private fun open(target: BookmarkFolder?) {
        folder = target
        if (search.text.isNotEmpty()) search.text.clear()
        refresh(0)
        list.setSelection(0)
    }

    private fun refresh(wait: Long) {
        loading?.cancel()
        val query = search.text.toString()
        val within = folder
        loading = host.lifecycleScope.launch {
            delay(wait)
            val store = host.store
            val (found, all, first) = withContext(store.io) {
                val all = if (bookmarks) store.folders() else emptyList()
                // A folder deleted meanwhile falls back to the top level.
                val current = within?.let { open -> all.firstOrNull { it.id == open.id } }
                val found: List<Any> = when {
                    !bookmarks || query.isNotBlank() -> store.entries(bookmarks, query)
                    else -> store.children(current?.id ?: BrowserStore.ROOT_FOLDER)
                }
                // The first screenful comes with its icons; rows further down ask for theirs as they are drawn.
                val missing = found.filterIsInstance<PageEntry>().take(FIRST_ICONS).map { it.url }.filter { BrowserStore.siteKey(it) !in icons }
                Triple(found, all, missing to if (missing.isEmpty()) emptyMap() else store.icons(missing))
            }
            // Sites of the first screenful were looked up already, found or not; drawing them asks no more.
            first.first.mapNotNullTo(iconsAsked, BrowserStore::siteKey)
            icons.putAll(first.second)
            folders = all
            val current = within?.let { open -> all.firstOrNull { it.id == open.id } }
            folder = current
            rows = if (bookmarks) found else HistoryDays.group(found.filterIsInstance<PageEntry>(), System.currentTimeMillis(), ::dayLabel)
            adapter.notifyDataSetChanged()
            crumb.title.text = current?.name
            crumb.view.isVisible = current != null && query.isBlank()
            empty.text = ui.str(when {
                query.isNotBlank() -> R.string.library_no_match
                current != null -> R.string.bookmark_folder_empty
                bookmarks -> R.string.bookmarks_empty
                else -> R.string.history_empty
            })
            empty.isVisible = rows.isEmpty()
        }
    }

    private fun dayLabel(day: HistoryDays.Day): String = when (day.daysAgo) {
        0 -> ui.str(R.string.history_today)
        1 -> ui.str(R.string.history_yesterday)
        else -> DateUtils.formatDateTime(host, day.start, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
            (if (day.sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR))
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
                } catch (_: ActivityNotFoundException) { host.toast(R.string.download_no_picker) }
            }.show()
    }

    private companion object {
        /** About two screens of rows, whose icons come with the list itself. */
        const val FIRST_ICONS = 30
    }
}
