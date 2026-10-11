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
        val form = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
        val dialog = host.browserDialog().setTitle(R.string.ai_key).setView(form)
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
            val result = runCatching { DeepSeek.balance(host.assistant.http, key) }
            row.setSubtitle(result.fold(
                onSuccess = { ui.str(R.string.ai_test_ok, it.ifBlank { "—" }) },
                onFailure = { error ->
                    when ((error as? DeepSeek.Failure)?.problem) {
                        DeepSeek.Problem.KEY -> ui.str(R.string.ai_error_key)
                        DeepSeek.Problem.NETWORK, null -> ui.str(R.string.ai_error_network)
                        else -> ui.str(R.string.ai_error_server)
                    }
                }))
        }
    }

    private companion object {
        const val KEY_PAGE = "https://platform.deepseek.com/api_keys"
    }
}
