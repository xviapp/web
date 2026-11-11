package app.xvi.web

import java.io.File
import java.io.InputStream

/** `data:[type][;base64],payload` addresses, decoded without loading anything else. */
internal object DataUrl {
    /** Media type named by [url], or `text/plain` as the standard says when none is given. */
    fun type(url: String): String {
        val header = url.substringAfter(':').substringBefore(',')
        return header.split(';').firstOrNull()?.trim()?.lowercase()?.takeIf { it.contains('/') } ?: "text/plain"
    }

    /**
     * The payload's bytes as a stream, decoded while read, so a large file is never held twice. `%XX`
     * stands for one byte, and a `%` that starts no escape (`width="100%"` in inline SVG) is itself.
     */
    fun open(url: String): InputStream {
        require(url.startsWith("data:", ignoreCase = true) && url.contains(',')) { "not a data address" }
        val comma = url.indexOf(',')
        val header = url.substring(5, comma)
        val bytes = PercentDecoding(url, comma + 1)
        return if (header.split(';').any { it.trim().equals("base64", ignoreCase = true) }) java.util.Base64.getMimeDecoder().wrap(bytes) else bytes
    }

    fun decode(url: String): ByteArray = open(url).use { it.readBytes() }

    /** Writes the payload to [file]; returns its media type. Fails for payloads over [limit] bytes. */
    fun write(url: String, file: File, limit: Long): String {
        require(url.length <= limit * 4 / 3 + 1024) { "too large" }
        open(url).use { input -> file.outputStream().use { input.copyTo(it) } }
        return type(url)
    }

    /** Text from [start] as bytes: escapes decoded, other characters in UTF-8. */
    private class PercentDecoding(private val text: String, start: Int) : InputStream() {
        private var at = start
        private val pending = ByteArray(4)
        private var pendingAt = 0
        private var pendingSize = 0

        override fun read(): Int {
            if (pendingAt < pendingSize) return pending[pendingAt++].toInt() and 0xff
            if (at >= text.length) return -1
            val c = text[at]
            if (c == '%' && at + 2 < text.length && hex(text[at + 1]) >= 0 && hex(text[at + 2]) >= 0) {
                val value = hex(text[at + 1]) * 16 + hex(text[at + 2])
                at += 3
                return value
            }
            if (c.code < 0x80) { at++; return c.code }
            // A character outside ASCII, or a surrogate pair, as UTF-8.
            val end = if (Character.isHighSurrogate(c) && at + 1 < text.length) at + 2 else at + 1
            val encoded = text.substring(at, end).toByteArray(Charsets.UTF_8)
            at = end
            System.arraycopy(encoded, 0, pending, 0, encoded.size)
            pendingSize = encoded.size
            pendingAt = 1
            return encoded[0].toInt() and 0xff
        }

        private fun hex(c: Char) = Character.digit(c, 16)
    }
}
