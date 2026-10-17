package app.xvi.web

import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        ready {
            target.ai?.takeIf { it.url == target.url && it.document == target.documentVersion }?.let {
                PageChat(target, it).show(null); return@ready
            }
            host.lifecycleScope.launch {
                val html = web.readString(OUTER_HTML)
                if (host.tab !== target || target.view !== web) return@launch
                val page = html?.let { withContext(Dispatchers.Default) { runCatching { ReaderExtractor.text(it, target.url) }.getOrNull() } }
                if (page == null || page.second.isBlank()) { host.toast(R.string.ai_page_empty); return@launch }
                val title = page.first.ifBlank { host.displayTitle(target) }
                val chat = AiConversation(target.url, target.documentVersion, title,
                    mutableListOf(DeepSeek.Message(DeepSeek.Message.SYSTEM, AiPrompts.page(title, target.url, page.second))))
                target.ai = chat
                PageChat(target, chat).show(AiPrompts.SUMMARY)
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
            ready { SelectionSheet(target, text, json.optString("context"), target.title).show() }
        }
    }

    /** Opens the settings when no API key has been configured yet. */
    private fun ready(action: () -> Unit) {
        if (!config.enabled) return
        val key = config.apiKey
        if (key == null) { host.toast(R.string.ai_needs_key); AiSettingsPanel(host).show(); return }
        action()
    }

    /** What went wrong, in the words the panel and the settings both show. */
    fun problem(failure: DeepSeek.Failure) = when (failure.problem) {
        DeepSeek.Problem.KEY -> ui.str(R.string.ai_error_key)
        DeepSeek.Problem.BALANCE -> ui.str(R.string.ai_error_balance)
        DeepSeek.Problem.RATE -> ui.str(R.string.ai_error_rate)
        DeepSeek.Problem.SERVER -> ui.str(R.string.ai_error_server)
        DeepSeek.Problem.NETWORK -> ui.str(R.string.ai_error_network)
        DeepSeek.Problem.REQUEST -> ui.str(R.string.ai_error_request, failure.detail.orEmpty())
    }

    /** Streams one answer into [answer]; [messages] must end with the user's turn. */
    private fun stream(target: BrowserTab, messages: List<DeepSeek.Message>, answer: Answer, done: (String?) -> Unit): Job {
        val text = StringBuilder()
        val thought = StringBuilder()
        return host.lifecycleScope.launch {
            try {
                val base = config.baseUrl
                val key = config.apiKey
                val document = target.documentVersion
                if (!config.enabled || key == null) {
                    answer.fail(ui.str(R.string.ai_needs_key), true)
                    done(null)
                    return@launch
                }
                if (!AiSendConsent(host).confirm(target, base)) {
                    answer.finish("", ui.str(R.string.ai_send_cancelled))
                    done(null)
                    return@launch
                }
                // A dialog can outlive its page or settings. Never send to a service other than
                // the one named in it, or reuse a key captured by an older conversation panel.
                if (!config.enabled || config.baseUrl != base || config.apiKey != key ||
                    target !in host.tabs || target.documentVersion != document) {
                    answer.fail(ui.str(R.string.ai_send_changed), false)
                    done(null)
                    return@launch
                }
                val reason = DeepSeek.chat(base, key, config.model, messages, config.thinking,
                    onReasoning = { piece -> thought.append(piece); answer.think(thought) }) { piece -> text.append(piece); answer.update(text) }
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

    /** Header button naming the model; tapping it switches models for the next answer. */
    private fun modelSwitch(sheet: BrowserSheet): View {
        val button = ui.asButton(ui.singleLine(ui.label("", 12f, ui.palette.muted))).apply {
            maxWidth = ui.dp(140)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(10), 0, ui.dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
            background = ui.selectable(14)
        }
        fun refresh() {
            button.text = config.model
            button.contentDescription = ui.str(R.string.ai_model_switch, config.model)
        }
        refresh()
        button.setOnClickListener { AiModelPicker(host).choose(::refresh) }
        return button
    }

    /** A sideways row of the user's own instructions, or null when there are none. */
    private fun customRow(run: (AiPrompts.Custom) -> Unit): View? {
        val prompts = config.prompts.takeIf { it.isNotEmpty() } ?: return null
        val row = ui.row().apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) }
        prompts.forEachIndexed { index, prompt ->
            row.addView(ui.textAction(prompt.name) { run(prompt) },
                LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) leftMargin = ui.dp(6) })
        }
        return HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** Summary and follow-up questions about one page. */
    private inner class PageChat(private val target: BrowserTab, private val chat: AiConversation) {
        private val sheet = host.sheet(ui.str(R.string.ai_title), target, ::modelSwitch)
        private val transcript = transcript(sheet)
        private val input = ui.edit(ui.str(R.string.ai_ask_hint)).apply {
            isSingleLine = false; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            background = ui.rounded(ui.palette.base, 20)
            host.applyInputPrivacy(this, target.isPrivate)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        private val send: ImageButton = ui.iconAction(R.drawable.ic_arrow_up_24, ui.str(R.string.ai_send), ui.palette.accent) {
            if (job?.isActive == true) job?.cancel() else submit()
        }
        private var job: Job? = null
        private var latest: Answer? = null

        fun show(first: String?) {
            sheet.add(ui.singleLine(ui.label(chat.title, 12f, ui.palette.muted)))
            sheet.add(transcript.view, transcript.params)
            customRow { prompt -> if (job?.isActive != true) ask(prompt.text) }?.let(sheet::add)
            val bar = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(send, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            sheet.add(bar)
            sheet.onDismiss { job?.cancel() }
            dropUnanswered()
            chat.messages.drop(1).forEach { message ->
                if (message.role == DeepSeek.Message.USER) question(message.content)
                else latest = Answer(transcript, target, ::retry).apply { finish(message.content, null) }
            }
            sheet.show()
            if (first != null) ask(first)
        }

        private fun submit() {
            val text = input.text.toString().trim()
            if (text.isEmpty() || job?.isActive == true) return
            input.setText("")
            ask(text)
        }

        private fun ask(text: String) {
            dropUnanswered()
            chat.messages.add(DeepSeek.Message(DeepSeek.Message.USER, text))
            question(text)
            run()
        }

        private fun run() {
            val answer = Answer(transcript, target, ::retry)
            latest = answer
            setBusy(true)
            job = stream(target, chat.messages.toList(), answer) { text ->
                if (text != null) chat.messages.add(DeepSeek.Message(DeepSeek.Message.ASSISTANT, text))
                setBusy(false)
            }
        }

        /** Asks the last question again, replacing its answer. */
        private fun retry(answer: Answer) {
            // Only the latest answer can be asked again; earlier ones are part of the conversation.
            if (job?.isActive == true || answer !== latest) return
            if (last() == DeepSeek.Message.ASSISTANT) chat.messages.removeAt(chat.messages.lastIndex)
            if (last() != DeepSeek.Message.USER) return
            transcript.remove(answer.id)
            run()
        }

        private fun last() = chat.messages.lastOrNull()?.role

        /** A question that failed or was stopped before any text is dropped rather than asked twice. */
        private fun dropUnanswered() { if (last() == DeepSeek.Message.USER) chat.messages.removeAt(chat.messages.lastIndex) }

        private fun question(text: String) {
            val shown = if (text == AiPrompts.SUMMARY) ui.str(R.string.ai_summary_question) else config.prompts.firstOrNull { it.text == text }?.name
            transcript.add(user = true, shown ?: text)
        }

        private fun setBusy(busy: Boolean) {
            send.setImageDrawable(ui.tintedIcon(if (busy) R.drawable.ic_cancel_24 else R.drawable.ic_arrow_up_24, ui.palette.accent))
            send.describe(ui.str(if (busy) R.string.ai_stop else R.string.ai_send))
        }
    }

    /** Explain, translate, summarize or rewrite the selected text. */
    private inner class SelectionSheet(private val target: BrowserTab,
                                       private val text: String, private val context: String, private val title: String) {
        private val sheet = host.sheet(ui.str(R.string.ai_selection_title), target, ::modelSwitch)
        private val transcript = transcript(sheet)
        private var job: Job? = null
        /** The request last made, so an answer can be asked for again. */
        private var last: String? = null

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
                chips.addView(ui.textAction(ui.str(label)) { run(AiPrompts.selection(kind, text, context, title)) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = ui.dp(6) })
            }
            sheet.add(chips)
            customRow { prompt -> run(AiPrompts.custom(prompt, text)) }?.let(sheet::add)
            sheet.add(transcript.view, transcript.params)
            sheet.onDismiss { job?.cancel() }
            sheet.show()
        }

        private fun run(request: String) {
            job?.cancel()
            last = request
            transcript.clear()
            val messages = listOf(DeepSeek.Message(DeepSeek.Message.SYSTEM, AiPrompts.SELECTION_SYSTEM),
                DeepSeek.Message(DeepSeek.Message.USER, request))
            job = stream(target, messages, Answer(transcript, target) { last?.let(::run) }) { }
        }
    }

    /** A transcript inside [sheet]: links open as tabs, and the page goes when the sheet does. */
    private fun transcript(sheet: BrowserSheet): AiTranscript {
        val transcript = AiTranscript(host, onLink = { url -> sheet.dismiss(); host.addTab(url) }, onGone = { sheet.dismiss() })
        sheet.onDismiss { transcript.destroy() }
        return transcript
    }

    /**
     * An answer as it streams in. Pieces arrive many times a second, so the page re-renders it at
     * most once per frame; copy and retry appear when it is complete or failed.
     */
    private inner class Answer(private val transcript: AiTranscript, private val target: BrowserTab, private val retry: (Answer) -> Unit) {
        val id = transcript.add(user = false)
        private var latest: CharSequence? = null
        /** Thinking received so far, shown folded above the answer; null until some arrives. */
        private var thought: CharSequence? = null
        private var text = ""
        private var pending = false
        /** Set once complete or failed; a render already queued for the next frame is then dropped. */
        private var closed = false

        init {
            transcript.handlers[id] = { action, index ->
                when (action) {
                    COPY -> host.copy(text, target.isPrivate)
                    CODE -> transcript.code(id, index) { host.copy(it, target.isPrivate) }
                    RETRY -> retry(this)
                    SETTINGS -> AiSettingsPanel(host).show()
                }
            }
        }

        fun update(text: CharSequence) { latest = text; schedule() }

        fun think(text: CharSequence) { thought = text; schedule() }

        private fun schedule() {
            if (pending || closed) return
            pending = true
            transcript.view.postOnAnimation {
                pending = false
                if (!closed) render()
            }
        }

        /** Shows what arrived since the last frame. */
        private fun render() {
            thought?.let { transcript.reasoning(id, it.toString()); thought = null }
            latest?.let { transcript.update(id, it.toString()); latest = null }
        }

        fun finish(text: String, note: String?) {
            thought?.let { transcript.reasoning(id, it.toString()); thought = null }
            closed = true
            this.text = text
            transcript.update(id, text)
            transcript.actions(id, note, listOfNotNull(
                if (text.isBlank()) null else COPY to ui.str(R.string.ai_copy),
                RETRY to ui.str(R.string.ai_retry)))
        }

        fun fail(message: String, settings: Boolean) {
            thought?.let { transcript.reasoning(id, it.toString()); thought = null }
            closed = true
            transcript.fail(id, message)
            transcript.actions(id, null, listOfNotNull(
                if (settings) SETTINGS to ui.str(R.string.ai_title) else null,
                RETRY to ui.str(R.string.ai_retry)))
        }
    }

    private companion object {
        const val COPY = "copy"
        const val CODE = "code"
        const val RETRY = "retry"
        const val SETTINGS = "settings"
        const val OUTER_HTML = "document.documentElement ? document.documentElement.outerHTML : ''"
        /** Selected text and the block around it, for explanations that depend on context. */
        const val SELECTION = "(() => { try { const s = window.getSelection(); const text = s ? s.toString() : ''; let context = '';" +
            " const n = s && s.anchorNode; const e = n && (n.nodeType === 1 ? n : n.parentElement);" +
            " const block = e && e.closest ? e.closest('p, li, td, th, blockquote, pre, h1, h2, h3, h4, h5, h6, dd, figcaption') : null;" +
            " if (block) context = (block.innerText || '').slice(0, ${AiPrompts.MAX_CONTEXT_CHARS});" +
            " return JSON.stringify({ text: text.slice(0, ${AiPrompts.MAX_SELECTION_CHARS}), context }); } catch (_) { return null; } })()"
    }
}
