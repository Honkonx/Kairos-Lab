package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GatewayControlOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRunner(val reply: (List<String>) -> Pair<Int, String> = { 0 to "" }) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>, timeoutSec: Long): Pair<Int, String> { calls += argv; return reply(argv) }
    }

    @Test
    fun sessionsOnlyListOwnPrefixAndNeverTouchModuleSessions() {
        val runner = FakeRunner { 0 to "kx-claude|1|0\nollama|1|1\nn8n|2|0\n" }
        val list = SessionManager(runner).list()
        assertEquals(listOf(SessionInfo("claude", 1, false)), list)
    }

    @Test
    fun sessionOpenPassesCommandAsSingleArgvAndPrefixesName() {
        val runner = FakeRunner()
        assertTrue(SessionManager(runner).open("claude", "claude --resume; rm -rf x").ok)
        val argv = runner.calls.single()
        assertEquals("kx-claude", argv[argv.indexOf("-s") + 1])
        assertEquals("claude --resume; rm -rf x", argv.last())
    }

    @Test
    fun sessionRejectsBadNamesWithoutRunningAnything() {
        val runner = FakeRunner()
        val m = SessionManager(runner)
        assertEquals(400, m.open("../x", "ls").exitCode)
        assertEquals(400, m.kill("Ollama").exitCode)
        assertEquals(400, m.read("a b", 10).exitCode)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun sessionSendUsesLiteralKeysThenEnter() {
        val runner = FakeRunner()
        SessionManager(runner).send("a", "hola", true)
        assertEquals(listOf("tmux", "send-keys", "-t", "kx-a", "-l", "hola"), runner.calls[0])
        assertEquals(listOf("tmux", "send-keys", "-t", "kx-a", "Enter"), runner.calls[1])
    }

    @Test
    fun homeFilesBlocksEscapesAndSecrets() {
        val home = tmp.newFolder("home")
        File(home, "ok.txt").writeText("hola")
        File(home, ".ssh").mkdirs(); File(home, ".ssh/id").writeText("secreto")
        val gw = File(home, "gw").also { it.mkdirs() }
        File(gw, "token").writeText("t")
        val files = HomeFiles(home, listOf(gw))
        assertEquals("hola", files.read("ok.txt").getOrNull())
        assertNull(files.resolve("../fuera"))
        assertNull(files.resolve("a/../../fuera"))
        assertNull(files.resolve(".ssh/id"))
        assertNull(files.resolve("gw/token"))
        File(home, ".homelab_token").writeText("t")
        assertNull(files.resolve(".homelab_token"))
        File(home, ".nube_token").writeText("t")
        assertNull(files.resolve(".nube_token"))
        assertNull(files.resolve("x/.npmrc"))
        assertTrue(files.read(".ssh/id").isFailure)
        assertFalse(files.list("").getOrThrow().any { it.name == "gw" })
    }

    @Test
    fun homeFilesWriteStaysInsideRoot() {
        val home = tmp.newFolder("home2")
        val files = HomeFiles(home)
        assertTrue(files.write("sub/n.txt", "x").isSuccess)
        assertEquals("x", File(home, "sub/n.txt").readText())
        assertTrue(files.write("../mal.txt", "x").isFailure)
    }

    private class MapSettings : AppSettings {
        val data = mutableMapOf("theme" to "oscuro")
        override val keys = setOf("theme")
        override fun get(key: String) = data[key]
        override fun set(key: String, value: String): Boolean { if (value !in setOf("oscuro", "claro")) return false; data[key] = value; return true }
    }

    private class NoModules : GatewayModuleOps {
        override fun list() = emptyList<ModuleSummary>()
        override fun status(id: String): ModuleSummary? = null
        override fun start(id: String) = ModuleOpResult(true)
        override fun stop(id: String) = ModuleOpResult(true)
        override fun install(id: String) = ModuleOpResult(true)
        override fun uninstall(id: String) = ModuleOpResult(true)
    }

    private fun core(runner: FakeRunner, home: File, settings: MapSettings = MapSettings()) = GatewayBridgeCore(
        "s", NoModules(), null,
        GatewayControl(SessionManager(runner), HomeFiles(home), settings, { id -> if (id == "claude") "claude" else null }),
    )

    private fun call(c: GatewayBridgeCore, target: String, vararg p: Pair<String, String>): Pair<Int, JSONObject> {
        val params = JSONObject().also { j -> p.forEach { j.put(it.first, it.second) } }
        val (code, body) = c.handle("s", JSONObject().put("target", target).put("params", params).toString())
        return code to JSONObject(body)
    }

    @Test
    fun bridgeOpensModuleTerminalFromCatalogOnly() {
        val runner = FakeRunner()
        val c = core(runner, tmp.newFolder("h3"))
        assertEquals(200, call(c, "session.open_module", "id" to "claude").first)
        assertEquals("claude", runner.calls.single().last())
        assertEquals(404, call(c, "session.open_module", "id" to "otro").first)
    }

    @Test
    fun bridgeSettingsAreAllowlisted() {
        val s = MapSettings()
        val c = core(FakeRunner(), tmp.newFolder("h4"), s)
        assertEquals("oscuro", call(c, "config.get", "key" to "theme").second.getString("value"))
        assertEquals(200, call(c, "config.set", "key" to "theme", "value" to "claro").first)
        assertEquals("claro", s.data["theme"])
        assertEquals(400, call(c, "config.set", "key" to "theme", "value" to "x").first)
        assertEquals(400, call(c, "config.get", "key" to "password").first)
    }

    @Test
    fun bridgeFilesRoundTripAndRejectEscape() {
        val c = core(FakeRunner(), tmp.newFolder("h5"))
        assertEquals(200, call(c, "fs.write", "path" to "a.txt", "content" to "uno").first)
        assertEquals("uno", call(c, "fs.read", "path" to "a.txt").second.getString("content"))
        assertEquals(400, call(c, "fs.read", "path" to "../x").first)
        assertNotNull(call(c, "fs.list", "path" to "").second.getJSONArray("entries"))
    }

    @Test
    fun bridgeFilesBase64RoundTripForBinaryContent() {
        val c = core(FakeRunner(), tmp.newFolder("h5b"))
        // Bytes no válidos como UTF-8 (0x00, 0xFF) — fs.write/fs.read de texto los corromperían.
        val bytes = byteArrayOf(0, 1, 2, -1, -2, 65, 66)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals(200, call(c, "fs.write", "path" to "bin.dat", "content" to b64, "encoding" to "base64").first)
        val (code, body) = call(c, "fs.read", "path" to "bin.dat", "encoding" to "base64")
        assertEquals(200, code)
        assertEquals("base64", body.getString("encoding"))
        assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(body.getString("content"))))
    }

    @Test
    fun bridgeFilesRejectInvalidBase64() {
        val c = core(FakeRunner(), tmp.newFolder("h5c"))
        assertEquals(400, call(c, "fs.write", "path" to "bin.dat", "content" to "no-es-base64!!", "encoding" to "base64").first)
    }

    // fs.compress/fs.extract ( lote 23, sin cobertura hasta este lote 33 -- ArchiveManagerTest.kt
    // ya prueba ArchiveManager en sí mismo; esto prueba el CAMINO REAL que usa el gateway: resolver las
    // rutas con HomeFiles.resolve() antes de llamarlo, igual que hace GatewayAppBridge.handleControl().
    @Test
    fun bridgeCompressAndExtractRoundTrip() {
        val c = core(FakeRunner(), tmp.newFolder("h5d"))
        assertEquals(200, call(c, "fs.write", "path" to "origen/a.txt", "content" to "contenido real").first)
        val (compressCode, compressBody) = call(c, "fs.compress", "files" to "origen/a.txt", "path" to "salida.zip", "format" to "zip")
        assertEquals(200, compressCode)
        assertTrue(compressBody.getBoolean("ok"))
        assertEquals("salida.zip", compressBody.getString("output"))
        assertEquals(200, call(c, "fs.mkdir", "path" to "destino").first)
        assertEquals(200, call(c, "fs.extract", "path" to "salida.zip", "destination" to "destino").first)
        assertEquals("contenido real", call(c, "fs.read", "path" to "destino/a.txt").second.getString("content"))
    }

    @Test
    fun bridgeCompressRejectsInvalidFormatAndMissingFiles() {
        val c = core(FakeRunner(), tmp.newFolder("h5e"))
        assertEquals(400, call(c, "fs.compress", "files" to "", "path" to "salida.zip", "format" to "zip").first)
        assertEquals(200, call(c, "fs.write", "path" to "a.txt", "content" to "x").first)
        assertEquals(400, call(c, "fs.compress", "files" to "a.txt", "path" to "salida.rar", "format" to "rar").first)
    }

    @Test
    fun bridgeCompressRejectsPathEscapeInFilesList() {
        val c = core(FakeRunner(), tmp.newFolder("h5f"))
        assertEquals(400, call(c, "fs.compress", "files" to "../afuera.txt", "path" to "salida.zip", "format" to "zip").first)
    }

    @Test
    fun homeFilesReadWriteBytesRoundTripAndEnforceSizeLimit() {
        val home = tmp.newFolder("home6")
        val files = HomeFiles(home)
        val data = ByteArray(10) { it.toByte() }
        assertTrue(files.writeBytes("x.bin", data).isSuccess)
        assertTrue(data.contentEquals(files.readBytes("x.bin").getOrThrow()))
        val tooBig = ByteArray(HomeFiles.MAX_WRITE_BINARY + 1)
        assertTrue(files.writeBytes("big.bin", tooBig).isFailure)
    }

    @Test
    fun controlTargetsFailClearlyWhenControlIsOff() {
        val c = GatewayBridgeCore("s", NoModules())
        assertEquals(404, call(c, "session.list").first)
    }

    @Test
    fun bareProgramsResolveToTermuxBinOnlyWhenTheyExist() {
        val bin = tmp.newFolder("bin")
        File(bin, "tmux").writeText("x")
        assertEquals(listOf(File(bin, "tmux").path, "ls"), resolveTermuxBinary(listOf("tmux", "ls"), bin.path))
        assertEquals(listOf("sh", "-c", "id"), resolveTermuxBinary(listOf("sh", "-c", "id"), bin.path))
        assertEquals(listOf("/system/bin/sh"), resolveTermuxBinary(listOf("/system/bin/sh"), bin.path))
    }
}
