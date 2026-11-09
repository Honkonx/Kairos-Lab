package com.termux.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.termux.R
import com.termux.app.GatewayConfirmReceiver
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resumen legible de TODOS los parámetros — nunca omite uno en silencio ni oculta un corte. Dos
 * revisiones de seguridad reales sobre esta misma función:
 * 1. (commit e342259) la versión original mostraba solo el primer campo "importante" que
 *    encontraba, así que `privilege.run` (params `provider`+`cmd`) aparecía como solo
 *    "cmd: rm -rf /" sin decir qué proveedor lo ejecutaría.
 * 2. (este fix) la versión siguiente mostraba todos los campos pero recortaba cada uno a
 *    [FIELD_MAX] SIN marcar el corte si el texto conjunto no superaba [TOTAL_MAX] — un comando de
 *    5000 caracteres se veía como "cmd: rm -rf /algo..." (300 caracteres) sin ningún indicio de
 *    que el resto seguía ahí; alguien podía aprobar creyendo que vio el comando completo.
 * Ahora CUALQUIER corte, de campo o de total, deja una marca "…" visible — nunca un texto que
 * parezca completo sin estarlo.
 */
internal fun describeGatewayPendingParams(params: JSONObject): String {
    val priority = listOf("provider", "cmd", "command", "name", "text", "path", "to", "alias", "image", "id")
    val keys = params.keys().asSequence().toList()
    val ordered = priority.filter { it in keys } + keys.filterNot { it in priority }
    val pairs = ordered.map { k ->
        val v = params.optString(k)
        "$k: " + if (v.length > FIELD_MAX) v.take(FIELD_MAX) + "…" else v
    }
    val joined = pairs.joinToString(" · ")
    return if (joined.length > TOTAL_MAX) joined.take(TOTAL_MAX) + "…" else joined
}

private const val FIELD_MAX = 300
private const val TOTAL_MAX = 600

/**
 * Confirmación en vivo de las acciones del gateway en nivel "pregunta" (`permission.AskUser`): sin esto, la acción
 * queda pendiente en el servidor sin que la persona vea NUNCA el comando real que se le pide aprobar — justo el hueco
 * que la revisión de seguridad de `privilege.run` dejó anotado como condición para bajar su nivel mínimo (ver
 * ``/`GATEWAY_GO.md`). Escucha `GET /v1/admin/events` (ya expone el evento "pending" con los
 * parámetros reales, `actions.go:ask()`) y muestra una notificación con el comando y botones Aprobar/Denegar — mismo
 * patrón que `StopModuleReceiver` (acción de notificación -> `BroadcastReceiver`, sin abrir la app).
 */
object GatewayConfirmationWatcher {
    private const val CHANNEL_ID = "kairos_gateway_confirm"
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    /** Arranca el hilo de escucha si no estaba corriendo ya; se llama junto a [GatewayManager.start]. */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        ensureChannel(app)
        thread = Thread({
            while (running.get()) {
                if (GatewayManager.isHealthy()) {
                    GatewayManager.adminClient().streamEvents(shouldStop = { !running.get() }) { type, data ->
                        if (type == "pending" && data != null) notify(app, data)
                    }
                }
                if (running.get()) Thread.sleep(2_000) // el gateway no está arriba o la conexión SSE se cayó: reintenta
            }
        }, "gateway-confirm-watch").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun notify(context: Context, data: JSONObject) {
        val id = data.optString("id")
        if (id.isEmpty()) return
        val actionId = data.optString("action_id")
        val title = data.optString("title").ifBlank { actionId }
        val tokenName = data.optString("token_name")
        val params = data.optJSONObject("params") ?: JSONObject()
        val body = (if (tokenName.isNotBlank()) "Token \"$tokenName\" pide: " else "") + describeGatewayPendingParams(params)
        val notifId = ("gw-" + id).hashCode()

        fun decide(approve: Boolean) = PendingIntent.getBroadcast(
            context, (notifId.toString() + approve).hashCode(),
            Intent(context, GatewayConfirmReceiver::class.java).apply {
                putExtra(GatewayConfirmReceiver.EXTRA_PENDING_ID, id)
                putExtra(GatewayConfirmReceiver.EXTRA_APPROVE, approve)
                putExtra(GatewayConfirmReceiver.EXTRA_NOTIFICATION_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("Gateway: $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL) // misma urgencia visual que una llamada: pide decisión ya
            .setAutoCancel(true)
            .addAction(0, "Aprobar", decide(true))
            .addAction(0, "Denegar", decide(false))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Gateway: confirmaciones", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
