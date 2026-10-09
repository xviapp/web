package app.xvi.web

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView

/** Tab list for the current mode, with the ghost button switching between ordinary and private. */
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
    private val actions = ui.column().apply { setPadding(0, ui.dp(8), 0, 0) }
    private val storedIcons = mutableMapOf<String, Bitmap>()

    init {
        sheet.add(summary)
        sheet.add(ScrollView(host).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.add(actions)
    }

    fun show() {
        host.tabsPanel?.dismiss()
        host.tabsPanel = this
        sheet.onDismiss { if (host.tabsPanel === this) host.tabsPanel = null }
        render()
        sheet.show()
        // Restored tabs have no live icon yet; use the ones saved for ordinary sites.
        val urls = host.tabs.filter { !it.isPrivate && it.favicon == null && it.url.isNotEmpty() }.map { it.url }
        if (urls.isNotEmpty()) host.io.execute {
            val icons = host.store.icons(urls)
            host.runOnUiThread { if (sheet.isShowing && icons.isNotEmpty()) { storedIcons.putAll(icons); render() } }
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
        host.tabs.forEachIndexed { index, item ->
            if (item.isPrivate == privateGroup) list.addView(row(index, item), LinearLayout.LayoutParams(-1, ui.dp(66)).apply { bottomMargin = ui.dp(4) })
        }
        renderActions(count)
    }

    private fun row(index: Int, item: BrowserTab): View {
        val current = item === host.tab
        val title = host.displayTitle(item)
        val line = ui.asButton(ui.row()).apply {
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
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        line.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.tab_close_named, title)) {
            host.haptic()
            host.closeTab(index)
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        return line
    }

    private fun renderActions(count: Int) {
        actions.removeAllViews()
        val primary = ui.row()
        primary.addView(ui.textAction(ui.str(R.string.tabs_new), true) { sheet.dismiss(); host.addTab() }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        val closed = host.tabManager.lastClosed?.takeIf { it.private == privateGroup }
        if (closed != null) {
            val label = if (closed.items.size > 1) ui.str(R.string.tabs_undo_many, closed.items.size) else ui.str(R.string.tabs_undo)
            primary.addView(ui.textAction(label) { host.undoCloseTabs() }, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { leftMargin = ui.dp(8) })
        }
        actions.addView(primary, LinearLayout.LayoutParams(-1, -2))
        if (count < 1) return
        val secondary = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        if (count > 1) secondary.addView(ui.textAction(ui.str(R.string.tabs_close_others)) { host.closeGroup(privateGroup, host.tab) },
            LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { rightMargin = ui.dp(8) })
        secondary.addView(ui.textAction(ui.str(R.string.tabs_close_all)) {
            val message = ui.str(if (privateGroup) R.string.tabs_close_all_private_confirm else R.string.tabs_close_all_confirm, count)
            host.confirm(message, ui.str(R.string.tabs_close_all), destructive = true) { host.closeGroup(privateGroup) }
        }.apply { setTextColor(palette.danger) }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        actions.addView(secondary, LinearLayout.LayoutParams(-1, -2))
    }
}
