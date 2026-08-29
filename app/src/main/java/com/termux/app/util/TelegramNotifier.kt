package com.termux.app.util

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Notificaciones remotas por Telegram — patrón `walkie-tg` (referencia/ciberseguridad/
 * i-Haklab-master, ver docs/mini-pc/PLAN_EXPANSION_HOMELAB_2026-08-13.md sección 4):
 * HTTP directo a `api.telegram.org/bot<token>/sendMessage`, sin SDK de terceros, sin webhook,
 * sin Firebase Cloud Messaging.
 *
 * Bloqueante, sin threading propio — mismo criterio que OllamaApiClient (ver ese archivo):
 * quien llama es responsable de correrlo fuera del hilo principal. ModuleEventBridge.notify()
 * ya corre en el hilo del FIFO (no el UI thread); ConfigFragment lo corre en un Thread nuevo
 * desde el botón "Probar". El timeout corto (10s) existe para acotar cuánto puede colgar
 * cualquiera de esos dos hilos si Telegram no responde.
 */
object TelegramNotifier {

    const val PREF_BOT_TOKEN = "pref_telegram_bot_token"
    const val PREF_CHAT_ID = "pref_telegram_chat_id"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    data class Result(val ok: Boolean, val error: String? = null)

    /** Firma simple para llamadas que solo necesitan saber si funcionó (ModuleEventBridge). */
    fun sendMessage(botToken: String, chatId: String, text: String): Boolean =
        send(botToken, chatId, text).ok

    /** Variante con detalle de error — la usa ConfigFragment para mostrarlo en el Toast del botón "Probar". */
    fun sendMessageDetailed(botToken: String, chatId: String, text: String): Result =
        send(botToken, chatId, text)

    private fun send(botToken: String, chatId: String, text: String): Result {
        if (botToken.isBlank() || chatId.isBlank()) return Result(false, "Token o chat id vacío")
        val conn = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            // El texto de error de Telegram nunca incluye el token (va en la URL, no en el
            // body de respuesta) — seguro para mostrar en un Toast tal cual.
            if (code !in 200..299) Result(false, extractTelegramError(responseText) ?: "HTTP $code")
            else Result(true)
        } catch (e: IOException) {
            Result(false, e.message ?: "Error de red")
        } catch (e: Exception) {
            Result(false, e.message ?: "Error desconocido")
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTelegramError(body: String): String? =
        try { JSONObject(body).optString("description").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
}
