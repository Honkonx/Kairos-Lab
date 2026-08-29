package com.termux.app.ui.studio.git

import com.termux.app.ui.studio.termux.TermuxBridge

/**
 * Wrapper real sobre [TermuxBridge] para operaciones de `git` -- corre el binario `git` que ya
 * viene instalado en la sesión de Termux/Kairos (paquete `git` de `pkg`, estándar en cualquier
 * stack de Termux/Kairos, ver `modulos/` y `termux-ai-stack-dev/scripts/` de la app principal
 * para otros módulos que asumen lo mismo). Kairos IDE no trae su propio `git` -- delega, igual
 * que [com.termux.app.ui.studio.build.BuildLogActivity] delega en el JDK/Gradle que el usuario ya tenga
 * instalado ahí.
 *
 * Cada método arma un comando `git` real (`git -C "<path>" ...`) y lo despacha vía
 * [TermuxBridge.runShellCommandAsync], que lo corre en background dentro del propio proceso
 * (in-process, ya no vía RUN_COMMAND/broadcast) y entrega el resultado por callback en el main
 * looper. Se ejecuta un solo comando en vuelo por vez (ver `GitPanelActivity`, que deshabilita
 * los botones mientras hay un comando corriendo).
 */
object GitBridge {

    /** Resultado crudo de correr un comando `git` -- éxito o error honesto, nunca un crash. */
    sealed class GitResult<out T> {
        data class Success<T>(val value: T) : GitResult<T>()
        /** [message] ya está listo para mostrar al usuario -- incluye stderr real de `git`
         * cuando lo hay, o el motivo de por qué ni se pudo despachar el comando (Kairos no
         * instalado, permiso no concedido). */
        data class Failure(val message: String) : GitResult<Nothing>()
    }

    data class FileChange(
        val indexStatus: Char,
        val worktreeStatus: Char,
        val path: String,
        val statusLabel: String
    )

    data class StatusResult(
        val branch: String,
        val changes: List<FileChange>
    )

    data class LogEntry(
        val shortHash: String,
        val date: String,
        val subject: String
    )

    private const val LOG_FORMAT = "%h|%ad|%s"

    fun status(projectPath: String, callback: (GitResult<StatusResult>) -> Unit) {
        // -c core.quotePath=false: sin esto, git C-quotea/escapa (octal) cualquier nombre de
        // archivo con bytes no-ASCII (ej. "café.txt" -> "caf\303\251.txt"), y parseStatusLine
        // mostraría el nombre escapado tal cual en vez del real. No afecta nombres con espacios
        // (esos nunca se quotean por defecto, ya soportados).
        val command = "git -C ${quote(projectPath)} -c core.quotePath=false status --porcelain=v1 --branch"
        runGit(command) { raw ->
            callback(raw.map(::parseStatus))
        }
    }

    fun currentBranch(projectPath: String, callback: (GitResult<String>) -> Unit) {
        val command = "git -C ${quote(projectPath)} rev-parse --abbrev-ref HEAD"
        runGit(command) { raw ->
            callback(raw.map { it.trim() })
        }
    }

    fun log(projectPath: String, limit: Int = 20, callback: (GitResult<List<LogEntry>>) -> Unit) {
        val command = "git -C ${quote(projectPath)} log -n $limit " +
            "--date=short --pretty=format:${quote(LOG_FORMAT)}"
        runGit(command) { raw ->
            callback(raw.map(::parseLog))
        }
    }

    fun diff(projectPath: String, file: String? = null, callback: (GitResult<String>) -> Unit) {
        val fileArgument = if (file.isNullOrBlank()) "" else " -- ${quote(file)}"
        val command = "git -C ${quote(projectPath)} diff$fileArgument"
        runGit(command) { raw ->
            callback(raw.map { it.ifBlank { "(sin cambios)" } })
        }
    }

    fun commit(projectPath: String, message: String, callback: (GitResult<String>) -> Unit) {
        if (message.isBlank()) {
            callback(GitResult.Failure("El mensaje de commit no puede estar vacío."))
            return
        }
        val command = "git -C ${quote(projectPath)} add -A && " +
            "git -C ${quote(projectPath)} commit -m ${quote(message)}"
        runGit(command) { raw ->
            callback(raw.map { it.ifBlank { "Commit creado." } })
        }
    }

    fun push(projectPath: String, callback: (GitResult<String>) -> Unit) {
        val command = "git -C ${quote(projectPath)} push"
        runGit(command) { raw ->
            callback(raw.map { output -> output.ifBlank { "Push completado." } })
        }
    }

    fun pull(projectPath: String, callback: (GitResult<String>) -> Unit) {
        val command = "git -C ${quote(projectPath)} pull"
        runGit(command) { raw ->
            callback(raw.map { output -> output.ifBlank { "Pull completado." } })
        }
    }

    // ── Núcleo async: un comando, un receiver efímero, un callback ─────────────────────

    private data class RawOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun <T> GitResult<RawOutput>.map(transform: (String) -> T): GitResult<T> = when (this) {
        is GitResult.Failure -> GitResult.Failure(message)
        is GitResult.Success -> {
            if (value.exitCode != 0) {
                GitResult.Failure(honestErrorMessage(value))
            } else {
                GitResult.Success(transform(value.stdout))
            }
        }
    }

    /** Mensaje honesto para el usuario: usa stderr real de `git` cuando lo hay (ej. "not a git
     * repository", "git: command not found" del propio bash) en vez de un genérico "algo salió
     * mal". */
    private fun honestErrorMessage(output: RawOutput): String {
        val detail = output.stderr.ifBlank { output.stdout }.trim()
        return if (detail.isBlank()) {
            "git terminó con código de error ${output.exitCode}."
        } else {
            detail
        }
    }

    private fun runGit(command: String, onResult: (GitResult<RawOutput>) -> Unit) {
        TermuxBridge.runShellCommandAsync(command) { result ->
            onResult(GitResult.Success(RawOutput(result.stdout, result.stderr, result.exitCode)))
        }
    }

    // ── Parsing de salida real de `git` ─────────────────────────────────────────────────

    /** `git status --porcelain=v1 --branch`: primera línea `## rama...tracking`, resto
     * `XY archivo` (o `XY origen -> destino` para renombres). Formato estable, pensado para
     * scripting -- por eso se usa en vez del formato "humano" de `git status` a secas. */
    private fun parseStatus(output: String): StatusResult {
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val branchLine = lines.firstOrNull { it.startsWith("## ") }
        val branch = branchLine
            ?.removePrefix("## ")
            ?.substringBefore("...")
            ?.substringBefore(" ")
            ?: "(desconocida)"

        val changes = lines
            .filterNot { it.startsWith("## ") }
            .mapNotNull(::parseStatusLine)

        return StatusResult(branch = branch, changes = changes)
    }

    private fun parseStatusLine(line: String): FileChange? {
        if (line.length < 4) return null
        val indexStatus = line[0]
        val worktreeStatus = line[1]
        val rawPath = line.substring(3)
        // Renombres: "old -> new" -- nos quedamos con el destino, que es lo que existe hoy.
        val path = if (rawPath.contains(" -> ")) rawPath.substringAfter(" -> ") else rawPath
        val label = statusLabelFor(indexStatus, worktreeStatus)
        return FileChange(indexStatus, worktreeStatus, path, label)
    }

    // Combos de "unmerged" que `git status --porcelain=v1` puede reportar durante un merge/rebase
    // con conflictos (ver `git help status`, sección "Unmerged"): DD, AU, UD, UA, DU, AA, UU.
    // Antes de este fix, statusLabelFor no los distinguía -- DD caía en la rama 'D' ("Eliminado")
    // y AA caía en la rama 'A' ("Nuevo"), mostrando una etiqueta engañosa para un archivo en
    // conflicto real que necesita resolverse antes de poder commitear.
    private val conflictCombos = setOf(
        "DD", "AU", "UD", "UA", "DU", "AA", "UU"
    )

    private fun statusLabelFor(indexStatus: Char, worktreeStatus: Char): String = when {
        "$indexStatus$worktreeStatus" in conflictCombos -> "Conflicto"
        indexStatus == '?' && worktreeStatus == '?' -> "Nuevo"
        indexStatus == 'R' || worktreeStatus == 'R' -> "Renombrado"
        indexStatus == 'D' || worktreeStatus == 'D' -> "Eliminado"
        indexStatus == 'A' -> "Nuevo"
        indexStatus == 'C' -> "Copiado"
        indexStatus == 'M' || worktreeStatus == 'M' -> "Modificado"
        else -> "Cambiado"
    }

    private fun parseLog(output: String): List<LogEntry> =
        output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                LogEntry(shortHash = parts[0], date = parts[1], subject = parts[2])
            }
            .toList()

    /** Quoting estándar de shell POSIX vía comillas simples -- seguro para rutas y mensajes de
     * commit con espacios, comillas dobles, `$`, backticks, etc. Único caso especial: comillas
     * simples embebidas, que se cierran/reabren con `'\''` (patrón estándar). */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
