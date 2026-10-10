package app.xvi.web

import java.io.File
import java.net.URLDecoder

/** `data:[type][;base64],payload` addresses, decoded without loading anything else. */
internal object DataUrl {
    /** Media type named by [url], or `text/plain` as the standard says when none is given. */
    fun type(url: String): String {
        val header = url.substringAfter(':').substringBefore(',')
        return header.split(';').firstOrNull()?.trim()?.lowercase()?.takeIf { it.contains('/') } ?: "text/plain"
    }

    fun decode(url: String): ByteArray {
        require(url.startsWith("data:", ignoreCase = true) && url.contains(',')) { "not a data address" }
        val header = url.substring(5, url.indexOf(','))
        val payload = url.substring(url.indexOf(',') + 1)
        return if (header.split(';').any { it.trim().equals("base64", ignoreCase = true) })
            java.util.Base64.getMimeDecoder().decode(URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8"))
        else URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
    }

    /** Writes the payload to [file]; returns its media type. Fails for payloads over [limit] bytes. */
    fun write(url: String, file: File, limit: Long): String {
        require(url.length <= limit * 4 / 3 + 1024) { "too large" }
        file.writeBytes(decode(url))
        return type(url)
    }
}
