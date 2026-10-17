package app.xvi.web

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
import androidx.core.view.isNotEmpty

/** Requests a page makes of the browser chrome: long-press menus, other apps, pop-ups, sign-in and script dialogs. */
internal class PageInteractions(private val host: PageHost) {
    enum class ScriptDialog { ALERT, CONFIRM, PROMPT, LEAVE }

    private val ui get() = host.ui
    /** Origins the user always allows to open pop-ups; loaded once, kept in step with the store. */

    /**
     * Long press on a link or image. For an image inside a link, WebView reports only the image
     * address, so the link target is asked for separately before the menu opens.
     */
    fun longPress(target: BrowserTab, web: WebView): Boolean {
        val result = web.hitTestResult
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> if (result.extra?.let(BrowserInput::isWebUrl) != true) return false
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> Unit
            else -> return false
        }
        val point = web as? BrowserWebView ?: return false
        // WebView offers the gesture to Android before sending contextmenu to the page. Claim it
        // here, but let the page cancel the menu before opening any browser UI.
        if (!web.settings.javaScriptEnabled) return showLongPressMenu(target, web, result)
        val document = target.documentVersion
        val script = contextMenuScript.replace("__X__", point.lastDownX.toString()).replace("__Y__", point.lastDownY.toString())
        web.evaluateJavascript(script) { allowed ->
            if (allowed != "false" && target in host.tabs && target.view === web && host.tab === target && target.documentVersion == document) {
                showLongPressMenu(target, web, result)
            }
        }
        return true
    }

    private val contextMenuScript by lazy { host.assets.open("page-context-menu.js").bufferedReader().use { it.readText() } }
    val touchTargetScript by lazy { host.assets.open("page-touch-target.js").bufferedReader().use { it.readText() } }

    private fun showLongPressMenu(target: BrowserTab, web: WebView, result: WebView.HitTestResult): Boolean {
        val raw = result.extra
        val extra = raw?.takeIf(BrowserInput::isWebUrl)
        // Images the page made itself (data: / blob:) can only be saved; their address means nothing elsewhere.
        val image = extra ?: raw?.takeIf(::isPageImage)
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> menu(target, web, extra ?: return false, null)
            // WebView leaves out data: image addresses; read the pressed image's address from the page.
            WebView.HitTestResult.IMAGE_TYPE -> if (image != null) menu(target, web, null, image) else {
                val point = web as? BrowserWebView ?: return false
                val script = IMAGE_AT.replace("__X__", point.lastDownX.toString()).replace("__Y__", point.lastDownY.toString())
                runCatching {
                    web.evaluateJavascript(script) { raw ->
                        val src = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()?.takeIf(::isPageImage)
                        if (src != null && target in host.tabs && target.view === web) menu(target, web, null, src)
                    }
                }.onFailure { return false }
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val reply = Handler(Looper.getMainLooper()) { message ->
                    val link = message.data.getString("url")?.takeIf(BrowserInput::isWebUrl)
                    if (target in host.tabs && target.view === web && (link != null || image != null)) menu(target, web, link, image)
                    true
                }
                web.requestFocusNodeHref(reply.obtainMessage())
            }
            else -> return false
        }
        host.haptic(web)
        return true
    }

    private fun isPageImage(value: String) =
        value.startsWith("data:image/", ignoreCase = true) || value.startsWith("blob:", ignoreCase = true)

    private fun menu(target: BrowserTab, web: WebView, link: String?, image: String?) {
        val address = link ?: image ?: return
        if (link == null && isPageImage(address)) {
            host.actionsDialog(host.str(R.string.image_page_made), listOf(host.str(R.string.image_save) to { host.downloads.saveImage(target, address) }),
                target, address = true)
            return
        }
        val webImage = image?.takeUnless(::isPageImage)
        fun openPrivate(url: String) = host.str(R.string.link_open_private) to { host.openPrivate(url) }
        val offerPrivate = host.canOpenPrivate && !target.isPrivate
        val actions = buildList<Pair<String, () -> Unit>> {
            if (link != null) {
                add(host.str(host.backgroundLinkLabel) to { host.openInBackground(target, link) })
                if (offerPrivate) add(openPrivate(link))
            }
            if (webImage != null) {
                add(host.str(R.string.image_open) to { host.openInBackground(target, webImage) })
                if (link == null && offerPrivate) add(openPrivate(webImage))
            }
            add(host.str(if (link != null) R.string.link_copy else R.string.image_copy) to { host.copy(address, target.isPrivate) })
            add(host.str(if (link != null) R.string.link_share else R.string.image_share) to { host.share(address) })
            if (image != null) add(host.str(R.string.image_save) to { host.downloads.saveImage(target, image) })
            if (link != null) add(host.str(R.string.link_download) to { host.downloads.download(target, link, web.settings.userAgentString, null, null) })
        }
        host.actionsDialog(address, actions, target, address = true)
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
        fun fallBack() { if (fallback != null && source in host.tabs) host.load(source, fallback) else host.toast(R.string.external_no_app) }
        if (target == null) { fallBack(); return }
        val pm = host.packageManager
        val app = runCatching { pm.resolveActivity(target, 0)?.takeIf { it.activityInfo.packageName != host.packageName }?.loadLabel(pm)?.toString() }.getOrNull()
        // A page names the application it wants opened, so the request can fail in more ways than a
        // missing handler: the package may be absent, disabled or refuse the intent. None of that may
        // reach the user as a crash, so anything but a successful start falls back the same way.
        fun openOrFallBack() { if (runCatching { host.startActivity(target) }.isFailure) fallBack() }
        val dialog = host.browserDialog(source).setTitle(R.string.external_title)
            .setMessage(if (app != null) host.str(R.string.external_message_app, app) else host.str(R.string.external_message_scheme, destination.scheme.orEmpty()))
            .setPositiveButton(R.string.external_open) { _, _ -> openOrFallBack() }
            .setNegativeButton(R.string.action_cancel, null)
        if (fallback != null) dialog.setNeutralButton(R.string.external_stay) { _, _ -> if (source in host.tabs) host.load(source, fallback) }
        dialog.show()
    }

    /** Pop-ups opened without a tap are blocked unless this session or a remembered choice allows the site. */
    fun popupAllowed(source: BrowserTab, web: WebView, userGesture: Boolean): Boolean {
        if (userGesture) return true
        val page = web.url ?: source.url
        val site = BrowserStore.siteKey(page) ?: return false
        if (site in source.session.popupSites) return true
        if (!source.isPrivate && host.siteChoices.allowsPopups(BrowserStore.origin(page))) return true
        if (host.tab === source) host.showSnackbar(host.str(R.string.popup_blocked), host.str(R.string.popup_allow)) {
            source.session.popupSites.add(site)
            host.toast(host.str(R.string.popup_allowed, site))
        }
        return false
    }

    /** Runs after a document becomes visible or finishes: re-applies per-page adjustments. */
    fun pageReady(web: WebView) {
        // Also covers older WebViews without document-start scripts. The observer is idempotent.
        runCatching { web.evaluateJavascript(touchTargetScript, null) }
        if (host.prefs.forceZoom) runCatching { web.evaluateJavascript(FORCE_ZOOM, null) }
    }

    /** Sign-in requested by the server (HTTP authentication). Nothing is saved. */
    fun httpAuth(target: BrowserTab, handler: HttpAuthHandler, hostName: String, realm: String?) {
        if (host.tab !== target) { handler.cancel(); return }
        val user = ui.edit(ui.str(R.string.auth_user)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val password = ui.edit(ui.str(R.string.auth_password)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        host.applyInputPrivacy(user, target.isPrivate)
        host.applyInputPrivacy(password, target.isPrivate)
        val form = ui.dialogForm(user, password)
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
        val content = ui.dialogForm(*listOfNotNull(field, block.takeIf { target.dialogCount > 2 }).toTypedArray())
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
        /** Address of the image under a point given in view pixels, allowing for zoom and scrolling. */
        const val IMAGE_AT = "(() => { try { const v = window.visualViewport; const s = window.devicePixelRatio * (v ? v.scale : 1);" +
            " const x = __X__ / s + (v ? v.offsetLeft : 0), y = __Y__ / s + (v ? v.offsetTop : 0);" +
            " const e = document.elementFromPoint(x, y); const img = e && e.closest ? e.closest('img') : null;" +
            " return img ? (img.currentSrc || img.src) : null; } catch (_) { return null; } })()"
        /** Removes the page's zoom limits from its viewport settings. */
        const val FORCE_ZOOM = "(() => { try { for (const m of document.querySelectorAll('meta[name=viewport]')) {" +
            " const kept = (m.getAttribute('content') || '').split(',').map(s => s.trim())" +
            ".filter(s => s && !/^(user-scalable|maximum-scale|minimum-scale)\\s*=/i.test(s));" +
            " kept.push('user-scalable=yes', 'maximum-scale=10'); m.setAttribute('content', kept.join(', ')); } } catch (_) {} })()"
    }
}
