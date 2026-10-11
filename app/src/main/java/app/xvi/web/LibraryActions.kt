package app.xvi.web

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-press actions for bookmarks and history. Removals happen at once and offer undo through
 * [notify], which shows a snackbar wherever the list lives; [changed] redraws that list.
 */
internal class LibraryActions(
    private val host: BrowserActivity,
    private val notify: (message: String, action: String, onAction: () -> Unit) -> Unit,
    private val changed: () -> Unit
) {
    private val store get() = host.store

    fun bookmark(entry: PageEntry, before: () -> Unit = {}) = host.actionsDialog(entry.title, listOf(
        openInNewTab(entry, before),
        host.str(R.string.bookmark_edit) to { edit(entry) },
        host.str(R.string.action_delete) to { removeBookmark(entry.url) }
    ))

    fun history(entry: PageEntry, before: () -> Unit = {}) = host.actionsDialog(entry.title, listOf(
        openInNewTab(entry, before),
        host.str(R.string.action_delete) to { remove(R.string.history_removed) { remove(false, entry.url) } }
    ))

    /** A start-page "recent" entry stands for every visit to its site. */
    fun site(entry: PageEntry) = host.actionsDialog(entry.title, listOf(
        openInNewTab(entry),
        host.str(R.string.action_delete) to { remove(R.string.site_history_removed, host.domain(entry.url)) { removeSiteHistory(entry.url) } }
    ))

    /** Removes the bookmark for [url] at once, offering undo. */
    fun removeBookmark(url: String) = remove(R.string.bookmark_removed) { remove(true, url) }

    /** Library entries are ordinary browsing, so they open as ordinary tabs even from private mode. */
    private fun openInNewTab(entry: PageEntry, before: () -> Unit = {}): Pair<String, () -> Unit> =
        host.str(R.string.link_open_new_tab) to { before(); host.addTab(entry.url, session = host.normalSession) }

    private fun edit(entry: PageEntry) = host.bookmarkDialog(entry.title, entry.url) { title, url ->
        host.lifecycleScope.launch {
            val saved = withContext(store.io) { store.updateBookmark(entry.url, title, url) }
            if (saved) changed()
        }
    }

    private fun remove(message: Int, vararg args: Any, work: BrowserStore.() -> RemovedRows) {
        host.lifecycleScope.launch {
            val removed = withContext(store.io) { store.work() }
            changed()
            if (removed.isEmpty) return@launch
            notify(host.str(message, *args), host.str(R.string.action_undo)) {
                host.lifecycleScope.launch {
                    withContext(store.io) { store.restore(removed) }
                    changed()
                }
            }
        }
    }
}

/**
 * Simple list of actions titled with the item's name. An [address] title is smaller and may take two
 * lines, since its end often matters; [source] is the tab the dialog is about.
 */
internal fun BrowserActivity.actionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, source: BrowserTab? = tab, address: Boolean = false) {
    val text = title.trim().ifBlank { str(R.string.record_untitled) }
    val heading = ui.label(text, if (address) 13f else 15f, ui.palette.muted).apply {
        maxLines = if (address) 2 else 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8))
    }
    browserDialog(source).setCustomTitle(heading).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }.show()
}

/** Shows the dialog; its positive button runs [accept], which closes it only by returning true. */
internal fun AlertDialog.showChecked(accept: () -> Boolean) {
    setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (accept()) dismiss() } }
    show()
}

/**
 * Name (and, when [url] is editable, address) form for a bookmark. [save] gets the trimmed values
 * only when the address is a web URL; otherwise the dialog stays open with an error.
 */
internal fun BrowserActivity.bookmarkDialog(title: String, url: String, editUrl: Boolean = true, save: (String, String) -> Unit) {
    val name = ui.edit(str(R.string.bookmark_name)).apply { setText(title); setSelectAllOnFocus(true) }
    val address = ui.edit(str(R.string.bookmark_url)).apply {
        setText(url)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        visibility = if (editUrl) View.VISIBLE else View.GONE
    }
    val error = ui.label(str(R.string.bookmark_invalid_url), 12f, ui.palette.danger).apply { visibility = View.GONE }
    val form = ui.dialogForm(name, address, error, top = 8).apply {
        (address.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
        (error.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6)
    }
    browserDialog().setTitle(if (editUrl) R.string.bookmark_edit else R.string.bookmark_add).setView(form)
        .setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.action_cancel, null).create()
        .showChecked {
            val typed = address.text.toString().trim()
            val target = when {
                !editUrl -> url
                BrowserInput.isWebUrl(typed) -> typed
                typed.contains('.') && typed.none(Char::isWhitespace) -> "https://$typed"
                else -> ""
            }
            if (!BrowserInput.isWebUrl(target)) { error.visibility = View.VISIBLE; return@showChecked false }
            save(name.text.toString().trim(), target)
            true
        }
}
