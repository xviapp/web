package app.xvi.web

import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.UUID

/** One-use handoff: the browser keeps its page until the new window actually takes it. */
internal object WebAppTransfer {
    private const val EXTRA_TRANSFER = "app.xvi.web.WEB_APP_TRANSFER"
    private class Pending(source: BrowserActivity, target: BrowserTab) {
        val source = WeakReference(source)
        val target = WeakReference(target)
    }
    private val pending = mutableMapOf<String, Pending>()
    private val main = Handler(Looper.getMainLooper())

    fun open(source: BrowserActivity, target: BrowserTab) {
        if (source.isDestroyed || target !in source.tabs || target.isPrivate || !BrowserInput.isWebUrl(target.url)) return
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(source, target)
        // If Android declines to launch the window, the original page stays where it was.
        main.postDelayed({ pending.remove(token) }, 30_000L)
        val intent = WebAppActivity.launchIntent(source, target.url, target.title.ifBlank { BrowserInput.display(target.url) })
            .putExtra(EXTRA_TRANSFER, token)
            // Moving a live page must not replace another window's unsaved work at the same address.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { source.startActivity(intent) }.onFailure {
            pending.remove(token)
            source.toast(R.string.webapp_open_failed)
        }
    }

    fun take(intent: Intent, destination: BrowserSession): BrowserTab? {
        val token = intent.getStringExtra(EXTRA_TRANSFER) ?: return null
        intent.removeExtra(EXTRA_TRANSFER)
        val transfer = pending.remove(token) ?: return null
        val target = transfer.target.get()?.takeIf { it.url == intent.dataString } ?: return null
        return transfer.source.get()?.takeForWindow(target, destination)
    }
}
