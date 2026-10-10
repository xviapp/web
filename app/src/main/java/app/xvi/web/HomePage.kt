package app.xvi.web

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Native start page. Its search field is a real input, so focusing it never moves the address bar. */
internal class HomePage(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private var generation = 0L

    val input: EditText = ui.edit("").apply {
        background = null
        setPadding(ui.dp(8), 0, ui.dp(4), 0)
        imeOptions = EditorInfo.IME_ACTION_GO
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    private val clear = ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.address_clear), palette.muted) { input.text.clear() }.apply { visibility = View.GONE }
    private val capsule = ui.row().apply {
        background = ui.rounded(palette.surface, 18)
        minimumHeight = ui.dp(58)
        setPadding(ui.dp(16), 0, ui.dp(4), 0)
        addView(ImageView(host).apply {
            setImageDrawable(ui.tintedIcon(R.drawable.ic_search_24, palette.muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        addView(input, LinearLayout.LayoutParams(0, ui.dp(58), 1f))
        addView(clear, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    }
    val proxyButton = ui.iconAction(R.drawable.ic_star_24, ui.str(R.string.proxy_title)) { host.showProxy() }

    init { ui.onTextChanged(input) { clear.visibility = if (it.isEmpty() || !input.hasFocus()) View.GONE else View.VISIBLE } }

    /** Bottom of the search field within [container], for placing suggestions below it. */
    fun inputBottomIn(container: View): Int {
        val field = IntArray(2).also(capsule::getLocationOnScreen)
        val origin = IntArray(2).also(container::getLocationOnScreen)
        return field[1] - origin[1] + capsule.height + ui.dp(6)
    }

    fun render(): View {
        val token = ++generation
        val private = host.isPrivateMode
        val scroll = ScrollView(host).apply { isFillViewport = true }
        val page = ui.column().apply { setPadding(ui.dp(24), ui.dp(36), ui.dp(24), ui.dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val header = FrameLayout(host)
        header.addView(ui.medium(ui.label(if (private) ui.str(R.string.private_title) else ui.str(R.string.app_name), 38f)).apply {
            gravity = Gravity.CENTER
            accessibilityHeading()
        }, FrameLayout.LayoutParams(-1, ui.dp(70)))
        (proxyButton.parent as? ViewGroup)?.removeView(proxyButton)
        header.addView(proxyButton, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.END))
        page.addView(header, LinearLayout.LayoutParams(-1, ui.dp(70)))
        page.addView(ui.label(ui.str(R.string.home_slogan_zh), 14f, palette.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, ui.dp(26)))
        page.addView(ui.label(ui.str(R.string.home_slogan_en), 12f, palette.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, ui.dp(22)))

        (capsule.parent as? ViewGroup)?.removeView(capsule)
        if (!input.hasFocus()) input.setText("")
        input.hint = host.searchOverlay.hint(private)
        host.applyInputPrivacy(input, private)
        page.addView(capsule, LinearLayout.LayoutParams(-1, ui.dp(58)).apply { topMargin = ui.dp(28); bottomMargin = ui.dp(20) })

        val current = host.tab
        if (!private && current != null && BrowserInput.isWebUrl(current.url)) {
            val title = current.title.ifBlank { host.domain(current.url) }
            val card = ui.menuRow(R.drawable.ic_continue_24, ui.str(R.string.home_continue), title) { host.showTab() }
            card.view.background = ui.pressable(palette.surface, 16)
            card.view.setPadding(ui.dp(4), ui.dp(4), ui.dp(12), ui.dp(4))
            card.view.contentDescription = ui.str(R.string.home_continue_description, title)
            page.addView(card.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }

        val sections = ui.column()
        page.addView(sections, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(page)

        host.io.execute {
            val store = host.store
            val bookmarks = store.entries(true, limit = HomeBookmarkPresentation.MAX_VISIBLE + 1)
            val bookmarkCount = if (bookmarks.size > HomeBookmarkPresentation.MAX_VISIBLE) store.count(true) else bookmarks.size
            val recent = if (private) emptyList() else store.recentSites(5)
            val icons = store.icons(bookmarks.map { it.url } + recent.map { it.url })
            host.runOnUiThread {
                if (!host.isDestroyed && token == generation) renderSections(sections, bookmarks, bookmarkCount, recent, icons)
            }
        }
        return scroll
    }

    private fun renderSections(sections: LinearLayout, bookmarks: List<PageEntry>, bookmarkCount: Int, recent: List<PageEntry>, icons: Map<String, Bitmap>) {
        val shortcuts = bookmarks.ifEmpty { if (recent.isEmpty()) defaultShortcuts() else emptyList() }
        if (shortcuts.isNotEmpty()) {
            sections.addView(ui.sectionTitle(ui.str(if (bookmarks.isNotEmpty()) R.string.bookmarks_title else R.string.home_shortcuts)), LinearLayout.LayoutParams(-1, -2))
            val grid = ui.column()
            val actions = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            sections.addView(grid, LinearLayout.LayoutParams(-1, -2))
            sections.addView(actions, LinearLayout.LayoutParams(-1, -2))
            var expanded = false
            fun renderGrid() {
                grid.removeAllViews()
                val visible = if (bookmarks.isEmpty()) shortcuts else bookmarks.take(HomeBookmarkPresentation.visibleCount(bookmarkCount, expanded))
                visible.chunked(HomeBookmarkPresentation.COLUMNS).forEach { group ->
                    val strip = ui.row()
                    group.forEach { entry -> strip.addView(shortcut(entry, icons[BrowserStore.siteKey(entry.url)]), LinearLayout.LayoutParams(0, -2, 1f)) }
                    repeat(HomeBookmarkPresentation.COLUMNS - group.size) { strip.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
                    grid.addView(strip, LinearLayout.LayoutParams(-1, -2))
                }
                actions.removeAllViews()
                actions.visibility = if (bookmarks.isNotEmpty() && HomeBookmarkPresentation.showsToggle(bookmarkCount)) View.VISIBLE else View.GONE
                if (actions.visibility == View.VISIBLE) {
                    actions.addView(ui.textAction(ui.str(if (expanded) R.string.action_collapse else R.string.action_expand)) {
                        expanded = !expanded
                        renderGrid()
                    }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
                    if (HomeBookmarkPresentation.showsMore(bookmarkCount, expanded)) {
                        actions.addView(ui.textAction(ui.str(R.string.action_more)) { LibraryPanel(host, true).show() },
                            LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { leftMargin = ui.dp(8) })
                    }
                }
            }
            renderGrid()
        }
        if (recent.isNotEmpty()) {
            sections.addView(ui.sectionTitle(ui.str(R.string.home_recent)), LinearLayout.LayoutParams(-1, -2))
            recent.forEach { entry ->
                val item = ui.menuRow(R.drawable.ic_history_24, entry.title, host.domain(entry.url), palette.muted) { host.navigate(entry.url) }
                icons[BrowserStore.siteKey(entry.url)]?.let { icon ->
                    item.icon.setImageBitmap(icon)
                    val inset = ui.dp(12)
                    item.icon.setPadding(inset, inset, inset, inset)
                    item.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                }
                item.view.contentDescription = ui.str(R.string.home_recent_description, entry.title, host.domain(entry.url))
                item.view.setOnLongClickListener {
                    host.haptic(it)
                    host.markDeleteTarget(item.view, true)
                    host.confirmDelete(entry.title, {
                        host.io.execute { host.store.removeSiteHistory(entry.url); host.runOnUiThread { host.refreshHome() } }
                    }, { host.markDeleteTarget(item.view, false) })
                    true
                }
                sections.addView(item.view, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun shortcut(entry: PageEntry, icon: Bitmap?): View = ui.asButton(ui.column()).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(8))
        background = ui.selectable(16)
        addView(ui.siteBadge(entry.title, entry.url, icon, 52, 23f, radius = 19), LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        addView(ui.singleLine(ui.label(entry.title, 12f, palette.muted)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, ui.dp(30)))
        contentDescription = entry.title
        setOnClickListener { host.navigate(entry.url) }
    }

    private fun defaultShortcuts() = listOf(
        PageEntry("https://www.bing.com", ui.str(R.string.shortcut_bing)), PageEntry("https://www.baidu.com", ui.str(R.string.shortcut_baidu)),
        PageEntry("https://www.wikipedia.org", ui.str(R.string.shortcut_wikipedia)), PageEntry("https://github.com", "GitHub")
    )

    private fun View.accessibilityHeading() { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
}
