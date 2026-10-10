package app.xvi.web

import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat

/** Connection details for the current page, opened from the icon before the address. */
internal class SiteInfoPanel(private val host: BrowserActivity, private val target: BrowserTab) {
    private val ui = host.ui
    private val palette = ui.palette

    fun show() {
        val url = target.url
        val secure = url.startsWith("https://", ignoreCase = true)
        val sheet = BrowserSheet(ui, host.root, BrowserInput.display(url), { target.isPrivate || host.isPrivateMode })
        val body = ui.column()
        body.addView(ui.menuRow(if (secure) R.drawable.ic_lock_24 else R.drawable.ic_info_24,
            ui.str(if (secure) R.string.site_secure else R.string.site_insecure), null,
            if (secure) palette.success else palette.warning).view)
        target.view?.certificate?.takeIf { secure }?.let { cert ->
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            body.addView(ui.sectionTitle(ui.str(R.string.site_certificate)))
            cert.issuedTo?.cName?.takeIf { it.isNotBlank() }?.let { body.addView(detail(R.string.site_issued_to, it)) }
            (cert.issuedBy?.oName?.takeIf { it.isNotBlank() } ?: cert.issuedBy?.cName)?.let { body.addView(detail(R.string.site_issued_by, it)) }
            cert.validNotAfterDate?.let { body.addView(detail(R.string.site_valid_until, format.format(it))) }
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

    private fun detail(label: Int, value: String) = ui.column().apply {
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6))
        addView(ui.label(ui.str(label), 12f, palette.muted))
        addView(ui.label(value, 14f, palette.ink))
    }
}
