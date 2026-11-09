package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeProvider(override val id: String, private val available: Boolean) : PrivilegeProvider {
    override val title = id
    override fun isAvailable() = available
    override fun run(command: String, timeoutSec: Long) = PrivilegeResult(true, "")
}

class ScreenMirrorManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun notReadyWhenNoShellLevelProviderIsAvailable() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("app", true)))
        val status = ScreenMirrorManager(registry, tmp.newFolder("gw1")).status()
        assertFalse(status.ready)
        assertEquals(null, status.provider)
    }

    @Test
    fun notReadyWhenShellProviderExistsButJarIsMissing() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("adb", true)))
        val status = ScreenMirrorManager(registry, tmp.newFolder("gw2"), jarExists = { false }).status()
        assertFalse(status.ready)
        assertEquals("adb", status.provider)
    }

    @Test
    fun readyOnlyMeansTheCommandCouldRunNotThatItWasVerified() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("shizuku", true)))
        val status = ScreenMirrorManager(registry, tmp.newFolder("gw3"), jarExists = { true }).status()
        assertTrue(status.ready)
        assertEquals("shizuku", status.provider)
    }

    @Test
    fun buildsTheAdaptedExecuteServerCommand() {
        val registry = PrivilegeRegistry(emptyList())
        val cmd = ScreenMirrorManager(registry, tmp.newFolder("gw4")).buildServerCommand(
            deviceJarPath = "/data/local/tmp/kairos-screen-server.jar",
            version = "0.1",
        )
        assertEquals("CLASSPATH=/data/local/tmp/kairos-screen-server.jar app_process / com.genymobile.scrcpy.Server 0.1", cmd)
    }

    @Test
    fun relayPortDiffersFromOtherLoopbackPorts() {
        // Debe coincidir con defaultScreenSourceAddr en native/gateway/cmd/kairos-gateway/main.go (8974) —
        // si algún día se renumera, hacerlo en los dos lados a la vez. Distinto a propósito de los otros
        // dos puertos loopback del proyecto: 8971 (GatewayAppBridge, el puente app↔gateway) y 8973 (donde
        // escucha el propio gateway, --listen de main.go) — ninguno debería poder chocar con otro.
        assertEquals(8974, ScreenMirrorManager.DEFAULT_RELAY_PORT)
        assertEquals(8971, GatewayAppBridge.DEFAULT_PORT)
        assertFalse(ScreenMirrorManager.DEFAULT_RELAY_PORT == GatewayAppBridge.DEFAULT_PORT)
    }

    @Test
    fun startFailsWithoutShellProvider() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("app", true)))
        val r = ScreenMirrorManager(registry, tmp.newFolder("gw5")).start()
        assertFalse(r.ok)
        assertEquals(503, r.exitCode)
    }

    @Test
    fun startFailsWithoutScidFile() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("adb", true)))
        val r = ScreenMirrorManager(registry, tmp.newFolder("gw6"), jarExists = { true }).start()
        assertFalse(r.ok)
        assertEquals(404, r.exitCode)
    }

    @Test
    fun startRunsTheRealServerCommandInBackgroundOnceScidExists() {
        val dir = tmp.newFolder("gw7")
        java.io.File(dir, "screen.scid").writeText("deadbeef")
        var lastCmd = ""
        val provider = object : PrivilegeProvider {
            override val id = "adb"
            override val title = "adb"
            override fun isAvailable() = true
            override fun run(command: String, timeoutSec: Long): PrivilegeResult { lastCmd = command; return PrivilegeResult(true, "") }
        }
        val r = ScreenMirrorManager(PrivilegeRegistry(listOf(provider)), dir, jarExists = { true }).start()
        assertTrue(r.ok)
        assertTrue("debe correr en segundo plano", lastCmd.contains("&"))
        assertTrue("debe incluir el scid real", lastCmd.contains("scid=deadbeef"))
        assertTrue("debe apuntar al jar real", lastCmd.contains(ScreenMirrorManager.JAR_NAME))
    }

    @Test
    fun startClampsAbsurdValuesBeforeBuildingTheCommand() {
        val dir = tmp.newFolder("gw10")
        java.io.File(dir, "screen.scid").writeText("deadbeef")
        var lastCmd = ""
        val provider = object : PrivilegeProvider {
            override val id = "adb"
            override val title = "adb"
            override fun isAvailable() = true
            override fun run(command: String, timeoutSec: Long): PrivilegeResult { lastCmd = command; return PrivilegeResult(true, "") }
        }
        val mgr = ScreenMirrorManager(PrivilegeRegistry(listOf(provider)), dir, jarExists = { true })
        mgr.start(maxSize = -1, bitRate = Int.MAX_VALUE, maxFps = 0)
        assertTrue("maxSize negativo se acota al mínimo", lastCmd.contains("max_size=128"))
        assertTrue("bitRate absurdo se acota al techo", lastCmd.contains("video_bit_rate=50000000"))
        assertTrue("maxFps 0 se acota al mínimo", lastCmd.contains("max_fps=1"))
    }

    @Test
    fun readScidRejectsMalformedFiles() {
        val dir = tmp.newFolder("gw8")
        assertEquals(null, ScreenMirrorManager(PrivilegeRegistry(emptyList()), dir).readScid())
        java.io.File(dir, "screen.scid").writeText("not-hex!")
        assertEquals(null, ScreenMirrorManager(PrivilegeRegistry(emptyList()), dir).readScid())
        java.io.File(dir, "screen.scid").writeText("deadbeef")
        assertEquals("deadbeef", ScreenMirrorManager(PrivilegeRegistry(emptyList()), dir).readScid())
    }

    @Test
    fun bridgeExposesScreenStatusAsJson() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("adb", true)))
        val ops = object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(false)
            override fun stop(id: String) = ModuleOpResult(false)
            override fun install(id: String) = ModuleOpResult(false)
            override fun uninstall(id: String) = ModuleOpResult(false)
        }
        val core = GatewayBridgeCore("s3cr3t", ops, registry, screenDir = tmp.newFolder("gw9"))
        val (code, body) = core.handle("s3cr3t", """{"target":"screen.status","params":{}}""")
        assertEquals(200, code)
        val json = JSONObject(body)
        assertFalse(json.getBoolean("ready"))
        assertEquals("adb", json.getString("provider"))
    }

    @Test
    fun bridgeWithoutScreenDirReturns404ForTheWholeScreenFamily() {
        val registry = PrivilegeRegistry(listOf(FakeProvider("adb", true)))
        val ops = object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(false)
            override fun stop(id: String) = ModuleOpResult(false)
            override fun install(id: String) = ModuleOpResult(false)
            override fun uninstall(id: String) = ModuleOpResult(false)
        }
        val core = GatewayBridgeCore("s3cr3t", ops, registry)
        for (target in listOf("screen.status", "screen.start", "screen.stop")) {
            val (code, _) = core.handle("s3cr3t", """{"target":"$target","params":{}}""")
            assertEquals(target, 404, code)
        }
    }
}
