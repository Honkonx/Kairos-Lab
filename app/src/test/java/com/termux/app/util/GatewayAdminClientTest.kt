package com.termux.app.util

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class GatewayAdminClientTest {
    private companion object {
        val SSE_BODY = listOf(": conectado", "", "event: pending", "data: {\"type\":\"pending\",\"data\":{\"id\":\"p9\",\"title\":\"X\"}}", "", "")
            .joinToString("\n")
    }

    private lateinit var server: ServerSocket
    private var lastSecret: String? = null
    private var lastBody: String = ""
    private lateinit var client: GatewayAdminClient

    /** Respuesta falsa según método + ruta (sin query). */
    private fun proofFor(secret: String, challenge: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(challenge.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Secreto con el que este servidor falso fabrica la prueba; otro valor = una app que ocupó el puerto. */
    private var serverSecret = "secreto-admin"

    private fun route(method: String, target: String): Triple<Int, String, String> {
        val path = target.substringBefore('?')
        val challenge = target.substringAfter("challenge=", "").substringBefore('&')
        val json = "application/json"
        return when {
            path == "/healthz" -> Triple(200, json, if (challenge.isEmpty()) "{\"ok\":true}" else "{\"ok\":true,\"proof\":\"${proofFor(serverSecret, challenge)}\"}")
            path == "/v1/admin/policy" && method == "GET" -> Triple(200, json, "{\"max\":\"pregunta\",\"responsible\":false}")
            path == "/v1/admin/policy" -> Triple(200, json, "{}")
            path == "/v1/admin/tokens" && method == "POST" ->
                Triple(201, json, "{\"token\":\"abc\",\"id\":\"t1\",\"name\":\"n\",\"level\":\"lectura\"}")
            path == "/v1/admin/tokens" -> Triple(200, json, "{\"tokens\":[{\"id\":\"t1\",\"name\":\"n\",\"level\":\"lectura\",\"created\":\"x\"}]}")
            path == "/v1/admin/pending" ->
                Triple(200, json, "{\"pending\":[{\"request\":{\"id\":\"p1\",\"token_name\":\"agente\",\"action_id\":\"mod.start\",\"title\":\"Iniciar\",\"risk\":\"medio\",\"params\":{\"id\":\"n8n\"}},\"state\":\"pendiente\"}]}")
            path.startsWith("/v1/admin/pending/") -> Triple(200, json, "{}")
            path == "/v1/admin/audit" ->
                Triple(200, json, "{\"entries\":[{\"time\":\"t\",\"token_id\":\"t1\",\"action\":\"a\",\"decision\":\"ejecutada\"}]}")
            path == "/v1/admin/events" ->
                Triple(200, "text/event-stream", SSE_BODY)
            else -> Triple(404, json, "{}")
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val parts = (input.readLine() ?: return@use).split(" ")
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).equals("X-Kairos-Admin", true)) lastSecret = line.substring(i + 1).trim()
            if (i > 0 && line.substring(0, i).equals("Content-Length", true)) length = line.substring(i + 1).trim().toInt()
        }
        val buf = CharArray(length)
        var read = 0
        while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
        lastBody = String(buf, 0, read)
        val (code, type, body) = route(parts[0], parts[1])
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = listOf("HTTP/1.1 $code X", "Content-Type: $type", "Content-Length: ${bytes.size}", "Connection: close", "", "")
            .joinToString("\r\n")
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    @Before
    fun setUp() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread { while (!server.isClosed) { try { serve(server.accept()) } catch (_: Exception) {} } }.apply { isDaemon = true; start() }
        client = GatewayAdminClient("http://127.0.0.1:${server.localPort}", "secreto-admin", 2000)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun healthAndPolicy() {
        assertTrue(client.health())
        assertEquals(GatewayPolicy("pregunta", false), client.getPolicy())
        assertEquals("secreto-admin", lastSecret)
        assertTrue(client.setPolicy(GatewayPolicy("total", true)))
        assertEquals("total", JSONObject(lastBody).getString("max"))
        assertTrue(JSONObject(lastBody).getBoolean("responsible"))
    }

    @Test
    fun tokensCreateAndList() {
        assertEquals(listOf(GatewayToken("t1", "n", "lectura", "x")), client.listTokens())
        assertEquals("abc", client.createToken("n", GatewayLevels.READ)?.token)
    }

    @Test
    fun pendingAndAudit() {
        val p = client.listPending().single()
        assertEquals("p1", p.id)
        assertEquals("n8n", p.params["id"])
        assertEquals("ejecutada", client.auditTail().single().decision)
        assertTrue(client.decide("p1", true))
    }

    @Test
    fun streamParsesEvents() {
        val got = mutableListOf<Pair<String, String?>>()
        client.streamEvents({ got.isNotEmpty() }) { type, data -> got.add(type to data?.optString("id")) }
        assertEquals(listOf("pending" to "p9"), got)
    }

    @Test
    fun unreachableServerFailsClosed() {
        val dead = GatewayAdminClient("http://127.0.0.1:1", "x", 300)
        assertFalse(dead.health())
        assertNull(dead.getPolicy())
        assertTrue(dead.listTokens().isEmpty())
        assertFalse(dead.decide("p", true))
    }

    @Test
    fun anImpostorOnThePortNeverReceivesTheAdminSecret() {
        serverSecret = "otro-secreto" // otra app ocupó el puerto: no conoce el secreto verdadero
        lastSecret = null
        assertNull(client.getPolicy())
        assertTrue(client.listTokens().isEmpty())
        assertFalse(client.shutdown())
        assertNull("el secreto no debe enviarse a quien no prueba conocerlo", lastSecret)
        assertTrue(client.health()) // la salud pública no lleva secreto
    }

    @Test
    fun levelsNeedingResponsibility() {
        assertFalse(GatewayLevels.needsResponsibility(GatewayLevels.ASK))
        assertTrue(GatewayLevels.needsResponsibility(GatewayLevels.SEMI))
        assertTrue(GatewayLevels.needsResponsibility(GatewayLevels.FULL))
    }
}
