package com.termux.app.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PGREP_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.shellQuote
import com.termux.shared.termux.TermuxConstants
import java.io.File
import com.termux.app.util.kairosThemeColor

private enum class DbEngine { SQLITE, MYSQL, POSTGRES }
private enum class SchemaViewMode { CATEGORY, MINDMAP }

/**
 * Pantalla "Estructura de la BD" (pedido explícito, ver docs/humano/humano115.md): muestra el
 * esquema REAL de una base — tablas agrupadas por categoría derivada del propio esquema, y un
 * mapa mental (Canvas) con nodos=tablas y líneas=relaciones FK reales. Primera versión
 * funcional: sin drag/zoom/pan propios (el pedido explícito los marca como no necesarios acá),
 * sin editor, sin librerías nuevas — todo con Canvas/View estándar (ver SchemaGraphView).
 *
 * MySQL/PostgreSQL no tienen driver JDBC en la app (Android no lo trae) — se consulta vía el
 * cliente CLI que instala modulos/db.sh (mysql/psql), igual que el resto de DbFragment ya hace
 * con sqlite3 para la acción "Abrir BD (interactivo)". SQLite se consulta 100% en proceso con
 * SQLiteDatabase + sqlite_master/PRAGMA foreign_key_list, sin ningún subproceso.
 */
class DbSchemaFragment : Fragment() {

    private var statusText: TextView? = null
    private var engineRow: LinearLayout? = null
    private var dbRow: LinearLayout? = null
    private var viewModeRow: LinearLayout? = null
    private var categoryList: LinearLayout? = null
    private var categoryScroll: ScrollView? = null
    private var graphScroll: HorizontalScrollView? = null
    private var graphView: SchemaGraphView? = null

    private var selectedEngine = DbEngine.SQLITE
    private var selectedDatabase: String? = null
    private var currentDbList: List<Pair<String, String>> = emptyList()
    private var currentSchema: SchemaResult? = null
    private var viewMode = SchemaViewMode.CATEGORY
    private var postgresUser: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        topBar.addView(ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener { parentFragmentManager.popBackStack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        topBar.addView(TextView(ctx).apply {
            text = getString(R.string.db_schema_title)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        root.addView(topBar)

        val engineScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val engineRowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        engineScroll.addView(engineRowView)
        root.addView(engineScroll)
        engineRow = engineRowView

        val dbScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val dbRowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(0), dp(10), dp(6))
        }
        dbScroll.addView(dbRowView)
        root.addView(dbScroll)
        dbRow = dbRowView

        val status = TextView(ctx).apply {
            text = getString(R.string.db_schema_loading_dbs)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(14), dp(4), dp(14), dp(8))
        }
        root.addView(status)
        statusText = status

        val modeRowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(0), dp(10), dp(6))
        }
        root.addView(modeRowView)
        viewModeRow = modeRowView

        val contentContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root.addView(contentContainer)

        val categoryListView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(0), dp(6), dp(24))
        }
        val categoryScrollView = ScrollView(ctx).apply {
            addView(categoryListView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        contentContainer.addView(categoryScrollView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        categoryList = categoryListView
        categoryScroll = categoryScrollView

        val graph = SchemaGraphView(ctx)
        graphView = graph
        val innerGraphScroll = ScrollView(ctx).apply {
            addView(graph, FrameLayout.LayoutParams(dp(700), dp(700)))
        }
        val outerGraphScroll = HorizontalScrollView(ctx).apply {
            addView(innerGraphScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            visibility = View.GONE
        }
        contentContainer.addView(outerGraphScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        graphScroll = outerGraphScroll

        rebuildEngineChips()
        rebuildViewModeChips()
        loadDatabaseList()
        return root
    }

    override fun onDestroyView() {
        statusText = null
        engineRow = null
        dbRow = null
        viewModeRow = null
        categoryList = null
        categoryScroll = null
        graphScroll = null
        graphView = null
        super.onDestroyView()
    }

    // ────────────────────────────────────────────────────────────
    // Selectores (motor / BD / vista) — chips construidos a mano, sin
    // dependencias nuevas (mismo estilo visual que pill() de BaseModuleFragment).
    // ────────────────────────────────────────────────────────────

    private fun rebuildEngineChips() {
        val row = engineRow ?: return
        row.removeAllViews()
        val engines = listOf(
            DbEngine.SQLITE to getString(R.string.db_schema_engine_sqlite),
            DbEngine.MYSQL to getString(R.string.db_schema_engine_mysql),
            DbEngine.POSTGRES to getString(R.string.db_schema_engine_postgres)
        )
        for ((engine, label) in engines) {
            row.addView(chip(label, engine == selectedEngine) {
                if (selectedEngine != engine) {
                    selectedEngine = engine
                    selectedDatabase = null
                    currentSchema = null
                    postgresUser = null
                    rebuildEngineChips()
                    clearContent()
                    loadDatabaseList()
                }
            })
        }
    }

    private fun rebuildDbChips() {
        val row = dbRow ?: return
        row.removeAllViews()
        for ((id, label) in currentDbList) {
            row.addView(chip(label, id == selectedDatabase) {
                if (selectedDatabase != id) {
                    selectedDatabase = id
                    rebuildDbChips()
                    loadSchema(selectedEngine, id)
                }
            })
        }
    }

    private fun rebuildViewModeChips() {
        val row = viewModeRow ?: return
        row.removeAllViews()
        val modes = listOf(
            SchemaViewMode.CATEGORY to getString(R.string.db_schema_view_category),
            SchemaViewMode.MINDMAP to getString(R.string.db_schema_view_mindmap)
        )
        for ((mode, label) in modes) {
            row.addView(chip(label, mode == viewMode) {
                if (viewMode != mode) {
                    viewMode = mode
                    rebuildViewModeChips()
                    render()
                }
            })
        }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(ctx.kairosThemeColor(if (selected) R.attr.kairosBg else R.attr.kairosText2))
            setBackgroundColor(ctx.kairosThemeColor(if (selected) R.attr.kairosGreen else R.attr.kairosBg3))
            if (selected) setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = dp(8) }
        }
    }

    private fun clearContent() {
        currentDbList = emptyList()
        dbRow?.removeAllViews()
        categoryList?.removeAllViews()
        graphView?.setSchema(emptyList(), emptyList())
        categoryScroll?.visibility = View.VISIBLE
        graphScroll?.visibility = View.GONE
    }

    // ────────────────────────────────────────────────────────────
    // Carga de datos (background thread — nunca bloquear UI)
    // ────────────────────────────────────────────────────────────

    private fun loadDatabaseList() {
        val engine = selectedEngine
        statusText?.text = getString(R.string.db_schema_loading_dbs)
        Thread {
            val result: Result<List<Pair<String, String>>> = try {
                when (engine) {
                    DbEngine.SQLITE -> Result.success(scanSqliteFiles().map { it.absolutePath to it.name })
                    DbEngine.MYSQL -> {
                        if (!isProcessAlive("mysqld")) {
                            throw IllegalStateException(getString(R.string.db_schema_mysql_not_running))
                        }
                        Result.success(loadMysqlDatabases().map { it to it })
                    }
                    DbEngine.POSTGRES -> {
                        if (!isProcessAlive("postgres")) {
                            throw IllegalStateException(getString(R.string.db_schema_postgres_not_running))
                        }
                        val user = resolvePostgresUser()
                        postgresUser = user
                        Result.success(loadPostgresDatabases(user).map { it to it })
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded || engine != selectedEngine) return@runOnUiThread
                result.onSuccess { list ->
                    currentDbList = list
                    rebuildDbChips()
                    statusText?.text = if (list.isEmpty()) {
                        getString(R.string.db_schema_no_dbs_found)
                    } else {
                        getString(R.string.db_schema_select_db)
                    }
                }.onFailure { e ->
                    currentDbList = emptyList()
                    dbRow?.removeAllViews()
                    val msg = e.message ?: getString(R.string.db_schema_unknown_error)
                    statusText?.text = getString(R.string.db_schema_error_prefix, msg)
                    toast(msg)
                }
            }
        }.start()
    }

    private fun loadSchema(engine: DbEngine, id: String) {
        statusText?.text = getString(R.string.db_schema_loading_schema)
        Thread {
            val result = try {
                Result.success(
                    when (engine) {
                        DbEngine.SQLITE -> loadSqliteSchema(id)
                        DbEngine.MYSQL -> loadMysqlSchema(id)
                        DbEngine.POSTGRES -> loadPostgresSchema(postgresUser ?: resolvePostgresUser(), id)
                    }
                )
            } catch (e: Exception) {
                Result.failure<SchemaResult>(e)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded || engine != selectedEngine || id != selectedDatabase) return@runOnUiThread
                result.onSuccess { schema ->
                    currentSchema = schema
                    statusText?.text = if (schema.tables.isEmpty()) {
                        getString(R.string.db_schema_no_tables)
                    } else {
                        getString(R.string.db_schema_tables_relations_count, schema.tables.size, schema.relations.size)
                    }
                    render()
                }.onFailure { e ->
                    currentSchema = null
                    val msg = e.message ?: getString(R.string.db_schema_unknown_error)
                    statusText?.text = getString(R.string.db_schema_error_loading, msg)
                    toast(msg)
                }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Render — "Por categoría" (lista agrupada) / "Mapa mental" (Canvas)
    // ────────────────────────────────────────────────────────────

    private fun render() {
        val schema = currentSchema
        when (viewMode) {
            SchemaViewMode.CATEGORY -> {
                categoryScroll?.visibility = View.VISIBLE
                graphScroll?.visibility = View.GONE
                renderCategoryView(schema)
            }
            SchemaViewMode.MINDMAP -> {
                categoryScroll?.visibility = View.GONE
                graphScroll?.visibility = View.VISIBLE
                renderMindMapView(schema)
            }
        }
    }

    private fun renderCategoryView(schema: SchemaResult?) {
        val list = categoryList ?: return
        val ctx = requireContext()
        list.removeAllViews()
        if (schema == null || schema.tables.isEmpty()) return
        val groups = categorizeTables(schema.tables)
        for ((category, tablesInGroup) in groups) {
            list.addView(TextView(ctx).apply {
                text = category
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.1f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(8), dp(16), dp(8), dp(6))
            })
            for (table in tablesInGroup.sortedBy { it.name }) {
                val outCount = schema.relations.count { it.fromTable == table.name }
                val inCount = schema.relations.count { it.toTable == table.name }
                val extra = buildString {
                    if (outCount > 0) append(getString(R.string.db_schema_fk_out, outCount))
                    if (inCount > 0) append(getString(R.string.db_schema_fk_in, inCount))
                }
                list.addView(TextView(ctx).apply {
                    text = getString(R.string.db_schema_table_row, table.name, table.columnCount, extra)
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                })
            }
        }
    }

    private fun renderMindMapView(schema: SchemaResult?) {
        val graph = graphView ?: return
        val tables = schema?.tables.orEmpty()
        val relations = schema?.relations.orEmpty()
        graph.setSchema(tables, relations)
        val density = requireContext().resources.displayMetrics.density
        val side = (Math.max(700, 170 * Math.max(tables.size, 1)) * density).toInt()
        graph.layoutParams = FrameLayout.LayoutParams(side, side)
        graph.requestLayout()
    }

    /**
     * Categoría real derivada del esquema (pedido explícito: "no inventes categorías falsas,
     * agrupá por algo que se pueda derivar del esquema real"): si 2+ tablas comparten el mismo
     * prefijo antes del primer "_" (ej. auth_users/auth_tokens → prefijo "auth"), se agrupan
     * bajo ese prefijo. El resto (sin prefijo compartido) se agrupa alfabéticamente por
     * primera letra — fallback real cuando el esquema no tiene convención de nombres.
     */
    private fun categorizeTables(tables: List<SchemaTable>): Map<String, List<SchemaTable>> {
        val prefixGroups = tables.groupBy { table ->
            val prefix = table.name.substringBefore('_', "")
            if (prefix.isNotEmpty() && prefix != table.name) prefix else null
        }
        val usedNames = mutableSetOf<String>()
        val result = LinkedHashMap<String, MutableList<SchemaTable>>()
        for ((prefix, group) in prefixGroups) {
            if (prefix != null && group.size >= 2) {
                result.getOrPut(prefix) { mutableListOf() }.addAll(group)
                usedNames.addAll(group.map { it.name })
            }
        }
        for (table in tables) {
            if (table.name in usedNames) continue
            val letter = table.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            result.getOrPut(letter) { mutableListOf() }.add(table)
        }
        return result.toSortedMap()
    }

    // ────────────────────────────────────────────────────────────
    // SQLite — 100% en proceso (sin subprocesos), mismo patrón que DbFragment.
    // ────────────────────────────────────────────────────────────

    private val skipDirs = setOf("node_modules", ".git", "venv", ".venv", ".gradle", "build", "__pycache__")

    private fun scanSqliteFiles(): List<File> {
        val out = mutableListOf<File>()
        collectDbFiles(File(TermuxConstants.TERMUX_HOME_DIR_PATH), 0, 3, out)
        return out
    }

    private fun collectDbFiles(dir: File, depth: Int, maxDepth: Int, out: MutableList<File>) {
        if (depth >= maxDepth) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isFile && (f.name.endsWith(".db") || f.name.endsWith(".sqlite"))) out.add(f)
        }
        for (d in children) {
            if (d.isDirectory && d.name !in skipDirs) collectDbFiles(d, depth + 1, maxDepth, out)
        }
    }

    private fun loadSqliteSchema(path: String): SchemaResult {
        if (!File(path).exists()) throw IllegalStateException(getString(R.string.db_schema_db_not_found, path))
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            val tableNames = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { c -> while (c.moveToNext()) tableNames.add(c.getString(0)) }

            val tables = tableNames.map { name ->
                var columnCount = 0
                db.rawQuery("PRAGMA table_info(`$name`)", null).use { c -> columnCount = c.count }
                SchemaTable(name, columnCount)
            }

            val relations = mutableListOf<SchemaRelation>()
            for (name in tableNames) {
                db.rawQuery("PRAGMA foreign_key_list(`$name`)", null).use { c ->
                    val refTableIdx = c.getColumnIndex("table")
                    while (c.moveToNext()) {
                        val refTable = if (refTableIdx >= 0) c.getString(refTableIdx) else null
                        if (!refTable.isNullOrEmpty()) relations.add(SchemaRelation(name, refTable))
                    }
                }
            }
            return SchemaResult(tables, relations)
        } finally {
            db?.close()
        }
    }

    // ────────────────────────────────────────────────────────────
    // MySQL/PostgreSQL — sin driver JDBC en la app, se consulta vía el cliente CLI
    // (mysql/psql) que instala modulos/db.sh, igual patrón que el resto de la app usa
    // para binarios de Termux (ver ProcessBuilderExt.applyTermuxEnv).
    // ────────────────────────────────────────────────────────────

    private fun isProcessAlive(processName: String): Boolean {
        return try {
            val pb = ProcessBuilder(TERMUX_PGREP_PATH, "-x", processName)
            pb.applyTermuxEnv()
            pb.start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun sqlEscape(value: String): String = value.replace("'", "''")

    private fun runShell(command: String): String {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
        pb.applyTermuxEnv()
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw IllegalStateException(output.trim().ifBlank { getString(R.string.db_err_exit_code, exitCode) })
        return output
    }

    // Defaults documentados de modulos/db.sh: mariadb-install-db corre con
    // --auth-root-authentication-method=normal → root sin contraseña.
    private fun mysqlExec(sql: String): String = runShell("mysql -u root -N -e ${shellQuote(sql)}")

    private fun loadMysqlDatabases(): List<String> {
        val skip = setOf("information_schema", "performance_schema", "mysql", "sys")
        return mysqlExec("SHOW DATABASES;").lines().map { it.trim() }.filter { it.isNotEmpty() && it !in skip }
    }

    private fun loadMysqlSchema(dbName: String): SchemaResult {
        val columnCounts = LinkedHashMap<String, Int>()
        mysqlExec(
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.columns " +
                "WHERE table_schema='${sqlEscape(dbName)}' ORDER BY TABLE_NAME, ORDINAL_POSITION;"
        ).lines().forEach { line ->
            val parts = line.split("\t")
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                columnCounts[parts[0]] = (columnCounts[parts[0]] ?: 0) + 1
            }
        }
        val tables = columnCounts.map { (name, count) -> SchemaTable(name, count) }

        val relations = mysqlExec(
            "SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.key_column_usage " +
                "WHERE table_schema='${sqlEscape(dbName)}' AND referenced_table_name IS NOT NULL;"
        ).lines().mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                SchemaRelation(parts[0], parts[1])
            } else null
        }
        return SchemaResult(tables, relations)
    }

    // Defaults documentados de modulos/db.sh: initdb corre con -U "$(whoami)" → el
    // superusuario de Postgres es el usuario Linux de la app (no siempre "postgres").
    private fun resolvePostgresUser(): String {
        return try {
            runShell("whoami").trim().ifBlank { "root" }
        } catch (_: Exception) {
            "root"
        }
    }

    private fun psqlExec(user: String, database: String, sql: String): String =
        runShell("psql -U ${shellQuote(user)} -d ${shellQuote(database)} -t -A -c ${shellQuote(sql)}")

    private fun loadPostgresDatabases(user: String): List<String> {
        return psqlExec(user, "postgres", "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname;")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadPostgresSchema(user: String, dbName: String): SchemaResult {
        val columnCounts = LinkedHashMap<String, Int>()
        psqlExec(
            user, dbName,
            "SELECT table_name, column_name FROM information_schema.columns " +
                "WHERE table_schema='public' ORDER BY table_name, ordinal_position;"
        ).lines().forEach { line ->
            val parts = line.split("|")
            val name = parts.getOrNull(0)?.trim().orEmpty()
            if (parts.size >= 2 && name.isNotEmpty()) {
                columnCounts[name] = (columnCounts[name] ?: 0) + 1
            }
        }
        val tables = columnCounts.map { (name, count) -> SchemaTable(name, count) }

        val fkSql = "SELECT tc.table_name, ccu.table_name FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
            "AND tc.table_schema = kcu.table_schema " +
            "JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name " +
            "AND ccu.table_schema = tc.table_schema " +
            "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public';"
        val relations = psqlExec(user, dbName, fkSql).lines().mapNotNull { line ->
            val parts = line.split("|")
            val from = parts.getOrNull(0)?.trim().orEmpty()
            val to = parts.getOrNull(1)?.trim().orEmpty()
            if (from.isNotEmpty() && to.isNotEmpty()) SchemaRelation(from, to) else null
        }
        return SchemaResult(tables, relations)
    }

    private fun toast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
