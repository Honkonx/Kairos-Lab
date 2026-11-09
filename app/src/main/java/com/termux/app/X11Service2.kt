package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.system.Os
import androidx.core.app.NotificationCompat
import com.termux.shared.termux.TermuxConstants
import com.termux.x11.CmdEntryPoint

/**
 * Segunda instancia del servidor X11 embebido, en un proceso Android propio (`:xserver2`) y
 * display `:2` — hallazgo real "2 instancias X11 conmutables (nativa + distro)", pedido
 * explícito del usuario desde 2026-08-18 (ver MEJORAS_PENDIENTES.md): "luego crear una especie
 * de panel para eso" (alternar entre escritorio nativo y con-distro sin cerrar uno para abrir
 * el otro). Es casi un duplicado exacto de [X11Service] a propósito — cada instancia de
 * CmdEntryPoint corre en SU PROPIO proceso Android (espacio de memoria aislado), así que cargar
 * el mismo libXlorie.so prebuilt 2 veces (una por proceso) no debería colisionar: el número de
 * display (":1" vs ":2") es lo que el binario nativo usa para derivar su propio socket/recursos
 * X (`$TMPDIR/.X11-unix/X<display>`), igual que cualquier servidor X real.
 *
 * SIN VERIFICAR EN DISPOSITIVO REAL (ver .claude/rules/kairos-minipc-empirical-verification.md)
 * — implementado 2026-11-09 sin dispositivo conectado esta sesión, permiso explícito del
 * usuario para avanzar igual. Antes de darlo por funcional: confirmar que ambos procesos
 * (`:xserver` y `:xserver2`) corren a la vez sin pisarse (`ps -ef` en el dispositivo), que cada
 * visor (KairosX11MainActivity) se conecta al display correcto (no al equivocado), y que
 * `desktop_setup_gui.sh`/`gui_start.sh` reciben `DISPLAY=:2` cuando corresponde.
 */
class X11Service2 : Service() {

    companion object {
        const val DISPLAY = ":2"
        private const val CHANNEL_ID = "x11_server_2"
        private const val NOTIFICATION_ID = 7894
        private const val WAKE_LOCK_TAG = "Kairos:X11ServerWakeLock2"

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, X11Service2::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, X11Service2::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        // Mismo fix de TMPDIR que X11Service -- necesario también acá porque este es un
        // proceso Android nuevo (:xserver2), con su propio entorno sin TMPDIR seteado.
        try {
            Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)
        } catch (_: Exception) {
        }
        Thread {
            Looper.prepare()
            CmdEntryPoint.main(arrayOf(DISPLAY))
        }.apply {
            name = "x11-server-2"
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
        // Mismo motivo que X11Service.onDestroy(): CmdEntryPoint.main() bloquea en
        // Looper.loop() en un Thread que stopService() no puede interrumpir -- matar el
        // proceso completo (aislado en :xserver2) es el único mecanismo real disponible.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Servidor X11 (distro)", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Servidor X11 (distro) activo")
            .setContentText("Display " + DISPLAY + " — tocá para volver a Kairos")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
