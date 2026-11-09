package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5: "Ollama
 * tiene para descargar modelos y así con cada módulo, cada uno tiene sus propias opciones") —
 * delega TODO en [OllamaApiClient] (la misma API REST de Ollama que ya usa `OllamaFragment`), sin
 * reimplementar nada. `pull` es bloqueante hasta 10 minutos (el mismo límite real de
 * `OllamaApiClient.pullModel`, que lee directo de `conn.readTimeout`) — no hay progreso en vivo
 * por esta vía todavía (el callback de progreso de `pullModel` se descarta, la llamada HTTP del
 * gateway solo puede devolver un resultado final); un `ollama.models` posterior confirma si el
 * modelo quedó descargado de verdad.
 */
interface GatewayOllamaOps {
    fun models(): JSONObject
    fun running(): JSONObject
    fun pull(name: String): JSONObject
    fun delete(name: String): JSONObject
    fun unload(name: String): JSONObject
}

class AppOllamaOps : GatewayOllamaOps {
    override fun models(): JSONObject = runCatching {
        JSONObject().put("ok", true).put("models", JSONArray(OllamaApiClient.listModels().map {
            JSONObject().put("name", it.name).put("size", it.sizeHuman).put("family", it.family)
        }))
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Ollama no responde") }

    override fun running(): JSONObject = runCatching {
        JSONObject().put("ok", true).put("models", JSONArray(OllamaApiClient.psModels().map {
            JSONObject().put("name", it.name).put("size_vram", it.sizeVramHuman).put("expires_at", it.expiresAt)
        }))
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Ollama no responde") }

    override fun pull(name: String): JSONObject = runCatching {
        OllamaApiClient.pullModel(name)
        JSONObject().put("ok", true).put("message", "modelo bajado: $name")
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "no se pudo bajar $name") }

    override fun delete(name: String): JSONObject = runCatching {
        OllamaApiClient.deleteModel(name)
        JSONObject().put("ok", true).put("message", "modelo borrado: $name")
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "no se pudo borrar $name") }

    override fun unload(name: String): JSONObject = runCatching {
        OllamaApiClient.unloadModel(name)
        JSONObject().put("ok", true).put("message", "modelo liberado de memoria: $name")
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "no se pudo liberar $name de memoria") }
}
