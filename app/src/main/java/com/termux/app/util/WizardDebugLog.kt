package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Log persistente de todo lo que pasa en el wizard desde la pantalla 3 (Procesos fantasma) en
 * adelante — pedido explícito del usuario (ver docs/humano/humano62.md): "si puedes agregar log
 * a la aplicacion entera... desde la tercera pantalla". Las 4 pantallas de esta ronda en
 * adelante (`WizardPhantomProcessFragment`, `WizardBatteryFragment`, `WizardInstallFragment`,
 * `TermuxInstaller.setupBootstrapIfNeeded()`, `RootfsInstaller`) son las que tuvieron bugs
 * reales que solo se pudieron confirmar con capturas de pantalla, ronda tras ronda — un log
 * persistente que el usuario pueda mandar directo (en vez de una captura de un mensaje de
 * error cortado) acorta mucho ese ciclo.
 *
 * Un solo archivo combinado (`~/kairos_logs/wizard_debug.log`), no uno por pantalla — mismo
 * directorio que ya usa `ModuleController.installLogFile()` para los logs de instalación de
 * módulos, así que el usuario ya sabe dónde mirar. Se reinicia (`resetForNewRun()`) cada vez
 * que arranca el wizard, para no ir arrastrando corridas viejas mezcladas con la actual.
 */
object WizardDebugLog {

    private const val LOG_FILE_NAME = "wizard_debug.log"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val logFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_logs").apply { mkdirs() }
            .resolve(LOG_FILE_NAME)

    /** Llamado una vez al entrar a la pantalla 3 (Procesos fantasma) — descarta el log de
     * una corrida anterior del wizard para que cada intento quede en un archivo limpio. */
    @Synchronized
    @JvmStatic
    fun resetForNewRun() {
        try {
            val file = logFile
            file.parentFile?.mkdirs()
            file.writeText("=== Nueva corrida del wizard — ${timeFormat.format(java.util.Date())} ===\n")
        } catch (_: Exception) {
            // El log es de diagnóstico, nunca debe interrumpir el flujo real del wizard.
        }
    }

    @Synchronized
    @JvmStatic
    fun log(tag: String, message: String) {
        try {
            logFile.appendText("[${timeFormat.format(java.util.Date())}] [$tag] $message\n")
        } catch (_: Exception) {
            // Idem — nunca debe tirar ni bloquear el flujo real por un problema de logging.
        }
    }

    @JvmStatic
    fun logException(tag: String, e: Exception) {
        log(tag, "EXCEPCIÓN: ${e.javaClass.simpleName}: ${e.message}")
    }
}
