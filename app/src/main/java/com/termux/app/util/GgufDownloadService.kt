package com.termux.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termux.R

/**
 * Protege el proceso de Kairos con una notificación foreground mientras una descarga de .gguf
 * sigue corriendo en background — sin esto, Android puede matar el proceso completo (y con él,
 * el `Thread` de descarga) en cuanto la app queda sin Activity visible bajo presión de memoria,
 * sin importar que [DownloadProgressNotifier] ya muestre una notificación ongoing (una
 * notificación posteada desde un Thread normal NO eleva la prioridad del proceso — solo lo hace
 * `startForeground()` llamado desde un [Service] real). Hallazgo real de
 * `docs/referencias/ia/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md` ("foreground service para
 * descargas de GGUF — hoy en Thread{}, se puede perder en background").
 *
 * A diferencia de `GatewayForegroundService`/`X11Service`, este Service NO ejecuta la descarga él
 * mismo — `LocalAIFragment.downloadCatalogModel()`/`downloadModel()` ya tienen un flujo real y
 * probado (resume por `Range`, notificación de progreso en vivo, diálogo "allowBackground",
 * `ModuleEventBridge` al completar). Lo único que faltaba era la protección de proceso — este
 * Service solo la agrega, con [start] justo antes de lanzar ese Thread y [stop] cuando termina
 * (éxito o error), sin duplicar ni tocar esa lógica ya probada. Usa el MISMO canal que
 * [DownloadProgressNotifier] (`"kairos_download_progress"`) y el MISMO `notificationId` que esa
 * descarga en particular — las llamadas posteriores de `DownloadProgressNotifier.updateProgress`
 * reemplazan en el sitio la notificación placeholder de este Service con el progreso real, en vez
 * de mostrar una segunda notificación duplicada.
 *
 * Soporta más de una descarga simultánea con un set de ids activos — [start] agrega, [stop]
 * quita, y el Service recién se detiene de verdad cuando el set queda vacío (ej. el catálogo y
 * "Agregar modelo por URL" corriendo a la vez no deben pisarse apagando el Service a mitad de
 * la otra descarga).
 */
class GgufDownloadService : Service() {

    companion object {
        /** Mismo canal que [DownloadProgressNotifier] a propósito — así la notificación de
         *  progreso real reemplaza en el sitio a esta placeholder, sin duplicar. */
        private const val CHANNEL_ID = "kairos_download_progress"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_TITLE = "title"
        private const val DEFAULT_NOTIFICATION_ID = 785010
        private val activeIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        @JvmStatic
        fun start(context: Context, notificationId: Int, title: String) {
            activeIds.add(notificationId)
            val intent = Intent(context, GgufDownloadService::class.java).apply {
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context, notificationId: Int) {
            activeIds.remove(notificationId)
            if (activeIds.isEmpty()) {
                context.stopService(Intent(context, GgufDownloadService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, DEFAULT_NOTIFICATION_ID) ?: DEFAULT_NOTIFICATION_ID
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.gguf_download_guard_notification_title)
        startForeground(notificationId, buildPlaceholderNotification(title))
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.gguf_download_guard_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                    }
                )
            }
        }
    }

    private fun buildPlaceholderNotification(title: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(getString(R.string.gguf_download_guard_notification_text))
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
