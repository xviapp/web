package app.xvi.web

import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import java.text.DateFormat

/** Filter lists: switch them on or off, add or remove subscriptions, and update them now. */
internal class AdBlockPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val sheet = host.sheet(R.string.adblock_lists)
    private val body = ui.column()

    fun show() {
        render()
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        buttons.addView(ui.textAction(ui.str(R.string.adblock_add)) { addSubscription() }, ui.weighted())
        buttons.addView(ui.textAction(ui.str(R.string.adblock_update), true) { update() }, ui.weighted().apply { leftMargin = ui.dp(8) })
        sheet.add(buttons)
        sheet.show()
    }

    private fun render() {
        body.removeAllViews()
        AdBlock.lists(prefs).forEach { list ->
            val info = AdBlock.info[list.id]
            val name = info?.name ?: list.name
            val enabled = AdBlock.isEnabled(prefs, list)
            val row = if (list.builtIn) ui.switchRow(R.drawable.ic_shield_24, name, enabled) { on -> setEnabled(list, on) }
                else ui.menuRow(R.drawable.ic_shield_24, name, iconColor = if (enabled) ui.palette.accent else ui.palette.muted) { subscription(list, name, enabled) }
            row.setSubtitle(status(list, info, enabled))
            body.addView(row.view)
        }
        body.addView(ui.label(ui.str(R.string.adblock_lists_note), 12f, ui.palette.muted).apply {
            gravity = Gravity.START
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4))
        })
    }

    private fun status(list: AdBlock.FilterList, info: AdBlock.ListInfo?, enabled: Boolean): String = when {
        !enabled -> LIST_NOTES[list.id]?.let(ui::str) ?: ui.str(R.string.adblock_list_off)
        info == null || !AdBlock.available(host, list) -> ui.str(R.string.adblock_list_missing)
        else -> ui.str(R.string.adblock_list_status, info.rules,
            if (info.updated == 0L) ui.str(R.string.adblock_list_bundled)
            else ui.str(R.string.adblock_list_updated, DateFormat.getDateInstance(DateFormat.MEDIUM).format(info.updated)))
    }

    /** A list switched on with nothing on the device is downloaded first. */
    private fun setEnabled(list: AdBlock.FilterList, on: Boolean) {
        AdBlock.setEnabled(prefs, list, on)
        if (on && !AdBlock.available(host, list)) update(setOf(list.id)) else AdBlock.reload(host) { render() }
    }

    private fun subscription(list: AdBlock.FilterList, name: String, enabled: Boolean) {
        host.actionsDialog(name, listOf(
            ui.str(if (enabled) R.string.adblock_disable else R.string.adblock_enable) to { setEnabled(list, !enabled) },
            ui.str(R.string.adblock_remove) to {
                prefs.adblockSubscriptions = prefs.adblockSubscriptions - list.url
                prefs.adblockOff = prefs.adblockOff - list.id
                AdBlock.forget(host, list.id)
                AdBlock.reload(host) { render() }
            },
        ))
    }

    private fun addSubscription() {
        val field = ui.edit(ui.str(R.string.adblock_add_hint)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        host.browserDialog().setTitle(R.string.adblock_add).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.action_cancel, null).create()
            .showChecked {
                val url = field.text.toString().trim()
                if (!BrowserInput.isWebUrl(url)) { field.error = ui.str(R.string.adblock_add_invalid); return@showChecked false }
                if (url !in prefs.adblockSubscriptions) prefs.adblockSubscriptions = prefs.adblockSubscriptions + url
                render()
                update(setOf(AdBlock.subscriptionId(url)))
                true
            }
    }

    /** Downloads [only] those lists, or every enabled one. */
    private fun update(only: Set<String>? = null) {
        host.showSnackbar(ui.str(R.string.adblock_updating))
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            AdBlock.update(host, host.network.proxy(), force = only == null, only = only) { failed ->
                if (host.isDestroyed) return@update
                render()
                host.showSnackbar(if (failed == 0) ui.str(R.string.adblock_updated) else ui.str(R.string.adblock_update_failed, failed))
            }
        }
    }

    companion object {
        /** What an optional list is for, shown while it is off. */
        private val LIST_NOTES = mapOf(
            "easyprivacy" to R.string.adblock_note_easyprivacy,
            "adguard-chinese" to R.string.adblock_note_adguard_chinese,
            "adguard-mobile" to R.string.adblock_note_adguard_mobile,
        )

        /** The user's own rules, applied on top of the lists. */
        fun editCustomRules(host: BrowserActivity, done: () -> Unit = {}) {
            val ui = host.ui
            val field = ui.edit(ui.str(R.string.adblock_custom_hint)).apply {
                setText(host.prefs.adblockCustomRules)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = false
                minLines = 4
                maxLines = 10
                gravity = Gravity.TOP or Gravity.START
                textSize = 13f
            }
            host.browserDialog().setTitle(R.string.adblock_custom).setMessage(R.string.adblock_custom_message).setView(ui.dialogForm(field))
                .setPositiveButton(R.string.action_save) { _, _ ->
                    host.prefs.adblockCustomRules = field.text.toString().trim()
                    AdBlock.reload(host) { done(); host.showSnackbar(ui.str(R.string.adblock_custom_saved)) }
                }
                .setNegativeButton(R.string.action_cancel, null).show()
        }
    }
}
