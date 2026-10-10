package app.xvi.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.view.isNotEmpty

/** Requests a page makes of the browser chrome: long-press menus, other apps, pop-ups, sign-in and script dialogs. */
internal class PageInteractions(private val host: BrowserActivity) {
    enum class ScriptDialog { ALERT, CONFIRM, PROMPT, LEAVE }

    private val ui get() = host.ui
    /** Origins the user always allows to open pop-ups; loaded once, kept in step with the store. */
    val popupOrigins = mutableSetOf<String>()

    /**
     * Long press on a link or image. For an image inside a link, WebView reports only the image
     * address, so the link target is asked for separately before the menu opens.
     */
    fun longPress(target: BrowserTab, web: WebView): Boolean {
        val result = web.hitTestResult
        val extra = result.extra?.takeIf(BrowserInput::isWebUrl)
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> menu(target, web, extra ?: return false, null)
            WebView.HitTestResult.IMAGE_TYPE -> menu(target, web, null, extra ?: return false)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val reply = Handler(Looper.getMainLooper()) { message ->
                    val link = message.data.getString("url")?.takeIf(BrowserInput::isWebUrl)
                    if (target in host.tabs && target.view === web && (link != null || extra != null)) menu(target, web, link, extra)
                    true
                }
                web.requestFocusNodeHref(reply.obtainMessage())
            }
            else -> return false
        }
        host.haptic(web)
        return true
    }

    private fun menu(target: BrowserTab, web: WebView, link: String?, image: String?) {
        val address = link ?: image ?: return
        val actions = buildList<Pair<String, () -> Unit>> {
            if (link != null) {
                add(host.str(R.string.link_open_new_tab) to { if (target in host.tabs) host.addTab(link, session = target.session) })
                if (!target.isPrivate) add(host.str(R.string.link_open_private) to { host.obtainPrivateSession()?.let { host.addTab(link, session = it) } })
            }
            if (image != null) {
                add(host.str(R.string.image_open) to { if (target in host.tabs) host.addTab(image, session = target.session) })
                if (link == null && !target.isPrivate) add(host.str(R.string.link_open_private) to { host.obtainPrivateSession()?.let { host.addTab(image, session = it) } })
            }
            add(host.str(if (link != null) R.string.link_copy else R.string.image_copy) to { host.copy(address, target.isPrivate) })
            add(host.str(if (link != null) R.string.link_share else R.string.image_share) to { host.share(address) })
            if (image != null) add(host.str(R.string.image_save) to { host.downloads.saveImage(target, image) })
            if (link != null) add(host.str(R.string.link_download) to { host.downloads.download(target, link, web.settings.userAgentString, null, null) })
        }
        val title = ui.label(address, 13f, ui.palette.muted).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8))
        }
        host.browserDialog(target).setCustomTitle(title).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }.show()
    }

    fun externalLink(uri: Uri, source: BrowserTab) {
        val blocked = setOf("file", "content", "javascript", "data", "about")
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme in blocked) return
        val parsed = runCatching { if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri) }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")?.takeIf(BrowserInput::isWebUrl)
        val destination = parsed.data?.takeIf { it.scheme?.lowercase() !in blocked }
        // Only the data and package survive; components and extras from the page are dropped.
        val target = destination?.let { Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(parsed.`package`) }
        if (target == null) {
            if (fallback != null && source in host.tabs) host.load(source, fallback) else host.toast(R.string.external_no_app)
            return
        }
        val pm = host.packageManager
        val app = runCatching { pm.resolveActivity(target, 0)?.takeIf { it.activityInfo.packageName != host.packageName }?.loadLabel(pm)?.toString() }.getOrNull()
        val dialog = host.browserDialog(source).setTitle(R.string.external_title)
            .setMessage(if (app != null) host.str(R.string.external_message_app, app) else host.str(R.string.external_message_scheme, destination.scheme.orEmpty()))
            .setPositiveButton(R.string.external_open) { _, _ ->
                try { host.startActivity(target) }
                catch (_: ActivityNotFoundException) {
                    if (fallback != null && source in host.tabs) host.load(source, fallback) else host.toast(R.string.external_no_app)
                }
            }.setNegativeButton(R.string.action_cancel, null)
        if (fallback != null) dialog.setNeutralButton(R.string.external_stay) { _, _ -> if (source in host.tabs) host.load(source, fallback) }
        dialog.show()
    }

    /** Pop-ups opened without a tap are blocked unless this session or a remembered choice allows the site. */
    fun popupAllowed(source: BrowserTab, web: WebView, userGesture: Boolean): Boolean {
        if (userGesture) return true
        val page = web.url ?: source.url
        val site = BrowserStore.siteKey(page) ?: return false
        if (site in source.session.popupSites) return true
        if (!source.isPrivate && BrowserStore.origin(page) in popupOrigins) return true
        if (host.tab === source) host.showSnackbar(host.str(R.string.popup_blocked), host.str(R.string.popup_allow)) {
            source.session.popupSites.add(site)
            host.toast(host.str(R.string.popup_allowed, site))
        }
        return false
    }

    /** Runs after a document becomes visible or finishes: re-applies per-page adjustments. */
    fun pageReady(web: WebView) {
        if (host.prefs.forceZoom) runCatching { web.evaluateJavascript(FORCE_ZOOM, null) }
    }

    /** Sign-in requested by the server (HTTP authentication). Nothing is saved. */
    fun httpAuth(target: BrowserTab, handler: HttpAuthHandler, hostName: String, realm: String?) {
        if (host.tab !== target) { handler.cancel(); return }
        val user = ui.edit(ui.str(R.string.auth_user)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val password = ui.edit(ui.str(R.string.auth_password)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        host.applyInputPrivacy(user, target.isPrivate)
        host.applyInputPrivacy(password, target.isPrivate)
        val form = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
            addView(user, LinearLayout.LayoutParams(-1, -2))
            addView(password, LinearLayout.LayoutParams(-1, -2))
        }
        val message = if (realm.isNullOrBlank()) ui.str(R.string.auth_message, hostName) else ui.str(R.string.auth_message_realm, hostName, realm)
        host.browserDialog(target).setTitle(R.string.auth_title).setMessage(message).setView(form)
            .setPositiveButton(R.string.auth_sign_in) { _, _ -> handler.proceed(user.text.toString(), password.text.toString()) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
        user.requestFocus()
    }

    /**
     * alert / confirm / prompt / beforeunload. Only the selected tab may show one; from the third dialog
     * on a page, the user can block the rest for that page.
     */
    fun scriptDialog(target: BrowserTab, web: WebView, url: String, message: String, defaultValue: String?, kind: ScriptDialog, result: JsResult): Boolean {
        if (target.view !== web || host.tab !== target || target.dialogsBlocked == target.documentVersion) { result.cancel(); return true }
        if (target.dialogDocument != target.documentVersion) { target.dialogDocument = target.documentVersion; target.dialogCount = 0 }
        target.dialogCount++
        val field = defaultValue?.let { value -> ui.edit("").apply { setText(value); selectAll(); host.applyInputPrivacy(this, target.isPrivate) } }
        val block = CheckBox(host).apply { text = ui.str(R.string.script_dialog_block); setTextColor(ui.palette.ink) }
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0) }
        field?.let { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        if (target.dialogCount > 2) content.addView(block, LinearLayout.LayoutParams(-1, -2))
        var answered = false
        fun answer(confirmed: Boolean) {
            if (answered) return
            answered = true
            if (block.isChecked) target.dialogsBlocked = target.documentVersion
            when {
                !confirmed -> result.cancel()
                result is JsPromptResult -> result.confirm(field?.text?.toString().orEmpty())
                else -> result.confirm()
            }
        }
        val builder = host.browserDialog(target)
        if (kind == ScriptDialog.LEAVE) {
            builder.setTitle(R.string.script_leave_title).setMessage(R.string.script_leave_message)
                .setPositiveButton(R.string.script_leave) { _, _ -> answer(true) }
                .setNegativeButton(R.string.script_stay) { _, _ -> answer(false) }
        } else {
            builder.setTitle(ui.str(R.string.script_dialog_title, BrowserInput.display(url))).setMessage(message)
                .setPositiveButton(R.string.action_confirm) { _, _ -> answer(true) }
            if (kind != ScriptDialog.ALERT) builder.setNegativeButton(R.string.action_cancel) { _, _ -> answer(false) }
        }
        if (content.isNotEmpty()) builder.setView(content)
        builder.setOnCancelListener { answer(false) }.setOnDismissListener { answer(false) }.show()
        return true
    }

    private companion object {
        /** Removes the page's zoom limits from its viewport settings. */
        const val FORCE_ZOOM = "(() => { try { for (const m of document.querySelectorAll('meta[name=viewport]')) {" +
            " const kept = (m.getAttribute('content') || '').split(',').map(s => s.trim())" +
            ".filter(s => s && !/^(user-scalable|maximum-scale|minimum-scale)\\s*=/i.test(s));" +
            " kept.push('user-scalable=yes', 'maximum-scale=10'); m.setAttribute('content', kept.join(', ')); } } catch (_) {} })()"
    }
}
