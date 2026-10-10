package app.xvi.web

import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main menu. Page actions appear only while a web page is showing. */
internal class MenuPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val sheet = host.sheet(R.string.app_name)

    fun show() {
        val page = host.tab?.takeIf { !host.homeVisible && BrowserInput.isWebUrl(it.url) }
        if (page == null) { build(null, false); return }
        // The bookmark state decides the first row, so read it before the panel appears.
        host.lifecycleScope.launch {
            val bookmarked = withContext(host.store.io) { host.store.isBookmarked(page.url) }
            build(page, bookmarked)
        }
    }

    private fun build(page: BrowserTab?, bookmarked: Boolean) {
        val body = ui.column()
        if (page != null) {
            body.addView(ui.sectionTitle(ui.str(R.string.menu_section_page)))
            body.addView(bookmarkRow(page, bookmarked))
            body.addView(ui.menuRow(R.drawable.ic_search_24, ui.str(R.string.menu_find)) { sheet.dismiss(); host.findInPage() }.view)
            body.addView(ui.menuRow(R.drawable.ic_share_24, ui.str(R.string.link_share)) { sheet.dismiss(); host.share(page.url) }.view)
            body.addView(ui.menuRow(R.drawable.ic_copy_24, ui.str(R.string.link_copy)) { sheet.dismiss(); host.copy(page.url) }.view)
            body.addView(ui.menuRow(R.drawable.ic_desktop_24, ui.str(R.string.menu_desktop),
                if (page.desktop) ui.str(R.string.menu_desktop_on) else null) {
                sheet.dismiss(); if (page in host.tabs) host.toggleDesktop(page)
            }.view)
            body.addView(ui.menuRow(R.drawable.ic_article_24, ui.str(R.string.menu_reader)) { sheet.dismiss(); host.pageTools.openReader(page) }.view)
            body.addView(ui.menuRow(R.drawable.ic_translate_24, ui.str(R.string.menu_translate)) { sheet.dismiss(); host.pageTools.translate(page) }.view)
            body.addView(ui.menuRow(R.drawable.ic_apps_24, ui.str(R.string.menu_tools)) { sheet.dismiss(); tools(page) }.view)
        }
        body.addView(ui.sectionTitle(ui.str(R.string.menu_section_browse)))
        body.addView(ui.menuRow(R.drawable.ic_ghost_24, ui.str(if (host.isPrivateMode) R.string.menu_leave_private else R.string.menu_private)) {
            sheet.dismiss(); host.switchGroup(!host.isPrivateMode)
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_qr_24, ui.str(R.string.scan_title)) { sheet.dismiss(); host.scan() }.view)
        body.addView(ui.menuRow(R.drawable.ic_bookmark_24, ui.str(R.string.bookmarks_title)) { sheet.dismiss(); LibraryPanel(host, true).show() }.view)
        body.addView(ui.menuRow(R.drawable.ic_history_24, ui.str(R.string.history_title)) { sheet.dismiss(); LibraryPanel(host, false).show() }.view)
        body.addView(ui.menuRow(R.drawable.ic_download_24, ui.str(R.string.downloads_title)) { sheet.dismiss(); DownloadsPanel(host).show() }.view)
        body.addView(ui.sectionTitle(ui.str(R.string.menu_section_settings)))
        body.addView(ui.menuRow(R.drawable.ic_star_24, ui.str(R.string.proxy_title), host.proxyLabel(), ui.palette.proxy(host.proxyPhase)) {
            sheet.dismiss(); host.showProxy()
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_settings_24, ui.str(R.string.settings_title)) { sheet.dismiss(); SettingsPanel(host).show() }.view)
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.show()
    }

    /** Less frequent page actions, kept out of the main list. */
    private fun tools(page: BrowserTab) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!page.isPrivate) add(ui.str(R.string.menu_add_to_home) to { host.pageTools.addToHome(page) })
            add(ui.str(R.string.menu_print) to { host.pageTools.print(page) })
            add(ui.str(R.string.menu_save_page) to { host.pageTools.savePage(page) })
            add(ui.str(R.string.menu_view_source) to { host.pageTools.viewSource(page) })
        }
        host.browserDialog(page).setTitle(R.string.menu_tools)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> if (page in host.tabs) actions[which].second() }
            .show()
    }

    private fun bookmarkRow(page: BrowserTab, bookmarked: Boolean) =
        if (bookmarked) {
            ui.menuRow(R.drawable.ic_bookmark_added_24, ui.str(R.string.bookmark_remove)) {
                sheet.dismiss()
                val store = host.store
                host.lifecycleScope.launch {
                    val removed = withContext(store.io) { store.remove(true, page.url) }
                    host.refreshHome()
                    host.showSnackbar(ui.str(R.string.bookmark_removed), ui.str(R.string.action_undo)) {
                        host.lifecycleScope.launch { withContext(store.io) { store.restore(removed) }; host.refreshHome() }
                    }
                }
            }.view
        } else {
            ui.menuRow(R.drawable.ic_bookmark_24, ui.str(R.string.bookmark_add)) {
                sheet.dismiss()
                host.bookmarkDialog(page.title, page.url, editUrl = false) { title, url ->
                    host.lifecycleScope.launch {
                        withContext(host.store.io) { host.store.bookmark(title, url) }
                        host.showSnackbar(ui.str(R.string.bookmark_added))
                    }
                }
            }.view
        }
}
