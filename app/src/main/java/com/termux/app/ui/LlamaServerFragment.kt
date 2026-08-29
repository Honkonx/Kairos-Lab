package com.termux.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.LocalModelManager
import com.termux.app.util.kairosThemeColor
import com.termux.shared.termux.TermuxConstants
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pantalla principal de IA Local — llama.cpp, rediseño visual real (2026-08-23, ver
 * docs/humano209.md, misma corrección que Ollama: "quedo horrible todo [...] hicite copia y
 * paste no organizaste bien las cosas"). Compacta a `compactStatusRow()` + `modelRow()`; TODOS
 * los parámetros del motor/servidor (antes en `buildParamsCard()`, esta misma clase) viven ahora
 * en `LlamaServerConfigFragment` — un solo ícono "⚙" acá, nada repartido en la pantalla
 * principal.
 */
class LlamaServerFragment : BaseModuleFragment() {
    override fun getModuleId() = "llamaserver"
    override fun getModuleName() = getString(R.string.llamaserver_title)

    private lateinit var runSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var runStatusRow: View
    private var modelCard: LinearLayout? = null

    private fun configFile() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".llamaserver_user_config")

    private fun readConfigValue(key: String): String {
        val file = configFile()
        if (!file.exists()) return ""
        return file.readLines()
            .firstOrNull { it.trim().startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim() ?: ""
    }

    private fun writeConfigValue(key: String, value: String) {
        val file = configFile()
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val idx = lines.indexOfFirst { it.trim().startsWith("$key=") }
        if (idx >= 0) lines[idx] = "$key=$value" else lines.add("$key=$value")
        file.writeText(lines.joinToString("\n") + "\n")
    }

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        addCard {
            addView(compactStatusRow(statusText = ":8085", isActive = false) {
                addView(TextView(requireContext()).apply {
                    text = "⚙"
                    textSize = 17f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(6), dp(4), dp(10), dp(4))
                    setOnClickListener { navigateTo(LlamaServerConfigFragment()) }
                })
                runSwitch = androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                    thumbTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_thumb_color)
                    trackTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_track_color)
                    setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                }
                addView(runSwitch)
            }.also { runStatusRow = it })
        }

        addCard(getString(R.string.llamaserver_card_modelo)) {
            modelCard = this
            addView(modelInfoRow(getString(R.string.llamaserver_cargando), ""))
        }
        actionButton(getString(R.string.llamaserver_btn_elegir_modelo), GHOST) { showModelPicker() }
        actionButton(getString(R.string.llamaserver_btn_catalogo), GHOST) { navigateTo(LocalAIFragment()) }

        refreshStatus()
    }

    private fun onToggle(on: Boolean) {
        if (on) {
            val model = readConfigValue("LLAMA_SERVER_MODEL")
            if (model.isBlank()) {
                toast(getString(R.string.llamaserver_toast_elegi_modelo))
                runSwitch.isChecked = false
                return
            }
            toast(getString(R.string.llamaserver_toast_iniciando))
            startModuleService { ok, _ ->
                toast(if (ok) getString(R.string.llamaserver_toast_iniciado_ok) else getString(R.string.llamaserver_toast_iniciado_fail))
                refreshStatus()
            }
        } else {
            stopModuleService { ok ->
                toast(if (ok) getString(R.string.llamaserver_toast_detenido_ok) else getString(R.string.llamaserver_toast_detenido_fail))
                refreshStatus()
            }
        }
    }

    private fun modelInfoRow(name: String, subtitle: String): View = modelRow(
        icon = "📦",
        name = name,
        subtitle = subtitle,
    )

    /** Estado real vía /health (ver docs/humano/humano cita original en el historial de este
     * archivo) — "activo" solo cuando el modelo terminó de cargar, no apenas arranca el proceso. */
    private fun refreshStatus() {
        Thread {
            val running = isModuleRunning()
            val model = readConfigValue("LLAMA_SERVER_MODEL")
            val health = if (running) checkHealth() else null
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                runSwitch.setOnCheckedChangeListener(null)
                runSwitch.isChecked = running
                runSwitch.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                val dot = (runStatusRow as? LinearLayout)?.getChildAt(0)
                dot?.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(
                        if (running) requireContext().kairosThemeColor(R.attr.kairosGreen)
                        else requireContext().kairosThemeColor(R.attr.kairosText3)
                    )
                }
                val subtitle = when {
                    !running -> getString(R.string.llamaserver_status_detenido)
                    health == "ok" -> getString(R.string.llamaserver_status_activo)
                    health == "loading model" -> getString(R.string.llamaserver_status_cargando_modelo)
                    health != null -> health
                    else -> getString(R.string.llamaserver_status_proceso_activo)
                }
                modelCard?.let {
                    it.removeAllViews()
                    it.addView(modelInfoRow(model.ifBlank { getString(R.string.llamaserver_modelo_ninguno) }, subtitle))
                }
            }
        }.start()
    }

    private fun checkHealth(): String? {
        return try {
            val port = readConfigValue("LLAMA_SERVER_PORT").ifBlank { "8085" }
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
                JSONObject(text).optString("status").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showModelPicker() {
        val ctx = requireContext()
        val models = LocalModelManager.listModels(ctx)
        if (models.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.llamaserver_dialog_sin_modelos_titulo))
                .setMessage(getString(R.string.llamaserver_dialog_sin_modelos_msg))
                .setPositiveButton(getString(R.string.llamaserver_btn_cerrar), null)
                .show()
            return
        }
        val names = models.map { it.name }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.llamaserver_dialog_elegir_modelo_titulo))
            .setItems(names) { _, index ->
                writeConfigValue("LLAMA_SERVER_MODEL", names[index])
                refreshStatus()
                toast(getString(R.string.llamaserver_toast_modelo_elegido, names[index]))
            }
            .setNegativeButton(getString(R.string.llamaserver_btn_cancelar), null)
            .show()
    }
}
