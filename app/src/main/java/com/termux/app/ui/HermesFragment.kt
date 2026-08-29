package com.termux.app.ui

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.HermesNative
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.shellQuote
import com.termux.app.util.showProjectsMenu
import com.termux.app.util.kairosThemeColor

class HermesFragment : BaseModuleFragment() {
    override fun getModuleId() = "hermes"
    override fun getModuleName() = "Hermes"

    // Antes la card ESTADO quedaba con "—"/"detenido" fijos para siempre — HermesNative.info()
    // ya existía y ya se usaba en showStatus() (diálogo bajo demanda), pero nunca poblaba la
    // card visible sin tocar nada (mismo patrón "—" ya usado por EntornoFragment.refreshStatus()).
    private var versionValue: TextView? = null
    private var gatewayValue: TextView? = null
    private var providerValue: TextView? = null

    override fun buildContent() {
        // Instalación silenciosa en segundo plano (pedido 2026-08-13, ver humano101): si
        // Hermes no está instalado, se ofrece instalarlo internamente sin bloquear — el
        // usuario sigue navegando mientras corre.
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                installModuleInBackground(null) { ok ->
                    if (ok) {
                        toast(getString(R.string.hermes_toast_installed))
                        container.removeAllViews()
                        buildContent()
                    } else {
                        toast(getString(R.string.hermes_toast_install_failed))
                    }
                }
            }
            return
        }
        addCard(getString(R.string.hermes_card_estado)) {
            addView(infoRow(getString(R.string.hermes_label_version), getString(R.string.hermes_dash)).also { versionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.hermes_label_gateway), getString(R.string.hermes_dash)).also { gatewayValue = it.valueTextView() })
            addView(infoRow(getString(R.string.hermes_label_provider), getString(R.string.hermes_dash)).also { providerValue = it.valueTextView() })
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.hermes_label_terminal_tui)
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
        // "hermes --tui", NO "hermes" a secas: confirmado contra la referencia oficial
        // de comandos (NousResearch/hermes-agent, website/docs/reference/cli-commands.md)
        // \u2014 el binario sin argumentos abre el REPL cl\u00E1sico (prompt_toolkit), un modo
        // DISTINTO. La TUI real (bundle Node/Ink, "modern TUI (recommended)" en los docs
        // oficiales) solo se activa con el flag --tui (equivalente a HERMES_TUI=1). El
        // bot\u00F3n promete "(TUI)" en la etiqueta, as\u00ED que el comando ten\u00EDa que
        // pedirla expl\u00EDcitamente en vez de caer al REPL cl\u00E1sico por default.
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
        // correcci\u00F3n expl\u00EDcita del usuario \u2014 la ronda anterior dej\u00F3 Hermes afuera asumiendo que
        // "Gestionar proyectos" (showProjectsMenu con onLaunchInProject, m\u00E1s abajo) ya cubr\u00EDa el
        // caso; no cubre elegir una carpeta cualquiera del almacenamiento sin importarla antes.
        actionButton(getString(R.string.hermes_btn_open_tui), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand("hermes --tui") },
                onChooseFolder = { path -> launchTerminalCommand("cd '$path' && hermes --tui") }
            )
        }
        // "hermes chat": confirmado real y DISTINTO de "hermes --tui" \u2014 ambos est\u00E1n
        // documentados como comandos separados en termux-ai-stack-dev/doc/HERMES.md
        // (\u00A76.5 "Submenu Comandos" y \u00A79 "Comandos de Referencia R\u00E1pida") y en el
        // submen\u00FA ejecutable real de menu_nativo.sh (opciones [1] hermes vs [2] hermes chat
        // vs --tui). "hermes" a secas es el REPL cl\u00E1sico, "hermes --tui" es el bundle
        // Node/Ink (TUI moderna), y "hermes chat" es un modo de chat interactivo dedicado
        // \u2014 3 entry points distintos del mismo binario, no un duplicado.
        actionButton(getString(R.string.hermes_btn_chat), GHOST) {
            launchTerminalCommand("hermes chat")
        }
        actionButton(getString(R.string.hermes_btn_gateway), GHOST) {
            navigateTo(HermesGatewayFragment())
        }
        // Pedido 2026-08-13 (ver docs/humano/humano115.md): Hermes era el \u00FAnico CLI real sin
        // esta opci\u00F3n \u2014 los dem\u00E1s (Claude/Codex/OpenCode/Antigravity/OpenClaw/i-Haklab) ya la
        // tienen v\u00EDa este mismo helper (ver util/ProjectActions.kt).
        actionButton(getString(R.string.hermes_btn_manage_projects), GHOST) {
            showProjectsMenu(
                onToast = { toast(it) },
                onLaunchInProject = { path -> launchTerminalCommand("cd '$path' && hermes --tui") }
            )
        }
        actionButton(getString(R.string.hermes_btn_command_reference), GHOST) {
            showCommandReference()
        }
        // "hermes model": confirmado real en termux-ai-stack-dev/doc/HERMES.md \u00A76.5/\u00A79
        // ("hermes model \u2014 Wizard de proveedor/modelo") y en menu_nativo.sh l\u00EDnea ~5059
        // ("hermes model < /dev/tty" \u2014 "Selecciona el provider con las flechas y Enter").
        // Es un selector 100% interactivo por TTY (navegaci\u00F3n por flechas), NO tiene un
        // flag tipo --list/--json que devuelva los modelos en texto plano parseable \u2014 ni
        // el script de instalaci\u00F3n ni la doc oficial documentan ninguno, y no se inventa
        // uno ac\u00E1. Por eso "el selector interactivo REAL" es literalmente lanzar el propio
        // comando del CLI en la terminal (mismo patr\u00F3n que "Abrir Hermes (TUI)" de arriba),
        // en vez de intentar parsear un output que el binario no expone. showProviderDialog()
        // de abajo se mantiene aparte porque escribe directo a ~/.hermes/.env (AI_PROVIDER +
        // API key) sin pasar por el CLI \u2014 m\u00E1s r\u00E1pido para setear solo la key, pero sin la
        // validaci\u00F3n/formato que el wizard real del binario aplica al config.yaml.
        actionButton(getString(R.string.hermes_btn_choose_model), GHOST) {
            launchTerminalCommand("hermes model")
        }
        actionButton(getString(R.string.hermes_btn_configure_provider_quick), GHOST) {
            showProviderDialog()
        }
        // Antes eran 2 botones sueltos ("Usar Ollama local" / "Usar llama-server local") para
        // una sola decisi\u00F3n excluyente (qu\u00E9 proveedor de IA local usa Hermes) \u2014 no hay ning\u00FAn
        // encendido/apagado asociado (ambos flujos solo listan modelos y escriben la config),
        // as\u00ED que encaja en dropdownRow() y no en dropdownSwitchRow() (ver docs/humano/humano194.md/
        // humano195.md, BaseModuleFragment.dropdownRow()). El bot\u00F3n "Configurar" despacha al
        // flujo real (useOllamaLocal()/useLlamaServerLocal()) seg\u00FAn la opci\u00F3n elegida \u2014 cada
        // uno sigue abriendo su propio di\u00E1logo de selecci\u00F3n de modelo, sin cambios de l\u00F3gica.
        val localProviderRow = dropdownRow(getString(R.string.hermes_label_local_provider), listOf(getString(R.string.hermes_option_ollama_local), getString(R.string.hermes_option_llama_server_local))) { }
        container.addView(localProviderRow.root)
        actionButton(getString(R.string.hermes_btn_configure_local_provider), GHOST) {
            when (localProviderRow.selectedOptionIndex()) {
                0 -> useOllamaLocal()
                else -> useLlamaServerLocal()
            }
        }
        // Bug real (2026-08-07, ver docs/humano/humano91.md): "en hermes tampoco sale la
        // opcion de detener los servicios" \u2014 el bot\u00F3n real (HermesGatewayFragment) exist\u00EDa
        // pero estaba una pantalla m\u00E1s abajo (Gateway) que la principal \u2014 a diferencia de
        // TODOS los dem\u00E1s m\u00F3dulos con servicio persistente, que tienen "Detener" directo ac\u00E1.
        // "hermes send": confirmado real en termux-ai-stack-dev/doc/HERMES.md \u00A79
        // ("hermes send \"mensaje\" \u2014 Env\u00EDa a plataforma configurada", agrupado bajo
        // "Env\u00EDo directo (sin agente, sin LLM)") y en menu_nativo.sh \u00A7MENSAJER\u00CDA
        // (ejecuta literal `hermes send "$_MSG"`). IMPORTANTE \u2014 no es un prompt al agente:
        // env\u00EDa el mensaje tal cual al canal ya configurado en ~/.hermes/.env (Telegram/
        // Discord/SMS/Signal), sin pasar por el LLM. El equivalente real a "one-shot al
        // agente" ser\u00EDa `hermes -z "prompt"` (documentado aparte, no pedido ac\u00E1) \u2014 se deja
        // como nota para no confundir a futuro.
        actionButton(getString(R.string.hermes_btn_send_message), GHOST) {
            showSendMessageDialog()
        }
        // "hermes -z \"mensaje\"": confirmado real en termux-ai-stack-dev/doc/HERMES.md
        // \u00A79 ("hermes -z \"responde solo OK\" # One-shot no interactivo (test)") \u2014 a
        // diferencia de "hermes send" de arriba (env\u00EDa tal cual al canal, sin LLM), esto
        // S\u00CD pasa por el agente/LLM y devuelve la respuesta, sin abrir la TUI/REPL completo.
        // Ya estaba anotado como pendiente en el comentario de "hermes send" m\u00E1s arriba
        // ("se deja como nota para no confundir a futuro") \u2014 mismo hueco real que
        // OpenCodeFragment.runDirectPrompt() cubre para OpenCode.
        actionButton(getString(R.string.hermes_btn_direct_prompt), GHOST) {
            showDirectPromptDialog()
        }
        actionButton(getString(R.string.hermes_btn_stop_gateway), GHOST) {
            Thread {
                val json = HermesNative.gatewayStop()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_toast_gateway_stopped) else getString(R.string.hermes_toast_could_not_stop))
                    refreshStatus()
                }
            }.start()
        }
        actionButton(getString(R.string.hermes_btn_status_diagnostics), GHOST) {
            showStatus()
        }
        // "hermes setup": confirmado por cmd_hermes -> wizard-cmd, mismo caso que
        // el TUI arriba \u2014 invocaci\u00F3n directa del binario real, sin incertidumbre.
        actionButton(getString(R.string.hermes_btn_full_wizard), GHOST) {
            launchTerminalCommand("hermes setup")
        }
        // "hermes doctor": confirmado en submenu_hermes() de
        // termux-ai-stack-dev/scripts/menu_nativo.sh (opciones [3.6] y [6]) \u2014 corre
        // en la terminal en vez de por kairos_manager.py porque su salida es texto
        // libre de diagn\u00F3stico (no un JSON), no tiene sentido envolverlo en Python.
        actionButton(getString(R.string.hermes_btn_doctor), GHOST) {
            launchTerminalCommand("hermes doctor")
        }
        // "hermes kanban": confirmado en el mismo submenu (opci\u00F3n [3.9], "Tablero
        // tareas") \u2014 no ten\u00EDa ning\u00FAn bot\u00F3n equivalente en Kairos.
        actionButton(getString(R.string.hermes_btn_kanban), GHOST) {
            launchTerminalCommand("hermes kanban")
        }
        // "hermes cron" \u2014 confirmado real contra la referencia oficial de comandos
        // (NousResearch/hermes-agent, website/docs/reference/cli-commands.md, "Scheduled job
        // management"). Auditoria 2026-08-25: responde directo a una pregunta del usuario de
        // esta misma sesion ("\u00BFcon Cactus podemos ejecutar tareas tipo 'a las 2pm ejecuta
        // n8n'?" \u2014 Cactus NO tiene scheduler propio, ver docs/modulos/CACTUS.md) \u2014 Hermes S\u00CD
        // tiene uno real y documentado, y hoy no ten\u00EDa ning\u00FAn bot\u00F3n en Kairos.
        actionButton(getString(R.string.hermes_btn_cron), GHOST) {
            launchTerminalCommand("hermes cron")
        }
        // Antes este bot\u00F3n hac\u00EDa lo mismo que "Instalar / reinstalar" de abajo
        // (reinstall() completo v\u00EDa install_hermes.sh). En termux-ai-stack son DOS
        // acciones distintas (submenu_hermes, opciones [8] y [9]): [8] corre
        // `hermes update` (liviano, en el lugar) y solo cae a reinstalar si falla;
        // [9] s\u00ED reinstala desde cero. Se separan ac\u00E1 para que "Actualizar" use
        // el camino liviano real en vez de siempre reinstalar todo.
        actionButton(getString(R.string.hermes_btn_update), GHOST) {
            launchTerminalCommand("hermes update")
        }
        actionButton(getString(R.string.hermes_btn_install_reinstall), GHOST) { reinstall() }
        // Hermes no reconoce ning\u00fan slug MCP en Engram (ver BaseModuleFragment.
        // engramSetupButton()) ni tiene tool-calling custom como OpenClaw \u2014 pero s\u00ed tiene un
        // sistema de Skills real (ver HermesNative.installEngramSkill()) que el agente carga
        // solo cuando aplica y ejecuta con su propia tool de terminal.
        actionButton(getString(R.string.hermes_btn_connect_engram), GHOST) {
            connectEngramSkill()
        }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditor\u00eda de men\u00fas
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // Hermes no ten\u00eda forma de desinstalarse desde su propia pantalla. Bot\u00f3n suelto (no
        // addMaintenanceCard()) para no duplicar "Actualizar Hermes"/"Instalar / reinstalar"
        // de arriba, que ya cubren esos dos casos por separado.
        addCard(getString(R.string.hermes_card_maintenance)) {
            actionButton(getString(R.string.hermes_btn_uninstall), DANGER) { confirmUninstallModule() }
        }
        refreshStatus()
    }

    private fun connectEngramSkill() {
        if (!com.termux.app.util.isTermuxBinaryAvailable("engram")) {
            toast(getString(R.string.hermes_toast_engram_not_installed))
            return
        }
        Thread {
            val json = HermesNative.installEngramSkill()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_toast_engram_skill_installed)
                else getString(R.string.hermes_error_format, json.optString("error", getString(R.string.hermes_error_unknown))))
            }
        }.start()
    }

    private fun refreshStatus() {
        Thread {
            val json = HermesNative.info()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                versionValue?.text = json.optString("version", getString(R.string.hermes_dash)).ifBlank { getString(R.string.hermes_dash) }
                gatewayValue?.text = if (json.optBoolean("running")) getString(R.string.hermes_status_active) else getString(R.string.hermes_status_stopped)
                providerValue?.text = json.optString("provider", getString(R.string.hermes_dash)).ifBlank { getString(R.string.hermes_dash) }
            }
        }.start()
    }

    private fun showCommandReference() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.hermes_dialog_commands_title))
            .setMessage(getString(R.string.hermes_dialog_commands_body))
            .setPositiveButton(getString(R.string.hermes_btn_close), null)
            .show()
    }

    // "hermes send \"mensaje\"" \u2014 confirmado real (ver comentario del bot\u00F3n en buildContent()).
    // Env\u00EDa el texto tal cual a la plataforma de mensajer\u00EDa ya configurada en ~/.hermes/.env
    // (Telegram/Discord/SMS/Signal), sin pasar por el agente/LLM \u2014 mismo comportamiento que el
    // men\u00FA de referencia (menu_nativo.sh, opci\u00F3n [8] de submenu_hermes \u2192 "MENSAJER\u00CDA").
    private fun showSendMessageDialog() {
        val ctx = requireContext()
        val messageInput = EditText(ctx).apply {
            hint = getString(R.string.hermes_hint_send_message)
            isSingleLine = false
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(messageInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.hermes_dialog_send_message_title))
            .setMessage(getString(R.string.hermes_dialog_send_message_msg))
            .setView(layout)
            .setPositiveButton(getString(R.string.hermes_btn_send)) { _, _ ->
                val message = messageInput.text.toString().trim()
                if (message.isEmpty()) { toast(getString(R.string.hermes_toast_empty_message)); return@setPositiveButton }
                launchTerminalCommand("hermes send ${shellQuote(message)}")
            }
            .setNegativeButton(getString(R.string.hermes_btn_cancel), null)
            .show()
    }

    // "hermes -z \"mensaje\"" — confirmado real (ver comentario del botón en buildContent()).
    private fun showDirectPromptDialog() {
        val ctx = requireContext()
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.hermes_hint_direct_prompt)
            isSingleLine = false
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(promptInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.hermes_dialog_direct_prompt_title))
            .setMessage(getString(R.string.hermes_dialog_direct_prompt_msg))
            .setView(layout)
            .setPositiveButton(getString(R.string.hermes_btn_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.hermes_toast_empty_prompt)); return@setPositiveButton }
                launchTerminalCommand("hermes -z ${shellQuote(prompt)}")
            }
            .setNegativeButton(getString(R.string.hermes_btn_cancel), null)
            .show()
    }

    private fun showProviderDialog() {
        val ctx = requireContext()
        val providerInput = EditText(ctx).apply { hint = getString(R.string.hermes_hint_provider) }
        val keyInput = EditText(ctx).apply { hint = getString(R.string.hermes_hint_api_key) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(providerInput)
            addView(keyInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.hermes_dialog_configure_provider_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.hermes_btn_save)) { _, _ ->
                val provider = providerInput.text.toString().trim()
                val key = keyInput.text.toString().trim()
                if (provider.isEmpty() || key.isEmpty()) { toast(getString(R.string.hermes_toast_missing_data)); return@setPositiveButton }
                Thread {
                    val json = HermesNative.configSetProvider(provider, key)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_toast_provider_saved) else getString(R.string.hermes_error_format, json.optString("error")))
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.hermes_btn_cancel), null)
            .show()
    }

    // Bug real (2026-08-07, ver docs/humano/humano91.md): antes era un EditText de texto
    // libre ("Modelo (ej. qwen2.5:1.5b)") sin ninguna lista real — el usuario tenía que
    // saber de memoria el tag exacto de un modelo ya descargado. Ahora lista los modelos
    // REALES vía HermesNative.ollamaModels() (mismo cliente HTTP que Ollama/OpenCode), y
    // escribe la config con el mecanismo real (HermesNative.configSetLocalProvider(), CLI
    // "hermes config set" — ver el comentario de esa función).
    private fun useOllamaLocal() {
        Thread {
            val json = HermesNative.ollamaModels()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.hermes_error_format, json.optString("error", getString(R.string.hermes_toast_ollama_unavailable))))
                    return@runOnUiThread
                }
                val models = json.optJSONArray("models")
                if (models == null || models.length() == 0) {
                    toast(getString(R.string.hermes_toast_no_ollama_models))
                    return@runOnUiThread
                }
                val names = (0 until models.length()).map { models.getJSONObject(it).optString("name") }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.hermes_dialog_ollama_model_title))
                    .setItems(names.toTypedArray()) { _, which ->
                        setLocalProvider("http://127.0.0.1:11434/v1", names[which])
                    }
                    .setNegativeButton(getString(R.string.hermes_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    // Mismo patrón que configureLlamaServer() de OpenCodeFragment.kt — llama-server sirve UN
    // solo modelo a la vez (el elegido al iniciarlo, ver LlamaServerFragment), sin endpoint
    // tipo /api/tags, así que se lista directo de LocalModelManager (los .gguf ya
    // descargados) en vez de preguntarle al servidor.
    private fun useLlamaServerLocal() {
        val models = com.termux.app.util.LocalModelManager.listModels(requireContext())
        if (models.isEmpty()) {
            toast(getString(R.string.hermes_toast_no_gguf_models))
            return
        }
        val names = models.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.hermes_dialog_llama_server_model_title))
            .setItems(names) { _, which ->
                setLocalProvider("http://127.0.0.1:8085/v1", names[which])
            }
            .setNegativeButton(getString(R.string.hermes_btn_cancel), null)
            .show()
    }

    private fun setLocalProvider(baseUrl: String, model: String) {
        Thread {
            val json = HermesNative.configSetLocalProvider(baseUrl, model)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                toast(if (json.optBoolean("ok", false)) getString(R.string.hermes_toast_configured_format, model) else getString(R.string.hermes_error_format, json.optString("error")))
                refreshStatus()
            }
        }.start()
    }

    private fun showStatus() {
        Thread {
            val json = HermesNative.info()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.hermes_error_format, json.optString("error"))); return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.hermes_dialog_status_title))
                    .setMessage(
                        getString(
                            R.string.hermes_dialog_status_body,
                            json.optBoolean("installed"),
                            json.optString("version", getString(R.string.hermes_dash)),
                            json.optBoolean("running"),
                            json.optBoolean("has_config"),
                            json.optString("provider", getString(R.string.hermes_dash))
                        )
                    )
                    .setPositiveButton(getString(R.string.hermes_btn_close), null)
                    .show()
            }
        }.start()
    }

    private fun reinstall() {
        toast(getString(R.string.hermes_toast_reinstalling))
        reinstallModuleService { ok ->
            toast(if (ok) getString(R.string.hermes_toast_updated) else getString(R.string.hermes_toast_install_failed))
        }
    }
}
