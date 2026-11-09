package com.termux.app.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Salida de un comando ejecutado con algún nivel de privilegio. */
data class PrivilegeResult(val ok: Boolean, val output: String, val exitCode: Int = if (ok) 0 else 1)

/** Ejecuta un programa con argumentos y devuelve (código de salida, salida mezclada). Se inyecta para probar sin Android. */
fun interface CommandRunner {
    fun run(argv: List<String>, timeoutSec: Long): Pair<Int, String>
}

/**
 * Java resuelve el programa con el PATH de la app (Android), no con el de Termux: `tmux`, `adb` o `rish` sueltos dan
 * «No such file or directory» (visto en el teléfono). Si el programa existe en `$PREFIX/bin` se usa esa ruta.
 */
internal fun resolveTermuxBinary(argv: List<String>, prefixBin: String = com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH): List<String> {
    val head = argv.firstOrNull() ?: return argv
    if (head.contains('/')) return argv
    val candidate = File(prefixBin, head)
    return if (candidate.isFile) listOf(candidate.path) + argv.drop(1) else argv
}

/** Ejecutor real: ProcessBuilder con el entorno de Termux (ver skill kairos-termux-process-exec). */
object ShellCommandRunner : CommandRunner {
    override fun run(argv: List<String>, timeoutSec: Long): Pair<Int, String> {
        val pb = ProcessBuilder(resolveTermuxBinary(argv)).redirectErrorStream(true)
        pb.applyTermuxEnv()
        return try {
            val process = pb.start()
            var text = ""
            val collector = Thread { text = try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" } }
            collector.start()
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                collector.join(1000)
                return 124 to "tiempo agotado tras ${timeoutSec}s"
            }
            collector.join(2000)
            process.exitValue() to text
        } catch (e: Exception) {
            127 to (e.message ?: "no se pudo ejecutar")
        }
    }
}

/**
 * Una forma de ejecutar comandos con más poder que la app sola. El gateway las trata a todas igual (acción
 * `privilege.run`, riesgo «danger»): solo el nivel «total» las ejecuta sin preguntar. Cada proveedor sabe decir si
 * está disponible HOY; no se asume nada (Shizuku, root o ADB inalámbrico dependen del teléfono y de lo que la persona
 * haya activado).
 */
interface PrivilegeProvider {
    val id: String
    val title: String
    fun isAvailable(): Boolean
    fun run(command: String, timeoutSec: Long = 30): PrivilegeResult
}

private fun result(r: Pair<Int, String>) = PrivilegeResult(r.first == 0, r.second.trim().take(8000), r.first)

/** Sin privilegios extra: el mismo usuario de la app (`sh -c`). Siempre disponible; es la base de comparación. */
class AppShellProvider(private val runner: CommandRunner = ShellCommandRunner) : PrivilegeProvider {
    override val id = "app"
    override val title = "Usuario de la app"
    override fun isAvailable() = true
    override fun run(command: String, timeoutSec: Long) = result(runner.run(listOf("sh", "-c", command), timeoutSec))
}

/** Root real (`su`). Disponible solo si `su -c id` responde con uid=0. */
class RootProvider(private val runner: CommandRunner = ShellCommandRunner) : PrivilegeProvider {
    override val id = "root"
    override val title = "Root (su)"
    override fun isAvailable(): Boolean {
        val (code, out) = runner.run(listOf("su", "-c", "id"), 5)
        return code == 0 && out.contains("uid=0")
    }
    override fun run(command: String, timeoutSec: Long) = result(runner.run(listOf("su", "-c", command), timeoutSec))
}

/**
 * ADB inalámbrico contra este mismo teléfono: usa `adb` (paquete `android-tools` de Termux) conectado a [serial]. Si
 * el dispositivo no figura como `device` no está disponible; emparejarlo (depuración inalámbrica) lo hace la persona.
 */
class AdbLocalProvider(
    private val runner: CommandRunner = ShellCommandRunner,
    private val serial: String = "localhost:5555",
    /** Si se da, [run] reusa esta sesión viva en vez de un proceso `adb shell` nuevo por comando
     * (ver [AdbPersistentShell]) — `null` conserva el comportamiento de siempre, ya probado. */
    private val persistent: AdbPersistentShell? = null,
) : PrivilegeProvider {
    override val id = "adb"
    override val title = "ADB inalámbrico"
    override fun isAvailable(): Boolean {
        val (code, out) = runner.run(listOf("adb", "devices"), 8)
        return code == 0 && out.lineSequence().any { it.startsWith(serial) && it.trim().endsWith("device") }
    }
    override fun run(command: String, timeoutSec: Long) =
        persistent?.let { result(it.run(command, timeoutSec)) }
            ?: result(runner.run(listOf("adb", "-s", serial, "shell", command), timeoutSec))

    companion object {
        /**
         * El puerto de depuración inalámbrica es dinámico (Android 11+) — `AdbPairingRunner`
         * siempre conecta a `127.0.0.1:<connectPort>`, nunca a `localhost:5555` (eso solo es un
         * valor de ejemplo sin pairing hecho todavía). Sin esto, `isAvailable()` buscaba un
         * serial que `adb devices` nunca iba a mostrar tras un pairing real — auditoría
         *, 2026-10-26. Usar este factory en vez del constructor a secas en
         * cualquier lugar con `Context` disponible (ScreenMirrorManager, ConfigFragment, el
         * gateway) — el no-arg sigue existiendo para tests y como fallback sin pairing previo.
         */
        fun forContext(context: Context, runner: CommandRunner = ShellCommandRunner): AdbLocalProvider {
            val port = PermissionsNotifier.lastConnectPort(context)
            return AdbLocalProvider(runner, if (port.isNotBlank()) "127.0.0.1:$port" else "localhost:5555")
        }

        /**
         * Igual que [forContext], pero con sesión `adb shell` persistente ([AdbPersistentShell])
         * en vez de un proceso nuevo por comando — pensada para la terminal embebida pidiendo
         * varios comandos privilegiados seguidos (ver `` meta M2). No reemplaza
         * a [forContext] en [PrivilegeRegistry.default] todavía — opt-in explícito hasta
         * confirmarla en un dispositivo real.
         */
        fun forContextPersistent(context: Context): AdbLocalProvider {
            val port = PermissionsNotifier.lastConnectPort(context)
            val serial = if (port.isNotBlank()) "127.0.0.1:$port" else "localhost:5555"
            return AdbLocalProvider(serial = serial, persistent = AdbPersistentShell(serial))
        }
    }
}

/**
 * Shizuku mediante `rish` (el script que la app Shizuku permite exportar). Disponible si existe [rishPath] y responde.
 * No requiere ninguna librería extra en Kairos.
 */
class ShizukuRishProvider(
    private val runner: CommandRunner = ShellCommandRunner,
    private val rishPath: String = File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, "rish").path,
    private val exists: (String) -> Boolean = { File(it).isFile },
) : PrivilegeProvider {
    override val id = "shizuku"
    override val title = "Shizuku (rish)"
    override fun isAvailable(): Boolean {
        if (!exists(rishPath)) return false
        val (code, out) = runner.run(listOf("sh", rishPath, "-c", "id"), 8)
        return code == 0 && out.contains("uid=")
    }
    override fun run(command: String, timeoutSec: Long) = result(runner.run(listOf("sh", rishPath, "-c", command), timeoutSec))
}

/**
 * Sesión de `adb shell` persistente (un solo proceso vivo, reusado comando a comando) en vez de
 * `adb -s <serial> shell <comando>` por separado cada vez. Es el cimiento real hacia un "Kairos
 * propio tipo Shizuku" (ver `.claude/rules/kairos-portar-no-comunicar-con-app-externa.md` y
 * ``, meta M2): una sesión privilegiada que se mantiene viva, en vez de pagar el
 * costo de arrancar/conectar `adb` en cada comando — igual de importante para el caso real de uso
 * (terminal embebida de Kairos pidiendo permisos elevados seguido, no una vez suelta).
 *
 * **Alcance real de esta ronda**: solo la sesión persistente + su wiring opcional en
 * [AdbLocalProvider]. El servicio Binder propio (starter + consentimiento + exponer permisos de
 * Android, equivalente completo a Shizuku) sigue sin construir — es trabajo real de varias rondas
 * más, documentado como pendiente en ``, no fabricado acá sin poder verificarlo
 * en un dispositivo real.
 *
 * No verificado en dispositivo real todavía (requiere un pairing ADB real + confirmar que el
 * parsing del marcador de fin de comando funciona contra un shell real de Android) — ver
 * `.claude/rules/empirical-verification-before-fix.md`.
 */
class AdbPersistentShell(
    private val serial: String,
    private val runner: (List<String>) -> Process = { argv -> ProcessBuilder(resolveTermuxBinary(argv)).redirectErrorStream(false).also { it.applyTermuxEnv() }.start() },
) {
    @Volatile private var process: Process? = null
    private val lock = Any()

    private fun isAlive(p: Process): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun ensureAlive(): Process = process?.takeIf(::isAlive) ?: runner(listOf("adb", "-s", serial, "shell")).also { process = it }

    /** Corre [command] en la sesión viva; si murió, la reinicia una vez antes de fallar. */
    fun run(command: String, timeoutSec: Long = 30): Pair<Int, String> = synchronized(lock) {
        val marker = "__KAIROS_END_${System.nanoTime()}__"
        val p = try { ensureAlive() } catch (e: Exception) { return 127 to (e.message ?: "no se pudo abrir la sesión adb") }
        try {
            p.outputStream.write("$command; echo \"$marker:\$?\"\n".toByteArray())
            p.outputStream.flush()
        } catch (e: Exception) {
            process = null
            return 127 to (e.message ?: "la sesión adb se cerró")
        }
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            val available = try { p.inputStream.available() } catch (_: Exception) { 0 }
            if (available > 0) {
                val n = p.inputStream.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n))
                val idx = sb.indexOf(marker)
                if (idx >= 0) {
                    val exitCode = sb.substring(idx + marker.length).removePrefix(":").trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    return exitCode to sb.substring(0, idx).trim()
                }
            } else Thread.sleep(20)
        }
        process?.destroyForcibly(); process = null
        return 124 to "tiempo agotado tras ${timeoutSec}s (sesión persistente)"
    }

    fun close() { process?.destroy(); process = null }
}

/** Catálogo de proveedores y estado de cada uno. Más proveedores se añaden aquí sin tocar el resto. */
class PrivilegeRegistry(val providers: List<PrivilegeProvider>) {
    data class Status(val id: String, val title: String, val available: Boolean)

    fun status(): List<Status> = providers.map { Status(it.id, it.title, runCatching { it.isAvailable() }.getOrDefault(false)) }

    /** Ejecuta con el proveedor pedido; si no existe o no está disponible devuelve un fallo claro, nunca otro proveedor. */
    fun run(providerId: String, command: String, timeoutSec: Long = 30): PrivilegeResult {
        val provider = providers.firstOrNull { it.id == providerId } ?: return PrivilegeResult(false, "proveedor desconocido: $providerId", 404)
        if (!runCatching { provider.isAvailable() }.getOrDefault(false)) return PrivilegeResult(false, "${provider.title} no está disponible", 503)
        return provider.run(command, timeoutSec)
    }

    companion object {
        /** Sin `Context`: el `AdbLocalProvider` queda en su serial de ejemplo, nunca disponible tras un pairing real — ver `forContext()` más abajo. */
        fun default() = PrivilegeRegistry(listOf(AppShellProvider(), ShizukuRishProvider(), AdbLocalProvider(), RootProvider()))

        /** Resuelve el serial de ADB real (puerto de la última conexión guardada, ver `AdbLocalProvider.forContext`). Usar esta variante siempre que haya un `Context` a mano. */
        fun default(context: Context) = PrivilegeRegistry(listOf(AppShellProvider(), ShizukuRishProvider(), AdbLocalProvider.forContext(context), RootProvider()))
    }
}
