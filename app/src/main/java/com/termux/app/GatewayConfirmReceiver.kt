package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.termux.app.util.GatewayManager

/**
 * Aprobar/Denegar de la notificación de [com.termux.app.util.GatewayConfirmationWatcher] — mismo patrón que
 * [StopModuleReceiver]: la persona ya vio el comando real en la notificación y decidió, no hace falta abrir la app.
 * `exported="false"`: solo el propio Android dispara esto al tocar el botón de una notificación de Kairos.
 */
class GatewayConfirmReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_PENDING_ID = "pending_id"
        const val EXTRA_APPROVE = "approve"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_PENDING_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)
        Thread { GatewayManager.adminClient().decide(id, approve) }.start()
    }
}
