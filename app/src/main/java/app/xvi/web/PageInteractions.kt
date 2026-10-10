package app.xvi.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/** Requests a page makes of the browser chrome: long-press menus, other apps and pop-ups. */
internal class PageInteractions(private val host: BrowserActivity) {
    private val ui get() = host.ui

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

    /** Pop-ups opened without a tap are blocked unless this session allowed the site. */
    fun popupAllowed(source: BrowserTab, web: WebView, userGesture: Boolean): Boolean {
        if (userGesture) return true
        val site = BrowserStore.siteKey(web.url ?: source.url) ?: return false
        if (site in source.session.popupSites) return true
        if (host.tab === source) host.showSnackbar(host.str(R.string.popup_blocked), host.str(R.string.popup_allow)) {
            source.session.popupSites.add(site)
            host.toast(host.str(R.string.popup_allowed, site))
        }
        return false
    }
}
