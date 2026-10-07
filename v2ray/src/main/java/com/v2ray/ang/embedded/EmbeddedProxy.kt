package com.v2ray.ang.embedded

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

/** Host-owned preference; native running state remains owned by the daemon. */
object EmbeddedProxy {
    const val STATE_RELOADING = 90
    private const val ENABLED = "web_proxy_enabled"
    private const val ROUTING_PORT = "web_proxy_acknowledged_port"
    @JvmStatic fun setRoutingPort(port: Int) { MmkvManager.encodeSettings(ROUTING_PORT, port) }
    @JvmStatic fun getRoutingPort(): Int = MmkvManager.decodeSettingsInt(ROUTING_PORT, 0)
    @JvmStatic fun networkProxy(): java.net.Proxy {
        val port = getRoutingPort()
        return if (port > 0) java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)) else java.net.Proxy.NO_PROXY
    }
    private const val PORT = "web_proxy_runtime_port"
    @JvmStatic fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(ENABLED, false)
    @JvmStatic fun setEnabled(enabled: Boolean) { MmkvManager.encodeSettings(ENABLED, enabled) }
    @JvmStatic fun getPort(): Int = MmkvManager.decodeSettingsInt(PORT, 0)
    @JvmStatic fun allocatePort(): Int = Utils.findRandomFreePort().also { MmkvManager.encodeSettings(PORT, it) }
    @JvmStatic fun hasProfile(): Boolean = !MmkvManager.getSelectServer().isNullOrEmpty()

    /** Keep custom DNS/outbounds/routing, but the listener belongs to the host, never the profile. */
    @JvmStatic fun runtimeConfig(raw: String): String = runtimeConfig(raw, getPort())
    fun runtimeConfig(raw: String, port: Int): String {
        val json = JsonParser.parseString(raw).asJsonObject
        require(port in 1024..65535) { "Invalid embedded listener port" }
        val previous = json.getAsJsonArray("inbounds")?.firstOrNull { it.isJsonObject && it.asJsonObject.get("tag")?.asString == "socks" }?.asJsonObject
        val settings = JsonObject().apply {
            addProperty("auth", "noauth")
            addProperty("udp", false)
            addProperty("userLevel", 8)
        }
        val listener = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("listen", "127.0.0.1")
            addProperty("port", port)
            addProperty("protocol", "socks")
            add("settings", settings)
            previous?.get("sniffing")?.let { add("sniffing", it.deepCopy()) }
        }
        json.add("inbounds", JsonArray().apply { add(listener) })
        return json.toString()
    }
}
