package app.xvi.web

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * A grid of start-page shortcuts that one drag can pass through: the start page's own, or an open
 * folder's. [items] are bookmarks ([PageEntry]) and folders ([BookmarkFolder]) of [folder], in order.
 */
internal abstract class ShortcutGrid(val folder: Long) {
    val items = mutableListOf<Any>()
    /** Rows of [HomeBookmarkPresentation.COLUMNS] cells each. */
    abstract val grid: LinearLayout
    /** How many of [items] are on screen, from the first. */
    abstract fun shown(): Int
    abstract fun redraw()
    /** Whether a point in the window lies in this grid's own area; the start page's takes every point. */
    open fun covers(x: Float, y: Float) = true
}

/** Builds the cells of a [ShortcutGrid]: a site badge or folder above a short name. */
internal class ShortcutCells(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette

    /**
     * Fills [grid] with [items] in rows; [dragged], whose place the lifted copy takes, stays invisible.
     * [tap] and [hold] get the item and its index.
     */
    fun fill(grid: LinearLayout, items: List<Any>, icons: Map<String, Bitmap>, dragged: Any?, tap: (Any) -> Unit, hold: (View, Int) -> Unit) {
        grid.removeAllViews()
        val columns = HomeBookmarkPresentation.COLUMNS
        items.chunked(columns).forEachIndexed { row, group ->
            val strip = ui.row().apply { gravity = Gravity.TOP }
            group.forEachIndexed { column, item ->
                val index = row * columns + column
                val cell = when (item) {
                    is BookmarkFolder -> folder(item)
                    else -> (item as PageEntry).let { bookmark(it, icons[BrowserStore.siteKey(it.url)]) }
                }
                cell.alpha = if (item === dragged) 0f else 1f
                cell.setOnClickListener { tap(item) }
                cell.setOnLongClickListener { hold(it, index); true }
                strip.addView(cell, ui.weighted())
            }
            repeat(columns - group.size) { strip.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(strip, ui.wide())
        }
    }

    private fun cell(name: String, badge: View) = ui.asButton(ui.column()).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(8))
        background = ui.selectable(16)
        addView(badge, ui.box(52, 52))
        addView(ui.singleLine(ui.label(name, 12f, palette.muted)).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }, ui.wide())
    }

    private fun bookmark(entry: PageEntry, icon: Bitmap?) = cell(entry.title, ui.siteBadge(entry.title, entry.url, icon, 52, 23f, radius = 19)).apply {
        contentDescription = ui.str(R.string.home_bookmark_description, entry.title)
    }

    private fun folder(folder: BookmarkFolder) = cell(folder.name, ImageView(host).apply {
        setImageDrawable(ui.tintedIcon(R.drawable.ic_folder_24, palette.accent))
        scaleType = ImageView.ScaleType.CENTER
        background = ui.rounded(palette.badgeSurface, 19)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }).apply {
        contentDescription = ui.str(R.string.bookmark_folder_description, folder.name, folder.count)
    }
}

/**
 * A start-page folder opened as a card in the middle of the screen, holding only its shortcuts. A
 * folder inside opens another card above; a tap outside or back closes the top one.
 */
internal class FolderPopups(
    private val host: BrowserActivity,
    private val cells: ShortcutCells,
    private val drag: () -> ShortcutDrag,
    private val menu: (Any) -> Unit
) {
    private val ui = host.ui
    private val open = mutableListOf<Popup>()
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { open.lastOrNull()?.let(::close) }
    }
    private var backAdded = false

    /** The open folders' grids, the outermost first. */
    val layers: List<ShortcutGrid> get() = open.map { it.layer }

    /** Opens [folder] above the others; [ready] gets its grid once its shortcuts are in. */
    fun show(folder: BookmarkFolder, ready: (ShortcutGrid) -> Unit = {}) {
        if (!backAdded) { host.onBackPressedDispatcher.addCallback(host, back); backAdded = true }
        val popup = Popup(folder)
        open += popup
        back.isEnabled = true
        popup.load(ready)
    }

    fun close(layer: ShortcutGrid) { open.firstOrNull { it.layer === layer }?.let(::close) }

    fun closeAll() { open.toList().asReversed().forEach(::close) }

    /** Reloads every open folder, as after a change elsewhere; a drag in progress keeps them as they are. */
    fun refresh() { if (drag().dragged == null) open.forEach { it.load {} } }

    private fun close(popup: Popup) {
        open -= popup
        back.isEnabled = open.isNotEmpty()
        popup.dismiss()
    }

    private inner class Popup(private val folder: BookmarkFolder) {
        private val icons = mutableMapOf<String, Bitmap>()
        private val grid = ui.column()
        private val empty = ui.label(ui.str(R.string.bookmark_folder_empty), 14f, ui.palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(24), 0, ui.dp(24))
        }
        // The card never grows past most of the screen; a long folder scrolls inside it.
        private val card = CappedColumn(host, (host.root.height * 0.7f).toInt()).apply {
            background = ui.rounded(ui.palette.surface, 24)
            elevation = ui.dp(12).toFloat()
            setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(12))
            isClickable = true
            contentDescription = ui.str(R.string.bookmark_folder_popup_description, folder.name)
            addView(ui.medium(ui.singleLine(ui.label(folder.name, 16f))).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(10)) }, ui.wide())
            addView(ScrollView(host).apply {
                addView(ui.column().apply { addView(grid, ui.wide()); addView(empty, ui.wide()) })
            }, ui.stretch())
        }
        private val shade = FrameLayout(host).apply {
            setBackgroundColor(Color.argb(102, 0, 0, 0))
            isClickable = true
            setOnClickListener { close(this@Popup) }
        }
        val layer = object : ShortcutGrid(folder.id) {
            override val grid get() = this@Popup.grid
            override fun shown() = items.size
            override fun redraw() = render()
            override fun covers(x: Float, y: Float): Boolean {
                val at = IntArray(2).also(card::getLocationInWindow)
                return x >= at[0] && x < at[0] + card.width && y >= at[1] && y < at[1] + card.height
            }
        }

        init {
            val width = minOf(host.root.width - ui.dp(48), ui.dp(380))
            shade.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            host.root.addView(shade, ui.full())
            shade.alpha = 0f
            card.scaleX = 0.88f; card.scaleY = 0.88f
            shade.animate().alpha(1f).setDuration(150).start()
            card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start()
        }

        fun load(ready: (ShortcutGrid) -> Unit) {
            host.lifecycleScope.launch {
                val store = host.store
                val (items, loaded) = withContext(store.io) {
                    val items = store.children(folder.id)
                    items to store.icons(items.filterIsInstance<PageEntry>().map { it.url })
                }
                icons.putAll(loaded)
                layer.items.clear()
                layer.items.addAll(items)
                render()
                ready(layer)
            }
        }

        private fun render() {
            cells.fill(grid, layer.items, icons, drag().dragged, tap = { item ->
                when (item) {
                    is PageEntry -> { closeAll(); host.navigate(item.url) }
                    is BookmarkFolder -> show(item)
                }
            }, hold = { view, index -> drag().start(view, layer, index) })
            empty.isVisible = layer.items.isEmpty()
        }

        fun dismiss() {
            shade.isClickable = false
            card.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
            shade.animate().alpha(0f).setDuration(120).withEndAction { host.root.removeView(shade) }.start()
        }
    }

    private companion object {
        val EASE = DecelerateInterpolator(1.6f)
    }
}

/**
 * Long-press-and-drag for start-page shortcuts, across the start page and the folders opened over it.
 * The pressed shortcut lifts and follows the finger, and the others slide out of its way. Resting it
 * over a folder's middle marks the folder; letting go there puts it inside, and staying a moment longer
 * shakes the folder and opens it, so the drag goes on among what it holds. Leaving an open folder's card
 * closes it, and the shortcut goes back beside it. Wherever it is let go, it stays.
 *
 * Lifting without moving opens [menu]; screen readers go straight there. [saved] runs after a change
 * is written.
 */
internal class ShortcutDrag(
    private val host: BrowserActivity,
    private val layers: () -> List<ShortcutGrid>,
    private val openFolder: (BookmarkFolder, (ShortcutGrid) -> Unit) -> Unit,
    private val closeLayer: (ShortcutGrid) -> Unit,
    private val menu: (Any) -> Unit,
    private val saved: () -> Unit
) {
    /** The shortcut being dragged, or null. */
    var dragged: Any? = null
        private set
    /** The grid now holding [dragged], and where; null while a folder it is going into opens. */
    private var layer: ShortcutGrid? = null
    private var index = -1
    /** The folder [dragged] came from, and the one it is going into while that opens. */
    private var origin = BrowserStore.ROOT_FOLDER
    private var entering = BrowserStore.ROOT_FOLDER
    private var moved = false
    /**
     * Whether leaving the top folder's card closes it. A folder opened by the drag opens in the middle of
     * the screen, maybe away from the finger, so only after the shortcut has been over its card.
     */
    private var armed = true
    private var lifted: ImageView? = null
    private var originX = 0f
    private var originY = 0f
    private var anchorX = Float.NaN
    private var anchorY = Float.NaN
    /** The folder under the shortcut's middle, by index in [layer], or -1. */
    private var holding = -1
    private val slop = android.view.ViewConfiguration.get(host).scaledTouchSlop
    private val root get() = host.window.decorView as ViewGroup
    private val dwell = Runnable { shake() }

    fun start(view: View, from: ShortcutGrid, at: Int) {
        host.haptic(view, HapticFeedbackConstants.LONG_PRESS)
        val item = from.items.getOrNull(at) ?: return
        val reader = host.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
        if (reader || dragged != null || view.width == 0 || view.height == 0) return menu(item)
        dragged = item
        layer = from
        index = at
        origin = from.folder
        moved = false
        armed = true
        anchorX = Float.NaN; anchorY = Float.NaN
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(true)
        // The rest of the gesture drives the drag, taken before any view sees it, since cells are rebuilt as shortcuts move.
        host.touchInterceptor = { onTouch(it); true }
        lift(view)
        from.redraw()
    }

    /** Copy of the shortcut drawn above everything in the window, slightly enlarged. */
    private fun lift(view: View) {
        val bitmap: Bitmap = createBitmap(view.width, view.height).also { view.draw(Canvas(it)) }
        val at = view.windowPosition()
        val rootAt = root.windowPosition()
        originX = (at[0] - rootAt[0]).toFloat()
        originY = (at[1] - rootAt[1]).toFloat()
        val copy = ImageView(host).apply {
            setImageBitmap(bitmap)
            background = host.ui.rounded(host.ui.palette.surface, 16)
            elevation = host.ui.dp(8).toFloat()
            alpha = 0.96f
            measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
            layout(0, 0, view.width, view.height)
            translationX = originX
            translationY = originY
        }
        root.overlay.add(copy)
        copy.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        lifted = copy
    }

    private fun onTouch(event: MotionEvent) {
        if (dragged == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (anchorX.isNaN()) { anchorX = event.rawX; anchorY = event.rawY }
                val dx = event.rawX - anchorX
                val dy = event.rawY - anchorY
                if (!moved && hypot(dx, dy) > slop) moved = true
                lifted?.translationX = originX + dx
                lifted?.translationY = originY + dy
                follow()
            }
            // A new touch means the previous gesture's end was lost (the window lost focus meanwhile).
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN -> drop()
        }
    }

    /** Centre of the lifted copy in window coordinates. */
    private fun centre(): Pair<Float, Float>? {
        val copy = lifted ?: return null
        val rootAt = root.windowPosition()
        return copy.translationX + copy.width / 2f + rootAt[0] to copy.translationY + copy.height / 2f + rootAt[1]
    }

    private fun follow() {
        val (x, y) = centre() ?: return
        val current = layer ?: return
        val all = layers()
        if (current !== all.first() && current === all.last()) {
            if (current.covers(x, y)) armed = true else if (armed) return leave(current) else return hold(-1)
        }
        val target = slotAt(current, x, y)
        if (target < 0 || target == index) return hold(-1)
        val holder = viewAt(current, target)?.takeIf { current.items[target] is BookmarkFolder }
        if (holder != null) {
            val box = bounds(holder)
            val insetX = box.width() / 4; val insetY = box.height() / 4
            if (x in box.left + insetX..box.right - insetX && y in box.top + insetY..box.bottom - insetY) return hold(target)
            hold(-1)
            // Only the half away from where the dragged shortcut sits reorders, so passing over a folder leaves it be.
            val from = viewAt(current, index)?.let(::bounds)
            if (from != null && (x - box.centerX()) * (from.centerX() - box.centerX()) + (y - box.centerY()) * (from.centerY() - box.centerY()) >= 0) return
        }
        hold(-1)
        val before = positions(current)
        val from = index
        current.items.add(target, current.items.removeAt(from))
        index = target
        host.haptic(current.grid, HapticFeedbackConstants.CLOCK_TICK)
        current.redraw()
        slide(current, before, from, target)
    }

    /** Marks the folder at [at] in [layer] as the one the shortcut would go into, and starts waiting to open it. */
    private fun hold(at: Int) {
        if (at == holding) return
        val current = layer
        if (holding >= 0 && current != null) viewAt(current, holding)?.animate()?.scaleX(1f)?.scaleY(1f)?.rotation(0f)?.setDuration(120)?.start()
        root.removeCallbacks(dwell)
        holding = at
        lifted?.animate()?.alpha(if (at >= 0) 0.7f else 0.96f)?.setDuration(120)?.start()
        if (at < 0 || current == null) return
        host.haptic(current.grid, HapticFeedbackConstants.CLOCK_TICK)
        viewAt(current, at)?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)?.setInterpolator(EASE)?.start()
        root.postDelayed(dwell, DWELL_MS)
    }

    /** Shakes the marked folder, then opens it with the shortcut inside, if it is still marked by then. */
    private fun shake() {
        val current = layer ?: return
        val at = holding
        val folder = current.items.getOrNull(at) as? BookmarkFolder ?: return
        val view = viewAt(current, at) ?: return
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -8f, 8f, -6f, 6f, -3f, 0f).apply {
            duration = 360
            doOnEnd { if (dragged != null && layer === current && holding == at) enter(current, folder) }
            start()
        }
    }

    /** Takes the shortcut out of [from] and into [folder], opened above it. */
    private fun enter(from: ShortcutGrid, folder: BookmarkFolder) {
        hold(-1)
        val item = dragged ?: return
        from.items.removeAt(index)
        from.redraw()
        layer = null
        index = -1
        entering = folder.id
        moved = true
        armed = false
        host.haptic(from.grid, HapticFeedbackConstants.LONG_PRESS)
        openFolder(folder) { grid ->
            if (dragged !== item || layer != null) return@openFolder
            grid.items.add(item)
            layer = grid
            index = grid.items.lastIndex
            grid.redraw()
        }
    }

    /** Closes the open folder [popup], and the shortcut goes back beside it in the grid below. */
    private fun leave(popup: ShortcutGrid) {
        hold(-1)
        val item = dragged ?: return
        popup.items.removeAt(index)
        closeLayer(popup)
        val below = layers().lastOrNull() ?: return
        val folderAt = below.items.indexOfFirst { it is BookmarkFolder && it.id == popup.folder }
        val at = if (folderAt >= 0) folderAt + 1 else below.shown()
        below.items.add(at.coerceIn(0, below.items.size), item)
        layer = below
        index = below.items.indexOf(item)
        moved = true
        below.redraw()
    }

    /** Settles the lifted copy where the shortcut ends up, then writes where that is. */
    private fun drop() {
        root.removeCallbacks(dwell)
        host.touchInterceptor = null
        val item = dragged ?: return
        val copy = lifted
        lifted = null
        val current = layer
        val holder = holding
        val finish = { into: Long, order: List<Any>? ->
            if (copy != null) root.overlay.remove(copy)
            dragged = null
            layer = null
            current?.redraw()
            if (!moved && order != null && into == origin) menu(item) else commit(item, into, order)
        }
        when {
            // Let go over a folder's middle: it goes inside, without opening it.
            current != null && holder >= 0 -> {
                hold(-1)
                val folder = current.items[holder] as BookmarkFolder
                val target = viewAt(current, holder)
                current.items.removeAt(index)
                moved = true
                shrinkInto(copy, target) { finish(folder.id, null) }
            }
            // Let go while a folder it is going into opens: it goes in there.
            current == null -> shrinkInto(copy, null) { finish(entering, null) }
            else -> {
                val target = viewAt(current, index)
                if (copy == null || target == null || !target.isAttachedToWindow) return finish(current.folder, current.items.toList())
                val at = target.windowPosition()
                val rootAt = root.windowPosition()
                val order = current.items.toList()
                copy.animate().translationX((at[0] - rootAt[0]).toFloat()).translationY((at[1] - rootAt[1]).toFloat())
                    .scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).setInterpolator(EASE).withEndAction { finish(current.folder, order) }.start()
            }
        }
    }

    private fun shrinkInto(copy: ImageView?, target: View?, done: () -> Unit) {
        if (copy == null) return done()
        val animation = copy.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(160).setInterpolator(EASE).withEndAction(done)
        if (target != null && target.isAttachedToWindow) {
            val at = target.windowPosition()
            val rootAt = root.windowPosition()
            animation.translationX((at[0] - rootAt[0] + (target.width - copy.width) / 2).toFloat())
                .translationY((at[1] - rootAt[1] + (target.height - copy.height) / 2).toFloat())
        }
        animation.start()
    }

    /**
     * Writes the shortcut into [folder] when it changed folders, then [order] when given: the grid it
     * ended in, arranged as shown. A move puts it first in the folder, ahead of all it holds, so
     * arranging a partly loaded grid after it never pushes anything past the shortcuts left unloaded.
     */
    private fun commit(item: Any, folder: Long, order: List<Any>?) {
        host.lifecycleScope.launch {
            val store = host.store
            val fine = withContext(store.io) {
                (folder == origin || store.moveInto(item, folder)).also { if (it && order != null) store.arrange(order) }
            }
            if (!fine) host.toast(R.string.bookmark_folder_exists)
            saved()
        }
    }

    private fun slotAt(grid: ShortcutGrid, x: Float, y: Float): Int {
        val view = grid.grid
        if (view.width == 0) return -1
        val at = view.windowPosition()
        val localX = x - at[0]
        val localY = y - at[1]
        if (localX < 0 || localX >= view.width) return -1
        val row = (0 until view.childCount).firstOrNull { localY >= view.getChildAt(it).top && localY < view.getChildAt(it).bottom } ?: return -1
        val columns = HomeBookmarkPresentation.COLUMNS
        val column = (localX * columns / view.width).toInt().coerceIn(0, columns - 1)
        return (row * columns + column).takeIf { it < minOf(grid.shown(), grid.items.size) } ?: -1
    }

    private fun viewAt(grid: ShortcutGrid, at: Int): View? {
        if (at < 0) return null
        val columns = HomeBookmarkPresentation.COLUMNS
        return (grid.grid.getChildAt(at / columns) as? ViewGroup)?.getChildAt(at % columns)
    }

    /** Where [view] is in the window. */
    private fun bounds(view: View): RectF {
        val at = view.windowPosition()
        return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    /** Window positions of the shortcuts now on screen, by index. */
    private fun positions(grid: ShortcutGrid): Map<Int, Pair<Int, Int>> = buildMap {
        for (at in 0 until minOf(grid.shown(), grid.items.size)) {
            val view = viewAt(grid, at)?.takeIf { it.isAttachedToWindow } ?: continue
            val position = view.windowPosition()
            put(at, position[0] to position[1])
        }
    }

    /** Animates every shortcut that changed place from where it was to where it is now. */
    private fun slide(grid: ShortcutGrid, before: Map<Int, Pair<Int, Int>>, from: Int, to: Int) {
        grid.grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.grid.viewTreeObserver.removeOnPreDrawListener(this)
                before.forEach { (old, position) ->
                    if (old == from) return@forEach
                    val now = when {
                        old in (from + 1)..to -> old - 1
                        old in to until from -> old + 1
                        else -> old
                    }
                    val view = viewAt(grid, now) ?: return@forEach
                    val at = view.windowPosition()
                    val dx = (position.first - at[0]).toFloat()
                    val dy = (position.second - at[1]).toFloat()
                    if (dx == 0f && dy == 0f) return@forEach
                    view.translationX = dx; view.translationY = dy
                    view.animate().translationX(0f).translationY(0f).setDuration(160).setInterpolator(EASE).start()
                }
                return true
            }
        })
    }

    private companion object {
        val EASE = DecelerateInterpolator(1.6f)
        /** How long a shortcut rests over a folder before the folder shakes and opens. */
        const val DWELL_MS = 380L
    }
}

/** Where the view's top-left corner is in its window, as `[x, y]`. */
private fun View.windowPosition() = IntArray(2).also(::getLocationInWindow)
