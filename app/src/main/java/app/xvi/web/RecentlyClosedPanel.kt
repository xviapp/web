package app.xvi.web

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.View
import android.widget.ScrollView

/** Recently closed tabs of one mode; tap reopens, a sideways swipe or the close button forgets one. */
internal class RecentlyClosedPanel(private val host: BrowserActivity, private val private: Boolean) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.tabs_recently_closed_title)
    private val list = ui.column()
    private val storedIcons = mutableMapOf<String, Bitmap>()

    init {
        sheet.add(ScrollView(host).apply { addView(list) }, ui.stretch())
        sheet.add(ui.textAction(ui.str(R.string.recently_closed_clear)) {
            host.haptic()
            host.recentlyClosed.removeAll { it.tab.isPrivate == private }
            host.persist()
            sheet.dismiss()
        }.apply { setTextColor(palette.danger) }, ui.wide().apply { topMargin = ui.dp(8) })
    }

    private val entries get() = host.recentlyClosed.filter { it.tab.isPrivate == private }

    fun show() {
        render()
        sheet.show()
        if (!private) host.loadStoredIcons(sheet, entries.map { it.tab.url }) { storedIcons.putAll(it); render() }
    }

    private fun render() {
        list.removeAllViews()
        entries.forEach { list.addView(row(it), ui.wide().apply { bottomMargin = ui.dp(4) }) }
    }

    private fun forget(entry: ClosedTab) {
        host.haptic()
        host.recentlyClosed.remove(entry)
        host.persist()
        if (entries.isEmpty()) sheet.dismiss() else render()
    }

    private fun row(entry: ClosedTab): View {
        val tab = entry.tab
        val title = host.displayTitle(tab)
        val time = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val detail = "${host.domain(tab.url)} · $time"
        val remove = ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.recently_closed_remove, title), palette.muted) { forget(entry) }
        return ui.tabLine(title, tab.url, BrowserStore.siteKey(tab.url)?.let(storedIcons::get), detail,
            ui.str(R.string.tab_description, title, detail), selected = false, actions = listOf(remove), onSwipe = { forget(entry) }) {
            host.haptic(it)
            sheet.dismiss()
            host.reopen(entry)
        }
    }
}
