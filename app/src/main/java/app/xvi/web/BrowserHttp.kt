package app.xvi.web

import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One connection pool and one dispatcher for every request the browser makes on a page's behalf:
 * view source, media covers and search suggestions.
 *
 * Building an [OkHttpClient] per request would create a thread pool, a connection pool and a route
 * database each time, which is far more expensive than the request itself. Derived clients share the
 * base pool and threads, and only the proxy routing differs.
 */
internal object BrowserHttp {
    private val base = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Base client for requests that follow the device's own network settings. */
    fun base(): OkHttpClient = base

    /** Client that reaches local addresses directly and sends everything else through [proxy]. */
    fun through(proxy: Proxy): OkHttpClient = base.newBuilder().proxySelector(LocalDirect(proxy)).build()
}
