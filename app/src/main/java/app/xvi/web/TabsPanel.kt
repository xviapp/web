package app.xvi.web

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab list for the current mode, with the ghost button switching between ordinary and private.
 * Opens scrolled to the current tab; rows close with a sideways swipe or their close button.
 */
internal class TabsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private var privateGroup = host.isPrivateMode
    private lateinit var modeButton: ImageButton
    private val sheet = host.sheet(R.string.tabs_title) { sheet ->
        ui.iconAction(R.drawable.ic_ghost_24, ui.str(R.string.tabs_switch_private)) {
            host.haptic()
            sheet.dismiss()
            host.switchGroup(!host.isPrivateMode)
            TabsPanel(host).show()
        }.also { modeButton = it }
    }
    private val summary = ui.label("", 12f, palette.muted)
    private val list = ui.column()
    private val scroll = ScrollView(host).apply { addView(list) }
    private val actions = ui.column().apply { setPadding(0, ui.dp(8), 0, 0) }
    private val storedIcons = mutableMapOf<String, Bitmap>()

    init {
        sheet.add(summary)
        sheet.add(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
        modeButton.imageTintList = android.content.res.ColorStateList.valueOf(if (privateGroup) palette.accent else palette.muted)
        modeButton.isSelected = privateGroup
        modeButton.contentDescription = ui.str(if (privateGroup) R.string.tabs_switch_ordinary else R.string.tabs_switch_private)
        modeButton.tooltipText = modeButton.contentDescription
        summary.text = ui.str(if (privateGroup) R.string.tabs_summary_private else R.string.tabs_summary, count)
        list.removeAllViews()
        host.tabs.forEach { item ->
            if (item.isPrivate == privateGroup) list.addView(row(item), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
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
        host.tabs.indexOf(item).takeIf { it >= 0 }?.let { host.closeTab(it) }
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
            LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(10) })
        val info = ui.column()
        info.addView(ui.singleLine(ui.label(title, 15f, if (current) palette.accent else palette.ink)))
        val status = when {
            item.keepAlive -> ui.str(R.string.tab_status_kept, host.domain(item.url))
            item.view == null && item.url.isNotEmpty() -> ui.str(R.string.tab_status_paused)
            item.url.isEmpty() -> ui.str(R.string.tab_new)
            else -> host.domain(item.url)
        }
        info.addView(ui.singleLine(ui.label(status, 12f, palette.muted)))
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        line.contentDescription = ui.str(if (current) R.string.tab_description_current else R.string.tab_description, title, status)
        line.addView(ui.iconAction(R.drawable.ic_pin_24, ui.str(if (item.keepAlive) R.string.tab_keep_off else R.string.tab_keep_on),
            if (item.keepAlive) palette.accent else palette.muted) {
            host.haptic()
            item.keepAlive = !item.keepAlive
            host.persist()
            render()
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        line.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.tab_close_named, title)) { close(item) },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        return SwipeRow(host) { close(item) }.apply {
            tag = item
            addView(line, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun renderActions(count: Int) {
        actions.removeAllViews()
        val primary = ui.row()
        primary.addView(ui.textAction(ui.str(R.string.tabs_new), true) { sheet.dismiss(); host.addTab() }, LinearLayout.LayoutParams(0, -2, 1f))
        val closed = host.tabManager.lastClosed?.takeIf { it.private == privateGroup }
        if (closed != null) {
            val label = if (closed.items.size > 1) ui.str(R.string.tabs_undo_many, closed.items.size) else ui.str(R.string.tabs_undo)
            primary.addView(ui.textAction(label) { host.undoCloseTabs() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
        }
        actions.addView(primary, LinearLayout.LayoutParams(-1, -2))
        val reopenable = host.recentlyClosed.filter { it.tab.isPrivate == privateGroup }
        if (reopenable.isNotEmpty()) {
            actions.addView(ui.textAction(ui.str(R.string.tabs_recently_closed, reopenable.size)) { sheet.dismiss(); RecentlyClosedPanel(host, privateGroup).show() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        if (count < 1) return
        val secondary = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        if (count > 1) secondary.addView(ui.textAction(ui.str(R.string.tabs_close_others)) { host.closeGroup(privateGroup, host.tab) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
        secondary.addView(ui.textAction(ui.str(R.string.tabs_close_all)) {
            val message = ui.str(if (privateGroup) R.string.tabs_close_all_private_confirm else R.string.tabs_close_all_confirm, count)
            host.confirm(message, ui.str(R.string.tabs_close_all), destructive = true) { host.closeGroup(privateGroup) }
        }.apply { setTextColor(palette.danger) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(secondary, LinearLayout.LayoutParams(-1, -2))
    }
}
