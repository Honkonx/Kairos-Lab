package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de cmd_backup (kairos_manager.py) — antes usaba el módulo
 * `tarfile` de Python; acá se invoca el binario `tar` real de Termux (ya viene
 * instalado de base, no hace falta ninguna librería nueva) vía ProcessBuilder en
 * vez de reimplementar TAR+gzip a mano en Kotlin.
 *
 * Ronda 2026-08-14: backup/restore completo con auto-detección de SD card externa
 * (patrón validado en referencia/ciberseguridad/i-Haklab-master — backup create,
 * ver docs/referencias/AUDITORIA_CATEGORIA_CIBERSEGURIDAD.md hallazgo #2).
 */
object BackupManager {

    private val home get() = ManagerNativeUtils.home

    // Mount point típico de una SD card en Android: /storage/<ID-de-volumen-4-4>,
    // ej. /storage/ABCD-1234 (el ID lo asigna el sistema de archivos vfat). /storage/emulated/0
    // (almacenamiento interno emulado) NO calza este patrón y se excluye explícitamente.
    private val SD_MOUNT_REGEX = Regex("""^/storage/[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$""")

    // Directorio del backup DINÁMICO: SD card externa si está montada/escribible, si no
    // cae a Download/KairosBackups del almacenamiento interno. Getter en vez de val fijo
    // para que refleje la SD en el momento real (puede montarse/desmontarse en caliente).
    private val backupDir: File get() = externalStorageDir() ?: File("/storage/emulated/0/Download/KairosBackups")

    /**
     * Detecta la SD card externa real ejecutando `df` y buscando mount points de la forma
     * `/storage/XXXX-XXXX` — el ID de volumen que Android asigna a una SD física (patrón de
     * referencia/ciberseguridad/i-Haklab-master, ver AUDITORIA_CATEGORIA_CIBERSEGURIDAD.md
     * hallazgo #2: `df` filtrando `fuse|sdcard|mmc`, excluyendo `emulated`). Devuelve null si
     * no hay SD montada o no es escribible — en ese caso el backup usa Download/KairosBackups.
     */
    fun externalStorageDir(): File? {
        val (rc, out, _) = ManagerNativeUtils.runExec(listOf("df"), 10)
        if (rc != 0) return null
        // df (toybox) imprime una línea por filesystem con la columna "Mounted on" al final.
        val mount = out.lineSequence()
            .mapNotNull { line ->
                line.trim().split(Regex("""\s+""")).lastOrNull()
                    ?.takeIf { it.startsWith("/storage/") }
            }
            .firstOrNull { path ->
                path != "/storage/emulated/0" && SD_MOUNT_REGEX.matches(path)
            }
            ?: return null
        val dir = File(mount)
        return if (dir.isDirectory && dir.canWrite()) dir else null
    }

    // Relativos a HOME — mismo set que respaldaba kairos_manager.py (scripts +
    // registry + .bashrc + configs propias de los módulos que guardan estado en
    // archivo). No incluye proyectos completos de Claude/OpenCode/OpenClaw — eso lo
    // cubre por separado projects-sync-all (otro comando, no tocado en esta migración).
    private val includeItems = listOf(
        "scripts",
        ".android_server_registry",
        ".bashrc",
        ".ollama_user_config",
        ".hermes",
        ".openclaw/openclaw.json",
        ".last_cf_url",
    )

    fun create(timestamp: String): JSONObject {
        if (!backupDir.exists()) backupDir.mkdirs()
        val dest = File(backupDir, "kairos_backup_$timestamp.tar.gz")
        val existing = includeItems.filter { File(home, it).exists() }
        if (existing.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "Nada para respaldar todavía")
        }
        val args = mutableListOf("tar", "-czf", dest.absolutePath, "-C", home)
        args.addAll(existing)
        val (rc, out, err) = ManagerNativeUtils.runExec(args, 120)
        if (rc != 0) {
            dest.delete()
            return JSONObject().put("ok", false).put("error", err.ifEmpty { out }.takeLast(500))
        }
        val size = dest.length()
        return JSONObject().apply {
            put("ok", true)
            put("path", dest.absolutePath)
            put("size", size)
            put("size_human", ManagerNativeUtils.humanSize(size))
            put("items", existing.size)
        }
    }

    fun list(): JSONObject {
        if (!backupDir.isDirectory) {
            return JSONObject().put("ok", true).put("backups", JSONArray()).put("count", 0)
        }
        val backups = backupDir.listFiles { f -> f.isFile && f.name.endsWith(".tar.gz") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
        val arr = JSONArray()
        backups.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("path", f.absolutePath)
                put("size", f.length())
                put("size_human", ManagerNativeUtils.humanSize(f.length()))
            })
        }
        return JSONObject().put("ok", true).put("backups", arr).put("count", backups.size)
    }

    /**
     * Restaura un backup completo creado con [create] — extrae el .tar.gz sobre el HOME de
     * Termux (`tar -xzf <backup> -C $HOME`). Timeout largo (300s): el tar de scripts/ puede
     * pesar cientos de MB y la extracción es I/O secuencial.
     *
     * Importante: `tar -xzf` solo escribe los archivos que el backup trae — si el .tar.gz no
     * incluye el registry (`~/.android_server_registry`, ej. porque fue creado cuando aún no
     * existía), la restauración NO lo toca: el registry actual se conserva intacto. No hay
     * ningún "reset previo" de $HOME antes de extraer (eso borraría configs que el backup no
     * cubre); el backup simplemente reemplaza los archivos que sí trae.
     */
    fun restore(file: File): JSONObject {
        if (!file.isFile || !file.exists()) {
            return JSONObject().put("ok", false).put("error", "El backup no existe: ${file.absolutePath}")
        }
        val homeDir = File(home)
        if (!homeDir.isDirectory) homeDir.mkdirs()
        val (rc, out, err) = ManagerNativeUtils.runExec(
            listOf("tar", "-xzf", file.absolutePath, "-C", home),
            300
        )
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", err.ifEmpty { out }.takeLast(500))
        }
        return JSONObject().apply {
            put("ok", true)
            put("path", file.absolutePath)
        }
    }
}
