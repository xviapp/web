package app.xvi.web

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main menu as a grid of icons. Page actions appear only while a web page is showing. */
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
            body.addView(grid(listOfNotNull(
                bookmarkAction(page, bookmarked),
                action(R.drawable.ic_share_24, R.string.menu_quick_share, ui.str(R.string.link_share)) { sheet.dismiss(); host.share(page.url) },
                action(R.drawable.ic_copy_24, R.string.menu_quick_copy, ui.str(R.string.link_copy)) { sheet.dismiss(); host.copy(page.url) },
                action(R.drawable.ic_search_24, R.string.menu_quick_find, ui.str(R.string.menu_find)) { sheet.dismiss(); host.findInPage() },
                action(R.drawable.ic_translate_24, R.string.menu_quick_translate, ui.str(R.string.menu_translate)) { sheet.dismiss(); host.pageTools.translate(page) },
                action(R.drawable.ic_article_24, R.string.menu_quick_reader, ui.str(R.string.menu_reader)) { sheet.dismiss(); host.pageTools.openReader(page) },
                action(R.drawable.ic_desktop_24, R.string.menu_quick_desktop,
                    if (page.desktop) ui.str(R.string.menu_state, ui.str(R.string.menu_desktop), ui.str(R.string.menu_desktop_on)) else ui.str(R.string.menu_desktop),
                    active = page.desktop) { sheet.dismiss(); if (page in host.tabs) host.toggleDesktop(page) },
                action(R.drawable.ic_apps_24, R.string.menu_quick_tools, ui.str(R.string.menu_tools)) { sheet.dismiss(); tools(page) },
                // Optional, so it goes last and switching it off moves nothing else.
                if (!host.aiConfig.enabled) null else
                    action(R.drawable.ic_ai_24, R.string.menu_quick_ai, ui.str(R.string.menu_ai)) { sheet.dismiss(); if (page in host.tabs) host.assistant.page(page) },
            )))
            body.addView(View(host).apply { setBackgroundColor(ui.palette.base) },
                ui.bar(1).apply { setMargins(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) })
        }
        val private = host.isPrivateMode
        body.addView(grid(listOfNotNull(
            action(R.drawable.ic_ghost_24, if (private) R.string.menu_quick_leave_private else R.string.menu_quick_private,
                ui.str(if (private) R.string.menu_leave_private else R.string.menu_private), active = private) {
                sheet.dismiss(); host.switchGroup(!host.isPrivateMode)
            },
            action(R.drawable.ic_bookmark_24, R.string.bookmarks_title, ui.str(R.string.bookmarks_title)) { sheet.dismiss(); LibraryPanel(host, true).show() },
            action(R.drawable.ic_history_24, R.string.menu_quick_history, ui.str(R.string.history_title)) { sheet.dismiss(); LibraryPanel(host, false).show() },
            action(R.drawable.ic_download_24, R.string.downloads_title, ui.str(R.string.downloads_title)) { sheet.dismiss(); DownloadsPanel(host).show() },
            action(R.drawable.ic_settings_24, R.string.settings_title, ui.str(R.string.settings_title)) { sheet.dismiss(); SettingsPanel(host).show() },
            // Leaving with back only hides the browser, so clearing on exit needs a real way out.
            if (host.prefs.clearOnExit.isEmpty()) null else
                action(R.drawable.ic_clean_24, R.string.menu_quick_exit, ui.str(R.string.menu_exit)) { sheet.dismiss(); host.finishAndRemoveTask() },
        )))
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.show()
    }

    /** Rows of [COLUMNS] equal cells; a short last row keeps the same cell width. */
    private fun grid(cells: List<View>) = ui.column().apply {
        cells.chunked(COLUMNS).forEach { chunk ->
            val row = ui.row().apply { gravity = Gravity.TOP }
            chunk.forEach { row.addView(it, ui.weighted()) }
            repeat(COLUMNS - chunk.size) { row.addView(View(host), ui.sized(0, 0, 1f)) }
            addView(row, ui.wide())
        }
    }

    /** Less frequent page actions, kept out of the main list. */
    private fun tools(page: BrowserTab) {
        val tools = host.sheet(R.string.menu_tools)
        fun tool(icon: Int, label: Int, title: Int, run: () -> Unit) =
            action(icon, label, ui.str(title)) { tools.dismiss(); if (page in host.tabs) run() }
        tools.add(grid(listOfNotNull(
            if (page.isPrivate) null else tool(R.drawable.ic_home_24, R.string.menu_quick_add_to_home, R.string.menu_add_to_home) { host.pageTools.addToHome(page) },
            if (page.isPrivate) null else tool(R.drawable.ic_apps_24, R.string.menu_quick_install_app, R.string.menu_install_app) { host.pageTools.installApp(page) },
            if (page.isPrivate) null else tool(R.drawable.ic_open_in_new_24, R.string.menu_quick_open_window, R.string.menu_open_window) { host.pageTools.openInWindow(page) },
            tool(R.drawable.ic_print_24, R.string.menu_quick_print, R.string.menu_print) { host.pageTools.print(page) },
            tool(R.drawable.ic_download_24, R.string.menu_save_page, R.string.menu_save_page) { host.pageTools.savePage(page) },
            tool(R.drawable.ic_code_24, R.string.menu_quick_view_source, R.string.menu_view_source) { host.pageTools.viewSource(page) },
            tool(R.drawable.ic_code_24, R.string.menu_quick_scripts, R.string.menu_scripts) { UserScriptsPanel.forPage(host, page) { UserScriptsPanel(host).show() } },
        )))
        tools.show()
    }

    /** Icon over a short label; [description] is the full name, and [active] marks a setting that is on. */
    private fun action(icon: Int, label: Int, description: String, color: Int = ui.palette.accent, active: Boolean = false, click: () -> Unit): View =
        ui.asButton(ui.column()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = ui.dp(72)
            setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(8))
            background = if (active) ui.pressable(ui.palette.selectedSurface, 14) else ui.selectable(14)
            isSelected = active
            contentDescription = description
            tooltipText = description
            setOnClickListener { click() }
            addView(ImageView(host).apply {
                setImageDrawable(ui.tintedIcon(icon, color))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, ui.box(24, 24))
            addView(ui.singleLine(ui.label(ui.str(label), 12f, if (active) ui.palette.accent else ui.palette.muted)).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, ui.sized(ui.wrap(), ui.wrap()).apply { topMargin = ui.dp(6) })
        }

    private fun bookmarkAction(page: BrowserTab, bookmarked: Boolean) =
        if (bookmarked) {
            action(R.drawable.ic_bookmark_added_24, R.string.menu_quick_bookmarked, ui.str(R.string.bookmark_remove), active = true) {
                sheet.dismiss()
                LibraryActions(host, host::showSnackbar) { host.refreshHome() }.removeBookmark(page.url)
            }
        } else {
            action(R.drawable.ic_bookmark_24, R.string.menu_quick_bookmark, ui.str(R.string.bookmark_add)) {
                sheet.dismiss()
                host.bookmarkDialog(page.title, page.url, editUrl = false) { title, url, folder ->
                    host.lifecycleScope.launch {
                        withContext(host.store.io) { host.store.bookmark(title, url, folder) }
                        host.showSnackbar(ui.str(R.string.bookmark_added))
                    }
                }
            }
        }

    private companion object {
        const val COLUMNS = 5
    }
}
