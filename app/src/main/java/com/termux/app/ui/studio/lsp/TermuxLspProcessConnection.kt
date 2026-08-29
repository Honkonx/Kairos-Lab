package com.termux.app.ui.studio.lsp

import android.util.Log
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.InputStream
import java.io.OutputStream

private const val LOG_TAG = "KairosLsp"

/**
 * [StreamConnectionProvider] real para Estudio — arranca el language server como proceso hijo
 * de Termux vía `bash -c "<comando>"`, nunca invocando el binario directo por `ProcessBuilder`.
 * Es el mismo patrón "bash resuelve PATH bien, ProcessBuilder no" documentado por el skill
 * `kairos-termux-process-exec` y aplicado en TODO el resto de la app (ver
 * `com.termux.app.util.ProcessBuilderExt` — más de 10 bugs reales de "Cannot run program X"
 * confirmados por esa misma asimetría) — no hay motivo para que LSP sea la excepción.
 *
 * Patrón de conexión (proceso hijo + stdin/stdout como streams JSON-RPC) adaptado de
 * `referencia/ides/Xed-Editor-main` (`core/main/src/main/java/com/rk/lsp/ProcessConnection.kt`,
 * GPLv3 — solo se leyó el patrón de invocación de la librería `io.github.rosemoe:editor-lsp`,
 * ningún código Kotlin de ese proyecto se copió acá), simplificado: sin sandbox proot propio
 * (Kairos corre el server nativo en Termux directo, no en un contenedor) y sin panel de logs en
 * la UI (Estudio no tiene uno en este MVP — stderr del server va a Logcat con [LOG_TAG]).
 */
class TermuxLspProcessConnection(private val startCommand: String) : StreamConnectionProvider {

    private var process: Process? = null

    override val inputStream: InputStream
        get() = process?.inputStream
            ?: throw IllegalStateException("LSP process not running: $startCommand")

    override val outputStream: OutputStream
        get() = process?.outputStream
            ?: throw IllegalStateException("LSP process not running: $startCommand")

    // No es parte real de StreamConnectionProvider (confirmado con javap sobre el .class real
    // del AAR: solo declara start()/inputStream/outputStream/close()) — se deja como propiedad
    // propia sin `override`, útil para diagnóstico externo si hace falta más adelante.
    val isClosed: Boolean
        get() = process?.isAlive != true

    override fun start() {
        if (process?.isAlive == true) return
        val processBuilder = ProcessBuilder(TERMUX_BASH_PATH, "-c", startCommand)
        processBuilder.applyTermuxEnv()
        val started = processBuilder.start()
        process = started
        Thread {
            runCatching {
                started.errorStream.bufferedReader().forEachLine { line ->
                    Log.w(LOG_TAG, "[$startCommand] $line")
                }
            }
        }.start()
    }

    override fun close() {
        process?.destroy()
        process = null
    }
}
