package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.termux.R
import com.termux.app.util.AdbShellBridge
import com.termux.app.util.GatewayManager

/**
 * Mantiene vivo el proceso del gateway (`libkairos_gateway.so`, arrancado por
 * [GatewayManager.start]) con una notificación foreground — sin esto, Android puede matar el
 * proceso de la app (y con él, el proceso hijo del gateway) en cuanto queda en background sin
 * nada que lo proteja, igual que le pasaría a cualquier otro trabajo de fondo sin servicio
 * propio. Pedido explícito del usuario (ver meta 7: "que Kairos quede
 * corriendo en segundo plano siempre ejecutando dicho código... para ver y controlar el móvil
 * por completo desde el gateway"). Mismo molde que [TelegramBotService]/[X11Service] — foreground
 * desde `onCreate()`, start()/stop() estáticos, `android:foregroundServiceType="dataSync"`.
 *
 * Este Service NO reimplementa la lógica de arrancar/detener el gateway — eso sigue siendo
 * [GatewayManager] tal cual, llamado desde `HomelabFragment` igual que antes. Lo único que cambia
 * es que arrancar el gateway también arranca (y detenerlo también para) este Service, para que el
 * proceso completo de Kairos quede protegido mientras el gateway esté activo.
 *
 * También hospeda el watcher periódico de reconexión de [AdbShellBridge] (2026-10-31) — mientras
 * el gateway esté activo, cada [ADB_RECONNECT_PERIOD_MS] intenta reconectar la sesión ADB
 * inalámbrica si se cayó, en vez de depender solo de la reconexión perezosa de `kairos-shell`.
 */
class GatewayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gateway_foreground"
        private const val NOTIFICATION_ID = 7895

        // Pendiente de esta ronda: watcher propio de reconexión del ADB
        // inalámbrico — antes solo reconectaba perezosamente al invocar `kairos-shell` a mano, lo
        // que no sirve si mDNS no está disponible y nadie toca la terminal por un rato largo.
        private const val ADB_RECONNECT_PERIOD_MS = 5L * 60 * 1000 // 5 minutos

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayForegroundService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adbReconnectRunnable = object : Runnable {
        override fun run() {
            Thread({ AdbShellBridge.reconnectIfNeeded() }, "adb-reconnect-watcher").apply { isDaemon = true; start() }
            mainHandler.postDelayed(this, ADB_RECONNECT_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        mainHandler.postDelayed(adbReconnectRunnable, ADB_RECONNECT_PERIOD_MS)
    }

    // START_STICKY: si Android mata el proceso de todas formas bajo presión de memoria extrema,
    // que lo reintente — a diferencia de TelegramBotService (START default), acá SÍ tiene sentido
    // porque GatewayManager.start() es idempotente (ver su propio chequeo de "ya corriendo") y no
    // depende de configuración de usuario que pudiera faltar (a diferencia del bot, que se niega
    // fail-closed sin token/chat_id). isHealthy()/start() hacen I/O real (HTTP, arrancar un
    // proceso) — SIEMPRE en un hilo propio, nunca en onStartCommand() (hilo principal, riesgo de
    // ANR, mismo criterio que onDestroy() más abajo).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        Thread({ if (!GatewayManager.isHealthy()) GatewayManager.start(app) }, "gateway-fg-start").apply { isDaemon = true; start() }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.gateway_foreground_channel_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val intent = Intent(this, TermuxActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.gateway_foreground_notification_title))
            .setContentText(getString(R.string.gateway_foreground_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // GatewayManager.stop() bloquea hasta 3s esperando a que el proceso real termine — onDestroy()
    // de un Service corre en el hilo principal, así que NUNCA se llama directo ahí (riesgo real de
    // ANR). Daemon thread: si el proceso de la app muere antes de que termine, no hay nada que
    // limpiar del lado Android de todas formas (el proceso hijo del gateway también muere con él).
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(adbReconnectRunnable)
        Thread({ GatewayManager.stop() }, "gateway-fg-stop").apply { isDaemon = true; start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
