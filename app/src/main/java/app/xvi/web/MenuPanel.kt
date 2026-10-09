package app.xvi.web

import android.widget.LinearLayout
import android.widget.ScrollView

/** Main menu. Page actions appear only while a web page is showing. */
internal class MenuPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val sheet = host.sheet(R.string.app_name)

    fun show() {
        val body = ui.column()
        val page = host.tab?.takeIf { !host.homeVisible && BrowserInput.isWebUrl(it.url) }
        if (page != null) {
            body.addView(ui.sectionTitle(ui.str(R.string.menu_section_page)))
            body.addView(bookmarkRow(page))
            body.addView(ui.menuRow(R.drawable.ic_search_24, ui.str(R.string.menu_find)) { sheet.dismiss(); host.findInPage() }.view)
            body.addView(ui.menuRow(R.drawable.ic_share_24, ui.str(R.string.link_share)) { sheet.dismiss(); host.share(page.url) }.view)
            body.addView(ui.menuRow(R.drawable.ic_copy_24, ui.str(R.string.link_copy)) { sheet.dismiss(); host.copy(page.url) }.view)
            body.addView(ui.menuRow(R.drawable.ic_desktop_24, ui.str(R.string.menu_desktop),
                ui.str(if (page.desktop) R.string.menu_desktop_on else R.string.menu_desktop_off)) {
                sheet.dismiss(); if (page in host.tabs) host.toggleDesktop(page)
            }.view)
        }
        body.addView(ui.sectionTitle(ui.str(R.string.menu_section_browse)))
        body.addView(ui.menuRow(R.drawable.ic_ghost_24, ui.str(if (host.isPrivateMode) R.string.menu_leave_private else R.string.menu_private)) {
            sheet.dismiss(); host.switchGroup(!host.isPrivateMode)
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_bookmark_24, ui.str(R.string.bookmarks_title)) { sheet.dismiss(); LibraryPanel(host, true).show() }.view)
        body.addView(ui.menuRow(R.drawable.ic_history_24, ui.str(R.string.history_title)) { sheet.dismiss(); LibraryPanel(host, false).show() }.view)
        body.addView(ui.menuRow(R.drawable.ic_download_24, ui.str(R.string.downloads_title)) { sheet.dismiss(); DownloadsPanel(host).show() }.view)
        body.addView(ui.sectionTitle(ui.str(R.string.menu_section_settings)))
        body.addView(ui.menuRow(R.drawable.ic_proxy_24, ui.str(R.string.proxy_title), host.proxyLabel(), ui.palette.proxy(host.proxyPhase)) {
            sheet.dismiss(); host.showProxy()
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_settings_24, ui.str(R.string.settings_title)) { sheet.dismiss(); SettingsPanel(host).show() }.view)
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.show()
    }

    private fun bookmarkRow(page: BrowserTab) =
        if (host.store.isBookmarked(page.url)) {
            ui.menuRow(R.drawable.ic_bookmark_added_24, ui.str(R.string.bookmark_saved), ui.str(R.string.bookmark_saved_hint)) {
                sheet.dismiss()
                val url = page.url
                val title = page.title
                host.store.remove(true, url)
                host.showSnackbar(ui.str(R.string.bookmark_removed), ui.str(R.string.action_undo)) { host.store.bookmark(title, url) }
            }.view
        } else {
            ui.menuRow(R.drawable.ic_bookmark_24, ui.str(R.string.bookmark_add)) {
                sheet.dismiss()
                val name = ui.edit(ui.str(R.string.bookmark_name)).apply { setText(page.title); setSelectAllOnFocus(true) }
                val frame = android.widget.FrameLayout(host).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(name) }
                host.browserDialog().setTitle(R.string.bookmark_add)
                    .setMessage(if (page.isPrivate) ui.str(R.string.bookmark_private_note) else null)
                    .setView(frame)
                    .setPositiveButton(R.string.action_save) { _, _ -> host.store.bookmark(name.text.toString(), page.url); host.showSnackbar(ui.str(R.string.bookmark_added)) }
                    .setNegativeButton(R.string.action_cancel, null).show()
            }.view
        }
}
