package com.termux.app.ui

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.TextView
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.OpenClawNative
import com.termux.app.util.ProjectsManager
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.runProjectsAction
import com.termux.app.util.showProjectsMenu
import com.termux.shared.termux.TermuxConstants
import java.io.File
import com.termux.app.util.kairosThemeColor

class OpenClawFragment : BaseModuleFragment() {
    override fun getModuleId() = "openclaw"
    override fun getModuleName() = "OpenClaw"

    // OpenClaw usa su propio "workspace" ($HOME/.openclaw/workspace), NO la carpeta
    // ~/proyectos compartida de Claude/OpenCode/Codex/Antigravity — confirmado en el
    // kairos_manager.py legado (OPENCLAW_WORKSPACE + prefijo de registry
    // "openclaw_workspace", cmd_openclaw's alias workspace-*→projects-*). ProjectsManager
    // ya acepta baseDir/regPrefix como parámetros para exactamente este caso, no hizo
    // falta tocarlo.
    private val workspaceDir: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".openclaw/workspace")
    private val workspaceRegPrefix = "openclaw_workspace"

    private lateinit var gatewaySwitch: SwitchRow
    private lateinit var tokenValue: TextView

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.openclaw_card_estado)) {
            addView(infoRow(getString(R.string.openclaw_label_variante), getString(R.string.openclaw_value_native_glibc)))
            // openclaw.sh escribe openclaw.version al registry en cada instalación (sin
            // mismatch de prefijo, a diferencia del bug real que sí tenía Claude Code — ver
            // ronda 41) — este valor estaba hardcodeado en "—" sin leerlo nunca, mismo bug de
            // los demás CLI ya corregido esa misma ronda, señalado sin arreglar por el agente
            // que agregó la sección de proyectos acá (fuera de su alcance en ese momento).
            val openclawVersion = com.termux.app.data.ModuleRegistry(requireContext()).load().get("openclaw.version")
            addView(infoRow(getString(R.string.openclaw_label_version), openclawVersion?.ifBlank { getString(R.string.openclaw_dash) } ?: getString(R.string.openclaw_dash)))
            // Bug real (2026-08-07, ver docs/humano/humano88.md): "Gateway"/"Token"/"Modelo
            // activo" estaban hardcodeados fijos ("detenido", "sk-***...", "qwen2.5:0.5b")
            // sin leer nada real — el usuario veía "detenido" incluso con el gateway
            // corriendo, un token falso con forma de API key (el token real de OpenClaw es
            // el auth token del propio gateway, no una API key de proveedor), y un modelo
            // local que ni siquiera aplica si el usuario configuró un proveedor remoto
            // (Anthropic/OpenAI/etc). Gateway y Token ahora se leen de verdad en loadInfo();
            // "Modelo activo" se quitó — no hay un único campo confiable de "modelo activo"
            // en el config real (depende del proveedor elegido en onboarding), mostrar algo
            // inventado sería peor que no mostrar nada.
            // Switch real (2026-08-22, ver docs/humano/humano193.md) — reemplaza la pill de solo
            // lectura + los botones separados "Iniciar gateway"/"Detener" de más abajo.
            gatewaySwitch = switchRow(getString(R.string.openclaw_label_gateway)) { on ->
                if (on) startGatewayGuarded() else stopModuleService { ok ->
                    toast(if (ok) getString(R.string.openclaw_toast_gateway_stopped) else getString(R.string.openclaw_toast_could_not_stop))
                    loadInfo()
                }
            }
            addView(gatewaySwitch.root)
            val tokenRow = valueRow(getString(R.string.openclaw_label_token), getString(R.string.openclaw_dash))
            tokenValue = tokenRow.second
            addView(tokenRow.first)
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.openclaw_label_terminal_tui)
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
        actionButton(getString(R.string.openclaw_btn_restart_gateway), GHOST) {
            toast(getString(R.string.openclaw_toast_restarting))
            stopModuleService {
                startGatewayGuarded(getString(R.string.openclaw_toast_gateway_restarted), getString(R.string.openclaw_toast_could_not_restart))
            }
        }
        actionButton(getString(R.string.openclaw_btn_view_logs), GHOST) {
            navigateTo(LogsFragment.newInstance("${com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH}/openclaw-logs/runtime.log"))
        }
        actionButton(getString(R.string.openclaw_btn_show_url_token), GHOST) {
            showUrlWithToken()
        }
        actionButton(getString(R.string.openclaw_btn_open_web), GHOST) {
            openWebServer()
        }
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
        // correcci\u00f3n expl\u00edcita del usuario. OpenClaw usa su propio workspace por defecto
        // ($HOME/.openclaw/workspace, ver workspaceDir arriba) pero "Elegir carpeta\u2026" sigue
        // teniendo sentido: el usuario puede querer correr la TUI sobre CUALQUIER carpeta del
        // almacenamiento, no solo sobre el workspace registrado \u2014 mismo criterio que el resto
        // de los CLIs, se incluye en vez de excluirlo.
        actionButton(getString(R.string.openclaw_btn_open_tui), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand("openclaw tui") },
                onChooseFolder = { path -> launchTerminalCommand("cd '$path' && openclaw tui") }
            )
        }
        actionButton(getString(R.string.openclaw_btn_onboarding), GHOST) {
            launchTerminalCommand("openclaw onboard")
        }
        actionButton(getString(R.string.openclaw_btn_provider_model), GHOST) {
            showProvidersMenu()
        }
        // Editor real de canales (channels.<provider> en ~/.openclaw/openclaw.json) \u2014 antes
        // no hab\u00EDa ning\u00FAn panel para esto (ver docs/modulos/OPENCLAW.md secci\u00F3n 8, ronda
        // 2026-08-25: "No implementado todav\u00EDa"). Discord/Telegram/WhatsApp/Slack confirmados
        // reales contra docs.openclaw.ai/gateway/config-channels \u2014 SMS no es un canal real,
        // no se agrega.
        actionButton(getString(R.string.openclaw_btn_configure_channels), GHOST) {
            showChannelsMenu()
        }
        // Nunca hab\u00EDa opci\u00F3n para importar/sincronizar/symlink workspaces ac\u00E1 \u2014 pedido
        // expl\u00EDcito del usuario (2026-08-01, ver docs/humano/humano42.md): "las opciones de
        // proyectos... toca ponerlos en todo los cli". Mismo patr\u00F3n de UI que
        // AntigravityFragment.manageProjects(), pero sobre workspaceDir (no ~/proyectos).
        actionButton(getString(R.string.openclaw_btn_manage_workspaces), GHOST) {
            showProjectsMenu(onToast = { toast(it) }, baseDir = workspaceDir, regPrefix = workspaceRegPrefix)
        }
        actionButton(getString(R.string.openclaw_btn_open_workspace_tui), GHOST) {
            openInProject()
        }
        actionButton(getString(R.string.openclaw_btn_install_update), GHOST) {
            reinstall()
        }
        // Engram no reconoce "openclaw" como agente en `engram setup <agent>` (ver
        // BaseModuleFragment.engramSetupButton()), pero OpenClaw S\u00cd es un cliente MCP real
        // y documentado (docs.openclaw.ai/cli/mcp) \u2014 se conecta con el comando gen\u00e9rico
        // `openclaw mcp add`, ver OpenClawNative.mcpConnectEngram().
        actionButton(getString(R.string.openclaw_btn_connect_engram), GHOST) {
            connectEngramMcp()
        }
        loadInfo()
    }

    private fun connectEngramMcp() {
        if (!com.termux.app.util.isTermuxBinaryAvailable("engram")) {
            toast(getString(R.string.openclaw_toast_engram_not_installed))
            return
        }
        toast(getString(R.string.openclaw_toast_connecting_engram))
        Thread {
            val json = OpenClawNative.mcpConnectEngram()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_unknown))))
                    return@runOnUiThread
                }
                if (json.optBoolean("gateway_running", false)) {
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.openclaw_dialog_engram_connected_title))
                        .setMessage(getString(R.string.openclaw_dialog_engram_connected_msg))
                        .setPositiveButton(getString(R.string.openclaw_btn_restart_now)) { _, _ ->
                            toast(getString(R.string.openclaw_toast_restarting))
                            stopModuleService {
                                startGatewayGuarded(getString(R.string.openclaw_toast_gateway_restarted_engram), getString(R.string.openclaw_toast_could_not_restart))
                            }
                        }
                        .setNegativeButton(getString(R.string.openclaw_btn_later), null)
                        .show()
                } else {
                    toast(getString(R.string.openclaw_toast_engram_connected))
                }
            }
        }.start()
    }

    /**
     * Reemplazo directo de los 3 campos hardcodeados que ten\u00eda el card ESTADO \u2014 mismo
     * patr\u00f3n que N8nFragment.loadInfo()/OpenCodeFragment.loadInfo(). Gateway usa
     * isModuleRunning() (mismo camino que el resto de la app, v\u00eda ModuleController), Token
     * lee el token real del gateway (OpenClawNative.gatewayUrl(), mismo parseo que "Mostrar
     * URL con token") en vez de la API key falsa que hab\u00eda antes.
     */
    private fun loadInfo() {
        Thread {
            val running = isModuleRunning()
            val urlJson = OpenClawNative.gatewayUrl()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                gatewaySwitch.setSwitchState(running)
                val token = urlJson.optString("token", "")
                if (token.isNotEmpty()) {
                    tokenValue.text = getString(R.string.openclaw_token_masked_format, token.take(6), token.takeLast(4))
                    tokenValue.setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                } else {
                    // No es un bug (auditor\u00eda 2026-08-12, ver docs/humano/humano99.md): el
                    // token solo se genera al correr "openclaw onboard" (wizard interactivo,
                    // nunca automatizado por el instalador silencioso a prop\u00f3sito). Se resalta
                    // en \u00e1mbar en vez de texto neutro para que se note que hace falta una
                    // acci\u00f3n, no que algo se rompi\u00f3.
                    tokenValue.text = getString(R.string.openclaw_token_missing)
                    tokenValue.setTextColor(requireContext().kairosThemeColor(R.attr.kairosAmber))
                }
            }
        }.start()
    }

    // Causa ra\u00edz real corregida 2026-08-25 (ver docs/modulos/OPENCLAW.md secci\u00f3n 12): el
    // bloqueo anterior (esperar "openclaw onboard" completo, ver OpenClawNative.isOnboarded())
    // era m\u00e1s estricto de lo que "openclaw gateway" necesita de verdad \u2014 el binario real
    // solo exige "gateway.mode"=="local" en el config para arrancar y auto-generar su propio
    // token; elegir proveedor de IA es un paso posterior y separable. OpenClawNative.gatewayStart()
    // ahora pre-siembra ese campo solo (ensureGatewayModeLocal()) antes de arrancar, as\u00ed que
    // ac\u00e1 ya no hace falta bloquear ni preguntar \u2014 se intenta arrancar directo. El bot\u00f3n
    // "\u2699 Onboarding" sigue disponible en la pantalla para configurar un proveedor real cuando
    // el usuario quiera, sin ser un prerequisito del gateway/token.
    private fun startGatewayGuarded(
        okMessage: String = getString(R.string.openclaw_toast_gateway_started),
        failMessage: String = getString(R.string.openclaw_toast_could_not_start)
    ) {
        toast(getString(R.string.openclaw_toast_starting_gateway))
        startModuleService { ok, _ ->
            toast(if (ok) okMessage else failMessage)
            loadInfo()
        }
    }

    private fun openInProject() {
        runProjectsAction({ ProjectsManager.projectsList(workspaceDir, workspaceRegPrefix) }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_unknown))))
                return@runProjectsAction
            }
            val projects = json.optJSONArray("projects")
            if (projects == null || projects.length() == 0) {
                toast(getString(R.string.openclaw_toast_no_workspaces))
                return@runProjectsAction
            }
            val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
            val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.openclaw_dialog_open_workspace_title))
                .setItems(names.toTypedArray()) { _, which ->
                    launchTerminalCommand("cd '${paths[which]}' && openclaw tui")
                }
                .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
                .show()
        }
    }

    // Bug real (auditor\u00eda 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // \u00a71.2): isModuleRunning() bloquea hasta 5s (ProcessBuilder().waitFor()) \u2014 antes se
    // llamaba directo en el hilo de UI. Mismo patr\u00f3n que loadInfo(): Thread de fondo +
    // resultado posteado a runOnUiThread.
    //
    // Pedido expl\u00edcito del usuario (2026-08-25): el navegador interno abr\u00eda la URL pelada
    // ("http://localhost:18789", sin token) \u2014 el usuario ten\u00eda que pegar el token a mano,
    // pese a que "Mostrar URL con token" ya arma la URL completa con "#token=..." para el
    // navegador externo. Ahora se abre esa misma URL con token (OpenClawNative.gatewayUrl())
    // tambi\u00e9n en el WebView interno, para entrar de una \u2014 si todav\u00eda no hay token (gateway sin
    // arrancar nunca antes), cae a la URL pelada como antes.
    private fun openWebServer() {
        Thread {
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            if (running) {
                val url = OpenClawNative.gatewayUrl().optString("url", "http://localhost:18789")
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    navigateTo(ModuleWebViewFragment.newInstance(url, getModuleName()))
                }
                return@Thread
            }
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(getString(R.string.openclaw_toast_starting_gateway))
                startModuleService { ok, _ ->
                    // startModuleService() ya entrega onDone en el hilo principal \u2014 leer el
                    // config real (gatewayUrl()) es I/O de disco, as\u00ed que se hace en un Thread
                    // aparte para no bloquear la UI, mismo patr\u00f3n que loadInfo()/showUrlWithToken().
                    if (!ok) {
                        toast(getString(R.string.openclaw_toast_could_not_start_gateway))
                        return@startModuleService
                    }
                    Thread {
                        val url = OpenClawNative.gatewayUrl().optString("url", "http://localhost:18789")
                        if (!isAdded) return@Thread
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            navigateTo(ModuleWebViewFragment.newInstance(url, getModuleName()))
                        }
                    }.start()
                }
            }
        }.start()
    }

    private fun showUrlWithToken() {
        Thread {
            val json = OpenClawNative.gatewayUrl()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_unknown))))
                    return@runOnUiThread
                }
                val url = json.optString("url", "")
                val ctx = context ?: return@runOnUiThread
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.openclaw_dialog_gateway_url_title))
                    .setMessage(if (url.isNotEmpty()) url else getString(R.string.openclaw_gateway_no_token_msg))
                    .setPositiveButton(getString(R.string.openclaw_btn_copy)) { _, _ ->
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("openclaw_url", url))
                        toast(getString(R.string.openclaw_toast_url_copied))
                    }
                    .setNegativeButton(getString(R.string.openclaw_btn_close), null)
                    .show()
            }
        }.start()
    }

    // Bug real (2026-08-07, ver docs/humano/humano91.md): "Proveedor IA / Modelo" era de
    // solo lectura — el TUI real (_submenu_cl_proveedor) tiene 3 acciones que escriben el
    // config de verdad, portadas acá 1:1 (ver OpenClawNative.kt para el detalle del JSON).
    private fun showProvidersMenu() {
        val options = arrayOf(
            getString(R.string.openclaw_option_view_full_config),
            getString(R.string.openclaw_option_configure_ollama),
            getString(R.string.openclaw_option_custom_provider),
            getString(R.string.openclaw_option_restore_backup)
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.openclaw_dialog_provider_model_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showProviders()
                    1 -> configureOllamaProvider()
                    2 -> configureCustomProvider()
                    3 -> restoreProviderBackup()
                }
            }
            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
            .show()
    }

    // ── Canales de mensajería (channels.<provider>) ─────────────────────────────────
    // Discord/Telegram/WhatsApp/Slack — los 4 canales confirmados reales contra
    // docs.openclaw.ai/gateway/config-channels (ver docs/modulos/OPENCLAW.md sección 8).
    // Signal/iMessage/WebChat y los de plugin (Matrix/Nostr/Twitch/Zalo) también son reales
    // pero se dejan fuera del menú por ahora — mismo criterio de "editor del caso común" que
    // el resto de esta pantalla, no una lista exhaustiva de cada canal soportado por el
    // proyecto real detrás de OpenClaw.
    // Función en vez de propiedad de clase — getString() requiere el Fragment adjunto, y las
    // propiedades se inicializan en construcción (antes de onAttach()).
    private fun channelProviders() = listOf(
        "discord" to getString(R.string.openclaw_channel_discord),
        "telegram" to getString(R.string.openclaw_channel_telegram),
        "whatsapp" to getString(R.string.openclaw_channel_whatsapp),
        "slack" to getString(R.string.openclaw_channel_slack)
    )

    private fun showChannelsMenu() {
        val channelProviders = channelProviders()
        val names = channelProviders.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.openclaw_dialog_configure_channels_title))
            .setItems(names) { _, which -> showChannelEditDialog(channelProviders[which].first, channelProviders[which].second) }
            .setNegativeButton(getString(R.string.openclaw_btn_close), null)
            .show()
    }

    private fun showChannelEditDialog(providerId: String, providerLabel: String) {
        Thread {
            val json = OpenClawNative.channelsList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_unknown))))
                    return@runOnUiThread
                }
                val existing = json.optJSONObject("channels")?.optJSONObject(providerId)
                buildChannelEditDialog(providerId, providerLabel, existing)
            }
        }.start()
    }

    private fun buildChannelEditDialog(providerId: String, providerLabel: String, existing: org.json.JSONObject?) {
        val ctx = requireContext()
        val enabledSwitch = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            text = getString(R.string.openclaw_switch_channel_active)
            isChecked = existing?.optBoolean("enabled", false) ?: false
        }
        val tokenInput = EditText(ctx).apply {
            hint = getString(R.string.openclaw_hint_channel_token_format, providerLabel)
            setText(existing?.optString("token", "") ?: "")
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(enabledSwitch)
            addView(tokenInput)
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(providerLabel)
            .setView(layout)
            .setPositiveButton(getString(R.string.openclaw_btn_save)) { _, _ ->
                val token = tokenInput.text.toString().trim()
                Thread {
                    val res = OpenClawNative.writeChannelEntry(providerId, token, enabledSwitch.isChecked)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.openclaw_toast_saved))
                        else getString(R.string.openclaw_error_format, res.optString("error", getString(R.string.openclaw_error_unknown))))
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.openclaw_btn_delete)) { _, _ -> confirmDeleteChannel(providerId, providerLabel) }
        }
        builder.show()
    }

    private fun confirmDeleteChannel(providerId: String, providerLabel: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.openclaw_dialog_delete_channel_title))
            .setMessage(getString(R.string.openclaw_confirm_delete_channel_format, providerLabel))
            .setPositiveButton(getString(R.string.openclaw_btn_delete)) { _, _ ->
                Thread {
                    val res = OpenClawNative.channelDelete(providerId)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.openclaw_toast_deleted))
                        else getString(R.string.openclaw_error_format, res.optString("error", getString(R.string.openclaw_error_unknown))))
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
            .show()
    }

    private fun configureOllamaProvider() {
        Thread {
            val json = OpenClawNative.ollamaModels()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_ollama_unavailable))))
                    return@runOnUiThread
                }
                val models = json.optJSONArray("models")
                if (models == null || models.length() == 0) {
                    toast(getString(R.string.openclaw_toast_no_ollama_models))
                    return@runOnUiThread
                }
                val names = (0 until models.length()).map { models.getJSONObject(it).optString("name") }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.openclaw_dialog_ollama_model_title))
                    .setItems(names.toTypedArray()) { _, which ->
                        askPrimaryThen { primary ->
                            Thread {
                                val res = OpenClawNative.providersConfigureOllama(names[which], primary)
                                if (!isAdded) return@Thread
                                requireActivity().runOnUiThread {
                                    toast(if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.openclaw_toast_configured))
                                    else getString(R.string.openclaw_error_format, res.optString("error", getString(R.string.openclaw_error_unknown))))
                                }
                            }.start()
                        }
                    }
                    .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun configureCustomProvider() {
        val ctx = requireContext()
        val nameInput = EditText(ctx).apply { hint = getString(R.string.openclaw_hint_custom_name) }
        val urlInput = EditText(ctx).apply { hint = getString(R.string.openclaw_hint_custom_url) }
        val keyInput = EditText(ctx).apply { hint = getString(R.string.openclaw_hint_api_key) }
        val modelInput = EditText(ctx).apply { hint = getString(R.string.openclaw_hint_custom_model) }
        val ctxInput = EditText(ctx).apply {
            hint = getString(R.string.openclaw_hint_context_window)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput); addView(urlInput); addView(keyInput); addView(modelInput); addView(ctxInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.openclaw_dialog_custom_provider_title))
            .setMessage(getString(R.string.openclaw_dialog_custom_provider_msg))
            .setView(layout)
            .setPositiveButton(getString(R.string.openclaw_btn_continue)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                val key = keyInput.text.toString().trim()
                val model = modelInput.text.toString().trim()
                val contextWindow = ctxInput.text.toString().trim().toIntOrNull() ?: 32768
                if (name.isEmpty() || url.isEmpty() || key.isEmpty() || model.isEmpty()) {
                    toast(getString(R.string.openclaw_toast_missing_data)); return@setPositiveButton
                }
                askPrimaryThen { primary ->
                    Thread {
                        val res = OpenClawNative.providersConfigureCustom(name, url, key, model, contextWindow, primary)
                        if (!isAdded) return@Thread
                        requireActivity().runOnUiThread {
                            toast(if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.openclaw_toast_configured))
                            else getString(R.string.openclaw_error_format, res.optString("error", getString(R.string.openclaw_error_unknown))))
                        }
                    }.start()
                }
            }
            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
            .show()
    }

    private fun askPrimaryThen(onChoice: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.openclaw_dialog_how_to_use_model_title))
            .setItems(arrayOf(getString(R.string.openclaw_option_primary_model), getString(R.string.openclaw_option_secondary_model))) { _, which ->
                onChoice(which == 0)
            }
            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
            .show()
    }

    private fun restoreProviderBackup() {
        Thread {
            val json = OpenClawNative.providersListBackups()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val backups = json.optJSONArray("backups")
                if (backups == null || backups.length() == 0) {
                    toast(getString(R.string.openclaw_toast_no_backups))
                    return@runOnUiThread
                }
                val names = (0 until backups.length()).map { backups.getJSONObject(it).optString("name") }
                val paths = (0 until backups.length()).map { backups.getJSONObject(it).optString("path") }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.openclaw_dialog_restore_backup_title))
                    .setItems(names.toTypedArray()) { _, which ->
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.openclaw_confirm_restore_backup_format, names[which]))
                            .setPositiveButton(getString(R.string.openclaw_btn_restore)) { _, _ ->
                                Thread {
                                    val res = OpenClawNative.providersRestoreBackup(paths[which])
                                    if (!isAdded) return@Thread
                                    requireActivity().runOnUiThread {
                                        toast(if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.openclaw_toast_restored))
                                        else getString(R.string.openclaw_error_format, res.optString("error", getString(R.string.openclaw_error_unknown))))
                                    }
                                }.start()
                            }
                            .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
                            .show()
                    }
                    .setNegativeButton(getString(R.string.openclaw_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun showProviders() {
        Thread {
            val json = OpenClawNative.providersList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.openclaw_error_format, json.optString("error", getString(R.string.openclaw_error_unknown))))
                    return@runOnUiThread
                }
                val config = json.optJSONObject("config")
                val ctx = context ?: return@runOnUiThread
                val body = config?.toString(2) ?: json.optString("message", getString(R.string.openclaw_config_empty))
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.openclaw_dialog_provider_model_title))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.openclaw_btn_close), null)
                    .show()
            }
        }.start()
    }

    private fun reinstall() {
        toast(getString(R.string.openclaw_toast_reinstalling))
        reinstallModuleService { ok ->
            toast(if (ok) getString(R.string.openclaw_toast_updated) else getString(R.string.openclaw_toast_install_failed))
        }
    }
}
