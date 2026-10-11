package app.xvi.web

import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/** One page's conversation, kept on its tab until the tab shows another document. Never stored. */
internal class AiConversation(val url: String, val document: Long, val title: String, val messages: MutableList<DeepSeek.Message>)

/** The AI assistant's bottom panels: page summary with follow-up questions, and selected-text actions. */
internal class AiPanel(private val host: BrowserActivity) {
    private val ui get() = host.ui
    private val config get() = host.aiConfig

    /** Summarizes the page, or reopens its conversation when the same document is still showing. */
    fun page(target: BrowserTab) {
        val web = target.view ?: return
        ready { key ->
            target.ai?.takeIf { it.url == target.url && it.document == target.documentVersion }?.let {
                PageChat(target, key, it).show(null); return@ready
            }
            host.lifecycleScope.launch {
                val html = web.readString(OUTER_HTML)
                if (host.tab !== target || target.view !== web) return@launch
                val page = html?.let { withContext(Dispatchers.Default) { runCatching { ReaderExtractor.text(it, target.url) }.getOrNull() } }
                if (page == null || page.second.isBlank()) { host.toast(R.string.ai_page_empty); return@launch }
                val title = page.first.ifBlank { host.displayTitle(target) }
                val chat = AiConversation(target.url, target.documentVersion, title,
                    mutableListOf(DeepSeek.Message("system", AiPrompts.page(title, target.url, page.second))))
                target.ai = chat
                PageChat(target, key, chat).show(AiPrompts.SUMMARY)
            }
        }
    }

    /** Reads the selection before [finish] closes the selection menu, which clears it. */
    fun selection(target: BrowserTab, web: WebView, finish: () -> Unit) {
        host.lifecycleScope.launch {
            val raw = web.readString(SELECTION)
            finish()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@launch
            val text = json.optString("text").trim()
            if (text.isEmpty() || target !in host.tabs) return@launch
            ready { key -> SelectionSheet(target, key, text, json.optString("context"), target.title).show() }
        }
    }

    /** Runs [action] with the API key, or opens the settings when there is none yet. */
    private fun ready(action: (String) -> Unit) {
        if (!config.enabled) return
        val key = config.apiKey
        if (key == null) { host.toast(R.string.ai_needs_key); AiSettingsPanel(host).show(); return }
        action(key)
    }

    /**
     * DeepSeek is reached directly, never through the in-app proxy: it is a mainland service, a
     * proxy node would only add latency, and page text should not pass through a third party.
     */
    val http: OkHttpClient get() = BrowserHttp.base()

    private fun problem(failure: DeepSeek.Failure) = when (failure.problem) {
        DeepSeek.Problem.KEY -> ui.str(R.string.ai_error_key)
        DeepSeek.Problem.BALANCE -> ui.str(R.string.ai_error_balance)
        DeepSeek.Problem.RATE -> ui.str(R.string.ai_error_rate)
        DeepSeek.Problem.SERVER -> ui.str(R.string.ai_error_server)
        DeepSeek.Problem.NETWORK -> ui.str(R.string.ai_error_network)
        DeepSeek.Problem.REQUEST -> ui.str(R.string.ai_error_request, failure.detail.orEmpty())
    }

    /** Streams one answer into [answer]; [messages] must end with the user's turn. */
    private fun stream(key: String, messages: List<DeepSeek.Message>, answer: Answer, done: (String?) -> Unit): Job {
        val text = StringBuilder()
        return host.lifecycleScope.launch {
            try {
                val reason = DeepSeek.chat(http, key, config.model, messages) { piece -> text.append(piece); answer.update(text) }
                answer.finish(text.toString(), if (reason == "length") ui.str(R.string.ai_truncated) else null)
                done(text.toString())
            } catch (failure: DeepSeek.Failure) {
                answer.fail(problem(failure), failure.problem == DeepSeek.Problem.KEY)
                done(null)
            } catch (cancelled: CancellationException) {
                if (text.isNotEmpty()) answer.finish(text.toString(), ui.str(R.string.ai_stopped))
                done(text.takeIf { it.isNotEmpty() }?.toString())
                throw cancelled
            }
        }
    }

    /** Summary and follow-up questions about one page. */
    private inner class PageChat(private val target: BrowserTab, private val key: String, private val chat: AiConversation) {
        private val sheet = BrowserSheet(ui, host.root, ui.str(R.string.ai_title), { target.isPrivate || host.isPrivateMode })
        private val list = ui.column()
        private val scroll = ScrollView(host).apply { addView(list) }
        private val input = ui.edit(ui.str(R.string.ai_ask_hint)).apply {
            isSingleLine = false; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            background = ui.rounded(ui.palette.base, 20)
            host.applyInputPrivacy(this, target.isPrivate)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        private val send: ImageButton = ui.iconAction(R.drawable.ic_arrow_up_24, ui.str(R.string.ai_send), ui.palette.accent) { if (job?.isActive == true) job?.cancel() else submit() }
        private var job: Job? = null

        fun show(first: String?) {
            sheet.add(ui.singleLine(ui.label(chat.title, 12f, ui.palette.muted)))
            sheet.add(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            val bar = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(send, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            sheet.add(bar)
            sheet.onDismiss { job?.cancel() }
            // An unanswered question (failed or stopped before any text) is dropped rather than shown again.
            if (chat.messages.lastOrNull()?.role == "user") chat.messages.removeAt(chat.messages.lastIndex)
            chat.messages.drop(1).forEach { message ->
                if (message.role == "user") question(message.content)
                else Answer(list, target, ::retry).finish(message.content, null)
            }
            sheet.show()
            if (first != null) ask(first)
            scrollToEnd()
        }

        private fun submit() {
            val text = input.text.toString().trim()
            if (text.isEmpty() || job?.isActive == true) return
            input.setText("")
            ask(text)
        }

        private fun ask(text: String) {
            if (chat.messages.lastOrNull()?.role == "user") chat.messages.removeAt(chat.messages.lastIndex)
            chat.messages.add(DeepSeek.Message("user", text))
            question(text)
            run()
        }

        private fun run() {
            val answer = Answer(list, target, ::retry)
            setBusy(true)
            scrollToEnd()
            job = stream(key, chat.messages.toList(), answer) { text ->
                if (text != null) chat.messages.add(DeepSeek.Message("assistant", text))
                setBusy(false)
            }
        }

        /** Asks the last question again, replacing its answer. */
        private fun retry(answer: Answer) {
            // Only the latest answer can be asked again; earlier ones are part of the conversation.
            if (job?.isActive == true || list.getChildAt(list.childCount - 1) !== answer.view) return
            if (chat.messages.lastOrNull()?.role == "assistant") chat.messages.removeAt(chat.messages.lastIndex)
            if (chat.messages.lastOrNull()?.role != "user") return
            list.removeView(answer.view)
            run()
        }

        private fun question(text: String) {
            val shown = if (text == AiPrompts.SUMMARY) ui.str(R.string.ai_summary_question) else text
            list.addView(ui.label(shown, 15f).apply {
                background = ui.rounded(ui.palette.selectedSurface, 16)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = ui.dp(10); leftMargin = ui.dp(48) })
        }

        private fun setBusy(busy: Boolean) {
            send.setImageDrawable(ui.tintedIcon(if (busy) R.drawable.ic_cancel_24 else R.drawable.ic_arrow_up_24, ui.palette.accent))
            send.contentDescription = ui.str(if (busy) R.string.ai_stop else R.string.ai_send)
            send.tooltipText = send.contentDescription
        }

        private fun scrollToEnd() = scroll.post { scroll.scrollTo(0, list.height) }
    }

    /** Explain, translate, summarize or rewrite the selected text. */
    private inner class SelectionSheet(private val target: BrowserTab, private val key: String,
                                       private val text: String, private val context: String, private val title: String) {
        private val sheet = BrowserSheet(ui, host.root, ui.str(R.string.ai_selection_title), { target.isPrivate || host.isPrivateMode })
        private val result = ui.column()
        private var job: Job? = null
        private var last: AiPrompts.Selection? = null

        fun show() {
            sheet.add(ui.label(text, 13f, ui.palette.muted).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.rounded(ui.palette.base, 12)
            })
            val kinds = listOf(
                AiPrompts.Selection.EXPLAIN to R.string.ai_explain, AiPrompts.Selection.TRANSLATE to R.string.ai_translate,
                AiPrompts.Selection.SUMMARIZE to R.string.ai_summarize, AiPrompts.Selection.REWRITE to R.string.ai_rewrite)
            val chips = ui.row().apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) }
            kinds.forEachIndexed { index, (kind, label) ->
                chips.addView(ui.textAction(ui.str(label)) { run(kind) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = ui.dp(6) })
            }
            sheet.add(chips)
            sheet.add(ScrollView(host).apply { addView(result) }, LinearLayout.LayoutParams(-1, 0, 1f))
            sheet.onDismiss { job?.cancel() }
            sheet.show()
        }

        private fun run(kind: AiPrompts.Selection) {
            job?.cancel()
            last = kind
            result.removeAllViews()
            val messages = listOf(DeepSeek.Message("system", AiPrompts.SELECTION_SYSTEM),
                DeepSeek.Message("user", AiPrompts.selection(kind, text, context, title)))
            job = stream(key, messages, Answer(result, target) { last?.let(::run) }) { }
        }
    }

    /**
     * An answer as it streams in. Pieces arrive many times a second, so the text is re-rendered at
     * most once per frame; copy and retry appear when it is complete or failed.
     */
    private inner class Answer(private val parent: LinearLayout, private val target: BrowserTab, private val retry: (Answer) -> Unit) {
        val view = ui.column()
        private val body = ui.label(ui.str(R.string.ai_thinking), 15f, ui.palette.muted).apply {
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }
        private val actions = ui.row().apply { visibility = View.GONE }
        private var latest: CharSequence = ""
        private var pending = false
        /** Set once complete or failed; a render already queued for the next frame is then dropped. */
        private var closed = false

        init {
            view.addView(body, LinearLayout.LayoutParams(-1, -2))
            view.addView(actions, LinearLayout.LayoutParams(-1, -2))
            parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }

        fun update(text: CharSequence) {
            latest = text
            if (pending || closed) return
            pending = true
            body.postOnAnimation {
                pending = false
                if (closed) return@postOnAnimation
                body.setTextColor(ui.palette.ink)
                body.text = AiText.render(latest.toString())
            }
        }

        fun finish(text: String, note: String?) {
            closed = true
            body.setTextColor(ui.palette.ink)
            body.text = AiText.render(text)
            showActions(text, note, settings = false)
        }

        fun fail(message: String, settings: Boolean) {
            closed = true
            body.setTextColor(ui.palette.danger)
            body.text = message
            showActions(null, null, settings)
        }

        private fun showActions(text: String?, note: String?, settings: Boolean) {
            actions.removeAllViews()
            note?.let { actions.addView(ui.label(it, 12f, ui.palette.muted), LinearLayout.LayoutParams(0, -2, 1f)) }
                ?: actions.addView(View(host), LinearLayout.LayoutParams(0, 0, 1f))
            if (!text.isNullOrBlank()) actions.addView(ui.iconAction(R.drawable.ic_copy_24, ui.str(R.string.ai_copy), ui.palette.muted) {
                host.copy(text, target.isPrivate)
            })
            if (settings) actions.addView(ui.iconAction(R.drawable.ic_settings_24, ui.str(R.string.ai_title), ui.palette.muted) { AiSettingsPanel(host).show() })
            actions.addView(ui.iconAction(R.drawable.ic_refresh_24, ui.str(R.string.ai_retry), ui.palette.muted) { retry(this) })
            actions.visibility = View.VISIBLE
        }
    }

    private companion object {
        const val OUTER_HTML = "document.documentElement ? document.documentElement.outerHTML : ''"
        /** Selected text and the block around it, for explanations that depend on context. */
        const val SELECTION = "(() => { try { const s = window.getSelection(); const text = s ? s.toString() : ''; let context = '';" +
            " const n = s && s.anchorNode; const e = n && (n.nodeType === 1 ? n : n.parentElement);" +
            " const block = e && e.closest ? e.closest('p, li, td, th, blockquote, pre, h1, h2, h3, h4, h5, h6, dd, figcaption') : null;" +
            " if (block) context = (block.innerText || '').slice(0, 1200);" +
            " return JSON.stringify({ text: text.slice(0, 8000), context }); } catch (_) { return null; } })()"
    }
}

/** The small part of Markdown that model answers use: headings, lists, bold and code. */
internal object AiText {
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^(\\s*)[-*+]\\s+")
    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    fun render(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        var code = false
        markdown.trimEnd().lines().forEachIndexed { index, raw ->
            if (raw.trimStart().startsWith("```")) { code = !code; return@forEachIndexed }
            if (index > 0 && out.isNotEmpty()) out.append('\n')
            if (code) { span(out, raw, TypefaceSpan("monospace")); return@forEachIndexed }
            var line = raw
            val isHeading = heading.containsMatchIn(line)
            if (isHeading) line = line.replace(heading, "")
            bullet.find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.range.last + 1) }
            val start = out.length
            var cursor = 0
            inline.findAll(line).forEach { match ->
                out.append(line, cursor, match.range.first)
                match.groups[1]?.let { span(out, it.value, StyleSpan(Typeface.BOLD)) }
                    ?: span(out, match.groups[2]!!.value, TypefaceSpan("monospace"))
                cursor = match.range.last + 1
            }
            out.append(line, cursor, line.length)
            if (isHeading) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun span(out: SpannableStringBuilder, text: String, what: Any) {
        val start = out.length
        out.append(text)
        out.setSpan(what, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
