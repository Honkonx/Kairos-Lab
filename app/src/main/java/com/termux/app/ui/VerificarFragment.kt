package com.termux.app.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * Fragment dedicado del módulo `verificar` (2026-08-17) — antes caía en GenericModuleFragment
 * (solo "Abrir en terminal"), pese a que modulos/verificar.sh ya hace exactamente el tipo de
 * verificación en vivo que otros módulos hacen a medias: lee el registry
 * (~/.android_server_registry) y confirma CADA módulo declarado `installed=true` contra el
 * filesystem/binario real con 6 estrategias (cmd/pkg/dir/file/plugin/config — ver el script
 * para el detalle de cada una).
 *
 * Esta ronda le agregó un flag `--json` real a verificar.sh (antes solo imprimía texto con
 * prefijos `[OK]`/`[WARN]`/`[SKIP]`, pensado para consola) — el script ya tenía toda la
 * información estructurada internamente (id, estrategia, blanco, resultado), así que exponerla
 * como un único objeto JSON en stdout evita tener que parsear texto libre acá:
 *   {"total":N,"ok":N,"warn":N,"skip":N,
 *    "modules":[{"id":"ollama","status":"ok","strategy":"cmd","target":"ollama"}, ...]}
 * `--json` implica `--silent` internamente (mismo modo texto plano, sin colores/banner) — no
 * hace falta pasar ambos flags.
 *
 * Es una herramienta de DIAGNÓSTICO TRANSVERSAL — complementa, no reemplaza, el flujo de
 * instalar/actualizar de cada módulo individual.
 */
class VerificarFragment : BaseModuleFragment() {

    override fun getModuleId() = "verificar"
    override fun getModuleName() = getString(R.string.verificar_module_name)

    private val scriptPath: String
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/verificar.sh").absolutePath

    private lateinit var resultsContainer: LinearLayout
    private lateinit var runButton: TextView

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) { installModuleInBackground(null) {} }
            return
        }

        addCard(getString(R.string.verificar_diagnostico_card_title)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.verificar_diagnostico_description)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            addView(createActionButton(getString(R.string.verificar_run_button), PRIMARY) {
                runVerification()
            }.also { runButton = it as TextView })
        }

        addCard(getString(R.string.verificar_results_card_title)) {
            resultsContainer = this
            addView(placeholderRow(getString(R.string.verificar_results_placeholder)))
        }

        addCard(getString(R.string.verificar_maintenance_card_title)) {
            actionButton(getString(R.string.verificar_update_button), GHOST) {
                toast(getString(R.string.verificar_updating_toast, getModuleName()))
                updateModuleService { ok ->
                    toast(if (ok) getString(R.string.verificar_updated_toast, getModuleName()) else getString(R.string.verificar_update_failed_toast))
                }
            }
            actionButton(getString(R.string.verificar_uninstall_button), DANGER) {
                com.termux.app.ModuleController.uninstallModule(getModuleId()) { ok ->
                    if (!isAdded) return@uninstallModule
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (ok) {
                            toast(getString(R.string.verificar_uninstalled_toast, getModuleName()))
                            parentFragmentManager.popBackStack()
                        } else toast(getString(R.string.verificar_uninstall_failed_toast, getModuleName()))
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Corrida real: `bash verificar.sh --all --json` en background
    // ────────────────────────────────────────────────────────────

    private data class ModuleResult(
        val id: String,
        val status: String,
        val strategy: String,
        val target: String,
    )

    private data class VerifyOutcome(
        val total: Int,
        val ok: Int,
        val warn: Int,
        val skip: Int,
        val modules: List<ModuleResult>,
    )

    private fun runVerification() {
        if (!isAdded) return
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.verificar_progress_title), getString(R.string.verificar_progress_message))
        runButton.isEnabled = false
        Thread {
            val outcome = try {
                verifyAll()
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                runButton.isEnabled = true
                if (outcome == null) {
                    progress.failure(getString(R.string.verificar_progress_failure))
                    renderResults(emptyList(), null)
                } else {
                    progress.success(
                        getString(R.string.verificar_progress_success, outcome.ok, outcome.warn, outcome.skip),
                        detail = getString(R.string.verificar_progress_success_detail, outcome.total)
                    )
                    renderResults(outcome.modules, outcome)
                }
            }
        }.start()
    }

    /** Corre `bash verificar.sh --all --json` y parsea el único objeto JSON de stdout. El
     * script puede terminar con exit code 1 cuando hay algún WARN (comportamiento intencional
     * de verificar.sh, ver su cabecera) — eso NO es un fallo de esta corrida, así que no se usa
     * el exit code para decidir éxito/fracaso, solo si stdout trae JSON parseable. */
    private fun verifyAll(): VerifyOutcome {
        val (_, stdout, stderr) = ManagerNativeUtils.runShell(
            "bash \"$scriptPath\" --all --json",
            timeoutSeconds = 40
        )
        if (stdout.isBlank()) throw IllegalStateException(stderr.ifBlank { "sin salida" })
        val root = JSONObject(stdout.trim())
        val modulesJson: JSONArray = root.optJSONArray("modules") ?: JSONArray()
        val modules = (0 until modulesJson.length()).map { i ->
            val m = modulesJson.getJSONObject(i)
            ModuleResult(
                id = m.optString("id"),
                status = m.optString("status"),
                strategy = m.optString("strategy"),
                target = m.optString("target"),
            )
        }
        return VerifyOutcome(
            total = root.optInt("total", modules.size),
            ok = root.optInt("ok", 0),
            warn = root.optInt("warn", 0),
            skip = root.optInt("skip", 0),
            modules = modules,
        )
    }

    private fun renderResults(modules: List<ModuleResult>, outcome: VerifyOutcome?) {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()
        if (modules.isEmpty()) {
            resultsContainer.addView(placeholderRow(
                if (outcome == null) getString(R.string.verificar_placeholder_failed)
                else getString(R.string.verificar_placeholder_empty)
            ))
            return
        }
        // Orden: primero los que fallan (WARN, lo más útil de ver arriba), después SKIP, OK al final.
        val ordered = modules.sortedBy { statusOrder(it.status) }
        for ((i, m) in ordered.withIndex()) {
            resultsContainer.addView(buildResultRow(m))
            if (i < ordered.size - 1) divider()
        }
    }

    private fun statusOrder(status: String): Int = when (status) {
        "warn" -> 0
        "skip" -> 1
        else -> 2
    }

    private fun buildResultRow(m: ModuleResult): LinearLayout {
        val ctx = requireContext()
        val (icon, label, color) = when (m.status) {
            "ok" -> Triple("✅", getString(R.string.verificar_status_ok_label), R.attr.kairosGreen)
            "warn" -> Triple("⚠️", getString(R.string.verificar_status_warn_label), R.attr.kairosAmber)
            // Bug real reportado por el usuario ("verificar funciona a medias"): "SKIP" significa
            // "el registry dice installed=true pero este módulo no tiene una estrategia de
            // verificación definida en verificar.sh" — NO significa que el módulo no esté
            // instalado (de hecho, el registry ya confirma que sí lo está, por eso aparece en la
            // lista). El texto anterior ("No instalado / sin estrategia definida") decía lo
            // contrario de lo que el propio dato implica.
            else -> Triple("○", getString(R.string.verificar_status_skip_label), R.attr.kairosText3)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(ctx).apply {
                    text = "$icon  ${m.id}"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(color))
                setPadding(0, dp(2), 0, 0)
            })
            if (m.strategy.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = getString(R.string.verificar_strategy_line, m.strategy, m.target)
                    textSize = 11f
                    setTypeface(android.graphics.Typeface.MONOSPACE)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }
    }

    private fun placeholderRow(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }
}
