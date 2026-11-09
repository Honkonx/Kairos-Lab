package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeProvidersTest {
    private class FakeRunner(val answers: Map<String, Pair<Int, String>>) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>, timeoutSec: Long): Pair<Int, String> {
            calls.add(argv)
            return answers[argv.joinToString(" ")] ?: (127 to "no existe")
        }
    }

    @Test
    fun rootIsAvailableOnlyWithUidZero() {
        assertTrue(RootProvider(FakeRunner(mapOf("su -c id" to (0 to "uid=0(root)")))).isAvailable())
        assertFalse(RootProvider(FakeRunner(mapOf("su -c id" to (0 to "uid=10810(app)")))).isAvailable())
        assertFalse(RootProvider(FakeRunner(emptyMap())).isAvailable())
    }

    @Test
    fun adbNeedsTheDeviceInDeviceState() {
        val ok = FakeRunner(mapOf("adb devices" to (0 to "List of devices attached\nlocalhost:5555\tdevice\n")))
        val unauthorized = FakeRunner(mapOf("adb devices" to (0 to "List of devices attached\nlocalhost:5555\tunauthorized\n")))
        assertTrue(AdbLocalProvider(ok).isAvailable())
        assertFalse(AdbLocalProvider(unauthorized).isAvailable())
    }

    @Test
    fun shizukuNeedsTheRishFile() {
        val runner = FakeRunner(mapOf("sh /x/rish -c id" to (0 to "uid=2000(shell)")))
        assertFalse(ShizukuRishProvider(runner, "/x/rish") { false }.isAvailable())
        assertTrue(ShizukuRishProvider(runner, "/x/rish") { true }.isAvailable())
    }

    @Test
    fun registryNeverFallsBackToAnotherProvider() {
        val runner = FakeRunner(mapOf("su -c id" to (1 to "no su"), "sh -c echo hi" to (0 to "hi")))
        val registry = PrivilegeRegistry(listOf(AppShellProvider(runner), RootProvider(runner)))
        assertEquals(503, registry.run("root", "echo hi").exitCode)
        assertEquals(404, registry.run("nada", "echo hi").exitCode)
        assertEquals("hi", registry.run("app", "echo hi").output)
        assertEquals(listOf(true, false), registry.status().map { it.available })
    }

    @Test
    fun bridgeExposesStatusAndRunWithTheSecretOnly() {
        val runner = FakeRunner(mapOf("sh -c echo hi" to (0 to "hi")))
        val core = GatewayBridgeCore("s", object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(true)
            override fun stop(id: String) = ModuleOpResult(true)
            override fun install(id: String) = ModuleOpResult(true)
            override fun uninstall(id: String) = ModuleOpResult(true)
        }, PrivilegeRegistry(listOf(AppShellProvider(runner))))
        val (code, body) = core.handle("s", """{"target":"privilege.status","params":{}}""")
        assertEquals(200, code)
        assertEquals("app", JSONObject(body).getJSONArray("providers").getJSONObject(0).getString("id"))
        val (runCode, runBody) = core.handle("s", """{"target":"privilege.run","params":{"provider":"app","cmd":"echo hi"}}""")
        assertEquals(200, runCode)
        assertEquals("hi", JSONObject(runBody).getString("output"))
        assertEquals(403, core.handle("otro", """{"target":"privilege.run","params":{"provider":"app","cmd":"echo hi"}}""").first)
        assertEquals(400, core.handle("s", """{"target":"privilege.run","params":{"provider":"app","cmd":""}}""").first)
    }
}
