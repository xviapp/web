package app.xvi.web

import android.content.Context
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.core.view.isVisible

/** Find-in-page bar bound to one WebView at a time. */
internal class FindBar(private val ui: BrowserUi, private val onClose: () -> Unit) {
    private val palette = ui.palette
    private var watcher: TextWatcher? = null
    /** The page being searched, or null while the bar is closed. */
    var web: WebView? = null
        private set

    private val input = ui.edit(ui.str(R.string.find_hint)).apply {
        background = null
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setSingleLine(true)
    }
    private val status = ui.label("", 12f, palette.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
    val view: LinearLayout = ui.row().apply {
        background = ui.rounded(palette.surface, 16)
        setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
        visibility = View.GONE
        addView(input, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        addView(status, LinearLayout.LayoutParams(-2, -2))
        addView(ui.iconAction(R.drawable.ic_arrow_up_24, ui.str(R.string.find_previous)) { web?.findNext(false) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        addView(ui.iconAction(R.drawable.ic_arrow_down_24, ui.str(R.string.find_next)) { web?.findNext(true) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        addView(ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.find_close)) { hide() }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    }
    val isVisible get() = view.isVisible

    fun show(target: WebView) {
        detach()
        view.isVisible = true
        input.setText("")
        status.text = ""
        web = target
        target.setFindListener { active, total, done ->
            if (done && web === target) status.text = if (total == 0) ui.str(R.string.find_none) else "${active + 1}/$total"
        }
        watcher = ui.onTextChanged(input) { if (web === target) target.findAllAsync(it) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { if (web === target) target.findNext(true); true } else false
        }
        // A menu sheet may still be closing; wait for it before asking for the keyboard.
        input.postDelayed({
            if (isVisible && web === target) {
                input.requestFocus()
                imm().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 300L)
    }

    /** Closes the bar; safe to call when it is already closed. */
    fun hide() {
        detach()
        if (!isVisible) return
        imm().hideSoftInputFromWindow(input.windowToken, 0)
        input.setOnEditorActionListener(null)
        view.isVisible = false
        onClose()
    }

    private fun detach() {
        watcher?.let(input::removeTextChangedListener)
        watcher = null
        web?.let { it.clearMatches(); it.setFindListener(null) }
        web = null
    }

    private fun imm() = ui.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
