package com.termux.app.util

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — Engram es
 * un CLI puro (`engram <subcomando> ...`, confirmado leyendo los call-sites reales de
 * `EngramFragment.runEngramCommand()`: `context`/`stats`/`projects list`/`doctor`/`save <t> <m>`/
 * `search <q>`/`export <path>`/`import <path>`/`delete project <p>`) — sin ninguna API propia que
 * delegar, así que la paridad real es correr el binario, mismo mecanismo (`sh -c`) y mismo nivel
 * de riesgo ya aceptado por `sandbox.run`/`distro.run` (danger, min_level total).
 */
class EngramManager(private val runner: CommandRunner = ShellCommandRunner) {
    fun run(args: String, timeoutSec: Long = 60): PrivilegeResult {
        if (args.isBlank()) return PrivilegeResult(false, "faltan argumentos", 400)
        val (code, out) = runner.run(listOf("sh", "-c", "engram $args"), timeoutSec)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }
}
