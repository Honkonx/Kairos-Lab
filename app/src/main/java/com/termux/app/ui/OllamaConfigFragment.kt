package com.termux.app.ui

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ChatHistoryStore
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.OllamaApiClient
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * Parámetros de inferencia reales de Ollama — antes hardcodeados, Guardar/Restaurar no hacían
 * nada. Estos valores no son cosméticos: ChatFragment (buildContextPrefix/makeOllamaRequest)
 * y cualquier request a Ollama dependen de ~/.ollama_user_config. Antes pasaba por
 * kairos_manager.py cmd_ollama's config-get/config-set/config-reset (python3 + subprocess
 * solo para leer/escribir 7 líneas "CLAVE=valor") — ahora OllamaApiClient.readConfig()/
 * writeConfigValue()/resetConfig() hacen lo mismo con java.io.File directo.
 */
class OllamaConfigFragment : BaseModuleFragment() {

    companion object {
        // Mismas claves que lee ChatFragment.buildContextMessages()/enforceHistoryLimit() —
        // SharedPreferences "kairos_llm_prefs" es el punto de contacto real entre esta
        // pantalla y el chat, no hace falta pasar por ningún callback/broadcast.
        private const val PREFS_NAME = "kairos_llm_prefs"
        private const val KEY_CONTEXT_TURNS = "context_turns"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        // Default = mismo MAX_CONTEXT_TURNS ya hardcodeado en ChatFragment — comportamiento
        // sin cambios hasta que el usuario toque un preset acá.
        private const val DEFAULT_CONTEXT_TURNS = 6
        private const val DEFAULT_HISTORY_LIMIT = 50
        // -1 = sin límite, paridad con el preset "[9] ∞" (OL_DISK_MSGS=9999) de
        // _ollama_config_sql() en menu_nativo.sh.
        private const val HISTORY_LIMIT_UNLIMITED = -1
    }

    override fun getModuleId() = "ollama"
    override fun getModuleName() = getString(R.string.ollama_config_title)

    private lateinit var tempInput: EditText
    private lateinit var repPenaltyInput: EditText
    private lateinit var topPInput: EditText
    private lateinit var topKInput: EditText
    private lateinit var numCtxInput: EditText
    private lateinit var numPredictInput: EditText
    private lateinit var systemPromptInput: EditText
    private lateinit var lanSwitch: SwitchCompat
    private lateinit var keepAliveInput: EditText
    private lateinit var numParallelInput: EditText
    private lateinit var maxLoadedInput: EditText
    private lateinit var numThreadInput: EditText
    private lateinit var numGpuInput: EditText
    private lateinit var ramValueLabel: TextView
    private lateinit var diskValueLabel: TextView

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.ollama_config_card_parametros)) {
            tempInput = paramRow(this, getString(R.string.ollama_config_label_temperatura), "0.7")
            repPenaltyInput = paramRow(this, getString(R.string.ollama_config_label_rep_penalty), "1.1")
            topPInput = paramRow(this, getString(R.string.ollama_config_label_top_p), "0.9")
            topKInput = paramRow(this, getString(R.string.ollama_config_label_top_k), "40")
            numCtxInput = paramRow(this, getString(R.string.ollama_config_label_contexto), "2048")
            numPredictInput = paramRow(this, getString(R.string.ollama_config_label_max_respuesta), "2048")
        }
        addCard(getString(R.string.ollama_config_card_system_prompt)) {
            systemPromptInput = EditText(requireContext()).apply {
                hint = getString(R.string.ollama_config_hint_system_prompt)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setHintTextColor(0xff8888aa.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minLines = 4
                gravity = Gravity.TOP
                background = null
            }
            addView(systemPromptInput)
        }
        // Toggle "escuchar en LAN" (OLLAMA_HOST=0.0.0.0 vs 127.0.0.1) — pedido explícito de
        // auditoría: modulos/ollama.sh's ollama_start.sh bindeaba SIEMPRE a 0.0.0.0 sin que el
        // usuario lo eligiera. Ahora el default es localhost-only y esto es un opt-in real.
        // Se guarda solo (sin botón "Guardar") porque es un toggle discreto, no un valor que
        // se esté ajustando en vivo como los sliders numéricos de arriba. El listener real se
        // conecta recién en loadConfig() (mismo truco "listener null → isChecked → listener"
        // que usa ConfigFragment.switchRow) para que el setChecked programático al leer la
        // config guardada no dispare una escritura innecesaria del mismo valor.
        addCard(getString(R.string.ollama_config_card_red)) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            row.addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_label_lan)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            lanSwitch = SwitchCompat(requireContext()).apply {
                thumbTintList = ContextCompat.getColorStateList(requireContext(), R.color.switch_thumb_color)
                trackTintList = ContextCompat.getColorStateList(requireContext(), R.color.switch_track_color)
            }
            row.addView(lanSwitch)
            addView(row)
            addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_desc_lan)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(2), dp(14), dp(10))
            })
        }
        // OLLAMA_KEEP_ALIVE/OLLAMA_NUM_PARALLEL/OLLAMA_MAX_LOADED_MODELS (ronda de continuación
        // 2026-08-19, ver AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md § Actualización) —
        // confirmados reales contra github.com/ollama/ollama/envconfig/config.go +
        // docs.ollama.com/faq. Antes ninguno de los 3 era ajustable desde la app — solo existía
        // el mecanismo puntual "Liberar modelo de memoria" (unloadModel, keep_alive:0 por
        // request), sin control del comportamiento default del servidor.
        addCard(getString(R.string.ollama_config_card_servidor)) {
            keepAliveInput = textParamRow(this, getString(R.string.ollama_config_label_keep_alive), "")
            numParallelInput = paramRow(this, getString(R.string.ollama_config_label_num_parallel), "0")
            maxLoadedInput = paramRow(this, getString(R.string.ollama_config_label_max_loaded), "0")
            addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_desc_servidor)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(0), dp(14), dp(6))
            })
            // Paridad con LlamaServerConfigFragment's "Threads CPU"/"Capas en GPU (-ngl)"
            // (auditoría de paridad de opciones, 2026-08-28) — mismos "options" reales del
            // /api/chat de Ollama (num_thread/num_gpu, ver OllamaApiClient.CONFIG_DEFAULTS y
            // ChatFragment.makeOllamaRequest), no env vars del binario como el resto de esta
            // card.
            numThreadInput = paramRow(this, getString(R.string.ollama_config_label_num_thread), "0")
            numGpuInput = paramRow(this, getString(R.string.ollama_config_label_num_gpu), "0")
            addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_desc_num_thread_gpu)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(0), dp(14), dp(6))
            })
        }
        actionButton(getString(R.string.ollama_config_btn_guardar), PRIMARY) { save() }
        actionButton(getString(R.string.ollama_config_btn_restaurar), GHOST) { restoreDefaults() }

        buildHistoryCard()
        buildCustomModelCard()

        // Consolidación (2026-08-23, corrección explícita del usuario tras la reorganización
        // anterior — "todas las opciones que son de configuracion de ollama debe ir en una
        // pantalla"): antes Reiniciar/Detalle del proceso/Info GPU/Actualizar/Desinstalar vivían
        // en un PopupMenu de la pantalla principal (OllamaFragment) — ahora viven ACÁ, la única
        // pantalla de configuración/mantenimiento real de Ollama. Cada acción llama exactamente
        // la misma función que antes (movidas de OllamaFragment.kt sin cambios de lógica).
        addCard(getString(R.string.ollama_config_card_mantenimiento)) {
            actionButton(getString(R.string.ollama_config_btn_reiniciar), GHOST) { restartService() }
            actionButton(getString(R.string.ollama_config_btn_detalle_proceso), GHOST) { showProcessDetail() }
            actionButton(getString(R.string.ollama_config_btn_info_gpu), GHOST) { showGpuInfo() }
            actionButton(getString(R.string.ollama_config_btn_actualizar), GHOST) { updateOllama() }
            actionButton(getString(R.string.ollama_config_btn_desinstalar), DANGER) { confirmUninstallModule() }
        }

        loadConfig()
    }

    // ── Mantenimiento (movido de OllamaFragment.kt, 2026-08-23 — misma lógica exacta) ──

    private fun restartService() {
        toast(getString(R.string.ollama_config_toast_reiniciando))
        stopModuleService {
            startModuleService { ok, _ ->
                toast(if (ok) getString(R.string.ollama_config_toast_reiniciado_ok) else getString(R.string.ollama_config_toast_reiniciado_fail))
            }
        }
    }

    // RAM usada/Uptime no tienen equivalente real en ningún lado (ni el propio cmd_ollama los
    // calculaba) — quedan "—" siempre. Se mantiene el diálogo igual, solo cambió desde dónde
    // se dispara.
    private fun showProcessDetail() {
        val version = com.termux.app.data.ModuleRegistry(requireContext()).load().get("ollama.version")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ollama_config_dialog_detalle_titulo))
            .setMessage(getString(R.string.ollama_config_dialog_detalle_msg, version?.ifBlank { "—" } ?: "—"))
            .setPositiveButton(getString(R.string.ollama_config_btn_cerrar), null)
            .show()
    }

    // Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): no se puede usar el
    // helper genérico updateModuleService() a secas — ollama.sh exige --variant en modo silent.
    private fun updateOllama() {
        val variant = com.termux.app.data.ModuleRegistry(requireContext()).load().get("ollama.install_mode")
        toast(getString(R.string.ollama_config_toast_actualizando))
        com.termux.app.ModuleController.installModule(getModuleId(), requireContext(), variant, true, {}) { ok ->
            if (!isAdded) return@installModule
            requireActivity().runOnUiThread {
                toast(if (ok) getString(R.string.ollama_config_toast_actualizado_ok) else getString(R.string.ollama_config_toast_actualizado_fail))
            }
        }
    }

    // Antes pasaba por kairos_manager.py cmd_ollama's gpu-info — acá se hace directo:
    // vulkaninfo por ProcessBuilder, /proc/cpuinfo con java.io.File.
    private fun showGpuInfo() {
        Thread {
            val device = detectVulkanDevice()
            val feats = detectCpuFeatures()
            val vulkanOn = bashrcHasOllamaVulkanExport()
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val featsText = if (feats.isEmpty()) getString(R.string.ollama_config_gpu_none_detected_feats) else feats.joinToString(", ")
                val body = getString(
                    R.string.ollama_config_gpu_body,
                    device ?: getString(R.string.ollama_config_gpu_no_detectado),
                    if (vulkanOn) getString(R.string.ollama_config_gpu_activado) else getString(R.string.ollama_config_gpu_desactivado),
                    featsText
                )
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.ollama_config_dialog_gpu_titulo))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.ollama_config_btn_cerrar), null)
                    .show()
            }
        }.start()
    }

    /** Nombre del dispositivo Vulkan reportado por `vulkaninfo` — línea "deviceName = ...". */
    private fun detectVulkanDevice(): String? {
        return try {
            val pb = ProcessBuilder("vulkaninfo")
            pb.applyTermuxEnv()
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lineSequence()
                .firstOrNull { it.contains("deviceName") }
                ?.substringAfter("=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** i8mm/dotprod(asimddp)/sve — mismas 3 features que ya buscaba cmd_ollama's gpu-info. */
    private fun detectCpuFeatures(): List<String> {
        val featuresLine = try {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val feats = mutableListOf<String>()
        if (featuresLine.contains("i8mm")) feats.add("i8mm")
        if (featuresLine.contains("dotprod") || featuresLine.contains("asimddp")) feats.add("dotprod")
        if (featuresLine.contains("sve")) feats.add("sve")
        return feats
    }

    /** ollama.sh escribe "export OLLAMA_VULKAN=1" en ~/.bashrc — leer la variable de entorno
     * del propio proceso de Kairos siempre daba "0" (applyTermuxEnv() no la propaga). */
    private fun bashrcHasOllamaVulkanExport(): Boolean {
        return try {
            File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".bashrc")
                .readLines()
                .any { it.trim() == "export OLLAMA_VULKAN=1" }
        } catch (_: Exception) {
            false
        }
    }

    // ── Historial de chat (RAM/disco) — paridad con _ollama_config_sql() de
    // menu_nativo.sh. Kairos no tiene un mecanismo "SQLite en RAM vs. disco" propio (el
    // chat es una UI nativa distinta, ver ChatHistoryStore) — el equivalente real más
    // cercano es: "RAM" = cuántos turnos previos se re-envían como contexto en cada
    // request a Ollama (ChatFragment.buildContextMessages), "disco" = cuántos mensajes se
    // conservan en el historial persistido (ChatFragment.enforceHistoryLimit +
    // ChatHistoryStore, un JSON plano en filesDir en vez de un .db SQLite). Ambos ya
    // existían del lado de ChatFragment — acá se agrega la UI de presets + personalizado
    // + estadísticas + borrado que la TUI original sí tenía y Kairos no.
    private fun buildHistoryCard() {
        addCard(getString(R.string.ollama_config_card_historial)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_desc_historial)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(4))
            })

            ramValueLabel = historySectionLabel(this)
            addView(presetButtonRow(
                listOf(
                    getString(R.string.ollama_config_preset_2msg) to 2,
                    getString(R.string.ollama_config_preset_4msg) to 4,
                    getString(R.string.ollama_config_preset_6msg) to 6,
                    getString(R.string.ollama_config_preset_8msg) to 8
                )
            ) { setContextTurns(it) })
            addView(customValueRow(getString(R.string.ollama_config_hint_personalizado_ram)) { raw ->
                val value = raw.toIntOrNull()
                if (value == null || value !in 1..20) toast(getString(R.string.ollama_config_toast_valor_invalido_ram))
                else setContextTurns(value)
            })

            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also {
                    it.topMargin = dp(6); it.bottomMargin = dp(6)
                    it.leftMargin = dp(14); it.rightMargin = dp(14)
                }
                setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBorder))
            })

            diskValueLabel = historySectionLabel(this)
            addView(presetButtonRow(
                listOf("20" to 20, "50" to 50, "100" to 100, "∞" to HISTORY_LIMIT_UNLIMITED)
            ) { setHistoryLimit(it) })
            addView(customValueRow(getString(R.string.ollama_config_hint_personalizado_disco)) { raw ->
                val value = raw.toIntOrNull()
                if (value == null || value !in 10..500) toast(getString(R.string.ollama_config_toast_valor_invalido_disco))
                else setHistoryLimit(value)
            })
        }
        actionButton(getString(R.string.ollama_config_btn_ver_estadisticas), GHOST) { showHistoryStats() }
        actionButton(getString(R.string.ollama_config_btn_borrar_historial), DANGER) { confirmClearHistory() }
        refreshHistoryLabels()
    }

    private fun historySectionLabel(container: LinearLayout): TextView {
        val label = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        container.addView(label)
        return label
    }

    /** Fila de botones "chip" (presets rápidos) — mismo criterio visual que
     *  ChatFragment.transportOptionButton, generalizado a N opciones en vez de 2 fijas. */
    private fun presetButtonRow(options: List<Pair<String, Int>>, onSelect: (Int) -> Unit): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(6))
        }
        options.forEachIndexed { index, (label, value) ->
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also {
                    if (index > 0) it.marginStart = dp(6)
                }
                setOnClickListener { onSelect(value) }
            })
        }
        return row
    }

    /** Campo numérico + botón "Aplicar" — equivalente a las opciones "Personalizado" de
     *  _ollama_config_sql() (RAM 1-20, disco 10-500 acá vs. 10-9999 en la TUI original). */
    private fun customValueRow(hintText: String, onApply: (String) -> Unit): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        val input = EditText(ctx).apply {
            hint = hintText
            textSize = 12f
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row.addView(input)
        row.addView(TextView(ctx).apply {
            text = getString(R.string.ollama_config_btn_aplicar)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) onApply(raw)
            }
        })
        return row
    }

    private fun setContextTurns(value: Int) {
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putInt(KEY_CONTEXT_TURNS, value).apply()
        refreshHistoryLabels()
        toast(getString(R.string.ollama_config_toast_ram_set, value))
    }

    private fun setHistoryLimit(value: Int) {
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putInt(KEY_HISTORY_LIMIT, value).apply()
        refreshHistoryLabels()
        toast(if (value == HISTORY_LIMIT_UNLIMITED) getString(R.string.ollama_config_toast_disco_ilimitado) else getString(R.string.ollama_config_toast_disco_set, value))
    }

    private fun refreshHistoryLabels() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        val ram = prefs.getInt(KEY_CONTEXT_TURNS, DEFAULT_CONTEXT_TURNS)
        val disk = prefs.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        ramValueLabel.text = getString(R.string.ollama_config_label_ram_actual, ram)
        diskValueLabel.text = getString(
            R.string.ollama_config_label_disco_actual,
            if (disk == HISTORY_LIMIT_UNLIMITED) getString(R.string.ollama_config_disco_ilimitado_label) else "$disk"
        )
    }

    /** Estadísticas reales del historial persistido (ChatHistoryStore) — paridad con la
     *  opción "[i] Estadísticas BD" de _ollama_config_sql(). Ahí cuenta filas de un SQLite
     *  (mensajes/chats/imágenes/tamaño/por modelo); acá Kairos no tiene multi-chat (una
     *  sola conversación activa, ver docstring de ChatHistoryStore), así que "chats" no
     *  aplica — se muestra total de mensajes, con imagen, tamaño en disco y desglose por
     *  modelo, que sí tienen equivalente real. */
    private fun showHistoryStats() {
        Thread {
            val ctx = context ?: return@Thread
            val messages = ChatHistoryStore.load(ctx)
            val total = messages.size
            val withImage = messages.count { it.imageBase64 != null }
            val byModel = messages.mapNotNull { it.model?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
            val sizeHuman = ManagerNativeUtils.humanSize(ChatHistoryStore.sizeBytes(ctx))
            runOnMain {
                val sb = StringBuilder()
                sb.append(getString(R.string.ollama_config_stats_mensajes, total))
                sb.append(getString(R.string.ollama_config_stats_con_imagen, withImage))
                sb.append(getString(R.string.ollama_config_stats_tamano_disco, sizeHuman))
                if (byModel.isNotEmpty()) {
                    sb.append(getString(R.string.ollama_config_stats_por_modelo_header))
                    byModel.forEach { (model, count) -> sb.append(getString(R.string.ollama_config_stats_modelo_line, model, count)) }
                }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.ollama_config_dialog_stats_titulo))
                    .setMessage(sb.toString().trim())
                    .setPositiveButton(getString(R.string.ollama_config_btn_cerrar), null)
                    .show()
            }
        }.start()
    }

    /** Borra TODO el historial persistido — paridad con "[j] Borrar BD". Solo afecta el
     *  archivo en disco: si el Chat IA sigue vivo en background (TermuxActivity lo
     *  mantiene con add+hide/show, ver ChatFragment.onHiddenChanged), sus mensajes en
     *  memoria recién se vacían la próxima vez que ese Fragment recargue el historial. */
    private fun confirmClearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ollama_config_dialog_borrar_historial_titulo))
            .setMessage(getString(R.string.ollama_config_dialog_borrar_historial_msg))
            .setPositiveButton(getString(R.string.ollama_config_btn_borrar)) { _, _ ->
                context?.let { ChatHistoryStore.clear(it) }
                toast(getString(R.string.ollama_config_toast_historial_borrado))
            }
            .setNegativeButton(getString(R.string.ollama_config_btn_cancelar), null)
            .show()
    }

    // ── Crear modelo personalizado (Modelfile) — paridad con las opciones [6]/[0] de
    // submenu_ollama_personalizacion() en menu_nativo.sh, fusionadas en un solo paso (allá
    // eran 2 pasos manuales: generar Modelfile, después crear el modelo desde ese archivo).
    private fun buildCustomModelCard() {
        addCard(getString(R.string.ollama_config_card_modelo_personalizado)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_desc_modelo_personalizado)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
        }
        actionButton(getString(R.string.ollama_config_btn_crear_modelo), PRIMARY) { startCustomModelFlow() }
    }

    private fun startCustomModelFlow() {
        toast(getString(R.string.ollama_config_toast_buscando_modelos))
        Thread {
            val models = try {
                OllamaApiClient.listModels().map { it.name }
            } catch (e: Exception) {
                runOnMain { toast(getString(R.string.ollama_config_toast_error_consultar_ollama, e.message ?: getString(R.string.ollama_config_esta_corriendo_fallback))) }
                return@Thread
            }
            runOnMain {
                if (models.isEmpty()) {
                    toast(getString(R.string.ollama_config_toast_no_hay_modelos))
                } else {
                    showBaseModelPicker(models)
                }
            }
        }.start()
    }

    private fun showBaseModelPicker(models: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ollama_config_dialog_modelo_base_titulo))
            .setItems(models.toTypedArray()) { _, which -> promptCustomModelName(models[which]) }
            .setNegativeButton(getString(R.string.ollama_config_btn_cancelar), null)
            .show()
    }

    private fun promptCustomModelName(baseModel: String) {
        val ctx = requireContext()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val input = EditText(ctx).apply {
            setText(getString(R.string.ollama_config_nombre_modelo_default, stamp))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.ollama_config_dialog_nombre_modelo_titulo))
            .setMessage(getString(R.string.ollama_config_dialog_nombre_modelo_msg, baseModel))
            .setView(input)
            .setPositiveButton(getString(R.string.ollama_config_btn_crear)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) toast(getString(R.string.ollama_config_toast_nombre_invalido)) else createCustomModel(baseModel, name)
            }
            .setNegativeButton(getString(R.string.ollama_config_btn_cancelar), null)
            .show()
    }

    /** Escribe ~/Modelfile con la config REAL guardada (~/.ollama_user_config, la misma que
     *  lee ChatFragment en cada request — ver OllamaApiClient.readConfig()), no valores
     *  hardcodeados ni lo que haya sin guardar en los EditText de arriba, y corre
     *  `ollama create <name> -f ~/Modelfile` (ManagerNativeUtils.runExec(), mismo patrón que
     *  el resto de la app — aplica el PATH de Termux para que resuelva el binario "ollama"). */
    private fun createCustomModel(baseModel: String, modelName: String) {
        toast(getString(R.string.ollama_config_toast_creando_modelo, modelName))
        Thread {
            val cfg = try {
                OllamaApiClient.readConfig()
            } catch (e: Exception) {
                runOnMain { toast(getString(R.string.ollama_config_toast_error_leyendo_config, e.message ?: getString(R.string.ollama_config_desconocido))) }
                return@Thread
            }
            // Mismo criterio de escape que _EPROMPT_ESC en menu_nativo.sh antes de envolver
            // en comillas triples SYSTEM """...""".
            val systemPrompt = (cfg["OLLAMA_SYSTEM_PROMPT"] ?: "").replace("\"", "\\\"")
            val modelfile = buildString {
                append("FROM ").append(baseModel).append('\n')
                append("SYSTEM \"\"\"").append(systemPrompt).append("\"\"\"\n")
                append("PARAMETER temperature ").append(cfg["OLLAMA_TEMP"] ?: "0.7").append('\n')
                append("PARAMETER top_p ").append(cfg["OLLAMA_TOP_P"] ?: "0.9").append('\n')
                append("PARAMETER top_k ").append(cfg["OLLAMA_TOP_K"] ?: "40").append('\n')
                append("PARAMETER repeat_penalty ").append(cfg["OLLAMA_REP_PENALTY"] ?: "1.1").append('\n')
                append("PARAMETER num_ctx ").append(cfg["OLLAMA_NUM_CTX"] ?: "2048").append('\n')
                append("PARAMETER num_predict ").append(cfg["OLLAMA_NUM_PREDICT"] ?: "2048").append('\n')
            }
            val modelfileOnDisk = File(ManagerNativeUtils.home, "Modelfile")
            try {
                modelfileOnDisk.writeText(modelfile)
            } catch (e: Exception) {
                runOnMain { toast(getString(R.string.ollama_config_toast_error_escribiendo_modelfile, e.message ?: getString(R.string.ollama_config_desconocido))) }
                return@Thread
            }
            val (exitCode, stdout, stderr) = ManagerNativeUtils.runExec(
                listOf("ollama", "create", modelName, "-f", modelfileOnDisk.absolutePath),
                timeoutSeconds = 180L
            )
            runOnMain {
                if (exitCode == 0) {
                    toast(getString(R.string.ollama_config_toast_modelo_creado, modelName))
                } else {
                    val detail = stderr.ifBlank { stdout.ifBlank { getString(R.string.ollama_config_codigo_salida, exitCode) } }
                    toast(getString(R.string.ollama_config_toast_error_creando_modelo, detail))
                }
            }
        }.start()
    }

    private fun paramRow(container: LinearLayout, label: String, defaultValue: String): EditText {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val input = EditText(ctx).apply {
            setText(defaultValue)
            textSize = 13f
            gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(dp(90), WRAP_CONTENT)
        }
        row.addView(input)
        container.addView(row)
        return input
    }

    /** Mismo layout que paramRow() pero texto libre (no numérico) — OLLAMA_KEEP_ALIVE acepta
     *  duraciones tipo "5m"/"1h" además de enteros ("-1"/"0"), no solo dígitos. */
    private fun textParamRow(container: LinearLayout, label: String, defaultValue: String): EditText {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val input = EditText(ctx).apply {
            setText(defaultValue)
            textSize = 13f
            gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(dp(90), WRAP_CONTENT)
        }
        row.addView(input)
        container.addView(row)
        return input
    }

    private fun loadConfig() {
        Thread {
            val cfg = try {
                OllamaApiClient.readConfig()
            } catch (e: Exception) {
                runOnMain { toast(getString(R.string.ollama_config_toast_error_cargando_config, e.message ?: getString(R.string.ollama_config_desconocido))) }
                return@Thread
            }
            runOnMain {
                tempInput.setText(cfg["OLLAMA_TEMP"] ?: "0.7")
                repPenaltyInput.setText(cfg["OLLAMA_REP_PENALTY"] ?: "1.1")
                topPInput.setText(cfg["OLLAMA_TOP_P"] ?: "0.9")
                topKInput.setText(cfg["OLLAMA_TOP_K"] ?: "40")
                numCtxInput.setText(cfg["OLLAMA_NUM_CTX"] ?: "2048")
                numPredictInput.setText(cfg["OLLAMA_NUM_PREDICT"] ?: "2048")
                systemPromptInput.setText(cfg["OLLAMA_SYSTEM_PROMPT"] ?: "")
                keepAliveInput.setText(cfg["OLLAMA_KEEP_ALIVE"] ?: "")
                numParallelInput.setText(cfg["OLLAMA_NUM_PARALLEL"] ?: "0")
                maxLoadedInput.setText(cfg["OLLAMA_MAX_LOADED_MODELS"] ?: "0")
                numThreadInput.setText(cfg["OLLAMA_NUM_THREAD"] ?: "0")
                numGpuInput.setText(cfg["OLLAMA_NUM_GPU"] ?: "0")
                lanSwitch.setOnCheckedChangeListener(null)
                lanSwitch.isChecked = cfg["OLLAMA_LAN"] == "1"
                lanSwitch.setOnCheckedChangeListener { _, checked -> saveLan(checked) }
            }
        }.start()
    }

    private fun saveLan(checked: Boolean) {
        Thread {
            try {
                OllamaApiClient.writeConfigValue("OLLAMA_LAN", if (checked) "1" else "0")
            } catch (e: Exception) {
                runOnMain { toast(getString(R.string.ollama_config_toast_error_guardando_red, e.message ?: getString(R.string.ollama_config_desconocido))) }
            }
        }.start()
    }

    private fun runOnMain(block: () -> Unit) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded) block() }
    }

    private fun save() {
        val temp = tempInput.text.toString().toFloatOrNull()
        if (temp == null || temp < 0f || temp > 2f) {
            toast(getString(R.string.ollama_config_toast_temp_invalida))
            return
        }
        val fields = listOf(
            "OLLAMA_TEMP" to tempInput.text.toString(),
            "OLLAMA_REP_PENALTY" to repPenaltyInput.text.toString(),
            "OLLAMA_TOP_P" to topPInput.text.toString(),
            "OLLAMA_TOP_K" to topKInput.text.toString(),
            "OLLAMA_NUM_CTX" to numCtxInput.text.toString(),
            "OLLAMA_NUM_PREDICT" to numPredictInput.text.toString(),
            "OLLAMA_SYSTEM_PROMPT" to systemPromptInput.text.toString(),
            "OLLAMA_KEEP_ALIVE" to keepAliveInput.text.toString().trim(),
            "OLLAMA_NUM_PARALLEL" to (numParallelInput.text.toString().trim().ifBlank { "0" }),
            "OLLAMA_MAX_LOADED_MODELS" to (maxLoadedInput.text.toString().trim().ifBlank { "0" }),
            "OLLAMA_NUM_THREAD" to (numThreadInput.text.toString().trim().ifBlank { "0" }),
            "OLLAMA_NUM_GPU" to (numGpuInput.text.toString().trim().ifBlank { "0" })
        )
        saveFields(fields)
    }

    // Escribe las 7 claves de una — antes se hacía una llamada a python3 por campo
    // (saveNext recursivo, un proceso nuevo por clave); ahora es una sola escritura de
    // archivo, no hace falta encadenar callbacks async por cada línea.
    private fun saveFields(fields: List<Pair<String, String>>) {
        Thread {
            val error = try {
                fields.forEach { (key, value) -> OllamaApiClient.writeConfigValue(key, value) }
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.ollama_config_desconocido)
            }
            runOnMain {
                toast(if (error == null) getString(R.string.ollama_config_toast_guardado) else getString(R.string.ollama_config_toast_error_guardando_config, error))
            }
        }.start()
    }

    private fun restoreDefaults() {
        Thread {
            val error = try {
                OllamaApiClient.resetConfig()
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.ollama_config_desconocido)
            }
            runOnMain {
                toast(if (error == null) getString(R.string.ollama_config_toast_restaurado) else getString(R.string.ollama_config_toast_error_generico, error))
                loadConfig()
            }
        }.start()
    }
}
