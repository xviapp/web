package app.xvi.web

import android.text.InputType

/** Shows and changes the assistant's model, from the settings or from an open assistant panel. */
internal class AiModelPicker(private val host: BrowserActivity) {
    private val ui = host.ui
    private val config = host.aiConfig

    fun label(model: String) = when {
        !DeepSeek.isDefault(config.baseUrl) || model !in DeepSeek.models -> model
        else -> ui.str(if (model == DeepSeek.DEFAULT_MODEL) R.string.ai_model_flash else R.string.ai_model_pro, model)
    }

    /** DeepSeek's own models to pick from, plus any other name; another service takes a name only. */
    fun choose(saved: () -> Unit) {
        if (!DeepSeek.isDefault(config.baseUrl)) { edit(saved); return }
        val models = DeepSeek.models
        val labels = models.map(::label) + ui.str(R.string.ai_model_custom)
        host.browserDialog().setTitle(R.string.ai_model)
            .setSingleChoiceItems(labels.toTypedArray(), models.indexOf(config.model).takeIf { it >= 0 } ?: models.size) { dialog, index ->
                dialog.dismiss()
                if (index < models.size) { config.model = models[index]; saved() } else edit(saved)
            }.show()
    }

    fun edit(saved: () -> Unit) {
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

    private companion object {
        const val MAX_MODEL = 100
    }
}
