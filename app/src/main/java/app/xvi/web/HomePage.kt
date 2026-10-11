package app.xvi.web

import android.graphics.Bitmap
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native start page. Its search field is a real input, so focusing it never moves the address bar. */
internal class HomePage(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private var loading: Job? = null
    private val actions = LibraryActions(host, { message, action, undo -> host.showSnackbar(message, action, undo) }) { host.refreshHome() }

    /** Plain text rather than a URL field: most start-page input is a search, often in Chinese. */
    val input: EditText = ui.edit("").apply {
        background = null
        setPadding(ui.dp(8), 0, ui.dp(4), 0)
        imeOptions = EditorInfo.IME_ACTION_GO
        inputType = InputType.TYPE_CLASS_TEXT
    }
    private val clear = ui.iconAction(R.drawable.ic_cancel_24, ui.str(R.string.address_clear), palette.muted) { input.text.clear() }.apply { visibility = View.GONE }
    private val scan = ui.iconAction(R.drawable.ic_qr_24, ui.str(R.string.scan_title), palette.muted) { host.hideSearch(); host.scan() }
    private val capsule = ui.row().apply {
        background = ui.rounded(palette.surface, 18)
        minimumHeight = ui.dp(58)
        setPadding(ui.dp(16), 0, ui.dp(4), 0)
        addView(ImageView(host).apply {
            setImageDrawable(ui.tintedIcon(R.drawable.ic_search_24, palette.muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, ui.box(24, 24))
        addView(input, ui.weighted())
        addView(clear, ui.tappable())
        addView(scan, ui.tappable())
    }
    val proxyButton = ui.iconAction(R.drawable.ic_star_24, ui.str(R.string.proxy_title)) { host.showProxy() }

    init {
        input.minimumHeight = ui.dp(58)
        ui.onTextChanged(input) { syncActions() }
    }

    /** Clear and scan share one slot: clear while editing typed text, scan while the field is empty. */
    fun syncActions() {
        val empty = input.text.isEmpty()
        clear.isVisible = !empty && input.hasFocus()
        scan.isVisible = empty
    }

    /**
     * The start page's layout, built once. [render] reuses it and only updates the parts that actually
     * change, so returning to the start page no longer throws away and rebuilds the search field.
     */
    private val scroll by lazy { ScrollView(host).apply { isFillViewport = true } }
    private val page by lazy {
        ui.column().apply { setPadding(ui.dp(24), ui.dp(36), ui.dp(24), ui.dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
    }
    private val wordmark by lazy {
        ui.medium(ui.label("", 38f)).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(70)
            setPadding(ui.dp(48), 0, ui.dp(48), 0)
            markHeading()
        }
    }
    /** The start page's own toolbar row, holding the continue card of the page left behind. */
    private val startActions by lazy { ui.column() }
    private val sections by lazy { ui.column() }
    private var built = false

    /** Bottom of the search field within [container], for placing suggestions below it. */
    fun inputBottomIn(container: View): Int {
        val field = IntArray(2).also(capsule::getLocationOnScreen)
        val origin = IntArray(2).also(container::getLocationOnScreen)
        return field[1] - origin[1] + capsule.height + ui.dp(6)
    }

    fun render(): View {
        build()
        val private = host.isPrivateMode
        wordmark.text = ui.str(if (private) R.string.private_title else R.string.app_name)
        if (!input.hasFocus()) input.setText("")
        syncActions()
        input.hint = host.searchOverlay.hint(private)
        host.applyInputPrivacy(input, private)
        renderContinue(private)
        refreshSections(private)
        return scroll
    }

    /** Builds the tree once; later renders only update it, so the search field keeps its identity. */
    private fun build() {
        if (built) return
        built = true
        val header = FrameLayout(host)
        header.addView(wordmark, ui.wide())
        header.addView(proxyButton, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.END))
        page.addView(header, ui.wide())
        page.addView(capsule, ui.wide().apply { topMargin = ui.dp(20); bottomMargin = ui.dp(20) })
        page.addView(startActions, ui.wide())
        page.addView(sections, ui.wide())
        scroll.addView(page)
    }

    /** The page left behind, as a card that goes back to it. */
    private fun renderContinue(private: Boolean) {
        startActions.removeAllViews()
        val current = host.tab
        if (private || current == null || !BrowserInput.isWebUrl(current.url)) return
        val title = current.title.ifBlank { host.domain(current.url) }
        val card = ui.menuRow(R.drawable.ic_play_24, ui.str(R.string.home_continue), title) { host.showTab() }
        card.view.background = ui.pressable(palette.surface, 16)
        card.view.setPadding(ui.dp(4), ui.dp(4), ui.dp(12), ui.dp(4))
        card.view.contentDescription = ui.str(R.string.home_continue_description, title)
        startActions.addView(card.view, ui.wide().apply { bottomMargin = ui.dp(8) })
    }

    private fun refreshSections(private: Boolean) {
        loading?.cancel()
        loading = host.lifecycleScope.launch {
            val store = host.store
            val (bookmarks, bookmarkCount, recent, icons) = withContext(store.io) {
                val bookmarks = store.entries(true, limit = HomeBookmarkPresentation.MAX_VISIBLE + 1)
                val count = if (bookmarks.size > HomeBookmarkPresentation.MAX_VISIBLE) store.count(true) else bookmarks.size
                val recent = if (private) emptyList() else store.recentSites(5)
                Sections(bookmarks, count, recent, store.icons(bookmarks.map { it.url } + recent.map { it.url }))
            }
            // The column is reused across renders, so the previous rows go before the new ones arrive.
            sections.removeAllViews()
            renderSections(sections, bookmarks, bookmarkCount, recent, icons)
        }
    }

    private data class Sections(val bookmarks: List<PageEntry>, val count: Int, val recent: List<PageEntry>, val icons: Map<String, Bitmap>)

    private fun renderSections(sections: LinearLayout, loaded: List<PageEntry>, bookmarkCount: Int, recent: List<PageEntry>, icons: Map<String, Bitmap>) {
        val bookmarks = loaded.toMutableList()
        if (bookmarks.isNotEmpty()) {
            sections.addView(ui.sectionTitle(ui.str(R.string.bookmarks_title)), ui.wide())
            val grid = ui.column()
            val toggles = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            sections.addView(grid, ui.wide())
            sections.addView(toggles, ui.wide())
            var expanded = false
            lateinit var drag: DragReorder
            fun visibleCount() = HomeBookmarkPresentation.visibleCount(bookmarkCount, expanded)
            fun renderGrid() {
                grid.removeAllViews()
                val visible = bookmarks.take(visibleCount())
                visible.chunked(HomeBookmarkPresentation.COLUMNS).forEachIndexed { row, group ->
                    val strip = ui.row().apply { gravity = Gravity.TOP }
                    group.forEachIndexed { column, entry ->
                        val index = row * HomeBookmarkPresentation.COLUMNS + column
                        val cell = shortcut(entry, icons[BrowserStore.siteKey(entry.url)]) { drag.start(it, index) }
                        cell.alpha = if (index == drag.dragging) 0f else 1f
                        strip.addView(cell, ui.weighted())
                    }
                    repeat(HomeBookmarkPresentation.COLUMNS - group.size) { strip.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
                    grid.addView(strip, ui.wide())
                }
                toggles.removeAllViews()
                toggles.isVisible = HomeBookmarkPresentation.showsToggle(bookmarkCount)
                if (toggles.isVisible) {
                    toggles.addView(ui.textAction(ui.str(if (expanded) R.string.action_collapse else R.string.action_expand)) {
                        expanded = !expanded
                        renderGrid()
                    }, ui.weighted())
                    if (HomeBookmarkPresentation.showsMore(bookmarkCount, expanded)) {
                        toggles.addView(ui.textAction(ui.str(R.string.action_more)) { LibraryPanel(host, true).show() },
                            ui.weighted().apply { leftMargin = ui.dp(8) })
                    }
                }
            }
            drag = DragReorder(host, grid, slotAt = { x, y ->
                val row = (0 until grid.childCount).firstOrNull { y >= grid.getChildAt(it).top && y < grid.getChildAt(it).bottom } ?: -1
                val column = (x * HomeBookmarkPresentation.COLUMNS / grid.width.coerceAtLeast(1)).toInt().coerceIn(0, HomeBookmarkPresentation.COLUMNS - 1)
                (row * HomeBookmarkPresentation.COLUMNS + column).takeIf { row >= 0 && it < visibleCount() } ?: -1
            }, visible = { 0 until visibleCount() }, viewAt = { index ->
                (grid.getChildAt(index / HomeBookmarkPresentation.COLUMNS) as? ViewGroup)?.getChildAt(index % HomeBookmarkPresentation.COLUMNS)
            }, move = { from, to -> bookmarks.add(to, bookmarks.removeAt(from)) }, redraw = ::renderGrid) { index, moved ->
                val entry = bookmarks.getOrNull(index) ?: return@DragReorder
                if (!moved) return@DragReorder actions.bookmark(entry)
                val order = bookmarks.map { it.url }
                host.store.writeAsync { reorderBookmarks(order) }
            }
            renderGrid()
        }
        if (recent.isNotEmpty()) {
            sections.addView(ui.sectionTitle(ui.str(R.string.home_recent)), ui.wide())
            recent.forEach { entry ->
                val item = ui.menuRow(R.drawable.ic_history_24, entry.title, host.domain(entry.url), palette.muted) { host.navigate(entry.url) }
                icons[BrowserStore.siteKey(entry.url)]?.let { item.setSiteIcon(it, ui.dp(12)) }
                item.view.contentDescription = ui.str(R.string.home_recent_description, entry.title, host.domain(entry.url))
                item.view.setOnLongClickListener { host.haptic(it); actions.site(entry); true }
                sections.addView(item.view, ui.wide())
            }
        }
    }

    private fun shortcut(entry: PageEntry, icon: Bitmap?, hold: (View) -> Unit): View = ui.asButton(ui.column()).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(8))
        background = ui.selectable(16)
        addView(ui.siteBadge(entry.title, entry.url, icon, 52, 23f, radius = 19), ui.box(52, 52))
        addView(ui.singleLine(ui.label(entry.title, 12f, palette.muted)).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) },
            ui.wide())
        contentDescription = ui.str(R.string.home_bookmark_description, entry.title)
        setOnClickListener { host.navigate(entry.url) }
        setOnLongClickListener { hold(it); true }
    }
}
