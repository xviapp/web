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
        if (BrowserStore.siteKey(url) != null) siteSettings(body)
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

    /** Desktop mode, text size, scripts and images for this site; each change applies at once. */
    private fun siteSettings(body: LinearLayout) {
        body.addView(ui.sectionTitle(ui.str(if (target.isPrivate) R.string.site_settings_private else R.string.site_settings)))
        fun current() = host.siteSettings.get(target.session, target.url)
        body.addView(ui.switchRow(R.drawable.ic_desktop_24, ui.str(R.string.menu_desktop), current().desktop == true) {
            host.updateSiteSetting(target, current().copy(desktop = it.takeIf { on -> on }))
        }.view)
        body.addView(ui.switchRow(R.drawable.ic_code_24, ui.str(R.string.site_javascript), current().javascript != false) {
            host.updateSiteSetting(target, current().copy(javascript = if (it) null else false))
        }.view)
        body.addView(ui.switchRow(R.drawable.ic_image_24, ui.str(R.string.site_images), current().images != false) {
            host.updateSiteSetting(target, current().copy(images = if (it) null else false))
        }.view)
        lateinit var size: MenuRow
        fun sizeLabel() = current().textZoom?.let { ui.str(R.string.site_text_size_value, it) } ?: ui.str(R.string.site_text_size_default)
        size = ui.menuRow(R.drawable.ic_text_size_24, ui.str(R.string.settings_text_size), sizeLabel()) {
            val choices = listOf<Int?>(null) + SITE_TEXT_ZOOMS
            val labels = choices.map { it?.let { value -> ui.str(R.string.site_text_size_value, value) } ?: ui.str(R.string.site_text_size_default) }
            host.browserDialog(target).setTitle(R.string.settings_text_size)
                .setSingleChoiceItems(labels.toTypedArray(), choices.indexOf(current().textZoom).coerceAtLeast(0)) { dialog, index ->
                    host.updateSiteSetting(target, current().copy(textZoom = choices[index]))
                    size.setSubtitle(sizeLabel())
                    dialog.dismiss()
                }.show()
        }
        body.addView(size.view)
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

    private companion object { val SITE_TEXT_ZOOMS = listOf(75, 85, 100, 115, 130, 150, 175) }
}
