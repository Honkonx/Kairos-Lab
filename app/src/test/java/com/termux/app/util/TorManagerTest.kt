package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: org.json.JSONObject es un stub vacío en la JVM del host (mismo motivo que ModuleControllerTest).
@RunWith(RobolectricTestRunner::class)
class TorManagerTest {
    @Test
    fun parseCheckResponseReadsTorExitAndIp() {
        assertEquals(Pair(true, "185.220.101.5"), TorManager.parseCheckResponse("""{"IsTor":true,"IP":"185.220.101.5"}"""))
        assertEquals(Pair(false, "203.0.113.9"), TorManager.parseCheckResponse("""{"IsTor":false,"IP":"203.0.113.9"}"""))
    }

    @Test
    fun parseCheckResponseRejectsAnythingThatIsNotTheExpectedJson() {
        assertNull(TorManager.parseCheckResponse(""))
        assertNull(TorManager.parseCheckResponse("<html>502</html>"))
        assertNull(TorManager.parseCheckResponse("""{"ok":true}"""))
    }

    @Test
    fun torrcSocksPortDetectionIgnoresComments() {
        assertTrue(TorManager.torrcHasSocksPort("Log notice\nSocksPort 9050\n"))
        assertTrue(TorManager.torrcHasSocksPort("  SocksPort 127.0.0.1:9050"))
        assertFalse(TorManager.torrcHasSocksPort("#SocksPort 9050\n## SocksPort 9051"))
        assertFalse(TorManager.torrcHasSocksPort(""))
    }

    @Test
    fun socksUrlUsesRemoteDnsResolution() {
        // socks5h = el hostname lo resuelve Tor, no el dispositivo (evita fuga de DNS).
        assertEquals("socks5h://127.0.0.1:9050", TorManager.SOCKS_URL)
    }
}
