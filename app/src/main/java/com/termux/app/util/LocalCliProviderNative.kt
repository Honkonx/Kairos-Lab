package com.termux.app.util

import org.json.JSONObject
import java.io.File

/**
 * "Usar Ollama/llama-server local" para los 3 CLIs de i-Haklab (freebuff/codebuff/mimocode/
 * mistralvibe/minimaxcli/copilotcli/qwencode) que SÍ confirman soporte real de endpoint
 * OpenAI-compatible custom — investigado contra cada repo real, no asumido (ver
 * docs/humano/humano116.md):
 *   - qwencode: soporta OPENAI_BASE_URL/OPENAI_API_KEY/OPENAI_MODEL vía .qwen/.env
 *     (QwenLM/qwen-code docs, "Any third-party provider or local model (Ollama / vLLM)")
 *   - mimocode: soporta un provider "custom" (@ai-sdk/openai-compatible) en
 *     mimocode.jsonc, mismo mecanismo que ya usa OpenCodeNative.ollamaConfig()
 *   - mistralvibe: soporta [[providers]] con api_base/backend=generic en config.toml
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

    // ── Mistral Vibe — ~/.vibe/config.toml ([[providers]]/[[models]] + active_model,
    //    backend="generic"/api_style="openai" confirmado por la referencia de configuración
    //    del proyecto real) — sin librería TOML en el proyecto, así que el bloque propio se
    //    escribe/reemplaza entre marcadores para no pisar el resto del archivo (MCP servers,
    //    otros providers ya configurados a mano por el usuario) en reconfiguraciones futuras.
    private const val TOML_BLOCK_START = "# --- kairos-local-provider (generado por Kairos, no editar a mano) ---"
    private const val TOML_BLOCK_END = "# --- kairos-local-provider end ---"
    private const val TOML_PROVIDER_NAME = "kairos-local"

    fun configureMistralVibe(baseUrl: String, model: String): JSONObject {
        return try {
            val configFile = File(home, ".vibe/config.toml")
            configFile.parentFile?.mkdirs()
            val existing = if (configFile.exists()) configFile.readText() else ""
            val withoutOldBlock = removeTomlBlock(existing)
            val block = buildString {
                appendLine(TOML_BLOCK_START)
                appendLine("active_model = \"${tomlEscape(TOML_PROVIDER_NAME)}\"")
                appendLine()
                appendLine("[[providers]]")
                appendLine("name = \"${tomlEscape(TOML_PROVIDER_NAME)}\"")
                appendLine("api_base = \"${tomlEscape(baseUrl)}\"")
                appendLine("api_key_env_var = \"\"")
                appendLine("api_style = \"openai\"")
                appendLine("backend = \"generic\"")
                appendLine()
                appendLine("[[models]]")
                appendLine("name = \"${tomlEscape(model)}\"")
                appendLine("provider = \"${tomlEscape(TOML_PROVIDER_NAME)}\"")
                appendLine("alias = \"${tomlEscape(TOML_PROVIDER_NAME)}\"")
                appendLine(TOML_BLOCK_END)
            }
            // El bloque va SIEMPRE primero: "active_model" es una clave raíz — si quedara
            // después de un [[providers]]/[[models]] ya existente en el resto del archivo,
            // TOML la interpretaría como parte de esa última tabla en vez de la raíz.
            configFile.writeText(block + "\n" + withoutOldBlock.trimStart('\n'))
            JSONObject().put("ok", true).put("message", "Mistral Vibe: $model")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    private fun removeTomlBlock(content: String): String {
        val start = content.indexOf(TOML_BLOCK_START)
        if (start < 0) return content
        val endMarker = content.indexOf(TOML_BLOCK_END, start)
        if (endMarker < 0) return content
        val afterEnd = content.indexOf('\n', endMarker).let { if (it < 0) content.length else it + 1 }
        return content.substring(0, start) + content.substring(afterEnd)
    }

    private fun tomlEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
