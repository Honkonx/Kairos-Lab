package com.termux.app.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * POST directo a ntfy (https://ntfy.sh o un servidor propio) desde el lado Android -- usado por
 * [AutomationManager.execute] para la acción "notify.send" SIN necesitar que el gateway Go esté
 * corriendo (a diferencia de restic.backup/db.backup, que son "inapp" y sí lo necesitan). Mismo
 * protocolo HTTP real que `native/gateway/internal/notify/ntfy.go` -- no hay forma de compartir
 * ese código Go con Kotlin, así que se repite acá a propósito (es un POST de ~15 líneas, no vale
 * la pena un puente nativo solo para esto).
 */
object NtfySender {

    private const val DEFAULT_SERVER = "https://ntfy.sh"

    /** [params] -- topic (requerido), message (requerido), title/server/token/priority (opcionales). */
    fun send(params: JSONObject): Pair<Boolean, String> {
        val topic = params.optString("topic").trim()
        if (topic.isBlank() || topic.any { it.isWhitespace() || it == '/' }) {
            return false to "Topic de ntfy inválido"
        }
        val server = params.optString("server").trim().ifBlank { DEFAULT_SERVER }.trimEnd('/')
        val message = params.optString("message")
        val title = params.optString("title")
        val token = params.optString("token")
        val priority = params.optString("priority").toIntOrNull()?.takeIf { it in 1..5 } ?: 3

        return try {
            val conn = (URL("$server/$topic").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                if (title.isNotBlank()) setRequestProperty("X-Title", title)
                setRequestProperty("X-Priority", priority.toString())
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) true to "Notificación enviada" else false to "ntfy respondió HTTP $code"
        } catch (e: Exception) {
            false to "No se pudo contactar a $server: ${e.message}"
        }
    }
}
