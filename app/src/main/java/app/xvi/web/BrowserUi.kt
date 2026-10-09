package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.v2ray.ang.api.ProxyPhase
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

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
    val dangerSurface = color(R.color.browser_danger_surface)
    val selectedSurface = color(R.color.browser_selected_surface)
    val badgeSurface = color(R.color.browser_badge_surface)
    val ripple = color(R.color.browser_ripple)
    val snackbar = color(R.color.browser_snackbar)
    val onSnackbar = color(R.color.browser_on_snackbar)
    val snackbarAction = color(R.color.browser_snackbar_action)

    fun proxy(phase: ProxyPhase) = when (phase) {
        ProxyPhase.RUNNING -> success
        ProxyPhase.STARTING -> warning
        ProxyPhase.RECONNECTING -> caution
        ProxyPhase.FAILED -> danger
        ProxyPhase.OFF -> muted
    }
}

/** A list row with an updatable title and subtitle. */
internal class MenuRow(val view: LinearLayout, val icon: ImageView, val title: TextView, val subtitle: TextView) {
    fun setSubtitle(text: CharSequence?) {
        subtitle.text = text
        subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** Programmatic view kit shared by the browser chrome and its panels. */
internal class BrowserUi(val context: Context, val palette: BrowserPalette) {
    private val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

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
            minimumHeight = dp(46)
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
        view.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        return MenuRow(view, image, titleView, subtitleView).also { it.setSubtitle(subtitle) }
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
            setImageDrawable(BitmapDrawable(context.resources, icon))
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
