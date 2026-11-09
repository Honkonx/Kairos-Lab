package com.termux.app.util

import java.io.File

/** Una sesión de terminal que el gateway abrió (tmux, nombre con prefijo [SessionManager.PREFIX]). */
data class SessionInfo(val name: String, val windows: Int, val attached: Boolean)

/**
 * Sesiones de terminal paralelas ("otra ventana") controladas por el gateway. Usan tmux con el prefijo `kx-` para no
 * tocar jamás las sesiones de los módulos (`ollama`, `n8n`...). Cada argumento viaja como argv, nunca por shell.
 */
class SessionManager(private val runner: CommandRunner = ShellCommandRunner) {

    fun list(): List<SessionInfo> {
        val (code, out) = runner.run(listOf("tmux", "list-sessions", "-F", "#{session_name}|#{session_windows}|#{session_attached}"), 5)
        if (code != 0) return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val p = line.trim().split('|')
            if (p.size < 3 || !p[0].startsWith(PREFIX)) return@mapNotNull null
            SessionInfo(p[0].removePrefix(PREFIX), p[1].toIntOrNull() ?: 1, p[2] != "0")
        }.toList()
    }

    /** Abre una sesión nueva ejecutando [command] (`sh -c` dentro de tmux). Falla si el nombre ya existe. */
    fun open(name: String, command: String): PrivilegeResult {
        if (!NAME.matches(name)) return PrivilegeResult(false, "nombre de sesión inválido", 400)
        if (command.isBlank() || command.length > MAX_COMMAND) return PrivilegeResult(false, "comando inválido", 400)
        val r = runner.run(listOf("tmux", "new-session", "-d", "-s", PREFIX + name, "-x", "120", "-y", "40", command), 10)
        return PrivilegeResult(r.first == 0, r.second.trim().take(400), r.first)
    }

    /** Escribe [text] en la sesión (literal) y, si [enter], pulsa Enter. */
    fun send(name: String, text: String, enter: Boolean): PrivilegeResult {
        if (!NAME.matches(name)) return PrivilegeResult(false, "nombre de sesión inválido", 400)
        if (text.length > MAX_COMMAND) return PrivilegeResult(false, "texto demasiado largo", 400)
        if (text.isNotEmpty()) {
            val r = runner.run(listOf("tmux", "send-keys", "-t", PREFIX + name, "-l", text), 5)
            if (r.first != 0) return PrivilegeResult(false, r.second.trim().take(400), r.first)
        }
        if (enter) {
            val r = runner.run(listOf("tmux", "send-keys", "-t", PREFIX + name, "Enter"), 5)
            if (r.first != 0) return PrivilegeResult(false, r.second.trim().take(400), r.first)
        }
        return PrivilegeResult(true, "")
    }

    /** Pantalla actual de la sesión (últimas [lines] líneas). */
    fun read(name: String, lines: Int): PrivilegeResult {
        if (!NAME.matches(name)) return PrivilegeResult(false, "nombre de sesión inválido", 400)
        val n = lines.coerceIn(1, MAX_LINES)
        val r = runner.run(listOf("tmux", "capture-pane", "-p", "-t", PREFIX + name, "-S", "-$n"), 5)
        return PrivilegeResult(r.first == 0, r.second.trimEnd().takeLast(MAX_OUTPUT), r.first)
    }

    fun kill(name: String): PrivilegeResult {
        if (!NAME.matches(name)) return PrivilegeResult(false, "nombre de sesión inválido", 400)
        val r = runner.run(listOf("tmux", "kill-session", "-t", PREFIX + name), 5)
        return PrivilegeResult(r.first == 0, r.second.trim().take(400), r.first)
    }

    companion object {
        const val PREFIX = "kx-"
        val NAME = Regex("[a-z0-9_-]{1,24}")
        const val MAX_COMMAND = 500
        const val MAX_LINES = 400
        const val MAX_OUTPUT = 16_000
    }
}

data class FileEntry(val name: String, val dir: Boolean, val size: Long)

/**
 * Archivos del HOME de Termux para el panel/IDE remoto. Todo se resuelve contra [root] (ruta canónica, sin salir por `..`
 * ni por enlaces) y se rechaza lo que cae en [denied] (claves, secretos del propio gateway).
 */
class HomeFiles(private val root: File, private val denied: List<File> = emptyList()) {
    private val rootCanonical = root.canonicalFile

    /** Ruta relativa → archivo dentro del root, o null si se sale o está vedado. */
    fun resolve(relative: String): File? {
        if (relative.contains('\u0000')) return null
        val target = File(rootCanonical, relative.trimStart('/')).canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) return null
        if (denied.any { target == it.canonicalFile || target.path.startsWith(it.canonicalFile.path + File.separator) }) return null
        if (target.path.split(File.separatorChar).any { it in SECRET_NAMES }) return null
        return target
    }

    fun list(relative: String): Result<List<FileEntry>> {
        val dir = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        val files = dir.listFiles() ?: return Result.failure(IllegalArgumentException("no es una carpeta"))
        return Result.success(files.filter { resolve(relative.trimEnd('/') + "/" + it.name) != null }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .take(MAX_ENTRIES).map { FileEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) })
    }

    fun read(relative: String): Result<String> {
        val file = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (!file.isFile) return Result.failure(IllegalArgumentException("no es un archivo"))
        if (file.length() > MAX_READ) return Result.failure(IllegalArgumentException("archivo demasiado grande (máx ${MAX_READ / 1024} KB)"))
        return Result.success(file.readText())
    }

    fun write(relative: String, content: String): Result<Unit> {
        val file = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (content.length > MAX_WRITE) return Result.failure(IllegalArgumentException("contenido demasiado grande"))
        if (file.isDirectory) return Result.failure(IllegalArgumentException("es una carpeta"))
        file.parentFile?.mkdirs()
        file.writeText(content)
        return Result.success(Unit)
    }

    /** Lectura binaria (`fs.read` con `encoding=base64`) — mismas reglas de ruta que [read], límite propio
     *  más holgado porque un binario (imagen, .deb, etc.) no tiene el techo de 128 KB pensado para texto. */
    fun readBytes(relative: String): Result<ByteArray> {
        val file = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (!file.isFile) return Result.failure(IllegalArgumentException("no es un archivo"))
        if (file.length() > MAX_READ_BINARY) return Result.failure(IllegalArgumentException("archivo demasiado grande (máx ${MAX_READ_BINARY / (1024 * 1024)} MB)"))
        return Result.success(file.readBytes())
    }

    /** Escritura binaria (`fs.write` con `encoding=base64`), ver [readBytes]. */
    fun writeBytes(relative: String, data: ByteArray): Result<Unit> {
        val file = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (data.size > MAX_WRITE_BINARY) return Result.failure(IllegalArgumentException("archivo demasiado grande (máx ${MAX_WRITE_BINARY / (1024 * 1024)} MB)"))
        if (file.isDirectory) return Result.failure(IllegalArgumentException("es una carpeta"))
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return Result.success(Unit)
    }

    fun mkdir(relative: String): Result<Unit> {
        val dir = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (dir == rootCanonical) return Result.failure(IllegalArgumentException("ruta no permitida"))
        return if (dir.isDirectory || dir.mkdirs()) Result.success(Unit) else Result.failure(IllegalArgumentException("no se pudo crear la carpeta"))
    }

    /** Borra un archivo o una carpeta VACÍA. Nunca el HOME ni algo fuera de él; borrar árboles enteros se hace desde una terminal. */
    fun delete(relative: String): Result<Unit> {
        val target = resolve(relative) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (target == rootCanonical) return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (target.isDirectory && !target.list().isNullOrEmpty()) return Result.failure(IllegalArgumentException("la carpeta no está vacía"))
        return if (target.delete()) Result.success(Unit) else Result.failure(IllegalArgumentException("no se pudo borrar"))
    }

    fun rename(from: String, to: String): Result<Unit> {
        val source = resolve(from) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        val dest = resolve(to) ?: return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (source == rootCanonical || dest == rootCanonical) return Result.failure(IllegalArgumentException("ruta no permitida"))
        if (!source.exists()) return Result.failure(IllegalArgumentException("no existe el origen"))
        if (dest.exists()) return Result.failure(IllegalArgumentException("el destino ya existe"))
        dest.parentFile?.mkdirs()
        return if (source.renameTo(dest)) Result.success(Unit) else Result.failure(IllegalArgumentException("no se pudo mover"))
    }

    companion object {
        /** Carpetas y archivos con credenciales conocidas: ni se listan ni se leen aunque el nivel sea «total».
         *  `.nube_token` es el token de [NubeServer] (gap real cerrado en esta ronda: antes de agregar
         *  `cloud.share_link`, `fs.read`/`fs.list` ya podían devolver este archivo en texto plano — la URL con
         *  token sigue existiendo, pero solo por el camino pensado para compartirla, nunca leyendo el archivo
         *  crudo por `fs.*`). */
        val SECRET_NAMES = setOf(".ssh", ".gnupg", ".homelab_token", ".git-credentials", ".netrc", ".npmrc", ".bash_history", ".aws", ".kube", ".nube_token")
        const val MAX_ENTRIES = 500
        const val MAX_READ = 128 * 1024L
        const val MAX_WRITE = 60 * 1024
        /** Tope para archivos binarios (`encoding=base64`) — pedido explícito: "límite razonable (p.ej. 2 MB)". */
        const val MAX_READ_BINARY = 2 * 1024 * 1024L
        const val MAX_WRITE_BINARY = 2 * 1024 * 1024
    }
}

/** Ajustes de la propia app que el gateway puede leer/cambiar. Lista cerrada: nada fuera de ella es accesible. */
interface AppSettings {
    val keys: Set<String>
    fun get(key: String): String?
    fun set(key: String, value: String): Boolean
}

/** Todo lo que el gateway puede controlar de la app además de los módulos. [terminalCommandOf] da el comando de terminal de un módulo. */
class GatewayControl(
    val sessions: SessionManager,
    val files: HomeFiles,
    val settings: AppSettings,
    val terminalCommandOf: (String) -> String?,
    val distros: DistroManager = DistroManager(sessions = sessions),
    val sandbox: SandboxManager = SandboxManager(),
    val engram: EngramManager = EngramManager(),
)

/** Ajustes reales de Kairos expuestos al gateway. Hoy solo el tema; se amplía añadiendo claves aquí. */
class AppPrefsSettings(private val context: android.content.Context) : AppSettings {
    override val keys = setOf("theme")

    override fun get(key: String): String? = if (key == "theme") KairosThemePrefs.getSelectedTheme(context).id else null

    override fun set(key: String, value: String): Boolean {
        if (key != "theme") return false
        val theme = KairosThemePrefs.KairosTheme.entries.firstOrNull { it.id == value } ?: return false
        KairosThemePrefs.setSelectedTheme(context, theme)
        return true
    }
}
