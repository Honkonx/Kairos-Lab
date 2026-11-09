package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Proveedores de IA para la ventana "Configurar IA externa" del Agente Kairos (2026-10-31,
 * + docs/arquitectura/PLAN_SHIZUKU_IA_HOMELAB_ADB_2026-10-31.md) — pedido
 * explícito del usuario: "siempre deben estar para escoger las empresas como anthropic, openai,
 * google, openrouter, ollama cloud y ollama/llama local, y siempre con los modelos".
 *
 * "Nunca vacío" (regla nueva, `.claude/rules/kairos-ia-selector-nunca-vacio.md`): el modelo nunca
 * se deja en blanco — [fetchModels] intenta traer el catálogo real y actual de cada proveedor en
 * vivo (evita hardcodear nombres que quedan viejos en semanas, confirmado real en esta misma
 * ronda: los catálogos de OpenAI/Google/OpenRouter cambian seguido); si la red falla o el token
 * todavía no es válido, [fallbackModel] da un valor razonable para que el selector NUNCA quede sin
 * nada elegido.
 *
 * **Unificado con `ui/studio/ai/AiProvider.kt` (el selector del IDE) — 2026-10-31**, pedido
 * explícito del usuario: antes eran 2 enums separados con proveedores parcialmente distintos
 * (acá faltaban DeepSeek/Grok, el del IDE no tenía OpenRouter/Ollama Cloud y usaba el formato
 * NATIVO de Gemini en vez del compatible-OpenAI). Ahora `AiCompany` es la única fuente de verdad
 * para ambas pantallas — el IDE (`AiClient.kt`/`AiProviderPrefs.kt`/`AiProviderSettingsActivity.kt`)
 * importa este enum en vez de tener el suyo propio.
 */
enum class AiCompany(val id: String, val label: String, val requiresApiKey: Boolean) {
    ANTHROPIC("anthropic", "Anthropic (Claude)", true),
    OPENAI("openai", "OpenAI", true),
    GOOGLE("google", "Google Gemini", true),
    OPENROUTER("openrouter", "OpenRouter", true),
    OLLAMA_CLOUD("ollama_cloud", "Ollama Cloud", true),
    DEEPSEEK("deepseek", "DeepSeek", true),
    GROK("grok", "Grok (xAI)", true),
    LOCAL("local", "Ollama / llama.cpp (local)", false),
    // "Personalizado" (hallazgo real de docs/referencias/ia/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md
    // -- ChatFragment.kt ya tenía esto como proveedor "custom" interno, separado de este enum
    // unificado; Agente/IDE no lo tenían). requiresApiKey=false porque un servidor OpenAI-
    // compatible propio puede no exigir token -- el campo de clave sigue disponible, solo no
    // es obligatorio. URL y modelo NO se resuelven acá (no hay un endpoint fijo que adivinar):
    // el caller los lee de dónde los haya guardado (AiProviderPrefs.getCustomBaseUrl()/
    // getCustomModel() en el IDE, o el equivalente del Agente) y arma la URL final con
    // [AiProviders.customCompletionsUrl].
    CUSTOM("custom", "Personalizado (OpenAI-compatible)", false);

    companion object {
        fun fromId(id: String?): AiCompany = entries.firstOrNull { it.id == id } ?: ANTHROPIC
    }
}

object AiProviders {

    /** URL de chat (formato OpenAI-compatible salvo Anthropic, que usa su propio /v1/messages). */
    fun chatUrl(company: AiCompany): String = when (company) {
        AiCompany.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
        AiCompany.OPENAI -> "https://api.openai.com/v1/chat/completions"
        // Endpoint OpenAI-compatible real de Gemini (ai.google.dev/gemini-api/docs/openai) — así
        // el motor del Agente (kairos_agent.sh, formato OpenAI genérico) no necesita un camino
        // aparte para Google.
        AiCompany.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        AiCompany.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        // URLs dadas por el usuario (2026-10-31): nativa ollama.com/api, compatible OpenAI
        // api.ollama.com/v1 — se usa la compatible OpenAI, mismo criterio que el resto.
        AiCompany.OLLAMA_CLOUD -> "https://api.ollama.com/v1/chat/completions"
        AiCompany.DEEPSEEK -> "https://api.deepseek.com/chat/completions"
        AiCompany.GROK -> "https://api.x.ai/v1/chat/completions"
        AiCompany.LOCAL -> "" // resuelto aparte (llama-server :8085 u Ollama :11434, ver LocalAiEndpoints)
        AiCompany.CUSTOM -> "" // resuelto aparte, ver customCompletionsUrl() -- no hay un host fijo
    }

    /** Arma la URL final de "/chat/completions" para [AiCompany.CUSTOM] a partir de la base que
     *  el usuario pegó (ej. "https://openrouter.ai/api/v1", sin el path final) -- mismo formato
     *  que espera cualquier endpoint OpenAI-compatible real. Promovido acá desde
     *  ChatFragment.customCompletionsUrl() (ya probado en producción, pedido real de un usuario
     *  vía Telegram) para que el Agente/IDE lo reusen en vez de reimplementarlo. */
    fun customCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    /** URL del catálogo real de modelos de cada proveedor, para [fetchModels]. `null` = sin vía de
     *  listado por API (hoy ninguno — Local se resuelve con [LocalAiEndpoints] en el caller). */
    private fun modelsUrl(company: AiCompany): String? = when (company) {
        AiCompany.ANTHROPIC -> "https://api.anthropic.com/v1/models"
        AiCompany.OPENAI -> "https://api.openai.com/v1/models"
        AiCompany.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/models"
        AiCompany.OPENROUTER -> "https://openrouter.ai/api/v1/models"
        AiCompany.OLLAMA_CLOUD -> "https://api.ollama.com/v1/models"
        AiCompany.DEEPSEEK -> "https://api.deepseek.com/models"
        AiCompany.GROK -> "https://api.x.ai/v1/models"
        AiCompany.LOCAL -> null
        AiCompany.CUSTOM -> null // no hay un catálogo genérico -- el usuario escribe el nombre del modelo a mano
    }

    /** Valor de respaldo si [fetchModels] no pudo traer nada (sin red, token inválido/vacío
     *  todavía) — nunca un selector vacío. OpenRouter/Ollama Cloud no tienen un "modelo típico"
     *  seguro sin consultarlos de verdad (catálogo muy variable, confirmado por investigación de
     *  esta ronda) — ahí el respaldo es un router/alias real en vez de adivinar un modelo puntual. */
    fun fallbackModel(company: AiCompany): String = when (company) {
        AiCompany.ANTHROPIC -> "claude-haiku-5-5"
        AiCompany.OPENAI -> "gpt-4o-mini"
        AiCompany.GOOGLE -> "gemini-2.5-flash"
        AiCompany.OPENROUTER -> "openrouter/auto"
        AiCompany.OLLAMA_CLOUD -> "llama3.1"
        AiCompany.DEEPSEEK -> "deepseek-chat"
        AiCompany.GROK -> "grok-2-latest"
        AiCompany.LOCAL -> ""
        AiCompany.CUSTOM -> "" // sin un modelo "típico" posible -- el usuario debe escribir el suyo
    }

    /**
     * Trae el catálogo real de modelos del proveedor (requiere [apiKey] salvo OpenRouter, que
     * expone su lista sin token). Corre HTTP síncrono — el caller debe llamarlo desde un hilo de
     * fondo (mismo patrón que el resto de la app, ver `.claude/rules/kotlin-kairos-android-
     * patterns.md`). Lista vacía = no se pudo traer nada real; el caller usa [fallbackModel].
     */
    fun fetchModels(company: AiCompany, apiKey: String): List<String> {
        val url = modelsUrl(company) ?: return emptyList()
        if (company.requiresApiKey && apiKey.isBlank() && company != AiCompany.OPENROUTER) return emptyList()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                when (company) {
                    AiCompany.ANTHROPIC -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    AiCompany.GOOGLE -> {} // la key va como query param, ver abajo
                    else -> if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val realUrl = if (company == AiCompany.GOOGLE) "$url?key=$apiKey" else url
            if (company == AiCompany.GOOGLE) {
                conn.disconnect()
                return fetchGoogleModels(realUrl)
            }
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseModelIds(company, body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchGoogleModels(urlWithKey: String): List<String> = try {
        val conn = (URL(urlWithKey).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
        }
        if (conn.responseCode !in 200..299) emptyList()
        else {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                // Nombres vienen como "models/gemini-2.5-flash" — se usa solo la parte real del id.
                arr.getJSONObject(i).optString("name").substringAfterLast('/').ifBlank { null }
            }.filter { it.contains("gemini") } // descarta embeddings/otros modelos no-chat del mismo listado
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** OpenAI/OpenRouter/Ollama Cloud/DeepSeek/Grok comparten el mismo formato
     *  `{"data": [{"id": "..."}]}` (todos explícitamente compatibles OpenAI, ver [chatUrl]). */
    private fun parseModelIds(company: AiCompany, body: String): List<String> = try {
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.getJSONObject(i).optString("id").ifBlank { null } }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Puertos reales que ya expone Kairos para IA local — movido acá desde
 *  `ui/studio/ai/AiProvider.kt` en la unificación de 2026-10-31 (ver KDoc de [AiCompany]). */
object LocalAiEndpoints {
    const val OLLAMA_URL = "http://127.0.0.1:11434"
    const val LLAMASERVER_URL = "http://127.0.0.1:8085"
    // El campo "model" del request OpenAI-compatible de llama-server se ignora — sirve un
    // solo modelo cargado en memoria (ver LLAMASERVER_MODEL en ChatFragment.kt de Kairos).
    const val LLAMASERVER_MODEL = "local"
}
