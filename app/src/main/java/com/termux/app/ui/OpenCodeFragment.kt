package com.termux.app.ui

import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.OpenCodeNative
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.showProjectsMenu
import com.termux.app.util.kairosThemeColor

class OpenCodeFragment : BaseModuleFragment() {
    override fun getModuleId() = "opencode"
    override fun getModuleName() = "OpenCode"

    private lateinit var versionValue: TextView
    private lateinit var webServerRow: DropdownSwitchRow
    private lateinit var openWebBtn: View
    private val webServerPorts = listOf(3000, 4096)

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.opencode_card_estado)) {
            addView(infoRow(getString(R.string.opencode_label_variant), "native\u00B7glibc"))
            val versionRow = valueRow(getString(R.string.opencode_label_version), getString(R.string.opencode_dash))
            versionValue = versionRow.second
            addView(versionRow.first)
            // Dropdown (puerto) + switch bloqueado — pedido explícito del usuario (2026-08-22,
            // ver docs/humano/humano192.md): reemplaza los 2 botones "Servidor web :3000"/":4096" +
            // el "Detener servidor" suelto de abajo. Mientras el switch está ON no se puede
            // cambiar el puerto (hay que apagar primero) — la TUI queda totalmente aparte, sin
            // relación con este switch (aclaración explícita del usuario).
            webServerRow = dropdownSwitchRow(
                label = getString(R.string.opencode_label_web_server),
                options = webServerPorts.map { ":$it" },
                initialIndex = 0,
                initialOn = false,
                onOptionChosen = {},
                onSwitchToggled = { on, portIndex -> onWebServerSwitchToggled(on, webServerPorts[portIndex]) }
            )
            addView(webServerRow.root)
            openWebBtn = createActionButton(getString(R.string.opencode_btn_open), GHOST) {
                openWebServerView(webServerPorts[webServerRow.selectedOptionIndex()])
            }.also { it.visibility = View.GONE }
            addView(openWebBtn)
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.opencode_label_terminal_tui)
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
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md): antes SIEMPRE hac\u00EDa "cd ~ &&
        // opencode ." (cmd_opencode tui-cmd) sin importar el proyecto \u2014 promptOpenLocation()
        // ofrece "Carpeta por defecto (~)" (comportamiento de siempre) o "Elegir carpeta\u2026"
        // (mismo navegador de carpetas de Proyectos), OpenCodeNative.tuiCmd(path) ya aceptaba
        // el path opcional, solo faltaba cablearlo desde ac\u00E1.
        actionButton(getString(R.string.opencode_btn_tui_terminal), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand(OpenCodeNative.tuiCmd().optString("command")) },
                onChooseFolder = { path -> launchTerminalCommand(OpenCodeNative.tuiCmd(path).optString("command")) }
            )
        }
        // Bug real (2026-08-07, ver docs/humano/humano88.md): estos dos botones eran mucho
        // m\u00E1s pobres que el "Gestionar proyectos" de CodexFragment/ClaudeFragment sobre la
        // misma carpeta compartida ~/proyectos \u2014 solo sincronizar-todos y una lista
        // de solo lectura, sin symlink/importar/eliminar. Se reemplazan por el mismo
        // patr\u00F3n de 4 opciones (ProjectsManager ya es gen\u00E9rico, no hizo falta tocarlo).
        // onLaunchInProject agregado 2026-08-26 (mismo mockup de arriba) \u2014 antes este men\u00FA no
        // ten\u00EDa forma de abrir la TUI directo en un proyecto ya importado a ~/proyectos, solo
        // symlink/copiar/eliminar/sincronizar.
        actionButton(getString(R.string.opencode_btn_manage_projects), GHOST) {
            showProjectsMenu(
                onToast = { toast(it) },
                onLaunchInProject = { path -> launchTerminalCommand(OpenCodeNative.tuiCmd(path).optString("command")) }
            )
        }
        // Confirmado contra opencode.ai/docs/cli/ (2026-08-17): "opencode run" corre en modo
        // no interactivo pasando el prompt como argumento posicional; "--model"/"-m" acepta
        // "provider/model"; "--continue"/"-c" contin\u00FAa la \u00FAltima sesi\u00F3n (combinable con run
        // para un mensaje de seguimiento sin abrir la TUI). Distinto del bot\u00F3n "TUI en
        // terminal" de arriba, que s\u00ED abre la interfaz interactiva completa.
        addCard(getString(R.string.opencode_card_prompt_directo)) {
            actionButton(getString(R.string.opencode_btn_send_prompt), PRIMARY) {
                runDirectPrompt()
            }
        }
        // "opencode auth login" \u2014 confirmado real (Models.dev): configura API keys de
        // proveedores en la nube (Anthropic, OpenAI, etc.), distinto de "Configurar Ollama/
        // llama-server local" de abajo (que solo escriben opencode.json apuntando a un
        // endpoint local, sin login ni API key).
        addCard(getString(R.string.opencode_card_cuenta)) {
            actionButton(getString(R.string.opencode_btn_configure_provider), GHOST) {
                launchTerminalCommand("opencode auth login")
            }
        }
        // OpenCode no ten\u00EDa ning\u00FAn bot\u00F3n de MCP todav\u00EDa (a diferencia de Claude Code) \u2014
        // agregado en la misma ronda que se sac\u00F3 "Ver servidores MCP" de Claude de la
        // terminal (ver docs/arquitectura/AUDITORIA_PANEL_MCP_UI_2026-08-19.md). Lee
        // directo ~/.config/opencode/opencode.json (OpenCodeNative.mcpServers) \u2014 el schema
        // real de OpenCode S\u00CD soporta un campo "enabled" plano por servidor, as\u00ED que ac\u00E1 el
        // panel adem\u00E1s permite habilitar/deshabilitar sin tocar la terminal
        // (OpenCodeNative.setMcpServerEnabled).
        addCard(getString(R.string.opencode_card_mcp)) {
            // "Ver detalle" por servidor (ronda de continuaci\u00F3n, ver
            // AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md \u00A7 Actualizaci\u00F3n): antes el panel
            // solo mostraba comando+args aplanados en una l\u00EDnea de "transport" \u2014 ahora reusa
            // OpenCodeNative.mcpServerDetail() para mostrar comando, args y variables de entorno
            // por separado (o URL/OAuth/headers para servidores remotos) sin abrir el editor de
            // texto ni la terminal.
            actionButton(getString(R.string.opencode_btn_view_mcp_servers), GHOST) {
                showMcpPanel(
                    moduleLabel = "OpenCode",
                    loadServers = { OpenCodeNative.mcpServers() },
                    onToggle = { name, enabled -> OpenCodeNative.setMcpServerEnabled(name, enabled) },
                    onViewDetail = { name -> OpenCodeNative.mcpServerDetail(name) }
                )
            }
        }
        engramSetupButton("opencode")
        actionButton(getString(R.string.opencode_btn_configure_ollama), GHOST) {
            configureOllama()
        }
        // Rama "llama-server-and-terminal-ux" (2026-08-05, ver docs/humano/humano71.md) \u2014
        // mismo mecanismo baseURL gen\u00E9rico compatible OpenAI que ya usa "Configurar Ollama
        // local" arriba, apuntando al puerto de llama-server en vez del de Ollama. Sin
        // confirmar en dispositivo real todav\u00EDa.
        actionButton(getString(R.string.opencode_btn_configure_llama_server), GHOST) {
            configureLlamaServer()
        }
        actionButton(getString(R.string.opencode_btn_reinstall_update), GHOST) {
            reinstall()
        }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditor\u00eda de men\u00fas
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // OpenCode no ten\u00eda forma de desinstalarse desde su propia pantalla. Bot\u00f3n suelto
        // (no addMaintenanceCard()) para no duplicar "Reinstalar / actualizar" de arriba.
        addCard(getString(R.string.opencode_card_mantenimiento)) {
            actionButton(getString(R.string.opencode_btn_uninstall), DANGER) { confirmUninstallModule() }
        }
        loadInfo()
    }

    /**
     * Reemplazo directo de cmd_opencode's "info" (kairos_manager.py) \u2014 versi\u00f3n desde el
     * registry/binario y estado corriendo/detenido del puerto :3000 desde ModuleController
     * (isModuleRunning()), mismo patr\u00f3n ya usado por N8nFragment.loadInfo(). Antes la pill
     * de "Web server" estaba hardcodeada en isActive=true sin importar el estado real.
     */
    private fun loadInfo() {
        Thread {
            val info = OpenCodeNative.info()
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val version = info.optString("version", "").ifBlank { getString(R.string.opencode_dash) }
                versionValue.text = version
                webServerRow.setSwitchState(running)
                openWebBtn.visibility = if (running) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // submenu_opencode_native() de termux-ai-stack ofrece dos puertos fijos para el
    // servidor web ([2] :3000 [3] :4096) \u2014 el de :3000 ya usaba ModuleController (script
    // de inicio de modules.json, mismo camino que Detener servidor/isModuleRunning). El
    // :4096 no tiene entrada propia en modules.json, as\u00ed que va directo a
    // OpenCodeNative.webStart() (mismo puerto como argumento \u2014 antes era
    // kairos_manager.py opencode web-start, migrado a Kotlin: era tmux+grep sobre un log,
    // sin necesidad real de Python).
    // Bug real (auditor\u00eda 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // \u00a71.2): isModuleRunning() bloquea hasta 5s \u2014 antes se llamaba directo en el hilo de UI.
    // Mismo patr\u00f3n que loadInfo(): Thread de fondo + resultado posteado a runOnUiThread.
    // Reemplaza openWebServer() (2026-08-22, ver docs/humano/humano192.md) \u2014 el switch bloqueado
    // reemplaza los 2 botones de puerto + "Detener servidor" suelto. ON = iniciar en el
    // puerto elegido en el dropdown, OFF = OpenCodeNative.stopAll() (mata TODAS las sesiones
    // tmux "opencode"/"opencode-*", sin importar el puerto \u2014 mismo alcance que el bot\u00f3n viejo).
    // Mockup aprobado por el usuario 2026-08-26 (ver
    // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md): el switch de servidor web ahora
    // pregunta d\u00f3nde arrancarlo (carpeta por defecto o elegir carpeta) antes de iniciar \u2014
    // OpenCodeNative.webStart(port, cwd) ya acepta el directorio de trabajo opcional. El
    // puerto :3000 sigue sin cwd (pasa por ModuleController/opencode_start.sh, un script fijo
    // de modules.json \u2014 cablear cwd ah\u00ed es un cambio de mayor alcance, fuera del pedido puntual
    // de esta ronda, que apuntaba a webStart()).
    private fun onWebServerSwitchToggled(on: Boolean, port: Int) {
        if (on) {
            promptOpenLocation(
                onDefault = { startWebServer(port, null) },
                onChooseFolder = { path -> startWebServer(port, path) }
            )
        } else {
            Thread { OpenCodeNative.stopAll() }.start()
            stopModuleService { ok ->
                toast(if (ok) getString(R.string.opencode_server_stopped) else getString(R.string.opencode_server_stop_failed))
                loadInfo()
            }
        }
    }

    private fun startWebServer(port: Int, cwd: String?) {
        toast(getString(R.string.opencode_starting_server, port))
        if (port == 3000) {
            // cwd ignorado a prop\u00f3sito ac\u00e1 \u2014 el puerto :3000 pasa por
            // ModuleController/opencode_start.sh (script fijo de modules.json, siempre arranca
            // en ~), ver comentario de onWebServerSwitchToggled() arriba.
            if (!cwd.isNullOrBlank()) toast(getString(R.string.opencode_port_3000_default_cwd))
            startModuleService { ok, _ ->
                requireActivity().runOnUiThread {
                    if (!ok) toast(getString(R.string.opencode_server_start_failed))
                    loadInfo()
                }
            }
        } else {
            Thread {
                val json = OpenCodeNative.webStart(port, cwd)
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!json.optBoolean("ok", false)) {
                        toast(getString(R.string.opencode_error_prefix, json.optString("error", getString(R.string.opencode_error_unknown))))
                    }
                    loadInfo()
                }
            }.start()
        }
    }

    private fun openWebServerView(port: Int) {
        navigateTo(ModuleWebViewFragment.newInstance("http://localhost:$port", "OpenCode"))
    }

    // submenu_opencode_native() opci\u00f3n [7] "Configurar Ollama local" \u2014 escribe
    // ~/.config/opencode/opencode.json apuntando al proveedor Ollama. Antes pasaba por
    // kairos_manager.py (cmd_opencode ollama-config/ollama-models) \u2014 era solo lectura/
    // escritura de un JSON simple + una llamada HTTP a Ollama, el usuario pregunt\u00f3
    // expl\u00edcitamente "por que si es ajustar un json" ten\u00eda que pasar por python; ten\u00eda
    // raz\u00f3n, ahora es org.json + java.io.File directo v\u00eda OpenCodeNative.
    private fun configureOllama() {
        Thread {
            val json = OpenCodeNative.ollamaModels()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    toast(getString(R.string.opencode_error_prefix, json.optString("error", getString(R.string.opencode_error_ollama_unavailable))))
                    return@runOnUiThread
                }
                val models = json.optJSONArray("models")
                if (models == null || models.length() == 0) {
                    toast(getString(R.string.opencode_no_ollama_models))
                    return@runOnUiThread
                }
                val names = (0 until models.length()).map { models.getJSONObject(it).optString("name") }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.opencode_dialog_title_ollama_model))
                    .setItems(names.toTypedArray()) { _, which ->
                        Thread {
                            val res = OpenCodeNative.ollamaConfig(names[which])
                            if (!isAdded) return@Thread
                            requireActivity().runOnUiThread {
                                toast(
                                    if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.opencode_configured_default))
                                    else getString(R.string.opencode_error_prefix, res.optString("error", getString(R.string.opencode_error_unknown)))
                                )
                            }
                        }.start()
                    }
                    .setNegativeButton(getString(R.string.opencode_dialog_cancel), null)
                    .show()
            }
        }.start()
    }

    // Mismo patr\u00f3n que configureOllama() de arriba, pero llama-server sirve UN solo modelo a
    // la vez (el que se eligi\u00f3 al iniciarlo, ver LlamaServerFragment) \u2014 no tiene un endpoint
    // tipo /api/tags para listar modelos disponibles como Ollama, as\u00ed que se lista directo de
    // LocalModelManager (los .gguf ya descargados, mismo directorio que usa el Chat IA) en vez
    // de preguntarle al servidor.
    private fun configureLlamaServer() {
        val models = com.termux.app.util.LocalModelManager.listModels(requireContext())
        if (models.isEmpty()) {
            toast(getString(R.string.opencode_no_gguf_models))
            return
        }
        val names = models.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.opencode_dialog_title_llama_model))
            .setItems(names) { _, which ->
                Thread {
                    val res = OpenCodeNative.llamaServerConfig(names[which])
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        toast(
                            if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.opencode_configured_default))
                            else getString(R.string.opencode_error_prefix, res.optString("error", getString(R.string.opencode_error_unknown)))
                        )
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.opencode_dialog_cancel), null)
            .show()
    }

    // Escapado mínimo de comilla simple — mismo patrón que CliToolFragment.shellEscape().
    private fun shellEscape(text: String): String = text.replace("'", "'\\''")

    private fun runDirectPrompt() {
        val ctx = requireContext()
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.opencode_prompt_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        val continueCheckbox = CheckBox(ctx).apply {
            text = getString(R.string.opencode_continue_last_session)
            val pad = dp(20)
            setPadding(pad, dp(8), pad, 0)
        }
        val modelInput = EditText(ctx).apply {
            hint = getString(R.string.opencode_model_hint)
            val pad = dp(20)
            setPadding(pad, dp(8), pad, 0)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(promptInput)
            addView(continueCheckbox)
            addView(modelInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.opencode_dialog_title_prompt))
            .setView(layout)
            .setPositiveButton(getString(R.string.opencode_dialog_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.opencode_prompt_empty)); return@setPositiveButton }
                val model = modelInput.text.toString().trim()
                val modelArg = if (model.isEmpty()) "" else " --model '${shellEscape(model)}'"
                val continueArg = if (continueCheckbox.isChecked) " --continue" else ""
                launchTerminalCommand("opencode run$continueArg '${shellEscape(prompt)}'$modelArg")
            }
            .setNegativeButton(getString(R.string.opencode_dialog_cancel), null)
            .show()
    }

    private fun reinstall() {
        toast(getString(R.string.opencode_reinstalling))
        reinstallModuleService { ok ->
            toast(if (ok) getString(R.string.opencode_updated) else getString(R.string.opencode_update_failed))
        }
    }

}
