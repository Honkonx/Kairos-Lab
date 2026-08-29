package com.termux.app.ui

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.shared.termux.TermuxConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.termux.app.util.kairosThemeColor

class EngramFragment : BaseModuleFragment() {
    override fun getModuleId() = "engram"
    override fun getModuleName() = getString(R.string.engram_module_name)

    private lateinit var estadoPillSlot: LinearLayout

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.engram_card_estado)) {
            addView(infoRow(getString(R.string.engram_row_motor_label), getString(R.string.engram_row_motor_value)))
            addView(infoRow(getString(R.string.engram_row_storage_label), getString(R.string.engram_row_storage_value)))
            estadoPillSlot = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.engram_label_estado)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(getString(R.string.engram_pill_dash), false).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
            addView(estadoPillSlot)
            // Falta desde siempre: Engram es un módulo CLI igual que Claude/Codex/OpenCode
            // (proceso que puede quedar corriendo en una sesión de terminal minimizada), pero
            // era el único de ese grupo sin terminalStatusPill() — el usuario no tenía forma
            // de saber si el TUI seguía vivo en segundo plano sin reabrir la terminal.
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.engram_label_terminal)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(terminalStatusPill().also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            })
        }
        addCard(getString(R.string.engram_card_que_es)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.engram_desc_que_es)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
        }
        // "engram" a secas (sin subcomando) NO abre el TUI — solo imprime el texto de ayuda
        // y sale con código 1 (confirmado leyendo cmd/engram/main.go del repo real:
        // "if len(os.Args) < 2 { printUsage(); exit(1) }"). El botón original ("Abrir en
        // terminal" → "engram") nunca mostraba el panel interactivo real, que es
        // "engram tui".
        refreshEstadoPill()
        actionButton(getString(R.string.engram_btn_abrir_tui), GHOST) {
            launchTerminalCommand("engram tui")
        }
        // Subcomandos confirmados en el README real de Gentleman-Programming/engram (no
        // inventados) — search/stats/doctor/export/delete son no-interactivos, así que se
        // corren directo con ProcessBuilder y se muestra el resultado en un diálogo, sin
        // pasar por la terminal (mismo criterio que "Query SQL" en SqliteFragment).
        // "engram save <title> <msg>" — confirmado en el README real (repo
        // Gentleman-Programming/engram, sección "Guardar un aprendizaje manualmente") y en
        // las dos copias de referencia idénticas (referencia/termux/termux-oracle-main y
        // referencia/ciberseguridad/i-Haklab-master). No estaba expuesto: hasta ahora la
        // memoria solo se llenaba automáticamente vía los agentes de IA (Claude Code,
        // OpenCode, etc.) integrados con "engram setup" — no había forma de anotar algo a
        // mano desde la app sin abrir la terminal.
        actionButton(getString(R.string.engram_btn_guardar_manual), GHOST) {
            promptAndSave()
        }
        actionButton(getString(R.string.engram_btn_buscar), GHOST) {
            promptAndSearch()
        }
        // "engram context [project]" — confirmado en el README real ("Displays recent
        // session context"). Complementa a "Buscar en memoria" (que requiere saber qué
        // buscar) con una vista rápida de lo último guardado, sin tener que adivinar un
        // término de búsqueda.
        actionButton(getString(R.string.engram_btn_contexto_reciente), GHOST) {
            runEngramCommand("context")
        }
        actionButton(getString(R.string.engram_btn_stats), GHOST) {
            runEngramCommand("stats")
        }
        // "engram projects list" — confirmado en el README real. La memoria de Engram está
        // organizada por proyecto (mismo concepto que usa "Eliminar memoria de un
        // proyecto" más abajo), pero no había forma de ver qué proyectos existen sin
        // adivinar el nombre exacto antes de este botón.
        actionButton(getString(R.string.engram_btn_listar_proyectos), GHOST) {
            runEngramCommand("projects", "list")
        }
        actionButton(getString(R.string.engram_btn_doctor), GHOST) {
            runEngramCommand("doctor")
        }
        // "engram setup [agent]" pregunta interactivamente qué agente configurar cuando no
        // se le pasa argumento — se abre en terminal en vez de adivinar el nombre exacto del
        // agente que espera el flag (no documentado con valores cerrados en el README).
        actionButton(getString(R.string.engram_btn_configurar_agentes), GHOST) {
            launchTerminalCommand("engram setup")
        }
        actionButton(getString(R.string.engram_btn_exportar), GHOST) {
            exportMemory()
        }
        // "engram import <file>" — confirmado real en el README oficial (Gentleman-Programming/
        // engram, tabla de referencia CLI), auditoría 2026-08-25: complemento directo de
        // "Exportar" que faltaba — sin esto, un export era de solo ida, sin forma de restaurar
        // el backup desde la app (había que abrir terminal a mano y adivinar el comando).
        actionButton(getString(R.string.engram_btn_importar), GHOST) {
            promptImportMemory()
        }
        actionButton(getString(R.string.engram_btn_eliminar_proyecto), DANGER) {
            promptDeleteProject()
        }
        actionButton(getString(R.string.engram_btn_reinstalar), GHOST) {
            toast(getString(R.string.engram_toast_reinstalando))
            reinstallModuleService { ok ->
                toast(if (ok) getString(R.string.engram_toast_actualizado) else getString(R.string.engram_toast_fallo_reinstalar))
            }
        }
    }

    private fun refreshEstadoPill() {
        Thread {
            val available = isTermuxBinaryAvailable("engram")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                estadoPillSlot.removeViewAt(estadoPillSlot.childCount - 1)
                estadoPillSlot.addView(pill(if (available) getString(R.string.engram_pill_listo) else getString(R.string.engram_pill_no_responde), available).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
        }.start()
    }

    // Diálogo con 2 campos (título + contenido), igual que el ejemplo del README real:
    // `engram save "Corrección de Bug en Auth" "Se cambió el middleware..."`. Sin --type ni
    // --project expuestos a propósito — son opcionales en el README y agregarlos acá
    // implicaría inventar una lista cerrada de valores válidos para --type que el README no
    // documenta con detalle (mismo criterio que "Configurar integración con agentes", que
    // por la misma razón se abre en terminal en vez de adivinar valores).
    private fun promptAndSave() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val titleEdit = EditText(ctx).apply { hint = getString(R.string.engram_hint_titulo) }
        val messageEdit = EditText(ctx).apply {
            hint = getString(R.string.engram_hint_contenido)
            minLines = 3
        }
        layout.addView(titleEdit)
        layout.addView(messageEdit)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.engram_dialog_guardar_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.engram_btn_guardar)) { _, _ ->
                val title = titleEdit.text.toString().trim()
                val message = messageEdit.text.toString().trim()
                if (title.isEmpty() || message.isEmpty()) {
                    toast(getString(R.string.engram_toast_falta_titulo_contenido))
                    return@setPositiveButton
                }
                runEngramCommand("save", title, message) { output ->
                    toast(if (output.contains("error", ignoreCase = true)) output.take(120) else getString(R.string.engram_toast_memoria_guardada))
                }
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    private fun promptAndSearch() {
        val ctx = requireContext()
        val edit = EditText(ctx).apply { hint = getString(R.string.engram_hint_buscar) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.engram_dialog_buscar_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.engram_btn_buscar_dialog)) { _, _ ->
                val query = edit.text.toString().trim()
                if (query.isNotEmpty()) runEngramCommand("search", query)
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    private fun exportMemory() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "engram_export_$timestamp.json"
        val exportPath = "${TermuxConstants.TERMUX_HOME_DIR_PATH}/$fileName"
        toast(getString(R.string.engram_toast_exportando))
        runEngramCommand("export", exportPath) { output ->
            val exported = java.io.File(exportPath).exists()
            toast(if (exported) getString(R.string.engram_toast_exportado, fileName) else getString(R.string.engram_toast_fallo_exportar, output.take(120)))
        }
    }

    // Lista los .json de $HOME (mismo lugar donde exportMemory() ya deja los backups,
    // "engram_export_*.json") — evita que el usuario tenga que escribir la ruta completa a
    // mano para algo que la propia app ya generó antes.
    private fun promptImportMemory() {
        val home = java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val exports = home.listFiles { f -> f.isFile && f.name.startsWith("engram_export_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (exports.isEmpty()) {
            toast(getString(R.string.engram_toast_no_backups))
            promptImportPath(null)
            return
        }
        val names = exports.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.engram_dialog_importar_title))
            .setItems(names) { _, which -> confirmImport(exports[which].absolutePath) }
            .setNeutralButton(getString(R.string.engram_btn_ruta_manual)) { _, _ -> promptImportPath(null) }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    private fun promptImportPath(prefill: String?) {
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.engram_hint_ruta)
            prefill?.let { setText(it) }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.engram_dialog_ruta_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.engram_btn_continuar)) { _, _ ->
                val path = edit.text.toString().trim()
                if (path.isNotEmpty()) confirmImport(path)
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    private fun confirmImport(path: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.engram_dialog_confirmar_import_title, path))
            .setMessage(getString(R.string.engram_dialog_confirmar_import_msg))
            .setPositiveButton(getString(R.string.engram_btn_importar_dialog)) { _, _ ->
                toast(getString(R.string.engram_toast_importando))
                runEngramCommand("import", path) { output ->
                    toast(output.take(120))
                }
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    // "engram delete project <name>" hace soft-delete por defecto (se puede recuperar) —
    // no se ofrece acá el flag --hard (borrado permanente) para no habilitar un botón de
    // "eliminar sin vuelta atrás" desde un simple diálogo de texto.
    private fun promptDeleteProject() {
        val ctx = requireContext()
        val edit = EditText(ctx).apply { hint = getString(R.string.engram_hint_nombre_proyecto) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.engram_dialog_eliminar_proyecto_title))
            .setMessage(getString(R.string.engram_dialog_eliminar_proyecto_msg))
            .setView(edit)
            .setPositiveButton(getString(R.string.engram_btn_continuar)) { _, _ ->
                val project = edit.text.toString().trim()
                if (project.isEmpty()) return@setPositiveButton
                confirmDeleteProject(project)
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteProject(project: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.engram_dialog_confirmar_eliminar_title, project))
            .setMessage(getString(R.string.engram_dialog_confirmar_eliminar_msg))
            .setPositiveButton(getString(R.string.engram_btn_eliminar_dialog)) { _, _ ->
                runEngramCommand("delete", "project", project) { output ->
                    toast(output.take(120))
                }
            }
            .setNegativeButton(getString(R.string.engram_btn_cancelar), null)
            .show()
    }

    /**
     * Corre `engram <args>` directo (sin terminal) — mismo patrón de PATH que usaba el
     * antiguo runManagerAction() (ver com.termux.app.util.applyTermuxEnv, obligatorio: el proceso
     * Java de la app no hereda el PATH de Termux). Si `onDone` es null, muestra la salida
     * cruda en un diálogo; si no, deja que el caller decida qué hacer con el resultado
     * (ej. exportMemory()/confirmDeleteProject() solo necesitan un toast corto).
     */
    private fun runEngramCommand(vararg args: String, onDone: ((String) -> Unit)? = null) {
        Thread {
            val output = try {
                // Ruta absoluta — mismo riesgo de "Cannot run program" ya confirmado esta
                // sesión (ver docs/humano/humano63.md), y Engram ya venía reportado sin
                // instalar/con bugs de git clone en rondas anteriores.
                val pb = ProcessBuilder(listOf("$TERMUX_PREFIX_PATH/bin/engram") + args)
                pb.redirectErrorStream(true)
                pb.applyTermuxEnv()
                val process = pb.start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                text.ifBlank { getString(R.string.engram_sin_salida) }
            } catch (e: Exception) {
                friendlyProcessErrorMessage(e, "Engram")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (onDone != null) onDone(output) else showOutputDialog("engram ${args.joinToString(" ")}", output)
            }
        }.start()
    }

    private fun showOutputDialog(title: String, output: String) {
        val ctx = requireContext()
        val textView = TextView(ctx).apply {
            text = output
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(textView) })
            .setPositiveButton(getString(R.string.engram_dialog_cerrar), null)
            .show()
    }
}
