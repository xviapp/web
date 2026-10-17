package app.xvi.web

import android.text.InputFilter
import android.text.InputType

/** Adds, changes and removes the user's own assistant instructions; [changed] runs after each save. */
internal class AiPromptEditor(private val host: BrowserActivity) {
    private val ui = host.ui
    private val config = host.aiConfig

    /** The saved instructions, plus a way to add one while there is room. */
    fun list(changed: () -> Unit) {
        val prompts = config.prompts
        val labels = prompts.map { it.name } + listOfNotNull(ui.str(R.string.ai_prompt_add).takeIf { prompts.size < AiPrompts.MAX_CUSTOM })
        host.browserDialog().setTitle(R.string.ai_prompts)
            .setItems(labels.toTypedArray()) { _, index -> edit(prompts.getOrNull(index)) { changed(); list(changed) } }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** Edits [prompt], or makes a new one when it is null. */
    fun edit(prompt: AiPrompts.Custom?, changed: () -> Unit) {
        val name = ui.edit(ui.str(R.string.ai_prompt_name)).apply {
            filters = arrayOf(InputFilter.LengthFilter(AiPrompts.MAX_CUSTOM_NAME))
            prompt?.let { setText(it.name) }
        }
        val text = ui.edit(ui.str(R.string.ai_prompt_text)).apply {
            isSingleLine = false; maxLines = 6; minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            filters = arrayOf(InputFilter.LengthFilter(AiPrompts.MAX_CUSTOM_TEXT))
            prompt?.let { setText(it.text) }
        }
        val note = ui.label(ui.str(R.string.ai_prompt_note), 13f, ui.palette.muted)
        fun save(value: AiPrompts.Custom?) {
            val rest = config.prompts.toMutableList()
            val index = rest.indexOf(prompt)
            when {
                value == null -> rest.remove(prompt)
                index >= 0 -> rest[index] = value
                else -> rest.add(value)
            }
            config.prompts = rest
            changed()
        }
        val dialog = host.browserDialog().setTitle(if (prompt == null) R.string.ai_prompt_add else R.string.ai_prompt_edit)
            .setView(ui.dialogForm(name, text, note))
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        if (prompt != null) dialog.setNeutralButton(R.string.action_delete) { _, _ -> save(null) }
        dialog.create().showChecked {
            val title = name.text.toString().trim()
            val body = text.text.toString().trim()
            when {
                title.isEmpty() -> { name.error = ui.str(R.string.ai_prompt_name_missing); false }
                body.isEmpty() -> { text.error = ui.str(R.string.ai_prompt_text_missing); false }
                else -> { save(AiPrompts.Custom(title, body)); true }
            }
        }
        name.requestFocus()
    }
}
