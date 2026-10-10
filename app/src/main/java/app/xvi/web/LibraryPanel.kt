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

/** Bookmarks or history. Rows are recycled, and searches run off the main thread. */
internal class LibraryPanel(private val host: BrowserActivity, private val bookmarks: Boolean) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(if (bookmarks) R.string.bookmarks_title else R.string.history_title)
    private var loading: Job? = null
    private var entries = emptyList<PageEntry>()
    private val icons = mutableMapOf<String, Bitmap>()
    private val actions = LibraryActions(host, sheet::showSnackbar) { refresh(0); host.refreshHome() }
    private val search = ui.edit(ui.str(R.string.library_search)).apply {
        background = ui.rounded(palette.base, 12); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        minimumHeight = ui.dp(48)
    }
    private val empty = ui.emptyState("").apply { visibility = View.GONE }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.tag as? MenuRow ?: ui.menuRow(R.drawable.ic_history_24, "", "").also {
                it.view.tag = it
                it.view.layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
            }
            val entry = entries[position]
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
            row.view.contentDescription = ui.str(R.string.library_row_description, entry.title, host.domain(entry.url))
            return row.view
        }
    }

    fun show() {
        sheet.add(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(8) })
        val list = ListView(host).apply {
            adapter = this@LibraryPanel.adapter
            divider = null
            selector = Color.TRANSPARENT.toDrawable()
            isVerticalScrollBarEnabled = true
            setOnItemClickListener { _, _, position, _ -> entries.getOrNull(position)?.let { sheet.dismiss(); host.navigate(it.url) } }
            setOnItemLongClickListener { _, view, position, _ ->
                val entry = entries.getOrNull(position) ?: return@setOnItemLongClickListener false
                host.haptic(view)
                if (bookmarks) actions.bookmark(entry, sheet::dismiss) else actions.history(entry, sheet::dismiss)
                true
            }
        }
        sheet.add(list, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.add(empty)
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        if (bookmarks) buttons.addView(ui.textAction(ui.str(R.string.bookmarks_files)) { bookmarkFiles() }, LinearLayout.LayoutParams(0, -2, 1f))
        else buttons.addView(ui.textAction(ui.str(R.string.clear_history)) {
            host.confirm(ui.str(R.string.clear_history_confirm), ui.str(R.string.action_clear), destructive = true) {
                host.lifecycleScope.launch {
                    withContext(host.store.io) { host.store.clearHistory() }
                    refresh(0); host.refreshHome()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
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
            adapter.notifyDataSetChanged()
            empty.text = ui.str(when {
                query.isNotBlank() -> R.string.library_no_match
                bookmarks -> R.string.bookmarks_empty
                else -> R.string.history_empty
            })
            empty.isVisible = found.isEmpty()
        }
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
