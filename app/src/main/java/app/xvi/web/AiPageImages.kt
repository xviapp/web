package app.xvi.web

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The pictures of the document [target] shows. Pixels the page already loaded are read in the page;
 * the rest are downloaded the way the page would, through its session and the browser's proxy.
 * Every answer is null once the tab shows another document.
 */
internal class AiPageImages(private val host: BrowserActivity, private val target: BrowserTab) {
    private val pageUrl = target.url
    private val document = target.documentVersion

    fun current() = target in host.tabs && target.url == pageUrl && target.documentVersion == document

    /** Candidates in the order they are offered, taken again each time so lazily loaded pictures appear. */
    suspend fun list(): List<AiImages.PageImage>? {
        val raw = run("list", "") ?: return null
        return AiImages.fromPage(runCatching { JSONArray(raw) }.getOrNull()).takeIf { current() }
    }

    suspend fun preview(image: AiImages.PageImage): Bitmap? {
        run("preview", image.key)?.let { data -> withContext(Dispatchers.Default) { AiImages.decodePreview(data) } }?.let { return it }
        val bytes = download(image) ?: return null
        return withContext(Dispatchers.Default) { AiImages.decodePreview(bytes) }
    }

    /** The picture resized for sending; null when it cannot be read. */
    suspend fun attach(image: AiImages.PageImage, name: String): DeepSeek.Image? {
        val source = image.url.ifEmpty { null }
        run("data", image.key)?.takeIf { it.isNotEmpty() }?.let { data ->
            withContext(Dispatchers.Default) { AiImages.read(data, name, source) }?.let { return it }
        }
        val bytes = download(image) ?: return null
        return withContext(Dispatchers.Default) { AiImages.read(bytes, name, source) }
    }

    private suspend fun run(mode: String, key: String): String? {
        if (!current()) return null
        val web = target.view ?: return null
        val script = script ?: withContext(Dispatchers.IO) {
            host.assets.open("ai/page-images.js").bufferedReader().use { it.readText() }
        }.also { script = it }
        val result = web.readString("($script)(${JSONObject.quote(mode)}, ${JSONObject.quote(key)})")
        return result.takeIf { current() }
    }

    private suspend fun download(image: AiImages.PageImage): ByteArray? {
        val url = image.url.toHttpUrlOrNull() ?: return null
        if (!current()) return null
        val source = pageUrl.toHttpUrlOrNull()
        // Only the page's own site gets its cookies, as a browser blocking third-party cookies would.
        val sameSite = source != null && url.topPrivateDomain() != null && url.topPrivateDomain() == source.topPrivateDomain()
        val cookie = if (!sameSite) null else runCatching { target.session.cookies().getCookie(image.url) }.getOrNull()
        val request = Request.Builder().url(url).apply {
            target.view?.settings?.userAgentString?.let { header("User-Agent", it) }
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            if (source != null && !(source.isHttps && !url.isHttps)) {
                val referer = if (source.scheme == url.scheme && source.host == url.host && source.port == url.port)
                    source.newBuilder().fragment(null).build() else source.newBuilder().encodedPath("/").query(null).fragment(null).build()
                header("Referer", referer.toString())
            }
        }.build()
        suspendCancellableCoroutine<Unit> { continuation ->
            host.network.prepare { if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> } }
        }
        if (!current()) return null
        val client = BrowserHttp.through(host.network.proxy()).newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                // An image may redirect to another host; the original host's cookies stay with it.
                val outgoing = chain.request().let {
                    if (it.url.host == url.host) it else it.newBuilder().removeHeader("Cookie").build()
                }
                chain.proceed(outgoing)
            }.build()
        return try {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val bytes = response.use {
                            if (!it.isSuccessful) return@use null
                            runCatching {
                                val data = it.body.source()
                                data.request(MAX_DOWNLOAD + 1)
                                if (data.buffer.size > MAX_DOWNLOAD) null else data.buffer.readByteArray()
                            }.getOrNull()
                        }
                        if (continuation.isActive) continuation.resume(bytes) { _, _, _ -> }
                    }
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { null }
    }

    private companion object {
        const val MAX_DOWNLOAD = 8L * 1024 * 1024
        /** Read once per process; the script is a bundled asset. */
        var script: String? = null
    }
}
