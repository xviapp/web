package app.xvi.web

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Suggestions shown for the start-page search field; searches use the default engine from settings.
 * The clipboard is read only when the user taps "paste and go".
 * Typing a known host's beginning completes it inline, selected, so Go opens it and typing replaces it.
 */
internal class SearchOverlay(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private var local: Job? = null
    private var remote: Job? = null
    private val results = ui.column()
    private val clipboardRow = ui.menuRow(R.drawable.ic_paste_24, ui.str(R.string.search_paste_go)) { pasteAndGo() }
    private var matches = emptyList<SuggestionCandidate>()
    private var phrases = emptyList<String>()
    private var completing = false
    private var deleting = false
    /** Completed host currently shown selected after the typed text, if any. */
    private var completion: String? = null
    /** Where to open suggestions held back to keep a page visible; they appear once the user types. */
    private var deferredTop: Int? = null
    /** The input currently being edited, even when suggestions are hidden to keep a page visible. */
    var input: EditText? = null
        private set
    val engine: String get() = host.prefs.engine

    val view: LinearLayout = ui.column().apply {
        setBackgroundColor(palette.base)
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        visibility = View.GONE
    }
    val isVisible get() = view.isVisible

    init {
        clipboardRow.view.background = ui.pressable(palette.surface, 14)
        view.addView(clipboardRow.view, ui.wide().apply { bottomMargin = ui.dp(8) })
        view.addView(ScrollView(host).apply { addView(results); isFillViewport = true }, ui.stretch())
    }

    /** Wires [field] so that edits drive suggestions and inline completion. */
    fun attach(field: EditText) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!completing) deleting = after < count
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (completing || input !== field || !field.hasFocus()) return
                completion = null
                deferredTop?.let { reveal(field, it); return }
                query(field.text.toString())
            }
        })
    }

    fun hint(private: Boolean) =
        ui.str(if (private) R.string.search_hint_private else R.string.search_hint, engine)

    /** Opens suggestions for [target]; without [showSuggestions] they wait until its text is first edited. */
    fun show(target: EditText, top: Int, showSuggestions: Boolean = true) {
        input = target
        completion = null
        if (!showSuggestions) {
            local?.cancel(); remote?.cancel()
            view.isVisible = false
            deferredTop = top
            return
        }
        reveal(target, top)
    }

    private fun reveal(target: EditText, top: Int) {
        deferredTop = null
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = top.coerceAtLeast(0); view.layoutParams = it }
        view.isVisible = true
        val clip = (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClipDescription
        // Only the description is inspected here, so no "pasted from clipboard" notice appears.
        val fresh = clip != null && (clip.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || clip.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) &&
            System.currentTimeMillis() - clip.timestamp < CLIPBOARD_FRESH_MS
        clipboardRow.view.isVisible = fresh
        query(target.text.toString())
    }

    fun hide() {
        local?.cancel(); remote?.cancel()
        view.isVisible = false
        input = null
        completion = null
        deferredTop = null
    }

    private fun pasteAndGo() {
        val text = (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(host)?.toString()?.trim()
        if (text.isNullOrEmpty()) { host.toast(R.string.search_paste_empty); return }
        host.navigate(text.take(BrowserInput.MAX_INPUT), engine)
    }

    /** What the user typed, without a completion shown after it. */
    private fun typed(field: EditText): String {
        val text = field.text.toString()
        val done = completion ?: return text
        val start = field.selectionStart
        // A completion is shown as a selection running to the end of the text.
        val completing = field.selectionEnd == text.length && start in 0 until text.length
        return if (completing && text.equals(done, ignoreCase = true)) text.substring(0, start) else text
    }

    private fun query(raw: String) {
        if (!isVisible) return
        val query = raw.trim()
        val private = host.isPrivateMode
        val engineName = engine
        val store = host.store
        local?.cancel(); remote?.cancel()
        phrases = emptyList()
        local = host.lifecycleScope.launch {
            delay(60)
            val found = withContext(store.io) {
                if (query.isEmpty()) {
                    (if (private) emptyList() else store.entries(false, limit = 6)).map { SuggestionCandidate(it.url, it.title) } to null
                } else store.suggestions(query, includeHistory = !private, limit = 15)
            }
            val field = input ?: return@launch
            if (typed(field).trim() != query) return@launch
            matches = found.first
            complete(field, query, found.second)
            render(query, engineName)
        }
        if (query.isNotEmpty() && !private && host.prefs.searchSuggestions && !BrowserInput.isWebUrl(query)) {
            remote = host.lifecycleScope.launch {
                delay(200)
                val endpoint = BrowserInput.suggestEndpoints[engineName] ?: return@launch
                val found = withContext(Dispatchers.IO) { runCatching { fetch(endpoint, query) }.getOrDefault(emptyList()) }
                val field = input ?: return@launch
                if (typed(field).trim() != query || engine != engineName) return@launch
                phrases = found
                render(query, engineName)
            }
        }
    }

    /** Appends the rest of [host] selected, unless the user is deleting or an IME is still composing. */
    private fun complete(field: EditText, query: String, candidate: String?) {
        val text = field.text
        if (candidate == null || deleting || text.toString() != query || field.selectionEnd != text.length ||
            BaseInputConnection.getComposingSpanStart(text) != -1 || !candidate.startsWith(query, ignoreCase = true)) return
        completing = true
        try {
            text.append(candidate.substring(query.length))
            field.setSelection(query.length, text.length)
            completion = text.toString()
        } finally { completing = false }
    }

    private fun fetch(endpoint: String, query: String): List<String> {
        // Shares the browser's connection pool; only the routing and the short deadline differ.
        val client = BrowserHttp.through(host.network.proxy()).newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(endpoint + URLEncoder.encode(query, "UTF-8")).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONArray(response.body.string()).optJSONArray(1) ?: return emptyList()
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { s -> s.isNotEmpty() && !s.equals(query, ignoreCase = true) } }
                .distinct().take(MAX_PHRASES)
        }
    }

    private fun render(query: String, engineName: String) {
        results.removeAllViews()
        if (query.isNotEmpty()) {
            val completed = completion
            fun open(input: String) = results.addView(ui.menuRow(R.drawable.ic_language_24,
                ui.str(R.string.search_open, BrowserInput.resolve(input, engineName))) { host.navigate(input, engineName) }.view)
            fun search() = results.addView(ui.menuRow(R.drawable.ic_search_24,
                ui.str(R.string.search_with, engineName, query)) { host.navigate(query, engineName) }.view)
            when {
                completed != null -> { open(completed); search() }
                BrowserInput.directUrl(query) != null -> open(query)
                else -> search()
            }
        }
        phrases.forEach { phrase ->
            val row = ui.menuRow(R.drawable.ic_search_24, phrase, iconColor = palette.muted) { host.navigate(phrase, engineName) }
            row.view.addView(fillButton(phrase), ui.tappable())
            results.addView(row.view)
        }
        matches.forEach { entry ->
            val row = ui.menuRow(if (entry.bookmarked) R.drawable.ic_bookmark_24 else R.drawable.ic_history_24, entry.title, host.domain(entry.url),
                if (entry.bookmarked) palette.accent else palette.muted) { host.navigate(entry.url, engineName) }
            row.view.contentDescription = ui.str(R.string.search_row_description, entry.title, host.domain(entry.url))
            row.view.addView(fillButton(entry.url), ui.tappable())
            results.addView(row.view, ui.wide().apply { bottomMargin = ui.dp(2) })
        }
    }

    private fun fillButton(text: String) = ui.iconAction(R.drawable.ic_arrow_back_24, ui.str(R.string.search_fill), palette.muted) {
        input?.let { it.setText(text); it.setSelection(text.length) }
    }.apply { rotation = 135f }

    fun destroy() { local?.cancel(); remote?.cancel() }

    private companion object {
        const val CLIPBOARD_FRESH_MS = 5 * 60 * 1000L
        const val MAX_PHRASES = 4
    }
}
