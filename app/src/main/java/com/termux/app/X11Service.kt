package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.system.Os
import androidx.core.app.NotificationCompat
import com.termux.shared.termux.TermuxConstants
import com.termux.x11.CmdEntryPoint

/**
 * Servidor X11 embebido (Xlorie/termux-x11). Patrón X11Service de Linbox-WinEmu
 * (ver docs/x11/X11_EMBEBIDO.md): corre en un proceso aparte del mismo APK
 * (`android:process=":xserver"`, declarado en el manifest de la app) y arranca el
 * servidor X con `CmdEntryPoint.main(arrayOf(":1"))` — el bloque static de
 * CmdEntryPoint carga `libXlorie.so` vía `System.loadLibrary("Xlorie")` y el `main`
 * nativo corre el Xorg completo dentro del proceso.
 *
 * El visor (`com.termux.x11.MainActivity`) se abre desde el menú "Más" → X11 y se
 * conecta al servidor por el broadcast `ACTION_START` que CmdEntryPoint reemite cada
 * segundo hasta que hay conexión (`sendBroadcastDelayed()`), en el paquete com.termux
 * (el de Kairos). Como el hilo de CmdEntryPoint hace `Looper.loop()` (bloqueante), el
 * proceso `:xserver` queda vivo mientras el servicio corre.
 */
class X11Service : Service() {

    companion object {
        private const val TAG = "X11Service"
        const val DISPLAY = ":1"
        private const val CHANNEL_ID = "x11_server"
        private const val NOTIFICATION_ID = 7893
        private const val WAKE_LOCK_TAG = "Kairos:X11ServerWakeLock"

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, X11Service::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, X11Service::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // WakeLock parcial mientras el proceso :xserver está vivo (patrón confirmado en
        // DroidDesk-main, un proyecto de referencia con la misma arquitectura — servidor X
        // embebido en su propio proceso Android — ver DroidDeskService.kt: sin esto, el
        // Phantom Process Killer / Doze de Android puede matar el proceso en background sin
        // avisar, mismo tipo de falla silenciosa ya root-causada para TMPDIR/LD_PRELOAD en
        // la investigación 2026-08-17 (docs/x11/X11_EMBEBIDO.md). El permiso
        // WAKE_LOCK ya estaba declarado en el manifest (usado por otro módulo) pero
        // X11Service no lo usaba. Se libera en onDestroy() — no se agrega timeout porque el
        // servicio ya es responsable de su propio ciclo de vida completo (start()/stop()).
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            // No debería fallar (WAKE_LOCK es un permiso normal, ya concedido en instalación)
            // — si pasa igual, el servidor sigue funcionando, solo pierde la protección extra.
        }
        // Investigación 2026-08-17 (ver docs/x11/X11_EMBEBIDO.md, reporte de usuario
        // "no inicia XFCE4 nativo ni con distro"): cmdentrypoint.c (fuente real del
        // libXlorie.so embebido, ver referencia/termux/termux-x11-master/lorie/ y
        // referencia/emuladores/linbox-main/termux-x11/.../android.c, misma lógica en ambos)
        // resuelve el socket X11 vía "$TMPDIR/.X11-unix/X<display>", y si TMPDIR no está
        // seteado hace su propio fallback: usa "/tmp" si access("/tmp", F_OK)==0, y solo si
        // ESO falla cae a "$PREFIX/tmp" (el path real de Termux). En un proceso Android bare
        // (":xserver", sin shell de Termux de por medio) no hay garantía de qué devuelve ese
        // access() en cada dispositivo — si por cualquier motivo "/tmp" resulta accesible,
        // Xlorie crea el socket en un lugar que NINGÚN cliente (ni startxfce4 nativo, ni un
        // proot-distro con --shared-tmp, que mapea "$PREFIX/tmp") va a encontrar jamás,
        // explicando por qué el escritorio no arranca en NINGUNO de los 2 modos por igual.
        // Fix: forzar TMPDIR explícito ANTES de que el código nativo lo lea, vía
        // android.system.Os.setenv() (wrapper real de setenv(3) a nivel de proceso — a
        // diferencia de System.getenv()/ProcessBuilder.environment(), esto SÍ es visible para
        // getenv() en código nativo JNI del mismo proceso), eliminando la ambigüedad en vez
        // de confiar en el fallback interno del .so.
        try {
            Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)
        } catch (_: Exception) {
            // No debería fallar nunca (setenv real de libc) — si pasa, el fallback interno
            // del propio Xlorie sigue vigente, no es fatal.
        }
        Thread {
            // CmdEntryPoint.main() necesita un Looper preparado en el hilo (hace
            // handler.post + Looper.loop()), igual que en Linbox-WinEmu:
            //   Looper.prepare()
            //   CmdEntryPoint.main(arrayOf(":13"))
            Looper.prepare()
            CmdEntryPoint.main(arrayOf(DISPLAY))
        }.apply {
            name = "x11-server"
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
            // Nada que hacer si la liberación falla — el servicio ya está terminando.
        }
        wakeLock = null
        super.onDestroy()
        // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): "Cerrar servidor
        // X11" llamaba a stopService() y el proceso :xserver seguía vivo indefinidamente
        // (confirmado con `ps -ef` antes/después — mismo PID, CPU acumulándose, sin morir).
        // Causa raíz: CmdEntryPoint.main() (código nativo protegido, no se toca) corre
        // Looper.prepare()+Looper.loop() en un Thread separado — un loop bloqueante que
        // stopService()/onDestroy() no interrumpe, porque Android solo destruye el objeto
        // Service, no mata Threads que ese Service haya lanzado. Como X11Service corre
        // aislado en su propio proceso (android:process=":xserver", ver AndroidManifest.xml),
        // matar el proceso completo es seguro y es el único mecanismo real disponible ya
        // que CmdEntryPoint no expone ningún stop()/exit() propio (solo System.exit() en
        // casos de error interno, ver x11-server/.../CmdEntryPoint.java).
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Servidor X11", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Servidor X11 activo")
            .setContentText("Display " + DISPLAY + " — tocá para volver a Kairos")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
