package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView that reports a downward pull once the page itself can scroll no further up. The pull starts
 * only from the page's own overscroll, so inner scrolling areas and pages that handle the gesture
 * themselves (maps, editors) never trigger a refresh.
 */
@SuppressLint("ViewConstructor") // Built in code only.
class BrowserWebView(context: Context) : WebView(context) {
    /** Where the last touch went down, in view pixels; long-press menus look there. */
    internal var lastDownX = 0f
    internal var lastDownY = 0f
    /** Whether the desktop user agent is in use, so site settings know when it must change. */
    internal var desktop = false
    /** Called with the pull distance in pixels while pulling, and with `release = true` when the finger lifts. */
    internal var onPull: ((distance: Float, release: Boolean) -> Unit)? = null
    private var overscrolledTop = false
    private var startY = Float.NaN
    private var distance = 0f

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) overscrolledTop = true
    }

    @SuppressLint("ClickableViewAccessibility") // Refresh stays available from the toolbar.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
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
            MotionEvent.ACTION_UP -> { if (!startY.isNaN()) onPull?.invoke(distance, true); reset() }
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
}
