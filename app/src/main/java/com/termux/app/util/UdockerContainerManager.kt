package com.termux.app.util

import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — udocker
 * NO solo corre imágenes efímeras (ver `DistroManager.runImage()`, `udocker run --rm -it`): tiene
 * CONTENEDORES NOMBRADOS persistentes (`udocker create`/`udocker ps`/`udocker run <nombre>`,
 * estado real entre llamadas), confirmado leyendo `UdockerFragment.kt`. Mismos comandos reales:
 * `EntornoNative.udockerContainersList()` (estado), `udocker run <contenedor> /bin/sh -lc
 * <comando>` (correr algo, sin `-it`), `udocker inspect`/`udocker rm`.
 */
object UdockerContainerManager {
    private val runner: CommandRunner = ShellCommandRunner

    fun list(): JSONObject = EntornoNative.udockerContainersList()

    private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun run(container: String, command: String, timeoutSec: Long = 60): PrivilegeResult {
        if (!NAME_PATTERN.matches(container)) return PrivilegeResult(false, "nombre de contenedor inválido", 400)
        if (command.isBlank()) return PrivilegeResult(false, "comando vacío", 400)
        val (code, out) = runner.run(
            listOf(
                TERMUX_BASH_PATH, "-c",
                "export UDOCKER_USE_PROOT_EXECUTABLE=\"$TERMUX_PREFIX_PATH/bin/proot\"; exec \"$TERMUX_UDOCKER_PATH\" run '$container' /bin/sh -lc '$command'"
            ),
            timeoutSec
        )
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    fun inspect(container: String): PrivilegeResult {
        if (!NAME_PATTERN.matches(container)) return PrivilegeResult(false, "nombre de contenedor inválido", 400)
        val (code, out) = runner.run(listOf(TERMUX_UDOCKER_PATH, "inspect", container), 20)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    fun remove(container: String, confirm: Boolean): PrivilegeResult {
        if (!confirm) return PrivilegeResult(false, "hace falta confirm=true explícito para borrar un contenedor", 400)
        if (!NAME_PATTERN.matches(container)) return PrivilegeResult(false, "nombre de contenedor inválido", 400)
        val (code, out) = runner.run(listOf(TERMUX_UDOCKER_PATH, "rm", container), 20)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }
}
