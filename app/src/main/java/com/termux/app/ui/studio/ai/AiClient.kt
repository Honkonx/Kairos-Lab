package com.termux.app.ui.studio.ai

import com.termux.app.util.AiCompany
import com.termux.app.util.AiProviders
import com.termux.app.util.LocalAiEndpoints
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Resultado de un pedido a la IA — nunca lanza, siempre resuelve a uno de estos dos casos. */
sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Error(val message: String) : AiResult()
}

/**
 * Cliente HTTP real para los proveedores de IA del IDE — endpoints y formatos de request
 * REPLICADOS de ChatFragment.kt en Kairos (app/src/main/java/com/termux/app/ui/ChatFragment.kt,
 * funciones cloudRequestSpec()/makeCloudRequest() para cloud, makeOllamaRequest()/
 * makeLlamaServerRequest() para local) — mismos endpoints, mismos headers de auth, mismo
 * formato de body por proveedor, confirmados ahí antes de escribir esto acá.
 *
 * A diferencia de ChatFragment (que soporta streaming SSE/NDJSON con historial de turnos),
 * esta primera versión hace un único request/respuesta sin estado — alcanza para "preguntar a
 * la IA sobre este código" (ver MainActivity.askAiAboutCode()), sin necesidad de reimplementar
 * el manejo de streaming completo en esta ronda.
 *
 * Todas las funciones son bloqueantes (HttpURLConnection sin threading propio) — el caller
 * (MainActivity) es responsable de correrlas en un Thread de fondo y volver al hilo principal
 * para tocar la UI, mismo criterio que OllamaApiClient.kt en Kairos.
 */
object AiClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val LOCAL_PROBE_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Atajo que resuelve proveedor + API key desde las preferencias guardadas por
     *  AiProviderSettingsActivity y despacha al backend correcto. */
    fun askWithPrefs(prefs: AiProviderPrefs, prompt: String): AiResult {
        val provider = prefs.getSelectedProvider()
        if (provider == AiCompany.CUSTOM) {
            val baseUrl = prefs.getCustomBaseUrl()
            val model = prefs.getCustomModel()
            if (baseUrl.isBlank() || model.isBlank()) {
                return AiResult.Error(
                    "Falta la URL base o el modelo del proveedor Personalizado — configuralos en " +
                        "Proveedor de IA antes de preguntar."
                )
            }
            return ask(provider, prefs.getApiKey(provider), prompt, customUrl = baseUrl, customModel = model)
        }
        if (provider.requiresApiKey) {
            val apiKey = prefs.getApiKey(provider)
            if (apiKey.isBlank()) {
                return AiResult.Error(
                    "Falta la API key de ${providerDisplayName(provider)} — configurala en " +
                        "Proveedor de IA antes de preguntar."
                )
            }
            return ask(provider, apiKey, prompt)
        }
        return ask(provider, "", prompt)
    }

    /** [customUrl]/[customModel] solo aplican (y son obligatorios) para [AiCompany.CUSTOM] —
     *  ver [askWithPrefs]. */
    fun ask(
        provider: AiCompany,
        apiKey: String,
        prompt: String,
        customUrl: String? = null,
        customModel: String? = null
    ): AiResult {
        return try {
            if (provider == AiCompany.LOCAL) askLocal(prompt)
            else askCloud(provider, apiKey, prompt, customUrl, customModel)
        } catch (e: Exception) {
            AiResult.Error(e.message ?: e.toString())
        }
    }

    private fun providerDisplayName(provider: AiCompany) = provider.label

    // ── Proveedores cloud (BYO API key) ────────────────────────────────────────────────

    private data class CloudSpec(
        val url: String,
        val headers: Map<String, String>,
        val body: JSONObject,
        val contentPath: String
    )

    /**
     * Spec de request por proveedor — URL y modelo por defecto vienen de `AiProviders`
     * (unificado con el Agente Kairos, 2026-10-31, ver KDoc de `AiCompany`). Anthropic queda
     * con su formato nativo propio (`/v1/messages`, headers `x-api-key`/`anthropic-version`);
     * el resto (OpenAI/Google/OpenRouter/Ollama Cloud/DeepSeek/Grok) son compatibles-OpenAI —
     * mismo `openAiBody()` + header `Authorization: Bearer`. Google antes usaba acá el formato
     * NATIVO de Gemini (`generateContent`) — se unificó al endpoint compatible-OpenAI real que
     * ya usa `AiProviders.chatUrl()` para que ambas pantallas (IDE y Agente) hablen el mismo
     * formato con Google.
     */
    private fun cloudRequestSpec(
        provider: AiCompany,
        apiKey: String,
        prompt: String,
        customUrl: String?,
        customModel: String?
    ): CloudSpec {
        if (provider == AiCompany.CUSTOM) {
            // Mismo formato OpenAI-compatible que el resto (openAiBody + Authorization: Bearer) —
            // la única diferencia real es que la URL/modelo vienen del usuario, no de AiProviders.
            return CloudSpec(
                url = AiProviders.customCompletionsUrl(customUrl.orEmpty()),
                headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"),
                body = openAiBody(customModel.orEmpty(), prompt),
                contentPath = "choices[0].message.content"
            )
        }
        if (provider == AiCompany.ANTHROPIC) {
            return CloudSpec(
                url = AiProviders.chatUrl(provider),
                headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                body = JSONObject().apply {
                    put("model", AiProviders.fallbackModel(provider))
                    put("max_tokens", 1024)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                },
                contentPath = "content[0].text"
            )
        }
        return CloudSpec(
            url = AiProviders.chatUrl(provider),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = openAiBody(AiProviders.fallbackModel(provider), prompt),
            contentPath = "choices[0].message.content"
        )
    }

    /** Body OpenAI-compatible (deepseek/openai/grok comparten /chat/completions). */
    private fun openAiBody(model: String, prompt: String): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
    }

    private fun askCloud(
        provider: AiCompany,
        apiKey: String,
        prompt: String,
        customUrl: String? = null,
        customModel: String? = null
    ): AiResult {
        val spec = cloudRequestSpec(provider, apiKey, prompt, customUrl, customModel)
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            for ((key, value) in spec.headers) conn.setRequestProperty(key, value)
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(spec.body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val errorBody = readErrorBody(conn)
                return AiResult.Error(
                    "${providerDisplayName(provider)} respondió HTTP $code" +
                        (errorBody?.let { " — ${it.take(300)}" } ?: "")
                )
            }
            val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = extractJsonPath(JSONObject(respBody), spec.contentPath)
            if (text.isNullOrBlank()) {
                AiResult.Error("Respuesta vacía de ${providerDisplayName(provider)}")
            } else {
                AiResult.Success(text)
            }
        } catch (e: Exception) {
            AiResult.Error("Error de conexión con ${providerDisplayName(provider)}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Navega un JSONObject con un mini-path style "a[0].b.c" — mismo mecanismo que
     *  ChatFragment.extractCloudText() en Kairos. */
    private fun extractJsonPath(json: JSONObject, path: String): String? {
        val tokens = path.split(".").toMutableList()
        var node: Any = json
        while (tokens.isNotEmpty()) {
            val tok = tokens.removeAt(0)
            val idxMatch = Regex("^(\\w+)\\[(\\d+)\\]$").matchEntire(tok)
            if (idxMatch != null) {
                val field = idxMatch.groupValues[1]
                val idx = idxMatch.groupValues[2].toInt()
                val arr = (node as? JSONObject)?.optJSONArray(field) ?: return null
                if (idx >= arr.length()) return null
                node = arr.get(idx)
            } else {
                node = (node as? JSONObject)?.opt(tok) ?: return null
            }
        }
        return node as? String
    }

    private fun readErrorBody(conn: HttpURLConnection): String? = try {
        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    } catch (_: Exception) {
        null
    }

    // ── Local (Ollama / llama-server, mismos puertos que ya expone Kairos) ────────────

    /**
     * Intenta llama-server primero (127.0.0.1:8085, /v1/chat/completions OpenAI-compatible —
     * el campo "model" se ignora, sirve un solo modelo cargado, ver LLAMASERVER_MODEL en
     * ChatFragment.kt) y si no hay nada escuchando ahí cae a Ollama (127.0.0.1:11434,
     * /api/chat — requiere un modelo real ya descargado, se usa el primero que aparezca en
     * /api/tags). ide-app no tiene selector de modelo propio esta ronda — a diferencia de
     * ChatFragment, que deja elegir motor y modelo antes de entrar al chat.
     */
    private fun askLocal(prompt: String): AiResult {
        askLlamaServer(prompt)?.let { return it }
        return askOllama(prompt) ?: AiResult.Error(
            "No se pudo conectar ni a llama-server (127.0.0.1:8085) ni a Ollama (127.0.0.1:11434). " +
                "Iniciá alguno de los dos módulos desde Kairos antes de usar la IA local."
        )
    }

    /** null = no hay nada escuchando en el puerto (para intentar el siguiente backend) —
     *  distinto de un AiResult.Error, que sí es un fallo real ya conectado al servidor. */
    private fun askLlamaServer(prompt: String): AiResult? {
        val body = JSONObject().apply {
            put("model", LocalAiEndpoints.LLAMASERVER_MODEL)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
        }
        return try {
            val conn = URL("${LocalAiEndpoints.LLAMASERVER_URL}/v1/chat/completions").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = LOCAL_PROBE_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != 200) {
                    val errorBody = readErrorBody(conn)
                    return AiResult.Error("llama-server respondió HTTP $code" + (errorBody?.let { " — ${it.take(300)}" } ?: ""))
                }
                val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val text = extractJsonPath(JSONObject(respBody), "choices[0].message.content")
                if (text.isNullOrBlank()) AiResult.Error("Respuesta vacía de llama-server") else AiResult.Success(text)
            } finally {
                conn.disconnect()
            }
        } catch (_: ConnectException) {
            null
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun askOllama(prompt: String): AiResult? {
        return try {
            val model = firstOllamaModel() ?: return AiResult.Error(
                "Ollama está corriendo pero no tiene ningún modelo descargado — bajá uno desde " +
                    "Kairos (tab Módulos → Ollama)."
            )
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }
            val conn = URL("${LocalAiEndpoints.OLLAMA_URL}/api/chat").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = LOCAL_PROBE_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != 200) {
                    val errorBody = readErrorBody(conn)
                    return AiResult.Error("Ollama respondió HTTP $code" + (errorBody?.let { " — ${it.take(300)}" } ?: ""))
                }
                val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                // /api/chat con stream=false devuelve un único JSON con "message":{"content":...}
                // (misma forma que cada línea NDJSON de la versión streaming en ChatFragment).
                val text = JSONObject(respBody).optJSONObject("message")?.optString("content")
                if (text.isNullOrBlank()) AiResult.Error("Respuesta vacía de Ollama") else AiResult.Success(text)
            } finally {
                conn.disconnect()
            }
        } catch (_: ConnectException) {
            null
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** Primer modelo ya descargado en Ollama (/api/tags) — ide-app no tiene selector de
     *  modelo propio esta ronda, ver askLocal(). */
    private fun firstOllamaModel(): String? {
        return try {
            val conn = URL("${LocalAiEndpoints.OLLAMA_URL}/api/tags").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = LOCAL_PROBE_TIMEOUT_MS
                conn.readTimeout = LOCAL_PROBE_TIMEOUT_MS
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val models = json.optJSONArray("models") ?: return null
                if (models.length() == 0) return null
                models.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
