package com.termux.app.util

/**
 * Traduce excepciones crudas del motor nativo (LlamaEngine/JNI, ver
 * llama-engine/src/main/cpp/LLMInference.cpp) a un mensaje accionable en
 * español, con el texto técnico original siempre disponible aparte (nunca
 * se descarta) — patrón "friendly message + Show Technical Details" de
 * PrivateLM (cross-platform-llm-client, `_getFriendlyErrorMessage`), ver
 * docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md.
 *
 * Los patrones de acá NO son inventados: salen de grepear los `throw
 * std::runtime_error(...)` reales de LLMInference.cpp más los mensajes que
 * arma llama.cpp mismo (tag pineado en KAIROS_LLAMACPP_TAG, ver
 * llama-engine/build.gradle) en src/llama-model.cpp ("unsupported/unknown
 * model architecture", "unable to allocate ... buffer") y
 * src/llama-mmap.cpp ("mmap failed", "failed to open") — estos últimos le
 * llegan a Kotlin dentro del string de la excepción porque LLMInference.cpp
 * los captura vía `llama_log_set()`/`_lastErrorLog` y los concatena al
 * "loadModel() failed: ...".
 */
object LlmErrorMapper {

    data class FriendlyError(val message: String, val technicalDetail: String)

    @JvmStatic
    fun map(rawMessage: String?): FriendlyError {
        val raw = rawMessage?.takeIf { it.isNotBlank() } ?: "Error desconocido del motor local"
        val lower = raw.lowercase()
        val friendly = when {
            "context size reached" in lower ->
                "El contexto de esta conversación se llenó. Iniciá un chat nuevo o subí el tamaño de contexto en IA Local → Parámetros."

            "unsupported model architecture" in lower || "unknown model architecture" in lower ->
                "Este modelo usa una arquitectura que el motor local todavía no soporta. Probá otro modelo del catálogo de IA Local."

            "unable to allocate" in lower || "failed to allocate" in lower ||
                "posix_memalign failed" in lower || "out of memory" in lower ->
                "No hay memoria suficiente para cargar este modelo. Cerrá otras apps o probá una versión más chica/cuantizada (ej. Q4_K_M)."

            "mmap failed" in lower || "failed to open" in lower ->
                "No se pudo abrir el archivo del modelo — puede estar dañado, movido o sin permisos. Probá descargarlo de nuevo desde IA Local."

            "missing tensor" in lower || "corrupted model" in lower ||
                "failed to load model from" in lower || "invalid model" in lower ->
                "El archivo del modelo parece estar corrupto o incompleto. Borralo (en IA Local) y descargalo de nuevo."

            "llama_decode() failed" in lower ->
                "Falló la generación de la respuesta. Probá enviar el mensaje de nuevo."

            "modelo no cargado" in lower ->
                raw // ya es un mensaje propio de Kairos en español, no hace falta traducirlo

            else ->
                "Error del motor local — no se pudo completar la operación."
        }
        return FriendlyError(friendly, raw)
    }

    /**
     * Traduce una respuesta HTTP no-2xx de la API real de Ollama (127.0.0.1:11434) a un
     * mensaje accionable — reporte real del usuario (2026-07-31, ver docs/humano/humano33.md):
     * el chat mostraba "HTTP 404"/"HTTP 400" a secas sin explicar qué hacer. Causa raíz real
     * (no la única causa, pero la más común): el selector de modelos del chat ofrecía
     * nombres que el usuario nunca había hecho `ollama pull` — Ollama devuelve 404 con
     * `{"error": "model \"x\" not found, try pulling it first"}` en ese caso (a veces 400
     * según la versión/endpoint). [[serverError]] es el campo "error" real que Ollama manda
     * en el cuerpo de la respuesta (ver ChatFragment.makeOllamaRequest) — nunca se descarta,
     * mismo criterio que [map] con el mensaje técnico del motor local.
     */
    @JvmStatic
    fun mapOllamaHttp(code: Int, serverError: String?, modelName: String): FriendlyError {
        val technical = "HTTP $code" + (serverError?.let { ": $it" } ?: "")
        val lowerErr = serverError?.lowercase() ?: ""
        val friendly = when {
            "not found" in lowerErr ->
                "El modelo \"$modelName\" no está descargado en Ollama todavía. Andá a Módulos → Ollama → Modelos → \"+ Descargar modelo\" para bajarlo, o elegí otro modelo ya descargado desde el selector."
            code == 404 ->
                "El modelo \"$modelName\" no está disponible en Ollama (HTTP 404). Andá a Módulos → Ollama → Modelos para descargarlo primero."
            code == 400 && serverError != null ->
                "Ollama rechazó el pedido: $serverError"
            code == 400 ->
                "Ollama rechazó el pedido (datos inválidos) — probá con otro modelo o iniciá un chat nuevo."
            code == 500 ->
                "Ollama tuvo un error interno procesando el pedido. Probá de nuevo o reiniciá el servicio de Ollama desde Módulos."
            else ->
                "Error de Ollama (HTTP $code)" + (serverError?.let { " — $it" } ?: "")
        }
        return FriendlyError(friendly, technical)
    }
}
