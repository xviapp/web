package app.xvi.web

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Addresses that only make sense on this device or its local network (routers, NAS, development
 * servers). They are reached directly; the proxy's remote server could never reach them.
 */
internal object LocalAddress {
    /** Same coverage as [isLocal], in the WebView proxy bypass syntax. */
    val bypassRules = listOf(
        "localhost", "*.localhost", "<local>", "*.local", "*.lan", "*.home.arpa",
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "::1/128", "fc00::/7", "fe80::/10"
    )

    /** True for loopback, private, link-local and shared ranges, and local-only host names. Never resolves DNS. */
    fun isLocal(host: String?): Boolean {
        val name = host?.trim()?.trimStart('[')?.trimEnd(']')?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home.arpa")) return true
        val literal = literal(name) ?: return !name.contains('.') && !name.contains(':')
        return when (literal) {
            is Inet4Address -> {
                val b = literal.address.map { it.toInt() and 0xff }
                b[0] == 127 || b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                    (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] in 64..127)
            }
            is Inet6Address -> {
                val b = literal.address.map { it.toInt() and 0xff }
                literal.isLoopbackAddress || (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
            }
            else -> false
        }
    }

    /** Parses only numeric addresses, so no lookup ever happens. */
    private fun literal(name: String): InetAddress? = when {
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(name) ->
            name.split('.').map { it.toInt() }.takeIf { parts -> parts.all { it in 0..255 } }
                ?.let { parts -> InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray()) }
        name.contains(':') && Regex("""^[0-9a-f:.%a-z]+$""").matches(name) -> runCatching { InetAddress.getByName(name.substringBefore('%')) }.getOrNull()
        else -> null
    }
}

/** Sends local addresses direct and everything else through [proxy]. */
internal class LocalDirect(private val proxy: java.net.Proxy) : java.net.ProxySelector() {
    override fun select(uri: java.net.URI?): List<java.net.Proxy> =
        listOf(if (LocalAddress.isLocal(uri?.host)) java.net.Proxy.NO_PROXY else proxy)
    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
}
