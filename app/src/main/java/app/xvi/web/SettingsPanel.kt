package app.xvi.web

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
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

    fun show() {
        val body = ui.column()
        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_browse)))
        lateinit var engine: MenuRow
        engine = ui.menuRow(R.drawable.ic_search_24, ui.str(R.string.settings_engine), prefs.engine) {
            val names = BrowserInput.engines.keys.toTypedArray()
            host.browserDialog().setTitle(R.string.settings_engine).setSingleChoiceItems(names, names.indexOf(prefs.engine)) { dialog, index ->
                prefs.engine = names[index]
                engine.setSubtitle(names[index])
                host.updateChrome()
                dialog.dismiss()
            }.show()
        }
        body.addView(engine.view)
        body.addView(ui.switchRow(R.drawable.ic_search_24, ui.str(R.string.settings_search_suggestions), prefs.searchSuggestions) {
            prefs.searchSuggestions = it
        }.view)
        lateinit var textSize: MenuRow
        textSize = ui.menuRow(R.drawable.ic_text_size_24, ui.str(R.string.settings_text_size), textZoomLabel(prefs.textZoom)) {
            host.browserDialog().setTitle(R.string.settings_text_size)
                .setSingleChoiceItems(TEXT_ZOOMS.map(::textZoomLabel).toTypedArray(), TEXT_ZOOMS.indexOf(prefs.textZoom)) { dialog, index ->
                    val value = TEXT_ZOOMS[index]
                    prefs.textZoom = value
                    host.tabs.forEach { it.view?.settings?.textZoom = value }
                    textSize.setSubtitle(textZoomLabel(value))
                    dialog.dismiss()
                }.show()
        }
        body.addView(textSize.view)
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
        body.addView(ui.switchRow(R.drawable.ic_pin_24, ui.str(R.string.settings_keep_background), prefs.keepBackground) {
            prefs.keepBackground = it
            host.syncKeepAlive()
        }.view)

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
        body.addView(ui.switchRow(R.drawable.ic_palette_24, ui.str(R.string.settings_dark_pages), prefs.darkPages) {
            prefs.darkPages = it
            host.applyPageDarkening()
        }.view)
        lateinit var haptics: MenuRow
        haptics = ui.switchRow(R.drawable.ic_vibration_24, ui.str(R.string.settings_haptics), prefs.haptics) {
            prefs.haptics = it
            if (it) host.haptic(haptics.view)
        }
        body.addView(haptics.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_data)))
        body.addView(ui.menuRow(R.drawable.ic_clean_24, ui.str(R.string.settings_clear)) { clearData() }.view)

        body.addView(ui.sectionTitle(ui.str(R.string.settings_section_about)))
        body.addView(ui.menuRow(R.drawable.ic_info_24, ui.str(R.string.settings_about, ui.str(R.string.app_name)), ui.str(R.string.settings_version, versionName())) {
            host.browserDialog().setTitle("${ui.str(R.string.app_name)} ${versionName()}")
                .setMessage("${ui.str(R.string.home_slogan_zh)}\n${ui.str(R.string.home_slogan_en)}")
                .setNegativeButton(R.string.action_close, null).show()
        }.view)
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.show()
    }

    private fun clearData() {
        val privateData = host.isPrivateMode
        val options = arrayOf(ui.str(R.string.clear_history), ui.str(R.string.clear_cache), ui.str(if (privateData) R.string.clear_site_data_private else R.string.clear_site_data))
        host.browserDialog().setTitle(R.string.settings_clear).setItems(options) { _, index ->
            val message = when {
                index == 0 -> ui.str(R.string.clear_history_confirm)
                index == 1 -> ui.str(R.string.clear_cache_confirm)
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
                    else -> (host.tab?.session ?: host.normalSession).clearWebsiteData { host.toast(R.string.cleared) }
                }
            }
        }.show()
    }

    private fun versionName() = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull().orEmpty()
    private fun textZoomLabel(value: Int) = ui.str(when (value) {
        85 -> R.string.text_size_small
        115 -> R.string.text_size_large
        130 -> R.string.text_size_largest
        else -> R.string.text_size_standard
    }, value)
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
