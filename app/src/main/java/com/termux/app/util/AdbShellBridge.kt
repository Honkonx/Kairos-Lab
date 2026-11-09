package com.termux.app.util

import android.content.Context
import java.io.File

/**
 * Cierre real de "terminar Shizuku" (2026-10-31, +
 * docs/arquitectura/PLAN_SHIZUKU_IA_HOMELAB_ADB_2026-10-31.md): activa automáticamente el
 * privilegio elevado de Kairos apenas se confirma un emparejamiento ADB inalámbrico (antes había
 * que pedirlo aparte), y expone un comando real en la terminal de Kairos (`kairos-shell`) —
 * equivalente a lo que `rish` hace para Shizuku, pero propio, sin ninguna app externa.
 *
 * **Nombre honesto, no "root"**: ADB inalámbrico da identidad SHELL (uid 2000) — más que una app
 * normal, pero NO root real (uid 0, que requiere `su`/bootloader desbloqueado). `kairos-shell` es
 * el nombre del comando a propósito, para no prometer algo que esta vía no da — `RootProvider`
 * (PrivilegeProviders.kt) sigue siendo el camino real para root de verdad, si el dispositivo lo
 * tiene.
 */
object AdbShellBridge {

    private const val REGISTRY_KEY_PORT = "adb.shell.connect_port"

    /** Llamar apenas `onPaired()` confirma un emparejamiento exitoso (ver
     *  `AdbPairingDialogFlow.kt`/`AdbPairingOverlayService.kt`). Dos efectos: deja el puerto
     *  vigente escrito en el registry compartido (lo lee `kairos-shell` desde bash), y
     *  (re)instala/actualiza el script en `$PREFIX/bin/`. */
    fun activateAfterPairing(context: Context) {
        val port = PermissionsNotifier.lastConnectPort(context)
        if (port.isBlank()) return
        RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
            val file = registryFile()
            val current = readRegistry(file).toMutableMap()
            current[REGISTRY_KEY_PORT] = port
            file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
        installShellCommand()
    }

    private fun registryFile() = File(ManagerNativeUtils.home, ".android_server_registry")

    private fun readRegistry(file: File): Map<String, String> =
        (if (file.exists()) file.readLines() else emptyList())
            .mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) null
                else t.indexOf('=').takeIf { it > 0 }?.let { eq -> t.substring(0, eq).trim() to t.substring(eq + 1).trim() }
            }.toMap()

    /**
     * Watcher propio de reconexión (pedido pendiente de esta ronda): antes
     * `kairos-shell` solo reconectaba de forma PEREZOSA (al invocarlo a mano) si la sesión ADB
     * inalámbrica se había caído — mDNS puede no estar disponible en todos los routers/Android,
     * confirmado real como el único mecanismo de reconexión existente. Esta función hace el MISMO
     * chequeo (`adb shell true` → si falla, `adb connect`) pero pensada para correr sola y
     * periódica (ver llamador real en `GatewayForegroundService`), para que la sesión esté viva
     * SIEMPRE, no solo cuando alguien ejecuta `kairos-shell` a mano. Devuelve `true` si hay una
     * sesión viva al terminar (ya lo estaba, o se logró reconectar), `false` si no había puerto
     * emparejado o la reconexión falló.
     */
    fun reconnectIfNeeded(): Boolean {
        val port = readRegistry(registryFile())[REGISTRY_KEY_PORT]?.trim()
        if (port.isNullOrBlank()) return false
        val serial = "127.0.0.1:$port"
        return try {
            if (runAdb("-s", serial, "shell", "true")) return true
            runAdb("connect", serial)
        } catch (_: Exception) {
            false
        }
    }

    private fun runAdb(vararg args: String): Boolean {
        val pb = ProcessBuilder(listOf("adb") + args)
        pb.applyTermuxEnv()
        pb.redirectErrorStream(true)
        val proc = pb.start()
        return proc.waitFor() == 0
    }

    /** Escribe (o reemplaza) `$PREFIX/bin/kairos-shell` — ver el propio script para el
     *  comportamiento real (lee el puerto del registry, reconecta sola si la sesión se cayó,
     *  reusando el mismo criterio ya confirmado para ADB inalámbrico: Android no pide el código
     *  de emparejamiento de nuevo mientras la Depuración inalámbrica siga activa, solo hace falta
     *  `adb connect` de nuevo si cambió el puerto/se cayó la conexión). */
    private fun installShellCommand() {
        try {
            val binDir = File(TERMUX_PREFIX_PATH, "bin")
            if (!binDir.isDirectory) return // bootstrap de Termux todavía no extraído
            val script = File(binDir, "kairos-shell")
            script.writeText(SCRIPT_CONTENT)
            script.setExecutable(true, false)
        } catch (_: Exception) {
            // Best-effort: si falla (permisos, bootstrap incompleto), el usuario sigue teniendo
            // la vía de Config > Permisos > ADB para emparejar/reintentar a mano.
        }
    }

    private val SCRIPT_CONTENT = """
        #!$TERMUX_BASH_PATH
        # kairos-shell -- ejecuta un comando con identidad SHELL (uid 2000, via ADB inalambrico
        # ya emparejado en Kairos) -- NO es root real (uid 0). Generado por Kairos
        # (AdbShellBridge.kt), se reinstala solo cada vez que se confirma un emparejamiento nuevo.
        REG="${'$'}HOME/.android_server_registry"
        PORT=${'$'}(grep '^$REGISTRY_KEY_PORT=' "${'$'}REG" 2>/dev/null | cut -d= -f2)
        if [ -z "${'$'}PORT" ]; then
          echo "kairos-shell: todavia no hay un emparejamiento ADB confirmado -- Config > Permisos > ADB inalambrico en Kairos." >&2
          exit 1
        fi
        SERIAL="127.0.0.1:${'$'}PORT"
        if ! adb -s "${'$'}SERIAL" shell true >/dev/null 2>&1; then
          # La sesion se cayo (reinicio, cambio de red) -- Android no vuelve a pedir el codigo de
          # emparejamiento mientras la Depuracion inalambrica siga activa, solo hace falta
          # reconectar (ver docs/arquitectura/PLAN_SHIZUKU_IA_HOMELAB_ADB_2026-10-31.md punto 1).
          adb connect "${'$'}SERIAL" >/dev/null 2>&1
        fi
        if [ "${'$'}#" -eq 0 ]; then
          exec adb -s "${'$'}SERIAL" shell
        else
          exec adb -s "${'$'}SERIAL" shell "${'$'}@"
        fi
    """.trimIndent() + "\n"
}
