package com.termux.app.util

import java.io.File
import java.util.UUID

/**
 * "Sandbox" para Termux NATIVO (2026-10-16, ver meta 9: "sandbox en termux
 * nativo, udocker y las proot distro") — las otras dos partes del pedido ya están resueltas por
 * DISEÑO: `udocker.run`/`distro.run` (GatewayDistroOps.kt) ya corren DENTRO de un contenedor/rootfs
 * aislado, eso es exactamente lo que udocker y proot-distro existen para dar. Termux nativo no
 * tiene ningún aislamiento real de filesystem/namespace (no hay chroot/namespaces propios sin
 * root) — lo que SÍ se puede dar, honesto y sin inventar una capacidad que no existe, es un HOME
 * y un directorio de trabajo DESCARTABLES: el comando corre con `HOME`/`TMPDIR`/`PWD` apuntando a
 * una carpeta nueva y vacía (nunca al HOME real de Termux), así que no puede leer ni pisar nada
 * del HOME real por accidente ni dejar basura ahí — no es aislamiento de seguridad (un comando
 * mal intencionado con udocker/root real podría igual escapar), es aislamiento de ESTADO.
 */
class SandboxManager(
    private val runner: CommandRunner = ShellCommandRunner,
    private val sandboxRoot: File = File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_sandbox"),
) {
    /**
     * Crea una carpeta nueva y vacía bajo [sandboxRoot], corre [command] ahí con `HOME`/`PWD`
     * apuntando a esa carpeta (vía `env HOME=... sh -c ...`, reusando el PATH/LD_LIBRARY_PATH
     * reales de Termux de [ShellCommandRunner.run] — sin esos dos el intérprete ni arrancaría).
     * [keep] decide si la carpeta se borra al terminar (por defecto no — sirve para inspeccionar
     * qué dejó el comando, igual que un contenedor con `--rm` vs sin él).
     */
    fun run(command: String, timeoutSec: Long = 60, keep: Boolean = false): PrivilegeResult {
        if (command.isBlank()) return PrivilegeResult(false, "comando vacío", 400)
        val dir = File(sandboxRoot, UUID.randomUUID().toString().take(12))
        if (!dir.mkdirs()) return PrivilegeResult(false, "no se pudo crear la carpeta del sandbox", 500)
        val (code, out) = try {
            runner.run(listOf("env", "HOME=${dir.path}", "TMPDIR=${dir.path}", "sh", "-c", "cd '${dir.path}' && $command"), timeoutSec)
        } finally {
            if (!keep) dir.deleteRecursively()
        }
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500).let {
            if (keep) PrivilegeResult(it.ok, it.output + "\n\n[sandbox conservado en ${dir.path}]", it.exitCode) else it
        }
    }
}
