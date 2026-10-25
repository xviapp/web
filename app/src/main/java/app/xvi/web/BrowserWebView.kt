package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.abs

/**
 * WebView that reports a downward pull once the page itself can scroll no further up. The pull starts
 * only from the page's own overscroll, so inner scrolling areas and pages that handle the gesture
 * themselves (maps, editors) never trigger a refresh.
 */
@SuppressLint("ViewConstructor") // Built in code only.
class BrowserWebView(context: Context) : WebView(MutableContextWrapper(context)) {
    /** A page moved to another window must use that window for dialogs and input. */
    internal fun rehost(context: Context) { (this.context as MutableContextWrapper).baseContext = context }
    /** Where the last touch went down, in view pixels; long-press menus look there. */
    internal var lastDownX = 0f
    internal var lastDownY = 0f
    /** Taps on the page so far; a page can ask again to open an app once the user has tapped it. */
    internal var taps = 0L
        private set
    private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    /** Whether the desktop user agent is in use, so site settings know when it must change. */
    internal var desktop = false
    /** Called with the pull distance in pixels while pulling, and with `release = true` when the finger lifts. */
    internal var onPull: ((distance: Float, release: Boolean) -> Unit)? = null
    /** Reveals browser controls even when a page's inner scroller consumes the gesture. */
    internal var onSwipeDown: (() -> Unit)? = null
    /** Whether the text selection menu offers the AI assistant. */
    internal var showAi: () -> Boolean = { false }
    /** Selection menu's AI item; call `finish` once the selection has been read, as closing the menu clears it. */
    internal var onAskAi: ((finish: () -> Unit) -> Unit)? = null
    private var overscrolledTop = false
    private var startY = Float.NaN
    private var distance = 0f
    private var swipeStartY = Float.NaN
    private val swipeThreshold = 24 * resources.displayMetrics.density

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) overscrolledTop = true
    }

    @SuppressLint("ClickableViewAccessibility") // Refresh stays available from the toolbar.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        // Observe only: the same events still reach the page, and refresh keeps using overscroll.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeStartY = event.y
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeStartY = Float.NaN
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - swipeStartY
                if (dy > swipeThreshold && dy > abs(event.x - lastDownX)) {
                    swipeStartY = Float.NaN
                    onSwipeDown?.invoke()
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { reset(); lastDownX = event.x; lastDownY = event.y }
            MotionEvent.ACTION_POINTER_DOWN -> cancel()
            MotionEvent.ACTION_MOVE -> {
                if (startY.isNaN() && overscrolledTop && scrollY <= 0) startY = event.y
                if (!startY.isNaN()) {
                    distance = event.y - startY
                    if (distance < 0 || scrollY > 0) cancel() else onPull?.invoke(distance, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - lastDownX) < tapSlop && abs(event.y - lastDownY) < tapSlop) taps++
                if (!startY.isNaN()) onPull?.invoke(distance, true)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return handled
    }

    private fun cancel() {
        if (!startY.isNaN()) onPull?.invoke(0f, true)
        reset()
    }

    private fun reset() {
        overscrolledTop = false
        startY = Float.NaN
        distance = 0f
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(if (onAskAi != null && showAi()) SelectionCallback(callback) else callback, type)

    /** The page's own selection menu plus an AI item; the floating menu keeps its position from the original. */
    private inner class SelectionCallback(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        // The AI item is the reason this menu is wrapped at all, so it must not drop into overflow.
        @SuppressLint("AlwaysShowAction")
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val shown = inner.onCreateActionMode(mode, menu)
            // Order 0 sorts ahead of WebView's own items, so the floating toolbar never pushes it into overflow.
            if (shown) menu.add(Menu.NONE, AI_ITEM, 0, R.string.ai_selection_action)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return shown
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = inner.onPrepareActionMode(mode, menu)
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != AI_ITEM) return inner.onActionItemClicked(mode, item)
            onAskAi?.invoke { mode.finish() } ?: mode.finish()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect) ?: super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        /** Outside the range of menu ids WebView uses for its own selection actions. */
        const val AI_ITEM = 0x7a1
    }
}
