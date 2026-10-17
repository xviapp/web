package app.xvi.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.util.Locale

/**
 * The sharpest icon a site offers for the home screen. WebView only reports the small tab icon, which
 * looks blurred at launcher size, so the page's web app manifest, its touch icons and its sized icon
 * links are asked instead, in that order of preference.
 */
internal object SiteIcons {
    /** [maskable] icons are drawn for cropping by the launcher and fill the whole adaptive icon. */
    data class Candidate(val src: String, val size: Int, val maskable: Boolean)

    /** Smallest icon worth using; anything smaller is no sharper than the tab icon. */
    const val MIN_SIZE = 96

    /** Icon links the page declares, and its manifest's address; JSON from the page or null. */
    const val PAGE_ICONS = "(() => { try { const out = { manifest: '', icons: [] };" +
        " const m = document.querySelector('link[rel~=\"manifest\"]'); if (m && m.href) out.manifest = m.href;" +
        " for (const l of document.querySelectorAll('link[rel~=\"icon\"], link[rel~=\"apple-touch-icon\"], link[rel~=\"apple-touch-icon-precomposed\"]')) {" +
        " if (l.href) out.icons.push({ src: l.href, sizes: l.getAttribute('sizes') || '', type: l.type || '', rel: l.rel || '' }); }" +
        " return JSON.stringify(out); } catch (_) { return null; } })()"

    /** Largest side named in a `sizes` value such as `"192x192 512x512"`; `any` counts as large. */
    fun largestSize(sizes: String): Int = sizes.lowercase(Locale.ROOT).split(Regex("\\s+")).maxOfOrNull { part ->
        if (part == "any") 512 else part.substringBefore('x').toIntOrNull() ?: 0
    } ?: 0

    /** Icons in a web app manifest, with addresses resolved against [manifestUrl]. */
    fun fromManifest(json: String, manifestUrl: String): List<Candidate> {
        val icons = runCatching { JSONObject(json).optJSONArray("icons") }.getOrNull() ?: return emptyList()
        return icons.objects().mapNotNull { icon ->
            val src = resolve(manifestUrl, icon.optString("src")) ?: return@mapNotNull null
            if (isVector(src, icon.optString("type"))) return@mapNotNull null
            val purpose = icon.optString("purpose").lowercase(Locale.ROOT).split(Regex("\\s+"))
            Candidate(src, largestSize(icon.optString("sizes")), maskable = "maskable" in purpose)
        }
    }

    /** Icons the page links to; touch icons without a size are 180 pixels by convention. */
    fun fromPage(json: String, pageUrl: String): Pair<String?, List<Candidate>> {
        val page = runCatching { JSONObject(json) }.getOrNull() ?: return null to emptyList()
        val manifest = page.optString("manifest").takeIf { it.isNotBlank() }?.let { resolve(pageUrl, it) }
        val icons = page.optJSONArray("icons")?.objects().orEmpty().mapNotNull { link ->
            val src = resolve(pageUrl, link.optString("src")) ?: return@mapNotNull null
            if (isVector(src, link.optString("type"))) return@mapNotNull null
            val touch = link.optString("rel").contains("apple-touch-icon", ignoreCase = true)
            val size = largestSize(link.optString("sizes")).takeIf { it > 0 } ?: if (touch) 180 else 0
            Candidate(src, size, maskable = false)
        }
        return manifest to icons
    }

    /**
     * Order to try: maskable icons big enough for a launcher, then the rest from largest down. The
     * conventional `/apple-touch-icon.png` comes last, for sites that serve one without linking it.
     */
    fun rank(candidates: List<Candidate>, pageUrl: String): List<Candidate> {
        val fallback = resolve(pageUrl, "/apple-touch-icon.png")?.let { Candidate(it, 0, false) }
        return (candidates.filter { it.size == 0 || it.size >= MIN_SIZE }
            .sortedWith(compareByDescending<Candidate> { it.maskable && it.size >= 192 }.thenByDescending { it.size }) + listOfNotNull(fallback))
            .distinctBy { it.src }
    }

    /**
     * Asks [web]'s page for its icons and loads the first usable one, with whether it is maskable; null
     * when there is none. Call on the main thread: [cookie] is read there, for the manifest only, since
     * some sites serve it to signed-in visitors alone.
     */
    suspend fun best(web: WebView, pageUrl: String, cookie: (String) -> String?, proxy: Proxy, userAgent: String): Pair<Bitmap, Boolean>? =
        withTimeoutOrNull(TIMEOUT_MS) {
            val (manifest, linked) = web.readString(PAGE_ICONS)?.let { fromPage(it, pageUrl) } ?: (null to emptyList())
            val manifestCookie = manifest?.let(cookie)
            withContext(Dispatchers.IO) {
                val client = BrowserHttp.through(proxy)
                fun fetch(url: String, limit: Long): ByteArray? = runCatching {
                    val request = Request.Builder().url(url).header("User-Agent", userAgent)
                        .apply { if (url == manifest && !manifestCookie.isNullOrBlank()) header("Cookie", manifestCookie) }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val source = response.body.source()
                        source.request(limit + 1)
                        if (source.buffer.size > limit) null else source.buffer.readByteArray()
                    }
                }.getOrNull()
                val fromManifest = manifest?.let { url -> fetch(url, MAX_MANIFEST)?.let { fromManifest(String(it), url) } }.orEmpty()
                for (candidate in rank(fromManifest + linked, pageUrl)) {
                    val bytes = fetch(candidate.src, MAX_ICON) ?: continue
                    val bitmap = decodeSampled(LAUNCHER_SIZE) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } ?: continue
                    if (minOf(bitmap.width, bitmap.height) >= MIN_SIZE) return@withContext bitmap to candidate.maskable
                }
                null
            }
        }

    private fun resolve(base: String, href: String): String? = runCatching {
        if (href.isBlank()) return null
        URI(base).resolve(href.trim()).toString().takeIf(BrowserInput::isWebUrl)
    }.getOrNull()

    private fun isVector(src: String, type: String) =
        type.contains("svg", ignoreCase = true) || src.substringBefore('?').endsWith(".svg", ignoreCase = true)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Adaptive icons are 108 dp; four times that is sharp on every launcher density. */
    const val LAUNCHER_SIZE = 432
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_MANIFEST = 256L * 1024
    private const val MAX_ICON = 2L * 1024 * 1024
}
