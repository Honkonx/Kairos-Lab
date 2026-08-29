package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Log interno TRANSVERSAL de la app Kairos en sí — pedido explícito del usuario (ver
 * docs/humano231.md): "por eso es bueno tener un log interno completo del apk incluso de la
 * terminal [...] Ojo, NO debe ser log de módulos, es log completo del APK en sí". Distinto de
 * `ModuleController.installLogFile()`/`WizardDebugLog.kt` (ambos ya existían, pero uno es
 * puramente por-módulo y el otro solo cubre el wizard de primer arranque) — este archivo cubre
 * el resto del ciclo de vida de la app: navegación, errores no capturados, y opcionalmente
 * actividad de terminal.
 *
 * 3 niveles, persistidos en las mismas SharedPreferences que el resto de Ajustes
 * (`ConfigFragment.PREFS_NAME = "kairos_prefs"`, mismo mecanismo que el checkbox de Términos y
 * Condiciones):
 * - OFF: no-op total, sin overhead (ni siquiera abre el archivo).
 * - NORMAL: eventos de alto nivel (navegación entre tabs, inicio/fin de instalación de
 *   módulos, errores no capturados vía Thread.UncaughtExceptionHandler).
 * - FULL: todo lo de NORMAL + actividad de terminal (comandos ejecutados, apertura/cierre de
 *   sesiones) — ver TermuxTerminalSessionActivityClient.addNewSession()/onSessionFinished().
 *
 * Cobertura real de esta ronda (no reclamar más de lo que hay): TermuxApplication.onCreate()
 * (arranque + uncaught exceptions), TermuxActivity.switchFragment() (navegación de tabs),
 * ModuleController.installModule()/startModule()/stopModule() (ciclo de vida de módulos),
 * TermuxTerminalSessionActivityClient.addNewSession()/onSessionFinished() (sesiones de
 * terminal, solo nivel FULL). Puntos NO instrumentados todavía (a propósito, fuera de alcance
 * de esta ronda): comandos individuales tecleados dentro de una sesión ya abierta, eventos de
 * ciclo de vida de cada Fragment individual, network requests de módulos (Telegram, checkers de
 * versión). Ver docs/arquitectura/LOG_INTERNO_KAIROS_2026-08-26.md para el detalle completo.
 */
object KairosLogger {

    enum class Level(val label: String) {
        OFF("Nada / apagado"),
        NORMAL("Normal / básico"),
        FULL("Full / completo");

        companion object {
            fun fromOrdinalSafe(ordinal: Int): Level = entries.getOrElse(ordinal) { OFF }
        }
    }

    private const val PREFS_NAME = "kairos_prefs" // mismo archivo que ConfigFragment.PREFS_NAME
    private const val KEY_LEVEL = "pref_kairos_log_level"
    private const val LOG_FILE_NAME = "kairos_app.log"
    private const val MAX_LOG_SIZE_BYTES = 5L * 1024 * 1024 // 5MB — ver rotación en maybeRotate()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val logFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_logs").apply { mkdirs() }
            .resolve(LOG_FILE_NAME)

    private val oldLogFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_logs").resolve("$LOG_FILE_NAME.old")

    /** Lee el nivel configurado desde SharedPreferences — sin Context cacheado a propósito
     * (mismo patrón que el resto de la app: cada caller ya tiene un Context a mano en el
     * momento de loguear, no vale la pena un Application-context global solo para esto). */
    @JvmStatic
    fun getLevel(context: Context): Level {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Level.fromOrdinalSafe(prefs.getInt(KEY_LEVEL, Level.NORMAL.ordinal))
    }

    @JvmStatic
    fun setLevel(context: Context, level: Level) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LEVEL, level.ordinal).apply()
    }

    /**
     * API pública para el resto del código — no-op inmediato si el nivel configurado es OFF, o
     * si [level] pide más detalle del que el usuario configuró (ej. un log FULL cuando el
     * usuario eligió NORMAL). [level] default NORMAL: la mayoría de los call-sites son eventos
     * de alto nivel, no actividad de terminal.
     */
    @Synchronized
    @JvmStatic
    @JvmOverloads
    fun log(context: Context, tag: String, message: String, level: Level = Level.NORMAL) {
        val configured = getLevel(context)
        if (configured == Level.OFF) return
        if (level.ordinal > configured.ordinal) return
        try {
            maybeRotate()
            logFile.appendText("[${timeFormat.format(java.util.Date())}] [$tag] $message\n")
        } catch (_: Exception) {
            // El log es de diagnóstico — nunca debe interrumpir el flujo real de la app.
        }
    }

    /** Trunca (renombra a .old, sobreescribiendo el anterior) cuando el archivo supera
     * MAX_LOG_SIZE_BYTES — evita que crezca sin límite en un dispositivo con poco espacio. */
    private fun maybeRotate() {
        val file = logFile
        if (!file.exists() || file.length() < MAX_LOG_SIZE_BYTES) return
        try {
            val old = oldLogFile
            if (old.exists()) old.delete()
            file.renameTo(old)
        } catch (_: Exception) {
            // Best-effort — si la rotación falla, seguimos anexando al archivo actual en vez
            // de perder el log entero.
        }
    }

    /** Últimas [n] líneas — usado por el diálogo "Ver log" de ConfigFragment. Nunca lanza. */
    @JvmStatic
    fun readLastLines(n: Int = 200): String {
        return try {
            val file = logFile
            if (!file.exists()) return "(sin log todavía)"
            file.readLines().takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            "(error leyendo el log: ${e.message})"
        }
    }

    /** Ruta real del archivo — usado para compartirlo (Intent.ACTION_SEND) desde la UI.
     * Nombre distinto del accessor autogenerado de [logFile] a propósito — `getLogFile()`
     * choca en JVM con el getter que Kotlin ya genera para la property `logFile` (mismo
     * nombre+firma, "Platform declaration clash", confirmado por el compilador real). */
    @JvmStatic
    fun currentLogFile(): File = logFile

    /**
     * Instala un `Thread.UncaughtExceptionHandler` global que loguea el crash ANTES de delegar
     * al handler previo (TermuxCrashUtils, que ya escribe su propio log de crash y reinicia la
     * app) — nunca reemplaza ese comportamiento, solo agrega una línea a este log transversal
     * para que quede correlacionado con la navegación/módulos que llevaron al crash. Llamado
     * una sola vez desde TermuxApplication.onCreate().
     */
    @JvmStatic
    fun installUncaughtExceptionHandler(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log(
                    context,
                    "CRASH",
                    "Excepción no capturada en hilo '${thread.name}': ${throwable.javaClass.simpleName}: ${throwable.message}"
                )
            } catch (_: Exception) {
                // Nunca dejar que un fallo del logger tape la excepción original.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
