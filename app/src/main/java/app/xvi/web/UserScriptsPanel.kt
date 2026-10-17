package app.xvi.web

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/** Installed user scripts: add them, switch them on or off, allow them in private tabs, edit, update or remove them. */
internal class UserScriptsPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val sheet = host.sheet(R.string.userscripts_title).apply { back?.let(::backTo) }
    private val body = ui.column()
    private lateinit var updateButton: TextView

    fun show() {
        render()
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        if (UserScripts.available) {
            val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            buttons.addView(ui.textAction(ui.str(R.string.userscripts_add)) { add() }, ui.weighted())
            updateButton = ui.textAction(ui.str(R.string.userscripts_check_updates), true) { update() }
            buttons.addView(updateButton, ui.weighted().apply { leftMargin = ui.dp(8) })
            sheet.add(buttons)
        }
        sheet.show()
    }

    private fun render() {
        body.removeAllViews()
        if (!UserScripts.available) { body.addView(ui.emptyState(ui.str(R.string.userscript_unavailable))); return }
        body.addView(ui.switchRow(R.drawable.ic_code_24, ui.str(R.string.userscripts_enabled), host.prefs.userScripts) {
            host.prefs.userScripts = it
            UserScripts.settingsChanged()
            sheet.showSnackbar(ui.str(R.string.userscripts_reload_hint))
        }.view)
        val scripts = UserScripts.scripts
        if (scripts.isEmpty()) { body.addView(ui.emptyState(ui.str(R.string.userscripts_empty))); return }
        body.addView(ui.sectionTitle(ui.str(R.string.userscripts_installed)))
        scripts.forEach { script ->
            val row = ui.menuRow(R.drawable.ic_code_24, script.meta.name, status(script),
                iconColor = if (script.enabled) ui.palette.accent else ui.palette.muted) { actions(script) }
            body.addView(row.view)
        }
    }

    private fun status(script: UserScript) = listOfNotNull(
        script.meta.version.takeIf { it.isNotEmpty() }?.let { ui.str(R.string.userscript_version, it) },
        if (script.enabled) null else ui.str(R.string.userscripts_off),
        if (script.allowPrivate) ui.str(R.string.userscripts_in_private) else null,
        if (!script.meta.hasTargets) ui.str(R.string.userscripts_no_sites) else null,
        if (script.meta.unsupportedGrants.isEmpty()) null else ui.str(R.string.userscripts_partly_supported),
    ).joinToString(" · ")

    private fun actions(script: UserScript) {
        fun changed(message: Int = R.string.userscripts_reload_hint) { render(); sheet.showSnackbar(ui.str(message)) }
        host.actionsDialog(script.meta.name, listOf(
            ui.str(if (script.enabled) R.string.userscripts_disable else R.string.userscripts_enable) to {
                UserScripts.setEnabled(script.id, !script.enabled); changed()
            },
            ui.str(if (script.allowPrivate) R.string.userscripts_private_disallow else R.string.userscripts_private_allow) to {
                UserScripts.setAllowPrivate(script.id, !script.allowPrivate); changed()
            },
            ui.str(R.string.userscripts_edit) to { edit(script) },
        ) + listOfNotNull(
            if (script.allowedHosts.isEmpty()) null else ui.str(R.string.userscripts_hosts_clear, script.allowedHosts.size) to {
                UserScripts.clearAllowedHosts(script.id); changed(R.string.userscripts_hosts_cleared)
            },
        ) + listOf(
            ui.str(R.string.action_delete) to {
                host.confirm(ui.str(R.string.userscripts_remove_confirm, script.meta.name), ui.str(R.string.action_delete), destructive = true) {
                    UserScripts.remove(script.id); changed(R.string.userscripts_removed)
                }
            },
        ), source = null)
    }

    private fun add() {
        host.actionsDialog(ui.str(R.string.userscripts_add), listOf(
            ui.str(R.string.userscripts_add_url) to { addFromUrl() },
            ui.str(R.string.userscripts_add_code) to { edit(null) },
        ), source = null)
    }

    private fun installed(name: String) { render(); sheet.showSnackbar(ui.str(R.string.userscript_installed, name)) }

    private fun addFromUrl() {
        val field = ui.edit(ui.str(R.string.userscripts_add_url_hint)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        host.browserDialog(null).setTitle(R.string.userscripts_add_url).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.userscript_install, null).setNegativeButton(R.string.action_cancel, null).create()
            .showChecked {
                val url = field.text.toString().trim()
                if (!BrowserInput.isWebUrl(url)) { field.error = ui.str(R.string.adblock_add_invalid); return@showChecked false }
                host.scriptInstall.fromUrl(url, installed = ::installed)
                true
            }
    }

    /** Code for a new script, or [script]'s to change; [text] brings back what a failed save left. */
    private fun edit(script: UserScript?, text: String? = null) {
        val field = ui.edit("").apply {
            setText(text ?: script?.code ?: TEMPLATE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 8
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        host.browserDialog(null).setTitle(if (script == null) R.string.userscripts_add_code else R.string.userscripts_edit).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.action_cancel, null).create()
            .showChecked {
                val code = field.text.toString()
                if (UserScriptMeta.parse(code) == null) { field.error = ui.str(R.string.userscript_failed_invalid); return@showChecked false }
                host.scriptInstall.fromCode(code, script, failed = { edit(script, code) }, installed = ::installed)
                true
            }
    }

    private fun update() {
        updateButton.isEnabled = false
        updateButton.text = ui.str(R.string.userscripts_checking)
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            UserScripts.update(host, host.network.proxy(), force = true) { updated, failed ->
                if (host.isDestroyed) return@update
                updateButton.isEnabled = true
                updateButton.text = ui.str(R.string.userscripts_check_updates)
                render()
                sheet.showSnackbar(when {
                    failed > 0 -> ui.str(R.string.userscripts_update_failed, failed)
                    updated > 0 -> ui.str(R.string.userscripts_updated_count, updated)
                    else -> ui.str(R.string.userscripts_update_nothing)
                })
            }
        }
    }

    companion object {
        private const val TEMPLATE = "// ==UserScript==\n// @name        新脚本\n// @namespace   local\n// @version     1.0\n" +
            "// @match       https://example.com/*\n// @grant       none\n// ==/UserScript==\n\n"

        /**
         * The scripts that name [page]'s address, switched on or off right there; on a private page the
         * switch says whether the script runs in private tabs. Changes apply once the page loads again.
         * [manage] opens the full list, where the window has one.
         */
        fun forPage(host: PageHost, page: BrowserTab, manage: (() -> Unit)?) {
            val ui = host.ui
            val sheet = BrowserSheet(ui, host.root, ui.str(R.string.userscripts_page_title), { page.isPrivate || host.isPrivateMode })
            val list = ui.column()
            val scripts = UserScripts.forPage(page.url)
            // Commands from scripts that run in the page's frames although they do not name the page itself come last.
            val commands = UserScriptApi.commands(page.view).groupBy { it.script }
            fun addCommands(id: String) = commands[id].orEmpty().forEach { command ->
                list.addView(ui.menuRow(R.drawable.ic_arrow_forward_24, command.name, iconColor = ui.palette.muted) {
                    sheet.dismiss(); UserScriptApi.run(command)
                }.apply { view.setPadding(ui.dp(40), view.paddingTop, view.paddingRight, view.paddingBottom) }.view)
            }
            when {
                !UserScripts.available -> list.addView(ui.emptyState(ui.str(R.string.userscript_unavailable)))
                !host.prefs.userScripts -> list.addView(ui.emptyState(ui.str(R.string.userscripts_all_off)))
                scripts.isEmpty() -> list.addView(ui.emptyState(ui.str(R.string.userscripts_page_empty)))
                else -> scripts.forEach { script ->
                    val inPrivate = page.isPrivate
                    val row = ui.switchRow(R.drawable.ic_code_24, script.meta.name, script.enabled && (!inPrivate || script.allowPrivate)) { on ->
                        if (inPrivate) { if (on) UserScripts.setEnabled(script.id, true); UserScripts.setAllowPrivate(script.id, on) }
                        else UserScripts.setEnabled(script.id, on)
                        sheet.showSnackbar(ui.str(R.string.userscripts_reload_hint))
                    }
                    if (inPrivate) row.setSubtitle(ui.str(R.string.userscripts_private_switch))
                    list.addView(row.view)
                    addCommands(script.id)
                }
            }
            if (UserScripts.available && host.prefs.userScripts) (commands.keys - scripts.map { it.id }.toSet()).forEach { id ->
                UserScripts.find(id)?.let { list.addView(ui.sectionTitle(it.meta.name)) }
                addCommands(id)
            }
            sheet.add(ScrollView(host).apply { addView(list) }, ui.stretch())
            val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            manage?.let { buttons.addView(ui.textAction(ui.str(R.string.userscripts_manage)) { sheet.dismiss(); it() }, ui.weighted()) }
            buttons.addView(ui.textAction(ui.str(R.string.page_refresh), true) { sheet.dismiss(); if (page in host.tabs) host.retryPage(page) },
                ui.weighted().apply { if (manage != null) leftMargin = ui.dp(8) })
            sheet.add(buttons)
            sheet.show()
        }
    }
}
