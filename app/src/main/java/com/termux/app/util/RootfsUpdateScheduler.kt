package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Chequeo PERIÓDICO/AUTOMÁTICO de actualizaciones del rootfs base — antes
 * RootfsPackageChecker.checkUpdates() solo corría si el usuario tocaba "Comprobar paquetes del
 * sistema" a mano en Ajustes (ConfigFragment) o en el wizard (WizardCheckFragment). Pedido
 * explícito del usuario: que corra solo y avise, sin que el usuario lo dispare.
 *
 * El proyecto NO usa WorkManager (confirmado por grep sobre app/src/main — cero matches reales,
 * solo una mención en un skill deshabilitado de .opencode/), así que en vez de agregar esa
 * dependencia nueva para un chequeo de "una vez cada tantos días", se usa el mecanismo más
 * simple posible: un timestamp en SharedPreferences("kairos_prefs") + un chequeo en background
 * al arrancar el proceso de la app (TermuxApplication.onCreate(), mismo lugar donde ya arranca
 * ModuleEventBridge). Si pasaron más de PERIOD_MS desde la última vez, corre
 * RootfsPackageChecker.checkUpdates() en un Thread{} (NUNCA bloquea el arranque — apt update +
 * apt list --upgradable puede tardar bastante con conexión lenta) y, si hay paquetes
 * desactualizados, notifica vía ModuleEventBridge.notifyDirect() (notificación local +
 * Telegram opt-in, mismo mecanismo que ya usan los eventos de módulos) en vez de esperar a que
 * el usuario entre a Ajustes por su cuenta.
 */
object RootfsUpdateScheduler {

    private const val PREFS_NAME = "kairos_prefs"
    private const val KEY_LAST_CHECK_TS = "rootfs_last_update_check_ts"
    private const val PERIOD_MS = 7L * 24 * 60 * 60 * 1000 // 7 días

    @JvmStatic
    fun maybeCheckPeriodically(context: Context) {
        val appContext = context.applicationContext

        // Solo si el wizard ya terminó (".kairos_ready" es el mismo flag que usa
        // ConfigFragment.showRerunSetupDialog()/performFullReinstall() como fuente de verdad
        // de "setup completo") — correr apt sobre un rootfs a medio instalar no tiene sentido
        // y podría pisarle el paso al propio wizard si el proceso arranca en paralelo.
        if (!File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_ready").exists()) return

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < PERIOD_MS) return

        Thread({
            val upgradable = try {
                RootfsPackageChecker.checkUpdates()
            } catch (_: Exception) {
                // No se guarda el timestamp acá a propósito — si algo salió mal (ej. apt
                // ocupado por otra operación en simultáneo) se reintenta en el próximo
                // arranque en vez de esperar 7 días más por un chequeo que nunca corrió.
                return@Thread
            }
            prefs.edit().putLong(KEY_LAST_CHECK_TS, now).apply()
            if (upgradable.isNotEmpty()) {
                ModuleEventBridge.notifyDirect(
                    appContext, "sistema", "updates_available",
                    "${upgradable.size} paquetes del sistema con actualización disponible."
                )
            }
        }, "RootfsUpdateScheduler").apply {
            isDaemon = true
            start()
        }
    }
}
