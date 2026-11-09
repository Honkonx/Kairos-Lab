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

/**
 * Pantalla Permisos (ConfigFragment, pestaña "Permisos") — pedido explícito del usuario,
 * punto 2 del encargo: mientras la persona corre el emparejamiento de ADB inalámbrico tiene que
 * salir de Kairos para leer el puerto/código que muestra Ajustes de Android, y puede volver sin
 * recordar lo que ya había tipeado. Reusa el mismo mecanismo de notificación que
 * `ModuleEventBridge` (NotificationCompat.Builder + canal propio, ver ese archivo para el patrón
 * original) en vez de un overlay (`FloatingWidgetService`) — no hace falta el permiso
 * SYSTEM_ALERT_WINDOW para esto, una notificación corriente alcanza y es menos invasivo.
 *
 * El código de emparejamiento NUNCA se guarda (expira en menos de un minuto y no tiene valor
 * persistir un secreto de un solo uso, ver `.claude/rules/kairos-secrets-never-revealed.md`) —
 * solo vive en la notificación mientras el emparejamiento está en curso, y se cancela al
 * terminar (éxito o error). Lo que SÍ se persiste (punto "guardar esta sesión" del encargo) son
 * los 2 puertos, que no cambian tan seguido como el código.
 */
object PermissionsNotifier {

    private const val CHANNEL_ID = "kairos_permissions"
    private const val NOTIFICATION_ID = 8417 // "ADB" en el teclado numérico, sin significado real más que ser estable y propio.

    @JvmStatic
    fun showPairingSnapshot(context: Context, pairingPort: String, code: String, connectPort: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Permisos — emparejamiento ADB", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = android.content.Intent(context, TermuxActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val text = "Puerto emparejamiento: $pairingPort · Código: $code · Puerto conexión: $connectPort"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Emparejando ADB inalámbrico…")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    @JvmStatic
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    // ── "Guardar esta sesión" — solo los 2 puertos, nunca el código de un solo uso ──────────

    private const val PREFS_NAME = "kairos_prefs"
    private const val KEY_PAIRING_PORT = "pref_adb_pairing_port"
    private const val KEY_CONNECT_PORT = "pref_adb_connect_port"

    @JvmStatic
    fun saveSession(context: Context, pairingPort: String, connectPort: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAIRING_PORT, pairingPort)
            .putString(KEY_CONNECT_PORT, connectPort)
            .apply()
    }

    @JvmStatic
    fun lastPairingPort(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PAIRING_PORT, "") ?: ""

    @JvmStatic
    fun lastConnectPort(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CONNECT_PORT, "") ?: ""
}
