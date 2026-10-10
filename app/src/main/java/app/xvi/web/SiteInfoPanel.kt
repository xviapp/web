package app.xvi.web

import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/** Connection details and remembered permissions for the current page, opened from the icon before the address. */
internal class SiteInfoPanel(private val host: BrowserActivity, private val target: BrowserTab) {
    private val ui = host.ui
    private val palette = ui.palette

    fun show() {
        val url = target.url
        val secure = host.isSecure(target)
        val overridden = url.startsWith("https://", ignoreCase = true) && !secure
        val sheet = BrowserSheet(ui, host.root, BrowserInput.display(url), { target.isPrivate || host.isPrivateMode })
        val body = ui.column()
        body.addView(ui.menuRow(if (secure) R.drawable.ic_lock_24 else R.drawable.ic_info_24,
            ui.str(if (secure) R.string.site_secure else R.string.site_insecure),
            if (overridden) ui.str(R.string.site_certificate_ignored) else null,
            if (secure) palette.success else palette.warning).view)
        target.view?.certificate?.takeIf { secure }?.let { cert ->
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            body.addView(ui.sectionTitle(ui.str(R.string.site_certificate)))
            cert.issuedTo?.cName?.takeIf { it.isNotBlank() }?.let { body.addView(detail(R.string.site_issued_to, it)) }
            (cert.issuedBy?.oName?.takeIf { it.isNotBlank() } ?: cert.issuedBy?.cName)?.let { body.addView(detail(R.string.site_issued_by, it)) }
            cert.validNotAfterDate?.let { body.addView(detail(R.string.site_valid_until, format.format(it))) }
        }
        val origin = BrowserStore.origin(url)
        if (!target.isPrivate && origin != null) {
            val permissions = ui.column()
            body.addView(ui.sectionTitle(ui.str(R.string.site_permissions)))
            body.addView(permissions)
            body.addView(ui.switchRow(R.drawable.ic_tabs_24, ui.str(R.string.site_popups), origin in host.pages.popupOrigins) { allowed ->
                if (allowed) host.pages.popupOrigins.add(origin) else host.pages.popupOrigins.remove(origin)
                host.store.write { if (allowed) setPermission(origin, SitePermissions.POPUP, true) else clearPermission(origin, SitePermissions.POPUP) }
            }.view)
            renderPermissions(permissions, origin)
        }
        body.addView(ui.sectionTitle(ui.str(R.string.site_address)))
        body.addView(ui.label(url, 13f, palette.ink).apply {
            setTextIsSelectable(true)
            maxLines = 4
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8))
        })
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        buttons.addView(ui.textAction(ui.str(R.string.error_copy_url)) { host.copy(url, target.isPrivate) }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
        sheet.add(buttons)
        sheet.show()
    }

    /** Remembered camera, microphone and location answers; tapping one forgets it. */
    private fun renderPermissions(container: LinearLayout, origin: String) {
        host.lifecycleScope.launch {
            val entries = withContext(host.store.io) { host.store.permissions(origin) }.filter { it.kind != SitePermissions.POPUP }
            container.removeAllViews()
            if (entries.isEmpty()) {
                container.addView(ui.label(ui.str(R.string.site_permissions_none), 13f, palette.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)) })
                return@launch
            }
            entries.forEach { entry ->
                val name = ui.str(when (entry.kind) {
                    SitePermissions.CAMERA -> R.string.permission_camera
                    SitePermissions.MICROPHONE -> R.string.permission_microphone
                    else -> R.string.permission_location
                })
                val state = ui.str(if (entry.allowed) R.string.site_permission_allowed else R.string.site_permission_denied)
                container.addView(ui.menuRow(R.drawable.ic_close_24, name, ui.str(R.string.site_permission_reset, state),
                    if (entry.allowed) palette.success else palette.muted) {
                    host.store.write { clearPermission(origin, entry.kind) }
                    renderPermissions(container, origin)
                }.view)
            }
        }
    }

    private fun detail(label: Int, value: String) = ui.column().apply {
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6))
        addView(ui.label(ui.str(label), 12f, palette.muted))
        addView(ui.label(value, 14f, palette.ink))
    }
}
