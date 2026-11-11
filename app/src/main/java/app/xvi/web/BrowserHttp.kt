package app.xvi.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
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

    /**
     * Client for what the browser fetches for [pageUrl] (its icons, media cover, pictures or source), with
     * the rules the page's own requests would have: a `Cookie` header goes only to the origin it was read
     * for and is dropped on a redirect anywhere else, and a page on the internet cannot reach this device
     * or the local network through it, directly or by redirect.
     */
    fun forPage(proxy: Proxy, pageUrl: String?): OkHttpClient =
        through(proxy).newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor(PageRules(pageUrl)).build()

    internal fun sameOrigin(a: HttpUrl, b: HttpUrl?) = b != null && a.scheme == b.scheme && a.host == b.host && a.port == b.port

    class LocalRefused : IOException("local address")

    /** Follows redirects itself, so each address is checked before anything connects to it. */
    private class PageRules(pageUrl: String?) : Interceptor {
        private val pageIsLocal = LocalAddress.isLocal(pageUrl?.toHttpUrlOrNull()?.host)

        override fun intercept(chain: Interceptor.Chain): Response {
            val first = chain.request()
            val cookieOrigin = first.url
            var request = first
            repeat(MAX_REDIRECTS + 1) {
                if (!pageIsLocal && LocalAddress.isLocal(request.url.host)) throw LocalRefused()
                val response = chain.proceed(request)
                val next = response.header("Location")?.takeIf { response.isRedirect }?.let(request.url::resolve) ?: return response
                response.close()
                request = request.newBuilder().url(next).apply {
                    if (!sameOrigin(next, cookieOrigin)) removeHeader("Cookie")
                    if (!sameOrigin(next, request.url)) removeHeader("Authorization")
                }.build()
            }
            throw IOException("too many redirects")
        }
    }

    private const val MAX_REDIRECTS = 10
}
