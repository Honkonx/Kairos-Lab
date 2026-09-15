package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termux.R
import com.termux.app.util.KairosLogger
import com.termux.app.util.TelegramNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bot de Telegram ENTRANTE — fase 2 de las notificaciones de Telegram (ver TelegramNotifier.kt,
 * que solo cubre el envío saliente, y ModuleEventBridge.kt, que lo consume). Investigación de
 * esta sesión (docs/referencias/agentes/REFERENCIA_PRIVATE_AGENT.md, sección 1 y 4) confirmó el
 * patrón correcto: long-polling (`getUpdates`), nunca webhook (Kairos no tiene un endpoint HTTPS
 * público fijo al que Telegram pueda pegarle), whitelist fail-closed por `from.id` (no `chat.id`
 * — un grupo podría tener el mismo chat.id para varios usuarios distintos), confirmación en 2
 * pasos (teclado inline Sí/No) para el único comando "riesgoso" de esta primera tanda (`/stop`).
 *
 * Mismo molde de `Service` que X11Service.kt (foreground, notificación persistente, start()/
 * stop() estáticos) — a diferencia de X11Service, corre en el proceso PRINCIPAL de la app (sin
 * `android:process` propio), porque necesita llamar directo a ModuleController (mismo proceso,
 * sin IPC) para arrancar/parar módulos y consultar su estado.
 *
 * Alcance deliberado de esta v1 (ver docs/modulos/TELEGRAM_BOT.md "Fuera de alcance"): el
 * servicio NO sobrevive a que Android mate el proceso completo de la app (no hay
 * BOOT_COMPLETED receiver ni START_STICKY explícito más allá del default de Service) — si el
 * usuario lo activó y el proceso muere, hay que volver a activar el switch en Ajustes. Se
 * documenta como gap conocido, no como omisión silenciosa.
 */
class TelegramBotService : Service() {

    companion object {
        private const val CHANNEL_ID = "telegram_bot"
        private const val NOTIFICATION_ID = 7894
        private const val PREFS_NAME = "kairos_prefs"

        /** Intención persistida del usuario ("el bot debe estar prendido") — la lee ConfigFragment para el estado inicial del switch. */
        const val PREF_BOT_ENABLED = "pref_telegram_bot_enabled"
        private const val PREF_UPDATE_OFFSET = "pref_telegram_bot_update_offset"

        // Flag de proceso, no de preferencia — refleja si el Service.onCreate() de ESTE proceso
        // vivo llegó a arrancar el loop de verdad (a diferencia de PREF_BOT_ENABLED, que es la
        // intención guardada del usuario y puede quedar "true" aunque el proceso haya muerto).
        @Volatile
        private var running = false

        @JvmStatic
        fun isRunning(): Boolean = running

        /**
         * Whitelist fail-closed (ver docs/referencias/agentes/REFERENCIA_PRIVATE_AGENT.md
         * sección 1: "whitelist vacía → permite todos los mensajes" es exactamente el bug que
         * ese proyecto tuvo y corrigió — acá se evita de raíz no arrancando NADA sin token+chat
         * id configurados, nunca "arrancar y confiar en que el chequeo interno alcance").
         */
        @JvmStatic
        fun isConfigured(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(TelegramNotifier.PREF_BOT_TOKEN, null)
            val chatId = prefs.getString(TelegramNotifier.PREF_CHAT_ID, null)
            return !token.isNullOrBlank() && !chatId.isNullOrBlank()
        }

        /** No manda ni siquiera el Intent si falta config — devuelve false para que el caller (ConfigFragment) avise al usuario. */
        @JvmStatic
        fun start(context: Context): Boolean {
            if (!isConfigured(context)) return false
            val intent = Intent(context, TelegramBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramBotService::class.java))
        }
    }

    @Volatile private var alive = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    // Guard anti doble-tap de los botones inline Sí/No (callback_query.id) — acotado, no crece
    // sin límite durante una sesión larga del servicio (patrón simple: set + cola FIFO paralela
    // para desalojar el más viejo cuando se supera MAX_PROCESSED_CALLBACKS).
    private val processedCallbackIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val processedCallbackOrder = ConcurrentLinkedQueue<String>()
    private val maxProcessedCallbacks = 200

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Segunda capa fail-closed — la primera es start() (arriba), que ni manda el Intent sin
        // config. Esta cubre el caso de que algo arranque el Service directo (ej. el propio
        // Android re-lanzándolo tras matarlo, con intent null) sin pasar por start().
        if (!isConfigured(this)) {
            KairosLogger.log(this, "TelegramBot", "onCreate() sin token/chat_id configurado — deteniendo (fail-closed)")
            stopSelf()
            return
        }

        alive = true
        running = true
        Thread({ runLoop() }, "telegram-bot-poll").apply {
            isDaemon = true
            start()
        }
        KairosLogger.log(this, "TelegramBot", "Servicio iniciado")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        running = false
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
            // best-effort — solo queremos desbloquear el hilo de polling si estaba esperando.
        }
        super.onDestroy()
        KairosLogger.log(this, "TelegramBot", "Servicio detenido")
    }

    // ────────────────────────────────────────────────────────────
    // Notificación foreground — mismo patrón que X11Service.kt
    // ────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.telegram_bot_channel_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val intent = Intent(this, TermuxActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.telegram_bot_notification_title))
            .setContentText(getString(R.string.telegram_bot_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ────────────────────────────────────────────────────────────
    // Long-polling loop
    // ────────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun token(): String? = prefs().getString(TelegramNotifier.PREF_BOT_TOKEN, null)
    private fun configuredChatId(): String? = prefs().getString(TelegramNotifier.PREF_CHAT_ID, null)
    private fun loadOffset(): Long = prefs().getLong(PREF_UPDATE_OFFSET, 0L)
    private fun saveOffset(offset: Long) {
        prefs().edit().putLong(PREF_UPDATE_OFFSET, offset).apply()
    }

    private fun runLoop() {
        var backoffMs = 2000L
        var offset = loadOffset()
        while (alive) {
            val botToken = token()
            val chatId = configuredChatId()
            if (botToken.isNullOrBlank() || chatId.isNullOrBlank()) {
                // Config vaciada en caliente (el usuario borró el token/chat id sin apagar el
                // switch) — fail-closed: cortar en vez de seguir corriendo sin whitelist real.
                KairosLogger.log(this, "TelegramBot", "Config vaciada en caliente — deteniendo servicio")
                stopSelf()
                return
            }
            try {
                val updates = getUpdates(botToken, offset)
                backoffMs = 2000L
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    offset = update.optLong("update_id") + 1
                    saveOffset(offset)
                    try {
                        processUpdate(update, botToken, chatId)
                    } catch (e: Exception) {
                        KairosLogger.log(this, "TelegramBot", "Error procesando update: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!alive) return
                // Reconexión con backoff exponencial (2s → 4s → ... → tope 60s) — evita martillar
                // la API de Telegram si la red está caída o Telegram responde error por un rato.
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return
                }
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun getUpdates(botToken: String, offset: Long): JSONArray {
        val timeoutSec = 25
        val body = JSONObject().apply {
            put("offset", offset)
            put("timeout", timeoutSec)
            put("allowed_updates", JSONArray(listOf("message", "callback_query")))
        }
        val conn = URL("https://api.telegram.org/bot$botToken/getUpdates").openConnection() as HttpURLConnection
        activeConnection = conn
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            // El servidor de Telegram mantiene la conexión abierta hasta timeoutSec esperando
            // updates nuevos — el readTimeout del cliente tiene que ser mayor, con margen.
            conn.readTimeout = (timeoutSec + 15) * 1000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw IOException("HTTP $code: $text")
            return JSONObject(text).optJSONArray("result") ?: JSONArray()
        } finally {
            conn.disconnect()
            activeConnection = null
        }
    }

    private fun processUpdate(update: JSONObject, botToken: String, whitelistedChatId: String) {
        update.optJSONObject("message")?.let {
            handleMessage(it, botToken, whitelistedChatId)
            return
        }
        update.optJSONObject("callback_query")?.let {
            handleCallbackQuery(it, botToken, whitelistedChatId)
        }
    }

    /**
     * Whitelist por `from.id` (quien mandó el mensaje/tocó el botón) — deliberadamente NO por
     * `chat.id` (ver KDoc de la clase y la investigación de private-agent citada ahí). El chat
     * id configurado en Ajustes cumple doble función: destino de los mensajes salientes Y único
     * emisor autorizado a mandar comandos entrantes (chat privado 1:1 con el dueño del bot).
     */
    private fun isAuthorized(fromId: Long, whitelistedChatId: String): Boolean {
        val allowed = whitelistedChatId.trim().toLongOrNull() ?: return false
        return fromId == allowed
    }

    // ────────────────────────────────────────────────────────────
    // Comandos de texto
    // ────────────────────────────────────────────────────────────

    private fun handleMessage(message: JSONObject, botToken: String, whitelistedChatId: String) {
        val from = message.optJSONObject("from") ?: return
        if (!isAuthorized(from.optLong("id", -1L), whitelistedChatId)) {
            KairosLogger.log(this, "TelegramBot", "Mensaje rechazado — from.id no está en la whitelist")
            return
        }
        val chatId = message.optJSONObject("chat")?.optLong("id") ?: return
        val text = message.optString("text").trim()
        if (!text.startsWith("/")) return
        val parts = text.split(Regex("\\s+"), limit = 2)
        // Soporta "/comando@NombreDelBot arg" (formato que Telegram usa en chats grupales) además
        // del "/comando arg" simple de un chat 1:1.
        val cmd = parts[0].removePrefix("/").substringBefore("@").lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        when (cmd) {
            "start" -> if (arg.isBlank()) sendHelp(botToken, chatId) else handleStartCommand(botToken, chatId, arg)
            "stop" -> handleStopCommand(botToken, chatId, arg)
            "status" -> sendModulesStatus(botToken, chatId)
            "ssh" -> sendSshStatus(botToken, chatId)
            "help" -> sendHelp(botToken, chatId)
            else -> sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_unknown_command))
        }
    }

    private fun handleStartCommand(botToken: String, chatId: Long, moduleId: String) {
        sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_starting, moduleId))
        com.termux.app.ModuleController.startModule(moduleId, applicationContext) { ok, output ->
            val msg = if (ok) {
                getString(R.string.telegram_bot_start_ok, moduleId)
            } else {
                getString(R.string.telegram_bot_start_fail, moduleId, output.take(300))
            }
            sendPlainMessage(botToken, chatId, msg)
        }
    }

    // /stop NO se ejecuta directo — pide confirmación con teclado inline Sí/No (comando más
    // "riesgoso" de esta v1: parar algo en uso). Ver handleCallbackQuery() para el 2do paso.
    private fun handleStopCommand(botToken: String, chatId: Long, moduleId: String) {
        if (moduleId.isBlank()) {
            sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_stop_usage))
            return
        }
        if (!com.termux.app.ModuleController.isRunning(moduleId)) {
            sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_not_running, moduleId))
            return
        }
        val nonce = System.currentTimeMillis().toString(36)
        val keyboard = JSONObject().put(
            "inline_keyboard",
            JSONArray().put(
                JSONArray()
                    .put(JSONObject().put("text", "✅ Sí").put("callback_data", "stopyes:$moduleId:$nonce"))
                    .put(JSONObject().put("text", "❌ No").put("callback_data", "stopno:$moduleId:$nonce"))
            )
        )
        sendMessageWithKeyboard(botToken, chatId, getString(R.string.telegram_bot_confirm_stop, moduleId), keyboard)
    }

    private fun sendModulesStatus(botToken: String, chatId: Long) {
        Thread {
            val lines = StringBuilder()
            try {
                val json = assets.open("modules.json").bufferedReader().use { it.readText() }
                val modules = JSONArray(json)
                var any = false
                for (i in 0 until modules.length()) {
                    val m = modules.getJSONObject(i)
                    if (!m.optBoolean("hasSwitch", false)) continue
                    val id = m.optString("id")
                    if (!com.termux.app.ModuleController.isRunning(id)) continue
                    any = true
                    lines.append("🟢 ").append(m.optString("name", id)).append('\n')
                }
                if (!any) lines.append(getString(R.string.telegram_bot_status_none))
            } catch (e: Exception) {
                lines.append(getString(R.string.telegram_bot_status_error, e.message ?: ""))
            }
            sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_status_header) + "\n" + lines.toString().trim())
        }.start()
    }

    private fun sendSshStatus(botToken: String, chatId: Long) {
        Thread {
            val msg = if (com.termux.app.ModuleController.isRunning("remote")) {
                getString(R.string.telegram_bot_ssh_running, 8022)
            } else {
                getString(R.string.telegram_bot_ssh_stopped)
            }
            sendPlainMessage(botToken, chatId, msg)
        }.start()
    }

    private fun sendHelp(botToken: String, chatId: Long) {
        sendPlainMessage(botToken, chatId, getString(R.string.telegram_bot_help))
    }

    // ────────────────────────────────────────────────────────────
    // Botones inline (confirmación en 2 pasos)
    // ────────────────────────────────────────────────────────────

    private fun handleCallbackQuery(cq: JSONObject, botToken: String, whitelistedChatId: String) {
        val callbackId = cq.optString("id")
        if (callbackId.isBlank()) return
        // Doble-tap: el mismo callback_query_id solo se procesa una vez, sin importar cuántas
        // veces Telegram reenvíe el toque (o cuántas veces el usuario lo toque de verdad antes
        // de que el teclado desaparezca del lado del cliente).
        if (!markCallbackProcessed(callbackId)) {
            answerCallbackQuery(botToken, callbackId, getString(R.string.telegram_bot_already_processed))
            return
        }
        val from = cq.optJSONObject("from")
        if (!isAuthorized(from?.optLong("id", -1L) ?: -1L, whitelistedChatId)) {
            answerCallbackQuery(botToken, callbackId, null)
            return
        }
        answerCallbackQuery(botToken, callbackId, null)

        val message = cq.optJSONObject("message")
        val chatId = message?.optJSONObject("chat")?.optLong("id")
        val messageId = if (message?.has("message_id") == true) message.optInt("message_id") else null
        val parts = cq.optString("data").split(":")
        val action = parts.getOrNull(0)
        val moduleId = parts.getOrNull(1)
        if (chatId == null || messageId == null || moduleId.isNullOrBlank()) return

        when (action) {
            "stopyes" -> com.termux.app.ModuleController.stopModule(moduleId, applicationContext) { ok ->
                editMessage(
                    botToken, chatId, messageId,
                    if (ok) getString(R.string.telegram_bot_stop_ok, moduleId) else getString(R.string.telegram_bot_stop_fail, moduleId)
                )
            }
            "stopno" -> editMessage(botToken, chatId, messageId, getString(R.string.telegram_bot_stop_cancelled, moduleId))
        }
    }

    private fun markCallbackProcessed(id: String): Boolean {
        synchronized(processedCallbackIds) {
            if (!processedCallbackIds.add(id)) return false
            processedCallbackOrder.add(id)
            while (processedCallbackOrder.size > maxProcessedCallbacks) {
                processedCallbackOrder.poll()?.let { processedCallbackIds.remove(it) }
            }
            return true
        }
    }

    // ────────────────────────────────────────────────────────────
    // HTTP saliente (sendMessage/editMessageText/answerCallbackQuery) — JSON directo, sin SDK,
    // mismo criterio que TelegramNotifier.kt (que solo cubre sendMessage sin teclado, form-
    // urlencoded). Acá hace falta JSON porque reply_markup es un objeto anidado.
    // ────────────────────────────────────────────────────────────

    private fun sendPlainMessage(botToken: String, chatId: Long, text: String) {
        postJson(botToken, "sendMessage", JSONObject().put("chat_id", chatId).put("text", text))
    }

    private fun sendMessageWithKeyboard(botToken: String, chatId: Long, text: String, keyboard: JSONObject) {
        postJson(
            botToken, "sendMessage",
            JSONObject().put("chat_id", chatId).put("text", text).put("reply_markup", keyboard)
        )
    }

    private fun editMessage(botToken: String, chatId: Long, messageId: Int, text: String) {
        postJson(
            botToken, "editMessageText",
            JSONObject()
                .put("chat_id", chatId)
                .put("message_id", messageId)
                .put("text", text)
                // Vacía el teclado inline tras resolver la confirmación — evita que el usuario
                // pueda volver a tocar Sí/No sobre un mensaje ya procesado (más allá del guard
                // de markCallbackProcessed(), que protege el mismo toque duplicado, no un
                // segundo toque distinto sobre los mismos botones).
                .put("reply_markup", JSONObject().put("inline_keyboard", JSONArray()))
        )
    }

    private fun answerCallbackQuery(botToken: String, callbackQueryId: String, text: String?) {
        postJson(
            botToken, "answerCallbackQuery",
            JSONObject().put("callback_query_id", callbackQueryId).apply {
                if (!text.isNullOrBlank()) put("text", text)
            }
        )
    }

    /** Bloqueante, timeout corto — se llama desde el hilo de polling o un Thread propio, nunca desde el UI thread. */
    private fun postJson(botToken: String, method: String, body: JSONObject) {
        try {
            val conn = URL("https://api.telegram.org/bot$botToken/$method").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode // fuerza el request — no nos interesa leer el body de respuesta acá.
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            KairosLogger.log(this, "TelegramBot", "postJson($method) falló: ${e.message}")
        }
    }
}
