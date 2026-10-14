package app.xvi.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Bottom panel: sized to its content up to a cap, dismissed by the close button, outside taps, back or a downward drag. */
internal class BrowserSheet(
    private val ui: BrowserUi,
    private val anchor: View,
    title: String,
    private val secure: () -> Boolean,
    headerAction: ((BrowserSheet) -> View)? = null
) {
    private val panel = CappedColumn(ui.context, Int.MAX_VALUE).apply {
        background = ui.rounded(ui.palette.surface, 24)
        setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(16))
    }
    /** Holds the panel plus a snackbar of its own, so undo stays visible above the dimmed page. */
    private val frame = FrameLayout(ui.context).apply { addView(panel, ui.wide()) }
    private val snackbar = BrowserSnackbar(ui, frame)
    private val popup = PopupWindow(frame, 1, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        isOutsideTouchable = true
        elevation = ui.dp(16).toFloat()
        animationStyle = R.style.BrowserSheetAnimation
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        // Deprecated for windows that handle keyboard insets themselves, which needs the Window a popup
        // does not expose; without it the keyboard would cover the panel's fields instead of shrinking it.
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    private val dismissActions = mutableListOf<() -> Unit>()
    /** The panel this one was opened from, shown again when this one closes; see [backTo]. */
    private var parent: (() -> Unit)? = null
    private lateinit var header: LinearLayout
    val isShowing get() = popup.isShowing

    init {
        popup.setOnDismissListener {
            open.remove(this); dismissActions.toList().forEach { it() }; dismissActions.clear()
            parent?.also { parent = null }?.invoke()
        }
        // A tap outside closes the whole stack, like the close button; back and a drag go up one level.
        // Some systems report that tap as a down event beyond the window rather than as ACTION_OUTSIDE.
        popup.setTouchInterceptor { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN &&
                (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height)) parent = null
            false
        }
        val handle = FrameLayout(ui.context).apply {
            addView(View(ui.context).apply {
                background = ui.rounded(ColorUtils.setAlphaComponent(ui.palette.muted, 96), 2)
            }, FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(handle, ui.bar(14))
        header = ui.row()
        header.addView(ui.medium(ui.singleLine(ui.label(title, 20f))).apply { isFocusable = true },
            ui.sized(0, ui.dp(52), 1f))
        // A header action sits beside the close button, never in its place: a tall panel leaves little
        // outside to tap, so the close button is the one dismissal every panel keeps within reach.
        headerAction?.invoke(this)?.let { header.addView(it, ui.sized(ui.wrap(), ui.wrap()).apply { rightMargin = ui.dp(4) }) }
        header.addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.action_close)) { leave() }, ui.tappable())
        panel.addView(header, ui.bar(52))
        enableDrag(handle)
        enableDrag(header)
    }

    @SuppressLint("ClickableViewAccessibility") // The close button and back key remain the accessible ways to dismiss.
    private fun enableDrag(view: View) {
        var startY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; panel.animate().cancel() }
                MotionEvent.ACTION_MOVE -> panel.translationY = (event.rawY - startY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (panel.translationY > ui.dp(96)) dismiss()
                    else panel.animate().translationY(0f).setDuration(150).start()
                }
            }
            true
        }
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
        panel.maxHeight = ((height - top - bottom) * 0.88f).toInt()
        popup.width = minOf(width - ui.dp(16), ui.dp(600))
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom + ui.dp(8))
        open.add(this)
        frame.post {
            val container = frame.rootView
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            if (secure()) params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
            params.dimAmount = 0.45f
            runCatching { (ui.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(container, params) }
        }
    }

    fun dismiss() { if (popup.isShowing) popup.dismiss() }

    /** Closes this panel without going back to the one it was opened from. */
    fun leave() { parent = null; dismiss() }

    /** Makes this a page of another panel: a back arrow, the back key or a drag reopen it through [show]. */
    fun backTo(show: () -> Unit) {
        // Pulled into the padding so the arrow, not its touch area, lines up with other panels' titles.
        if (parent == null) header.addView(ui.iconAction(R.drawable.ic_arrow_back_24, ui.str(R.string.action_back)) { dismiss() }, 0,
            ui.tappable().apply { leftMargin = -ui.dp(12) })
        parent = show
    }

    companion object {
        private val open = mutableSetOf<BrowserSheet>()
        /** Closes every panel shown over [anchor]; they are windows of their own, so nothing drawn in the page can cover them. */
        fun dismissAll(anchor: View) = open.filter { it.anchor === anchor }.forEach(BrowserSheet::leave)
    }
}
