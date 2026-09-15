package com.termux.app.ui

import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.util.EntornoNative
import java.io.File

/**
 * Pestaña "Sistema" de EntornoFragment (Mini PC) — X11 embebido a nivel de infraestructura
 * (GPU/audio), distinto del X11 "de uso" de EntornoX11Tab; también el editor de scripts de
 * arranque generados por entorno.sh. Extraído de EntornoFragment.kt en el refactor de
 * separación por pestaña (reorganización pura, ver git history de EntornoFragment.kt para el
 * detalle histórico completo de cada decisión).
 */
internal class EntornoSistemaTab(private val fragment: EntornoFragment) {

    /** Pestaña "Sistema" — mapeo 1:1 con las acciones que antes vivían bajo el sectionLabel "AUDIO + GPU". */
    fun render(parent: LinearLayout) {
        val ctx = fragment.requireContext()
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_audio_gpu))
        tileGrid(ctx, parent, listOf(
            TileAction(fragment.getString(R.string.entorno_tile_pulseaudio_toggle), R.drawable.ic_audio, running = { fragment.pulseRunning }) { fragment.runEntornoAction("pulse-toggle") },
            TileAction(fragment.getString(R.string.entorno_tile_diagnostico_gpu), R.drawable.ic_gpu) { showGpuDiagnostic() },
            TileAction(fragment.getString(R.string.entorno_tile_benchmark_gpu), R.drawable.ic_gpu) { showGpuBenchmark() },
            TileAction(fragment.getString(R.string.entorno_tile_configurar_metodo_gpu), R.drawable.ic_settings) { promptGpuMethod() },
            TileAction(fragment.getString(R.string.entorno_tile_recursos_sesion), R.drawable.ic_gpu) { showSessionResources() },
            TileAction(fragment.getString(R.string.entorno_tile_editar_script), R.drawable.ic_terminal) { promptEditScript() }
        ))
    }

    private fun showGpuDiagnostic() {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_diagnosticando_gpu))
        Thread {
            val json = EntornoNative.gpuDiagnostic()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    Snackbar.make(fragment.requireView(), fragment.getString(R.string.entorno_error_no_pudo_diagnosticar_gpu), Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // Tarea 1 (2026-09-15): "Método activo" mostraba directo lo PEDIDO
                // (entorno.gpu_method), nunca lo que terminó cargado de verdad — ver
                // classifyActiveGpuBackend()/gpu_mismatch en EntornoNative.gpuDiagnostic().
                val configuredMethod = json.optString("gpu_method")
                val activeBackend = json.optString("gpu_method_active", configuredMethod)
                var message = fragment.getString(
                    R.string.entorno_gpu_diagnostico_mensaje,
                    json.optString("gpu_type"),
                    configuredMethod,
                    json.optString("renderer"),
                    json.optString("vulkan_device"),
                    json.optString("drivers_installed"),
                    activeBackend
                )
                if (json.optBoolean("gpu_mismatch", false)) {
                    message += fragment.getString(
                        R.string.entorno_gpu_diagnostico_advertencia_fallback,
                        configuredMethod, activeBackend
                    )
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_diagnostico_gpu_titulo))
                    .setMessage(message)
                    .setPositiveButton(fragment.getString(R.string.entorno_cerrar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Benchmark GPU (glxgears) — "Diagnóstico GPU" dice QUÉ driver está activo pero nunca un
     * número comparable de rendimiento real; este botón corre EntornoNative.gpuBenchmark()
     * (glxgears, ya viene con mesa-utils que setGpuMethod() instala para todos los métodos) y
     * muestra el FPS resultante.
     */
    private fun showGpuBenchmark() {
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_benchmark_gpu))
        Thread {
            val json = EntornoNative.gpuBenchmark()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    val detail = json.optString("output", "").takeLast(200)
                    val base = json.optString("error", fragment.getString(R.string.entorno_error_no_pudo_benchmark_gpu))
                    val msg = if (detail.isNotBlank()) "$base — $detail" else base
                    Snackbar.make(fragment.requireView(), msg, Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_benchmark_gpu_titulo))
                    .setMessage(fragment.getString(R.string.entorno_gpu_benchmark_mensaje, json.optString("fps")))
                    .setPositiveButton(fragment.getString(R.string.entorno_cerrar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Memoria (RSS) de la sesión de escritorio activa — Kairos solo mostraba recursos
     * agregados del sistema completo (MonitorFragment), nunca cuánto consume la sesión Mini PC
     * en sí.
     */
    private fun showSessionResources() {
        Thread {
            val json = EntornoNative.sessionResourceUsage()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    Snackbar.make(
                        fragment.requireView(),
                        json.optString("error", fragment.getString(R.string.entorno_error_no_pudo_leer_recursos)),
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_recursos_sesion_titulo))
                    .setMessage(
                        fragment.getString(
                            R.string.entorno_recursos_sesion_mensaje,
                            json.optString("process"),
                            json.optInt("pid"),
                            json.optString("rss_mb")
                        )
                    )
                    .setPositiveButton(fragment.getString(R.string.entorno_cerrar), null)
                    .show()
            }
        }.start()
    }

    private fun promptGpuMethod() {
        Thread {
            val json = EntornoNative.gpuMethodOptions()
            if (!fragment.isAdded) return@Thread
            fragment.requireActivity().runOnUiThread {
                val labels = json.optJSONArray("labels")
                val values = json.optJSONArray("values")
                if (labels == null || values == null) {
                    fragment.toastMsg(fragment.getString(R.string.entorno_error_no_pudo_leer_gpu))
                    return@runOnUiThread
                }
                val labelArr = Array(labels.length()) { labels.optString(it) }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.entorno_dialog_metodo_gpu_titulo, json.optString("gpu_type")))
                    .setItems(labelArr) { _, which -> fragment.runEntornoAction("gpu-method", values.optString(which)) }
                    .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Editor de scripts de arranque embebido en la app (auditoría 2026-08-31 de
     * referencia/contenedores/trierarch-main — DrawerScriptEditor.kt de ese proyecto permite
     * editar in-app el script de arranque de cada DE/distro sin salir de la app). Solo
     * lectura/escritura de texto plano sobre los .sh de $HOME/scripts/entorno/ — no
     * reimplementa ni toca entorno.sh (script protegido): esto solo edita los ARCHIVOS ya
     * generados por ese script, nunca su lógica de generación.
     */
    private data class EditableScript(val fileName: String, val label: String)

    // Función, no `val` de evaluación inmediata — getString() no puede correr antes de que el
    // Fragment esté adjunto a un Context.
    private fun editableEntornoScripts() = listOf(
        EditableScript("gui_start.sh", fragment.getString(R.string.entorno_script_gui_start)),
        EditableScript("gui_stop.sh", fragment.getString(R.string.entorno_script_gui_stop)),
        EditableScript("distro_setup_gui.sh", fragment.getString(R.string.entorno_script_distro_setup_gui))
    )

    private fun entornoScriptsDir(): File =
        File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/entorno")

    private fun promptEditScript() {
        val scripts = editableEntornoScripts()
        val labels = scripts.map { it.label }.toTypedArray()
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.entorno_dialog_elegir_script_titulo))
            .setItems(labels) { _, which -> openScriptEditor(scripts[which]) }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun openScriptEditor(script: EditableScript) {
        val file = File(entornoScriptsDir(), script.fileName)
        if (!file.exists()) {
            fragment.toastMsg(fragment.getString(R.string.entorno_editor_script_no_encontrado, script.fileName))
            return
        }
        val ctx = fragment.requireContext()
        val content = try { file.readText() } catch (e: Exception) {
            fragment.toastMsg(fragment.getString(R.string.entorno_editor_script_error_guardar, script.fileName, e.message ?: ""))
            return
        }
        val editText = EditText(ctx).apply {
            setText(content)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            isSingleLine = false
            gravity = Gravity.TOP or Gravity.START
            minLines = 14
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(editText) }
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.entorno_dialog_editar_script_titulo, script.fileName))
            .setView(scroll)
            .setPositiveButton(fragment.getString(R.string.entorno_editor_script_guardar)) { _, _ ->
                saveScriptEdit(file, editText.text.toString())
            }
            .setNegativeButton(fragment.getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun saveScriptEdit(file: File, newContent: String) {
        try {
            file.writeText(newContent)
            fragment.toastMsg(fragment.getString(R.string.entorno_editor_script_guardado_toast, file.name))
        } catch (e: Exception) {
            fragment.toastMsg(fragment.getString(R.string.entorno_editor_script_error_guardar, file.name, e.message ?: ""))
        }
    }
}
