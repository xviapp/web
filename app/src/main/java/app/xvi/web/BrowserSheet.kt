package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Bottom panel: sized to its content up to a cap, dismissed by the close button, outside taps, back or a downward drag.
 * It floats [clearance] above the window's bottom edge, leaving what the window keeps there in view.
 */
internal class BrowserSheet(
    private val ui: BrowserUi,
    private val anchor: View,
    title: String,
    private val secure: () -> Boolean,
    headerAction: ((BrowserSheet) -> View)? = null,
    private val clearance: () -> Int = { 0 },
    internal val tag: String? = null,
    private val outsideTap: ((Int, Int, String?) -> Unit)? = null
) {
    private val slop = ViewConfiguration.get(ui.context).scaledTouchSlop
    private val hitBounds = Rect()
    private val panel = object : LinearLayout(ui.context) {
        var maxHeight = Int.MAX_VALUE
        private var downX = 0f
        private var downY = 0f
        private var pullStartY = 0f
        private var pulling = false
        private var tracker: VelocityTracker? = null

        init {
            orientation = VERTICAL
            background = ui.rounded(ui.palette.surface, 24)
            setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(16))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    pulling = false
                    recycleTracker()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    if (!pulling && dy > slop && dy > abs(dx) * 1.5f && !canScrollUp(this, ev.rawX, ev.rawY)) {
                        beginPull(ev)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTracker()
            }
            return pulling
        }

        @SuppressLint("ClickableViewAccessibility") // The close button and back key remain the accessible ways to dismiss.
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!canScrollUp(this, ev.rawX, ev.rawY)) {
                        beginPull(ev)
                        return true
                    }
                    return super.onTouchEvent(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pulling) return super.onTouchEvent(ev)
                    addScreenMovement(ev)
                    translationY = (ev.rawY - pullStartY).coerceAtLeast(0f)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!pulling) return super.onTouchEvent(ev)
                    addScreenMovement(ev)
                    // A quick downward flick closes the panel even before it has been dragged far.
                    val flung = ev.actionMasked == MotionEvent.ACTION_UP && translationY > ui.dp(16) &&
                        (tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f) > ui.dp(800)
                    pulling = false
                    recycleTracker()
                    if (flung || translationY > ui.dp(96)) stepBackOrDismiss()
                    else animate().translationY(0f).setDuration(150).start()
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun beginPull(ev: MotionEvent) {
            pulling = true
            pullStartY = ev.rawY
            animate().cancel()
            recycleTracker()
            tracker = VelocityTracker.obtain()
            addScreenMovement(ev)
        }

        private fun addScreenMovement(ev: MotionEvent) {
            // The panel moves under the finger, so velocity is measured in screen coordinates.
            val screen = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
            tracker?.addMovement(screen)
            screen.recycle()
        }

        private fun recycleTracker() {
            tracker?.recycle()
            tracker = null
        }
    }

    /** Holds the panel plus a snackbar of its own, so undo stays visible above the dimmed page. */
    private val frame = FrameLayout(ui.context).apply { addView(panel, ui.wide()) }
    private val snackbar = BrowserSnackbar(ui, frame)
    private val dismissActions = mutableListOf<() -> Unit>()
    /** The panel this one was opened from, shown again when this one closes; see [backTo]. */
    private var parent: (() -> Unit)? = null
    /** In-panel back step (for example leaving a bookmark folder), run before [parent] or closing. */
    private var onBack: (() -> Boolean)? = null
    private var window: SheetWindow? = null
    private val header = ui.row()
    private val titleView = ui.medium(ui.singleLine(ui.label(title, 20f))).apply { isFocusable = true }
    private var backButton: ImageButton? = null
    val isShowing get() = window?.let { it.active === this && it.popup.isShowing } == true

    init {
        val handle = FrameLayout(ui.context).apply {
            addView(View(ui.context).apply {
                background = ui.rounded(ColorUtils.setAlphaComponent(ui.palette.muted, 96), 2)
            }, FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(handle, ui.bar(14))
        header.addView(titleView, ui.sized(0, ui.dp(52), 1f))
        // A header action sits beside the close button, never in its place: a tall panel leaves little
        // outside to tap, so the close button is the one dismissal every panel keeps within reach.
        headerAction?.invoke(this)?.let { header.addView(it, ui.sized(ui.wrap(), ui.wrap()).apply { rightMargin = ui.dp(4) }) }
        header.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.action_close)) { leave() }, ui.tappable())
        panel.addView(header, ui.bar(52))
    }

    private fun canScrollUp(group: ViewGroup, rawX: Float, rawY: Float): Boolean {
        val x = rawX.toInt()
        val y = rawY.toInt()
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (!child.isVisible) continue
            if (!child.getGlobalVisibleRect(hitBounds) || !hitBounds.contains(x, y)) continue
            if (child is WebView || child is EditText) return true
            if (child.canScrollVertically(-1)) return true
            if (child is ViewGroup && canScrollUp(child, rawX, rawY)) return true
        }
        return false
    }

    private fun stepBackOrDismiss() {
        if (onBack?.invoke() == true) panel.animate().translationY(0f).setDuration(150).start()
        else dismiss()
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    /** Installs or clears an in-panel back step and updates the header's back arrow to match. */
    fun setBackAction(description: String = ui.str(R.string.action_back), action: (() -> Boolean)?) {
        onBack = action
        syncBackButton(description)
    }

    private fun syncBackButton(description: String = ui.str(R.string.action_back)) {
        val wanted = onBack != null || parent != null
        if (!wanted) {
            backButton?.isVisible = false
            return
        }
        // Pulled into the padding so the arrow, not its touch area, lines up with other panels' titles.
        val button = backButton ?: ui.iconAction(R.drawable.ic_arrow_back_24, description) { stepBackOrDismiss() }.also {
            backButton = it
            header.addView(it, 0, ui.tappable().apply { leftMargin = -ui.dp(12) })
        }
        button.describe(description)
        button.isVisible = true
    }

    fun onDismiss(action: () -> Unit) { dismissActions.add(action) }

    /** Brief message inside this panel, for example to undo a removal. */
    fun showSnackbar(message: String, action: String? = null, onAction: (() -> Unit)? = null) = snackbar.show(message, action, onAction)

    /** Content views; a list that should scroll takes `ui.stretch()` so it shrinks to fit. */
    fun add(view: View, params: LinearLayout.LayoutParams = ui.wide()) = panel.addView(view, params)

    fun show() {
        val insets = ViewCompat.getRootWindowInsets(anchor)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val top = insets?.top ?: 0
        val bottom = insets?.bottom ?: 0
        val height = anchor.rootView.height.takeIf { it > 0 } ?: ui.context.resources.displayMetrics.heightPixels
        val width = anchor.rootView.width.takeIf { it > 0 } ?: ui.context.resources.displayMetrics.widthPixels
        val lifted = bottom + clearance() + ui.dp(8)
        val popupWidth = minOf(width - ui.dp(16), ui.dp(600))
        panel.maxHeight = minOf(((height - top - bottom) * 0.88f).toInt(), height - top - lifted - ui.dp(8))
        panel.translationY = 0f

        val existing = windows[anchor]?.takeIf { it.popup.isShowing }
        if (existing != null) {
            existing.active?.takeIf { it !== this }?.let { previous ->
                open.remove(previous)
                previous.parent = null
                previous.onBack = null
                previous.finishDismiss()
            }
            (frame.parent as? ViewGroup)?.removeView(frame)
            existing.container.removeAllViews()
            existing.container.addView(frame, ui.anchored(Gravity.BOTTOM))
            existing.active = this
            window = existing
            open.add(this)
            existing.popup.update(0, lifted, popupWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            existing.applyWindowFlags(secure())
            val direction = existing.nextDirection.takeIf { it != 0 } ?: 1
            panel.animate().cancel()
            panel.translationX = direction * ui.dp(20).toFloat()
            panel.alpha = 0.55f
            panel.animate().translationX(0f).alpha(1f).setDuration(140).start()
            return
        }

        val hostWindow = SheetWindow(ui, anchor)
        windows[anchor] = hostWindow
        window = hostWindow
        (frame.parent as? ViewGroup)?.removeView(frame)
        hostWindow.container.addView(frame, ui.anchored(Gravity.BOTTOM))
        hostWindow.active = this
        hostWindow.popup.width = popupWidth
        hostWindow.popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, lifted)
        open.add(this)
        frame.post { hostWindow.applyWindowFlags(secure()) }
    }

    /** Swaps this panel for the one [openNext] opens inside the same popup window, without flashing the dim layer. */
    fun replace(openNext: () -> Unit) {
        parent = null
        onBack = null
        val hostWindow = window
        if (hostWindow == null || hostWindow.active !== this || !hostWindow.popup.isShowing) {
            openNext()
            return
        }
        open.remove(this)
        hostWindow.active = null
        finishDismiss()
        hostWindow.nextDirection = 1
        openNext()
        hostWindow.nextDirection = 0
        if (hostWindow.active == null && hostWindow.popup.isShowing) {
            hostWindow.closingExplicitly = true
            hostWindow.popup.dismiss()
        }
    }

    fun dismiss() {
        val hostWindow = window ?: return
        if (hostWindow.active !== this) return
        val backParent = parent
        parent = null
        onBack = null
        if (backParent != null && hostWindow.popup.isShowing && anchor.isAttachedToWindow) {
            open.remove(this)
            hostWindow.active = null
            finishDismiss()
            hostWindow.nextDirection = -1
            backParent.invoke()
            hostWindow.nextDirection = 0
            if (hostWindow.active == null && hostWindow.popup.isShowing) {
                hostWindow.closingExplicitly = true
                hostWindow.popup.dismiss()
            }
            return
        }
        hostWindow.closingExplicitly = true
        if (hostWindow.popup.isShowing) hostWindow.popup.dismiss()
    }

    /** Closes this panel without going back to the one it was opened from. */
    fun leave() {
        parent = null
        onBack = null
        dismiss()
    }

    /** Makes this a page of another panel: a back arrow, the back key or a drag reopen it through [show]. */
    fun backTo(show: () -> Unit) {
        parent = show
        syncBackButton()
    }

    private fun finishDismiss() {
        val actions = dismissActions.toList()
        dismissActions.clear()
        actions.forEach { it() }
    }

    /** Shared popup window over one [anchor], so moving between panels keeps the dim layer steady. */
    private class SheetWindow(private val ui: BrowserUi, private val anchor: View) {
        val container = FrameLayout(ui.context)
        var active: BrowserSheet? = null
        var closingExplicitly = false
        var outsideDismiss = false
        var outsidePoint: Pair<Int, Int>? = null
        var nextDirection = 0

        val popup: PopupWindow = object : PopupWindow(container, 1, WindowManager.LayoutParams.WRAP_CONTENT, true) {
            override fun dismiss() {
                if (!closingExplicitly && !outsideDismiss && anchor.isAttachedToWindow) {
                    val current = active
                    if (current != null) {
                        if (current.onBack?.invoke() == true) return
                        if (current.parent != null) { current.dismiss(); return }
                    }
                }
                val wasOutside = outsideDismiss
                val point = outsidePoint
                val sourceTag = active?.tag
                val tapHandler = active?.outsideTap
                outsideDismiss = false
                outsidePoint = null
                closingExplicitly = false
                active?.let { sheet ->
                    active = null
                    open.remove(sheet)
                    sheet.parent = null
                    sheet.onBack = null
                    sheet.finishDismiss()
                }
                super.dismiss()
                if (windows[anchor] === this@SheetWindow) windows.remove(anchor)
                if (wasOutside && point != null && tapHandler != null && anchor.isAttachedToWindow) {
                    anchor.post { if (anchor.isAttachedToWindow) tapHandler(point.first, point.second, sourceTag) }
                }
            }
        }.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            isOutsideTouchable = true
            elevation = ui.dp(16).toFloat()
            animationStyle = R.style.BrowserSheetAnimation
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            // Deprecated for windows that handle keyboard insets themselves, which needs the Window a popup
            // does not expose; without it the keyboard would cover the panel's fields instead of shrinking it.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // A tap outside closes the whole stack, like the close button; back and a drag go up one level.
            // Some systems report that tap as a down event beyond the window rather than as ACTION_OUTSIDE.
            @SuppressLint("ClickableViewAccessibility") // Only observes outside touches; the close button handles accessibility dismissal.
            setTouchInterceptor { view, event ->
                val outOfBounds = event.actionMasked == MotionEvent.ACTION_DOWN &&
                    (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height)
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || outOfBounds) {
                    outsideDismiss = true
                    outsidePoint = if (outOfBounds) {
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        (loc[0] + event.x.toInt()) to (loc[1] + event.y.toInt())
                    } else {
                        val rx = event.rawX.toInt()
                        val ry = event.rawY.toInt()
                        if (rx > 0 || ry > 0) rx to ry else null
                    }
                }
                false
            }
        }

        fun applyWindowFlags(secure: Boolean) {
            if (!popup.isShowing) return
            val root = container.rootView
            val params = root.layoutParams as? WindowManager.LayoutParams ?: return
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND).let {
                if (secure) it or WindowManager.LayoutParams.FLAG_SECURE
                else it and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            params.dimAmount = 0.45f
            runCatching { (ui.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params) }
        }
    }

    companion object {
        private val open = mutableSetOf<BrowserSheet>()
        private val windows = WeakHashMap<View, SheetWindow>()

        /** Active sheet currently displayed over [anchor], if any. */
        fun activeOver(anchor: View): BrowserSheet? = windows[anchor]?.active?.takeIf { it.isShowing }

        /** Closes every panel shown over [anchor]; they are windows of their own, so nothing drawn in the page can cover them. */
        fun dismissAll(anchor: View) = open.filter { it.anchor === anchor }.toList().forEach(BrowserSheet::leave)
    }
}
