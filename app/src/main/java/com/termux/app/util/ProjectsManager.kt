package com.termux.app.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de `_handle_project_actions()` (kairos_manager.py) — symlinks +
 * file locking + registro de "orígenes" de proyecto (usado por Claude/OpenCode/Codex/
 * Antigravity, y en Python también por OpenClaw vía workspace-*). Portado completo
 * porque python3 está confirmado roto/no confiable en dispositivo real (ver
 * docs/humano*.md) — a diferencia de ClaudeNative/OpenCodeNative (que dejaron
 * projects-* en Python a propósito por ser symlinks+fcntl "de riesgo"), acá se hace la
 * migración completa pedida explícitamente.
 *
 * Diferencias deliberadas respecto al original Python:
 *  - fcntl.flock() → java.nio.channels.FileLock sobre el mismo archivo ".lock", más un
 *    `synchronized` de proceso (ver [withRegistryLock]) — FileLock.lock() de Java NO
 *    bloquea a otro hilo del mismo JVM que intente lockear la misma región (lanza
 *    OverlappingFileLockException en vez de esperar), a diferencia de flock() en POSIX.
 *  - Symlinks: android.system.Os.symlink/lstat/readlink/remove (syscalls directos, API
 *    21+, msdk de la app es 24) en vez de java.nio.file.Files.createSymbolicLink — NIO2
 *    (java.nio.file) requiere desugaring y su soporte de symlinks en Android vía
 *    desugar_jdk_libs no está garantizado en todas las versiones; Os.symlink() es la
 *    syscall real, sin intermediarios.
 *  - write_registry(prefix, entries) del original borra TODAS las claves bajo ese
 *    prefijo antes de reescribir — correcto para módulos normales (siempre reescriben
 *    su set completo de claves en un solo update_registry()), pero es un bug real para
 *    "project_origin": cada projects-import/set-origin nuevo borraba los orígenes de
 *    TODOS los demás proyectos ya importados y dejaba solo el que se acababa de
 *    escribir. [mergeRegistryEntries] corrige esto mergeando por clave completa exacta
 *    ("project_origin.nombre") sin tocar el resto del registry.
 */
object ProjectsManager {

    private val home get() = ManagerNativeUtils.home
    private val registryFile: File get() = File(home, ".android_server_registry")
    private val lockFile: File get() = File(home, ".android_server_registry.lock")

    /** Carpeta compartida por Claude/OpenCode/Codex/Antigravity — misma PROJECTS_DIR de kairos_manager.py. */
    val PROJECTS_DIR: File get() = File(home, "proyectos")

    /** Prefijo de registro para el origen de cada proyecto (symlink real o carpeta importada). */
    const val PROJECT_ORIGIN_PREFIX = "project_origin"

    /** Carpeta Download del almacenamiento interno — origen por defecto de symlink/copiar. */
    const val DOWNLOAD_DIR_PATH = "/storage/emulated/0/Download"

    /**
     * Raíz del almacenamiento interno — punto de partida para navegar CUALQUIER carpeta
     * (no solo Download) al crear un symlink o copiar un proyecto (pedido explícito
     * 2026-08-14: "cualquier carpeta accesible" no solo Download). MANAGE_EXTERNAL_STORAGE
     * ya se pide en el wizard (ver FileManagerFragment.kt), así que java.io.File directo
     * alcanza para navegar acá también — no hace falta Storage Access Framework.
     */
    const val EXTERNAL_STORAGE_ROOT = "/storage/emulated/0"

    // ════════════════════════════════════════════════════════════
    //  Registry — lectura/escritura con lock real (equivalente a fcntl.flock)
    // ════════════════════════════════════════════════════════════

    // Mecanismo real (FileLock + synchronized intra-proceso) extraído a RegistryLock
    // (consolidación 2026-08-13, ver auditoría de código) — TunnelManager/EntornoNative
    // ahora comparten el mismo lock (mismo lockFile) en vez de reimplementarlo sin
    // protección de concurrencia.
    private fun <T> withRegistryLock(block: () -> T): T = RegistryLock.withLock(lockFile, block)

    private fun readRegistryLines(): List<String> =
        if (registryFile.exists()) try { registryFile.readLines() } catch (_: Exception) { emptyList() } else emptyList()

    private fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val eq = trimmed.indexOf('=')
        return if (eq > 0) trimmed.substring(0, eq).trim() else null
    }

    /** Mergea entries (clave completa "prefix.nombre" → valor) sin tocar el resto del registry. */
    private fun mergeRegistryEntries(entries: Map<String, String>) {
        withRegistryLock {
            val keep = readRegistryLines().filter { keyOf(it) !in entries.keys }.toMutableList()
            entries.forEach { (k, v) -> keep.add("$k=$v") }
            registryFile.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    private fun deleteRegistryKey(key: String) {
        withRegistryLock {
            val keep = readRegistryLines().filter { keyOf(it) != key }
            registryFile.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Visibilidad de módulos — "Activar/Desactivar" de la Tienda (2026-08-11, humano97
    //  punto 4): Desactivar OCULTA el módulo de la pantalla Módulos SIN desinstalarlo
    //  (el registro <id>.installed sigue intacto). Se persiste en el mismo registry real
    //  (clave <id>.hidden=true), con el mismo lock que el resto de escrituras del proyecto.
    // ════════════════════════════════════════════════════════════

    /** Oculta (hidden=true) o restaura (borra la clave) un módulo en la pantalla Módulos. */
    fun setModuleHidden(moduleId: String, hidden: Boolean) {
        if (hidden) mergeRegistryEntries(mapOf("$moduleId.hidden" to "true"))
        else deleteRegistryKey("$moduleId.hidden")
    }

    /** Ids de los módulos ocultos de la pantalla Módulos (registry <id>.hidden=true). */
    fun hiddenModuleIds(): Set<String> =
        ManagerNativeUtils.readRegistry()
            .filterKeys { it.endsWith(".hidden") }
            .filterValues { it == "true" }
            .mapKeys { it.key.removeSuffix(".hidden") }
            .keys

    private fun readOrigin(regPrefix: String, name: String): String =
        ManagerNativeUtils.readRegistry()["$regPrefix.$name"] ?: ""

    private fun readOriginsFor(regPrefix: String): Map<String, String> {
        val prefixDot = "$regPrefix."
        return ManagerNativeUtils.readRegistry()
            .filterKeys { it.startsWith(prefixDot) }
            .mapKeys { it.key.removePrefix(prefixDot) }
    }

    // ════════════════════════════════════════════════════════════
    //  Symlinks — syscalls directos (android.system.Os), sin java.nio.file
    // ════════════════════════════════════════════════════════════

    private fun isSymlink(file: File): Boolean = try {
        val mode = Os.lstat(file.absolutePath).st_mode
        (mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    } catch (_: ErrnoException) {
        false
    }

    private fun createSymlink(target: String, linkFile: File) {
        try {
            Os.symlink(target, linkFile.absolutePath)
        } catch (e: ErrnoException) {
            throw java.io.IOException(e.message ?: "symlink falló", e)
        }
    }

    private fun removeSymlinkOrDir(file: File) {
        if (isSymlink(file)) {
            try { Os.remove(file.absolutePath) } catch (e: ErrnoException) {
                throw java.io.IOException(e.message ?: "no se pudo borrar symlink", e)
            }
        } else if (file.isDirectory) {
            file.deleteRecursively()
        }
    }

    // File.getCanonicalFile() resuelve symlinks vía realpath() del SO (semántica
    // equivalente a os.path.realpath() de Python en Linux/Android).
    private fun realPath(file: File): File = try { file.canonicalFile } catch (_: Exception) { file.absoluteFile }

    // ════════════════════════════════════════════════════════════
    //  PROYECTOS — reutilizable (Claude, OpenCode, Codex, Antigravity)
    // ════════════════════════════════════════════════════════════

    private fun projList(baseDir: File, regPrefix: String): JSONArray {
        baseDir.mkdirs()
        val origins = readOriginsFor(regPrefix)
        val arr = JSONArray()
        val names = baseDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        for (name in names) {
            val full = File(baseDir, name)
            if (!full.isDirectory && !isSymlink(full)) continue
            val real = realPath(full)
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("path", full.absolutePath)
                    .put("real_path", real.absolutePath)
                    .put("is_symlink", isSymlink(full))
                    .put("origin", origins[name] ?: "")
                    .put("has_package_json", File(real, "package.json").exists())
                    .put("has_git", File(real, ".git").isDirectory)
                    .put("exists", real.isDirectory)
            )
        }
        return arr
    }

    private fun projCreateSymlink(source: String, baseDir: File): Pair<Boolean, String> {
        baseDir.mkdirs()
        val name = File(source).name
        val dest = File(baseDir, name)
        if (dest.exists() || isSymlink(dest)) return false to "Ya existe: $name"
        return try {
            createSymlink(source, dest)
            true to "Symlink: $name"
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    private fun projDelete(name: String, baseDir: File, regPrefix: String): Pair<Boolean, String> {
        val full = File(baseDir, name)
        if (!isSymlink(full) && !full.isDirectory) return false to "No existe: $name"
        return try {
            removeSymlinkOrDir(full)
            deleteRegistryKey("$regPrefix.$name")
            true to "Eliminado: $name"
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    private fun projImportCopy(source: String, baseDir: File, regPrefix: String): Pair<Boolean, String> {
        baseDir.mkdirs()
        val srcFile = File(source)
        val name = srcFile.name
        val dest = File(baseDir, name)
        if (dest.exists()) return false to "Ya existe: $name"
        return try {
            srcFile.copyRecursively(dest, overwrite = false)
            mergeRegistryEntries(mapOf("$regPrefix.$name" to source))
            true to "Importado: $name"
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    private fun projSync(name: String, baseDir: File, regPrefix: String): Pair<Boolean, String> {
        val origin = readOrigin(regPrefix, name)
        if (origin.isBlank()) return false to "$name sin origen"
        val src = File(baseDir, name)
        if (!src.isDirectory) return false to "No existe: $name"
        return try {
            val originDir = File(origin)
            src.listFiles()?.forEach { item ->
                val dest = File(originDir, item.name)
                if (item.isDirectory) {
                    if (dest.exists()) dest.deleteRecursively()
                    item.copyRecursively(dest, overwrite = true)
                } else {
                    item.copyTo(dest, overwrite = true)
                }
            }
            true to "Sincronizado: $name → $origin"
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    /**
     * Dirección OPUESTA a [projSync]: trae los cambios más recientes del original
     * (almacenamiento externo) hacia la copia en `home/proyecto` — pedido explícito
     * 2026-08-14 ("sincronizar de nuevo" bajo demanda, no automática). [projSync] ya
     * cubría la dirección copia→original (útil para empujar ediciones hechas dentro de
     * Termux de vuelta al original), así que se agrega esta sin tocar esa — ambas
     * conviven, cada una resuelta por su propio botón de menú.
     */
    private fun projSyncFromOrigin(name: String, baseDir: File, regPrefix: String): Pair<Boolean, String> {
        val origin = readOrigin(regPrefix, name)
        if (origin.isBlank()) return false to "$name no tiene un original vinculado (es un symlink, no una copia)"
        val originDir = File(origin)
        if (!originDir.isDirectory) return false to "El original ya no existe: $origin"
        val dest = File(baseDir, name)
        if (isSymlink(dest)) return false to "$name es un symlink — se actualiza solo, no hace falta sincronizar"
        dest.mkdirs()
        return try {
            originDir.listFiles()?.forEach { item ->
                val target = File(dest, item.name)
                if (item.isDirectory) {
                    if (target.exists()) target.deleteRecursively()
                    item.copyRecursively(target, overwrite = true)
                } else {
                    item.copyTo(target, overwrite = true)
                }
            }
            true to "Actualizado: $name ← $origin"
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    private fun storageDirs(path: String?): JSONArray {
        val base = File(path ?: DOWNLOAD_DIR_PATH)
        val arr = JSONArray()
        if (!base.isDirectory) return arr
        val dirs = try {
            base.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        dirs.forEach { arr.put(JSONObject().put("name", it.name).put("path", it.absolutePath)) }
        return arr
    }

    // ════════════════════════════════════════════════════════════
    //  API pública — mismos nombres de acción/campos JSON que
    //  _handle_project_actions() para que el parsing existente en los Fragments
    //  (json.optJSONArray("projects"), json.optString("message"), ...) no cambie.
    // ════════════════════════════════════════════════════════════

    fun projectsList(baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        val projects = projList(baseDir, regPrefix)
        return JSONObject().put("ok", true).put("projects", projects).put("count", projects.length())
    }

    fun projectsCreateSymlink(source: String, baseDir: File = PROJECTS_DIR): JSONObject {
        if (source.isBlank()) return JSONObject().put("ok", false).put("error", "Falta ruta")
        val (ok, msg) = projCreateSymlink(source, baseDir)
        return if (ok) JSONObject().put("ok", true).put("message", msg)
        else JSONObject().put("ok", false).put("error", msg)
    }

    fun projectsDelete(name: String, baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        if (name.isBlank()) return JSONObject().put("ok", false).put("error", "Falta nombre")
        val (ok, msg) = projDelete(name, baseDir, regPrefix)
        return if (ok) JSONObject().put("ok", true).put("message", msg)
        else JSONObject().put("ok", false).put("error", msg)
    }

    fun projectsImport(source: String, baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        if (source.isBlank()) return JSONObject().put("ok", false).put("error", "Falta ruta storage")
        val (ok, msg) = projImportCopy(source, baseDir, regPrefix)
        return if (ok) JSONObject().put("ok", true).put("message", msg)
        else JSONObject().put("ok", false).put("error", msg)
    }

    fun projectsSync(name: String, baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        if (name.isBlank()) return JSONObject().put("ok", false).put("error", "Falta nombre")
        val (ok, msg) = projSync(name, baseDir, regPrefix)
        return if (ok) JSONObject().put("ok", true).put("message", msg)
        else JSONObject().put("ok", false).put("error", msg)
    }

    /** Pull manual (bajo demanda) — trae los cambios del original hacia UN proyecto copiado. */
    fun projectsSyncFromOrigin(name: String, baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        if (name.isBlank()) return JSONObject().put("ok", false).put("error", "Falta nombre")
        val (ok, msg) = projSyncFromOrigin(name, baseDir, regPrefix)
        return if (ok) JSONObject().put("ok", true).put("message", msg)
        else JSONObject().put("ok", false).put("error", msg)
    }

    fun projectsSyncAll(baseDir: File = PROJECTS_DIR, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        val results = JSONArray()
        var okCount = 0
        var failCount = 0
        val projects = projList(baseDir, regPrefix)
        for (i in 0 until projects.length()) {
            val p = projects.getJSONObject(i)
            if (p.optString("origin").isBlank()) continue
            val (ok, msg) = projSync(p.optString("name"), baseDir, regPrefix)
            results.put(JSONObject().put("name", p.optString("name")).put("ok", ok).put("message", msg))
            if (ok) okCount++ else failCount++
        }
        return JSONObject().put("ok", true).put("results", results).put("synced", okCount).put("failed", failCount)
    }

    fun projectsOrigins(regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        val origins = readOriginsFor(regPrefix)
        val obj = JSONObject()
        origins.forEach { (k, v) -> obj.put(k, v) }
        return JSONObject().put("ok", true).put("origins", obj).put("count", origins.size)
    }

    fun projectsSetOrigin(name: String, path: String, regPrefix: String = PROJECT_ORIGIN_PREFIX): JSONObject {
        if (name.isBlank() || path.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Uso: projects-set-origin <nombre> <ruta>")
        }
        mergeRegistryEntries(mapOf("$regPrefix.$name" to path))
        return JSONObject().put("ok", true).put("message", "Origen: $name → $path")
    }

    fun projectsStorageDirs(path: String? = null): JSONObject {
        val dirs = storageDirs(path)
        return JSONObject().put("ok", true).put("dirs", dirs).put("base", path ?: DOWNLOAD_DIR_PATH)
    }
}

/**
 * Corre `action` en un hilo de fondo y entrega el resultado en el hilo de UI — mismo
 * patrón que el antiguo `BaseModuleFragment.runManagerAction()` (eliminado en 2026-08-10
 * al quedar sin llamadores) pero para llamadas directas a
 * [ProjectsManager] (sin python3 de por medio). Extension function standalone a
 * propósito: evita tocar BaseModuleFragment.kt mientras otro trabajo en paralelo puede
 * estar tocando ese archivo compartido.
 */
fun Fragment.runProjectsAction(action: () -> JSONObject, onResult: (JSONObject) -> Unit) {
    Thread {
        val result = try {
            action()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
        if (!isAdded) return@Thread
        activity?.runOnUiThread { onResult(result) }
    }.start()
}
