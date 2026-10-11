package app.xvi.web

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
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
            gravity = android.view.Gravity.CENTER
            minimumHeight = ui.dp(36)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            contentDescription = ui.str(if (private) R.string.tabs_switch_private else R.string.tabs_switch_ordinary)
            setOnClickListener {
                if (private == privateGroup) return@setOnClickListener
                host.haptic()
                sheet.dismiss()
                host.switchGroup(private)
                TabsPanel(host).show()
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
        val urls = host.tabs.filter { !it.isPrivate && it.favicon == null && it.url.isNotEmpty() }.map { it.url }
        if (urls.isNotEmpty()) host.lifecycleScope.launch {
            val icons = withContext(host.store.io) { host.store.icons(urls) }
            if (sheet.isShowing && icons.isNotEmpty()) { storedIcons.putAll(icons); render() }
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
        val line = ui.asButton(ui.row()).apply {
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4))
            background = if (current) ui.pressable(palette.selectedSurface, 16) else ui.selectable(16)
            isSelected = current
            setOnClickListener {
                host.haptic(it)
                host.selectTab(item)
                sheet.dismiss()
            }
        }
        val icon = item.favicon ?: BrowserStore.siteKey(item.url)?.let(storedIcons::get)
        line.addView(ui.siteBadge(title, item.url, icon, 36, 13f, if (current) palette.accent else palette.muted),
            ui.box(36, 36).apply { rightMargin = ui.dp(10) })
        val info = ui.column()
        info.addView(ui.singleLine(ui.label(title, 15f, if (current) palette.accent else palette.ink)))
        val status = when {
            item.keepAlive -> ui.str(R.string.tab_status_kept, host.domain(item.url))
            item.view == null && item.url.isNotEmpty() -> ui.str(R.string.tab_status_paused)
            item.url.isEmpty() -> ui.str(R.string.tab_new)
            else -> host.domain(item.url)
        }
        info.addView(ui.singleLine(ui.label(status, 12f, palette.muted)))
        line.addView(info, ui.weighted())
        line.contentDescription = ui.str(if (current) R.string.tab_description_current else R.string.tab_description, title, status)
        line.addView(ui.iconAction(R.drawable.ic_pin_24, ui.str(if (item.keepAlive) R.string.tab_keep_off else R.string.tab_keep_on),
            if (item.keepAlive) palette.accent else palette.muted) {
            host.haptic()
            item.keepAlive = !item.keepAlive
            host.persist()
            render()
        }, ui.tappable())
        line.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.tab_close_named, title)) { close(item) },
            ui.tappable())
        return SwipeRow(host) { close(item) }.apply {
            tag = item
            addView(line, ui.wide())
        }
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
        ListPopupWindow(host).apply {
            anchorView = anchor
            isModal = true
            width = ui.dp(200)
            horizontalOffset = anchor.width - ui.dp(200)
            setBackgroundDrawable(ui.pressable(palette.surface, 12))
            setAdapter(object : ArrayAdapter<MoreAction>(host, android.R.layout.simple_list_item_1, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? TextView ?: ui.singleLine(ui.label("", 15f))).apply {
                        val item = items[position]
                        text = item.label
                        setTextColor(if (item.destructive) palette.danger else palette.ink)
                        minimumHeight = ui.dp(48)
                        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                        background = ui.selectable()
                    }
            })
            setOnItemClickListener { _, _, position, _ -> dismiss(); items[position].run() }
            show()
        }
    }
}
