package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Acción "Detener" de las notificaciones de sesión de módulo (`ModuleEventBridge`, eventos
 * `session_started`/`session_idle`/`session_permission` — los 3 que implican que el módulo está
 * corriendo ahora mismo). Gap real de "comunicación externa con Android" (ronda 2026-09-16): antes
 * de esto, una notificación de módulo solo ofrecía tocarla para ABRIR la app — para detener el
 * módulo había que abrir Kairos y navegar a Módulos, aunque la notificación ya sabía qué módulo
 * era. Mismo patrón que `TermuxService`'s propia acción "Exit" en su notificación de sesión
 * (`PendingIntent.getService`) — acá es `getBroadcast` porque `stopModule()` no necesita un
 * `Service` propio, solo el `ProcessBuilder` que ya orquesta `ModuleController`.
 *
 * `exported="false"` en el manifest — no es una superficie pública para otras apps (a diferencia
 * de `DeepLinkHandler`, que SÍ es la superficie externa deliberada y pasa por
 * `ActionApprovalGate`); esto solo lo dispara el propio Android al tocar el botón de UNA
 * notificación que Kairos generó, así que no hace falta gatearlo de nuevo — el usuario ya vio la
 * notificación y decidió tocar "Detener" a propósito, equivalente a tocar el toggle del módulo
 * en la propia app.
 */
class StopModuleReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
        val appContext = context.applicationContext
        ModuleController.stopModule(moduleId, appContext) { _ -> }
    }
}
