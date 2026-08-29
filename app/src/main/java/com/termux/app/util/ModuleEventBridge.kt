package com.termux.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.termux.app.TermuxActivity
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileInputStream

/**
 * Bridge módulo→app sin polling — patrón adoptado de Podroid (`podroid-hostd`, ver
 * docs/referencias/REFERENCIA_PODROID.md "Arquitectura" punto 2 y "Profundización 2026-08-01" punto 3).
 * A diferencia de Podroid (guest en una VM real, sin acceso directo al filesystem del host,
 * necesita un daemon C sobre virtio-console/vsock), los módulos proot de Kairos (n8n, Entorno)
 * NO están aislados del filesystem real — un script bash corriendo bajo proot puede escribir
 * directo a un archivo en `$HOME` (fuera del proot) sin ningún mecanismo especial. Esto reduce
 * el bridge a: un FIFO simple + un hilo Kotlin leyéndolo bloqueante.
 *
 * Formato de línea: "modulo:evento:detalle" (ver `notify_event()` en modulos/lib.sh, el lado
 * bash que escribe). Cada línea válida dispara una notificación Android real, reusando el
 * mismo canal/permiso que ya usa `TermuxActivity.notifySessionNeedsAttention()` (agregada en
 * la ronda de whispercode) — este bridge es un mecanismo nuevo y separado, no reemplaza ese.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────
 * CONTRATO DE EVENTOS DE SESIÓN (bash ↔ Kotlin) — 2026-08-15
 * ─────────────────────────────────────────────────────────────────────────────────────────
 * Los scripts bash AÚN no emiten estos eventos (solo install_done/vnc_closed hoy). Este es el
 * contrato que DEBEN seguir cuando los emitan, y el que Kotlin ya sabe parsear (ver
 * parseSessionEvent()). Se escriben al MISMO FIFO que notify_event() ($HOME/.kairos_events),
 * pero con un prefijo fijo "modulo" y el id de módulo como campo, no como prefijo:
 *
 *   Línea exacta a escribir (vía `echo '...' > $HOME/.kairos_events`, con timeout corto y
 *   `|| true`, igual que notify_event() en modulos/lib.sh — nunca bloquear un script real):
 *
 *     modulo:session_started:<module_id>
 *     modulo:session_idle:<module_id>:<detalle opcional>
 *     modulo:session_error:<module_id>:<detalle opcional>
 *     modulo:session_permission:<module_id>:<detalle opcional>
 *     modulo:session_stopped:<module_id>
 *
 *   Ejemplos reales:
 *     echo 'modulo:session_started:ollama'                      > $HOME/.kairos_events
 *     echo 'modulo:session_idle:n8n:sin requests 5 min'         > $HOME/.kairos_events
 *     echo 'modulo:session_error:db:puerto 3306 ocupado'        > $HOME/.kairos_events
 *     echo 'modulo:session_permission:remote:falta openssh'     > $HOME/.kairos_events
 *     echo 'modulo:session_stopped:llamaserver'                 > $HOME/.kairos_events
 *
 *   (El prefijo fijo "modulo" es deliberado: distingue estas líneas del formato histórico
 *   "<module_id>:<evento>:<detalle>" — ej. "ollama:install_done:..." — y evita que un id de
 *   módulo con ":" interno rompa el parseo. El id de módulo va como 3er campo.)
 *
 *   Mapeo a la notificación:
 *     session_started  → SessionEvent.STARTED   (siempre notifica, sin cooldown)
 *     session_stopped  → SessionEvent.STOPPED   (siempre notifica, sin cooldown)
 *     session_idle     → SessionEvent.IDLE      (cooldown 30 min)
 *     session_error    → SessionEvent.ERROR     (cooldown 5 min)
 *     session_permission → SessionEvent.PERMISSION (cooldown 15 min)
 *
 *   Cooldown / collapse: el id de notificación y la clave de cooldown se derivan de
 *   "$module_id:<tipo>" (p. ej. "n8n:session_idle") — dentro de la ventana, un mismo
 *   módulo+evento repite actualiza la MISMA notificación en vez de apilar una nueva.
 *   Equivalente Kotlin del collapse_id de FCM que ya usan los mensajes push locales.
 *
 *   Regla para scripts: emitir session_idle/error/permission SOLO cuando el módulo esté
 *   corriendo (o el evento ocurra sobre una sesión viva) y haya algo real que reportar —
 *   el cooldown de 5/15/30 min ya protege contra spam, pero una sesión que reporta idle
 *   cada minuto igual ensucia el registro. session_started/stopped se emiten una vez por
 *   transición real de estado, no en loops.
 * ─────────────────────────────────────────────────────────────────────────────────────────
 */
object ModuleEventBridge {

    private const val CHANNEL_ID = "kairos_module_events"
    private var started = false

    /** Crea el FIFO si hace falta y arranca el hilo lector — llamar UNA vez por proceso (ver TermuxApplication.onCreate()). Es a prueba de llamadas repetidas (no-op si ya está corriendo). */
    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        Thread({ runLoop(appContext) }, "ModuleEventBridge").apply {
            isDaemon = true
            start()
        }
    }

    private fun pipeFile(): File = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_events")

    /**
     * Bug real confirmado (ver docs/humano/humano63.md, auditoría de ProcessBuilder): usaba
     * `"mkfifo"` por nombre relativo y solo seteaba `PATH` a mano (sin HOME/PREFIX/
     * LD_LIBRARY_PATH/SHELL) — entorno incompleto, mismo patrón del bug original de julio.
     * Se llama UNA sola vez, al arrancar la app (`TermuxApplication.onCreate()`) — la
     * ventana de mayor riesgo de timing de todo el proyecto. Si fallaba, `runLoop()`
     * retornaba sin reintentar nunca y el bridge de eventos quedaba desactivado el resto de
     * la sesión, sin ningún aviso. Corregido: ruta absoluta + helper compartido, y un
     * reintento con pausa corta por si es una demora transitoria (mismo criterio que
     * `RootfsInstaller.installDebs()`).
     */
    private fun ensurePipe(): Boolean {
        val pipe = pipeFile()
        if (pipe.exists()) return true
        repeat(2) { attempt ->
            try {
                val pb = ProcessBuilder(TERMUX_MKFIFO_PATH, pipe.absolutePath)
                pb.applyTermuxEnv()
                if (pb.start().waitFor() == 0 && pipe.exists()) return true
            } catch (_: Exception) {
                // Sigue al reintento (o se rinde si ya fue el último).
            }
            if (attempt == 0) Thread.sleep(1500)
        }
        return false
    }

    /**
     * Loop de por vida del proceso: abre el FIFO en lectura (bloquea hasta que algo lo abra en
     * escritura del otro lado — comportamiento normal y esperado de un FIFO, no un bug), lee
     * líneas, despacha, y REABRE en cuanto el lado de escritura cierra (EOF) — un solo
     * `echo > pipe` desde bash cierra su extremo apenas termina, así que sin este loop el
     * bridge solo recibiría el primer evento de toda la vida de la app.
     */
    private fun runLoop(context: Context) {
        if (!ensurePipe()) return
        val pipe = pipeFile()
        while (true) {
            try {
                FileInputStream(pipe).use { input ->
                    input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        handleLine(context, line)
                    }
                }
            } catch (_: Exception) {
                // Pipe borrado/permiso/lo que sea — esperar un poco antes de reintentar en vez
                // de girar en un loop apretado consumiendo CPU si el error es persistente.
                Thread.sleep(2000)
            }
        }
    }

    /**
     * Dispara la misma notificación que llegaría por el FIFO (local + Telegram opt-in) sin
     * pasar por bash — para código Kotlin que detecta un evento por su cuenta en vez de un
     * script (ver RootfsUpdateScheduler.kt: chequeo periódico de actualizaciones del rootfs).
     * Reusa exactamente el mismo notify() privado que usa runLoop()/handleLine(), así que
     * respeta el mismo toggle de notificaciones locales y el mismo opt-in de Telegram.
     */
    @JvmStatic
    fun notifyDirect(context: Context, module: String, event: String, detail: String = "") {
        notify(context.applicationContext, module, event, detail)
    }

    /**
     * Disparo proactivo de un evento de SESIÓN (idle/error/permission/started/stopped) desde
     * código Kotlin — 2026-08-14 (humano123 C3/C4). Mismo camino que notifyDirect() (local +
     * Telegram opt-in + cooldown), pero tipado para que el caller no tenga que recordar los
     * nombres de evento: los únicos que existen son los que messageFor() sabe traducir.
     */
    @JvmStatic
    fun notifySessionEvent(context: Context, module: String, event: SessionEvent, detail: String = "") {
        notify(context.applicationContext, module, event.value, detail)
    }

    enum class SessionEvent(val value: String) {
        STARTED("session_started"),
        STOPPED("session_stopped"),
        IDLE("session_idle"),
        ERROR("session_error"),
        PERMISSION("session_permission"),
    }

    /**
     * Parsea una línea del CONTRATO de sesión (`modulo:session_*:<module_id>[:<detalle>]`,
     * ver el header del archivo) y devuelve (moduleId, SessionEvent) si matchea — null para
     * cualquier otra línea (las históricas `modulo:evento:detalle` con el id como prefijo no
     * se tocan). Es lo que el engine puede usar para leer la salida de un script en
     * ejecución y convertirla en notificación local sin pasar por el FIFO.
     */
    @JvmStatic
    fun parseSessionEvent(line: String): Pair<String, SessionEvent>? {
        val parts = line.trim().split(":")
        if (parts.size < 3 || parts[0] != "modulo") return null
        val event = SessionEvent.entries.firstOrNull { it.value == parts[1] } ?: return null
        val moduleId = parts[2].takeIf { it.isNotBlank() } ?: return null
        return moduleId to event
    }

    private fun handleLine(context: Context, rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        // Línea del contrato de sesión — el id real es el 3er campo, no el prefijo.
        parseSessionEvent(line)?.let { (moduleId, event) ->
            val detail = line.split(":", limit = 4).getOrNull(3) ?: ""
            notifySessionEvent(context, moduleId, event, detail)
            return
        }
        val parts = line.split(":", limit = 3)
        val module = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
        val event = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
        val detail = parts.getOrNull(2) ?: ""
        notify(context, module, event, detail)
    }

    private fun notify(context: Context, module: String, event: String, detail: String) {
        // Cooldown anti-spam (2026-08-14, humano123 C3): un módulo que manda el MISMO
        // evento repetido (p. ej. "session_idle" cada pocos minutos desde un proceso bash en
        // loop) no debe spamear una notificación por línea. Equivalente Kotlin de un
        // "collapse_id" de FCM: mismo módulo+evento dentro de la ventana → se actualiza la
        // misma notificación (mismo id) en vez de apilar una nueva. El id de notificación
        // derivado de "$module:$event" ya colapsa en la bandeja; el timestamp en prefs evita
        // incluso re-notificar dentro de la ventana de cooldown.
        val prefs = context.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
        val cooldownMs = cooldownFor(event)
        val lastKey = "last_event_${module}_$event"
        val now = System.currentTimeMillis()
        val last = prefs.getLong(lastKey, 0L)
        if (cooldownMs > 0 && now - last < cooldownMs) return
        prefs.edit().putLong(lastKey, now).apply()

        val (title, text) = messageFor(module, event, detail)

        if (prefs.getBoolean("pref_notify_modules", true)) {
            sendLocalNotification(context, module, event, title, text)
        }
        // Independiente del toggle de notificaciones locales — Telegram es opt-in propio,
        // gobernado únicamente por si hay token+chat id configurados (ver ConfigFragment).
        sendTelegramIfConfigured(prefs, title, text)
    }

    /** Ventana de cooldown por evento — los de "sesión" (idle) son los propensos a repetirse. */
    private fun cooldownFor(event: String): Long = when (event) {
        "session_idle" -> 30 * 60 * 1000L  // 30 min
        "session_permission" -> 15 * 60 * 1000L // 15 min
        "session_error" -> 5 * 60 * 1000L // 5 min
        "session_started", "session_stopped" -> 0L // siempre informan (no se repiten en loop)
        else -> 0L
    }

    private fun sendLocalNotification(context: Context, module: String, event: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Eventos de módulos", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = android.content.Intent(context, TermuxActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, "$module:$event".hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("$module:$event".hashCode(), notification)
    }

    /**
     * Se llama en el propio hilo del FIFO (runLoop() → handleLine() → notify()) — TelegramNotifier
     * es bloqueante con timeout corto (10s, ver ese archivo) para no dejar el bridge sin leer
     * eventos nuevos más de lo estrictamente necesario si Telegram no responde.
     */
    private fun sendTelegramIfConfigured(prefs: android.content.SharedPreferences, title: String, text: String) {
        val token = prefs.getString(TelegramNotifier.PREF_BOT_TOKEN, null)
        val chatId = prefs.getString(TelegramNotifier.PREF_CHAT_ID, null)
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) return
        TelegramNotifier.sendMessage(token, chatId, "$title\n$text")
    }

    private fun messageFor(module: String, event: String, detail: String): Pair<String, String> {
        val moduleLabel = module.replaceFirstChar { it.uppercase() }
        return when (event) {
            "install_done" -> "$moduleLabel instalado" to "La instalación terminó — tocá para abrir Módulos."
            // Instalación silenciosa (2026-08-20, ver BottomSheetInstalacion.startSilentInstall()
            // y AUDITORIA_UX_INSTALACION_2026-08-19.md): a diferencia de "install_done" (que ya
            // emiten ~52 modulos/*.sh vía notify_event, solo en el camino de ÉXITO), este evento
            // lo dispara SIEMPRE Kotlin (BottomSheetInstalacion), tanto en éxito como en fallo —
            // "install_done" cubre el éxito por partida doble (bash + Kotlin, mismo id de
            // notificación así que no duplica visualmente), pero el fallo no tenía NINGÚN aviso
            // proactivo hasta ahora si el usuario ya había navegado a otra pantalla.
            "install_failed" -> "❌ Falló instalar $moduleLabel" to detail.ifBlank { "Revisá ~/kairos_logs/install_$module.log" }
            "vnc_closed" -> "Sesión de escritorio cerrada" to "La sesión VNC de Entorno se cerró."
            // Eventos de SESIÓN (2026-08-14, humano123 C3/C4) — los manda el lado bash de un
            // módulo (notify_event en modulos/lib.sh) o código Kotlin (notifyDirect). Son los
            // que permiten el aviso PROACTIVO sin polling: el módulo avisa solo cuando pasa
            // algo, en vez de que la app tenga que estar preguntando. Ver el plan en
            // MEJORAS_PENDIENTES.md (push proactivo idle/error/permiso).
            "session_started" -> "$moduleLabel activo" to detail.ifBlank { "La sesión arrancó correctamente." }
            "session_stopped" -> "$moduleLabel detenido" to detail.ifBlank { "La sesión se detuvo." }
            "session_idle" -> "$moduleLabel inactivo" to detail.ifBlank { "Lleva un rato sin actividad — ¿sigue haciendo falta que corra?" }
            "session_error" -> "⚠ Error en $moduleLabel" to detail.ifBlank { "Ocurrió un error en la sesión — revisá los logs del módulo." }
            "session_permission" -> "🔐 Permiso requerido en $moduleLabel" to detail.ifBlank { "Falta algún permiso para que el módulo funcione — abrí Ajustes." }
            // Disparado por RootfsUpdateScheduler.kt (chequeo periódico, no por un script bash
            // — "module" acá es el string fijo "sistema", no un id de modules.json real).
            "updates_available" -> "Actualizaciones del sistema disponibles" to detail.ifBlank { "Abrí Ajustes → Comprobar paquetes del sistema." }
            else -> "$moduleLabel: $event" to detail.ifBlank { "Tocá para abrir Kairos." }
        }
    }
}
