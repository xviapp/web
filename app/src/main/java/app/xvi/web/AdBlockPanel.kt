package app.xvi.web

import android.content.res.ColorStateList
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.net.toUri
import java.text.DateFormat

/** Filter lists: switch them on or off, add or remove subscriptions, and update them now. */
internal class AdBlockPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val sheet = host.sheet(R.string.adblock_lists).apply { back?.let(::backTo) }
    private val body = ui.column()
    private val rows = HashMap<String, MenuRow>()
    /** While an update runs: what each list is doing, in place of its usual status. */
    private val live = HashMap<String, String>()
    /** Lists whose last update failed, so the reason stays in view afterwards. */
    private val failed = HashSet<String>()
    private var running = false
    private var total = 0
    private var finished = 0
    private val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ui.palette.accent)
        visibility = View.INVISIBLE
    }
    private lateinit var updateButton: TextView

    fun show() {
        render()
        sheet.add(progress, ui.bar(3))
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        buttons.addView(ui.textAction(ui.str(R.string.adblock_add)) { addSubscription() }, ui.weighted())
        updateButton = ui.textAction(ui.str(R.string.adblock_update), true) { update() }
        buttons.addView(updateButton, ui.weighted().apply { leftMargin = ui.dp(8) })
        sheet.add(buttons)
        sheet.show()
    }

    private fun render() {
        body.removeAllViews()
        rows.clear()
        AdBlock.lists(prefs).forEach { list ->
            val info = AdBlock.info[list.id]
            val name = info?.name ?: list.name
            val enabled = AdBlock.isEnabled(prefs, list)
            val row = if (list.builtIn) ui.switchRow(R.drawable.ic_shield_24, name, enabled) { on -> setEnabled(list, on) }
                else ui.menuRow(R.drawable.ic_shield_24, name, iconColor = if (enabled) ui.palette.accent else ui.palette.muted) { subscription(list, name, enabled) }
            row.setSubtitle(live[list.id] ?: status(list, info, enabled))
            rows[list.id] = row
            body.addView(row.view)
        }
    }

    private fun status(list: AdBlock.FilterList, info: AdBlock.ListInfo?, enabled: Boolean): String = when {
        !enabled -> LIST_NOTES[list.id]?.let(ui::str) ?: ui.str(R.string.adblock_list_off)
        list.id in failed -> ui.str(R.string.adblock_progress_failed)
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

    /** Downloads [only] those lists, or every enabled one, showing each list's progress in its row. */
    private fun update(only: Set<String>? = null) {
        if (running) return
        running = true
        total = 0
        finished = 0
        failed.removeAll(only ?: failed.toSet())
        progress.visibility = View.VISIBLE
        updateButton.isEnabled = false
        updateButton.text = ui.str(R.string.adblock_updating)
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            AdBlock.update(host, host.network.proxy(), force = only == null, only = only, progress = ::onProgress) { updated, broken ->
                if (host.isDestroyed) return@update
                running = false
                live.clear()
                progress.visibility = View.INVISIBLE
                updateButton.isEnabled = true
                updateButton.text = ui.str(R.string.adblock_update)
                render()
                sheet.showSnackbar(when {
                    broken > 0 -> ui.str(R.string.adblock_update_failed, broken)
                    updated > 0 -> ui.str(R.string.adblock_updated_count, updated)
                    else -> ui.str(R.string.adblock_update_nothing)
                })
            }
        }
    }

    private fun onProgress(step: AdBlock.Progress) {
        if (host.isDestroyed) return
        when (step) {
            is AdBlock.Progress.Waiting -> {
                total = step.ids.size
                step.ids.forEach { show(it, ui.str(R.string.adblock_progress_waiting)) }
            }
            is AdBlock.Progress.Downloading -> {
                val from = source(step.id, step.source)
                show(step.id, when {
                    step.total > 0 -> ui.str(R.string.adblock_progress_percent, from, (step.received * 100 / step.total).toInt())
                    step.received > 0 -> ui.str(R.string.adblock_progress_downloading, from, Formatter.formatShortFileSize(host, step.received))
                    else -> ui.str(R.string.adblock_progress_connecting, from)
                })
            }
            is AdBlock.Progress.Downloaded -> { finished++; show(step.id, ui.str(R.string.adblock_progress_done)) }
            is AdBlock.Progress.Failed -> { finished++; failed.add(step.id); show(step.id, ui.str(R.string.adblock_progress_failed)) }
            AdBlock.Progress.Applying -> updateButton.text = ui.str(R.string.adblock_progress_applying)
        }
        if (step !is AdBlock.Progress.Applying && total > 0) updateButton.text = ui.str(R.string.adblock_progress_button, finished, total)
    }

    private fun show(id: String, text: String) {
        live[id] = text
        rows[id]?.setSubtitle(text)
    }

    /** Names where a list is coming from: its official address, or the mirror by host. */
    private fun source(id: String, index: Int): String {
        val list = AdBlock.lists(prefs).firstOrNull { it.id == id } ?: return ""
        if (index == 0) return ui.str(if (list.builtIn) R.string.adblock_source_official else R.string.adblock_source_subscription)
        return when (val host = list.mirrors.getOrNull(index - 1)?.toUri()?.host) {
            "cdn.jsdelivr.net" -> "jsDelivr"
            "raw.githubusercontent.com" -> "GitHub"
            else -> host.orEmpty()
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
            host.browserDialog().setTitle(R.string.adblock_custom).setView(ui.dialogForm(field))
                .setPositiveButton(R.string.action_save) { _, _ ->
                    host.prefs.adblockCustomRules = field.text.toString().trim()
                    AdBlock.reload(host, done)
                }
                .setNegativeButton(R.string.action_cancel, null).show()
        }
    }
}
