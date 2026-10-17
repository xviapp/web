package app.xvi.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Holds a full-screen video. An upward or downward drag on the left side sets the screen brightness,
 * on the right side the volume. Taps, sideways drags and the middle of the screen stay with the
 * page's own player, and drags that start near the top or bottom edge are left to the system bars.
 * The brightness applies to this window only and ends with full screen.
 */
@SuppressLint("ViewConstructor") // Built in code only.
internal class VideoGestures(private val ui: BrowserUi, private val window: Window, private val audio: AudioManager) : FrameLayout(ui.context) {
    private enum class Control { BRIGHTNESS, VOLUME }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val edge = ui.dp(48)
    private var startX = 0f
    private var startY = 0f
    /** Set once a drag is taken; the value it started from, as a fraction of the full range. */
    private var control: Control? = null
    private var startValue = 0f
    private val hint = ui.medium(ui.label("", 15f, Color.WHITE)).apply {
        background = ui.rounded(0x99000000.toInt(), 20)
        setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
        visibility = View.GONE
    }
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(200).withEndAction { hint.visibility = View.GONE }.start() }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** Puts [video] under the gestures; the hint stays on top of it. */
    fun hold(video: View) {
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Gives the video back to the page and the brightness back to the system. */
    fun release() {
        removeCallbacks(hideHint)
        removeAllViews()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = detect(ev)

    /** Takes the gesture once it is clearly an up or down drag on one side. */
    private fun detect(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y; control = null }
            MotionEvent.ACTION_MOVE -> if (control == null) {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) control = side()?.also { start(it) }
            }
        }
        return control != null
    }

    @SuppressLint("ClickableViewAccessibility") // The player's own controls and the volume keys remain.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Reached directly when the video itself takes no touches: keep the gesture while it may become a drag.
        if (control == null) return detect(ev) || (ev.actionMasked == MotionEvent.ACTION_DOWN && side() != null)
        val active = control ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // A drag over the whole height covers the whole range; upward raises it.
                val value = (startValue + (startY - ev.y) / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                apply(active, value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                control = null
                postDelayed(hideHint, HINT_MS)
            }
        }
        return true
    }

    /** The control under the drag's start, or null in the middle, near an edge, or with fixed volume. */
    private fun side(): Control? {
        if (startY < edge || startY > height - edge) return null
        return when {
            startX < width * SIDE -> Control.BRIGHTNESS
            startX > width * (1 - SIDE) && !audio.isVolumeFixed -> Control.VOLUME
            else -> null
        }
    }

    private fun start(control: Control) {
        startValue = when (control) {
            Control.BRIGHTNESS -> window.attributes.screenBrightness.takeIf { it >= 0f } ?: systemBrightness()
            Control.VOLUME -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume()
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        apply(control, startValue)
    }

    private fun apply(control: Control, value: Float) {
        val percent = when (control) {
            Control.BRIGHTNESS -> {
                // Never fully dark, so the screen cannot be lost.
                val level = value.coerceAtLeast(0.01f)
                window.attributes = window.attributes.apply { screenBrightness = level }
                (value * 100).roundToInt()
            }
            Control.VOLUME -> {
                val index = (value * maxVolume()).roundToInt()
                // Do Not Disturb can refuse the change; the hint then shows what really applies.
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume()
            }
        }
        hint.text = ui.str(if (control == Control.BRIGHTNESS) R.string.video_brightness else R.string.video_volume, percent)
        removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
    }

    private fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** The system setting as a fraction; only a starting point, since devices map it to light differently. */
    private fun systemBrightness() =
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f).coerceIn(0f, 1f)

    private companion object {
        /** Width of each side's strip, as a share of the screen; the middle belongs to the player. */
        const val SIDE = 0.4f
        const val HINT_MS = 600L
    }
}
