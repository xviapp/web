package app.xvi.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils
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
    private val popup = PopupWindow(panel, 1, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = ui.dp(16).toFloat()
        animationStyle = R.style.BrowserSheetAnimation
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    private val dismissActions = mutableListOf<() -> Unit>()
    val isShowing get() = popup.isShowing

    init {
        popup.setOnDismissListener { dismissActions.toList().forEach { it() }; dismissActions.clear() }
        val handle = FrameLayout(ui.context).apply {
            addView(View(ui.context).apply {
                background = ui.rounded(ColorUtils.setAlphaComponent(ui.palette.muted, 96), 2)
            }, FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, ui.dp(14)))
        val header = ui.row()
        header.addView(ui.medium(ui.singleLine(ui.label(title, 20f))).apply { isFocusable = true },
            LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        header.addView(headerAction?.invoke(this) ?: ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.action_close)) { dismiss() },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        panel.addView(header, LinearLayout.LayoutParams(-1, ui.dp(52)))
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

    /** Content views; a list that should scroll takes `LayoutParams(-1, 0, 1f)` so it shrinks to fit. */
    fun add(view: View, params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) = panel.addView(view, params)

    fun show() {
        val insets = ViewCompat.getRootWindowInsets(anchor)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val top = insets?.top ?: 0
        val bottom = insets?.bottom ?: 0
        val height = anchor.rootView.height.takeIf { it > 0 } ?: ui.context.resources.displayMetrics.heightPixels
        val width = anchor.rootView.width.takeIf { it > 0 } ?: ui.context.resources.displayMetrics.widthPixels
        panel.maxHeight = ((height - top - bottom) * 0.88f).toInt()
        popup.width = minOf(width - ui.dp(16), ui.dp(600))
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom + ui.dp(8))
        panel.post {
            val container = panel.rootView
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            if (secure()) params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
            params.dimAmount = 0.45f
            runCatching { (ui.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(container, params) }
        }
    }

    fun dismiss() { if (popup.isShowing) popup.dismiss() }
}
