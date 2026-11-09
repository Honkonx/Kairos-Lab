package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — mismos
 * comandos reales que ya usa `DbFragment` (`mysql -u root -e ...`/`psql -U "$(whoami)" ...`,
 * confirmados ahí, no inventados). Operación DESTRUCTIVA (`drop`) con una segunda compuerta
 * propia, además del `min_level: total` del manifiesto: exige `confirm=true` explícito — mismo
 * criterio de "no apurar lo irreversible" que ya se aplicó en Remote/Restic esta sesión, pero acá
 * SÍ se implementa (con la compuerta) en vez de diferirse, porque el usuario la nombró
 * explícitamente ("DB" en el listado de módulos con opciones propias).
 */
object DbManager {
    private val NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private val BACKUP_FILE_PATTERN = Regex("^[A-Za-z0-9_.-]+$")
    private val runner: CommandRunner = ShellCommandRunner

    private fun backupsDir(): File = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "backups/db").apply { mkdirs() }
    private fun timestamp(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    private fun run(cmd: String, timeoutSec: Long = 30): PrivilegeResult {
        val (code, out) = runner.run(listOf("bash", "-c", cmd), timeoutSec)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    /** Mismo chequeo de puerto real que `DbFragment.isAlive()` — pgrep no ve el proceso por la
     *  restricción real de SELinux/Yama ya confirmada ahí (ver ese comentario), el puerto sí. */
    fun status(): Map<String, Boolean> = mapOf(
        "mysql_running" to ManagerNativeUtils.checkPort(3306),
        "postgres_running" to ManagerNativeUtils.checkPort(5432),
        "redis_running" to ManagerNativeUtils.checkPort(6379),
    )

    fun create(engine: String, name: String): PrivilegeResult {
        if (!NAME_PATTERN.matches(name)) return PrivilegeResult(false, "nombre de base de datos inválido", 400)
        return when (engine) {
            "mysql" -> run("mysql -u root -e 'CREATE DATABASE IF NOT EXISTS `$name`;'")
            "postgres" -> run("psql -U \"\$(whoami)\" -d postgres -c 'CREATE DATABASE $name;'")
            else -> PrivilegeResult(false, "motor desconocido: $engine", 400)
        }
    }

    /** [confirm] debe venir en `true` explícito — ver cabecera del archivo. */
    fun drop(engine: String, name: String, confirm: Boolean): PrivilegeResult {
        if (!confirm) return PrivilegeResult(false, "hace falta confirm=true explícito para borrar una base de datos", 400)
        if (!NAME_PATTERN.matches(name)) return PrivilegeResult(false, "nombre de base de datos inválido", 400)
        return when (engine) {
            "mysql" -> run("mysql -u root -e 'DROP DATABASE IF EXISTS `$name`;'")
            "postgres" -> run("psql -U \"\$(whoami)\" -d postgres -c 'DROP DATABASE IF EXISTS $name;'")
            else -> PrivilegeResult(false, "motor desconocido: $engine", 400)
        }
    }

    fun backup(engine: String, name: String): PrivilegeResult {
        if (!NAME_PATTERN.matches(name)) return PrivilegeResult(false, "nombre de base de datos inválido", 400)
        val out = File(backupsDir(), "${engine}_${name}_${timestamp()}.sql")
        return when (engine) {
            "mysql" -> run("mysqldump -u root ${shellQuote(name)} > ${shellQuote(out.absolutePath)}").let {
                if (it.ok) PrivilegeResult(true, out.absolutePath) else it
            }
            "postgres" -> run("pg_dump -U \"\$(whoami)\" ${shellQuote(name)} > ${shellQuote(out.absolutePath)}").let {
                if (it.ok) PrivilegeResult(true, out.absolutePath) else it
            }
            else -> PrivilegeResult(false, "motor desconocido: $engine", 400)
        }
    }

    /** Mismos comandos reales que `DbFragment.restoreMysqlDb()`/`restorePgDb()` -- esas 2
     *  funciones quedan intactas (DbFragment.kt está protegido, ver
     *  `.claude/rules/kairos-module-touch-policy.md`), este método es la pieza nueva y propia
     *  del gateway. [backupFile] es solo el NOMBRE del archivo (sin rutas) dentro de
     *  `~/backups/db/` -- el mismo directorio donde escribe [backup] -- nunca una ruta
     *  arbitraria del filesystem. */
    fun restore(engine: String, name: String, backupFile: String): PrivilegeResult {
        if (!NAME_PATTERN.matches(name)) return PrivilegeResult(false, "nombre de base de datos inválido", 400)
        if (!BACKUP_FILE_PATTERN.matches(backupFile)) return PrivilegeResult(false, "nombre de archivo de respaldo inválido", 400)
        val file = File(backupsDir(), backupFile)
        if (!file.isFile) return PrivilegeResult(false, "no existe ese respaldo en ~/backups/db", 400)
        return when (engine) {
            "mysql" -> run("mysql -u root -e 'CREATE DATABASE IF NOT EXISTS `$name`;' && mysql -u root ${shellQuote(name)} < ${shellQuote(file.absolutePath)}", 120)
            "postgres" -> run("psql -U \"\$(whoami)\" -d postgres -c 'CREATE DATABASE $name;' 2>/dev/null; psql -U \"\$(whoami)\" -d ${shellQuote(name)} -f ${shellQuote(file.absolutePath)}", 120)
            else -> PrivilegeResult(false, "motor desconocido: $engine", 400)
        }
    }
}
