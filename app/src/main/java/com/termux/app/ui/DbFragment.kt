package com.termux.app.ui

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.app.util.kairosThemeColor
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.data.ModuleRegistry
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PGREP_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.shellQuote
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Detalle del módulo Base de Datos (creado 2026-08-10 — ver PLAN.md "Rediseño de pantallas
 * 2026-08-10", Fase 3). Reemplaza a SqliteFragment: SQLite ya no es submenú de Python, este
 * módulo agrupa MySQL/MariaDB + PostgreSQL + SQLite en una sola pantalla.
 *
 * - Servidores (MySQL/MariaDB y PostgreSQL): estado en vivo (pgrep) + arrancar/detener
 *   cada uno vía los scripts que deja modulos/db.sh en ~/scripts/db/ (mysql_start.sh,
 *   mysql_stop.sh, postgres_start.sh, postgres_stop.sh). El toggle de la pantalla Módulos
 *   usa ModuleController con los wrappers start.sh/stop.sh.
 * - SQLite: gestión de archivos .db/.sqlite con android.database.sqlite.SQLiteDatabase
 *   (API de primera clase del framework — misma lógica que heredó de SqliteFragment,
 *   sin ningún subproceso Python).
 */
class DbFragment : BaseModuleFragment() {

    override fun getModuleId() = "db"
    override fun getModuleName() = getString(R.string.db_module_name)

    private var mysqlValue: TextView? = null
    private var postgresValue: TextView? = null
    private var sqliteValue: TextView? = null
    private var redisValue: TextView? = null
    private var dbList: JSONObject? = null
    // Switches reales (2026-08-22, ver docs/humano/humano193.md) — reemplazan los 3 pares de
    // botones Iniciar/Detener (MySQL/PostgreSQL/Redis, motores independientes que corren en
    // paralelo, no son variantes excluyentes — mismo criterio que Remote SSH/túnel).
    private lateinit var mysqlSwitch: SwitchRow
    private lateinit var postgresSwitch: SwitchRow
    private lateinit var redisSwitch: SwitchRow

    // Reorganizado 2026-08-26 (pedido explícito del usuario, usando el helper nuevo
    // BaseModuleFragment.setupTabs() — ver docs/estructura/COMPONENTES_UI.md) en 3 pestañas
    // reales agrupadas por tipo: "Motores" (estado en vivo + start/stop de los 3 servidores +
    // acceso a la estructura), "SQLite" (gestión de archivos .db/.sqlite), "Backups" (crear/
    // eliminar/backup/restore de MySQL y PostgreSQL — antes 2 cards largas y separadas).
    // Reorganización puramente visual — TODAS las acciones/botones que existían siguen
    // presentes, solo cambia dónde viven. `actionButton()`/`switchRow()` siguen agregando a
    // `container` (no aceptan `parent`, a diferencia de `addCard()`) — dentro de una pestaña se
    // arman con `addView(createActionButton(...))`/`addView(switchRow(...).root)` en su lugar,
    // mismo patrón que usó CiberseguridadFragment.renderReconocimientoTab()/renderWebTab().
    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        setupTabs(
            listOf(getString(R.string.db_tab_motores), getString(R.string.db_tab_sqlite), getString(R.string.db_tab_backups)),
            tabMode = TabLayout.MODE_FIXED
        )
            .tab(0) { buildMotoresTab(it) }
            .tab(1) { buildSqliteTab(it) }
            .tab(2) { buildBackupsTab(it) }
            .build()

        // Gap real (auditoría de consistencia de menús 2026-08-19): este módulo no tenía
        // NINGÚN botón de Actualizar/Desinstalar desde su propia pantalla — GenericModuleFragment
        // lo da gratis a cualquier módulo sin pantalla propia. Ver BaseModuleFragment.
        // addMaintenanceCard().
        addMaintenanceCard()

        refreshServerStatus()
    }

    private fun buildMotoresTab(parent: LinearLayout) {
        addCard(getString(R.string.db_card_estado), parent) {
            addView(infoRow(getString(R.string.db_engine_mysql_label), "…").also { mysqlValue = it.valueTextView() })
            addView(infoRow(getString(R.string.db_engine_postgres_label), "…").also { postgresValue = it.valueTextView() })
            addView(infoRow(getString(R.string.db_engine_sqlite_label), "…").also { sqliteValue = it.valueTextView() })
            // Redis agregado v1.1.0 de modulos/db.sh (cruce contra referencia/termux/
            // core-termux-main/, ver docs/viejo/AUDITORIA_MODULOS_SISTEMA_VS_REFERENCIA_2026-08-19.md)
            addView(infoRow(getString(R.string.db_engine_redis_label), "…").also { redisValue = it.valueTextView() })
            addView(createActionButton(getString(R.string.db_btn_refresh_status), GHOST) { refreshServerStatus() })
        }

        addCard(getString(R.string.db_card_servidores), parent) {
            mysqlSwitch = switchRow(getString(R.string.db_engine_mysql_label)) { on -> if (on) startServer("mysql") else stopServer("mysql") }
            addView(mysqlSwitch.root)
            postgresSwitch = switchRow(getString(R.string.db_engine_postgres_label)) { on -> if (on) startServer("postgres") else stopServer("postgres") }
            addView(postgresSwitch.root)
            // Aviso preventivo, no bloqueante (bug #31 — ver docs/arquitectura/
            // AUDITORIA_ADB_DISPOSITIVO_REAL_2026-08-21.md Ronda 5, causa raíz confirmada con
            // strace en dispositivo real: postgres --check e initdb se cuelgan leyendo un
            // socketpair interno de self-pipe/latch que nunca recibe su write() — no es un
            // problema de configuración, es una limitación del sandbox de Android en ese
            // hardware puntual). Solo se probó en 1 dispositivo real, así que el texto dice
            // "puede" y no generaliza a "siempre falla".
            addView(TextView(requireContext()).apply {
                text = getString(R.string.db_postgres_warning)
                textSize = 11f
                setPadding(dp(14), 0, dp(14), dp(8))
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosAmber))
            })
            redisSwitch = switchRow(getString(R.string.db_engine_redis_label)) { on -> if (on) startServer("redis") else stopServer("redis") }
            addView(redisSwitch.root)
        }

        // Pedido explícito del usuario (ver docs/humano/humano115.md): vista del esquema real
        // (tablas por categoría + mapa mental con relaciones FK) para los 3 motores, en
        // DbSchemaFragment.kt.
        addCard(getString(R.string.db_card_estructura), parent) {
            addView(createActionButton(getString(R.string.db_btn_estructura), GHOST) { navigateTo(DbSchemaFragment()) })
        }
    }

    private fun buildSqliteTab(parent: LinearLayout) {
        addCard(getString(R.string.db_card_sqlite), parent) {
            addView(createActionButton(getString(R.string.db_btn_list_dbs), GHOST) { runSqliteAction("list-dbs") })
            addView(createActionButton(getString(R.string.db_btn_open_db), GHOST) { promptAndRun("open") })
            addView(createActionButton(getString(R.string.db_btn_view_tables), GHOST) { promptAndRun("tables") })
            addView(createActionButton(getString(R.string.db_btn_n8n_db), GHOST) { runSqliteAction("n8n-db") })
            addView(createActionButton(getString(R.string.db_btn_export_csv), GHOST) { promptAndRun("export-csv") })
            addView(createActionButton(getString(R.string.db_btn_create_db_empty), GHOST) { promptAndRun("create-db") })
            addView(createActionButton(getString(R.string.db_btn_backup_db), GHOST) { promptAndRun("backup") })
            addView(createActionButton(getString(R.string.db_btn_query_sql), GHOST) { promptAndRun("query") })
        }
    }

    private fun buildBackupsTab(parent: LinearLayout) {
        // Gap real (auditoría 2026-08-17): antes MySQL/PostgreSQL solo tenían
        // start/stop — crear/eliminar una BD o sacar un backup exigía abrir la terminal a
        // mano y recordar la sintaxis de mysql/psql. Mismos comandos reales que ya usa
        // DbSchemaFragment (mysql -u root, psql -U "$(whoami)" — defaults documentados de
        // modulos/db.sh: root sin contraseña, superusuario Postgres = usuario Linux).
        addCard(getString(R.string.db_card_mysql_dbs), parent) {
            addView(createActionButton(getString(R.string.db_btn_create_db), GHOST) { promptDbName(getString(R.string.db_dialog_title_create_mysql)) { name -> createMysqlDb(name) } })
            addView(createActionButton(getString(R.string.db_btn_delete_db), GHOST) { promptDbName(getString(R.string.db_dialog_title_delete_mysql)) { name -> confirmDropDb(getString(R.string.db_engine_mysql_label), name) { dropMysqlDb(name) } } })
            addView(createActionButton(getString(R.string.db_btn_backup_mysqldump), GHOST) { promptDbName(getString(R.string.db_dialog_title_backup_mysql)) { name -> backupMysqlDb(name) } })
            // Auditoría 2026-08-25: faltaba el complemento real de "Backup" — un .sql generado
            // no servía de nada sin forma de restaurarlo desde la app (había que abrir terminal
            // a mano). Mismo patrón "listar backups reales de ~/backups/db, elegir" que se usó
            // hoy mismo para Engram import/export.
            addView(createActionButton(getString(R.string.db_btn_restore_backup), GHOST) { promptRestoreBackup("mysql", getString(R.string.db_engine_mysql_label)) { name, file -> restoreMysqlDb(name, file) } })
        }
        addCard(getString(R.string.db_card_postgres_dbs), parent) {
            addView(createActionButton(getString(R.string.db_btn_create_db), GHOST) { promptDbName(getString(R.string.db_dialog_title_create_pg)) { name -> createPgDb(name) } })
            addView(createActionButton(getString(R.string.db_btn_delete_db), GHOST) { promptDbName(getString(R.string.db_dialog_title_delete_pg)) { name -> confirmDropDb(getString(R.string.db_engine_postgres_label), name) { dropPgDb(name) } } })
            addView(createActionButton(getString(R.string.db_btn_backup_pgdump), GHOST) { promptDbName(getString(R.string.db_dialog_title_backup_pg)) { name -> backupPgDb(name) } })
            addView(createActionButton(getString(R.string.db_btn_restore_backup), GHOST) { promptRestoreBackup("postgres", getString(R.string.db_engine_postgres_label)) { name, file -> restorePgDb(name, file) } })
        }
    }

    // ────────────────────────────────────────────────────────────
    // Estado de servidores: pgrep en vivo + registry (versiones)
    // ────────────────────────────────────────────────────────────

    private fun refreshServerStatus() {
        Thread {
            val mysqlRunning = isAlive("mysqld")
            val postgresRunning = isAlive("postgres")
            val redisRunning = isAlive("redis-server")
            val registry = try {
                ModuleRegistry(requireContext()).load()
            } catch (_: Exception) {
                null
            }
            val mysqlVer = registry?.get("mysql.version") ?: ""
            val postgresVer = registry?.get("postgres.version") ?: ""
            val sqliteVer = registry?.get("sqlite.version") ?: ""
            val redisVer = registry?.get("redis.version") ?: ""
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                mysqlValue?.text = statusText(mysqlRunning, mysqlVer)
                mysqlValue?.setTextColor(activeColor(mysqlRunning))
                postgresValue?.text = statusText(postgresRunning, postgresVer)
                postgresValue?.setTextColor(activeColor(postgresRunning))
                sqliteValue?.text = if (sqliteVer.isNotEmpty()) getString(R.string.db_version_only, sqliteVer) else getString(R.string.db_dash)
                redisValue?.text = if (redisVer.isNotEmpty()) statusText(redisRunning, redisVer) else getString(R.string.db_dash)
                redisValue?.setTextColor(activeColor(redisRunning))
                mysqlSwitch.setSwitchState(mysqlRunning)
                postgresSwitch.setSwitchState(postgresRunning)
                redisSwitch.setSwitchState(redisRunning)
            }
        }.start()
    }

    private fun statusText(running: Boolean, version: String): String {
        val prefix = if (running) getString(R.string.db_status_active) else getString(R.string.db_status_inactive)
        return if (version.isNotEmpty()) getString(R.string.db_status_with_version, prefix, version) else prefix
    }

    private fun activeColor(running: Boolean): Int =
        requireContext().kairosThemeColor(if (running) R.attr.kairosGreen else R.attr.kairosText3)

    private fun isAlive(process: String): Boolean {
        // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): "pgrep -x postgres"
        // y "pgrep -x redis-server" devuelven exit 1 (no match) pese a que ambos procesos
        // corren de verdad y /proc/<pid>/comm confirma el nombre exacto — el switch de la app
        // quedaba mostrando "inactivo" con el servidor real corriendo. "pgrep -x mariadbd" SÍ
        // funciona (no es que -x esté roto en general), pero "pgrep postgres"/"pgrep
        // redis-server" (sin -x) confirmados funcionando para los 3 en pruebas reales en
        // dispositivo — se saca -x acá, aceptando el riesgo teórico de un falso positivo por
        // substring (sin conflicto real conocido entre mysqld/postgres/redis-server y otro
        // proceso del sistema).
        val pb = ProcessBuilder(TERMUX_PGREP_PATH, process)
        return try {
            pb.applyTermuxEnv()
            pb.start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun startServer(which: String) {
        val base = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/db")
        val script = File(base, "${which}_start.sh")
        runServerScript(script.absolutePath, getString(R.string.db_starting_engine, which), which)
    }

    private fun stopServer(which: String) {
        val base = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/db")
        val script = File(base, "${which}_stop.sh")
        runServerScript(script.absolutePath, getString(R.string.db_stopping_engine, which), which)
    }

    private fun runServerScript(script: String, loadingMsg: String, which: String) {
        val v = view ?: return
        Snackbar.make(v, loadingMsg, Snackbar.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val output = pb.start().inputStream.bufferedReader().use { it.readText() }
                output.lines().firstOrNull { it.startsWith("[OK]") || it.startsWith("[ERROR]") } ?: output
            } catch (e: Exception) {
                friendlyProcessErrorMessage(e, getString(R.string.db_module_name))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(v, postgresErrorHint(which, result), Snackbar.LENGTH_LONG).show()
                refreshServerStatus()
            }
        }.start()
    }

    // Bug #31 (ver docs/adb/AUDITORIA_ADB_DISPOSITIVO_REAL_2026-08-21.md Ronda 5): en
    // el dispositivo real de pruebas, postgres_start.sh falla porque el motor se cuelga (bloqueo
    // interno confirmado con strace, no un error de configuración) — modulos/db.sh ya lo detecta
    // y corta con timeout en vez de colgarse para siempre, pero el mensaje crudo del script
    // ("[ERROR] ...") no explica la causa real. Acá se agrega esa causa solo cuando el intento
    // fue justamente sobre postgres y el resultado marca fallo.
    private fun postgresErrorHint(which: String, result: String): String {
        if (which != "postgres" || !result.startsWith("[ERROR]")) return result
        return result + getString(R.string.db_postgres_error_hint)
    }

    // ────────────────────────────────────────────────────────────
    // MySQL/PostgreSQL — crear/eliminar BD + backup vía CLI real (mysql/psql/mysqldump/
    // pg_dump, los mismos binarios que instala modulos/db.sh). Sin driver JDBC en la app
    // (Android no lo trae) — mismo patrón que DbSchemaFragment.runShell()/mysqlExec()/
    // psqlExec(), pero acá no hace falta parsear el resultado, solo saber si salió bien.
    // ────────────────────────────────────────────────────────────

    private val dbNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    private fun promptDbName(title: String, onValid: (String) -> Unit) {
        val ctx = requireContext()
        val edit = EditText(ctx).apply { hint = getString(R.string.db_hint_db_name) }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton(getString(R.string.db_ok)) { _, _ ->
                val name = edit.text.toString().trim()
                if (!dbNamePattern.matches(name)) {
                    toast(getString(R.string.db_invalid_name))
                } else {
                    onValid(name)
                }
            }
            .setNegativeButton(getString(R.string.db_cancel), null)
            .show()
    }

    private fun confirmDropDb(engine: String, name: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.db_dialog_title_delete_engine, engine))
            .setMessage(getString(R.string.db_confirm_delete_message, name))
            .setPositiveButton(getString(R.string.db_delete)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.db_cancel), null)
            .show()
    }

    private fun backupsDir(): File =
        File(TermuxConstants.TERMUX_HOME_DIR_PATH, "backups/db").also { it.mkdirs() }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

    private fun runShellCommand(command: String): Result<String> {
        return try {
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
            pb.applyTermuxEnv()
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0) Result.success(output) else Result.failure(IllegalStateException(output.trim().ifBlank { getString(R.string.db_err_exit_code, exit) }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun runDbAction(loadingMsg: String, command: () -> String) {
        val v = view ?: return
        Snackbar.make(v, loadingMsg, Snackbar.LENGTH_SHORT).show()
        Thread {
            val result = runShellCommand(command())
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val msg = result.fold({ getString(R.string.db_ok) }, { e -> getString(R.string.db_action_error, e.message) })
                Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }

    // Defaults documentados de modulos/db.sh: mariadb-install-db corre con
    // --auth-root-authentication-method=normal → root sin contraseña.
    // Backticks sin escapar a propósito: dentro de comillas simples de bash el backtick no
    // dispara command substitution (eso solo pasa con comillas dobles/sin comillas), así que
    // un backslash antes ("\\`") viajaría literal a mysql y rompería el identifier quoting.
    private fun createMysqlDb(name: String) =
        runDbAction(getString(R.string.db_creating_mysql, name)) { "mysql -u root -e 'CREATE DATABASE IF NOT EXISTS `$name`;'" }

    private fun dropMysqlDb(name: String) =
        runDbAction(getString(R.string.db_deleting_mysql, name)) { "mysql -u root -e 'DROP DATABASE IF EXISTS `$name`;'" }

    private fun backupMysqlDb(name: String) {
        val out = File(backupsDir(), "mysql_${name}_${timestamp()}.sql")
        runDbAction(getString(R.string.db_backup_mysql_progress, name)) { "mysqldump -u root ${shellQuote(name)} > ${shellQuote(out.absolutePath)}" }
    }

    // Defaults documentados de modulos/db.sh: initdb corre con -U "$(whoami)" → el
    // superusuario de Postgres es el usuario Linux de la app (no siempre "postgres").
    private fun createPgDb(name: String) =
        runDbAction(getString(R.string.db_creating_pg, name)) { "psql -U \"\$(whoami)\" -d postgres -c 'CREATE DATABASE $name;'" }

    private fun dropPgDb(name: String) =
        runDbAction(getString(R.string.db_deleting_pg, name)) { "psql -U \"\$(whoami)\" -d postgres -c 'DROP DATABASE IF EXISTS $name;'" }

    private fun backupPgDb(name: String) {
        val out = File(backupsDir(), "postgres_${name}_${timestamp()}.sql")
        runDbAction(getString(R.string.db_backup_pg_progress, name)) { "pg_dump -U \"\$(whoami)\" ${shellQuote(name)} > ${shellQuote(out.absolutePath)}" }
    }

    // Restaurar: lista los .sql reales de ~/backups/db (mismo lugar que backupMysqlDb()/
    // backupPgDb() ya escriben) filtrados por prefijo de motor, para no obligar al usuario a
    // escribir la ruta completa a mano. La BD destino se crea antes de importar (mysql/psql
    // fallan si no existe) — mismo criterio que createMysqlDb()/createPgDb() de arriba.
    private fun promptRestoreBackup(enginePrefix: String, engineLabel: String, onRestore: (String, File) -> Unit) {
        val files = backupsDir().listFiles { f -> f.isFile && f.name.startsWith("${enginePrefix}_") && f.name.endsWith(".sql") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            toast(getString(R.string.db_no_backups_found, engineLabel))
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.db_dialog_title_restore_engine, engineLabel))
            .setItems(names) { _, which ->
                val file = files[which]
                // El nombre de BD original está en el propio nombre del archivo
                // (<engine>_<nombre>_<timestamp>.sql, ver backupMysqlDb()/backupPgDb()) — se
                // usa como sugerencia visual (toast), el nombre final lo confirma el usuario en
                // promptDbName() por si quiere restaurar con otro nombre.
                val guessedName = file.name.removePrefix("${enginePrefix}_").substringBeforeLast('_')
                toast(getString(R.string.db_suggested_name, guessedName))
                promptDbName(getString(R.string.db_dialog_title_restore_target)) { name -> onRestore(name, file) }
            }
            .setNegativeButton(getString(R.string.db_cancel), null)
            .show()
    }

    private fun restoreMysqlDb(name: String, file: File) {
        runDbAction(getString(R.string.db_restoring_mysql, name)) {
            "mysql -u root -e 'CREATE DATABASE IF NOT EXISTS `$name`;' && mysql -u root ${shellQuote(name)} < ${shellQuote(file.absolutePath)}"
        }
    }

    private fun restorePgDb(name: String, file: File) {
        runDbAction(getString(R.string.db_restoring_pg, name)) {
            "psql -U \"\$(whoami)\" -d postgres -c 'CREATE DATABASE $name;' 2>/dev/null; psql -U \"\$(whoami)\" -d ${shellQuote(name)} -f ${shellQuote(file.absolutePath)}"
        }
    }

    // ────────────────────────────────────────────────────────────
    // SQLite — lógica heredada de SqliteFragment (2026-08-10: movida acá junto con
    // MySQL/PostgreSQL al crear el módulo db; SQLite ya no es submenú de Python).
    // ────────────────────────────────────────────────────────────

    private val skipDirs = setOf("node_modules", ".git", "venv", ".venv", ".gradle", "build", "__pycache__")

    private val homeDir get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val rootfsBase get() = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro/installed-rootfs")

    private fun humanSize(bytes: Long): String {
        var b = bytes.toDouble()
        for (u in listOf("B", "KB", "MB", "GB")) {
            if (b < 1024) return String.format("%.1f%s", b, u)
            b /= 1024
        }
        return String.format("%.1fTB", b)
    }

    private fun runSqliteAction(action: String, vararg extraArgs: String) {
        Thread {
            val json = try {
                when (action) {
                    "list-dbs" -> buildListDbsJson()
                    "tables" -> buildTablesJson(extraArgs.getOrNull(0))
                    "export-csv" -> buildExportCsvJson(extraArgs.getOrNull(0), extraArgs.getOrNull(1))
                    "create-db" -> buildCreateDbJson(extraArgs.getOrNull(0))
                    "backup" -> buildBackupJson(extraArgs.getOrNull(0))
                    "query" -> buildQueryJson(extraArgs.getOrNull(0), extraArgs.drop(1))
                    "n8n-db" -> buildN8nDbJson()
                    else -> JSONObject().put("ok", false).put("error", getString(R.string.db_err_unknown_action, action))
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_unknown))
            }
            val ok = json.optBoolean("ok", false)
            if (ok && action == "list-dbs") dbList = json
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val msg = if (ok) getString(R.string.db_ok) else getString(R.string.db_action_error, json.optString("error", getString(R.string.db_err_unknown)))
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buildListDbsJson(): JSONObject {
        val home = homeDir
        val dbs = JSONArray()
        collectDbFiles(home, 0, 3, home.absolutePath, dbs)
        val n8nDb = File(rootfsBase, "debian/root/.n8n/database.sqlite")
        if (n8nDb.exists()) {
            dbs.put(
                JSONObject()
                    .put("path", n8nDb.absolutePath)
                    .put("name", getString(R.string.db_n8n_db_display_name))
                    .put("size", n8nDb.length())
                    .put("size_human", humanSize(n8nDb.length()))
                    .put("is_n8n", true)
            )
        }
        return JSONObject().put("ok", true).put("databases", dbs).put("count", dbs.length())
    }

    private fun collectDbFiles(dir: File, depth: Int, maxDepth: Int, homePath: String, out: JSONArray) {
        if (depth >= maxDepth) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isFile && (f.name.endsWith(".db") || f.name.endsWith(".sqlite"))) {
                out.put(
                    JSONObject()
                        .put("path", f.absolutePath)
                        .put("name", f.name)
                        .put("size", f.length())
                        .put("size_human", humanSize(f.length()))
                        .put("display", f.absolutePath.replace(homePath, "~"))
                )
            }
        }
        for (d in children) {
            if (d.isDirectory && d.name !in skipDirs) collectDbFiles(d, depth + 1, maxDepth, homePath, out)
        }
    }

    private fun buildTablesJson(dbPath: String?): JSONObject {
        if (dbPath.isNullOrBlank() || !File(dbPath).exists()) {
            return JSONObject().put("ok", false).put("error", getString(R.string.db_err_db_not_found))
        }
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val tables = JSONArray()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
                while (c.moveToNext()) tables.put(c.getString(0))
            }
            JSONObject().put("ok", true).put("tables", tables).put("count", tables.length()).put("database", dbPath)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_generic))
        } finally {
            db?.close()
        }
    }

    private fun buildExportCsvJson(dbPath: String?, table: String?): JSONObject {
        if (dbPath.isNullOrBlank() || table.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("error", getString(R.string.db_err_usage_export_csv))
        }
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.db_err_db_not_found))
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val tableNames = if (table != "all") {
                listOf(table)
            } else {
                val names = mutableListOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) names.add(c.getString(0))
                }
                names
            }
            val exported = JSONArray()
            for (t in tableNames) {
                val csvFile = File(dbFile.parentFile, "$t.csv")
                var rows = 0
                db.rawQuery("SELECT * FROM [$t]", null).use { c ->
                    csvFile.bufferedWriter().use { w ->
                        w.write(c.columnNames.joinToString(","))
                        w.newLine()
                        while (c.moveToNext()) {
                            val values = (0 until c.columnCount).map { i -> cellToString(c, i) }
                            w.write(values.joinToString(","))
                            w.newLine()
                            rows++
                        }
                    }
                }
                exported.put(JSONObject().put("table", t).put("path", csvFile.absolutePath).put("rows", rows))
            }
            JSONObject().put("ok", true).put("exported", exported).put("count", exported.length())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_generic))
        } finally {
            db?.close()
        }
    }

    // Backup real de un archivo SQLite (pedido de auditoría 2026-08-17: MySQL/PostgreSQL ya
    // tienen mysqldump/pg_dump arriba, SQLite no tenía equivalente). File.copyTo alcanza
    // porque SQLite guarda todo en un único archivo — no hace falta el comando ".backup" del
    // CLI sqlite3 (que además requeriría lanzar un subproceso solo para esto).
    private fun buildBackupJson(dbPath: String?): JSONObject {
        if (dbPath.isNullOrBlank() || !File(dbPath).exists()) {
            return JSONObject().put("ok", false).put("error", getString(R.string.db_err_db_not_found))
        }
        val src = File(dbPath)
        val out = File(backupsDir(), "${src.nameWithoutExtension}_${timestamp()}.bak")
        return try {
            src.copyTo(out, overwrite = true)
            JSONObject().put("ok", true).put("message", getString(R.string.db_msg_backup_created)).put("path", out.absolutePath)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_generic))
        }
    }

    private fun buildCreateDbJson(name: String?): JSONObject {
        if (name.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.db_err_missing_name))
        val fp = File(homeDir, "$name.db")
        if (fp.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.db_err_already_exists))
        return try {
            SQLiteDatabase.openOrCreateDatabase(fp, null).close()
            JSONObject().put("ok", true).put("message", getString(R.string.db_msg_db_created)).put("path", fp.absolutePath)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_generic))
        }
    }

    private fun buildQueryJson(dbPath: String?, sqlParts: List<String>): JSONObject {
        if (dbPath.isNullOrBlank() || sqlParts.isEmpty()) {
            return JSONObject().put("ok", false).put("error", getString(R.string.db_err_usage_query))
        }
        if (!File(dbPath).exists()) return JSONObject().put("ok", false).put("error", getString(R.string.db_err_db_not_found))
        val sql = sqlParts.joinToString(" ")
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            val trimmed = sql.trim().trimEnd(';').trim().uppercase()
            if (trimmed.startsWith("SELECT") || trimmed.startsWith("PRAGMA") || trimmed.startsWith("WITH") || trimmed.startsWith("EXPLAIN")) {
                db.rawQuery(sql, null).use { c ->
                    val headers = JSONArray(c.columnNames.toList())
                    val rows = JSONArray()
                    while (c.moveToNext()) {
                        val row = JSONObject()
                        for (i in 0 until c.columnCount) row.put(c.getColumnName(i), cellToString(c, i))
                        rows.put(row)
                    }
                    JSONObject().put("ok", true).put("headers", headers).put("rows", rows).put("count", rows.length())
                }
            } else {
                db.execSQL(sql)
                var affected = -1
                try {
                    db.rawQuery("SELECT changes()", null).use { c -> if (c.moveToFirst()) affected = c.getInt(0) }
                } catch (_: Exception) { /* changes() no disponible, se deja -1 */ }
                JSONObject().put("ok", true).put("message", getString(R.string.db_msg_executed)).put("affected", affected)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", getString(R.string.db_err_sql, e.message))
        } finally {
            db?.close()
        }
    }

    private fun buildN8nDbJson(): JSONObject {
        var n8nDb = File(rootfsBase, "debian/root/.n8n/database.sqlite")
        if (!n8nDb.exists()) {
            findFileByName(rootfsBase, "database.sqlite", 0, 10)?.let { n8nDb = it }
        }
        if (!n8nDb.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.db_err_n8n_db_not_found))
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(n8nDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tables = JSONArray()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
                while (c.moveToNext()) tables.put(c.getString(0))
            }
            JSONObject().put("ok", true).put("path", n8nDb.absolutePath).put("tables", tables).put("count", tables.length())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.db_err_generic))
        } finally {
            db?.close()
        }
    }

    private fun findFileByName(dir: File, name: String, depth: Int, maxDepth: Int): File? {
        if (depth >= maxDepth || !dir.isDirectory) return null
        val children = dir.listFiles() ?: return null
        for (f in children) {
            if (f.isFile && f.name == name) return f
        }
        for (d in children) {
            if (d.isDirectory) {
                findFileByName(d, name, depth + 1, maxDepth)?.let { return it }
            }
        }
        return null
    }

    private fun cellToString(c: Cursor, i: Int): String {
        return when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> ""
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
            Cursor.FIELD_TYPE_BLOB -> "<blob>"
            else -> c.getString(i) ?: ""
        }
    }

    private fun promptAndRun(action: String) {
        when (action) {
            "export-csv" -> promptMultiAndRun(action, listOf(getString(R.string.db_hint_ruta_bd), getString(R.string.db_hint_tabla)))
            "query" -> promptMultiAndRun(action, listOf(getString(R.string.db_hint_ruta_bd), getString(R.string.db_hint_sql_query)))
            // "open" abre la BD con el CLI real de sqlite3 en la terminal (lo instala
            // modulos/db.sh) — antes caía en "Acción desconocida: open" (bug heredado de
            // SqliteFragment). shellQuote() evita inyección de shell en la ruta (ver
            // ProcessBuilderExt.kt).
            "open" -> {
                val ctx = requireContext()
                val edit = EditText(ctx)
                edit.hint = getString(R.string.db_hint_ruta_bd)
                AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.db_dialog_title_open_db))
                    .setView(edit)
                    .setPositiveButton(getString(R.string.db_ok)) { _, _ ->
                        val value = edit.text.toString()
                        if (value.isNotBlank()) {
                            launchTerminalCommand("sqlite3 ${shellQuote(value)}", getString(R.string.db_tab_sqlite))
                        }
                    }
                    .setNegativeButton(getString(R.string.db_cancel), null)
                    .show()
            }
            else -> {
                val ctx = requireContext()
                val edit = EditText(ctx)
                edit.hint = when (action) {
                    "tables" -> getString(R.string.db_hint_ruta_bd)
                    "create-db" -> getString(R.string.db_hint_nombre_db)
                    "backup" -> getString(R.string.db_hint_ruta_backup)
                    else -> getString(R.string.db_hint_valor)
                }
                AlertDialog.Builder(ctx)
                    .setTitle(action)
                    .setView(edit)
                    .setPositiveButton(getString(R.string.db_ok)) { _, _ ->
                        val value = edit.text.toString()
                        if (value.isNotBlank()) runSqliteAction(action, value)
                    }
                    .setNegativeButton(getString(R.string.db_cancel), null)
                    .show()
            }
        }
    }

    private fun promptMultiAndRun(action: String, hints: List<String>) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val edits = hints.map { h -> EditText(ctx).apply { hint = h }.also { layout.addView(it) } }
        AlertDialog.Builder(ctx)
            .setTitle(action)
            .setView(layout)
            .setPositiveButton(getString(R.string.db_ok)) { _, _ ->
                val values = edits.map { it.text.toString() }
                if (values.all { it.isNotBlank() }) runSqliteAction(action, *values.toTypedArray())
            }
            .setNegativeButton(getString(R.string.db_cancel), null)
            .show()
    }
}
