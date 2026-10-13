package app.xvi.web

import android.webkit.GeolocationPermissions
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every site's remembered permissions in one list; tapping one forgets it, so the site asks again. */
internal class SitePermissionsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.site_permissions)
    private val list = ui.column()

    fun show() {
        sheet.add(ScrollView(host).apply { addView(list) }, ui.stretch())
        render()
        sheet.show()
    }

    private fun render() {
        host.lifecycleScope.launch {
            val entries = withContext(host.store.io) { host.store.allPermissions() }
            list.removeAllViews()
            if (entries.isEmpty()) { list.addView(ui.emptyState(ui.str(R.string.site_permissions_all_none)), ui.wide()); return@launch }
            entries.groupBy { it.origin }.forEach { (origin, granted) ->
                list.addView(ui.sectionTitle(host.domain(origin)))
                granted.forEach { entry ->
                    val state = ui.str(if (entry.allowed) R.string.site_permission_allowed else R.string.site_permission_denied)
                    list.addView(ui.menuRow(R.drawable.ic_close_24, name(entry.kind), ui.str(R.string.site_permission_reset, state),
                        if (entry.allowed) palette.success else palette.muted) { forget(listOf(entry)) }.view)
                }
            }
            list.addView(ui.textAction(ui.str(R.string.site_permissions_clear_all)) {
                host.confirm(ui.str(R.string.site_permissions_clear_all_confirm), ui.str(R.string.action_clear), destructive = true) {
                    forget(entries)
                    // Location answers WebView kept on its own go too.
                    GeolocationPermissions.getInstance().clearAll()
                }
            }, ui.wide().apply { topMargin = ui.dp(12) })
        }
    }

    /** Pop-up choices go through [SiteChoices], which every window reads them from. */
    private fun forget(entries: List<SitePermission>) {
        host.lifecycleScope.launch {
            entries.filter { it.kind == SitePermissions.POPUP }.forEach { host.siteChoices.setPopups(it.origin, false) }
            // The same single writer runs the pop-up changes first, so the list read next never shows them.
            host.store.write { entries.filter { it.kind != SitePermissions.POPUP }.forEach { clearPermission(it.origin, it.kind) } }
            render()
        }
    }

    private fun name(kind: String) = ui.str(when (kind) {
        SitePermissions.CAMERA -> R.string.permission_camera
        SitePermissions.MICROPHONE -> R.string.permission_microphone
        SitePermissions.POPUP -> R.string.permission_popup
        else -> R.string.permission_location
    })
}
