package com.termux.app.ui

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.HermesNative

class HermesGatewayFragment : BaseModuleFragment() {
    override fun getModuleId() = "hermes"
    override fun getModuleName() = "Gateway"

    // Mismo gap real que HermesFragment (ver comentario ahí): la card quedaba fija en
    // "detenido" para siempre en vez de reflejar HermesNative.info().running.
    private var estadoValue: TextView? = null
    private lateinit var gatewaySwitch: SwitchRow

    override fun buildContent() {
        // Auditoría 2026-07-27: única pantalla de detalle de módulo sin este gate — sin
        // él, mostraba Iniciar/Detener/etc. igual aunque Hermes no estuviera instalado.
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.hermes_gateway_card_estado)) {
            addView(infoRow(getString(R.string.hermes_gateway_label_estado), getString(R.string.hermes_gateway_dash)).also { estadoValue = it.valueTextView() })
        }
        // Switch real (2026-08-22, ver docs/humano/humano193.md) — reemplaza los botones separados
        // "Iniciar"/"Detener". HermesNative.gatewayStart() corre
        // `tmux new-session -d -s hermes-gw "hermes gateway"` — confirmado contra la
        // referencia oficial de comandos (NousResearch/hermes-agent,
        // website/docs/reference/cli-commands.md): "hermes gateway — Run or manage
        // the messaging gateway service" es un subcomando real y documentado, no
        // adivinado. Pendiente real: Termux es "Tier 2, best-effort" en la doc oficial
        // (ver docs/viejo/BUGS_PERSISTENTES_2026-07-26.md §Hermes) — el gateway en sí no
        // tiene issues de Android reportados en la investigación de esta sesión, a
        // diferencia de la TUI (ver HermesFragment.kt), pero no se probó en dispositivo.
        gatewaySwitch = switchRow(getString(R.string.hermes_gateway_label_gateway)) { on ->
            if (on) {
                toast(getString(R.string.hermes_gateway_toast_starting))
                Thread {
                    val json = HermesNative.gatewayStart()
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_gateway_toast_started) else getString(R.string.hermes_gateway_error_format, json.optString("error", json.optString("message"))))
                        refreshStatus()
                    }
                }.start()
            } else {
                Thread {
                    val json = HermesNative.gatewayStop()
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_gateway_toast_stopped) else getString(R.string.hermes_gateway_error_format, json.optString("error")))
                        refreshStatus()
                    }
                }.start()
            }
        }
        container.addView(gatewaySwitch.root)
        actionButton(getString(R.string.hermes_gateway_btn_view_status), GHOST) {
            Thread {
                val json = HermesNative.info()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!json.optBoolean("ok", false)) {
                        toast(getString(R.string.hermes_gateway_error_format, json.optString("error"))); return@runOnUiThread
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.hermes_gateway_dialog_status_title))
                        .setMessage(
                            getString(
                                R.string.hermes_gateway_dialog_status_body,
                                json.optBoolean("running"),
                                json.optString("provider", getString(R.string.hermes_gateway_dash)),
                                json.optBoolean("has_config")
                            )
                        )
                        .setPositiveButton(getString(R.string.hermes_gateway_btn_close), null)
                        .show()
                }
            }.start()
        }
        // Sin ruta de log estático conocida para el gateway de Hermes — se
        // conecta a la sesión tmux en vivo (mismo patrón que n8n usa para
        // "Ver logs" cuando no hay un archivo fijo, ver N8nFragment.kt).
        actionButton(getString(R.string.hermes_gateway_btn_view_logs), GHOST) {
            launchTerminalCommand("tmux attach -t hermes-gw", getString(R.string.hermes_gateway_terminal_title))
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        Thread {
            val json = HermesNative.info()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val running = json.optBoolean("running")
                estadoValue?.text = if (running) getString(R.string.hermes_gateway_status_active) else getString(R.string.hermes_gateway_status_stopped)
                gatewaySwitch.setSwitchState(running)
            }
        }.start()
    }
}
