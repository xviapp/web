package app.xvi.web

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File

/** Bounded downloads and file replacement shared by ad and parental-control lists. */
internal object FilterListDownload {
    private const val MAX_BYTES = 15L * 1024 * 1024

    /** Null on failure or rejected content; [received] reports progress at most every 150 ms. */
    fun download(client: OkHttpClient, url: String, accept: (String) -> Boolean,
                 received: (Long, Long) -> Unit = { _, _ -> }): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val total = response.body.contentLength()
            if (total > MAX_BYTES) return@use null
            val source = response.body.source()
            val buffer = Buffer()
            var reported = 0L
            while (source.read(buffer, 64 * 1024) != -1L) {
                if (buffer.size > MAX_BYTES) return@use null
                val now = System.currentTimeMillis()
                if (now - reported >= 150) { reported = now; received(buffer.size, total) }
            }
            received(buffer.size, total)
            buffer.readUtf8()
        }
    }.getOrNull()?.takeIf(accept)

    /** A failed write keeps the previous list and removes the partial download. */
    fun save(file: File, text: String): Boolean {
        val partial = File(file.path + ".part")
        return try {
            file.parentFile?.mkdirs()
            partial.writeText(text)
            partial.renameTo(file)
        } catch (_: java.io.IOException) {
            false
        } finally {
            partial.delete()
        }
    }
}
