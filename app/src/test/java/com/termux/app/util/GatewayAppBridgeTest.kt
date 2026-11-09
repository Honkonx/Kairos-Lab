package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAppBridgeTest {
    private class FakeOps : GatewayModuleOps {
        val calls = mutableListOf<String>()
        private val modules = listOf(ModuleSummary("n8n", "n8n", installed = true, running = false))
        override fun list() = modules
        override fun status(id: String) = modules.firstOrNull { it.id == id }
        override fun start(id: String): ModuleOpResult { calls += "start:$id"; return ModuleOpResult(true) }
        override fun stop(id: String): ModuleOpResult { calls += "stop:$id"; return ModuleOpResult(true, pending = true) }
        override fun install(id: String): ModuleOpResult { calls += "install:$id"; return ModuleOpResult(false, message = "boom") }
        override fun uninstall(id: String): ModuleOpResult { calls += "uninstall:$id"; return ModuleOpResult(true) }
    }

    private val ops = FakeOps()
    private val core = GatewayBridgeCore("secreto", ops)

    private fun call(secret: String?, target: String, id: String? = null): Pair<Int, JSONObject> {
        val params = JSONObject().also { if (id != null) it.put("id", id) }
        val (code, body) = core.handle(secret, JSONObject().put("target", target).put("params", params).toString())
        return code to JSONObject(body)
    }

    @Test
    fun wrongOrMissingSecretIsRejectedBeforeAnyWork() {
        assertEquals(403, call(null, "module.start", "n8n").first)
        assertEquals(403, call("otro", "module.start", "n8n").first)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun emptyConfiguredSecretNeverAuthenticates() {
        val open = GatewayBridgeCore("", ops)
        assertEquals(403, open.handle("", """{"target":"module.list"}""").first)
    }

    @Test
    fun invalidIdsAreRejected() {
        for (bad in listOf("", "N8N", "a b", "../x", "x;rm", "a".repeat(41))) {
            assertEquals("id '$bad'", 400, call("secreto", "module.start", bad).first)
        }
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun listAndStatus() {
        val (code, json) = call("secreto", "module.list")
        assertEquals(200, code)
        assertEquals("n8n", json.getJSONArray("modules").getJSONObject(0).getString("id"))
        assertEquals(200, call("secreto", "module.status", "n8n").first)
        assertEquals(404, call("secreto", "module.status", "inexistente").first)
    }

    @Test
    fun startStopInstallMapToStatusCodes() {
        assertEquals(200, call("secreto", "module.start", "n8n").first)
        assertEquals(202, call("secreto", "module.stop", "n8n").first)
        val (code, json) = call("secreto", "module.install", "n8n")
        assertEquals(500, code)
        assertFalse(json.getBoolean("ok"))
        assertEquals(listOf("start:n8n", "stop:n8n", "install:n8n"), ops.calls)
    }

    @Test
    fun unknownTargetAndBadBody() {
        assertEquals(404, call("secreto", "system.reboot").first)
        assertEquals(400, core.handle("secreto", "no es json").first)
    }

    private class FakeMetrics(private val json: JSONObject) : GatewayMetricsOps {
        override fun read() = json
    }

    @Test
    fun systemMetricsReturns404WhenNotWiredAnd200WithTheRealData() {
        assertEquals(404, call("secreto", "system.metrics").first)
        val data = JSONObject().put("cpu_percent", 12).put("ram_used_mb", 100).put("ram_total_mb", 200)
            .put("storage_used_gb", 1.5).put("storage_total_gb", 10.0).put("storage_has_permission", true)
            .put("battery_percent", 88)
        val withMetrics = GatewayBridgeCore("secreto", ops, metrics = FakeMetrics(data))
        val (code, body) = withMetrics.handle("secreto", JSONObject().put("target", "system.metrics").toString())
        assertEquals(200, code)
        assertEquals(12, JSONObject(body).getInt("cpu_percent"))
        assertEquals(88, JSONObject(body).getInt("battery_percent"))
    }

    /** Fake sin tocar [NubeServer] real (socket real) — mismo espíritu que [FakeMetrics]. */
    private class FakeCloud(var running: Boolean = false, var port: Int = 8971, var files: Int = 0) : GatewayCloudOps {
        val calls = mutableListOf<String>()
        var shareLinkResult: CloudShareResult = CloudShareResult(false, message = "no configurado en el fake")
        override fun status(): JSONObject {
            val json = JSONObject().put("running", running).put("port", port)
            if (running) json.put("file_count", files)
            return json
        }
        override fun start(): CloudOpResult { calls += "start"; running = true; return CloudOpResult(true) }
        override fun stop(): CloudOpResult { calls += "stop"; running = false; return CloudOpResult(true) }
        override fun shareLink(path: String): CloudShareResult { calls += "share_link:$path"; return shareLinkResult }
    }

    @Test
    fun cloudActionsReturn404WhenNotWiredAndNeverLeakTheToken() {
        assertEquals(404, call("secreto", "cloud.status").first)
        val cloud = FakeCloud(running = true, port = 8971, files = 3)
        val withCloud = GatewayBridgeCore("secreto", ops, cloud = cloud)

        val (statusCode, statusBody) = withCloud.handle("secreto", JSONObject().put("target", "cloud.status").toString())
        assertEquals(200, statusCode)
        val statusJson = JSONObject(statusBody)
        assertTrue(statusJson.getBoolean("running"))
        assertEquals(3, statusJson.getInt("file_count"))
        assertFalse("cloud.status nunca debe incluir el token", statusJson.toString().contains("token", ignoreCase = true))

        val (stopCode, _) = withCloud.handle("secreto", JSONObject().put("target", "cloud.stop").toString())
        assertEquals(200, stopCode)
        val (startCode, _) = withCloud.handle("secreto", JSONObject().put("target", "cloud.start").toString())
        assertEquals(200, startCode)
        assertEquals(listOf("stop", "start"), cloud.calls)
    }

    @Test
    fun cloudShareLinkReturns404WhenNotWiredAnd200WithTheUrlFromTheOps() {
        assertEquals(404, call("secreto", "cloud.share_link").first)

        val cloud = FakeCloud(running = true)
        cloud.shareLinkResult = CloudShareResult(true, url = "http://192.168.1.5:8971/download?path=foto.png&token=abc")
        val withCloud = GatewayBridgeCore("secreto", ops, cloud = cloud)
        val params = JSONObject().put("path", "foto.png")
        val (code, body) = withCloud.handle("secreto", JSONObject().put("target", "cloud.share_link").put("params", params).toString())
        assertEquals(200, code)
        val json = JSONObject(body)
        assertTrue(json.getBoolean("ok"))
        assertEquals("http://192.168.1.5:8971/download?path=foto.png&token=abc", json.getString("url"))
        assertEquals(listOf("share_link:foto.png"), cloud.calls)
    }

    @Test
    fun cloudShareLinkFailureMapsToBadRequest() {
        val cloud = FakeCloud(running = true)
        cloud.shareLinkResult = CloudShareResult(false, message = "ruta inválida o fuera de la carpeta nube")
        val withCloud = GatewayBridgeCore("secreto", ops, cloud = cloud)
        val params = JSONObject().put("path", "../fuera")
        val (code, body) = withCloud.handle("secreto", JSONObject().put("target", "cloud.share_link").put("params", params).toString())
        assertEquals(400, code)
        assertFalse(JSONObject(body).getBoolean("ok"))
    }

    private class FakeMinipc(private val json: JSONObject) : GatewayMinipcOps {
        var desktopStartResult: JSONObject = JSONObject().put("ok", true)
        var desktopStopResult: JSONObject = JSONObject().put("ok", true)
        var desktopStatusResult: JSONObject = JSONObject().put("ok", true)
        var lastDesktopStartArgs: Pair<String, String?>? = null
        override fun vncStatus(): JSONObject = json
        override fun desktopStart(de: String, distro: String?): JSONObject {
            lastDesktopStartArgs = de to distro
            return desktopStartResult
        }
        override fun desktopStop(): JSONObject = desktopStopResult
        override fun desktopStatus(): JSONObject = desktopStatusResult
    }

    @Test
    fun minipcVncStatusReturns404WhenNotWiredAnd200WithTheRealShape() {
        assertEquals(404, call("secreto", "minipc.vnc_status").first)
        val status = JSONObject().put("installed", true).put("running", false).put("port", 5901)
        val withMinipc = GatewayBridgeCore("secreto", ops, minipc = FakeMinipc(status))

        val (code, body) = withMinipc.handle("secreto", JSONObject().put("target", "minipc.vnc_status").toString())
        assertEquals(200, code)
        val json = JSONObject(body)
        assertTrue(json.getBoolean("installed"))
        assertFalse(json.getBoolean("running"))
        assertEquals(5901, json.getInt("port"))
    }

    @Test
    fun minipcDesktopStartRejectsUnknownDesktopEnvironmentAndInvalidDistroAlias() {
        val fake = FakeMinipc(JSONObject())
        val withMinipc = GatewayBridgeCore("secreto", ops, minipc = fake)

        val badDe = withMinipc.handle("secreto", JSONObject().put("target", "minipc.desktop_start").put("params", JSONObject().put("de", "gnome")).toString())
        assertEquals(400, badDe.first)
        assertNull(fake.lastDesktopStartArgs)

        val badDistro = withMinipc.handle("secreto", JSONObject().put("target", "minipc.desktop_start").put("params", JSONObject().put("de", "xfce4").put("distro", "../etc")).toString())
        assertEquals(400, badDistro.first)
        assertNull(fake.lastDesktopStartArgs)
    }

    @Test
    fun minipcDesktopStartDefaultsToXfce4NativeAndForwardsResultAsIs() {
        val fake = FakeMinipc(JSONObject())
        fake.desktopStartResult = JSONObject().put("ok", true).put("message", "XFCE4 corriendo")
        val withMinipc = GatewayBridgeCore("secreto", ops, minipc = fake)

        val (code, body) = withMinipc.handle("secreto", JSONObject().put("target", "minipc.desktop_start").put("params", JSONObject()).toString())
        assertEquals(200, code)
        assertEquals("xfce4" to null, fake.lastDesktopStartArgs)
        assertEquals("XFCE4 corriendo", JSONObject(body).getString("message"))
    }

    @Test
    fun minipcDesktopStopReturns500WhenTheRealOperationFails() {
        val fake = FakeMinipc(JSONObject())
        fake.desktopStopResult = JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        val withMinipc = GatewayBridgeCore("secreto", ops, minipc = fake)

        val (code, body) = withMinipc.handle("secreto", JSONObject().put("target", "minipc.desktop_stop").toString())
        assertEquals(500, code)
        assertEquals("Entorno no instalado todavía", JSONObject(body).getString("error"))
    }

    /** Fake sin tocar [KairosAgentClient] real (requiere el binario `kairos-agent`) — mismo espíritu que [FakeCloud]. */
    private class FakeAgent(var result: AgentAskResult) : GatewayAgentOps {
        val calls = mutableListOf<String>()
        override fun ask(text: String): AgentAskResult { calls += text; return result }
    }

    private fun callAgent(core: GatewayBridgeCore, text: String?): Pair<Int, JSONObject> {
        val params = JSONObject().also { if (text != null) it.put("text", text) }
        val (code, body) = core.handle("secreto", JSONObject().put("target", "agent.ask").put("params", params).toString())
        return code to JSONObject(body)
    }

    @Test
    fun agentAskReturns404WhenNotWiredAndMapsOkToStatusCodes() {
        assertEquals(404, callAgent(core, "estado de n8n").first)

        val agent = FakeAgent(AgentAskResult(true, "n8n: no instalado."))
        val withAgent = GatewayBridgeCore("secreto", ops, agent = agent)
        val (okCode, okBody) = callAgent(withAgent, "estado de n8n")
        assertEquals(200, okCode)
        assertTrue(okBody.getBoolean("ok"))
        assertEquals("n8n: no instalado.", okBody.getString("reply"))
        assertEquals(listOf("estado de n8n"), agent.calls)

        agent.result = AgentAskResult(false, "El motor del Agente no respondió.")
        val (failCode, failBody) = callAgent(withAgent, "instala ollama")
        assertEquals(500, failCode)
        assertFalse(failBody.getBoolean("ok"))
    }

    @Test
    fun agentAskRejectsBlankOrTooLongText() {
        val withAgent = GatewayBridgeCore("secreto", ops, agent = FakeAgent(AgentAskResult(true, "")))
        assertEquals(400, callAgent(withAgent, null).first)
        assertEquals(400, callAgent(withAgent, "").first)
        assertEquals(400, callAgent(withAgent, "x".repeat(501)).first)
    }

    /** Fake sin tocar [KairosAgentClient] real — mismo espíritu que [FakeAgent]. */
    private class FakeAiChat(var result: AiChatResult) : GatewayAiChatOps {
        val calls = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        override fun chat(text: String, history: List<Pair<String, String>>): AiChatResult {
            calls += text to history
            return result
        }
    }

    private fun callAiChat(core: GatewayBridgeCore, text: String?, history: JSONArray? = null): Pair<Int, JSONObject> {
        val params = JSONObject()
        if (text != null) params.put("text", text)
        if (history != null) params.put("history", history)
        val (code, body) = core.handle("secreto", JSONObject().put("target", "ai.chat").put("params", params).toString())
        return code to JSONObject(body)
    }

    @Test
    fun aiChatReturns404WhenNotWiredAndMapsOkToStatusCodes() {
        assertEquals(404, callAiChat(core, "hola").first)

        val chat = FakeAiChat(AiChatResult(true, "hola, ¿en qué te ayudo?"))
        val withChat = GatewayBridgeCore("secreto", ops, aiChat = chat)
        val (okCode, okBody) = callAiChat(withChat, "hola")
        assertEquals(200, okCode)
        assertTrue(okBody.getBoolean("ok"))
        assertEquals("hola, ¿en qué te ayudo?", okBody.getString("reply"))
        assertEquals(listOf("hola" to emptyList<Pair<String, String>>()), chat.calls)

        chat.result = AiChatResult(false, "Ningún modelo de IA respondió.")
        val (failCode, failBody) = callAiChat(withChat, "otra vez")
        assertEquals(500, failCode)
        assertFalse(failBody.getBoolean("ok"))
    }

    @Test
    fun aiChatRejectsBlankOrTooLongTextAndTooLongHistory() {
        val withChat = GatewayBridgeCore("secreto", ops, aiChat = FakeAiChat(AiChatResult(true, "")))
        assertEquals(400, callAiChat(withChat, null).first)
        assertEquals(400, callAiChat(withChat, "").first)
        assertEquals(400, callAiChat(withChat, "x".repeat(2001)).first)
        val tooLongHistory = JSONArray((1..21).map { JSONObject().put("role", "user").put("content", "turno $it") })
        assertEquals(400, callAiChat(withChat, "hola", tooLongHistory).first)
    }

    @Test
    fun aiChatForwardsHistoryAsRoleContentPairsAndIgnoresJunkTurns() {
        val chat = FakeAiChat(AiChatResult(true, "ok"))
        val withChat = GatewayBridgeCore("secreto", ops, aiChat = chat)
        val history = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "primer mensaje"))
            .put(JSONObject().put("role", "assistant").put("content", "primera respuesta"))
            .put(JSONObject().put("role", "system").put("content", "esto se ignora"))
            .put(JSONObject().put("role", "user").put("content", ""))
        assertEquals(200, callAiChat(withChat, "segundo mensaje", history).first)
        assertEquals(
            listOf("primer mensaje" to "user", "primera respuesta" to "assistant").map { it.second to it.first },
            chat.calls.single().second,
        )
    }

    @Test
    fun aiChatAlsoAcceptsHistoryAsAStringifiedJsonArray() {
        // El gateway Go real solo transporta params como texto (map[string]string) — ver comentario
        // de handleAiChat() en GatewayAppBridge.kt. El panel/gateway manda "history" como un STRING con
        // un JSON array adentro, no como un JSONArray nativo.
        val chat = FakeAiChat(AiChatResult(true, "ok"))
        val withChat = GatewayBridgeCore("secreto", ops, aiChat = chat)
        val params = JSONObject().put("text", "hola").put("history", """[{"role":"user","content":"previo"}]""")
        val (code, _) = withChat.handle("secreto", JSONObject().put("target", "ai.chat").put("params", params).toString())
        assertEquals(200, code)
        assertEquals(listOf("user" to "previo"), chat.calls.single().second)
    }

    private class FakeOllama : GatewayOllamaOps {
        var modelsResult: JSONObject = JSONObject().put("ok", true).put("models", JSONArray())
        var pullResult: JSONObject = JSONObject().put("ok", true)
        var lastPulledName: String? = null
        override fun models(): JSONObject = modelsResult
        override fun running(): JSONObject = JSONObject().put("ok", true).put("models", JSONArray())
        override fun pull(name: String): JSONObject { lastPulledName = name; return pullResult }
        override fun delete(name: String): JSONObject = JSONObject().put("ok", true)
        override fun unload(name: String): JSONObject = JSONObject().put("ok", true)
    }

    @Test
    fun ollamaModelsReturns404WhenNotWiredAnd200WithTheRealShape() {
        assertEquals(404, call("secreto", "ollama.models").first)
        val fake = FakeOllama()
        fake.modelsResult = JSONObject().put("ok", true).put("models", JSONArray().put(JSONObject().put("name", "llama3:8b")))
        val withOllama = GatewayBridgeCore("secreto", ops, ollama = fake)

        val (code, body) = withOllama.handle("secreto", JSONObject().put("target", "ollama.models").toString())
        assertEquals(200, code)
        assertEquals("llama3:8b", JSONObject(body).getJSONArray("models").getJSONObject(0).getString("name"))
    }

    @Test
    fun ollamaPullRejectsAnInvalidModelNameWithoutCallingTheRealOllamaApi() {
        val fake = FakeOllama()
        val withOllama = GatewayBridgeCore("secreto", ops, ollama = fake)

        val (code, _) = withOllama.handle("secreto", JSONObject().put("target", "ollama.pull").put("params", JSONObject().put("name", "")).toString())
        assertEquals(400, code)
        assertNull(fake.lastPulledName)
    }

    @Test
    fun ollamaPullReturns500WhenTheRealDownloadFails() {
        val fake = FakeOllama()
        fake.pullResult = JSONObject().put("ok", false).put("error", "Ollama no responde")
        val withOllama = GatewayBridgeCore("secreto", ops, ollama = fake)

        val (code, body) = withOllama.handle("secreto", JSONObject().put("target", "ollama.pull").put("params", JSONObject().put("name", "llama3:8b")).toString())
        assertEquals(500, code)
        assertEquals("llama3:8b", fake.lastPulledName)
        assertEquals("Ollama no responde", JSONObject(body).getString("error"))
    }
}
