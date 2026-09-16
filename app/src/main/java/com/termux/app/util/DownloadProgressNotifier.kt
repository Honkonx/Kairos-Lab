package com.termux.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.termux.app.TermuxActivity

/**
 * Notificación de progreso REAL (bytes descargados / tamaño total) para descargas largas
 * iniciadas por el usuario — hoy solo modelos GGUF (`LocalAIFragment.downloadCatalogModel()`/
 * `downloadModel()`, hasta ~9GB según el catálogo curado, ver
 * docs/ia-local/LLAMA_CPP_EMBEBIDO.md). Pedido explícito de ronda 2026-09-15 (Android 16/API 36
 * "Live Updates" — `Notification.ProgressStyle`, barra segmentada visible en status bar chip +
 * lock screen).
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────
 * POR QUÉ `android.app.Notification.Builder` NATIVO, NO `androidx.core.app.NotificationCompat`
 * ─────────────────────────────────────────────────────────────────────────────────────────
 * `NotificationCompat.ProgressStyle` (el wrapper de androidx que también generaría el fallback
 * automático en versiones viejas) recién existe desde `androidx.core:core:1.16.0` — este
 * proyecto fija `androidx.core:core:1.13.1` (ver app/build.gradle), varias versiones menores
 * antes. Bumpear esa dependencia es un cambio de superficie mucho más grande que agregar una
 * notificación (afecta CUALQUIER uso de NotificationCompat/ContextCompat en toda la app) y
 * queda fuera de alcance de esta ronda — en vez de eso, se usa el `android.app.Notification`
 * NATIVO de plataforma, confirmado real en el `android.jar` de compileSdk=36 con `javap`
 * (`platforms/android-36/android.jar`, clases `Notification$ProgressStyle`,
 * `Notification$ProgressStyle$Segment`, `Notification$ProgressStyle$Point`, método público
 * `Notification.Builder.setFlag(int, boolean)`, campo `Notification.FLAG_PROMOTED_ONGOING`,
 * `NotificationManager.canPostPromotedNotifications()`, y `Build.VERSION_CODES.BAKLAVA = 36`
 * — todo confirmado con evidencia real de bytecode, no solo documentación). El fallback en
 * versiones viejas (`setProgress()` clásico) sí usa `NotificationCompat.Builder` normal — ambos
 * caminos convergen en `NotificationManagerCompat.notify()`, que acepta cualquier
 * `android.app.Notification` sin importar qué Builder lo construyó.
 *
 * El progreso se expresa siempre en porcentaje 0-100 (un único `Segment(100)`), nunca en bytes
 * crudos — un `.gguf` de ~9GB supera `Int.MAX_VALUE` (~2.1GB) y `Segment`/`setProgress()` solo
 * aceptan `Int`, así que bytes reales causarían overflow silencioso.
 * ─────────────────────────────────────────────────────────────────────────────────────────
 */
object DownloadProgressNotifier {

    private const val CHANNEL_ID = "kairos_download_progress"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            // IMPORTANCE_LOW (no IMPORTANCE_DEFAULT como ModuleEventBridge): esta notificación
            // se reconstruye cada ~500ms mientras dura la descarga (throttle real de
            // LocalModelManager.downloadModel) — con importancia default sonaría/vibraría en
            // cada actualización si el canal no lo bloqueara explícitamente.
            NotificationChannel(CHANNEL_ID, "Progreso de descargas", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Barra de progreso de descargas largas iniciadas por el usuario (modelos GGUF, etc.)"
                setSound(null, null)
            }
        )
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, TermuxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Actualiza (o crea) la notificación ongoing de progreso. Pensado para llamarse desde el
     * hilo de descarga directamente (no requiere el hilo de UI) — se muestra igual esté o no
     * el Fragment que la originó adjunto, a propósito: el punto de esta notificación es avisar
     * cuando el usuario YA navegó a otra pantalla.
     *
     * `percent` fuera de 0..100 (ej. -1 durante "Conectando…", antes de conocer el
     * Content-Length) deja la barra en modo indeterminado.
     */
    @JvmStatic
    fun updateProgress(context: Context, notificationId: Int, title: String, percent: Int, statusText: String) {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return
        ensureChannel(appContext)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildProgressStyleNotification(appContext, notificationId, title, percent, statusText)
        } else {
            buildClassicNotification(appContext, notificationId, title, percent, statusText)
        }
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
    }

    /** Reemplaza la notificación ongoing por un resultado final (éxito/error), ya sin barra de progreso. */
    @JvmStatic
    fun finish(context: Context, notificationId: Int, title: String, success: Boolean, message: String) {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return
        ensureChannel(appContext)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) title else "$title — error")
            .setContentText(message)
            .setContentIntent(contentIntent(appContext, notificationId))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
    }

    /** Quita la notificación de progreso sin dejar un resultado final — usado cuando otro camino (ej. ModuleEventBridge) ya va a avisar el resultado. */
    @JvmStatic
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId)
    }

    private fun buildProgressStyleNotification(
        context: Context,
        notificationId: Int,
        title: String,
        percent: Int,
        statusText: String,
    ): Notification {
        val indeterminate = percent !in 0..100
        val style = Notification.ProgressStyle()
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(PROGRESS_COLOR)))
            .setProgress(percent.coerceIn(0, 100))
            .setProgressIndeterminate(indeterminate)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(contentIntent(context, notificationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(style)

        // Promoción a status bar chip / lock screen ("Live Update" real, no solo la barra
        // dentro de la notificación) — requiere que el sistema lo autorice de antemano
        // (`canPostPromotedNotifications()`, único chequeo público disponible; no existe un
        // diálogo de runtime permission estándar equivalente al de POST_NOTIFICATIONS para
        // esto). Si no está autorizado, la notificación sigue funcionando igual como ongoing
        // normal con ProgressStyle — la promoción es un extra visual, no un requisito.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && nm.canPostPromotedNotifications()) {
            builder.setFlag(Notification.FLAG_PROMOTED_ONGOING, true)
        }
        return builder.build()
    }

    private fun buildClassicNotification(
        context: Context,
        notificationId: Int,
        title: String,
        percent: Int,
        statusText: String,
    ): Notification {
        val indeterminate = percent !in 0..100
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(contentIntent(context, notificationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
    }

    /** `kairos_green` real (colors_kairos.xml) — la notificación del sistema no puede leer `?attr/kairosGreen` en runtime, así que se hardcodea el mismo valor. */
    private val PROGRESS_COLOR = Color.parseColor("#22C55E")
}
