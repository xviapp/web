package app.xvi.web

import android.os.Handler
import android.os.Looper

/**
 * Runs work that has to wait for the proxy to settle, without waiting on it forever.
 *
 * The proxy core reports through a callback, but nothing outside this app guarantees that call always
 * arrives — "not configured" and "core failed to start" are reported the same way, and a page load or
 * a started download may not be held up by them. Callers therefore get one answer, at most
 * [TIMEOUT_MS] later, on the main thread.
 *
 * The timeout does not invent a proxy state: it only lets the caller proceed. Whatever the proxy
 * indicator shows is still whatever the core last reported.
 */
internal object ProxyReady {
    /** Long enough for a proxy that is already coming up, short enough that nothing looks stuck. */
    private const val TIMEOUT_MS = 1500L

    fun run(network: BrowserProxyNetwork, proceed: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var answered = false
        fun answer() {
            if (answered) return
            answered = true
            proceed()
        }
        val timeout = Runnable { answer() }
        main.postDelayed(timeout, TIMEOUT_MS)
        network.prepare {
            main.removeCallbacks(timeout)
            answer()
        }
    }
}
