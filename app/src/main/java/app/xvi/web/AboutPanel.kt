package app.xvi.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.webkit.WebViewCompat

/** Product information and offline documents, using the same navigation as the other settings. */
internal class AboutPanel(private val host: BrowserActivity, private val back: (() -> Unit)? = null) {
    private val ui = host.ui

    fun show(scrollY: Int = 0) {
        val sheet = host.sheet(R.string.about_title).apply { back?.let(::backTo) }
        val body = ui.column()
        val scroll = ScrollView(host).apply { addView(body) }
        fun page(open: (() -> Unit) -> Unit) {
            val position = scroll.scrollY
            sheet.leave()
            open { show(position) }
        }
        body.addView(ui.medium(ui.label(ui.str(R.string.app_name), 26f)).apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(4))
        })
        body.addView(paragraph(ui.str(R.string.settings_version, versionName())))
        body.addView(paragraph(ui.str(R.string.about_description)))
        body.addView(paragraph(ui.str(R.string.about_maintainer)))
        body.addView(ui.menuRow(R.drawable.ic_article_24, ui.str(R.string.about_privacy)) { page(::privacy) }.view)
        body.addView(ui.menuRow(R.drawable.ic_code_24, ui.str(R.string.about_notices)) { page(::notices) }.view)
        body.addView(ui.menuRow(R.drawable.ic_info_24, ui.str(R.string.about_feedback)) { page(::feedback) }.view)
        body.addView(ui.menuRow(R.drawable.ic_download_24, ui.str(R.string.about_releases), ui.str(R.string.about_releases_note)) {
            sheet.leave(); host.addTab(RELEASES)
        }.view)
        body.addView(ui.menuRow(R.drawable.ic_open_in_new_24, ui.str(R.string.about_project), "github.com/xviapp/web") {
            sheet.leave(); host.addTab(PROJECT)
        }.view)
        sheet.add(scroll, ui.stretch())
        sheet.show()
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun privacy(back: () -> Unit) {
        val body = ui.column()
        body.addView(paragraph(ui.str(R.string.privacy_intro)))
        listOf(
            R.string.privacy_local_title to R.string.privacy_local,
            R.string.privacy_network_title to R.string.privacy_network,
            R.string.privacy_ai_title to R.string.privacy_ai,
            R.string.privacy_private_title to R.string.privacy_private,
            R.string.privacy_permissions_title to R.string.privacy_permissions,
            R.string.privacy_control_title to R.string.privacy_control,
            R.string.privacy_contact_title to R.string.privacy_contact
        ).forEach { (title, text) ->
            body.addView(ui.sectionTitle(ui.str(title)))
            body.addView(paragraph(ui.str(text)))
        }
        document(R.string.about_privacy, body, back)
    }

    private fun notices(back: () -> Unit) {
        // Packaged from the repository's canonical notice, so the in-app copy cannot drift.
        val text = host.assets.open("about/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
            .replace(Regex("(?m)^# +"), "")
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1（$2）")
            .replace("`", "")
        document(R.string.about_notices, ui.column().apply { addView(paragraph(text)) }, back)
    }

    private fun feedback(back: () -> Unit) {
        val sheet = host.sheet(R.string.about_feedback).apply { backTo(back) }
        val diagnostics = ui.str(R.string.feedback_diagnostics, ui.str(R.string.app_name), versionName(),
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
            WebViewCompat.getCurrentWebViewPackage(host)?.versionName ?: ui.str(R.string.feedback_webview_unknown))
        val body = ui.column()
        body.addView(paragraph(ui.str(R.string.feedback_note)))
        body.addView(ui.menuRow(R.drawable.ic_open_in_new_24, ui.str(R.string.feedback_open), "GitHub Issues") {
            sheet.leave(); host.addTab(ISSUES)
        }.view)
        body.addView(ui.sectionTitle(ui.str(R.string.feedback_diagnostics_title)))
        body.addView(paragraph(diagnostics))
        body.addView(paragraph(ui.str(R.string.feedback_diagnostics_note)))
        body.addView(ui.menuRow(R.drawable.ic_copy_24, ui.str(R.string.feedback_copy)) {
            (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(ui.str(R.string.feedback_diagnostics_title), diagnostics))
            if (Build.VERSION.SDK_INT < 33) host.toast(R.string.feedback_copied)
        }.view)
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.show()
    }

    private fun document(title: Int, body: LinearLayout, back: () -> Unit) {
        host.sheet(title).apply {
            backTo(back)
            add(ScrollView(host).apply { addView(body) }, ui.stretch())
            show()
        }
    }

    private fun paragraph(text: String) = ui.label(text, 14f, ui.palette.muted).apply {
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(10))
        setLineSpacing(ui.dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
    }

    private fun versionName() = host.packageManager.getPackageInfo(host.packageName, 0).versionName.orEmpty()

    companion object {
        const val PROJECT = "https://github.com/xviapp/web"
        const val RELEASES = "$PROJECT/releases"
        const val ISSUES = "$PROJECT/issues"
    }
}
