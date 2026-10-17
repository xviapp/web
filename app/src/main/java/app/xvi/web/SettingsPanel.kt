package app.xvi.web

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings that apply immediately; rows show their current value and update in place. */
internal class SettingsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val sheet = host.sheet(R.string.settings_title)
    private val scroll = ScrollView(host)

    /** [scrollY] puts the list back where it was when one of its pages closes. */
    fun show(scrollY: Int = 0) {
        val body = ui.column()
        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_browse)))
        lateinit var engine: MenuRow
        engine = ui.menuRow(R.drawable.ic_search_24, ui.str(R.string.settings_engine), prefs.engine) {
            val names = BrowserInput.engines.keys.toList() + BrowserInput.CUSTOM_ENGINE
            val labels = names.map { if (it == BrowserInput.CUSTOM_ENGINE) ui.str(R.string.settings_engine_custom) else it }.toTypedArray()
            host.browserDialog().setTitle(R.string.settings_engine).setSingleChoiceItems(labels, names.indexOf(prefs.engine)) { dialog, index ->
                dialog.dismiss()
                if (names[index] == BrowserInput.CUSTOM_ENGINE) customEngine { engine.setSubtitle(prefs.engine) }
                else {
                    prefs.engine = names[index]
                    engine.setSubtitle(names[index])
                    host.updateChrome()
                }
            }.show()
        }
        body.addView(engine.view)
        val proxy = ui.menuRow(R.drawable.ic_language_24, ui.str(R.string.proxy_title), host.proxyLabel()) {
            page { ProxyPanel(host, it).show() }
        }
        // Falling back to direct is the one state worth noticing from here.
        if (host.proxyRoute == BrowserProxyNetwork.Route.DIRECT) proxy.subtitle.setTextColor(ui.palette.caution)
        body.addView(proxy.view)
        lateinit var textSize: MenuRow
        textSize = ui.menuRow(R.drawable.ic_text_size_24, ui.str(R.string.settings_text_size), textZoomLabel(prefs.textZoom)) {
            host.browserDialog().setTitle(R.string.settings_text_size)
                .setSingleChoiceItems(TEXT_ZOOMS.map(::textZoomLabel).toTypedArray(), TEXT_ZOOMS.indexOf(prefs.textZoom)) { dialog, index ->
                    val value = TEXT_ZOOMS[index]
                    prefs.textZoom = value
                    host.preferencesChanged()
                    textSize.setSubtitle(textZoomLabel(value))
                    dialog.dismiss()
                }.show()
        }
        body.addView(textSize.view)
        body.addView(ui.switchRow(R.drawable.ic_zoom_24, ui.str(R.string.settings_force_zoom), prefs.forceZoom) {
            prefs.forceZoom = it
            if (it) host.preferencesChanged() else host.toast(R.string.settings_force_zoom_off)
        }.view)
        lateinit var defaultBrowser: MenuRow
        defaultBrowser = ui.menuRow(R.drawable.ic_language_24, ui.str(R.string.settings_default_browser), defaultBrowserStatus()) {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = host.getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    host.onBrowserRoleResult = { defaultBrowser.setSubtitle(defaultBrowserStatus()) }
                    host.browserRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                } else host.toast(R.string.settings_default_browser_held)
            } else host.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        body.addView(defaultBrowser.view)
        if (Build.VERSION.SDK_INT >= 29) body.addView(ui.switchRow(R.drawable.ic_download_24, ui.str(R.string.settings_ask_download_location), prefs.askDownloadLocation) {
            prefs.askDownloadLocation = it
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_apps_24, ui.str(R.string.webapps_title)) { page { WebAppsPanel(host, it).show() } }.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_adblock)))
        lateinit var lists: MenuRow
        fun listsSummary() = AdBlock.filter?.let { ui.str(R.string.adblock_lists_summary, it.size) } ?: ui.str(R.string.adblock_lists_loading)
        body.addView(ui.switchRow(R.drawable.ic_shield_24, ui.str(R.string.adblock_enabled), prefs.adblock) {
            prefs.adblock = it
            host.preferencesChanged()
        }.view)
        lists = ui.menuRow(R.drawable.ic_article_24, ui.str(R.string.adblock_lists), listsSummary()) { page { AdBlockPanel(host, it).show() } }
        body.addView(lists.view)
        lateinit var custom: MenuRow
        custom = ui.menuRow(R.drawable.ic_code_24, ui.str(R.string.adblock_custom), customSummary()) {
            // The rows show the result, so saving needs no message of its own.
            AdBlockPanel.editCustomRules(host) { lists.setSubtitle(listsSummary()); custom.setSubtitle(customSummary()) }
        }
        body.addView(custom.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_userscripts)))
        body.addView(ui.menuRow(R.drawable.ic_code_24, ui.str(R.string.userscripts_title), userScriptsStatus()) { page { UserScriptsPanel(host, it).show() } }.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_ai)))
        body.addView(ui.menuRow(R.drawable.ic_ai_24, ui.str(R.string.ai_title), aiStatus()) { page { AiSettingsPanel(host, it).show() } }.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_appearance)))
        lateinit var theme: MenuRow
        theme = ui.menuRow(R.drawable.ic_palette_24, ui.str(R.string.settings_theme), themeLabel(prefs.theme)) {
            val themes = BrowserPrefs.Theme.entries
            host.browserDialog().setTitle(R.string.settings_theme)
                .setSingleChoiceItems(themes.map(::themeLabel).toTypedArray(), themes.indexOf(prefs.theme)) { dialog, index ->
                    dialog.dismiss()
                    val chosen = themes[index]
                    if (chosen == prefs.theme) return@setSingleChoiceItems
                    if (host.privateSession == null) { sheet.dismiss(); host.applyTheme(chosen) }
                    else host.confirm(ui.str(R.string.settings_theme_private_confirm)) { sheet.dismiss(); host.applyTheme(chosen) }
                    theme.setSubtitle(themeLabel(prefs.theme))
                }.show()
        }
        body.addView(theme.view)
        body.addView(ui.switchRow(R.drawable.ic_dark_mode_24, ui.str(R.string.settings_pure_black), prefs.pureBlack) {
            restyle { prefs.pureBlack = it }
        }.apply { setSubtitle(ui.str(R.string.settings_pure_black_note)) }.view)
        if (Build.VERSION.SDK_INT >= 31) body.addView(ui.switchRow(R.drawable.ic_palette_24, ui.str(R.string.settings_dynamic_color), prefs.dynamicColor) {
            restyle { prefs.dynamicColor = it }
        }.apply { setSubtitle(ui.str(R.string.settings_dynamic_color_note)) }.view)
        body.addView(ui.switchRow(R.drawable.ic_dark_mode_24, ui.str(R.string.settings_dark_pages), prefs.darkPages) {
            prefs.darkPages = it
            host.preferencesChanged()
        }.view)
        lateinit var haptics: MenuRow
        haptics = ui.switchRow(R.drawable.ic_vibration_24, ui.str(R.string.settings_haptics), prefs.haptics) {
            prefs.haptics = it
            if (it) host.haptic(haptics.view)
        }
        body.addView(haptics.view)

        privacy(body)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_data)))
        body.addView(ui.menuRow(R.drawable.ic_download_24, ui.str(R.string.backup_export)) {
            sheet.dismiss(); host.backups.export()
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_folder_24, ui.str(R.string.backup_import)) {
            sheet.dismiss(); host.backups.import()
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_clean_24, ui.str(R.string.settings_clear)) { clearData() }.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_about)))
        body.addView(ui.menuRow(R.drawable.ic_info_24, ui.str(R.string.about_title), ui.str(R.string.settings_version, versionName())) {
            page { AboutPanel(host, it).show() }
        }.view)
        scroll.addView(body)
        sheet.add(scroll, ui.stretch())
        sheet.show()
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
    }

    /** Opens a page of settings; closing it with back comes back here at the same place. */
    private fun page(show: (back: () -> Unit) -> Unit) {
        val y = scroll.scrollY
        sheet.dismiss()
        show { SettingsPanel(host).show(y) }
    }

    private fun privacy(body: LinearLayout) {
        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_privacy)))
        body.addView(ui.menuRow(R.drawable.ic_ghost_24, ui.str(R.string.settings_private_lock), privateLockStatus()) {
            page { PrivateLockPanel(host, it).show() }
        }.view)
        body.addView(ui.switchRow(R.drawable.ic_lock_24, ui.str(R.string.settings_https_first), prefs.httpsFirst) { prefs.httpsFirst = it }.view)
        body.addView(ui.switchRow(R.drawable.ic_search_24, ui.str(R.string.settings_search_suggestions), prefs.searchSuggestions) {
            prefs.searchSuggestions = it
        }.view)
        lateinit var clearOnExit: MenuRow
        clearOnExit = ui.menuRow(R.drawable.ic_clean_24, ui.str(R.string.settings_clear_on_exit), clearOnExitSummary()) {
            val labels = ExitCleanup.ALL.map(::clearOnExitLabel).toTypedArray()
            val chosen = ExitCleanup.ALL.map { it in prefs.clearOnExit }.toBooleanArray()
            host.browserDialog().setTitle(R.string.settings_clear_on_exit)
                .setMultiChoiceItems(labels, chosen) { _, index, checked -> chosen[index] = checked }
                .setPositiveButton(R.string.action_save) { _, _ ->
                    prefs.clearOnExit = ExitCleanup.ALL.filterIndexedTo(LinkedHashSet()) { index, _ -> chosen[index] }
                    prefs.exitCleanupPending = prefs.clearOnExit.isNotEmpty()
                    clearOnExit.setSubtitle(clearOnExitSummary())
                }
                .setNegativeButton(R.string.action_cancel, null).show()
        }
        body.addView(clearOnExit.view)
        body.addView(ui.menuRow(R.drawable.ic_shield_24, ui.str(R.string.site_permissions)) { page { SitePermissionsPanel(host, it).show() } }.view)
    }

    private fun privateLockStatus() = ui.str(when {
        !prefs.lockPrivate -> R.string.settings_private_lock_off
        prefs.lockPrivateCloseOnFail -> R.string.settings_private_lock_on_wipe
        else -> R.string.settings_private_lock_on
    })
    private fun clearOnExitSummary() =
        prefs.clearOnExit.takeIf { it.isNotEmpty() }?.joinToString("、", transform = ::clearOnExitLabel) ?: ui.str(R.string.settings_clear_on_exit_off)
    private fun clearOnExitLabel(key: String) = ui.str(when (key) {
        ExitCleanup.TABS -> R.string.clear_on_exit_tabs
        ExitCleanup.HISTORY -> R.string.clear_on_exit_history
        ExitCleanup.SITE_DATA -> R.string.clear_on_exit_site_data
        else -> R.string.clear_on_exit_cache
    })

    /** Search address with `%s`; saving it also makes it the default engine. */
    private fun customEngine(done: () -> Unit) {
        val field = ui.edit(ui.str(R.string.settings_engine_custom_hint)).apply {
            setText(prefs.customEngine)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        host.browserDialog().setTitle(R.string.settings_engine_custom).setMessage(R.string.settings_engine_custom_message)
            .setView(ui.dialogForm(field)).setPositiveButton(R.string.action_save, null).setNegativeButton(R.string.action_cancel, null).create()
            .showChecked {
                val template = field.text.toString().trim()
                if (!BrowserInput.isValidTemplate(template)) { field.error = ui.str(R.string.settings_engine_custom_invalid); return@showChecked false }
                prefs.customEngine = template
                BrowserInput.customTemplate = template
                prefs.engine = BrowserInput.CUSTOM_ENGINE
                host.updateChrome()
                done()
                true
            }
    }

    private fun clearData() {
        val privateData = host.isPrivateMode
        val options = arrayOf(ui.str(R.string.clear_history), ui.str(R.string.clear_cache),
            ui.str(if (privateData) R.string.clear_site_data_private else R.string.clear_site_data), ui.str(R.string.clear_permissions))
        host.browserDialog().setTitle(R.string.settings_clear).setItems(options) { _, index ->
            val message = when {
                index == 0 -> ui.str(R.string.clear_history_confirm)
                index == 1 -> ui.str(R.string.clear_cache_confirm)
                index == 3 -> ui.str(R.string.clear_permissions_confirm)
                privateData -> ui.str(R.string.clear_site_data_private_confirm)
                else -> ui.str(R.string.clear_site_data_confirm)
            }
            host.confirm(message, ui.str(R.string.action_clear), destructive = true) {
                when (index) {
                    0 -> host.lifecycleScope.launch {
                        withContext(host.store.io) { host.store.clearHistory() }
                        host.toast(R.string.cleared); host.refreshHome()
                    }
                    1 -> {
                        val session = host.tab?.session ?: host.normalSession
                        val web = host.tab?.view ?: session.createWebView(host)
                        web.clearCache(true)
                        if (web !== host.tab?.view) session.release(web)
                        host.toast(R.string.cleared)
                    }
                    3 -> {
                        host.siteChoices.clear()
                        // Location answers WebView kept on its own are cleared too.
                        android.webkit.GeolocationPermissions.getInstance().clearAll()
                        host.toast(R.string.cleared)
                    }
                    else -> (host.tab?.session ?: host.normalSession).clearWebsiteData { host.toast(R.string.cleared) }
                }
            }
        }.show()
    }

    private fun customSummary(): String? = AdBlock.info[AdBlock.CUSTOM]?.rules?.takeIf { it > 0 }?.let { ui.str(R.string.adblock_custom_count, it) }
    private fun userScriptsStatus(): String {
        val scripts = UserScripts.scripts
        return when {
            !UserScripts.available -> ui.str(R.string.userscripts_status_unavailable)
            scripts.isEmpty() -> ui.str(R.string.userscripts_status_none)
            !prefs.userScripts -> ui.str(R.string.userscripts_status_off)
            else -> ui.str(R.string.userscripts_status_count, scripts.count { it.enabled }, scripts.size)
        }
    }
    private fun aiStatus() = ui.str(when {
        !host.aiConfig.enabled -> R.string.ai_status_off
        host.aiConfig.apiKey == null -> R.string.ai_status_unset
        else -> R.string.ai_status_ready
    })
    private fun versionName() = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull().orEmpty()
    private fun textZoomLabel(value: Int) = ui.str(when (value) {
        85 -> R.string.text_size_small
        115 -> R.string.text_size_large
        130 -> R.string.text_size_largest
        else -> R.string.text_size_standard
    }, value)
    /**
     * Applies a color change by rebuilding the window, as a theme change does. That would end a private
     * session, so then it asks first; declined, the change waits for the next start.
     */
    private fun restyle(change: () -> Unit) {
        change()
        if (host.privateSession == null) { sheet.dismiss(); host.applyTheme(prefs.theme) }
        else host.confirm(ui.str(R.string.settings_theme_private_confirm)) { sheet.dismiss(); host.applyTheme(prefs.theme) }
    }

    private fun themeLabel(theme: BrowserPrefs.Theme) = ui.str(when (theme) {
        BrowserPrefs.Theme.SYSTEM -> R.string.theme_system
        BrowserPrefs.Theme.LIGHT -> R.string.theme_light
        BrowserPrefs.Theme.DARK -> R.string.theme_dark
    })
    /** Only the held state is worth stating; otherwise the row title says enough. */
    private fun defaultBrowserStatus(): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roles = host.getSystemService(RoleManager::class.java)
        return if (roles.isRoleHeld(RoleManager.ROLE_BROWSER)) ui.str(R.string.settings_default_browser_held) else null
    }

    private companion object { val TEXT_ZOOMS = intArrayOf(85, 100, 115, 130).toList() }
}
