package com.termux.app.ui.studio.termux

import android.os.Handler
import android.os.Looper
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import java.io.File

/**
 * Capa de ejecución de comandos real del IDE — corre binarios de la sesión de Termux/Kairos
 * DENTRO del mismo proceso de la app (Kairos ya vive en el proceso `com.termux`, así que un
 * `ProcessBuilder` directo puede lanzar `bash`/`git`/`./gradlew` del prefix de Termux sin el
 * mecanismo externo `RUN_COMMAND` que necesitaba la versión standalone de este IDE).
 *
 * Es el mismo helper compartido que usa el resto de Kairos (`ProcessBuilderExt.applyTermuxEnv`)
 * — arranca `$PREFIX/bin/bash -c "<comando>"` con HOME/PREFIX/PATH/LD_LIBRARY_PATH/TMPDIR/SHELL
 * ya seteados, igual que una sesión interactiva real de Termux. La ruta absoluta de bash
 * (`TERMUX_BASH_PATH`) evita la asimetría documentada en `ProcessBuilderExt.kt` donde la
 * resolución por nombre relativo falla en dispositivos reales.
 *
 * API síncrona ([runShellCommand]) para scripts cortos (git status/log), y variante
 * async/streaming ([runShellCommandAsync]/[runShellCommandStreaming]) que postea la salida
 * línea a línea al main looper — usada por la terminal del IDE y por el log de compilación
 * (gradle es de ejecución larga y el usuario necesita ver el progreso).
 */
object TermuxBridge {

    /** Resultado completo de un comando: salida estándar, error estándar y código de salida.
     * El código -1 significa que el proceso ni siquiera pudo arrancar (ej. Termux no instalado
     * o binario roto) — el detalle queda en [stderr]. */
    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Corre [command] de forma bloqueante y devuelve el resultado completo. Nunca lanza —
     * un fallo de arranque del proceso se reporta como `CommandResult(stderr=<motivo>, exitCode=-1)`. */
    fun runShellCommand(command: String, workingDirectory: String? = null): CommandResult {
        return try {
            val process = startProcess(command, workingDirectory)

            // Leer ambos streams en threads paralelos: si un stream llena el pipe buffer del
            // SO (64KB) antes de que se lea, el proceso se cuelga esperando a que drene — el
            // clásico deadlock de ProcessBuilder con salida grande (ej. gradle, git log largo).
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
            val stderrThread = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
            stdoutThread.start()
            stderrThread.start()
            stdoutThread.join()
            stderrThread.join()
            val exitCode = process.waitFor()

            CommandResult(stdout.toString(), stderr.toString(), exitCode)
        } catch (e: Exception) {
            CommandResult("", e.message ?: "no se pudo ejecutar el comando", -1)
        }
    }

    /** Corre [command] en un thread de background y entrega el resultado en el main looper. */
    fun runShellCommandAsync(
        command: String,
        workingDirectory: String? = null,
        onResult: (CommandResult) -> Unit
    ) {
        Thread {
            val result = runShellCommand(command, workingDirectory)
            mainHandler.post { onResult(result) }
        }.start()
    }

    /**
     * Corre [command] en background entregando cada línea de salida (stdout + stderr fusionados,
     * en orden de llegada) en vivo al main looper vía [onLine], y al terminar [onDone] con el
     * código de salida. Pensado para ejecución larga donde el usuario debe ver el progreso
     * (compilación gradle, instalaciones).
     */
    fun runShellCommandStreaming(
        command: String,
        workingDirectory: String? = null,
        onLine: (String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ) {
        Thread {
            try {
                val process = startProcess(command, workingDirectory)

                val reader = { stream: java.io.InputStream ->
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) {
                                mainHandler.post { onLine(line) }
                            }
                        }
                    }
                }
                val stdoutThread = Thread { reader(process.inputStream) }
                val stderrThread = Thread { reader(process.errorStream) }
                stdoutThread.start()
                stderrThread.start()
                stdoutThread.join()
                stderrThread.join()
                val exitCode = process.waitFor()

                mainHandler.post { onDone(exitCode) }
            } catch (e: Exception) {
                val detail = e.message ?: "no se pudo ejecutar el comando"
                mainHandler.post { onLine(detail) }
                mainHandler.post { onDone(-1) }
            }
        }.start()
    }

    private fun startProcess(command: String, workingDirectory: String?): Process {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
        pb.applyTermuxEnv()
        if (!workingDirectory.isNullOrBlank()) {
            pb.directory(File(workingDirectory))
        }
        return pb.start()
    }
}