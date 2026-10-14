package app.xvi.web

import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Settings → AI assistant: on/off, the API address (DeepSeek or another OpenAI-compatible service),
 * the key, the model and a connection test.
 */
internal class AiSettingsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val config = host.aiConfig
    private val sheet = host.sheet(R.string.ai_title)

    fun show() {
        val body = ui.column()
        body.addView(ui.switchRow(R.drawable.ic_ai_24, ui.str(R.string.ai_enabled), config.enabled) { config.enabled = it }.view)
        lateinit var endpoint: MenuRow
        lateinit var key: MenuRow
        lateinit var model: MenuRow
        fun refresh() { endpoint.setSubtitle(baseLabel()); key.setSubtitle(keyLabel()); model.setSubtitle(modelLabel(config.model)) }
        endpoint = ui.menuRow(R.drawable.ic_language_24, ui.str(R.string.ai_base), baseLabel()) { editBase { refresh(); if (!DeepSeek.isDefault(config.baseUrl)) editModel(::refresh) } }
        body.addView(endpoint.view)
        key = ui.menuRow(R.drawable.ic_lock_24, ui.str(R.string.ai_key), keyLabel()) { editKey(::refresh) }
        body.addView(key.view)
        model = ui.menuRow(R.drawable.ic_apps_24, ui.str(R.string.ai_model), modelLabel(config.model)) { chooseModel(::refresh) }
        body.addView(model.view)
        lateinit var test: MenuRow
        test = ui.menuRow(R.drawable.ic_refresh_24, ui.str(R.string.ai_test)) { test(test) }
        body.addView(test.view)
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.show()
    }

    private fun keyLabel() = config.apiKey?.let { ui.str(R.string.ai_key_set, it.takeLast(4)) } ?: ui.str(R.string.ai_key_unset)

    private fun baseLabel() = config.baseUrl.takeUnless(DeepSeek::isDefault) ?: ui.str(R.string.ai_base_default)

    private fun modelLabel(model: String) = when {
        !DeepSeek.isDefault(config.baseUrl) || model !in DeepSeek.models -> model
        else -> ui.str(if (model == DeepSeek.DEFAULT_MODEL) R.string.ai_model_flash else R.string.ai_model_pro, model)
    }

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

    /** DeepSeek's own models to pick from, plus any other name; another service takes a name only. */
    private fun chooseModel(saved: () -> Unit) {
        if (!DeepSeek.isDefault(config.baseUrl)) { editModel(saved); return }
        val models = DeepSeek.models
        val labels = models.map(::modelLabel) + ui.str(R.string.ai_model_custom)
        host.browserDialog().setTitle(R.string.ai_model)
            .setSingleChoiceItems(labels.toTypedArray(), models.indexOf(config.model).takeIf { it >= 0 } ?: models.size) { dialog, index ->
                dialog.dismiss()
                if (index < models.size) { config.model = models[index]; saved() } else editModel(saved)
            }.show()
    }

    private fun editModel(saved: () -> Unit) {
        val field = ui.edit(ui.str(R.string.ai_model_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(config.model)
            selectAll()
        }
        host.browserDialog().setTitle(R.string.ai_model).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create().showChecked {
                val name = field.text.toString().trim()
                if (name.isEmpty() || name.length > MAX_MODEL) { field.error = ui.str(R.string.ai_model_invalid); false }
                else { config.model = name; saved(); true }
            }
        field.requestFocus()
    }

    private fun editKey(saved: () -> Unit) {
        val field = ui.edit(ui.str(R.string.ai_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            host.applyInputPrivacy(this, true)
        }
        val dialog = host.browserDialog().setTitle(R.string.ai_key).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.ai_key_save) { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) { config.apiKey = value; saved() }
            }
            .setNegativeButton(R.string.action_cancel, null)
        // A saved key can be cleared; until then the same button leads to DeepSeek's key page.
        if (config.apiKey != null) dialog.setNeutralButton(R.string.ai_key_clear) { _, _ -> config.apiKey = null; saved() }
        else if (DeepSeek.isDefault(config.baseUrl)) dialog.setNeutralButton(R.string.ai_key_get) { _, _ -> sheet.dismiss(); host.addTab(KEY_PAGE) }
        dialog.show()
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
        const val MAX_MODEL = 100
    }
}
