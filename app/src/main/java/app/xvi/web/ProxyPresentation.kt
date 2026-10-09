package app.xvi.web

import android.content.Context
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState

/** Localized copy belongs to the browser; core state contains only error codes. */
internal object ProxyPresentation {
    fun message(context: Context, state: ProxyState): String = when {
        state.errorCode == "WEBVIEW_UNSUPPORTED" -> context.getString(R.string.proxy_webview_unsupported)
        state.errorCode == "NO_PROFILE" -> context.getString(R.string.proxy_node_needed)
        state.phase == ProxyPhase.RECONNECTING -> context.getString(R.string.proxy_reconnecting,
            ((state.retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000)
        state.phase == ProxyPhase.FAILED && !state.hasProfile -> context.getString(R.string.proxy_node_needed)
        state.phase == ProxyPhase.FAILED -> context.getString(R.string.proxy_start_failed)
        else -> ""
    }

    fun subscriptionError(context: Context, code: String): String = context.getString(when (code) {
        "INVALID_URL" -> R.string.proxy_subscription_invalid
        "NO_SUBSCRIPTION" -> R.string.proxy_subscription_url_needed
        "NO_NODES", "NO_PROFILE" -> R.string.proxy_node_needed
        "NO_USABLE_NODES" -> R.string.proxy_subscription_no_usable_nodes
        "BUSY" -> R.string.proxy_subscription_updating
        "DOWNLOAD_FAILED" -> R.string.proxy_subscription_download_failed
        "TIMEOUT" -> R.string.proxy_subscription_timeout
        "CANCELLED" -> R.string.proxy_subscription_cancelled
        "STORAGE_FAILED" -> R.string.proxy_subscription_storage_failed
        else -> R.string.proxy_subscription_failed
    })
}
