package app.xvi.web

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recently closed tabs of one mode; tap reopens, a sideways swipe or the close button forgets one. */
internal class RecentlyClosedPanel(private val host: BrowserActivity, private val private: Boolean) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.tabs_recently_closed_title)
    private val list = ui.column()
    private val storedIcons = mutableMapOf<String, Bitmap>()

    init {
        sheet.add(ScrollView(host).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.add(ui.textAction(ui.str(R.string.recently_closed_clear)) {
            host.haptic()
            host.recentlyClosed.removeAll { it.tab.isPrivate == private }
            host.persist()
            sheet.dismiss()
        }.apply { setTextColor(palette.danger) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
    }

    private val entries get() = host.recentlyClosed.filter { it.tab.isPrivate == private }

    fun show() {
        render()
        sheet.show()
        if (private) return
        val urls = entries.map { it.tab.url }
        if (urls.isNotEmpty()) host.lifecycleScope.launch {
            val icons = withContext(host.store.io) { host.store.icons(urls) }
            if (sheet.isShowing && icons.isNotEmpty()) { storedIcons.putAll(icons); render() }
        }
    }

    private fun render() {
        list.removeAllViews()
        entries.forEach { list.addView(row(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) }) }
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
        val line = ui.asButton(ui.row()).apply {
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4))
            background = ui.selectable(16)
            contentDescription = ui.str(R.string.tab_description, title, detail)
            setOnClickListener {
                host.haptic(it)
                sheet.dismiss()
                host.reopen(entry)
            }
        }
        line.addView(ui.siteBadge(title, tab.url, BrowserStore.siteKey(tab.url)?.let(storedIcons::get), 36, 13f, palette.muted),
            LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(10) })
        val info = ui.column()
        info.addView(ui.singleLine(ui.label(title, 15f)))
        info.addView(ui.singleLine(ui.label(detail, 12f, palette.muted)))
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.recently_closed_remove, title), palette.muted) { forget(entry) },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        return SwipeRow(host) { forget(entry) }.apply { addView(line, android.widget.FrameLayout.LayoutParams(-1, -2)) }
    }
}
