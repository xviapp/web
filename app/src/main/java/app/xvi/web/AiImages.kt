package app.xvi.web

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pictures are held only in the tab's conversation. Album files and page pictures alike are sent as
 * resized JPEG without metadata, so the AI service never fetches anything from the page's websites.
 */
internal object AiImages {
    const val MAX_PER_MESSAGE = 4
    const val MAX_PER_CHAT = 12
    /** Every picture goes again with each follow-up question, so each stays small. */
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_SIDE = 1280
    private const val PREVIEW_SIDE = 320
    private val rasterData = Regex("^data:image/(?:png|jpeg|webp|gif);base64,[A-Za-z0-9+/=\\r\\n]+$")

    /** A picture on the page, named by the key the page script gave it; [url] is empty for inline pictures. */
    data class PageImage(val key: String, val url: String, val name: String)

    fun fromPage(raw: JSONArray?): List<PageImage> = buildList {
        if (raw == null) return@buildList
        for (i in 0 until minOf(raw.length(), 30)) {
            val item = raw.optJSONObject(i) ?: continue
            val key = item.optString("key").takeIf { it.isNotEmpty() && it.length <= 4096 } ?: continue
            val url = item.optString("url")
            if (url.isNotEmpty() && (!BrowserInput.isWebUrl(url) || url != key)) continue
            if (url.isEmpty() && !key.startsWith("inline:")) continue
            if (none { it.key == key }) add(PageImage(key, url, item.optString("name").take(120)))
        }
    }

    fun isInline(data: String) = data.startsWith("data:image/") && rasterData.matches(data)

    fun decodePreview(data: String): Bitmap? = runCatching {
        if (data.length > 4_000_000 || !isInline(data)) return null
        val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
        decodeSampled(PREVIEW_SIDE) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    }.getOrNull()

    fun decodePreview(bytes: ByteArray): Bitmap? = runCatching {
        decodeSampled(PREVIEW_SIDE) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    }.getOrNull()

    fun encodePreview(bitmap: Bitmap): String {
        val ratio = minOf(1f, PREVIEW_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val thumbnail = bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
        return try { dataUrl(jpeg(thumbnail, 75)) } finally { if (thumbnail !== bitmap) thumbnail.recycle() }
    }

    /** An album file; null when it cannot be read as a picture. */
    fun read(resolver: ContentResolver, uri: Uri, name: String): DeepSeek.Image? {
        if (uri.scheme != "content") return null
        return encode(name, null) { resolver.openInputStream(uri) }
    }

    /** A picture the page loaded, or its inline data; [source] is its address, kept for the transcript. */
    fun read(bytes: ByteArray, name: String, source: String?): DeepSeek.Image? = encode(name, source) { bytes.inputStream() }

    fun read(data: String, name: String, source: String?): DeepSeek.Image? {
        if (!isInline(data)) return null
        val bytes = runCatching { Base64.decode(data.substringAfter(','), Base64.DEFAULT) }.getOrNull() ?: return null
        return read(bytes, name, source)
    }

    private fun encode(name: String, source: String?, open: () -> InputStream?): DeepSeek.Image? {
        // Sampling halves the size, so decode at up to twice the target and scale the rest exactly.
        val sampled = decodeSampled(MAX_SIDE * 2) { options -> open()?.use { BitmapFactory.decodeStream(it, null, options) } } ?: return null
        val orientation = runCatching { open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull()
        var picture = sampled
        try {
            picture = replace(picture, oriented(picture, orientation))
            // Transparent diagrams need a white background when converted to JPEG.
            if (picture.hasAlpha()) picture = replace(picture, Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).apply { drawColor(Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
            })
            var side = MAX_SIDE
            while (true) {
                picture = replace(picture, fit(picture, side))
                // Detailed pictures can stay large; lower the quality first, then the size.
                for (quality in intArrayOf(82, 70, 55)) {
                    val bytes = jpeg(picture, quality)
                    if (bytes.size <= MAX_BYTES) return DeepSeek.Image(dataUrl(bytes), name, encodePreview(picture), source)
                }
                if (side <= 320) return null
                side = side * 3 / 4
            }
        } finally { picture.recycle() }
    }

    /** [next], recycling [current] when it was replaced. */
    private fun replace(current: Bitmap, next: Bitmap): Bitmap { if (next !== current) current.recycle(); return next }

    private fun fit(bitmap: Bitmap, side: Int): Bitmap {
        val ratio = side.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (ratio >= 1f) bitmap else bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
    }

    private fun oriented(bitmap: Bitmap, orientation: Int?): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun jpeg(bitmap: Bitmap, quality: Int) =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun dataUrl(bytes: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
