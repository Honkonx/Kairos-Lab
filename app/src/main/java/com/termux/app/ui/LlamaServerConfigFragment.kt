package com.termux.app.ui

import android.app.ActivityManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.llm.GpuBackend
import com.termux.llm.LlamaEngine
import com.termux.shared.termux.TermuxConstants
import java.io.File
import com.termux.app.util.kairosThemeColor
import com.termux.app.util.applyTermuxEnv

/**
 * Pantalla "⚙ Configuración" de IA Local — llama.cpp (2026-08-23, ver docs/humano209.md,
 * pedido explícito del usuario: "contexto, token, threads entre otras opciones que estan en la
 * pantalla principal no pasaron a configuracion"). Recibe TODA la card "PARÁMETROS" que antes
 * vivía en `LlamaServerFragment.buildParamsCard()` — mismo archivo de config
 * (`~/.llamaserver_user_config`), mismas claves, mismo comportamiento, solo cambia la pantalla.
 *
 * 2026-08-24 (ver docs/humano210.md, corrección explícita: "en ia local ahi dos tuercas una en
 * la pantalla principal y otra dentro de catalogo, deja todo dentro de la tuerca en la pantalla
 * principal") — absorbe también las cards MOTOR/PARÁMETROS que antes vivían en
 * `LocalAIConfigFragment` (ahora eliminado), reachable desde el "⚙" propio del catálogo GGUF
 * (`LocalAIFragment`). Mismas `SharedPreferences kairos_llm_prefs`, mismas claves, mismo
 * comportamiento — el motor embebido que usa `ChatFragment` es un subsistema DISTINTO del
 * servidor HTTP `llama-server` de arriba (uno corre dentro del proceso de la app, el otro es un
 * binario aparte con su propio puerto), pero para el usuario ambos son "la configuración de IA
 * Local" — una sola pantalla, cards separadas por claridad, un solo ícono "⚙" de entrada.
 */
class LlamaServerConfigFragment : BaseModuleFragment() {

    companion object {
        private const val EMBEDDED_PREFS = "kairos_llm_prefs"
        private const val KEY_BACKEND = "backend"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_CONTEXT_SIZE = "context_size"
        // Mismas claves que OllamaConfigFragment/ChatFragment.buildContextMessages() —
        // "context_turns" es compartida entre motores (ver comentario grande en
        // strings_ollama.xml), no exclusiva de Ollama pese al nombre histórico de la key.
        private const val KEY_CONTEXT_TURNS = "context_turns"
        private const val DEFAULT_CONTEXT_TURNS = 6
    }

    private lateinit var gpuStatusText: TextView
    private lateinit var temperatureLabel: TextView
    private lateinit var contextSizeLabel: TextView
    private lateinit var ramValueLabel: TextView
    override fun getModuleId() = "llamaserver"
    override fun getModuleName() = getString(R.string.llamaserver_config_title)

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
        var ctxInput: EditText? = null
        var threadsInput: EditText? = null
        var nglInput: EditText? = null
        var apiKeyInput: EditText? = null
        var parallelInput: EditText? = null
        var embeddingsSwitch: SwitchCompat? = null
        var lanSwitch: SwitchCompat? = null
        var systemPromptInput: EditText? = null

        addCard(getString(R.string.llamaserver_config_card_motor)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.llamaserver_config_desc_motor)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(4))
            })
            ctxInput = paramField(this, getString(R.string.llamaserver_config_label_contexto))
            threadsInput = paramField(this, getString(R.string.llamaserver_config_label_threads))
            nglInput = paramField(this, getString(R.string.llamaserver_config_label_ngl))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.llamaserver_config_desc_gpu_vulkan)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(0), dp(14), dp(6))
            })
        }

        addCard(getString(R.string.llamaserver_config_card_servidor)) {
            apiKeyInput = paramTextField(this, getString(R.string.llamaserver_config_label_api_key))
            parallelInput = paramField(this, getString(R.string.llamaserver_config_label_parallel))
            embeddingsSwitch = paramSwitch(this, getString(R.string.llamaserver_config_label_embeddings))
            lanSwitch = paramSwitch(this, getString(R.string.llamaserver_config_label_lan))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.llamaserver_config_desc_api_key)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(0), dp(14), dp(6))
            })
        }

        ctxInput?.setText(readConfigValue("LLAMA_SERVER_CTX_SIZE").ifBlank { "0" })
        threadsInput?.setText(readConfigValue("LLAMA_SERVER_THREADS").ifBlank { "0" })
        nglInput?.setText(readConfigValue("LLAMA_SERVER_NGL").ifBlank { "0" })
        apiKeyInput?.setText(readConfigValue("LLAMA_SERVER_API_KEY"))
        parallelInput?.setText(readConfigValue("LLAMA_SERVER_PARALLEL").ifBlank { "0" })
        embeddingsSwitch?.isChecked = readConfigValue("LLAMA_SERVER_EMBEDDINGS") == "1"
        lanSwitch?.isChecked = readConfigValue("LLAMA_SERVER_LAN") == "1"

        // System prompt — clave compartida OLLAMA_SYSTEM_PROMPT (ver comentario grande en
        // strings_ollama.xml § llamaserver_config_card_system_prompt): antes solo alcanzable
        // desde OllamaConfigFragment, que bloquea la pantalla entera si Ollama no está
        // instalado. OllamaApiClient.readConfig()/writeConfigValue() son java.io.File puro,
        // sin dependencia del binario ollama.
        addCard(getString(R.string.llamaserver_config_card_system_prompt)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.llamaserver_config_desc_system_prompt)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(4))
            })
            systemPromptInput = EditText(requireContext()).apply {
                hint = getString(R.string.ollama_config_hint_system_prompt)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setHintTextColor(0xff8888aa.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minLines = 4
                gravity = Gravity.TOP
                background = null
            }
            systemPromptInput?.setText(
                try {
                    com.termux.app.util.OllamaApiClient.readConfig()["OLLAMA_SYSTEM_PROMPT"] ?: ""
                } catch (_: Exception) { "" }
            )
            addView(systemPromptInput)
        }

        // Contexto de chat (RAM) — clave compartida "context_turns" (ver mismo comentario de
        // arriba), sin equivalente accesible en ChatFragment (a diferencia de history_limit,
        // que sí tiene un control in-chat — ver ChatFragment's showSettingsDialog).
        addCard(getString(R.string.llamaserver_config_card_contexto_ram)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.llamaserver_config_desc_contexto_ram)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(4))
            })
            ramValueLabel = TextView(requireContext()).apply {
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(14), dp(10), dp(14), dp(4))
            }
            addView(ramValueLabel)
            val presetRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(2), dp(14), dp(6))
            }
            listOf(
                getString(R.string.ollama_config_preset_2msg) to 2,
                getString(R.string.ollama_config_preset_4msg) to 4,
                getString(R.string.ollama_config_preset_6msg) to 6,
                getString(R.string.ollama_config_preset_8msg) to 8
            ).forEachIndexed { index, (label, value) ->
                presetRow.addView(TextView(requireContext()).apply {
                    text = label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBg3))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also {
                        if (index > 0) it.marginStart = dp(6)
                    }
                    setOnClickListener { setContextTurns(value) }
                })
            }
            addView(presetRow)
            val customRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(2), dp(14), dp(10))
            }
            val customInput = EditText(requireContext()).apply {
                hint = getString(R.string.ollama_config_hint_personalizado_ram)
                textSize = 12f
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            customRow.addView(customInput)
            customRow.addView(TextView(requireContext()).apply {
                text = getString(R.string.ollama_config_btn_aplicar)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosBlue))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    val value = customInput.text.toString().trim().toIntOrNull()
                    if (value == null || value !in 1..20) toast(getString(R.string.ollama_config_toast_valor_invalido_ram))
                    else setContextTurns(value)
                }
            })
            addView(customRow)
        }
        refreshRamLabel()

        actionButton(getString(R.string.llamaserver_config_btn_guardar_parametros), GHOST) {
            val ctx = ctxInput?.text?.toString()?.trim()?.toIntOrNull()
            val threads = threadsInput?.text?.toString()?.trim()?.toIntOrNull()
            val ngl = nglInput?.text?.toString()?.trim()?.toIntOrNull()
            val parallel = parallelInput?.text?.toString()?.trim()?.toIntOrNull()
            if (ctx == null || ctx < 0 || threads == null || threads < 0 || ngl == null || ngl < 0 ||
                parallel == null || parallel < 0
            ) {
                toast(getString(R.string.llamaserver_config_toast_valores_invalidos))
                return@actionButton
            }
            writeConfigValue("LLAMA_SERVER_CTX_SIZE", ctx.toString())
            writeConfigValue("LLAMA_SERVER_THREADS", threads.toString())
            writeConfigValue("LLAMA_SERVER_NGL", ngl.toString())
            writeConfigValue("LLAMA_SERVER_API_KEY", apiKeyInput?.text?.toString()?.trim() ?: "")
            writeConfigValue("LLAMA_SERVER_PARALLEL", parallel.toString())
            writeConfigValue("LLAMA_SERVER_EMBEDDINGS", if (embeddingsSwitch?.isChecked == true) "1" else "0")
            writeConfigValue("LLAMA_SERVER_LAN", if (lanSwitch?.isChecked == true) "1" else "0")
            Thread {
                try {
                    com.termux.app.util.OllamaApiClient.writeConfigValue(
                        "OLLAMA_SYSTEM_PROMPT", systemPromptInput?.text?.toString() ?: ""
                    )
                } catch (_: Exception) { /* system prompt es opcional — el resto ya se guardó */ }
            }.start()
            toast(getString(R.string.llamaserver_config_toast_guardado))
        }

        // ── Motor embebido (usado por Chat/Catálogo GGUF, `kairos_llm_prefs`) —
        // absorbido de LocalAIConfigFragment, ver comentario de cabecera de esta clase. ──
        addCard(getString(R.string.llamaserver_config_card_motor_embebido)) {
            val statusRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            statusRow.addView(TextView(context).apply {
                text = "🖥"
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            })
            gpuStatusText = TextView(context).apply {
                text = getString(R.string.llamaserver_config_detectando)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
            }
            statusRow.addView(gpuStatusText)
            addView(statusRow)

            val embeddedPrefs = requireContext().getSharedPreferences(EMBEDDED_PREFS, 0)
            val savedBackend = embeddedPrefs.getString(KEY_BACKEND, GpuBackend.VULKAN_IF_AVAILABLE.name)
            val radioGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(dp(14), dp(0), dp(14), dp(6))
            }
            val rbVulkan = RadioButton(context).apply {
                text = getString(R.string.llamaserver_config_radio_vulkan)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                isChecked = savedBackend == GpuBackend.VULKAN_IF_AVAILABLE.name
            }
            val rbCpu = RadioButton(context).apply {
                text = getString(R.string.llamaserver_config_radio_cpu)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                isChecked = savedBackend == GpuBackend.CPU_ONLY.name
            }
            radioGroup.addView(rbVulkan)
            radioGroup.addView(rbCpu)
            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                val backend = if (checkedId == rbVulkan.id) GpuBackend.VULKAN_IF_AVAILABLE else GpuBackend.CPU_ONLY
                embeddedPrefs.edit().putString(KEY_BACKEND, backend.name).apply()
            }
            addView(radioGroup)
        }

        addCard(getString(R.string.llamaserver_config_card_generacion)) {
            val embeddedPrefs = requireContext().getSharedPreferences(EMBEDDED_PREFS, 0)
            val ramTierMaxCtx = ramTierMaxContextSize(requireContext())

            temperatureLabel = TextView(context).apply {
                textSize = 14f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setPadding(dp(14), dp(6), dp(14), dp(0))
            }
            addView(temperatureLabel)
            val savedTemp = embeddedPrefs.getFloat(KEY_TEMPERATURE, 0.7f)
            updateTemperatureLabel(savedTemp)
            val tempSeek = SeekBar(context).apply {
                max = 200 // 0.00 .. 2.00, pasos de 0.01
                progress = (savedTemp * 100).toInt()
                setPadding(dp(10), 0, dp(10), 0)
            }
            tempSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val temp = progress / 100f
                    updateTemperatureLabel(temp)
                    if (fromUser) embeddedPrefs.edit().putFloat(KEY_TEMPERATURE, temp).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            addView(tempSeek)

            contextSizeLabel = TextView(context).apply {
                textSize = 14f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setPadding(dp(14), dp(12), dp(14), dp(0))
            }
            addView(contextSizeLabel)
            val savedCtx = embeddedPrefs.getInt(KEY_CONTEXT_SIZE, minOf(2048, ramTierMaxCtx))
            updateContextSizeLabel(savedCtx, ramTierMaxCtx)
            val ctxSeek = SeekBar(context).apply {
                max = 8192 - 512
                progress = savedCtx - 512
                setPadding(dp(10), 0, dp(10), 0)
            }
            ctxSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val ctxSize = progress + 512
                    updateContextSizeLabel(ctxSize, ramTierMaxCtx)
                    if (fromUser) embeddedPrefs.edit().putInt(KEY_CONTEXT_SIZE, ctxSize).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            addView(ctxSeek)

            addView(TextView(context).apply {
                text = getString(R.string.llamaserver_config_desc_top_p_fixed)
                textSize = 11f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(10), dp(14), dp(6))
            })
        }

        refreshGpuStatus()

        addCard(getString(R.string.llamaserver_config_card_mantenimiento)) {
            // Paridad con OllamaConfigFragment's "Reiniciar"/"Detalle del proceso" (auditoría
            // de paridad de opciones, 2026-08-28) — antes solo llama.cpp tenía "Actualizar"/
            // "Desinstalar" acá, sin forma de reiniciar el servicio ni ver su estado real sin
            // salir a la terminal (ver .claude/rules/kairos-product-philosophy.md).
            actionButton(getString(R.string.llamaserver_config_btn_reiniciar), GHOST) { restartService() }
            actionButton(getString(R.string.llamaserver_config_btn_detalle_proceso), GHOST) { showProcessDetail() }
            // Paridad con OllamaConfigFragment's "Info GPU" (auditoría de paridad de opciones,
            // 2026-08-28) — antes solo el motor EMBEBIDO tenía info de GPU (card de arriba); el
            // binario llama-server (proceso aparte, -ngl en vez de un toggle) no tenía
            // equivalente propio, a diferencia de Ollama que sí lo expone para su binario.
            actionButton(getString(R.string.llamaserver_config_btn_info_gpu), GHOST) { showGpuInfo() }
            actionButton(getString(R.string.llamaserver_config_btn_actualizar), GHOST) {
                toast(getString(R.string.llamaserver_config_toast_actualizando))
                updateModuleService { ok ->
                    toast(if (ok) getString(R.string.llamaserver_config_toast_actualizado_ok) else getString(R.string.llamaserver_config_toast_actualizado_fail))
                }
            }
            actionButton(getString(R.string.llamaserver_config_btn_desinstalar), DANGER) { confirmUninstallModule() }
        }
    }

    // ── Mantenimiento (paridad con OllamaConfigFragment, 2026-08-28 — mismo patrón exacto:
    // stopModuleService+startModuleService encadenados, AlertDialog de solo-lectura). ──

    private fun restartService() {
        toast(getString(R.string.llamaserver_config_toast_reiniciando))
        stopModuleService {
            startModuleService { ok, _ ->
                toast(if (ok) getString(R.string.llamaserver_config_toast_reiniciado_ok) else getString(R.string.llamaserver_config_toast_reiniciado_fail))
            }
        }
    }

    // ── Contexto de chat (RAM) — mismos SharedPreferences/clave que OllamaConfigFragment,
    // ver comentario grande en strings_ollama.xml. ──
    private fun setContextTurns(value: Int) {
        requireContext().getSharedPreferences(EMBEDDED_PREFS, 0).edit()
            .putInt(KEY_CONTEXT_TURNS, value).apply()
        refreshRamLabel()
        toast(getString(R.string.ollama_config_toast_ram_set, value))
    }

    private fun refreshRamLabel() {
        val ram = requireContext().getSharedPreferences(EMBEDDED_PREFS, 0)
            .getInt(KEY_CONTEXT_TURNS, DEFAULT_CONTEXT_TURNS)
        ramValueLabel.text = getString(R.string.ollama_config_label_ram_actual, ram)
    }

    // ── Info GPU / Vulkan para el binario llama-server — mismo mecanismo de detección que
    // OllamaConfigFragment.showGpuInfo() (vulkaninfo + /proc/cpuinfo), sin el chequeo de
    // bashrc (llama-server no tiene un toggle tipo OLLAMA_VULKAN — Vulkan viene siempre
    // compilado, ver comentario de llamaserver_config_gpu_body). ──
    private fun showGpuInfo() {
        Thread {
            val device = detectVulkanDevice()
            val feats = detectCpuFeatures()
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val featsText = if (feats.isEmpty()) getString(R.string.ollama_config_gpu_none_detected_feats) else feats.joinToString(", ")
                val body = getString(
                    R.string.llamaserver_config_gpu_body,
                    device ?: getString(R.string.ollama_config_gpu_no_detectado),
                    featsText
                )
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.llamaserver_config_dialog_gpu_titulo))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.llamaserver_btn_cerrar), null)
                    .show()
            }
        }.start()
    }

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

    private fun showProcessDetail() {
        val port = readConfigValue("LLAMA_SERVER_PORT").ifBlank { "8085" }
        val model = readConfigValue("LLAMA_SERVER_MODEL").ifBlank { "—" }
        val ctx = readConfigValue("LLAMA_SERVER_CTX_SIZE").ifBlank { "0 (default)" }
        val ngl = readConfigValue("LLAMA_SERVER_NGL").ifBlank { "0 (CPU)" }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.llamaserver_config_dialog_detalle_titulo))
            .setMessage(getString(R.string.llamaserver_config_dialog_detalle_msg, port, model, ctx, ngl))
            .setPositiveButton(getString(R.string.llamaserver_btn_cerrar), null)
            .show()
    }

    private fun paramTextField(container: LinearLayout, label: String): EditText {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val input = EditText(ctx).apply {
            textSize = 13f
            gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(dp(150), WRAP_CONTENT)
        }
        row.addView(input)
        container.addView(row)
        return input
    }

    private fun paramSwitch(container: LinearLayout, label: String): SwitchCompat {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val switch = SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
        }
        row.addView(switch)
        container.addView(row)
        return switch
    }

    /** Idéntico al que tenía `LocalAIConfigFragment.refreshGpuStatus()` — absorbido acá tal cual. */
    private fun refreshGpuStatus() {
        Thread {
            val info = try {
                val engine = LlamaEngine()
                engine.loadBackends(requireContext().applicationInfo.nativeLibraryDir)
                val name = engine.getGpuDeviceInfo()
                if (name.isNotEmpty()) getString(R.string.llamaserver_config_gpu_detectada, name) else getString(R.string.llamaserver_config_sin_gpu)
            } catch (e: Throwable) {
                getString(R.string.llamaserver_config_gpu_error, e.message ?: getString(R.string.llamaserver_config_error_desconocido))
            }
            activity?.runOnUiThread { if (isAdded) gpuStatusText.text = info }
        }.start()
    }

    private fun updateTemperatureLabel(temp: Float) {
        val warn = if (temp > 1.0f) getString(R.string.llamaserver_config_temperature_warn) else ""
        temperatureLabel.text = getString(R.string.llamaserver_config_temperature_format, temp, warn)
    }

    private fun updateContextSizeLabel(size: Int, safeMax: Int) {
        val warn = if (size > safeMax) getString(R.string.llamaserver_config_contexto_warn, safeMax) else ""
        contextSizeLabel.text = getString(R.string.llamaserver_config_contexto_format, size, warn)
    }

    private fun ramTierMaxContextSize(ctx: Context): Int {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val totalGb = info.totalMem / (1024.0 * 1024 * 1024)
            when {
                totalGb < 4 -> 2048
                totalGb < 8 -> 4096
                else -> 8192
            }
        } catch (_: Exception) {
            2048
        }
    }

    private fun paramField(container: LinearLayout, label: String): EditText {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val input = EditText(ctx).apply {
            textSize = 13f
            gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(dp(90), WRAP_CONTENT)
        }
        row.addView(input)
        container.addView(row)
        return input
    }
}
