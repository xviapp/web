package app.xvi.web

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Suggestions shown while an address input has focus. Engine pills pick the engine for this search
 * only; the default stays in settings. The clipboard is read only when the user taps "paste and go".
 */
internal class SearchOverlay(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var generation = 0L
    private val pills = mutableListOf<TextView>()
    private val results = ui.column()
    private val clipboardRow = ui.menuRow(R.drawable.ic_paste_24, ui.str(R.string.search_paste_go)) { pasteAndGo() }
    /** The input currently driving the overlay. */
    var input: EditText? = null
        private set
    /** Engine for the current search; reset to the default each time the overlay opens. */
    var engine: String = host.prefs.engine
        private set

    val view: LinearLayout = ui.column().apply {
        setBackgroundColor(palette.base)
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        visibility = View.GONE
    }
    val isVisible get() = view.visibility == View.VISIBLE

    init {
        clipboardRow.view.background = ui.pressable(palette.surface, 14)
        view.addView(clipboardRow.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val engines = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        BrowserInput.engines.keys.forEach { name ->
            val pill = ui.asButton(ui.label(name, 13f)).apply {
                gravity = android.view.Gravity.CENTER
                minHeight = ui.dp(36)
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
                setOnClickListener { selectEngine(name) }
            }
            pills.add(pill)
            engines.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        }
        view.addView(HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(engines) }, LinearLayout.LayoutParams(-1, -2))
        view.addView(ScrollView(host).apply { addView(results); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun hint(private: Boolean, name: String = host.prefs.engine) =
        ui.str(if (private) R.string.search_hint_private else R.string.search_hint, name)

    fun show(target: EditText, top: Int) {
        input = target
        engine = host.prefs.engine
        renderPills()
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = top.coerceAtLeast(0); view.layoutParams = it }
        view.visibility = View.VISIBLE
        val clip = (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClipDescription
        // Only the description is inspected here, so no "pasted from clipboard" notice appears.
        val fresh = clip != null && (clip.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || clip.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) &&
            System.currentTimeMillis() - clip.timestamp < CLIPBOARD_FRESH_MS
        clipboardRow.view.visibility = if (fresh) View.VISIBLE else View.GONE
        query(target.text.toString())
    }

    fun hide() {
        pending?.let(handler::removeCallbacks)
        pending = null
        generation++
        view.visibility = View.GONE
        input = null
    }

    private fun selectEngine(name: String) {
        host.haptic()
        engine = name
        renderPills()
        input?.hint = hint(host.isPrivateMode, name)
        input?.let { query(it.text.toString()) }
    }

    private fun renderPills() = pills.forEach { pill ->
        val active = pill.text == engine
        pill.setTextColor(if (active) palette.onAccent else palette.muted)
        pill.background = ui.pressable(if (active) palette.accent else palette.surface, 18)
        pill.isSelected = active
    }

    private fun pasteAndGo() {
        val text = (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(host)?.toString()?.trim()
        if (text.isNullOrEmpty()) { host.toast(R.string.search_paste_empty); return }
        host.navigate(text.take(8192), engine)
    }

    fun query(raw: String) {
        if (!isVisible) return
        val query = raw.trim()
        pending?.let(handler::removeCallbacks)
        val token = ++generation
        val private = host.isPrivateMode
        val engineName = engine
        val work = Runnable {
            host.io.execute {
                val store = host.store
                val items = if (query.isEmpty()) {
                    if (private) emptyList() else store.entries(false, limit = 6)
                } else {
                    val history = if (private) emptyList() else store.entries(false, query, 15)
                    (store.entries(true, query, 8) + history).distinctBy { it.url }.take(15)
                }
                val bookmarked = store.bookmarkedAmong(items.map { it.url })
                host.runOnUiThread {
                    if (host.isDestroyed || token != generation || !isVisible || input?.text?.toString()?.trim() != query) return@runOnUiThread
                    render(query, engineName, items.map { it to (it.url in bookmarked) })
                }
            }
        }
        pending = work
        handler.postDelayed(work, 80L)
    }

    private fun render(query: String, engineName: String, matches: List<Pair<PageEntry, Boolean>>) {
        results.removeAllViews()
        if (query.isNotEmpty()) {
            val resolved = BrowserInput.resolve(query, engineName)
            val direct = !resolved.startsWith(BrowserInput.engines.getValue(engineName))
            val row = if (direct) ui.menuRow(R.drawable.ic_language_24, ui.str(R.string.search_open, resolved)) { host.navigate(query, engineName) }
                else ui.menuRow(R.drawable.ic_search_24, ui.str(R.string.search_with, engineName, query)) { host.navigate(query, engineName) }
            results.addView(row.view, LinearLayout.LayoutParams(-1, -2))
        }
        matches.forEach { (entry, bookmarked) ->
            val row = ui.menuRow(if (bookmarked) R.drawable.ic_bookmark_24 else R.drawable.ic_history_24, entry.title, host.domain(entry.url),
                if (bookmarked) palette.accent else palette.muted) { host.navigate(entry.url, engineName) }
            row.view.contentDescription = "${entry.title}，${host.domain(entry.url)}"
            row.view.addView(ui.iconAction(R.drawable.ic_arrow_back_24, ui.str(R.string.search_fill), palette.muted) {
                input?.let { it.setText(entry.url); it.setSelection(entry.url.length) }
            }.apply { rotation = 135f }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            results.addView(row.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) })
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        generation++
    }

    private companion object { const val CLIPBOARD_FRESH_MS = 5 * 60 * 1000L }
}
