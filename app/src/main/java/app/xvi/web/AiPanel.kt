package app.xvi.web

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
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
    private var picked: ((List<android.net.Uri>) -> Unit)? = null
    private val imagePicker = host.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        picked?.also { picked = null }?.invoke(uris)
    }

    /** Opens the page's conversation. Content is sent only after the user chooses an action. */
    fun page(target: BrowserTab) {
        val web = target.view ?: return
        ready {
            target.ai?.takeIf { it.url == target.url && it.document == target.documentVersion }?.let {
                PageChat(target, it).show(); return@ready
            }
            host.lifecycleScope.launch {
                val url = target.url
                val document = target.documentVersion
                val html = web.readString(ReaderExtractor.PAGE_HTML)
                val page = html?.let { withContext(Dispatchers.Default) { runCatching { ReaderExtractor.text(it, url) }.getOrNull() } }
                val text = page?.second.orEmpty()
                // A page of pictures alone can still be asked about.
                if (text.isBlank() && AiPageImages(host, target).list().isNullOrEmpty()) {
                    if (host.tab === target) host.toast(R.string.ai_page_empty)
                    return@launch
                }
                if (host.tab !== target || target.view !== web || target.documentVersion != document || target.url != url) return@launch
                val title = page?.first.orEmpty().ifBlank { host.displayTitle(target) }
                val chat = AiConversation(target.url, target.documentVersion, title,
                    mutableListOf(DeepSeek.Message(DeepSeek.Message.SYSTEM, AiPrompts.page(title, url, text))))
                target.ai = chat
                PageChat(target, chat).show()
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
                // Pictures go again with every follow-up, so a model without vision fails each time.
                val pictures = failure.problem == DeepSeek.Problem.REQUEST && messages.any { it.images.isNotEmpty() }
                answer.fail(problem(failure) + if (pictures) "\n" + ui.str(R.string.ai_error_images) else "", failure.problem == DeepSeek.Problem.KEY)
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

    /** Summary and follow-up questions about one page, with pictures from the page or the album. */
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
        private val addImages: ImageButton = ui.iconAction(R.drawable.ic_image_24, ui.str(R.string.ai_add_images)) { choosePageImages() }
        private var job: Job? = null
        private var latest: Answer? = null
        /** Pictures for the next question; [Attachment.key] names a page picture so the picker can show it checked. */
        private val attachments = mutableListOf<Attachment>()
        private val attachmentRow = ui.row()
        private val attachmentScroll = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(attachmentRow)
            visibility = View.GONE
        }
        /** Shown until the first question: the page's two starting actions. */
        private val opening = ui.column()
        private var working: Job? = null
        private var pageImagePicker: AiPageImagePicker? = null
        private val pictures = AiPageImages(host, target)

        fun show() {
            sheet.add(ui.singleLine(ui.label(chat.title, 12f, ui.palette.muted)))
            // Pictures sit on the left, as in the input bar; summary is the main action and takes the room.
            val actions = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
            actions.addView(ui.textAction(ui.str(R.string.ai_add_images)) { choosePageImages() }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(ui.tintedIcon(R.drawable.ic_image_24, ui.palette.accent), null, null, null)
                compoundDrawablePadding = ui.dp(6)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
            actions.addView(ui.textAction(ui.str(R.string.ai_summary_question)) { if (canSend()) ask(AiPrompts.SUMMARY) },
                LinearLayout.LayoutParams(0, -2, 1f))
            opening.addView(actions)
            sheet.add(opening)
            sheet.add(transcript.view, transcript.params)
            customRow { prompt -> if (canSend()) ask(prompt.text) }?.let(sheet::add)
            sheet.add(attachmentScroll)
            val bar = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            bar.addView(addImages, ui.tappable())
            bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(send, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            sheet.add(bar)
            sheet.onDismiss {
                job?.cancel()
                working?.cancel()
                pageImagePicker?.dismiss()
                picked = null
                attachments.clear()
            }
            dropUnanswered()
            chat.messages.drop(1).forEach { message ->
                if (message.role == DeepSeek.Message.USER) question(message)
                else latest = Answer(transcript, target, ::retry).apply { finish(message.content, null) }
            }
            sheet.show()
            opening.visibility = if (chat.messages.size == 1) View.VISIBLE else View.GONE
        }

        private fun submit() {
            val text = input.text.toString().trim()
            if (text.isEmpty() && attachments.isEmpty() || !canSend()) return
            input.setText("")
            ask(text.ifEmpty { AiPrompts.IMAGES })
        }

        private fun ask(text: String) {
            if (!current()) { host.toast(R.string.ai_send_changed); return }
            dropUnanswered()
            val message = DeepSeek.Message(DeepSeek.Message.USER, text, attachments.map { it.image })
            chat.messages.add(message)
            attachments.clear()
            refreshAttachments()
            opening.visibility = View.GONE
            question(message)
            run()
        }

        private fun current() = target in host.tabs && target.ai === chat && target.url == chat.url && target.documentVersion == chat.document
        private fun canSend() = job?.isActive != true && working?.isActive != true

        private fun room() = minOf(AiImages.MAX_PER_MESSAGE - attachments.size,
            AiImages.MAX_PER_CHAT - chat.messages.sumOf { it.images.size } - attachments.size).coerceAtLeast(0)

        /** Pictures are numbered across the conversation, so the model and the user mean the same one. */
        private fun nextName() = ui.str(R.string.ai_image_label, chat.messages.sumOf { it.images.size } + attachments.size + 1)

        /** Disables sending while pictures are read; [block] runs while the sheet still shows this document. */
        private fun busyWith(block: suspend () -> Unit): Job = host.lifecycleScope.launch {
            send.isEnabled = false
            addImages.isEnabled = false
            try { block() } finally {
                if (sheet.isShowing) { send.isEnabled = true; addImages.isEnabled = true }
            }
        }.also { working = it }

        /** The page's pictures as a grid, with the album as its first tile; straight to the album when there are none. */
        private fun choosePageImages() {
            if (!canSend() || !current()) return
            if (room() == 0 && attachments.none { it.key != null }) { host.toast(R.string.ai_image_limit); return }
            var album = false
            busyWith {
                // Taken again each time, so pictures the page loaded since are offered too.
                val images = pictures.list()
                if (!sheet.isShowing || !current() || images == null) return@busyWith
                if (images.isEmpty()) { album = true; return@busyWith }
                val selected = attachments.mapNotNull { it.key }.toSet()
                val limit = room() + attachments.count { it.key != null }
                var then: (() -> Unit)? = null
                pageImagePicker = AiPageImagePicker(host, target, pictures, images, selected, limit, album = { then = ::chooseAlbum }) { chosen ->
                    pageImagePicker = null
                    if (sheet.isShowing && current() && canSend()) attachPage(chosen, then)
                }.also { it.show() }
            }.invokeOnCompletion { if (album && sheet.isShowing) chooseAlbum() }
        }

        /** Applies the picker's choice, then runs [then] once the new pictures are read. */
        private fun attachPage(chosen: List<AiImages.PageImage>, then: (() -> Unit)?) {
            attachments.removeAll { attachment -> attachment.key != null && chosen.none { it.key == attachment.key } }
            refreshAttachments()
            val added = chosen.filter { image -> attachments.none { it.key == image.key } }
            if (added.isEmpty()) { then?.invoke(); return }
            busyWith {
                for (image in added) {
                    if (room() == 0) { host.toast(R.string.ai_image_limit); break }
                    val attached = pictures.attach(image, image.name.ifBlank { nextName() })
                    if (!sheet.isShowing || !current()) return@busyWith
                    if (attached == null) host.toast(R.string.ai_image_failed) else attachments.add(Attachment(attached, image.key))
                    refreshAttachments()
                }
            }.invokeOnCompletion { if (then != null && sheet.isShowing) then() }
        }

        private fun chooseAlbum() {
            if (!canSend() || !current()) return
            if (room() == 0) { host.toast(R.string.ai_image_limit); return }
            picked = { uris ->
                if (sheet.isShowing && current() && canSend() && uris.isNotEmpty()) {
                    val available = room()
                    if (uris.size > available) host.toast(R.string.ai_image_limit)
                    busyWith {
                        for (uri in uris.take(available)) {
                            val image = try { withContext(Dispatchers.IO) { AiImages.read(host.contentResolver, uri, nextName()) } }
                                catch (_: java.io.IOException) { null }
                                catch (_: SecurityException) { null }
                            if (!sheet.isShowing || !current()) return@busyWith
                            if (image == null) host.toast(R.string.ai_image_failed) else attachments.add(Attachment(image, null))
                            refreshAttachments()
                        }
                    }
                }
            }
            try { imagePicker.launch("image/*") }
            catch (_: android.content.ActivityNotFoundException) { picked = null; host.toast(R.string.upload_unavailable) }
        }

        /** Thumbnails of the pictures for the next question, each with a button that removes it. */
        private fun refreshAttachments() {
            attachmentRow.removeAllViews()
            attachmentScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
            attachments.forEach { attachment ->
                val image = attachment.image
                val item = FrameLayout(host).apply {
                    background = ui.rounded(ui.palette.base, 10)
                    clipToOutline = true
                }
                val bitmap = image.preview?.let(AiImages::decodePreview)
                item.addView(ImageView(host).apply {
                    if (bitmap != null) { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP }
                    else { setImageDrawable(ui.tintedIcon(R.drawable.ic_image_24, ui.palette.muted)); scaleType = ImageView.ScaleType.CENTER }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(-1, -1))
                item.addView(ImageButton(host).apply {
                    setImageDrawable(ui.tintedIcon(R.drawable.ic_close_24, Color.WHITE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }, ui.dp(8))
                    setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                    contentDescription = ui.str(R.string.ai_remove_image, image.name)
                    tooltipText = contentDescription
                    setOnClickListener { if (working?.isActive != true) { attachments.remove(attachment); refreshAttachments() } }
                }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.TOP or Gravity.END))
                attachmentRow.addView(item, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { rightMargin = ui.dp(8); topMargin = ui.dp(6) })
            }
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
            if (!canSend() || answer !== latest) return
            if (!current()) { host.toast(R.string.ai_send_changed); return }
            if (last() == DeepSeek.Message.ASSISTANT) chat.messages.removeAt(chat.messages.lastIndex)
            if (last() != DeepSeek.Message.USER) return
            transcript.remove(answer.id)
            run()
        }

        private fun last() = chat.messages.lastOrNull()?.role

        /** A question that failed or was stopped before any text is dropped rather than asked twice. */
        private fun dropUnanswered() { if (last() == DeepSeek.Message.USER) chat.messages.removeAt(chat.messages.lastIndex) }

        private fun question(message: DeepSeek.Message) {
            val text = message.content
            val shown = when {
                text == AiPrompts.SUMMARY -> ui.str(R.string.ai_summary_question)
                // Pictures sent without a question show as pictures alone.
                message.images.isNotEmpty() && text == AiPrompts.IMAGES -> ""
                else -> config.prompts.firstOrNull { it.text == text }?.name
            }
            transcript.add(user = true, shown ?: text, message.images)
        }

        private fun setBusy(busy: Boolean) {
            send.isEnabled = working?.isActive != true
            send.setImageDrawable(ui.tintedIcon(if (busy) R.drawable.ic_cancel_24 else R.drawable.ic_arrow_up_24, ui.palette.accent))
            send.describe(ui.str(if (busy) R.string.ai_stop else R.string.ai_send))
        }
    }

    private class Attachment(val image: DeepSeek.Image, val key: String?)

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
     * An answer as it streams in. Pieces arrive many times a second; the page shows the newest text once it
     * has shown the last, and waits about as long as that took before the next, so a long answer never keeps
     * it rendering without pause. Copy and retry appear when it is complete or failed.
     */
    private inner class Answer(private val transcript: AiTranscript, private val target: BrowserTab, private val retry: (Answer) -> Unit) {
        val id = transcript.add(user = false)
        private var latest: CharSequence? = null
        /** Thinking received so far, shown folded above the answer; null until some arrives. */
        private var thought: CharSequence? = null
        private var text = ""
        /** Set from when a render is due until the page has shown it. */
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

        private fun schedule(wait: Long = 0) {
            if (pending || closed) return
            pending = true
            transcript.view.postOnAnimationDelayed({ if (closed) pending = false else render() }, wait)
        }

        /** Shows what arrived since the last render; the next is due once the page has shown this one. */
        private fun render() {
            val thinking = thought?.toString().also { thought = null }
            val answer = latest?.toString().also { latest = null }
            if (thinking == null && answer == null) { pending = false; return }
            val started = SystemClock.uptimeMillis()
            val shown = {
                pending = false
                if (latest != null || thought != null) schedule(SystemClock.uptimeMillis() - started)
            }
            thinking?.let { transcript.reasoning(id, it, shown.takeIf { answer == null }) }
            answer?.let { transcript.update(id, it, shown = shown) }
        }

        fun finish(text: String, note: String?) {
            thought?.let { transcript.reasoning(id, it.toString()); thought = null }
            closed = true
            this.text = text
            transcript.update(id, text, whole = true)
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
        /** Selected text and the block around it, for explanations that depend on context. */
        const val SELECTION = "(() => { try { const s = window.getSelection(); const text = s ? s.toString() : ''; let context = '';" +
            " const n = s && s.anchorNode; const e = n && (n.nodeType === 1 ? n : n.parentElement);" +
            " const block = e && e.closest ? e.closest('p, li, td, th, blockquote, pre, h1, h2, h3, h4, h5, h6, dd, figcaption') : null;" +
            " if (block) context = (block.innerText || '').slice(0, ${AiPrompts.MAX_CONTEXT_CHARS});" +
            " return JSON.stringify({ text: text.slice(0, ${AiPrompts.MAX_SELECTION_CHARS}), context }); } catch (_) { return null; } })()"
    }
}
