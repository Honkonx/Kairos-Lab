package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayLinkTest {
    @Test
    fun normalizeAddsSchemeAndPort() {
        assertEquals("http://192.168.1.5:8973", GatewayLink.normalizeCustom("192.168.1.5", 8973))
        assertEquals("http://mi-casa.local:9000", GatewayLink.normalizeCustom(" http://mi-casa.local:9000/ ", 8973))
        assertEquals("https://gw.ejemplo.com", GatewayLink.normalizeCustom("https://gw.ejemplo.com/", 8973))
        assertEquals("", GatewayLink.normalizeCustom("   ", 8973))
    }

    @Test
    fun resolveFollowsPriority() {
        assertEquals("http://1.2.3.4:8973", GatewayLink.resolve("http://1.2.3.4:8973", "https://t.trycloudflare.com", true, "10.0.0.2", 8973))
        assertEquals("https://t.trycloudflare.com", GatewayLink.resolve("", "https://t.trycloudflare.com/", true, "10.0.0.2", 8973))
        assertEquals("https://10.0.0.2:8973", GatewayLink.resolve("", "", true, "10.0.0.2", 8973))
        assertEquals("http://127.0.0.1:8973", GatewayLink.resolve("", "", true, "localhost", 8973))
        assertEquals("http://127.0.0.1:8973", GatewayLink.resolve("", "", false, "10.0.0.2", 8973))
    }
}
