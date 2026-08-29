package com.termux.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.OllamaApiClient
import com.termux.app.util.kairosThemeColor

/**
 * Pantalla principal de Ollama — rediseño visual real (2026-08-23, corrección explícita del
 * usuario: "quedo horrible todo [...] hicite copia y paste no organizaste bien las cosas" tras
 * una primera pasada que solo reordenaba los mismos botones planos de siempre. Esta versión usa
 * los componentes nuevos de `BaseModuleFragment` (`compactStatusRow()`, `modelRow()`,
 * `showFab()`) en vez de cards de texto plano, y recorta el texto explicativo largo — la
 * pantalla es para escanear de un vistazo, no para leer. "No es quitar opciones es
 * reorganizarlas": TODA la configuración/mantenimiento (Reiniciar/Detalle del proceso/Info GPU/
 * Actualizar/Desinstalar) vive ahora en `OllamaConfigFragment` — un solo ícono "⚙" acá arriba,
 * nada repartido en un menú de la pantalla principal.
 */
class OllamaFragment : BaseModuleFragment() {
    override fun getModuleId() = "ollama"
    override fun getModuleName() = getString(R.string.ollama_title)

    private var modelsListCard: LinearLayout? = null
    private lateinit var runSwitch: androidx.appcompat.widget.SwitchCompat
    private var cpuFallbackBanner: View? = null

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        // Estado compacto: 1 fila (punto + puerto/versión) + switch de arranque como trailing.
        // "⚙" al lado navega a la ÚNICA pantalla de configuración/mantenimiento — nada más acá.
        addCard {
            val version = com.termux.app.data.ModuleRegistry(requireContext()).load().get("ollama.version")
            addView(compactStatusRow(
                statusText = ":11434" + (version?.takeIf { it.isNotBlank() }?.let { " · v$it" } ?: ""),
                isActive = false,
            ) {
                addView(TextView(requireContext()).apply {
                    text = "⚙"
                    textSize = 17f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(6), dp(4), dp(10), dp(4))
                    setOnClickListener { navigateTo(OllamaConfigFragment()) }
                })
                runSwitch = androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                    thumbTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_thumb_color)
                    trackTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.switch_track_color)
                    setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                }
                addView(runSwitch)
            }.also { runStatusRow = it })
        }
        refreshRunningState()
        checkCpuFallback()

        addCard(getString(R.string.ollama_card_modelos)) {
            modelsListCard = this
            addView(modelInfoLoadingRow())
        }
        actionButton(getString(R.string.ollama_btn_descargar_modelo), GHOST) { navigateTo(ModelsFragment()) }

        showFab("💬") { navigateTo(ChatFragment()) }

        refreshModelsInfo()
    }

    private lateinit var runStatusRow: View

    private fun onToggle(on: Boolean) {
        if (on) {
            toast(getString(R.string.ollama_toast_iniciando))
            startModuleServiceWithPolling(onPoll = { refreshRunningState() }) { ok, _ ->
                toast(if (ok) getString(R.string.ollama_toast_iniciado_ok) else getString(R.string.ollama_toast_iniciado_fail))
                refreshRunningState()
                if (ok) checkCpuFallback()
            }
        } else {
            toast(getString(R.string.ollama_toast_deteniendo))
            stopModuleService { ok -> toast(if (ok) getString(R.string.ollama_toast_detenido_ok) else getString(R.string.ollama_toast_detenido_fail)) }
        }
    }

    private fun modelInfoLoadingRow(): TextView = TextView(requireContext()).apply {
        text = getString(R.string.ollama_cargando)
        textSize = 12f
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun refreshRunningState() {
        Thread {
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                runSwitch.setOnCheckedChangeListener(null)
                runSwitch.isChecked = running
                runSwitch.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                updateStatusDot(running)
            }
        }.start()
    }

    /** El punto de `compactStatusRow()` se pinta una sola vez al construirla — acá se
     * recolorea directo (primer hijo de la fila) cuando cambia el estado real. */
    private fun updateStatusDot(active: Boolean) {
        if (!::runStatusRow.isInitialized) return
        val dot = (runStatusRow as? LinearLayout)?.getChildAt(0) ?: return
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(
                if (active) requireContext().kairosThemeColor(R.attr.kairosGreen)
                else requireContext().kairosThemeColor(R.attr.kairosText3)
            )
        }
        dot.background = bg
    }

    private fun checkCpuFallback() {
        Thread {
            val installMode = com.termux.app.data.ModuleRegistry(requireContext()).load().get("ollama.install_mode")
            val isCpuFallback = installMode == "termux_npm" && try {
                java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".ollama_backend_status").readText().trim() == "cpu"
            } catch (_: Exception) {
                false
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                renderCpuFallbackBanner(isCpuFallback)
            }
        }.start()
    }

    // Banner recortado (2026-08-23, "quitar mucho de ese texto que son comentarios o ayuda") —
    // antes era una oración larga explicando la causa; queda solo la alerta + dónde ver más.
    private fun renderCpuFallbackBanner(show: Boolean) {
        cpuFallbackBanner?.let { container.removeView(it) }
        cpuFallbackBanner = null
        if (!show) return
        val banner = TextView(requireContext()).apply {
            text = getString(R.string.ollama_cpu_fallback_banner)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosAmber))
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val anchorIndex = if (::runStatusRow.isInitialized) container.indexOfChild(runStatusRow.parent as? View ?: runStatusRow) else -1
        if (anchorIndex >= 0) container.addView(banner, anchorIndex + 1) else container.addView(banner, 1)
        cpuFallbackBanner = banner
    }

    private fun refreshModelsInfo() {
        Thread {
            val running = try { OllamaApiClient.psModels() to null } catch (e: Exception) { null to (e.message ?: "desconocido") }
            val downloaded = try { OllamaApiClient.listModels() to null } catch (e: Exception) { null to (e.message ?: "desconocido") }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                renderModels(running.first, running.second, downloaded.first, downloaded.second)
            }
        }.start()
    }

    /** Lista de modelos con `modelRow()` — ícono según familia, subtítulo con tamaño, el
     * modelo activo con "Liberar" como trailing. Reemplaza las 2 cards de texto plano de
     * antes ("MODELO ACTIVO"/"MODELOS DESCARGADOS"). */
    private fun renderModels(
        running: List<OllamaApiClient.RunningModel>?,
        runningError: String?,
        downloaded: List<OllamaApiClient.ModelSummary>?,
        downloadedError: String?,
    ) {
        val card = modelsListCard ?: return
        card.removeAllViews()

        if (running == null && downloaded == null) {
            card.addView(modelInfoTextRow(getString(R.string.ollama_no_responde)))
            return
        }

        val runningNames = running?.map { it.name }?.toSet() ?: emptySet()
        val greenBg = android.graphics.Color.argb(30, 34, 197, 94)

        if (running != null) {
            for (m in running) {
                card.addView(modelRow(
                    icon = "🟢",
                    iconBg = greenBg,
                    name = m.name,
                    subtitle = getString(R.string.ollama_model_en_memoria, m.sizeVramHuman),
                    trailing = {
                        addView(TextView(requireContext()).apply {
                            text = getString(R.string.ollama_btn_liberar)
                            textSize = 11.5f
                            setTextColor(requireContext().kairosThemeColor(R.attr.kairosBlue))
                            setPadding(dp(8), dp(4), dp(2), dp(4))
                            setOnClickListener { unloadModel(m.name) }
                        })
                    },
                ))
            }
        }

        if (downloaded == null) return
        val restDownloaded = downloaded.filter { it.name !in runningNames }
        if (restDownloaded.isEmpty() && running.isNullOrEmpty()) {
            card.addView(modelInfoTextRow(getString(R.string.ollama_no_hay_modelos_descargados)))
            return
        }
        for (m in restDownloaded) {
            card.addView(modelRow(
                icon = "💬",
                name = m.name,
                subtitle = if (m.family.isNotEmpty()) "${m.sizeHuman} · ${m.family}" else m.sizeHuman,
            ))
        }
    }

    private fun unloadModel(name: String) {
        toast(getString(R.string.ollama_toast_liberando, name))
        Thread {
            val error = try { OllamaApiClient.unloadModel(name); null } catch (e: Exception) { e.message ?: getString(R.string.ollama_desconocido) }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (error == null) getString(R.string.ollama_toast_liberado, name) else getString(R.string.ollama_toast_error_generico, error))
                if (error == null) refreshModelsInfo()
            }
        }.start()
    }

    private fun modelInfoTextRow(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
}
