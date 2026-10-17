package app.xvi.web

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-press actions for bookmarks, their folders and history. Removals happen at once and offer undo through
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
        host.str(R.string.action_delete) to { undoable(R.string.history_removed) { remove(false, entry.url) } }
    ))

    /** A start-page "recent" entry stands for every visit to its site. */
    fun site(entry: PageEntry) = host.actionsDialog(entry.title, listOf(
        openInNewTab(entry),
        host.str(R.string.action_delete) to { undoable(R.string.site_history_removed, host.domain(entry.url)) { removeSiteHistory(entry.url) } }
    ))

    /** [open] shows what the folder holds. Deleting it takes everything inside too, with undo. */
    fun folder(folder: BookmarkFolder, open: () -> Unit) = host.actionsDialog(folder.name, listOf(
        host.str(R.string.bookmark_folder_open) to open,
        host.str(R.string.bookmark_folder_rename) to { rename(folder) },
        host.str(R.string.bookmark_folder_move) to { relocate(folder) },
        host.str(R.string.action_delete) to { undoable(R.string.bookmark_folder_removed, folder.name) { removeFolder(folder.id) } }
    ))

    /** Asks for a name and makes the folder inside [parent]. */
    fun newFolder(parent: Long = BrowserStore.ROOT_FOLDER) {
        host.lifecycleScope.launch {
            val taken = withContext(store.io) { store.folders().filter { it.parent == parent }.map { it.name } }
            host.folderNameDialog(R.string.bookmark_folder_new, "", taken) { name ->
                host.lifecycleScope.launch {
                    withContext(store.io) { store.addFolder(name, parent) }
                    changed()
                }
            }
        }
    }

    private fun rename(folder: BookmarkFolder) {
        host.lifecycleScope.launch {
            val taken = withContext(store.io) { store.folders().filter { it.parent == folder.parent }.map { it.name } }
            host.folderNameDialog(R.string.bookmark_folder_rename, folder.name, taken) { name ->
                host.lifecycleScope.launch {
                    if (withContext(store.io) { store.renameFolder(folder.id, name) }) changed()
                }
            }
        }
    }

    /** Picks where the folder goes: the top level or any folder outside it. */
    private fun relocate(folder: BookmarkFolder) {
        host.lifecycleScope.launch {
            val all = withContext(store.io) { store.folders() }
            val inside = BookmarkTree.subtree(all, folder.id)
            val places = listOf(null to 0) + BookmarkTree.flatten(all).filter { it.first.id !in inside }
            val labels = places.map { (place, depth) -> place?.let { "\u3000".repeat(depth) + it.name } ?: host.str(R.string.bookmark_folder_root) }
            host.browserDialog().setTitle(R.string.bookmark_folder_move).setItems(labels.toTypedArray()) { _, which ->
                val target = places[which].first?.id ?: BrowserStore.ROOT_FOLDER
                host.lifecycleScope.launch {
                    if (withContext(store.io) { store.moveFolder(folder.id, target) }) changed() else host.toast(R.string.bookmark_folder_exists)
                }
            }.show()
        }
    }

    /** Moves [item], a bookmark or folder, into [folder] at once; a bookmark's move offers undo. */
    fun fileInto(item: Any, folder: BookmarkFolder) {
        if (item is PageEntry) return undoable(R.string.bookmark_moved, folder.name) { moveBookmark(item.url, folder.id) }
        host.lifecycleScope.launch {
            if (withContext(store.io) { store.moveInto(item, folder.id) }) changed() else host.toast(R.string.bookmark_folder_exists)
        }
    }

    /** Removes the bookmark for [url] at once, offering undo. */
    fun removeBookmark(url: String) = undoable(R.string.bookmark_removed) { remove(true, url) }

    /** Opens in the current mode, so a private window never quietly continues with ordinary sign-ins and history. */
    private fun openInNewTab(entry: PageEntry, before: () -> Unit = {}): Pair<String, () -> Unit> =
        host.str(R.string.link_open_new_tab) to { before(); host.addTab(entry.url) }

    private fun edit(entry: PageEntry) = host.bookmarkDialog(entry.title, entry.url, folder = entry.folder) { title, url, folder ->
        host.lifecycleScope.launch {
            val saved = withContext(store.io) { store.updateBookmark(entry.url, title, url, folder) }
            if (saved) changed()
        }
    }

    /** Runs [work] at once; what it took or changed can be put back from the snackbar. */
    private fun undoable(message: Int, vararg args: Any, work: BrowserStore.() -> RemovedRows) {
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
internal fun PageHost.actionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, source: BrowserTab? = tab, address: Boolean = false) {
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
 * Name (and, when [url] is editable, address) form for a bookmark, with the folder it goes in, which
 * starts as [folder]. [save] gets the trimmed values only when the address is a web URL; otherwise
 * the dialog stays open with an error.
 */
internal fun BrowserActivity.bookmarkDialog(
    title: String, url: String, editUrl: Boolean = true, folder: Long = BrowserStore.ROOT_FOLDER,
    save: (title: String, url: String, folder: Long) -> Unit
) = lifecycleScope.launch {
    var folders = withContext(store.io) { store.folders() }
    var chosen = folder.takeIf { id -> folders.any { it.id == id } } ?: BrowserStore.ROOT_FOLDER
    val place = ui.textAction("") {}
    fun showPlace() {
        val path = BookmarkTree.path(folders, chosen)
        place.text = str(R.string.bookmark_folder_choice, if (path.isEmpty()) str(R.string.bookmark_folder_root) else path.joinToString(" / "))
    }
    showPlace()
    place.setOnClickListener {
        // Folders appear nested, each under the one holding it; a new folder goes at the top level.
        val tree = BookmarkTree.flatten(folders)
        val labels = listOf(str(R.string.bookmark_folder_root)) + tree.map { (it, depth) -> "\u3000".repeat(depth) + it.name } +
            str(R.string.bookmark_folder_new_choice)
        val selected = if (chosen == BrowserStore.ROOT_FOLDER) 0 else tree.indexOfFirst { it.first.id == chosen } + 1
        ui.popupList(place, labels, place.width, selected) { index ->
            when (index) {
                0 -> chosen = BrowserStore.ROOT_FOLDER
                labels.lastIndex -> folderNameDialog(R.string.bookmark_folder_new, "", folders.filter { it.parent == BrowserStore.ROOT_FOLDER }.map { it.name }) { name ->
                    lifecycleScope.launch {
                        val id = withContext(store.io) { store.addFolder(name) }
                        folders = withContext(store.io) { store.folders() }
                        chosen = id
                        showPlace()
                    }
                }
                else -> chosen = tree[index - 1].first.id
            }
            showPlace()
        }
    }
    val name = ui.edit(str(R.string.bookmark_name)).apply { setText(title); setSelectAllOnFocus(true) }
    val address = ui.edit(str(R.string.bookmark_url)).apply {
        setText(url)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        visibility = if (editUrl) View.VISIBLE else View.GONE
    }
    val error = ui.label(str(R.string.bookmark_invalid_url), 12f, ui.palette.danger).apply { visibility = View.GONE }
    val form = ui.dialogForm(name, address, place, error, top = 8).apply {
        (address.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
        (place.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
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
            save(name.text.toString().trim(), target, chosen)
            true
        }
}

/** Name form for a folder; a name another folder in [taken] has keeps the dialog open with an error. */
internal fun BrowserActivity.folderNameDialog(@StringRes heading: Int, current: String, taken: Collection<String>, save: (String) -> Unit) {
    val name = ui.edit(str(R.string.bookmark_folder_name)).apply { setText(current); setSelectAllOnFocus(true) }
    val error = ui.label(str(R.string.bookmark_folder_exists), 12f, ui.palette.danger).apply { visibility = View.GONE }
    val form = ui.dialogForm(name, error, top = 8).apply { (error.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6) }
    browserDialog().setTitle(heading).setView(form)
        .setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.action_cancel, null).create()
        .showChecked {
            val typed = name.text.toString().trim().take(BrowserStore.MAX_FOLDER_NAME)
            if (typed.isEmpty()) return@showChecked false
            if (typed != current && typed in taken) { error.visibility = View.VISIBLE; return@showChecked false }
            save(typed)
            true
        }
}
