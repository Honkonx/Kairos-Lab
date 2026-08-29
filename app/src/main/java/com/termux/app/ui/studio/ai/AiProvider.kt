package com.termux.app.ui.studio.ai

import androidx.annotation.StringRes
import com.termux.R

/**
 * Proveedores de IA para el chat/asistente del IDE (BYO API key, cada usuario pega la suya).
 * Mismos 5 proveedores cloud que ya usa Kairos en ChatFragment.kt (CLOUD_PROVIDERS: gemini,
 * deepseek, openai, anthropic, grok) — patrón confirmado también en la referencia
 * android-code-studio-dev (core/app/.../artificial/secrets/ApiKey.kt, GPL-3.0, solo el patrón
 * de nombres de proveedor/preferencia se reutiliza, no su código) — más una opción adicional
 * "local" propia de este IDE, sin key, que asume Ollama/llama-server corriendo en el mismo
 * dispositivo (mismos puertos que expone Kairos).
 */
enum class AiProvider(val id: String, @StringRes val labelRes: Int, val requiresApiKey: Boolean) {
    GEMINI("gemini", R.string.ai_provider_gemini, true),
    DEEPSEEK("deepseek", R.string.ai_provider_deepseek, true),
    OPENAI("openai", R.string.ai_provider_openai, true),
    ANTHROPIC("anthropic", R.string.ai_provider_anthropic, true),
    GROK("grok", R.string.ai_provider_grok, true),
    LOCAL("local", R.string.ai_provider_local, false);

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Puertos reales que ya expone Kairos para IA local — ver ChatFragment.kt (OLLAMA_URL, LLAMASERVER_URL). */
object LocalAiEndpoints {
    const val OLLAMA_URL = "http://127.0.0.1:11434"
    const val LLAMASERVER_URL = "http://127.0.0.1:8085"
    // El campo "model" del request OpenAI-compatible de llama-server se ignora — sirve un
    // solo modelo cargado en memoria (ver LLAMASERVER_MODEL en ChatFragment.kt de Kairos).
    const val LLAMASERVER_MODEL = "local"
}
