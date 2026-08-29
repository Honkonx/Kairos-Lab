package com.termux.app.ui

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.data.ModuleRegistry
import com.termux.app.model.ModuleInfo
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.LocalCliProviderNative
import com.termux.app.util.LocalModelManager
import com.termux.app.util.OllamaApiClient
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.showProjectsMenu
import org.json.JSONObject
import com.termux.app.util.kairosThemeColor

/**
 * Fragment de detalle GENÉRICO para módulos — reemplaza el `when (module.id)` hardcodeado
 * de ModulesFragment.navigateToModuleDetail() como FALLBACK para cualquier módulo que no
 * tenga (ni necesite) un fragment dedicado (ver docs/arquitectura/PLAN.md, Fase A del
 * sistema de plugins, 2026-08-10).
 *
 * Renderiza TODO desde la metadata de [ModuleInfo] (modules.json) + el estado real del
 * registry ($HOME/.android_server_registry): sin importar qué módulo nuevo se agregue al
 * catálogo, este fragment ya sabe dibujar su estado, su switch, su webview, su terminal y
 * sus acciones de instalar/actualizar/desinstalar. Eso es lo que hace "semi-universal" el
 * sistema de módulos: agregar un plugin deja de requerir una clase Kotlin nueva.
 *
 * Los 14 fragments dedicados (OllamaFragment, N8nFragment, ...) se conservan tal cual y se
 * siguen usando para sus módulos (tienen UI específica real); este es el `else` del when.
 */
class GenericModuleFragment : BaseModuleFragment() {

    private var module: ModuleInfo? = null
    private var estadoPillSlot: LinearLayout? = null
    private var installedVersion: String = ""

    override fun getModuleId(): String = module?.id ?: ""
    override fun getModuleName(): String = module?.name ?: getString(R.string.generic_module_default_name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Misma convención que BottomSheetInstalacion.newInstance(): se pasan los campos
        // individuales (ModuleInfo NO es Parcelable y no se agrega kotlin-parcelize para
        // no tocar la configuración de build — patrón establecido en este codebase).
        val args = arguments
        if (args != null) {
            module = ModuleInfo(
                id = args.getString(ARG_ID) ?: "",
                name = args.getString(ARG_NAME) ?: "",
                icon = args.getString(ARG_ICON) ?: "",
                iconBg = args.getString(ARG_ICON_BG) ?: "",
                port = args.getString(ARG_PORT) ?: "",
                description = args.getString(ARG_DESCRIPTION) ?: "",
                size = args.getString(ARG_SIZE) ?: "",
                type = args.getString(ARG_TYPE) ?: "",
                requiresProot = args.getBoolean(ARG_REQUIRES_PROOT, false),
                hasVariants = args.getBoolean(ARG_HAS_VARIANTS, false),
                estimate = args.getString(ARG_ESTIMATE) ?: "",
                hasSwitch = args.getBoolean(ARG_HAS_SWITCH, true),
                tmuxSession = args.getString(ARG_TMUX_SESSION) ?: "",
                webviewUrl = args.getString(ARG_WEBVIEW_URL) ?: "",
                terminalCommand = args.getString(ARG_TERMINAL_COMMAND) ?: ""
            )
        }
    }

    override fun buildContent() {
        val m = module ?: return
        if (!isModuleInstalled()) { showNotInstalled(m.name); return }

        readInstalledVersion()

        addCard(getString(R.string.generic_module_section_estado)) {
            addView(infoRow(getString(R.string.generic_module_label_id), m.id))
            if (installedVersion.isNotEmpty()) addView(infoRow(getString(R.string.generic_module_label_version), installedVersion))
            if (m.port.isNotEmpty()) addView(infoRow(getString(R.string.generic_module_label_port), m.port))
            if (m.type.isNotEmpty()) addView(infoRow(getString(R.string.generic_module_label_type), m.type))
            addView(infoRow(
                getString(R.string.generic_module_label_execution),
                if (m.requiresProot) getString(R.string.generic_module_value_proot) else getString(R.string.generic_module_value_native),
                if (m.requiresProot) requireContext().kairosThemeColor(R.attr.kairosAmber) else null
            ))
            if (m.tmuxSession.isNotEmpty()) addView(infoRow(getString(R.string.generic_module_label_tmux_session), m.tmuxSession))
            if (m.terminalCommand.isNotEmpty()) {
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(TextView(requireContext()).apply {
                        text = getString(R.string.generic_module_label_terminal)
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
            estadoPillSlot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.generic_module_label_server)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill("—", false).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
            addView(estadoPillSlot)
        }

        if (m.description.isNotEmpty()) {
            addCard(getString(R.string.generic_module_section_que_es)) {
                addView(TextView(requireContext()).apply {
                    text = m.description
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                })
            }
        }

        refreshEstadoPill()

        // Switch real start/stop (mismos helpers con guard de Fragment-adjunto que el resto).
        if (m.hasSwitch) {
            addCard(getString(R.string.generic_module_section_control)) {
                actionButton(getString(R.string.generic_module_btn_start_server), PRIMARY) {
                    startModuleService { ok, output ->
                        if (ok) toast(getString(R.string.generic_module_toast_started, m.name))
                        else toast(getString(R.string.generic_module_toast_start_failed, output.take(80)))
                    }
                }
                actionButton(getString(R.string.generic_module_btn_stop_server), DANGER) {
                    stopModuleService { ok ->
                        if (ok) toast(getString(R.string.generic_module_toast_stopped, m.name))
                        else toast(getString(R.string.generic_module_toast_stop_failed, m.name))
                    }
                }
            }
        }

        // Interfaz web del módulo (si expone webviewUrl, ej. opencode/n8n/openclaw).
        if (m.webviewUrl.isNotEmpty()) {
            actionButton(getString(R.string.generic_module_btn_open_webview), GHOST) {
                val url = if (m.webviewUrl.startsWith("http")) m.webviewUrl else "http://${m.webviewUrl}"
                navigateTo(ModuleWebViewFragment.newInstance(url, m.name))
            }
        }

        // CLI del módulo (si expone terminalCommand, ej. python/claude/codex/engram/cactus).
        if (m.terminalCommand.isNotEmpty()) {
            // Mockup aprobado por el usuario 2026-08-26 (ver
            // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI"
            // tras corrección explícita del usuario — este fallback GENÉRICO cubre cualquier
            // módulo nuevo del catálogo con terminalCommand que no tenga fragment dedicado, así
            // que agregar el diálogo acá lo hereda automáticamente sin tocar nada más.
            actionButton(getString(R.string.generic_module_btn_open_terminal), GHOST) {
                promptOpenLocation(
                    onDefault = { launchTerminalCommand(m.terminalCommand) },
                    onChooseFolder = { path -> launchTerminalCommand("cd '$path' && ${m.terminalCommand}") }
                )
            }
            // Gestión de proyectos compartida (~/proyectos) para TODOS los CLIs — el menú
            // vive en ProjectActions.kt (semi-universal) y cubre automáticamente a los CLIs
            // de i-Haklab (freebuff/codebuff/copilotcli/minimaxcli/mimocode/mistralvibe/
            // qwencode) y cactus, que caen en este fragment genérico. Pedido 2026-08-13:
            // "todos los CLI deben tener la opción para symlink en Downloads y en ~/proyectos".
            // El menú incluye "Abrir en proyecto" como primera opción (mismo comando + cd).
            actionButton(getString(R.string.generic_module_btn_manage_projects), GHOST) {
                showProjectsMenu(
                    onToast = { toast(it) },
                    onLaunchInProject = { path -> launchTerminalCommand("cd '$path' && ${m.terminalCommand}") }
                )
            }
        }

        // "Usar Ollama/llama-server local" — SOLO para los 3 CLIs de i-Haklab confirmados
        // con soporte real de endpoint OpenAI-compatible custom (investigación contra cada
        // repo real, ver docs/humano/humano116.md y LocalCliProviderNative.kt): qwencode
        // (.qwen/.env), mimocode (mimocode.jsonc provider "custom"), mistralvibe
        // (config.toml [[providers]] backend=generic). Los otros 4 candidatos del mismo
        // lote (freebuff/codebuff/minimaxcli/copilotcli) están atados a su propio backend
        // cloud sin ningún campo de endpoint custom — no se les agrega el botón a propósito,
        // en vez de forzar una feature que no va a funcionar.
        if (m.id in LOCAL_PROVIDER_MODULE_IDS) {
            addCard(getString(R.string.generic_module_section_local_provider)) {
                actionButton(getString(R.string.generic_module_btn_use_ollama_local), GHOST) { useOllamaLocal(m.id) }
                actionButton(getString(R.string.generic_module_btn_use_llamaserver_local), GHOST) { useLlamaServerLocal(m.id) }
            }
        }

        // Tamaño/estimación como info secundaria (no siempre existen en el catálogo).
        val meta = listOf(m.size to getString(R.string.generic_module_label_size), m.estimate to getString(R.string.generic_module_label_install_estimate)).filter { it.first.isNotEmpty() }
        if (meta.isNotEmpty()) {
            addCard(getString(R.string.generic_module_section_details)) {
                meta.forEach { (value, key) -> addView(infoRow(key, value)) }
            }
        }

        addCard(getString(R.string.generic_module_section_maintenance)) {
            actionButton(getString(R.string.generic_module_btn_update), GHOST) {
                toast(getString(R.string.generic_module_toast_updating, m.name))
                updateModuleService { ok ->
                    toast(if (ok) getString(R.string.generic_module_toast_updated, m.name) else getString(R.string.generic_module_toast_update_failed, m.id))
                }
            }
            actionButton(getString(R.string.generic_module_btn_uninstall), DANGER) {
                confirmUninstall(m)
            }
        }
    }

    private fun readInstalledVersion() {
        try {
            installedVersion = ModuleRegistry(requireContext()).load().get("${module?.id}.version") ?: ""
        } catch (_: Exception) {
            installedVersion = ""
        }
    }

    private fun refreshEstadoPill() {
        Thread {
            val running = isModuleRunning()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val slot = estadoPillSlot ?: return@runOnUiThread
                slot.removeViewAt(slot.childCount - 1)
                slot.addView(pill(if (running) getString(R.string.generic_module_status_running) else getString(R.string.generic_module_status_stopped), running).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
        }.start()
    }

    // Mismo patrón que HermesFragment.useOllamaLocal()/OpenCodeFragment.configureOllama():
    // lista los modelos REALES ya descargados en Ollama vía OllamaApiClient (mismo cliente
    // HTTP compartido con ChatFragment/OllamaFragment), en vez de un EditText de texto libre.
    private fun useOllamaLocal(moduleId: String) {
        Thread {
            val models = try {
                OllamaApiClient.listModels()
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    toast(getString(R.string.generic_module_error_prefix, e.message ?: getString(R.string.generic_module_ollama_not_available)))
                }
                return@Thread
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (models.isEmpty()) { toast(getString(R.string.generic_module_toast_no_ollama_models)); return@runOnUiThread }
                val names = models.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.generic_module_dialog_title_ollama_model, module?.name))
                    .setItems(names) { _, which -> setLocalProvider(moduleId, "http://127.0.0.1:11434/v1", names[which]) }
                    .setNegativeButton(getString(R.string.generic_module_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    // llama-server sirve UN solo modelo a la vez (el elegido al iniciarlo, ver
    // LlamaServerFragment) — sin endpoint tipo /api/tags, así que se lista directo de
    // LocalModelManager (los .gguf ya descargados), mismo patrón que HermesFragment.
    private fun useLlamaServerLocal(moduleId: String) {
        val models = LocalModelManager.listModels(requireContext())
        if (models.isEmpty()) {
            toast(getString(R.string.generic_module_toast_no_gguf_models))
            return
        }
        val names = models.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.generic_module_dialog_title_llamaserver_model, module?.name))
            .setItems(names) { _, which -> setLocalProvider(moduleId, "http://127.0.0.1:8085/v1", names[which]) }
            .setNegativeButton(getString(R.string.generic_module_btn_cancel), null)
            .show()
    }

    private fun setLocalProvider(moduleId: String, baseUrl: String, model: String) {
        Thread {
            val json = when (moduleId) {
                "qwencode" -> LocalCliProviderNative.configureQwenCode(baseUrl, model)
                "mimocode" -> LocalCliProviderNative.configureMimoCode(baseUrl, model)
                "mistralvibe" -> LocalCliProviderNative.configureMistralVibe(baseUrl, model)
                else -> JSONObject().put("ok", false).put("error", getString(R.string.generic_module_error_no_local_provider_support))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (json.optBoolean("ok", false)) getString(R.string.generic_module_toast_provider_configured, model) else getString(R.string.generic_module_error_prefix, json.optString("error")))
            }
        }.start()
    }

    // Checkbox "opt-in" de desinstalación profunda (2026-08-13, ver deepUninstallModule() en
    // ModuleController.kt) — mismo patrón armado a mano que PluginsFragment.confirmUninstall().
    private fun confirmUninstall(m: ModuleInfo) {
        val deepCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = getString(R.string.generic_module_checkbox_deep_uninstall)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.generic_module_dialog_title_uninstall, m.name))
            .setMessage(getString(R.string.generic_module_dialog_msg_uninstall))
            .setView(deepCheckbox)
            .setPositiveButton(getString(R.string.generic_module_btn_uninstall_confirm)) { _, _ ->
                if (deepCheckbox.isChecked) {
                    com.termux.app.ModuleController.deepUninstallModule(m.id) { ok, message ->
                        if (!isAdded) return@deepUninstallModule
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            toast(message)
                            if (ok) parentFragmentManager.popBackStack()
                        }
                    }
                } else {
                    com.termux.app.ModuleController.uninstallModule(m.id) { ok ->
                        if (!isAdded) return@uninstallModule
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            if (ok) {
                                toast(getString(R.string.generic_module_toast_uninstalled, m.name))
                                parentFragmentManager.popBackStack()
                            } else toast(getString(R.string.generic_module_toast_uninstall_failed, m.name))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.generic_module_btn_cancel), null)
            .show()
    }

    companion object {
        private const val ARG_ID = "module_id"
        private const val ARG_NAME = "module_name"
        private const val ARG_ICON = "module_icon"
        private const val ARG_ICON_BG = "module_icon_bg"
        private const val ARG_PORT = "module_port"
        private const val ARG_DESCRIPTION = "module_description"
        private const val ARG_SIZE = "module_size"
        private const val ARG_TYPE = "module_type"
        private const val ARG_REQUIRES_PROOT = "module_requires_proot"
        private const val ARG_HAS_VARIANTS = "module_has_variants"
        private const val ARG_ESTIMATE = "module_estimate"
        private const val ARG_HAS_SWITCH = "module_has_switch"
        private const val ARG_TMUX_SESSION = "module_tmux_session"
        private const val ARG_WEBVIEW_URL = "module_webview_url"
        private const val ARG_TERMINAL_COMMAND = "module_terminal_command"

        // Investigación 2026-08-13 (ver docs/humano/humano116.md): de los 7 CLIs candidatos
        // de i-Haklab (freebuff/codebuff/copilotcli/minimaxcli/mimocode/mistralvibe/
        // qwencode), solo estos 3 confirman soporte real de endpoint OpenAI-compatible
        // custom contra su repo/documentación real — ver LocalCliProviderNative.kt.
        private val LOCAL_PROVIDER_MODULE_IDS = setOf("qwencode", "mimocode", "mistralvibe")

        fun newInstance(module: ModuleInfo): GenericModuleFragment {
            return GenericModuleFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, module.id)
                    putString(ARG_NAME, module.name)
                    putString(ARG_ICON, module.icon)
                    putString(ARG_ICON_BG, module.iconBg)
                    putString(ARG_PORT, module.port)
                    putString(ARG_DESCRIPTION, module.description)
                    putString(ARG_SIZE, module.size)
                    putString(ARG_TYPE, module.type)
                    putBoolean(ARG_REQUIRES_PROOT, module.requiresProot)
                    putBoolean(ARG_HAS_VARIANTS, module.hasVariants)
                    putString(ARG_ESTIMATE, module.estimate)
                    putBoolean(ARG_HAS_SWITCH, module.hasSwitch)
                    putString(ARG_TMUX_SESSION, module.tmuxSession)
                    putString(ARG_WEBVIEW_URL, module.webviewUrl)
                    putString(ARG_TERMINAL_COMMAND, module.terminalCommand)
                }
            }
        }
    }
}
