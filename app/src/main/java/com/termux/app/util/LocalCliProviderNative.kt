package com.termux.app.util

import org.json.JSONObject
import java.io.File

/**
 * "Usar Ollama/llama-server local" para los 3 CLIs de i-Haklab (freebuff/codebuff/mimocode/
 * mistralvibe/minimaxcli/copilotcli/qwencode) que SÍ confirman soporte real de endpoint
 * OpenAI-compatible custom — investigado contra cada repo real, no asumido (ver
 *   - qwencode: soporta OPENAI_BASE_URL/OPENAI_API_KEY/OPENAI_MODEL vía .qwen/.env
 *     (QwenLM/qwen-code docs, "Any third-party provider or local model (Ollama / vLLM)")
 *   - mimocode: soporta un provider "custom" (@ai-sdk/openai-compatible) en
 *     mimocode.jsonc, mismo mecanismo que ya usa OpenCodeNative.ollamaConfig()
 * Los otros 4 (freebuff/codebuff/minimaxcli/copilotcli) están atados a su propio backend
 * cloud sin ningún campo de endpoint custom — no tienen función acá a propósito.
 */
object LocalCliProviderNative {

    private val home get() = ManagerNativeUtils.home

    // ── Qwen Code — ~/.qwen/.env (KEY=VALUE simple, mismo algoritmo que
    //    HermesNative/OllamaApiClient vía ManagerNativeUtils.upsertKeyValueLine) ──────────
    fun configureQwenCode(baseUrl: String, model: String): JSONObject {
        return try {
            val envFile = File(home, ".qwen/.env")
            envFile.parentFile?.mkdirs()
            ManagerNativeUtils.upsertKeyValueLine(envFile, "OPENAI_API_KEY", "local")
            ManagerNativeUtils.upsertKeyValueLine(envFile, "OPENAI_BASE_URL", baseUrl)
            ManagerNativeUtils.upsertKeyValueLine(envFile, "OPENAI_MODEL", model)
            JSONObject().put("ok", true).put("message", "Qwen Code: $model")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    // ── MiMo Code — ~/.config/mimocode/mimocode.jsonc (provider "custom", mismo esquema
    //    que OpenCodeNative.ollamaConfig()/llamaServerConfig() — provider npm
    //    @ai-sdk/openai-compatible + options.baseURL/apiKey) ─────────────────────────────
    fun configureMimoCode(baseUrl: String, model: String): JSONObject {
        return try {
            val configFile = File(home, ".config/mimocode/mimocode.jsonc")
            configFile.parentFile?.mkdirs()
            val provider = JSONObject()
                .put("name", "Custom")
                .put("npm", "@ai-sdk/openai-compatible")
                .put("only_configured_models", true)
                .put("models", JSONObject().put(model, JSONObject().put("name", model)))
                .put("options", JSONObject().put("baseURL", baseUrl).put("apiKey", "local"))
            val cfg = JSONObject()
                .put("model", "custom/$model")
                .put("provider", JSONObject().put("custom", provider))
            configFile.writeText(cfg.toString(2) + "\n")
            JSONObject().put("ok", true).put("message", "MiMo Code: $model")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }
}
