package com.v2ray.ang.embedded

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class EmbeddedProxyTest {
    @Test fun customProfilesCannotExposeOrReplaceHostListener() {
        val raw = """{"inbounds":[{"tag":"tun","protocol":"tun"},{"tag":"socks","listen":"0.0.0.0","port":80,"protocol":"http","settings":{"accounts":[{"user":"secret","pass":"secret"}]},"sniffing":{"enabled":true,"routeOnly":true}}],"dns":{"servers":["1.1.1.1"]},"routing":{"rules":[{"domain":["example.test"],"outboundTag":"block"}]},"outbounds":[{"tag":"block","protocol":"blackhole"}]}"""
        val input = JsonParser.parseString(raw).asJsonObject
        val runtime = JsonParser.parseString(EmbeddedProxy.runtimeConfig(raw, 19080)).asJsonObject
        assertEquals(input.get("routing"), runtime.get("routing"))
        assertEquals(input.get("dns"), runtime.get("dns"))
        assertEquals(input.get("outbounds"), runtime.get("outbounds"))
        val inbounds = runtime.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        val listener = inbounds[0].asJsonObject
        assertEquals("127.0.0.1", listener.get("listen").asString)
        assertEquals(19080, listener.get("port").asInt)
        assertEquals("socks", listener.get("protocol").asString)
        assertEquals("socks", listener.get("tag").asString)
        assertEquals("noauth", listener.getAsJsonObject("settings").get("auth").asString)
        assertFalse(listener.getAsJsonObject("settings").has("accounts"))
        assertEquals(input.getAsJsonArray("inbounds")[1].asJsonObject.get("sniffing"), listener.get("sniffing"))
    }
    @Test fun customProfileWithoutInboundStillGetsHostListener() {
        val json = JsonParser.parseString(EmbeddedProxy.runtimeConfig("""{"outbounds":[{"protocol":"freedom"}]}""", 19081)).asJsonObject
        assertEquals(19081, json.getAsJsonArray("inbounds")[0].asJsonObject.get("port").asInt)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPortCannotStartCore() {
        EmbeddedProxy.runtimeConfig("{}", 0)
    }
}
