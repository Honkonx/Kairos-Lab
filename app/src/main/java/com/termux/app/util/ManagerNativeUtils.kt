package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Utilidades compartidas por los reemplazos nativos de kairos_manager.py (OpenClaw,
 * Hermes, Entorno, Backup) — antes cada acción viajaba Kotlin -> python3 -> JSON
 * stdout solo para envolver un tmux/pgrep/socket check o un cat de archivo; acá se
 * hace directo, sin el intérprete de por medio. Equivalentes a read_registry(),
 * tmux_has(), pgrep_f(), check_port() y run_cmd() de kairos_manager.py.
 */
object ManagerNativeUtils {

    val home: String = TermuxConstants.TERMUX_HOME_DIR_PATH

    fun readRegistry(): Map<String, String> {
        val file = File(home, ".android_server_registry")
        val map = mutableMapOf<String, String>()
        if (!file.exists()) return map
        try {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                    }
                }
            }
        } catch (_: Exception) { }
        return map
    }

    /** Mismo archivo de lock que ProjectsManager ya usaba en solitario (".android_server_registry.lock")
     *  — compartido ahora por RegistryLock para que cualquier read-modify-write sobre
     *  ~/.android_server_registry (ProjectsManager/TunnelManager/EntornoNative) se serialice
     *  contra el mismo lock, no uno por manager (consolidación 2026-08-13, ver auditoría). */
    val registryLockFile: File get() = File(home, ".android_server_registry.lock")

    // Rutas absolutas en vez de nombre relativo — bug real confirmado, usado por
    // EntornoNative.kt entre otros, ya con bugs reales confirmados y parcheados con
    // timeouts en rondas previas.
    fun tmuxHas(session: String): Boolean =
        runExec(listOf(TERMUX_TMUX_PATH, "has-session", "-t", session), 5).first == 0

    fun pgrepF(pattern: String): Boolean =
        runExec(listOf(TERMUX_PGREP_PATH, "-f", pattern), 5).first == 0

    /** "pgrep -x" — match por nombre exacto de proceso (a diferencia de pgrepF, que hace
     *  match por patrón/substring vía -f). Antes vivía duplicada como método privado en
     *  RemoteManager (consolidación 2026-08-13, ver auditoría). */
    fun pgrepX(name: String): Boolean =
        runExec(listOf(TERMUX_PGREP_PATH, "-x", name), 5).first == 0

    /**
     * Chequeo de "actividad real" de un proceso vía delta de `/proc/<pid>/io` entre dos
     * lecturas separadas por [sampleMs] (ronda 2026-09-09, hallazgo de
     * referencia/termux/RDeX-main/smb_tui.py:33-52) — a diferencia de un simple "¿el PID
     * existe?" (que solo dice running/stopped), esto distingue un proceso VIVO pero
     * colgado/inactivo de uno con transferencia de datos real en curso (descarga de un
     * modelo GGUF, `pull` de una imagen grande, un futuro módulo Nube/SMB). Devuelve `false`
     * si el archivo no es legible (proceso muerto, o /proc/<pid>/io restringido por el mismo
     * tipo de límite de visibilidad ya documentado para pgrep entre procesos de distinto
     * dominio SELinux — ver DbFragment.isAlive()) — mismo criterio "false honesto" que el
     * resto de checks de este archivo, nunca lanza.
     */
    fun isProcessBusy(pid: Int, sampleMs: Long = 500): Boolean {
        val ioFile = File("/proc/$pid/io")
        fun readBytes(): Long? = try {
            var sum = 0L
            var found = false
            ioFile.forEachLine { line ->
                if (line.startsWith("rchar:") || line.startsWith("wchar:")) {
                    sum += line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    found = true
                }
            }
            if (found) sum else null
        } catch (_: Exception) { null }
        val before = readBytes() ?: return false
        try { Thread.sleep(sampleMs) } catch (_: InterruptedException) { return false }
        val after = readBytes() ?: return false
        return after > before
    }

    fun checkPort(port: Int, host: String = "127.0.0.1"): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Núcleo compartido de runExec()/runExecWithStdin()/runShell() — las 3 solo diferían en
     * cómo se arma el [ProcessBuilder] y si hay [stdin] que escribir; el resto (lectura de
     * stdout/stderr en threads separados, orden waitFor→destroyForcibly→join, manejo de
     * timeout) era código idéntico copiado 3 veces. Ya causó un bug real de divergencia: el
     * fix de orden destroyForcibly()-antes-de-join() (2026-08-24, "claude doctor" colgado
     * ~2min pese al timeout de 30s) se aplicó a runExec()/runShell()
     * pero se había olvidado en runExecWithStdin() en la ronda siguiente — con un solo helper,
     * el próximo fix de este tipo solo hace falta aplicarlo una vez.
     *
     * Bug real evitado en la lectura de streams (mismo commit 2026-08-24): sin try/catch
     * dentro de cada Thread lector, destroyForcibly() cerrando los streams mientras el Thread
     * está bloqueado en readText() propaga una InterruptedIOException sin capturar fuera de un
     * Thread sin manejador propio — mata TODO el proceso de la app (confirmado en logcat:
     * FATAL EXCEPTION).
     */
    private fun runProcessWithTimeout(pb: ProcessBuilder, timeoutSeconds: Long, stdin: String? = null): Triple<Int, String, String> {
        return try {
            pb.applyTermuxEnv()
            val process = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { try { out.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val errThread = Thread { try { err.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            outThread.start(); errThread.start()
            if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()); it.flush() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            // destroyForcibly() DEBE correr ANTES de outThread.join()/errThread.join(), no
            // después — esos threads bloquean en readText() hasta que el proceso cierra sus
            // streams; si el proceso sigue vivo (no terminó dentro del timeout), join() espera
            // para siempre porque el stream nunca se cierra. Destruir primero libera los
            // threads lectores de inmediato.
            if (!finished) process.destroyForcibly()
            outThread.join(); errThread.join()
            if (!finished) {
                Triple(1, "", "timeout")
            } else {
                Triple(process.exitValue(), out.toString().trim(), err.toString().trim())
            }
        } catch (e: Exception) {
            Triple(1, "", e.message ?: "error")
        }
    }

    /**
     * Corre un binario por argv directo (sin pasar por un shell) — equivalente a
     * run_cmd() de kairos_manager.py cuando el comando no necesita interpretación de
     * shell (pipes, &&, comillas). Preferido sobre runShell() siempre que se pueda,
     * evita cualquier problema de quoting (shlex.quote en la versión Python).
     */
    fun runExec(args: List<String>, timeoutSeconds: Long = 30): Triple<Int, String, String> =
        runProcessWithTimeout(ProcessBuilder(args), timeoutSeconds)

    /**
     * Igual que [runExec] pero escribe [stdin] al proceso y cierra su stream de entrada antes
     * de esperar la salida — para binarios como `vncpasswd` que solo aceptan la contraseña de
     * forma interactiva por stdin, nunca por argv (ver EntornoNative.vncSetPassword(), humano202).
     */
    fun runExecWithStdin(args: List<String>, stdin: String, timeoutSeconds: Long = 15): Triple<Int, String, String> =
        runProcessWithTimeout(ProcessBuilder(args), timeoutSeconds, stdin)

    /**
     * Corre un comando vía `bash -c` — solo para los pocos casos que de verdad
     * necesitan interpretación de shell (pipes, &&, `&` de backgrounding). Preferir
     * runExec() cuando el comando es un binario + argumentos simples.
     */
    fun runShell(cmd: String, timeoutSeconds: Long = 30): Triple<Int, String, String> =
        runProcessWithTimeout(ProcessBuilder(TERMUX_BASH_PATH, "-c", cmd), timeoutSeconds)

    fun humanSize(bytes: Long): String {
        var b = bytes.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (b < 1024) return String.format("%.1f%s", b, unit)
            b /= 1024
        }
        return String.format("%.1fTB", b)
    }

    /**
     * "Leer archivo KEY=VALUE, buscar prefijo, reemplazar o agregar" — algoritmo
     * compartido por HermesNative.configSetProvider(), OllamaApiClient.writeConfigValue()
     * y EntornoNative.updateRegistryValue() (consolidación 2026-08-13, ver auditoría de
     * código). No preserva comentarios/formato más allá de las líneas ya presentes —
     * mismo alcance que las 3 implementaciones originales que reemplaza. No pensado para
     * archivos con múltiples líneas por la misma clave — usa la primera coincidencia,
     * igual que el código original.
     */
    fun upsertKeyValueLine(file: File, key: String, value: String) {
        val prefix = "$key="
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val idx = lines.indexOfFirst { it.trim().startsWith(prefix) }
        val newLine = "$key=$value"
        if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
        file.writeText(lines.joinToString("\n") + "\n")
    }

    // Última muestra de /proc/stat (línea agregada "cpu ") para el delta de cpuUsagePercent() —
    // una sola lectura de /proc/stat solo da totales acumulados desde el boot, hace falta la
    // diferencia entre dos lecturas separadas en el tiempo para un % instantáneo real. Mismo
    // patrón que termux-status (AhmarZaidi/termux-status, status.py línea ~173, "CPU calculation
    // using /proc/stat alternative"), ver docs/referencias/interfaz/REFERENCIA_TERMUX_STATUS.md
    // — portado acá como helper compartido en vez de reimplementarlo en cada caller (pedido
    // 2026-08-25, panel de métricas en vivo de la terminal adaptada).
    @Volatile
    private var lastCpuSample: LongArray? = null

    /**
     * % de CPU del dispositivo (no por-proceso) desde la última llamada a esta misma función.
     * Devuelve null en la primera llamada de una sesión (no hay muestra previa con la que
     * calcular un delta) — el caller debe tolerar null y reintentar en el siguiente ciclo.
     */
    fun cpuUsagePercent(): Int? {
        val fields = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
                ?.trim()?.split(Regex("\\s+"))
        } catch (_: Exception) {
            null
        } ?: return null
        if (fields.size < 5 || fields[0] != "cpu") return null
        val sample = try {
            LongArray(fields.size - 1) { i -> fields[i + 1].toLong() }
        } catch (_: Exception) {
            return null
        }
        val prev = lastCpuSample
        lastCpuSample = sample
        if (prev == null || prev.size != sample.size) return null

        val prevIdle = prev[3] + (prev.getOrElse(4) { 0L })
        val idle = sample[3] + (sample.getOrElse(4) { 0L })
        val prevTotal = prev.sum()
        val total = sample.sum()

        val totalDelta = total - prevTotal
        val idleDelta = idle - prevIdle
        if (totalDelta <= 0) return null
        return (((totalDelta - idleDelta).toDouble() / totalDelta) * 100).toInt().coerceIn(0, 100)
    }

    private data class RamUsage(val usedMb: Long, val totalMb: Long, val percent: Int)

    /** % de RAM usada del dispositivo (MemTotal - MemAvailable), vía /proc/meminfo. */
    private fun readRamUsage(): RamUsage? {
        var totalKb = 0L
        var availableKb = 0L
        try {
            File("/proc/meminfo").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") -> totalKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        line.startsWith("MemAvailable:") -> availableKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (totalKb <= 0) return null
        val usedKb = (totalKb - availableKb).coerceAtLeast(0)
        val pct = ((usedKb.toDouble() / totalKb) * 100).toInt().coerceIn(0, 100)
        return RamUsage(usedKb / 1024, totalKb / 1024, pct)
    }

    /** "CPU 12% · RAM 1024/4096 MB (25%)" listo para mostrar, o null si aún no hay muestra de CPU. */
    fun systemMetricsSummary(): String? {
        val cpu = cpuUsagePercent() ?: return null
        val ram = readRamUsage() ?: return null
        return "CPU ${cpu}% · RAM ${ram.usedMb}/${ram.totalMb} MB (${ram.percent}%)"
    }
}
