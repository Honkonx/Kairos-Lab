package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Habla directo con la API REST pública de n8n (127.0.0.1:5678/api/v1) — mismo patrón HTTP
 * que OllamaApiClient (HttpURLConnection + org.json). n8n expone GET /workflows,
 * POST /workflows/{id}/activate y POST /workflows/{id}/deactivate desde hace varias
 * versiones (API Key generada en n8n → Settings → API). Antes Kairos solo usaba esta API
 * para el healthcheck de arranque (curl /healthz en start.sh) — el resto de la pantalla
 * (N8nFragment) nunca leía ni gestionaba workflows reales, solo abría el webview o la
 * terminal. Se agrega acá para poder listar/activar/desactivar workflows sin salir de la
 * app ni depender del webview completo.
 *
 * Todas las funciones son bloqueantes (sin threading propio) — el Fragment que llama es
 * responsable de correrlas en un Thread y volver al hilo principal para tocar la UI.
 */
object N8nApiClient {

    private const val BASE_URL = "http://127.0.0.1:5678/api/v1"

    data class WorkflowSummary(val id: String, val name: String, val active: Boolean, val updatedAt: String)

    private val apiKeyFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".n8n_api_key")

    fun hasApiKey(): Boolean = apiKeyFile.isFile && apiKeyFile.length() > 0

    fun readApiKey(): String? = if (apiKeyFile.isFile) apiKeyFile.readText().trim().ifBlank { null } else null

    fun writeApiKey(key: String) {
        if (key.isBlank()) {
            apiKeyFile.delete()
        } else {
            apiKeyFile.writeText(key.trim())
        }
    }

    @Throws(Exception::class)
    fun listWorkflows(): List<WorkflowSummary> {
        val json = httpJson("$BASE_URL/workflows?limit=100", "GET", null)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val w = data.getJSONObject(i)
            WorkflowSummary(
                id = w.optString("id"),
                name = w.optString("name", "(sin nombre)"),
                active = w.optBoolean("active", false),
                updatedAt = w.optString("updatedAt", "")
            )
        }
    }

    @Throws(Exception::class)
    fun setActive(id: String, active: Boolean) {
        val action = if (active) "activate" else "deactivate"
        httpJson("$BASE_URL/workflows/$id/$action", "POST", null)
    }

    data class ExecutionSummary(val id: String, val workflowId: String, val status: String, val startedAt: String)

    // Ronda 2026-08-25 (auditoría vs. docs.n8n.io/connect/n8n-api/execution): GET /executions
    // es real y estaba sin cubrir — antes solo se podía activar/desactivar workflows, sin
    // ninguna forma de ver si la última corrida falló sin abrir el webview completo. Campos
    // confirmados reales: id/workflowId/startedAt/status/finished — "status" a veces no viene
    // en el listado (bug conocido, ver github.com/n8n-io/n8n/issues/20706), por eso se cae a
    // "finished" como respaldo en vez de asumir que "status" siempre está.
    @Throws(Exception::class)
    fun listExecutions(limit: Int = 20): List<ExecutionSummary> {
        val json = httpJson("$BASE_URL/executions?limit=$limit", "GET", null)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val e = data.getJSONObject(i)
            val status = e.optString("status", "").ifBlank {
                if (e.optBoolean("finished", false)) "success" else "unknown"
            }
            ExecutionSummary(
                id = e.optString("id"),
                workflowId = e.optString("workflowId", ""),
                status = status,
                startedAt = e.optString("startedAt", "")
            )
        }
    }

    private fun httpJson(urlStr: String, method: String, body: JSONObject?): JSONObject {
        val apiKey = readApiKey() ?: throw IOException("Falta API Key de n8n — configurala en el módulo (Settings → API en n8n)")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("X-N8N-API-KEY", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${extractError(text) ?: text}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractError(body: String): String? =
        try { JSONObject(body).optString("message").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
}
