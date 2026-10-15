package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import kotlin.math.abs

/** Colors resolved from resources, so light and dark stay in one place (values / values-night). */
internal class BrowserPalette(private val context: Context) {
    private fun color(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val base = color(R.color.browser_base)
    val surface = color(R.color.browser_surface)
    val ink = color(R.color.browser_ink)
    val muted = color(R.color.browser_muted)
    val accent = color(R.color.browser_accent)
    val onAccent = color(R.color.browser_on_accent)
    val success = color(R.color.browser_success)
    val warning = color(R.color.browser_warning)
    val caution = color(R.color.browser_caution)
    val danger = color(R.color.browser_danger)
    val selectedSurface = color(R.color.browser_selected_surface)
    val badgeSurface = color(R.color.browser_badge_surface)
    val ripple = color(R.color.browser_ripple)
    val snackbar = color(R.color.browser_snackbar)
    val onSnackbar = color(R.color.browser_on_snackbar)
    val snackbarAction = color(R.color.browser_snackbar_action)

    fun proxy(route: BrowserProxyNetwork.Route) = when (route) {
        BrowserProxyNetwork.Route.PROXY -> success
        BrowserProxyNetwork.Route.CHECKING -> warning
        BrowserProxyNetwork.Route.DIRECT -> caution
        BrowserProxyNetwork.Route.VPN, BrowserProxyNetwork.Route.OFF -> muted
    }
}

/** A list row with an updatable title and subtitle; [toggle] is the switch of a switch row. */
internal class MenuRow(val view: LinearLayout, val icon: ImageView, val title: TextView, val subtitle: TextView, val toggle: SwitchCompat? = null) {
    fun setSubtitle(text: CharSequence?) {
        subtitle.text = text
        subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Greys the row out and stops it, and its switch, from responding. */
    fun setEnabled(enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
        toggle?.isEnabled = enabled
    }

    /** Shows a site's own icon in place of the row's symbol, inset to the symbol's size. */
    fun setSiteIcon(bitmap: Bitmap, inset: Int) {
        icon.setImageBitmap(bitmap)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setPadding(inset, inset, inset, inset)
    }
}

/** The same text as [View.setContentDescription] and as the long-press tooltip. */
internal fun View.describe(text: CharSequence) { contentDescription = text; tooltipText = text }

/** Announces a section title as a heading; older systems have no such role. */
internal fun View.markHeading() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }

/** Confirmation feel for a completed gesture, where the system offers one. */
internal val CONFIRM_HAPTIC = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY

/**
 * A picture decoded at a power-of-two reduction so neither side exceeds [maxSide] by much. [decode]
 * reads the source with the options it is given, once for the size and once for the pixels.
 */
internal fun decodeSampled(maxSide: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return decode(BitmapFactory.Options().apply { inSampleSize = sample })
}

/** A sideways drag rather than a scroll: past [slop] and clearly more horizontal than vertical. */
internal fun isSidewaysDrag(dx: Float, dy: Float, slop: Int) = abs(dx) > slop && abs(dx) > abs(dy) * 1.5f

/** Programmatic view kit shared by the browser chrome and its panels. */
internal class BrowserUi(val context: Context, val palette: BrowserPalette) {
    private val density = context.resources.displayMetrics.density

    /** Design pixels to real pixels. */
    fun dp(value: Int) = (value * density).toInt()
    fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * Layout sizes, named so a declaration reads as its intent. The framework's own constants are
     * used through these instead of `-1` / `-2`, which carry no meaning at the call site.
     */
    fun fill() = MATCH_PARENT
    fun wrap() = WRAP_CONTENT

    /** Row in a vertical LinearLayout: spans the width, as tall as its content. */
    fun wide() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    /** Full-width row at a fixed height: a progress bar, a header. */
    fun bar(heightDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))

    /**
     * Full-width child of a FrameLayout pinned to an edge. It must be FrameLayout params: a FrameLayout
     * converts any other kind on addView and keeps only size and margins, so the gravity would be lost.
     */
    fun anchored(gravity: Int) = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, gravity)

    /** Row in a row or column whose parent decides the size on both axes. */
    fun full() = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    /** Fixed size, given in design pixels on both axes. */
    fun box(widthDp: Int, heightDp: Int) = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))

    /** Fixed width and height, for the cases that are not measured in design pixels. */
    fun sized(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    fun sized(width: Int, height: Int, weight: Float) = LinearLayout.LayoutParams(width, height, weight)

    /** Share of the leftover space on the main axis, as tall as its content. */
    fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)

    /** Share of the leftover space, filling the cross axis — a list that should scroll, for example. */
    fun stretch(weight: Float = 1f) = LinearLayout.LayoutParams(MATCH_PARENT, 0, weight)

    /** A tappable square; 48dp is the smallest target the browser allows. */
    fun tappable(sizeDp: Int = TAP_TARGET_DP) = box(sizeDp, sizeDp)

    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun label(text: CharSequence, size: Float = 15f, color: Int = palette.ink) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    fun singleLine(view: TextView, where: TextUtils.TruncateAt = TextUtils.TruncateAt.END) = view.apply { maxLines = 1; ellipsize = where }
    fun medium(view: TextView) = view.apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    /** Filled background with press feedback. */
    fun pressable(color: Int, radius: Int = 16): Drawable =
        RippleDrawable(ColorStateList.valueOf(palette.ripple), rounded(color, radius), rounded(Color.WHITE, radius))
    /** Transparent background with rounded press feedback. */
    fun selectable(radius: Int = 12): Drawable =
        RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rounded(Color.WHITE, radius))
    fun borderless(): Drawable = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, null)

    /** Announce custom clickable views as buttons to accessibility services. */
    fun <V : View> asButton(view: V): V = view.apply {
        isFocusable = true
        isClickable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    fun tintedIcon(@DrawableRes resource: Int, color: Int = palette.ink): Drawable? =
        AppCompatResources.getDrawable(context, resource)?.mutate()?.also { DrawableCompat.setTint(it, color) }

    fun iconAction(@DrawableRes resource: Int, description: String, color: Int = palette.ink, click: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, resource))
        imageTintList = ColorStateList.valueOf(color)
        background = borderless()
        contentDescription = description
        tooltipText = description
        isFocusable = true
        minimumWidth = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { click() }
    }

    fun textAction(text: String, primary: Boolean = false, click: () -> Unit) =
        asButton(label(text, 14f, if (primary) palette.onAccent else palette.ink)).apply {
            gravity = Gravity.CENTER
            background = pressable(if (primary) palette.accent else palette.base, 14)
            minimumHeight = dp(48)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            singleLine(this)
            setOnClickListener { click() }
        }

    fun sectionTitle(text: String) = label(text, 12f, palette.muted).apply { setPadding(dp(4), dp(14), dp(4), dp(5)) }

    fun menuRow(@DrawableRes icon: Int, title: String, subtitle: String? = null, iconColor: Int = palette.accent, click: (() -> Unit)? = null): MenuRow {
        val view = row().apply {
            minimumHeight = dp(if (subtitle == null) 56 else 64)
            setPadding(dp(4), dp(4), dp(8), dp(4))
            background = selectable()
            if (click != null) { asButton(this); setOnClickListener { click() } }
        }
        val image = ImageView(context).apply {
            setImageDrawable(tintedIcon(icon, iconColor))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        view.addView(image, LinearLayout.LayoutParams(dp(48), dp(48)))
        val copy = column()
        val titleView = singleLine(label(title, 15f))
        val subtitleView = singleLine(label("", 12f, palette.muted))
        copy.addView(titleView)
        copy.addView(subtitleView)
        view.addView(copy, weighted())
        return MenuRow(view, image, titleView, subtitleView).also { it.setSubtitle(subtitle) }
    }

    /** Menu row with a trailing switch; the whole row toggles it and reads as one switch. */
    fun switchRow(@DrawableRes icon: Int, title: String, checked: Boolean, changed: (Boolean) -> Unit): MenuRow {
        val row = menuRow(icon, title)
        val toggle = SwitchCompat(context).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.view.addView(toggle, sized(WRAP_CONTENT, WRAP_CONTENT))
        row.view.setOnClickListener { toggle.toggle() }
        row.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return MenuRow(row.view, row.icon, row.title, row.subtitle, toggle)
    }

    /** Fields and options inside an alert dialog, inset to line up with its title. */
    fun dialogForm(vararg views: View, top: Int = 4) = column().apply {
        setPadding(dp(20), dp(top), dp(20), 0)
        views.forEach { addView(it, wide()) }
    }

    /** Dropdown of [labels] under [anchor]; [selected] is highlighted, and picking one dismisses it. */
    fun popupList(anchor: View, labels: List<String>, width: Int, selected: Int = -1, color: (Int) -> Int = { palette.ink },
                  onPick: (Int) -> Unit) = ListPopupWindow(context).apply {
        anchorView = anchor
        isModal = true
        this.width = width
        horizontalOffset = anchor.width - width
        setBackgroundDrawable(pressable(palette.surface, 12))
        setAdapter(object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (convertView as? TextView ?: singleLine(label("", 15f))).apply {
                    text = labels[position]
                    setTextColor(color(position))
                    minimumHeight = dp(48)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = if (position == selected) pressable(palette.selectedSurface, 8) else selectable()
                }
        })
        setOnItemClickListener { _, _, position, _ -> dismiss(); onPick(position) }
        show()
        if (selected >= 0) setSelection(selected)
    }

    fun edit(hint: String) = EditText(context).apply {
        this.hint = hint; setTextColor(palette.ink); setHintTextColor(palette.muted); textSize = 16f; isSingleLine = true
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    fun emptyState(text: String) = label(text, 15f, palette.muted).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), dp(40), dp(8), dp(40))
    }

    /** Site icon when known, otherwise the title's first character on a tinted tile. */
    fun siteBadge(title: String, url: String, icon: Bitmap?, size: Int, textSize: Float, color: Int = palette.accent, radius: Int = 12): View {
        if (icon != null) return ImageView(context).apply {
            setImageDrawable(icon.toDrawable(context.resources))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dp(size) / 4
            setPadding(inset, inset, inset, inset)
            background = rounded(palette.badgeSurface, radius)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val initial = title.trim().take(1).ifBlank { (BrowserStore.siteKey(url) ?: "").take(1).uppercase() }.ifBlank { "·" }
        return label(initial, textSize, color).apply {
            gravity = Gravity.CENTER
            background = rounded(palette.badgeSurface, radius)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** The framework's own size constants, named once so a declaration reads as its intent. */
    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Smallest target the browser allows for anything tappable. */
        const val TAP_TARGET_DP = 48
    }

    fun onTextChanged(view: EditText, action: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }.also(view::addTextChangedListener)
}

/** Vertical layout that never grows beyond [maxHeight]; weighted children shrink to fit. */
@SuppressLint("ViewConstructor") // Built in code only.
internal class CappedColumn(context: Context, var maxHeight: Int) : LinearLayout(context) {
    init { orientation = VERTICAL }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/** Short message with an optional action, floating at the bottom of [container]. */
internal class BrowserSnackbar(private val ui: BrowserUi, container: FrameLayout, private val bottomOffset: () -> Int = { 0 }) {
    private val palette = ui.palette
    private val main = Handler(Looper.getMainLooper())
    private val text = ui.label("", 14f, palette.onSnackbar).apply { maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val action = ui.asButton(ui.medium(ui.label("", 14f, palette.snackbarAction))).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(12), 0, ui.dp(12), 0)
        background = ui.selectable(10)
    }
    private val view = ui.row().apply {
        background = ui.rounded(palette.snackbar, 14)
        elevation = ui.dp(6).toFloat()
        setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4))
        minimumHeight = ui.dp(48)
        visibility = View.GONE
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val hide = Runnable { view.animate().alpha(0f).setDuration(150).withEndAction { view.visibility = View.GONE }.start() }

    init {
        action.minimumHeight = ui.dp(48)
        container.addView(view, ui.anchored(Gravity.BOTTOM).apply { setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(8)) })
    }

    fun show(message: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
        main.removeCallbacks(hide)
        view.animate().cancel()
        text.text = message
        action.text = actionText
        action.visibility = if (actionText == null) View.GONE else View.VISIBLE
        action.setOnClickListener { dismiss(); onAction?.invoke() }
        (view.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomOffset() + ui.dp(8)
        view.requestLayout()
        view.alpha = 0f
        view.isVisible = true
        view.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hide, if (actionText == null) 3000L else 5000L)
    }

    fun dismiss() { main.removeCallbacks(hide); if (view.isVisible) hide.run() }
}

/**
 * Row that follows a horizontal drag and calls [onDismiss] once dragged past a third of its width.
 * Taps and vertical scrolling pass through; buttons inside remain the accessible way to act.
 */
@SuppressLint("ViewConstructor") // Built in code only.
internal class SwipeRow(context: Context, private val onDismiss: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.rawX; startY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startX
                if (isSidewaysDrag(dx, ev.rawY - startY, slop)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility") // The close button is the accessible path.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        val dx = ev.rawX - startX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> { translationX = dx; alpha = 1f - minOf(1f, abs(dx) / width.coerceAtLeast(1)) }
            MotionEvent.ACTION_UP -> {
                dragging = false
                if (abs(dx) > width / 3f) {
                    animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat()).alpha(0f).setDuration(120).withEndAction(onDismiss).start()
                } else animate().translationX(0f).alpha(1f).setDuration(120).start()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; animate().translationX(0f).alpha(1f).setDuration(120).start() }
        }
        return true
    }
}
