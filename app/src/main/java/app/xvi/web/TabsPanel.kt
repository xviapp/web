package app.xvi.web

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab list for the current mode, with an ordinary / private switch in the header. Opens scrolled to
 * the current tab; rows close with a sideways swipe or their close button, and an undo snackbar follows.
 * The bottom keeps one row: a new tab, plus the rarer actions behind a more button.
 */
internal class TabsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private var privateGroup = host.isPrivateMode
    private lateinit var ordinaryMode: TextView
    private lateinit var privateMode: TextView
    private val sheet = host.sheet(R.string.tabs_title) { sheet ->
        fun mode(private: Boolean) = ui.asButton(ui.medium(ui.label(ui.str(if (private) R.string.tabs_mode_private else R.string.tabs_mode_ordinary), 13f))).apply {
            gravity = Gravity.CENTER
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            contentDescription = ui.str(if (private) R.string.tabs_switch_private else R.string.tabs_switch_ordinary)
            setOnClickListener {
                if (private == privateGroup) return@setOnClickListener
                host.haptic()
                sheet.dismiss()
                host.switchGroup(private)
                if (!host.privateCovered) TabsPanel(host).show()
            }
        }
        ui.row().apply {
            background = ui.rounded(palette.base, 18)
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            ordinaryMode = mode(false).also { addView(it, ui.sized(ui.wrap(), ui.wrap())) }
            privateMode = mode(true).also { addView(it, ui.sized(ui.wrap(), ui.wrap())) }
        }
    }
    private val summary = ui.label("", 12f, palette.muted)
    private val list = ui.column()
    private val scroll = ScrollView(host).apply { addView(list) }
    private val actions = ui.column().apply { setPadding(0, ui.dp(8), 0, 0) }
    private val storedIcons = mutableMapOf<String, Bitmap>()

    init {
        sheet.add(summary)
        sheet.add(scroll, ui.stretch())
        sheet.add(actions)
    }

    fun show() {
        host.tabsPanel?.dismiss()
        host.tabsPanel = this
        sheet.onDismiss { if (host.tabsPanel === this) host.tabsPanel = null }
        render()
        sheet.show()
        scrollToCurrent()
        // Restored tabs have no live icon yet; use the ones saved for ordinary sites.
        host.loadStoredIcons(sheet, host.tabs.filter { !it.isPrivate && it.favicon == null && it.url.isNotEmpty() }.map { it.url }) {
            storedIcons.putAll(it); render()
        }
    }

    fun dismiss() = sheet.dismiss()

    fun render() {
        if (host.tabManager.count(privateGroup) == 0) privateGroup = host.isPrivateMode
        val count = host.tabManager.count(privateGroup)
        listOf(ordinaryMode to false, privateMode to true).forEach { (view, private) ->
            val selected = private == privateGroup
            view.isSelected = selected
            view.background = if (selected) ui.pressable(palette.selectedSurface, 16) else ui.selectable(16)
            view.setTextColor(if (!selected) palette.muted else if (private) palette.accent else palette.ink)
        }
        summary.text = ui.str(if (privateGroup) R.string.tabs_summary_private else R.string.tabs_summary, count)
        list.removeAllViews()
        host.tabs.forEach { item ->
            if (item.isPrivate == privateGroup) list.addView(row(item), ui.wide().apply { bottomMargin = ui.dp(4) })
        }
        renderActions(count)
    }

    private fun scrollToCurrent() {
        val current = host.tab ?: return
        list.post {
            val view = (0 until list.childCount).map(list::getChildAt).firstOrNull { it.tag === current } ?: return@post
            scroll.scrollTo(0, (view.top - (scroll.height - view.height) / 2).coerceAtLeast(0))
        }
    }

    private fun close(item: BrowserTab) {
        host.haptic()
        val title = host.displayTitle(item).take(20)
        host.tabs.indexOf(item).takeIf { it >= 0 }?.let { host.closeTab(it) }
        offerUndo(ui.str(R.string.tab_closed, title))
    }

    private fun closeGroup(except: BrowserTab?) {
        val closing = host.tabs.count { it.isPrivate == privateGroup && it !== except }
        host.closeGroup(privateGroup, except)
        offerUndo(ui.str(R.string.tabs_closed_many, closing))
    }

    /** Undo lives in the panel's own snackbar, so the bottom row never changes shape after a close. */
    private fun offerUndo(message: String) {
        // Closing the last private tab ends its session, and the panel then shows ordinary tabs; there is
        // nothing left to undo into, as the old in-row undo button also knew.
        if (host.tabManager.lastClosed?.private != privateGroup || !sheet.isShowing) return
        sheet.showSnackbar(message, ui.str(R.string.action_undo)) { host.undoCloseTabs() }
    }

    private fun row(item: BrowserTab): View {
        val current = item === host.tab
        val title = host.displayTitle(item)
        val status = when {
            item.keepAlive -> ui.str(R.string.tab_status_kept, host.domain(item.url))
            item.view == null && item.url.isNotEmpty() -> ui.str(R.string.tab_status_paused)
            item.url.isEmpty() -> ui.str(R.string.tab_new)
            else -> host.domain(item.url)
        }
        val keep = ui.iconAction(R.drawable.ic_pin_24, ui.str(if (item.keepAlive) R.string.tab_keep_off else R.string.tab_keep_on),
            if (item.keepAlive) palette.accent else palette.muted) {
            host.haptic()
            item.keepAlive = !item.keepAlive
            host.persist()
            render()
        }
        val closeButton = ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.tab_close_named, title)) { close(item) }
        return ui.tabLine(title, item.url, item.favicon ?: BrowserStore.siteKey(item.url)?.let(storedIcons::get), status,
            ui.str(if (current) R.string.tab_description_current else R.string.tab_description, title, status),
            selected = current, actions = listOf(keep, closeButton), onSwipe = { close(item) }) {
            host.haptic(it)
            host.selectTab(item)
            sheet.dismiss()
        }.apply { tag = item }
    }

    private fun renderActions(count: Int) {
        actions.removeAllViews()
        val row = ui.row()
        row.addView(ui.textAction(ui.str(R.string.tabs_new), true) { sheet.dismiss(); host.addTab() }, ui.weighted())
        val more = moreActions(count)
        if (more.isNotEmpty()) {
            lateinit var button: View
            button = ui.iconAction(R.drawable.ic_more_24, ui.str(R.string.tabs_more)) { openMore(button, more) }
            row.addView(button, ui.tappable().apply { leftMargin = ui.dp(8) })
        }
        actions.addView(row, ui.wide())
    }

    private class MoreAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

    /** The rarer actions: reopening closed tabs and closing in bulk. */
    private fun moreActions(count: Int): List<MoreAction> = buildList {
        val reopenable = host.recentlyClosed.count { it.tab.isPrivate == privateGroup }
        if (reopenable > 0) add(MoreAction(ui.str(R.string.tabs_recently_closed, reopenable)) {
            sheet.dismiss(); RecentlyClosedPanel(host, privateGroup).show()
        })
        if (count > 1) add(MoreAction(ui.str(R.string.tabs_close_others)) { closeGroup(host.tab) })
        if (count > 0) add(MoreAction(ui.str(R.string.tabs_close_all), destructive = true) {
            val message = ui.str(if (privateGroup) R.string.tabs_close_all_private_confirm else R.string.tabs_close_all_confirm, count)
            host.confirm(message, ui.str(R.string.tabs_close_all), destructive = true) { closeGroup(null) }
        })
    }

    private fun openMore(anchor: View, items: List<MoreAction>) {
        ui.popupList(anchor, items.map { it.label }, ui.dp(200), color = { if (items[it].destructive) palette.danger else palette.ink }) {
            items[it].run()
        }
    }
}

/**
 * One row of a tab list: site badge, title and [detail], then [actions]. Tapping runs [onClick]; a
 * sideways swipe runs [onSwipe], with the actions as the accessible way to do the same.
 */
internal fun BrowserUi.tabLine(title: String, url: String, icon: Bitmap?, detail: String, description: String, selected: Boolean,
                               actions: List<View>, onSwipe: () -> Unit, onClick: (View) -> Unit): View {
    val line = asButton(row()).apply {
        minimumHeight = dp(64)
        setPadding(dp(8), dp(4), dp(4), dp(4))
        background = if (selected) pressable(palette.selectedSurface, 16) else selectable(16)
        isSelected = selected
        contentDescription = description
        setOnClickListener(onClick)
    }
    line.addView(siteBadge(title, url, icon, 36, 13f, if (selected) palette.accent else palette.muted), box(36, 36).apply { rightMargin = dp(10) })
    val info = column()
    info.addView(singleLine(label(title, 15f, if (selected) palette.accent else palette.ink)))
    info.addView(singleLine(label(detail, 12f, palette.muted)))
    line.addView(info, weighted())
    actions.forEach { line.addView(it, tappable()) }
    return SwipeRow(context) { onSwipe() }.apply { addView(line, wide()) }
}

/** Saved icons for ordinary [urls], handed to [loaded] if [sheet] is still open and any were found. */
internal fun BrowserActivity.loadStoredIcons(sheet: BrowserSheet, urls: List<String>, loaded: (Map<String, Bitmap>) -> Unit) {
    if (urls.isEmpty()) return
    lifecycleScope.launch {
        val icons = withContext(store.io) { store.icons(urls) }
        if (sheet.isShowing && icons.isNotEmpty()) loaded(icons)
    }
}
