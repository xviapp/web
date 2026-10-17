package app.xvi.web

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The last gate before page content leaves the device, shared by every assistant action. */
internal class AiSendConsent(private val host: BrowserActivity) {
    suspend fun confirm(target: BrowserTab, base: String): Boolean {
        val config = host.aiConfig
        if (!target.isPrivate && config.sendConsent && config.baseUrl == base) return true
        return suspendCancellableCoroutine { continuation ->
            val message = host.str(R.string.ai_consent_message, base) +
                if (target.isPrivate) "\n\n" + host.str(R.string.ai_consent_private) else ""
            val dialog = host.browserDialog(target)
                .setTitle(R.string.ai_consent_title)
                .setMessage(message)
                .setPositiveButton(R.string.ai_consent_send) { _, _ ->
                    if (continuation.isActive) {
                        val accepted = config.enabled && config.baseUrl == base && target in host.tabs
                        if (accepted && !target.isPrivate) config.sendConsent = true
                        continuation.resume(accepted)
                    }
                }
                .setNegativeButton(R.string.action_cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .create()
            dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
            continuation.invokeOnCancellation { host.runOnUiThread { dialog.dismiss() } }
            dialog.show()
        }
    }
}
