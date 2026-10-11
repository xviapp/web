package app.xvi.web

import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Settings → AI assistant: on/off, the DeepSeek API key, the model and a connection test. */
internal class AiSettingsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val config = host.aiConfig
    private val sheet = host.sheet(R.string.ai_title)

    fun show() {
        val body = ui.column()
        body.addView(ui.switchRow(R.drawable.ic_ai_24, ui.str(R.string.ai_enabled), config.enabled) { config.enabled = it }.view)
        lateinit var key: MenuRow
        key = ui.menuRow(R.drawable.ic_lock_24, ui.str(R.string.ai_key), keyLabel()) { editKey { key.setSubtitle(keyLabel()) } }
        body.addView(key.view)
        lateinit var model: MenuRow
        model = ui.menuRow(R.drawable.ic_apps_24, ui.str(R.string.ai_model), modelLabel(config.model)) {
            val models = DeepSeek.models
            host.browserDialog().setTitle(R.string.ai_model)
                .setSingleChoiceItems(models.map(::modelLabel).toTypedArray(), models.indexOf(config.model)) { dialog, index ->
                    config.model = models[index]
                    model.setSubtitle(modelLabel(models[index]))
                    dialog.dismiss()
                }.show()
        }
        body.addView(model.view)
        lateinit var test: MenuRow
        test = ui.menuRow(R.drawable.ic_refresh_24, ui.str(R.string.ai_test)) { test(test) }
        body.addView(test.view)
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.show()
    }

    private fun keyLabel() = config.apiKey?.let { ui.str(R.string.ai_key_set, it.takeLast(4)) } ?: ui.str(R.string.ai_key_unset)

    private fun modelLabel(model: String) = ui.str(if (model == DeepSeek.DEFAULT_MODEL) R.string.ai_model_flash else R.string.ai_model_pro, model)

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
            .setNeutralButton(R.string.ai_key_get) { _, _ -> sheet.dismiss(); host.addTab(KEY_PAGE) }
            .setNegativeButton(R.string.action_cancel, null)
        // A saved key can be cleared; until then the same button leads to DeepSeek's key page.
        if (config.apiKey != null) dialog.setNeutralButton(R.string.ai_key_clear) { _, _ -> config.apiKey = null; saved() }
        dialog.show()
        field.requestFocus()
    }

    private fun test(row: MenuRow) {
        val key = config.apiKey ?: run { row.setSubtitle(ui.str(R.string.ai_key_unset)); return }
        row.setSubtitle(ui.str(R.string.ai_test_running))
        host.lifecycleScope.launch {
            val status = try {
                ui.str(R.string.ai_test_ok, DeepSeek.balance(key).ifBlank { "—" })
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
