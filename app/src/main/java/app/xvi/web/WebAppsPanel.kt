package app.xvi.web

import android.widget.ScrollView
import androidx.core.content.pm.ShortcutManagerCompat

/** The web apps on the home screen, to open or remove. */
internal class WebAppsPanel(private val host: BrowserActivity, private val back: (() -> Unit)? = null) {
    private val ui = host.ui

    fun show() {
        val sheet = host.sheet(R.string.webapps_title).apply { back?.let(::backTo) }
        val apps = WebAppActivity.installed(host)
        if (apps.isEmpty()) sheet.add(ui.emptyState(ui.str(R.string.webapps_empty)))
        else {
            val list = ui.column()
            apps.forEach { app ->
                val title = app.shortLabel.toString()
                val url = app.intent.dataString.orEmpty()
                list.addView(ui.menuRow(R.drawable.ic_apps_24, title, BrowserInput.display(url)) {
                    host.actionsDialog(title, listOf(
                        ui.str(R.string.webapp_open) to { sheet.leave(); runCatching { host.startActivity(app.intent) } },
                        ui.str(R.string.webapp_remove) to {
                            sheet.leave()
                            // Launchers keep pinned icons; a disabled one only says the app is gone.
                            ShortcutManagerCompat.disableShortcuts(host, listOf(app.id), ui.str(R.string.webapp_disabled))
                            host.showSnackbar(ui.str(R.string.webapp_removed))
                        },
                    ))
                }.view)
            }
            sheet.add(ScrollView(host).apply { addView(list) }, ui.stretch())
        }
        sheet.show()
    }
}
