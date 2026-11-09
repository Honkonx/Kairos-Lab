package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Niveles de permiso del gateway (`native/gateway/internal/permission`, docs/homelab/GATEWAY_GO.md), en el orden
 * de menor a mayor. Los nombres son los mismos que escribe el gateway en sus archivos y en su API.
 */
object GatewayLevels {
    const val READ = "lectura"
    const val ASK = "pregunta"
    const val SEMI = "semi-total"
    const val FULL = "total"
    val ALL = listOf(READ, ASK, SEMI, FULL)

    /** Los niveles por encima de "pregunta" solo valen si el usuario marcó "soy responsable de lo que pase". */
    fun needsResponsibility(level: String): Boolean = level == SEMI || level == FULL
}

data class GatewayPolicy(val max: String, val responsible: Boolean)
data class GatewayToken(val id: String, val name: String, val level: String, val created: String)

/** Token recién creado: [token] es el valor en claro y SOLO viaja en esta respuesta (nunca se vuelve a mostrar). */
data class GatewayCreatedToken(val token: String, val id: String, val name: String, val level: String)

/** Una acción que espera la confirmación del usuario (nivel "pregunta"). */
data class GatewayPending(
    val id: String,
    val tokenName: String,
    val actionId: String,
    val title: String,
    val risk: String,
    val params: Map<String, String>,
)

data class GatewayAuditEntry(val time: String, val tokenId: String, val action: String, val decision: String, val result: String)

/**
 * Cliente HTTP de la administración del gateway (rutas `/v1/admin/...`), solo para la app: manda el secreto en
 * `X-Kairos-Admin`. Todo es bloqueante: llamarlo fuera del hilo principal. Cualquier fallo de red o respuesta
 * inesperada devuelve null/false/lista vacía; nunca lanza hacia la UI.
 */
class GatewayAdminClient(
    private val baseUrl: String,
    private val adminSecret: String,
    private val timeoutMs: Int = 5000,
) {
    private data class Reply(val code: Int, val body: String)

    /**
     * Comprueba que quien responde en el puerto es **nuestro** gateway antes de enviarle el secreto de administración: otra
     * app del teléfono podría haber ocupado el puerto. El gateway devuelve `proof = HMAC-SHA256(secreto, reto)`; quien no
     * conoce el secreto no puede producirla. Esta consulta no lleva el secreto.
     */
    private fun gatewayIsGenuine(): Boolean {
        if (adminSecret.isEmpty()) return false
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val conn = try {
            (URL("$baseUrl/healthz?challenge=$nonce").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
        } catch (_: Exception) { return false }
        return try {
            if (conn.responseCode != 200) return false
            val proof = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("proof")
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(adminSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            val expected = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), proof.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): Reply? {
        if (path.startsWith("/v1/admin") && !gatewayIsGenuine()) return null
        return try {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("X-Kairos-Admin", adminSecret)
                setRequestProperty("Accept", "application/json")
                if (body != null || method == "POST" || method == "PUT") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write((body?.toString() ?: "").toByteArray(Charsets.UTF_8)) }
                }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Reply(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** ¿Responde `/healthz` (público, sin secreto)? */
    fun health(): Boolean = request("GET", "/healthz")?.code == 200

    /** Pide al gateway que se apague de forma ordenada. */
    fun shutdown(): Boolean = request("POST", "/v1/admin/shutdown")?.code == 200

    fun getPolicy(): GatewayPolicy? {
        val r = request("GET", "/v1/admin/policy") ?: return null
        if (r.code != 200) return null
        return try {
            JSONObject(r.body).let { GatewayPolicy(it.optString("max"), it.optBoolean("responsible")) }
        } catch (_: Exception) {
            null
        }
    }

    fun setPolicy(policy: GatewayPolicy): Boolean {
        val body = JSONObject().put("max", policy.max).put("responsible", policy.responsible)
        return request("PUT", "/v1/admin/policy", body)?.code == 200
    }

    fun listTokens(): List<GatewayToken> {
        val r = request("GET", "/v1/admin/tokens") ?: return emptyList()
        if (r.code != 200) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("tokens") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                GatewayToken(it.optString("id"), it.optString("name"), it.optString("level"), it.optString("created"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun createToken(name: String, level: String): GatewayCreatedToken? {
        val r = request("POST", "/v1/admin/tokens", JSONObject().put("name", name).put("level", level)) ?: return null
        if (r.code != 201) return null
        return try {
            JSONObject(r.body).let {
                GatewayCreatedToken(it.optString("token"), it.optString("id"), it.optString("name"), it.optString("level"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun revokeToken(id: String): Boolean = request("DELETE", "/v1/admin/tokens/${java.net.URLEncoder.encode(id, "UTF-8")}")?.code == 200

    fun listPending(): List<GatewayPending> {
        val r = request("GET", "/v1/admin/pending") ?: return emptyList()
        if (r.code != 200) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("pending") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("request") }.map(::pendingFrom)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Aprueba o deniega una confirmación. Al aprobar, el gateway ejecuta la acción por su cuenta. */
    fun decide(id: String, approve: Boolean): Boolean =
        request("POST", "/v1/admin/pending/${java.net.URLEncoder.encode(id, "UTF-8")}", JSONObject().put("approve", approve))?.code == 200

    fun auditTail(n: Int = 50): List<GatewayAuditEntry> {
        val r = request("GET", "/v1/admin/audit?n=$n") ?: return emptyList()
        if (r.code != 200) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("entries") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                GatewayAuditEntry(it.optString("time"), it.optString("token_id"), it.optString("action"), it.optString("decision"), it.optString("result"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Flujo SSE completo de la administración (`/v1/admin/events`): bloquea hasta que [shouldStop] sea true, la
     * conexión se corte o falle. Entrega cada evento como (tipo, datos); para `pending` los datos traen la
     * petición. El llamador decide si reconectar. El gateway manda un latido cada 15 s, así que 45 s sin nada
     * significa conexión muerta.
     */
    fun streamEvents(shouldStop: () -> Boolean, onEvent: (type: String, data: JSONObject?) -> Unit) {
        if (!gatewayIsGenuine()) return
        val conn = try {
            (URL("$baseUrl/v1/admin/events").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = 45_000
                setRequestProperty("X-Kairos-Admin", adminSecret)
                setRequestProperty("Accept", "text/event-stream")
            }
        } catch (_: Exception) {
            return
        }
        try {
            if (conn.responseCode != 200) return
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var type = ""
                var data = StringBuilder()
                while (!shouldStop()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event: ") -> type = line.removePrefix("event: ").trim()
                        line.startsWith("data: ") -> data.append(line.removePrefix("data: "))
                        line.isEmpty() -> {
                            if (type.isNotEmpty()) {
                                val inner = try {
                                    JSONObject(data.toString()).optJSONObject("data")
                                } catch (_: Exception) {
                                    null
                                }
                                onEvent(type, inner)
                            }
                            type = ""
                            data = StringBuilder()
                        }
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            // sin latidos: se da por caída; el llamador reconecta
        } catch (_: Exception) {
            // conexión cerrada o rota
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Convierte la petición pendiente que manda el gateway (campos del paquete `pending`) en el modelo de la app. */
        fun pendingFrom(o: JSONObject): GatewayPending {
            val params = mutableMapOf<String, String>()
            o.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.optString(k) } }
            return GatewayPending(
                id = o.optString("id"),
                tokenName = o.optString("token_name"),
                actionId = o.optString("action_id"),
                title = o.optString("title"),
                risk = o.optString("risk"),
                params = params,
            )
        }
    }
}
