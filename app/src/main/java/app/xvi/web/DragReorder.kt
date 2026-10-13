package app.xvi.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import kotlin.math.hypot

/**
 * Long-press-and-drag reordering for the items laid out in [container]. The pressed item lifts and
 * follows the finger; the others slide out of its way, and on release it settles into its new place.
 * Lifting without moving reports `moved = false`, so callers keep their long-press menu; screen
 * readers go straight there.
 *
 * [slotAt] maps a point in [container] to an item index, or -1; [visible] is the range of indices on
 * screen and [viewAt] the view now showing one. [move] shifts one item in the caller's data, then [redraw] shows it; callers hide
 * the [dragging] item, whose place the lifted copy takes. [scroll], when given, scrolls by a pixel
 * amount while the item rests near the top or bottom edge.
 *
 * When [canHold] says an item can take the dragged one, as a folder takes a bookmark, resting over its
 * middle marks it, and letting go there hands both to [into] instead of [finish]. Its edges still
 * reorder, though only past its middle, so passing over it on the way somewhere else does not.
 */
internal class DragReorder(
    private val host: BrowserActivity,
    private val container: ViewGroup,
    private val slotAt: (x: Float, y: Float) -> Int,
    private val visible: () -> IntRange,
    private val viewAt: (index: Int) -> View?,
    private val move: (from: Int, to: Int) -> Unit,
    private val redraw: () -> Unit,
    private val scroll: ((Int) -> Unit)? = null,
    /** Lock sideways movement, for single-column lists. */
    private val vertical: Boolean = false,
    private val canHold: ((dragged: Int, target: Int) -> Boolean)? = null,
    private val into: ((dragged: Int, target: Int) -> Unit)? = null,
    private val finish: (index: Int, moved: Boolean) -> Unit
) {
    var dragging = -1
        private set
    /** The item the dragged one would go into if let go now, or -1. */
    private var holding = -1
    /**
     * Where the marked item was when marked, a little enlarged. Marking takes its middle, but the mark
     * holds anywhere in here, so an unsteady finger neither drops it nor moves anything.
     */
    private var holdBox: RectF? = null
    private val holdMargin = host.ui.dp(8).toFloat()
    private var moved = false
    private var lifted: ImageView? = null
    /** Where the lifted copy started, in the window, and where the finger was when it first moved. */
    private var originX = 0f
    private var originY = 0f
    private var anchorX = Float.NaN
    private var anchorY = Float.NaN
    private var edgeStep = 0
    private val slop = ViewConfiguration.get(host).scaledTouchSlop
    private val edge = host.ui.dp(56)
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (dragging < 0 || edgeStep == 0) return
            scroll?.invoke(edgeStep)
            follow()
            container.postOnAnimation(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Long-press opens the same actions without dragging.
    private val touches = View.OnTouchListener { _, event -> onTouch(event); true }

    fun start(view: View, index: Int) {
        host.haptic(view, HapticFeedbackConstants.LONG_PRESS)
        val reader = host.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
        if (reader || dragging >= 0 || view.width == 0 || view.height == 0) return finish(index, false)
        dragging = index
        moved = false
        anchorX = Float.NaN; anchorY = Float.NaN
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(true)
        // The rest of the gesture drives the drag. In the browser window it is taken before any view sees
        // it, since the pressed item is rebuilt as items move; a panel's list keeps its views and takes it itself.
        if (container.rootView === host.window.decorView) host.touchInterceptor = { onTouch(it); true }
        else container.setOnTouchListener(touches)
        lift(view)
        redraw()
    }

    /** Copy of the item drawn above everything in the window, slightly enlarged. */
    private fun lift(view: View) {
        val bitmap: Bitmap = createBitmap(view.width, view.height).also { view.draw(Canvas(it)) }
        val at = view.windowPosition()
        val overlayRoot = container.rootView as ViewGroup
        val rootAt = overlayRoot.windowPosition()
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
        overlayRoot.overlay.add(copy)
        copy.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        lifted = copy
    }

    private fun onTouch(event: MotionEvent) {
        if (dragging < 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (anchorX.isNaN()) { anchorX = event.rawX; anchorY = event.rawY }
                val dx = if (vertical) 0f else event.rawX - anchorX
                val dy = event.rawY - anchorY
                if (!moved && hypot(dx, dy) > slop) moved = true
                lifted?.translationX = originX + dx
                lifted?.translationY = originY + dy
                follow()
                updateEdge()
            }
            // A new touch means the previous gesture's end was lost (the window lost focus meanwhile).
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN -> drop()
        }
    }

    /** Centre of the lifted copy in [container] coordinates. */
    private fun liftedCentre(): Pair<Float, Float>? {
        val copy = lifted ?: return null
        val rootAt = container.rootView.windowPosition()
        val at = container.windowPosition()
        val x = copy.translationX + copy.width / 2f + rootAt[0] - at[0]
        val y = copy.translationY + copy.height / 2f + rootAt[1] - at[1]
        return x to y
    }

    private fun updateEdge() {
        if (scroll == null) return
        val (_, y) = liftedCentre() ?: return
        val step = when {
            y < edge -> -host.ui.dp(10)
            y > container.height - edge -> host.ui.dp(10)
            else -> 0
        }
        if (step != 0 && edgeStep == 0) { edgeStep = step; container.postOnAnimation(edgeScroll) } else edgeStep = step
    }

    /** Moves the item once the lifted copy's centre is over another slot, or marks one that can take it. */
    private fun follow() {
        val (x, y) = liftedCentre() ?: return
        if (holding >= 0 && holdBox?.contains(x, y) == true) return
        val target = slotAt(x, y)
        if (target < 0 || target == dragging) return hold(-1)
        val holder = viewAt(target)?.takeIf { canHold?.invoke(dragging, target) == true }
        if (holder != null) {
            val box = bounds(holder)
            if (box.width() > 0 && box.height() > 0) {
                val insetX = box.width() / 4; val insetY = box.height() / 4
                if (x in box.left + insetX..box.right - insetX && y in box.top + insetY..box.bottom - insetY) return hold(target)
                hold(-1)
                // Only the half away from where the dragged item sits reorders.
                val origin = viewAt(dragging)?.let(::bounds)
                if (origin != null && (x - box.centerX()) * (origin.centerX() - box.centerX()) + (y - box.centerY()) * (origin.centerY() - box.centerY()) >= 0) return
            }
        }
        hold(-1)
        val from = dragging
        val before = positions()
        move(from, target)
        dragging = target
        host.haptic(container, HapticFeedbackConstants.CLOCK_TICK)
        redraw()
        slide(before, from, target)
    }

    /** Marks [index] as the item that would take the dragged one, unmarking the previous; -1 for none. */
    private fun hold(index: Int) {
        if (index == holding) return
        if (holding >= 0) viewAt(holding)?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        holding = index
        holdBox = if (index >= 0) viewAt(index)?.let(::bounds)?.apply { inset(-holdMargin, -holdMargin) } else null
        lifted?.animate()?.alpha(if (index >= 0) 0.7f else 0.96f)?.setDuration(120)?.start()
        if (index < 0) return
        host.haptic(container, HapticFeedbackConstants.CLOCK_TICK)
        viewAt(index)?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)?.setInterpolator(EASE)?.start()
    }

    /** Where [view] is in [container]. */
    private fun bounds(view: View): RectF {
        val at = view.windowPosition()
        val origin = container.windowPosition()
        val left = (at[0] - origin[0]).toFloat()
        val top = (at[1] - origin[1]).toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    /** Window positions of the items now on screen, by index. */
    private fun positions(): Map<Int, Pair<Int, Int>> = buildMap {
        for (index in visible()) {
            val view = viewAt(index)?.takeIf { it.isAttachedToWindow } ?: continue
            val at = view.windowPosition()
            put(index, at[0] to at[1])
        }
    }

    /** Animates every item that changed place from where it was to where it is now. */
    private fun slide(before: Map<Int, Pair<Int, Int>>, from: Int, to: Int) {
        container.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                container.viewTreeObserver.removeOnPreDrawListener(this)
                before.forEach { (old, position) ->
                    if (old == from) return@forEach
                    val now = when {
                        old in (from + 1)..to -> old - 1
                        old in to until from -> old + 1
                        else -> old
                    }
                    val view = viewAt(now) ?: return@forEach
                    val at = view.windowPosition()
                    val dx = (position.first - at[0]).toFloat()
                    val dy = (position.second - at[1]).toFloat()
                    if (dx == 0f && dy == 0f) return@forEach
                    view.animate().cancel()
                    view.translationX = dx; view.translationY = dy
                    view.animate().translationX(0f).translationY(0f).setDuration(160).setInterpolator(EASE).start()
                }
                return true
            }
        })
    }

    /** Settles the lifted copy into the item's place, then shows the item again. */
    private fun drop() {
        edgeStep = 0
        container.removeCallbacks(edgeScroll)
        if (host.touchInterceptor != null && container.rootView === host.window.decorView) host.touchInterceptor = null
        container.setOnTouchListener(null)
        val index = dragging
        val copy = lifted
        lifted = null
        val holder = holding
        if (holder >= 0) {
            hold(-1)
            val done: () -> Unit = {
                if (copy != null) (container.rootView as ViewGroup).overlay.remove(copy)
                dragging = -1
                redraw()
                into?.invoke(index, holder)
            }
            // The lifted copy shrinks away into the item taking it.
            val target = viewAt(holder)
            if (copy == null || target == null || !target.isAttachedToWindow) { done(); return }
            val at = target.windowPosition()
            val rootAt = container.rootView.windowPosition()
            copy.animate().translationX((at[0] - rootAt[0] + (target.width - copy.width) / 2).toFloat())
                .translationY((at[1] - rootAt[1] + (target.height - copy.height) / 2).toFloat())
                .scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(160).setInterpolator(EASE).withEndAction(done).start()
            return
        }
        val done = {
            if (copy != null) (container.rootView as ViewGroup).overlay.remove(copy)
            dragging = -1
            redraw()
            if (index >= 0) finish(index, moved)
        }
        val target = viewAt(index)
        if (copy == null || target == null || !target.isAttachedToWindow) { done(); return }
        val at = target.windowPosition()
        val rootAt = container.rootView.windowPosition()
        copy.animate().translationX((at[0] - rootAt[0]).toFloat()).translationY((at[1] - rootAt[1]).toFloat())
            .scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(EASE).withEndAction(done).start()
    }

    private companion object {
        val EASE = DecelerateInterpolator(1.6f)
    }
}

/** Where the view's top-left corner is in its window, as `[x, y]`. */
private fun View.windowPosition() = IntArray(2).also(::getLocationInWindow)
