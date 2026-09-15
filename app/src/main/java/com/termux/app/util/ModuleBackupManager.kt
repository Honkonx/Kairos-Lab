package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Backup + rollback automático para [com.termux.app.ModuleController.cleanReinstallModule] —
 * hallazgo de la auditoría de `referencia/termux/Termux_XFCE` (yanghoeg), `app-installer` hace
 * backup + smoke-test + rollback automático antes de un upgrade; Kairos no tenía nada
 * equivalente (ver `MEJORAS_PENDIENTES.md` § "Módulos — backup + rollback automático al
 * reinstalar", y `docs/referencias/herramientas/REFERENCIA_VIBEWORKS.md` para el patrón más
 * completo — releases inmutables + activación atómica + health-check + rollback — del que este
 * mecanismo toma la idea central en una versión v1 más chica: una sola copia de respaldo, no un
 * historial de N versiones).
 *
 * Alcance deliberado (v1): esto respalda únicamente lo que Kairos mismo controla y borra de
 * forma 100% genérica ANTES de saber si la reinstalación va a funcionar —
 * `~/scripts/<id>/`(+`-udocker`), las líneas `<id>.*` del registry, y (cuando
 * [com.termux.app.ModuleController]'s `DeepUninstallPlan` lo declara) rutas de archivo
 * DEDICADAS del módulo que un `rm -rf`/`rm -f` explícito va a borrar (ej. el binario nativo de
 * Ollama o de Claude Code). NO intenta respaldar paquetes reales de `npm uninstall -g`/
 * `pkg uninstall -y`/`pip uninstall` — mover esos archivos a mano por fuera del gestor de
 * paquetes desincronizaría su base de datos interna (dpkg/`npm ls -g` quedarían mintiendo sobre
 * qué hay instalado realmente); para esos casos la resiliencia real ya viene de la propia caché
 * local de pkg/npm/pip (un `install` después de un `uninstall` reciente normalmente resuelve
 * desde caché, sin red nueva).
 *
 * Todo el movimiento de archivos usa `File.renameTo()` (fallback a copia+borrado solo si el
 * rename falla, ej. cruce de filesystem) — un rename es ~O(1) sin importar el tamaño del
 * archivo/directorio, así que un módulo de cientos de MB (Ollama, llama.cpp) no duplica espacio
 * en disco mientras dura el respaldo, a diferencia de un `cp -r` real.
 */
object ModuleBackupManager {

    private val HOME get() = TermuxConstants.TERMUX_HOME_DIR_PATH

    private fun backupRoot(moduleId: String) = File(HOME, ".kairos_module_backups/$moduleId")
    private fun manifestFile(moduleId: String) = File(backupRoot(moduleId), "manifest.txt")
    private fun registrySnapshotFile(moduleId: String) = File(backupRoot(moduleId), "registry.txt")

    /**
     * Mueve a un respaldo temporal, EN ORDEN, antes de que
     * [com.termux.app.ModuleController.deepUninstallModule] borre nada:
     * 1. `~/scripts/<id>/` y `~/scripts/<id>-udocker/` (control scripts propios del módulo).
     * 2. [extraBackupPaths] (rutas de binario/config dedicadas que el `DeepUninstallPlan` del
     *    módulo va a borrar con `rm -rf`/`rm -f`, si las declara).
     * 3. Las líneas `<id>.*` del registry (snapshot de texto, no hace falta mover el archivo
     *    completo).
     *
     * Devuelve `true` si el respaldo se armó sin excepciones — incluye el caso legítimo de "no
     * había nada que respaldar" (ej. el módulo nunca llegó a generar `~/scripts/<id>/`), que no
     * es un error: simplemente no hay nada que perder si la reinstalación falla después.
     */
    fun createBackup(moduleId: String, extraBackupPaths: List<String> = emptyList()): Boolean {
        return try {
            val root = backupRoot(moduleId)
            // Restos de un intento anterior sin limpiar (ej. la app se cerró a mitad de una
            // reinstalación previa) — no mezclar un respaldo viejo con uno nuevo.
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()

            val manifestLines = mutableListOf<String>()
            var index = 0
            fun moveIfExists(originalPath: String) {
                val src = File(originalPath)
                if (!src.exists()) return
                val entryName = "entry_${index++}"
                if (moveFileOrDir(src, File(root, entryName))) {
                    manifestLines.add("$entryName|$originalPath")
                }
            }

            moveIfExists(File(HOME, "scripts/$moduleId").path)
            moveIfExists(File(HOME, "scripts/$moduleId-udocker").path)
            extraBackupPaths.forEach(::moveIfExists)
            manifestFile(moduleId).writeText(manifestLines.joinToString("\n"))

            val registryFile = File(HOME, ".android_server_registry")
            val registryLines = RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
                if (registryFile.exists()) {
                    registryFile.readLines().filter { it.startsWith("$moduleId.") }
                } else {
                    emptyList()
                }
            }
            if (registryLines.isNotEmpty()) {
                registrySnapshotFile(moduleId).writeText(registryLines.joinToString("\n") + "\n")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Restaura todo lo movido por [createBackup] a su ubicación original, y borra el respaldo.
     * Devuelve `false` si no hay ningún respaldo para [moduleId] (nunca se llamó a
     * [createBackup], o ya se restauró/descartó antes) — el caller no debe reportar "se
     * restauró la versión anterior" en ese caso. También devuelve `false` (aunque limpia el
     * respaldo igual) cuando SÍ había un respaldo pero estaba vacío — el módulo no tenía nada
     * que perder antes de esta reinstalación (ej. primer intento de instalación), así que no
     * hay nada real que "restaurar" y el caller no debe afirmar que lo hizo.
     */
    fun restoreBackup(moduleId: String): Boolean {
        val root = backupRoot(moduleId)
        if (!root.exists()) return false
        return try {
            var restoredSomething = false

            val manifest = manifestFile(moduleId)
            if (manifest.exists()) {
                manifest.readLines()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val (entryName, originalPath) = line.split("|", limit = 2)
                        val src = File(root, entryName)
                        if (src.exists()) {
                            val dst = File(originalPath)
                            dst.parentFile?.mkdirs()
                            // La reinstalación fallida puede haber dejado un archivo/carpeta a
                            // medio escribir en la ruta original — se descarta antes de mover el
                            // respaldo de vuelta, para no mezclar restos nuevos con lo viejo.
                            if (dst.exists()) {
                                if (dst.isDirectory) dst.deleteRecursively() else dst.delete()
                            }
                            if (moveFileOrDir(src, dst)) restoredSomething = true
                        }
                    }
            }

            val snapshot = registrySnapshotFile(moduleId)
            if (snapshot.exists()) {
                val lines = snapshot.readLines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
                        val registryFile = File(HOME, ".android_server_registry")
                        val current = if (registryFile.exists()) registryFile.readLines() else emptyList()
                        // Descarta cualquier línea "<id>.*" que la reinstalación fallida haya
                        // llegado a escribir antes de fallar, para no dejar una mezcla de
                        // registry viejo + parcial nuevo.
                        val kept = current.filterNot { it.startsWith("$moduleId.") } + lines
                        registryFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                    }
                    restoredSomething = true
                }
            }

            root.deleteRecursively()
            restoredSomething
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reinstalación exitosa — el respaldo ya no hace falta. Se borra en vez de acumularse (v1:
     * una sola copia de la última reinstalación, no un historial de N versiones como vibeworks).
     */
    fun discardBackup(moduleId: String) {
        try { backupRoot(moduleId).deleteRecursively() } catch (_: Exception) {}
    }

    private fun moveFileOrDir(src: File, dst: File): Boolean {
        return try {
            if (src.renameTo(dst)) return true
            // Fallback solo si renameTo() falla (ej. cruce de filesystem) — copia real +
            // borrado del origen, último recurso, no el camino esperado en uso normal.
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
                src.deleteRecursively()
            } else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
