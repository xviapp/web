package app.xvi.web

import java.net.URI

/**
 * HTTPS-First: plain `http://` addresses are tried over HTTPS first, and only fall back to HTTP after
 * the user agrees. Local addresses are left alone, since devices on the network rarely have a
 * certificate. Plain Kotlin, so it is covered by JVM tests.
 */
internal object HttpsFirst {
    /** The `https://` form of [url], or null when it is not plain HTTP or should not be upgraded. */
    fun upgrade(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        // Another port means another service; its HTTPS port cannot be guessed.
        if (uri.port != -1 && uri.port != 80) return null
        if (LocalAddress.isLocal(host)) return null
        // Only the scheme changes (and an explicit :80), so the rest of the address stays exactly as written.
        val authority = uri.rawAuthority ?: return null
        val rest = url.substring(url.indexOf(':') + 1)
        return "https:" + if (uri.port == 80) rest.replaceFirst(authority, authority.removeSuffix(":80")) else rest
    }

    /** The host [url] is reached on, for remembering that a site may use plain HTTP. */
    fun host(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /**
     * True when [url] goes back to plain HTTP on the site of [original], the address that was being
     * tried over HTTPS, as a site without HTTPS support does by redirecting; following it would only
     * upgrade it again.
     */
    fun isDowngrade(url: String, original: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        return site(uri.host) != null && site(uri.host) == site(host(original))
    }

    private fun site(host: String?) = host?.lowercase()?.removePrefix("www.")
}
