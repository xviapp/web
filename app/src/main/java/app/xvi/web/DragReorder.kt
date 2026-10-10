package app.xvi.web

import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager

/**
 * Long-press-and-drag reordering for the items laid out in [container]. Lifting without moving
 * reports `moved = false`, so callers keep their long-press menu; screen readers go straight there.
 *
 * [slotAt] maps a point in [container] to an item index, or -1. [move] shifts one item in the
 * caller's data, then [redraw] shows it; [dragging] is the item to show as lifted meanwhile.
 * [scroll], when given, scrolls by a pixel amount while the drag rests near the top or bottom edge.
 */
internal class DragReorder(
    private val host: BrowserActivity,
    private val container: View,
    private val slotAt: (x: Float, y: Float) -> Int,
    private val move: (from: Int, to: Int) -> Unit,
    private val redraw: () -> Unit,
    private val scroll: ((Int) -> Unit)? = null,
    private val finish: (index: Int, moved: Boolean) -> Unit
) {
    var dragging = -1
        private set
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f
    private var edgeStep = 0
    private val edge = host.ui.dp(48)
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (dragging < 0 || edgeStep == 0) return
            scroll?.invoke(edgeStep)
            follow(lastX, lastY)
            container.postOnAnimation(this)
        }
    }

    init {
        container.setOnDragListener { _, event -> onDrag(event) }
    }

    fun start(view: View, index: Int) {
        host.haptic(view, HapticFeedbackConstants.LONG_PRESS)
        val reader = (host.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
        if (reader || dragging >= 0) return finish(index, false)
        dragging = index
        moved = false
        if (!view.startDragAndDrop(null, View.DragShadowBuilder(view), this, 0)) {
            dragging = -1
            return finish(index, false)
        }
        redraw()
    }

    private fun onDrag(event: DragEvent): Boolean {
        if (event.localState !== this) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                lastX = event.x; lastY = event.y
                follow(event.x, event.y)
                val step = when {
                    scroll == null -> 0
                    event.y < edge -> -host.ui.dp(8)
                    event.y > container.height - edge -> host.ui.dp(8)
                    else -> 0
                }
                if (step != 0 && edgeStep == 0) { edgeStep = step; container.postOnAnimation(edgeScroll) } else edgeStep = step
            }
            DragEvent.ACTION_DRAG_EXITED -> edgeStep = 0
            DragEvent.ACTION_DRAG_ENDED -> {
                edgeStep = 0
                container.removeCallbacks(edgeScroll)
                val index = dragging
                dragging = -1
                redraw()
                if (index >= 0) finish(index, moved)
            }
        }
        return true
    }

    private fun follow(x: Float, y: Float) {
        val target = slotAt(x, y)
        if (target < 0 || target == dragging) return
        move(dragging, target)
        dragging = target
        moved = true
        host.haptic(container, HapticFeedbackConstants.CLOCK_TICK)
        redraw()
    }
}
