package app.xvi.web

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.text.InputType
import android.view.Gravity
import android.view.View
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
    private val cells = ShortcutCells(host)
    /** The start page's own shortcuts, while there are any. */
    private var home: ShortcutGrid? = null
    private val popups: FolderPopups = FolderPopups(host, cells, { drag }, ::menu)
    private val drag: ShortcutDrag = ShortcutDrag(host, layers = { listOfNotNull(home) + popups.layers },
        openFolder = { folder, ready -> popups.show(folder, ready) }, closeLayer = popups::close, menu = ::menu, saved = { host.refreshHome() })

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
    /** Opens the proxy settings; its tint shows the route traffic takes. */
    private val proxyButton = ui.iconAction(R.drawable.ic_star_24, ui.str(R.string.proxy_title)) {
        ProxyPanel(host).show()
    }
    private var proxyButtonRoute: BrowserProxyNetwork.Route? = null

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
            val (shortcuts, bookmarkCount, recent, icons) = withContext(store.io) {
                // The top level of the bookmarks panel: folders among the bookmarks outside them.
                val shortcuts = store.children(BrowserStore.ROOT_FOLDER, HomeBookmarkPresentation.MAX_VISIBLE + 1)
                val count = if (shortcuts.size > HomeBookmarkPresentation.MAX_VISIBLE) store.childCount(BrowserStore.ROOT_FOLDER) else shortcuts.size
                val recent = if (private) emptyList() else store.recentSites(5)
                Sections(shortcuts, count, recent, store.icons(shortcuts.filterIsInstance<PageEntry>().map { it.url } + recent.map { it.url }))
            }
            // The column is reused across renders, so the previous rows go before the new ones arrive.
            sections.removeAllViews()
            renderSections(sections, shortcuts, bookmarkCount, recent, icons)
            popups.refresh()
        }
    }

    /** [shortcuts] are the bookmarks' top level, folders among bookmarks; [count] is how many there are in all. */
    private data class Sections(val shortcuts: List<Any>, val count: Int, val recent: List<PageEntry>, val icons: Map<String, Bitmap>)

    private fun renderSections(sections: LinearLayout, loaded: List<Any>, bookmarkCount: Int, recent: List<PageEntry>, icons: Map<String, Bitmap>) {
        if (loaded.isNotEmpty()) {
            sections.addView(ui.sectionTitle(ui.str(R.string.bookmarks_title)), ui.wide())
            val toggles = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            var expanded = false
            val shortcuts = object : ShortcutGrid(BrowserStore.ROOT_FOLDER) {
                override val grid = ui.column()
                /** Only some load; one dragged out of a folder adds to them. */
                fun total() = bookmarkCount + items.size - loaded.size
                override fun shown() = HomeBookmarkPresentation.visibleCount(total(), expanded).coerceAtMost(items.size)
                override fun redraw() {
                    cells.fill(grid, items.take(shown()), icons, drag.dragged, tap = ::open) { view, index -> drag.start(view, this, index) }
                    toggles.removeAllViews()
                    toggles.isVisible = HomeBookmarkPresentation.showsToggle(total())
                    if (toggles.isVisible) {
                        toggles.addView(ui.textAction(ui.str(if (expanded) R.string.action_collapse else R.string.action_expand)) {
                            expanded = !expanded
                            redraw()
                        }, ui.weighted())
                        if (HomeBookmarkPresentation.showsMore(total(), expanded)) {
                            toggles.addView(ui.textAction(ui.str(R.string.action_more)) { LibraryPanel(host, true).show() },
                                ui.weighted().apply { leftMargin = ui.dp(8) })
                        }
                    }
                }
            }
            shortcuts.items.addAll(loaded)
            home = shortcuts
            sections.addView(shortcuts.grid, ui.wide())
            sections.addView(toggles, ui.wide())
            shortcuts.redraw()
        } else home = null
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

    /** A tapped shortcut: a bookmark opens, a folder opens in the middle of the screen. */
    private fun open(item: Any) {
        when (item) {
            is PageEntry -> host.navigate(item.url)
            is BookmarkFolder -> popups.show(item)
        }
    }

    /** What a long press without a drag offers. */
    private fun menu(item: Any) {
        when (item) {
            is PageEntry -> actions.bookmark(item)
            is BookmarkFolder -> actions.folder(item) { popups.show(item) }
        }
    }

    fun updateProxy() {
        if (proxyButtonRoute == host.proxyRoute) return
        proxyButtonRoute = host.proxyRoute
        proxyButton.imageTintList = ColorStateList.valueOf(palette.proxy(host.proxyRoute))
        proxyButton.describe(ui.str(R.string.proxy_button_description, host.proxyLabel()))
    }

    /** Closes any folder open over the start page, as when the page is left. */
    fun closeFolders() = popups.closeAll()
}
