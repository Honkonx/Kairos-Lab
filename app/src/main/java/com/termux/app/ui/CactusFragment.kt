package com.termux.app.ui

import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.TERMUX_CACTUS_PATH
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla dedicada del módulo Cactus Needle (cactus-needle — motor de tool-calling local,
 * ver `modulos/cactus.sh`) — antes caía en GenericModuleFragment ("Abrir en terminal" nomás).
 *
 * Reusa el mismo mecanismo real que ya usa ChatFragment.dispatchCactusRun() (ver
 * `cactus run --json-only "<pedido>"` / `cactus ai --json-only "<pedido>"`, confirmado
 * leyendo el heredoc de cactus_engine.py en modulos/cactus.sh) — acá NO se modifica
 * ChatFragment, solo se reimplementa el mismo llamado a `cactus` por argv directo
 * (ManagerNativeUtils.runExec, sin shell de por medio) como pantalla propia del módulo,
 * más gestión de "tareas" guardadas y export/import de los archivos que cactus usa.
 *
 * "Con IA" (`cactus ai`) queda deshabilitado (con motivo visible) si ni Ollama (11434) ni
 * llama-server (8085) están corriendo — mismo criterio de disponibilidad que ya usa
 * ChatFragment.llamaServerAvailable()/dispatchCactusRun(), acá vía
 * ManagerNativeUtils.checkPort() (equivalente, ya existente, evita reimplementar el socket
 * check).
 */
class CactusFragment : BaseModuleFragment() {

    override fun getModuleId() = "cactus"
    override fun getModuleName() = getString(R.string.cactus_module_name)

    private val home get() = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val tasksFile get() = File(home, TASKS_FILE_NAME)
    private val engineFile get() = File(home, ENGINE_REL_PATH)

    private var mAiBackendAvailable = false
    private var mAiPromptInput: EditText? = null
    private var mAiRunButton: TextView? = null
    private var mAiDisabledNote: TextView? = null

    private lateinit var mTasksListContainer: LinearLayout

    // Debe registrarse como campo de instancia (no dentro de un onClick) — mismo requisito de
    // ciclo de vida que ConfigFragment.mPickConfigFileLauncher / ChatFragment.mPickImageLauncher.
    private val mPickImportFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { confirmImportScripts(it) }
        }

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                installModuleInBackground(null) { ok ->
                    toast(if (ok) getString(R.string.cactus_installed) else getString(R.string.cactus_install_failed))
                }
            }
            return
        }

        buildEstadoCard()
        buildSinIaCard()
        buildConIaCard()
        buildExtractCard()
        buildTareasCard()
        buildToolsCard()
        buildServerCard()
        buildScriptsCard()

        actionButton(getString(R.string.cactus_btn_terminal), GHOST) {
            launchTerminalCommand("cactus status")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Estado — ¿Ollama / llama-server corriendo? (backend del razonador de `cactus ai`)
    // ────────────────────────────────────────────────────────────

    private fun buildEstadoCard() {
        addCard(getString(R.string.cactus_card_estado)) {
            val (ollamaRow, ollamaValue) = valueRow(getString(R.string.cactus_ollama_label), getString(R.string.cactus_verificando))
            addView(ollamaRow)
            val (llamaRow, llamaValue) = valueRow(getString(R.string.cactus_llama_label), getString(R.string.cactus_verificando))
            addView(llamaRow)
            checkBackendsAsync(ollamaValue, llamaValue)
        }
    }

    private fun checkBackendsAsync(ollamaValue: TextView, llamaValue: TextView) {
        Thread {
            val ollamaUp = ManagerNativeUtils.checkPort(11434)
            val llamaUp = ManagerNativeUtils.checkPort(8085)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                setBackendStatusText(ollamaValue, ollamaUp)
                setBackendStatusText(llamaValue, llamaUp)
                mAiBackendAvailable = ollamaUp || llamaUp
                updateAiSectionEnabled()
            }
        }.start()
    }

    private fun setBackendStatusText(view: TextView, up: Boolean) {
        val ctx = requireContext()
        view.text = if (up) getString(R.string.cactus_active) else getString(R.string.cactus_inactive)
        view.setTextColor(
            ctx.kairosThemeColor(if (up) R.attr.kairosGreen else R.attr.kairosText3)
        )
    }

    private fun updateAiSectionEnabled() {
        val enabled = mAiBackendAvailable
        mAiPromptInput?.isEnabled = enabled
        mAiPromptInput?.alpha = if (enabled) 1f else 0.4f
        mAiRunButton?.isEnabled = enabled
        mAiRunButton?.alpha = if (enabled) 1f else 0.4f
        mAiDisabledNote?.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ────────────────────────────────────────────────────────────
    // "Sin IA" — cactus run --json-only "<pedido>" (needle decide la tool directo, SIN razonador)
    // ────────────────────────────────────────────────────────────

    private fun buildSinIaCard() {
        addCard(getString(R.string.cactus_card_sin_ia)) {
            val input = styledEditText(getString(R.string.cactus_hint_sin_ia))
            addView(input)
            addView(createActionButton(getString(R.string.cactus_btn_run_sin_ia), PRIMARY) {
                val query = input.text.toString().trim()
                if (query.isBlank()) { toast(getString(R.string.cactus_toast_write_request)); return@createActionButton }
                runCactus(query, useAi = false)
            })
        }
    }

    // ────────────────────────────────────────────────────────────
    // "Con IA" — cactus ai --json-only "<pedido>" (razonador Ollama/llama-server interpreta
    // primero, needle traduce y ejecuta) — deshabilitado si no hay backend disponible.
    // ────────────────────────────────────────────────────────────

    private fun buildConIaCard() {
        addCard(getString(R.string.cactus_card_con_ia)) {
            val input = styledEditText(getString(R.string.cactus_hint_con_ia))
            mAiPromptInput = input
            addView(input)
            val note = TextView(requireContext()).apply {
                text = getString(R.string.cactus_note_need_backend)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(4), dp(14), dp(4))
                visibility = android.view.View.GONE
            }
            mAiDisabledNote = note
            addView(note)
            val runButton = createActionButton(getString(R.string.cactus_btn_run_con_ia), PRIMARY) {
                val query = input.text.toString().trim()
                if (query.isBlank()) { toast(getString(R.string.cactus_toast_write_request)); return@createActionButton }
                runCactus(query, useAi = true)
            } as TextView
            mAiRunButton = runButton
            addView(runButton)
            updateAiSectionEnabled()
        }
    }

    private fun runCactus(query: String, useAi: Boolean) {
        if (useAi && !mAiBackendAvailable) {
            toast(getString(R.string.cactus_toast_start_backend))
            return
        }
        val progress = ProgressDialogController(requireContext())
        progress.show(
            if (useAi) getString(R.string.cactus_progress_ai_title) else getString(R.string.cactus_progress_run_title),
            getString(R.string.cactus_progress_ejecutando)
        )
        Thread {
            val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(cactusArgs(query, useAi), CACTUS_RUN_TIMEOUT_SECONDS)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                reportCactusResult(progress, exitCode, stdout, stderr)
            }
        }.start()
    }

    private fun cactusArgs(query: String, useAi: Boolean): List<String> =
        if (useAi) listOf(TERMUX_CACTUS_PATH, "ai", "--json-only", query)
        else listOf(TERMUX_CACTUS_PATH, "run", "--json-only", query)

    private fun reportCactusResult(progress: ProgressDialogController, exitCode: Int, stdout: String, stderr: String) {
        if (stdout.isBlank() &&
            (stderr.contains("No such file", ignoreCase = true) || stderr.contains("Cannot run program", ignoreCase = true))
        ) {
            progress.failure(getString(R.string.cactus_not_installed_error), getString(R.string.cactus_reinstall_hint))
            return
        }
        if (stdout.isBlank()) {
            progress.failure(getString(R.string.cactus_sin_salida), stderr.ifBlank { getString(R.string.cactus_exit_code, exitCode) })
            return
        }
        try {
            val summary = formatCactusResult(JSONObject(stdout))
            progress.success(getString(R.string.cactus_listo), summary)
        } catch (e: Exception) {
            val extra = if (stderr.isNotBlank()) "\n\n[stderr]\n$stderr" else ""
            progress.failure(getString(R.string.cactus_parse_error), "$stdout$extra")
        }
    }

    /** Mismo shape que arma run_needle() en cactus_engine.py: {type, confidence, results[],
     *  reasoning} — formateo propio (no se reusa el de ChatFragment, es privado ahí). */
    private fun formatCactusResult(json: JSONObject): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.cactus_result_tipo, json.optString("type", "?")))
        if (json.has("confidence") && !json.isNull("confidence")) {
            sb.append(getString(R.string.cactus_result_confianza, json.optDouble("confidence").toString()))
        }
        val reasoning = json.optString("reasoning", "")
        if (reasoning.isNotBlank()) sb.append(getString(R.string.cactus_result_razonamiento, reasoning))
        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return sb.toString().trim().ifBlank { getString(R.string.cactus_sin_resultados) }
        }
        sb.append("\n")
        for (i in 0 until results.length()) {
            val step = results.optJSONObject(i) ?: continue
            val name = step.optString("name", "?")
            sb.append("— $name\n")
            val result = step.optJSONObject("result")
            if (result != null) {
                val toolStdout = result.optString("stdout", "")
                if (toolStdout.isNotBlank()) sb.append("  ${toolStdout.trim().take(400)}\n")
                val toolStderr = result.optString("stderr", "")
                if (toolStderr.isNotBlank()) sb.append(getString(R.string.cactus_result_step_err, toolStderr.trim().take(400)))
            }
        }
        return sb.toString().trim()
    }

    // ────────────────────────────────────────────────────────────
    // "Extraer datos" — cactus extract <schema> "<texto>" --json-only. Extracción
    // estructurada real de needle (ver README needle-main, sección "Extraction" — needle la
    // trata como tool-calling con una única tool declarada, los "arguments" de esa llamada
    // SON los campos extraídos). Ronda 2026-08-25 (pedido explícito del usuario: "en cactus
    // toca agregar mas plantillas agregar por categoria"): EXTRACT_SCHEMAS de cactus_engine.py
    // pasó de 2 a 8 esquemas — "invoice"/"receipt" son los ejemplos reales del README
    // ("Quickstart"/"Extraction"), los otros 6 son casos de uso nuevos sobre el mismo mecanismo
    // genérico (needle acepta cualquier schema JSON válido, confirmado en el propio README línea
    // ~10 "a byte-level grammar compiled from your schemas" — no son capacidades nuevas del
    // motor, son plantillas nuevas de Kairos). El selector de 2 botones fijos se reemplaza por
    // un diálogo agrupado por categoría (mismas categorías que ya trae cactus_engine.py, para
    // que la UI y el motor no se puedan desincronizar — ver EXTRACT_SCHEMA_CATALOG abajo).
    // ────────────────────────────────────────────────────────────

    // (id, etiqueta visible (string resource), categoría (string resource)) — debe reflejar
    // EXTRACT_SCHEMAS de modulos/cactus.sh. No hay forma de leer el diccionario Python real
    // desde Kotlin en build-time, así que esta lista se mantiene a mano en paralelo — si se
    // agrega un schema nuevo al script, agregarlo acá también (mismo criterio que
    // CLI_MODULE_CONFIGS de CliToolFragment.kt).
    private data class ExtractSchemaOption(val id: String, val labelRes: Int, val categoryRes: Int)

    private val EXTRACT_SCHEMA_CATALOG by lazy {
        listOf(
            ExtractSchemaOption("invoice", R.string.cactus_schema_invoice, R.string.cactus_category_business_docs),
            ExtractSchemaOption("receipt", R.string.cactus_schema_receipt, R.string.cactus_category_business_docs),
            ExtractSchemaOption("purchase_order", R.string.cactus_schema_purchase_order, R.string.cactus_category_business_docs),
            ExtractSchemaOption("quote", R.string.cactus_schema_quote, R.string.cactus_category_business_docs),
            ExtractSchemaOption("business_card", R.string.cactus_schema_business_card, R.string.cactus_category_identification),
            ExtractSchemaOption("contact", R.string.cactus_schema_contact, R.string.cactus_category_identification),
            ExtractSchemaOption("meeting_notes", R.string.cactus_schema_meeting_notes, R.string.cactus_category_productivity),
            ExtractSchemaOption("event", R.string.cactus_schema_event, R.string.cactus_category_productivity)
        )
    }

    private var mExtractSchema = "invoice"

    private fun buildExtractCard() {
        addCard(getString(R.string.cactus_card_extract)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_extract_desc)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
            val schemaLabel = TextView(requireContext()).apply {
                text = getString(R.string.cactus_extract_schema_label_default)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(0), dp(14), dp(4))
            }
            addView(schemaLabel)
            addView(createActionButton(getString(R.string.cactus_btn_choose_schema), GHOST) {
                showExtractSchemaDialog(schemaLabel)
            })
            val textInput = styledEditText(getString(R.string.cactus_hint_extract_text))
            addView(textInput)
            addView(createActionButton(getString(R.string.cactus_btn_extract), PRIMARY) {
                val text = textInput.text.toString().trim()
                if (text.isBlank()) { toast(getString(R.string.cactus_toast_paste_text)); return@createActionButton }
                runExtract(mExtractSchema, text)
            })
        }
    }

    // Diálogo agrupado por categoría — un TextView de header (no clickeable) por categoría,
    // seguido de una fila clickeable por esquema. Mismo criterio visual que las cards del resto
    // de la app (header en mayúsculas/gris, filas con selectableItemBackground).
    private fun showExtractSchemaDialog(schemaLabel: TextView) {
        val ctx = requireContext()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // `dialogRef` se asigna DESPUÉS de crear el diálogo — los listeners de abajo solo lo
        // usan al tocarlos (ya con el diálogo mostrado y la referencia asignada), Kotlin captura
        // la variable por referencia, no por valor al momento de crear el listener.
        var dialogRef: AlertDialog? = null
        for ((categoryRes, options) in EXTRACT_SCHEMA_CATALOG.groupBy { it.categoryRes }) {
            list.addView(TextView(ctx).apply {
                text = getString(categoryRes).uppercase()
                textSize = 10f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(dp(20), dp(14), dp(20), dp(6))
            })
            for (option in options) {
                list.addView(TextView(ctx).apply {
                    text = "${getString(option.labelRes)} (${option.id})"
                    textSize = 14f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true
                    isFocusable = true
                    val outValue = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        mExtractSchema = option.id
                        schemaLabel.text = getString(R.string.cactus_extract_schema_label, getString(option.labelRes), option.id)
                        dialogRef?.dismiss()
                    }
                })
            }
        }
        dialogRef = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.cactus_dialog_choose_schema_title))
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .create()
        dialogRef?.show()
    }

    private fun runExtract(schema: String, text: String) {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.cactus_progress_extract_title), getString(R.string.cactus_progress_extrayendo))
        Thread {
            val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(
                listOf(TERMUX_CACTUS_PATH, "extract", schema, text, "--json-only"), CACTUS_RUN_TIMEOUT_SECONDS
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                reportExtractResult(progress, exitCode, stdout, stderr)
            }
        }.start()
    }

    private fun reportExtractResult(progress: ProgressDialogController, exitCode: Int, stdout: String, stderr: String) {
        if (stdout.isBlank()) {
            progress.failure(getString(R.string.cactus_sin_salida), stderr.ifBlank { getString(R.string.cactus_exit_code, exitCode) })
            return
        }
        try {
            progress.success(getString(R.string.cactus_listo), formatExtractResult(JSONObject(stdout)))
        } catch (e: Exception) {
            val extra = if (stderr.isNotBlank()) "\n\n[stderr]\n$stderr" else ""
            progress.failure(getString(R.string.cactus_parse_error), "$stdout$extra")
        }
    }

    /** Mismo shape que arma run_extract() en cactus_engine.py: {type, schema, confidence,
     *  fields, reasoning}. */
    private fun formatExtractResult(json: JSONObject): String {
        if (json.optString("type") == "error") return json.optString("error", getString(R.string.cactus_extract_error_default))
        val sb = StringBuilder()
        sb.append(getString(R.string.cactus_extract_schema_result, json.optString("schema", "?")))
        if (json.has("confidence") && !json.isNull("confidence")) {
            sb.append(getString(R.string.cactus_extract_confianza, json.optDouble("confidence").toString()))
        }
        val fields = json.optJSONObject("fields")
        if (fields == null || fields.length() == 0) {
            sb.append(getString(R.string.cactus_extract_sin_campos))
        } else {
            sb.append("\n")
            for (key in fields.keys()) sb.append("$key: ${fields.get(key)}\n")
        }
        val reasoning = json.optString("reasoning", "")
        if (reasoning.isNotBlank()) sb.append(getString(R.string.cactus_extract_razonamiento, reasoning))
        return sb.toString().trim()
    }

    // ────────────────────────────────────────────────────────────
    // Tareas — MVP: persistidas en ~/.cactus_tasks.json, ejecución manual bajo demanda
    // (sin scheduler real, pedido explícito de mantenerlo simple).
    // ────────────────────────────────────────────────────────────

    private data class CactusTask(val id: String, val title: String, val prompt: String, val useAi: Boolean)

    private fun buildTareasCard() {
        addCard(getString(R.string.cactus_card_tareas)) {
            mTasksListContainer = this
            renderTasksList()
        }
    }

    private fun renderTasksList() {
        mTasksListContainer.removeAllViews()
        val tasks = loadTasks()
        if (tasks.isEmpty()) {
            mTasksListContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_sin_tareas)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
        } else {
            tasks.forEach { task -> mTasksListContainer.addView(taskRow(task)) }
        }
        val buttonsRow = LinearLayout(requireContext()).apply { orientation = HORIZONTAL }
        buttonsRow.addView(createActionButton(getString(R.string.cactus_btn_plantillas), GHOST) { showTaskTemplatesDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        buttonsRow.addView(createActionButton(getString(R.string.cactus_btn_nueva_tarea), GHOST) { showAddTaskDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        mTasksListContainer.addView(buttonsRow)
    }

    /** Plantillas de tareas comunes atadas al catálogo real de tools de cactus_engine.py
     *  (TOOLS = run_bash, run_python, read_file, write_file, list_dir, system_info,
     *  engram_remember, engram_recall) — para que el usuario no tenga que escribir el pedido
     *  desde cero cada vez. Al elegir una se abre "Nueva tarea" pre-cargada, editable antes de
     *  guardar. Ronda 2026-08-27 (pedido explícito del usuario: "en cactus toca poner mas
     *  comandos en plantillas") — agregadas 5 plantillas nuevas para las 3 tools del catálogo
     *  que todavía no tenían ninguna plantilla (run_bash, run_python, write_file) + 2 usando
     *  modo `ai` (razonador local vía Ollama/llama-server antes de que needle traduzca a tool
     *  call — ver cactus.sh sección "CLI RESULTANTE", comando `cactus ai`), confirmadas contra
     *  el catálogo TOOLS real de modulos/cactus.sh (cactus_engine.py líneas ~393-483) — no son
     *  sintaxis inventada, cada prompt describe en lenguaje natural exactamente lo que esa tool
     *  ya sabe ejecutar (needle mapea el pedido a la tool call correspondiente, no hay flags de
     *  CLI que el usuario deba escribir a mano). */
    private data class TaskTemplate(val titleRes: Int, val promptRes: Int?, val useAi: Boolean)

    private val taskTemplates by lazy {
        listOf(
            TaskTemplate(R.string.cactus_template_info_sistema_title, R.string.cactus_template_info_sistema_prompt, false),
            TaskTemplate(R.string.cactus_template_listar_download_title, R.string.cactus_template_listar_download_prompt, false),
            TaskTemplate(R.string.cactus_template_buscar_memoria_title, R.string.cactus_template_buscar_memoria_prompt, false),
            TaskTemplate(R.string.cactus_template_guardar_nota_title, R.string.cactus_template_guardar_nota_prompt, false),
            TaskTemplate(R.string.cactus_template_leer_archivo_title, R.string.cactus_template_leer_archivo_prompt, false),
            // Nuevas 2026-08-27 — cubren run_bash, run_python, write_file (tools del catálogo sin
            // plantilla hasta ahora) + 2 ejemplos en modo `ai` (razonador interpreta el pedido
            // antes de que needle ejecute la tool call).
            TaskTemplate(R.string.cactus_template_bash_title, R.string.cactus_template_bash_prompt, false),
            TaskTemplate(R.string.cactus_template_python_title, R.string.cactus_template_python_prompt, false),
            TaskTemplate(R.string.cactus_template_write_file_title, R.string.cactus_template_write_file_prompt, false),
            TaskTemplate(R.string.cactus_template_diagnostico_ia_title, R.string.cactus_template_diagnostico_ia_prompt, true),
            TaskTemplate(R.string.cactus_template_lenguaje_natural_title, null, true),
        )
    }

    private fun showTaskTemplatesDialog() {
        val titles = taskTemplates.map { getString(it.titleRes) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cactus_dialog_plantillas_title))
            .setItems(titles) { _, which ->
                val template = taskTemplates[which]
                showAddTaskDialog(getString(template.titleRes), template.promptRes?.let { getString(it) } ?: "", template.useAi)
            }
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .show()
    }

    private fun taskRow(task: CactusTask): android.view.View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = task.title
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = if (task.useAi) getString(R.string.cactus_task_row_con_ia, task.prompt) else getString(R.string.cactus_task_row_sin_ia, task.prompt)
                    textSize = 11f
                    maxLines = 2
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                })
            })
            addView(TextView(ctx).apply {
                text = "▶"
                textSize = 16f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { runTask(task) }
            })
            addView(TextView(ctx).apply {
                text = "🗑"
                textSize = 16f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { confirmDeleteTask(task) }
            })
        }
    }

    private fun showAddTaskDialog(
        prefillTitle: String = "",
        prefillPrompt: String = "",
        prefillUseAi: Boolean = false
    ) {
        val ctx = requireContext()
        val titleInput = EditText(ctx).apply { hint = getString(R.string.cactus_hint_titulo_tarea); setText(prefillTitle) }
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.cactus_hint_pedido_needle)
            isSingleLine = false
            setText(prefillPrompt)
            if (prefillPrompt.isNotEmpty()) setSelection(prefillPrompt.length)
        }
        val aiSwitch = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            text = getString(R.string.cactus_switch_con_ia)
            isChecked = prefillUseAi
        }
        val layout = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(titleInput)
            addView(promptInput)
            addView(aiSwitch)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.cactus_dialog_nueva_tarea_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.cactus_btn_guardar)) { _, _ ->
                val title = titleInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (title.isEmpty() || prompt.isEmpty()) { toast(getString(R.string.cactus_toast_faltan_datos)); return@setPositiveButton }
                val task = CactusTask(
                    id = "${System.currentTimeMillis()}",
                    title = title,
                    prompt = prompt,
                    useAi = aiSwitch.isChecked
                )
                val tasks = loadTasks().toMutableList()
                tasks.add(task)
                saveTasks(tasks)
                renderTasksList()
            }
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .show()
    }

    private fun confirmDeleteTask(task: CactusTask) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cactus_dialog_eliminar_tarea_title))
            .setMessage(getString(R.string.cactus_dialog_eliminar_tarea_msg, task.title))
            .setPositiveButton(getString(R.string.cactus_btn_eliminar)) { _, _ ->
                val tasks = loadTasks().filterNot { it.id == task.id }
                saveTasks(tasks)
                renderTasksList()
            }
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .show()
    }

    private fun runTask(task: CactusTask) {
        if (task.useAi && !mAiBackendAvailable) {
            toast(getString(R.string.cactus_toast_task_needs_ia))
            return
        }
        val progress = ProgressDialogController(requireContext())
        progress.show(task.title, getString(R.string.cactus_progress_ejecutando))
        Thread {
            val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(
                cactusArgs(task.prompt, task.useAi), CACTUS_RUN_TIMEOUT_SECONDS
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                reportCactusResult(progress, exitCode, stdout, stderr)
            }
        }.start()
    }

    private fun loadTasks(): List<CactusTask> {
        if (!tasksFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(tasksFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CactusTask(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    prompt = o.optString("prompt"),
                    useAi = o.optBoolean("use_ai", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveTasks(tasks: List<CactusTask>) {
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("prompt", task.prompt)
                put("use_ai", task.useAi)
            })
        }
        tasksFile.writeText(arr.toString(2))
    }

    // ────────────────────────────────────────────────────────────
    // Catálogo de tools — `cactus tools` (auditoría de cobertura 2026-08-19, ver
    // docs/viejo/AUDITORIA_COBERTURA_57_MODULOS_2026-08-19.md): comando de solo
    // lectura, una sola pasada, imprime el JSON de TOOLS (cactus.sh) — mismo catálogo que
    // needle usa para decidir qué tool-call ejecutar. Antes solo accesible abriendo terminal
    // manual (no había ni siquiera un botón de terminal dedicado para esto).
    // ────────────────────────────────────────────────────────────

    private lateinit var mToolsListContainer: LinearLayout

    private fun buildToolsCard() {
        addCard(getString(R.string.cactus_card_tools)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_tools_desc)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
            mToolsListContainer = LinearLayout(requireContext()).apply { orientation = VERTICAL }
            addView(mToolsListContainer)
            addView(createActionButton(getString(R.string.cactus_btn_ver_catalogo), GHOST) { loadToolsCatalog() })
        }
    }

    private fun loadToolsCatalog() {
        Thread {
            val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(listOf(TERMUX_CACTUS_PATH, "tools"), CACTUS_RUN_TIMEOUT_SECONDS)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                renderToolsCatalog(exitCode, stdout, stderr)
            }
        }.start()
    }

    private fun renderToolsCatalog(exitCode: Int, stdout: String, stderr: String) {
        mToolsListContainer.removeAllViews()
        if (stdout.isBlank()) {
            mToolsListContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_tools_error, stderr.ifBlank { getString(R.string.cactus_exit_code, exitCode) })
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(4), dp(14), dp(4))
            })
            return
        }
        try {
            val tools = JSONArray(stdout)
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                mToolsListContainer.addView(TextView(requireContext()).apply {
                    val name = tool.optString("name", "?")
                    val description = tool.optString("description", "")
                    text = getString(R.string.cactus_tools_item, name, description)
                    textSize = 12f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                })
            }
        } catch (e: Exception) {
            mToolsListContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_tools_unexpected_format, e.message ?: "")
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(4), dp(14), dp(4))
            })
        }
    }

    // ────────────────────────────────────────────────────────────
    // Servidor HTTP (opt-in) — `cactus serve` (ver modulos/cactus.sh PASO 5), para que
    // n8n u otro cliente HTTP dispare tareas de Cactus vía POST /run sin pasar por el
    // CLI (docs/arquitectura/PROPUESTA_ORQUESTACION_CRUZADA_2026-08-25.md sección 2,
    // opción "a"). Apagado por defecto — mismo mecanismo real (startModuleService/
    // stopModuleService/isModuleRunning de BaseModuleFragment, tmux + start.sh/stop.sh)
    // que ya usa LlamaServerFragment, no uno nuevo. Solo escucha en 127.0.0.1 y exige el
    // token persistido en ~/.cactus_http_token (header X-Cactus-Token).
    // ────────────────────────────────────────────────────────────

    private lateinit var mServerSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var mServerStatusText: TextView

    private fun buildServerCard() {
        addCard(getString(R.string.cactus_card_server)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_server_desc, CACTUS_SERVE_PORT)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(4))
            })
            val row = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            mServerStatusText = TextView(requireContext()).apply {
                text = getString(R.string.cactus_server_verificando)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            row.addView(mServerStatusText)
            mServerSwitch = androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                thumbTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_thumb_color)
                trackTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_track_color)
                setOnCheckedChangeListener { _, checked -> onServerToggle(checked) }
            }
            row.addView(mServerSwitch)
            addView(row)
            addView(createActionButton(getString(R.string.cactus_btn_ver_token), GHOST) { showServerTokenDialog() })
            refreshServerStatus()
        }
    }

    private fun onServerToggle(on: Boolean) {
        if (on) {
            toast(getString(R.string.cactus_toast_iniciando_server))
            startModuleService { ok, _ ->
                toast(if (ok) getString(R.string.cactus_toast_server_iniciado, CACTUS_SERVE_PORT) else getString(R.string.cactus_toast_server_no_inicio))
                refreshServerStatus()
            }
        } else {
            stopModuleService { ok ->
                toast(if (ok) getString(R.string.cactus_toast_server_detenido) else getString(R.string.cactus_toast_server_no_detuvo))
                refreshServerStatus()
            }
        }
    }

    private fun refreshServerStatus() {
        Thread {
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                mServerSwitch.setOnCheckedChangeListener(null)
                mServerSwitch.isChecked = running
                mServerSwitch.setOnCheckedChangeListener { _, checked -> onServerToggle(checked) }
                mServerStatusText.text = if (running) getString(R.string.cactus_server_status_active, CACTUS_SERVE_PORT) else getString(R.string.cactus_server_status_stopped)
                mServerStatusText.setTextColor(
                    requireContext().kairosThemeColor(if (running) R.attr.kairosGreen else R.attr.kairosText3)
                )
            }
        }.start()
    }

    /** Token real vive en ~/.cactus_http_token (generado por cactus_engine.py, `_serve_token()`,
     *  mismo patrón que NubeServer.kt/.nube_token) — se lee tal cual, no se genera acá. */
    private fun showServerTokenDialog() {
        Thread {
            val tokenFile = File(home, ".cactus_http_token")
            val token = if (tokenFile.exists()) tokenFile.readText().trim() else ""
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val message = if (token.isBlank()) {
                    getString(R.string.cactus_token_not_generated)
                } else {
                    getString(R.string.cactus_token_header_required, token)
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.cactus_dialog_token_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.cactus_btn_cerrar), null)
                    .show()
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Scripts — exportar/importar los archivos que cactus usa: el motor
    // ~/scripts/cactus/cactus_engine.py (instalado por cactus.sh) y las tareas guardadas
    // ~/.cactus_tasks.json. Mismo patrón que ConfigExportManager/DiagnosticExportManager:
    // export directo a Download/ (sin SAF, la app ya escribe ahí), import vía selector SAF
    // (ActivityResultContracts.GetContent(), mismo patrón que ConfigFragment.mPickConfigFileLauncher).
    // ────────────────────────────────────────────────────────────

    private fun buildScriptsCard() {
        addCard(getString(R.string.cactus_card_scripts)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.cactus_scripts_desc)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            addView(createActionButton(getString(R.string.cactus_btn_export_scripts), GHOST) { confirmExportScripts() })
            addView(createActionButton(getString(R.string.cactus_btn_import_scripts), GHOST) {
                mPickImportFileLauncher.launch("application/json")
            })
        }
    }

    private fun confirmExportScripts() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cactus_dialog_export_title))
            .setMessage(getString(R.string.cactus_dialog_export_msg))
            .setPositiveButton(getString(R.string.cactus_btn_exportar)) { _, _ -> runExportScripts() }
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .show()
    }

    private fun runExportScripts() {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.cactus_progress_export_title), getString(R.string.cactus_progress_generando))
        Thread {
            val result = exportScripts()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.optBoolean("ok", false)) {
                    progress.success(getString(R.string.cactus_export_success, result.optString("path", "")))
                } else {
                    progress.failure(getString(R.string.cactus_export_error_title), result.optString("error", getString(R.string.cactus_error_desconocido)))
                }
            }
        }.start()
    }

    private fun exportScripts(): JSONObject {
        return try {
            val files = JSONObject()
            if (engineFile.exists()) {
                files.put(ENGINE_REL_PATH, JSONObject().apply {
                    put("type", "text")
                    put("content", engineFile.readText())
                })
            }
            if (tasksFile.exists()) {
                files.put(TASKS_FILE_NAME, JSONObject().apply {
                    put("type", "text")
                    put("content", tasksFile.readText())
                })
            }
            val export = JSONObject().apply {
                put(SCHEMA_MARKER, true)
                put("version", 1)
                put("created_at", System.currentTimeMillis())
                put("files", files)
            }
            val dir = File("/storage/emulated/0/Download/KairosCactus")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "cactus-export-${System.currentTimeMillis()}.json")
            dest.writeText(export.toString(2))
            JSONObject().put("ok", true).put("path", dest.absolutePath)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.cactus_export_error_title))
        }
    }

    private fun confirmImportScripts(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cactus_dialog_import_title))
            .setMessage(getString(R.string.cactus_dialog_import_msg))
            .setPositiveButton(getString(R.string.cactus_btn_importar)) { _, _ -> runImportScripts(uri) }
            .setNegativeButton(getString(R.string.cactus_cancelar), null)
            .show()
    }

    private fun runImportScripts(uri: Uri) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show(getString(R.string.cactus_progress_import_title), getString(R.string.cactus_progress_leyendo))
        Thread {
            val result = try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: throw java.io.IOException(getString(R.string.cactus_error_no_open_file))
                importScripts(text)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: getString(R.string.cactus_error_desconocido))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.optBoolean("ok", false)) {
                    progress.success(getString(R.string.cactus_import_success, result.optJSONArray("applied")?.length() ?: 0))
                    renderTasksList()
                } else {
                    progress.failure(getString(R.string.cactus_import_error_title), result.optString("error", getString(R.string.cactus_error_desconocido)))
                }
            }
        }.start()
    }

    private fun importScripts(rawText: String): JSONObject {
        val root = try {
            JSONObject(rawText)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", getString(R.string.cactus_import_invalid_file, e.message ?: ""))
        }
        if (!root.optBoolean(SCHEMA_MARKER, false)) {
            return JSONObject().put("ok", false).put("error", getString(R.string.cactus_import_invalid_export))
        }
        val applied = JSONArray()
        val files = root.optJSONObject("files") ?: JSONObject()
        for (rel in files.keys()) {
            try {
                val entry = files.getJSONObject(rel)
                val content = entry.optString("content", "")
                val dest = File(home, rel)
                dest.parentFile?.mkdirs()
                dest.writeText(content)
                applied.put(rel)
            } catch (_: Exception) {
                // se saltea ese archivo puntual, no aborta el resto (mismo criterio que ConfigExportManager)
            }
        }
        return JSONObject().put("ok", true).put("applied", applied)
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    private fun styledEditText(hintText: String): EditText {
        val ctx = requireContext()
        return EditText(ctx).apply {
            hint = hintText
            isSingleLine = false
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setHintTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    companion object {
        private const val CACTUS_RUN_TIMEOUT_SECONDS = 60L
        private const val TASKS_FILE_NAME = ".cactus_tasks.json"
        private const val ENGINE_REL_PATH = "scripts/cactus/cactus_engine.py"
        private const val SCHEMA_MARKER = "kairos_cactus_export"
        // Debe coincidir con el puerto default de `cactus serve` (modulos/cactus.sh,
        // cmd_serve()/main()) y con ModuleController.getModulePort("cactus").
        private const val CACTUS_SERVE_PORT = 8977
    }
}
