package app.xvi.web

import java.net.URI
import java.util.Locale

/**
 * Which addresses a web app window keeps, and the colour it takes from the page. A web app is one
 * site: the host it was installed from, with or without `www.`, on the same scheme and port. Plain
 * Kotlin so the rules are covered by JVM tests.
 */
internal object WebAppScope {
    /** `scheme://host[:port]` with `www.` dropped, the unit a web app covers; null for non-web addresses. */
    fun of(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    fun contains(start: String, url: String): Boolean {
        val scope = of(start) ?: return false
        return of(url) == scope
    }

    /**
     * Whether a main-frame navigation of the window to [url] goes to the browser instead. Server
     * redirects stay, since sign-in and similar flows bounce through other sites and come back.
     */
    fun leaves(start: String, url: String, redirect: Boolean): Boolean = !redirect && !contains(start, url)

    /**
     * Opaque colour from a page's `theme-color`: `#rgb`, `#rrggbb`, `#rrggbbaa` or `rgb()` / `rgba()`.
     * Transparency is dropped, since the bars cannot show the page through them. Null for anything else.
     */
    fun themeColor(value: String?): Int? {
        val text = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            if (hex.any { Character.digit(it, 16) < 0 }) return null
            val rgb = when (hex.length) {
                3, 4 -> hex.take(3).map { "$it$it" }.joinToString("")
                6, 8 -> hex.take(6)
                else -> return null
            }
            return (0xFF000000 or rgb.toLong(16)).toInt()
        }
        val match = Regex("""^rgba?\(\s*([0-9.]+)\s*[, ]\s*([0-9.]+)\s*[, ]\s*([0-9.]+)\s*(?:[,/]\s*[0-9.]+%?\s*)?\)$""").matchEntire(text) ?: return null
        val (r, g, b) = match.destructured.toList().map { it.toFloatOrNull()?.takeIf { v -> v in 0f..255f }?.toInt() ?: return null }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Whether dark icons read better than light ones on [color]. */
    fun isLight(color: Int): Boolean {
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        return r * 299 + g * 587 + b * 114 > 150_000
    }
}
