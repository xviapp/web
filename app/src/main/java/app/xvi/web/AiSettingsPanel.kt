package app.xvi.web

import android.text.InputType
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Settings → AI assistant: on/off, the API address (DeepSeek or another OpenAI-compatible service),
 * the key, the model, thinking, the user's own instructions and a connection test.
 */
internal class AiSettingsPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val config = host.aiConfig
    private val models = AiModelPicker(host)
    private val sheet = host.sheet(R.string.ai_title).apply { back?.let(::backTo) }

    fun show() {
        val body = ui.column()
        body.addView(ui.switchRow(R.drawable.ic_ai_24, ui.str(R.string.ai_enabled), config.enabled) { config.enabled = it }.view)
        lateinit var endpoint: MenuRow
        lateinit var key: MenuRow
        lateinit var model: MenuRow
        fun refresh() { endpoint.setSubtitle(baseLabel()); key.setSubtitle(keyLabel()); model.setSubtitle(models.label(config.model)) }
        endpoint = ui.menuRow(R.drawable.ic_language_24, ui.str(R.string.ai_base), baseLabel()) { editBase { refresh(); if (!DeepSeek.isDefault(config.baseUrl)) models.edit(::refresh) } }
        body.addView(endpoint.view)
        key = ui.menuRow(R.drawable.ic_lock_24, ui.str(R.string.ai_key), keyLabel()) { editKey(::refresh) }
        body.addView(key.view)
        model = ui.menuRow(R.drawable.ic_apps_24, ui.str(R.string.ai_model), models.label(config.model)) { models.choose(::refresh) }
        body.addView(model.view)
        lateinit var thinking: MenuRow
        fun thinkingLabel() = ui.str(R.string.ai_thinking_mode_status, effortLabel(config.thinking))
        thinking = ui.menuRow(R.drawable.ic_ai_24, ui.str(R.string.ai_thinking_mode), thinkingLabel()) {
            chooseEffort { thinking.setSubtitle(thinkingLabel()) }
        }
        body.addView(thinking.view)
        lateinit var prompts: MenuRow
        prompts = ui.menuRow(R.drawable.ic_article_24, ui.str(R.string.ai_prompts), promptsLabel()) {
            AiPromptEditor(host).list { prompts.setSubtitle(promptsLabel()) }
        }
        body.addView(prompts.view)
        lateinit var test: MenuRow
        test = ui.menuRow(R.drawable.ic_refresh_24, ui.str(R.string.ai_test)) { test(test) }
        body.addView(test.view)
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.show()
    }

    private fun effortLabel(effort: DeepSeek.Effort) = ui.str(when (effort) {
        DeepSeek.Effort.OFF -> R.string.ai_effort_off
        DeepSeek.Effort.LOW -> R.string.ai_effort_low
        DeepSeek.Effort.HIGH -> R.string.ai_effort_high
        DeepSeek.Effort.MAX -> R.string.ai_effort_max
    })

    private fun chooseEffort(saved: () -> Unit) {
        val efforts = DeepSeek.Effort.entries
        host.browserDialog().setTitle(R.string.ai_thinking_mode)
            .setSingleChoiceItems(efforts.map(::effortLabel).toTypedArray(), efforts.indexOf(config.thinking)) { dialog, index ->
                dialog.dismiss()
                config.thinking = efforts[index]
                saved()
            }.show()
    }

    private fun promptsLabel() = config.prompts.size.let { if (it == 0) ui.str(R.string.ai_prompts_none) else ui.str(R.string.ai_prompts_count, it) }

    private fun keyLabel() = config.apiKey?.let { ui.str(R.string.ai_key_set, it.takeLast(4)) } ?: ui.str(R.string.ai_key_unset)

    private fun baseLabel() = config.baseUrl.takeUnless(DeepSeek::isDefault) ?: ui.str(R.string.ai_base_default)

    /**
     * Another service gets its own key: changing the address forgets the saved one, so a DeepSeek key
     * is never sent to a server it was not made for.
     */
    private fun editBase(saved: () -> Unit) {
        val current = config.baseUrl
        val field = ui.edit(ui.str(R.string.ai_base_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (!DeepSeek.isDefault(current)) setText(current)
        }
        val note = ui.label(ui.str(R.string.ai_base_note), 13f, ui.palette.muted)
        fun apply(base: String) {
            if (base == config.baseUrl) return
            if (config.apiKey != null) { config.apiKey = null; host.toast(R.string.ai_base_key_cleared) }
            config.baseUrl = base
            if (DeepSeek.isDefault(base)) config.model = DeepSeek.DEFAULT_MODEL
            saved()
        }
        val dialog = host.browserDialog().setTitle(R.string.ai_base).setView(ui.dialogForm(field, note))
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        if (!DeepSeek.isDefault(current)) dialog.setNeutralButton(R.string.ai_base_reset) { _, _ -> apply(DeepSeek.BASE) }
        dialog.create().showChecked {
            val input = field.text.toString()
            val base = if (input.isBlank()) DeepSeek.BASE else DeepSeek.normalizeBase(input)
            if (base == null) { field.error = ui.str(R.string.ai_base_invalid); false } else { apply(base); true }
        }
        field.requestFocus()
    }

    private fun editKey(saved: () -> Unit) {
        val field = ui.edit(ui.str(R.string.ai_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            host.applyInputPrivacy(this, true)
        }
        val dialog = host.browserDialog().setTitle(R.string.ai_key).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        // A saved key can be cleared; until then the same button leads to DeepSeek's key page.
        if (config.apiKey != null) dialog.setNeutralButton(R.string.ai_key_clear) { _, _ -> config.apiKey = null; saved() }
        else if (DeepSeek.isDefault(config.baseUrl)) dialog.setNeutralButton(R.string.ai_key_get) { _, _ -> sheet.leave(); host.addTab(KEY_PAGE) }
        dialog.create().showChecked {
            val value = field.text.toString().trim()
            when {
                value.isEmpty() -> true
                !DeepSeek.isValidKey(value) -> { field.error = ui.str(R.string.ai_key_invalid); false }
                else -> { config.apiKey = value; saved(); true }
            }
        }
        field.requestFocus()
    }

    private fun test(row: MenuRow) {
        val key = config.apiKey ?: run { row.setSubtitle(ui.str(R.string.ai_key_unset)); return }
        row.setSubtitle(ui.str(R.string.ai_test_running))
        val base = config.baseUrl
        host.lifecycleScope.launch {
            val status = try {
                if (DeepSeek.isDefault(base)) ui.str(R.string.ai_test_ok, DeepSeek.balance(key).ifBlank { "—" })
                else ui.str(R.string.ai_test_ok_models, DeepSeek.modelCount(base, key))
            } catch (failure: DeepSeek.Failure) {
                host.assistant.problem(failure)
            }
            row.setSubtitle(status)
        }
    }

    private companion object {
        const val KEY_PAGE = "https://platform.deepseek.com/api_keys"
    }
}
