package com.termux.app.ui

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.TextView
import com.termux.R
import com.termux.app.oauth.AntigravityOAuth
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.ProjectsManager
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.runProjectsAction
import com.termux.app.util.showProjectsMenu
import com.termux.shared.termux.TermuxConstants
import java.io.File
import com.termux.app.util.kairosThemeColor

class AntigravityFragment : BaseModuleFragment() {
    override fun getModuleId() = "antigravity"
    override fun getModuleName() = getString(R.string.antigravity_module_name)

    private lateinit var estadoPillSlot: LinearLayout

    // 2026-07-28: login nativo "Sign in with Google" (ver com.termux.app.oauth.AntigravityOAuth,
    // portado de referencia/ides/CodeAssist-main/agent-impl/, GPLv3 — licencia compatible con Kairos).
    // El refresh token vive en un archivo plano en $HOME/.config/agy/ — no había ninguna convención
    // previa de dónde vive la config de agy (ni en modulos/antigravity.sh ni en
    // termux-ai-stack-dev/scripts/install_antigravity.sh, se buscó explícitamente antes de inventar
    // esta ruta). ⚠️ Riesgo asumido a propósito: impersonar el cliente OAuth de Antigravity viola los
    // Términos de Servicio de Google — ver el comentario completo en AntigravityOAuth.kt. Además,
    // AntigravitySecrets.kt NO tiene credenciales reales cargadas por defecto (el proyecto de
    // referencia tampoco las tenía committeadas) — el login fallará en el intercambio de token
    // ("invalid_client") hasta que se completen esos valores a mano.
    private val tokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/agy/oauth_refresh_token")

    private var loginRunning = false

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        val loggedIn = tokenFile.exists() && tokenFile.length() > 0
        addCard(getString(R.string.antigravity_card_estado)) {
            addView(infoRow(getString(R.string.antigravity_label_method), getString(R.string.antigravity_value_native_binario)))
            // antigravity.sh escribe antigravity.version al registry en cada instalación
            // exitosa (update_registry(), ver antigravity.sh línea ~85) — mismo patrón que
            // OllamaFragment.kt, leído acá en vez de dejar el "—" hardcodeado que había antes.
            val version = com.termux.app.data.ModuleRegistry(requireContext()).load().get("antigravity.version")
            addView(infoRow(getString(R.string.antigravity_label_version), version?.ifBlank { getString(R.string.antigravity_placeholder_dash) } ?: getString(R.string.antigravity_placeholder_dash)))
            estadoPillSlot = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.antigravity_label_status)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(getString(R.string.antigravity_placeholder_dash), false).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
            addView(estadoPillSlot)
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(0), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.antigravity_label_google_account)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(if (loggedIn) getString(R.string.antigravity_status_connected) else getString(R.string.antigravity_status_not_connected), loggedIn).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            })
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(0), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.antigravity_label_terminal)
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
        refreshEstadoPill()
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
        // corrección explícita del usuario (la ronda anterior lo había dejado solo en OpenCode,
        // asumiendo que "Abrir en proyecto" de más abajo ya era equivalente — no lo era, el
        // usuario pidió el mismo diálogo de 2 opciones en TODOS lados).
        actionButton(getString(R.string.antigravity_btn_open_terminal), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand("agy") },
                onChooseFolder = { path -> launchTerminalCommand("cd '$path' && agy") }
            )
        }
        // "agy -p"/"--print"/"--prompt": confirmado real (docs oficiales,
        // antigravity.google/docs/cli/getting-started + toolsbase.dev/en/reference/
        // antigravity-cli-commands, ambos 2026-08-19) — corre un prompt en modo
        // no interactivo y termina, sin abrir la sesión completa del agente. Mismo
        // hueco que OpenCodeFragment.runDirectPrompt() ya cubre para OpenCode — Antigravity
        // era el único CLI de agente sin este atajo, el usuario tenía que abrir la
        // terminal completa (botón de arriba) para un pedido de una sola línea.
        actionButton(getString(R.string.antigravity_btn_direct_prompt), GHOST) {
            runDirectPrompt()
        }
        // Ronda 2026-08-25 (auditoría de código+docs oficiales): confirmado real contra
        // gradually.ai/en/antigravity-cli-commands + toolsbase.dev/en/reference/
        // antigravity-cli-commands — "agy --continue" retoma la última conversación (no
        // cubierto antes, mismo hueco que otros CLIs ya tenían llenado con
        // continueSessionCommand). "agy --model \"<nombre>\"" selecciona uno de los 8 modelos
        // reales soportados (familia Gemini 3.x, Claude Sonnet/Opus 4.6, GPT-OSS 120B) —
        // agregado como campo opcional dentro del mismo diálogo de prompt directo, mismo
        // patrón que CliToolFragment.askModelThenRun().
        actionButton(getString(R.string.antigravity_btn_continue_last), GHOST) {
            launchTerminalCommand("agy --continue")
        }
        // submenu_antigravity() en termux-ai-stack-dev/scripts/menu_nativo.sh tiene una
        // opción [2] "Abrir agy en proyecto" que lista $HOME/proyectos y hace
        // `cd "$dir" && agy` — ese directorio es EXACTAMENTE el mismo PROJECTS_DIR
        // ($HOME/proyectos) que ya usa kairos_manager.py para Claude/OpenCode
        // (ver ClaudeFragment.kt.openInProject()), así que "claude projects-list"/
        // "opencode projects-list" devuelven la misma carpeta compartida sin
        // importar qué CLI la lista — no hace falta un cmd_antigravity nuevo en
        // kairos_manager.py para esto, solo reusar el dispatch ya existente.
        actionButton(getString(R.string.antigravity_btn_open_in_project), GHOST) {
            openInProject()
        }
        // Nunca había una opción para importar/sincronizar/symlink acá — el usuario
        // reportó explícitamente que Antigravity (y Codex) se quedaron atrás de
        // Claude/OpenCode en esto. Mismo patrón de UI que ClaudeFragment.manageProjects(),
        // sobre la misma carpeta compartida ~/proyectos (ProjectsManager.PROJECTS_DIR).
        actionButton(getString(R.string.antigravity_btn_manage_projects), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }
        engramSetupButton("antigravity-cli")
        if (loggedIn) {
            actionButton(getString(R.string.antigravity_btn_logout_google), DANGER) { logout() }
        } else {
            actionButton(getString(R.string.antigravity_btn_login_google), GHOST) { startGoogleSignIn() }
        }
        actionButton(getString(R.string.antigravity_btn_update), GHOST) {
            // Confirmado contra termux-ai-stack-dev/scripts/menu_nativo.sh
            // (submenu_antigravity, opción [4]): tampoco corre "agy update" como
            // subcomando del CLI — llama `AGY_MODE=update _run_installer
            // install_antigravity.sh`, es decir, re-ejecuta el instalador. Ya es
            // exactamente lo que hace este botón vía ModuleController.installModule().
            toast(getString(R.string.antigravity_toast_reinstalling))
            reinstallModuleService { ok ->
                toast(if (ok) getString(R.string.antigravity_toast_updated) else getString(R.string.antigravity_toast_update_failed))
            }
        }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditoría de menús
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // Antigravity CLI no tenía forma de desinstalarse desde su propia pantalla. Botón
        // suelto (no addMaintenanceCard()) para no duplicar "Actualizar (agy update)" de arriba.
        addCard(getString(R.string.antigravity_card_mantenimiento)) {
            actionButton(getString(R.string.antigravity_btn_uninstall), DANGER) { confirmUninstallModule() }
        }
    }

    private fun refreshEstadoPill() {
        Thread {
            val available = isTermuxBinaryAvailable("agy")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                estadoPillSlot.removeViewAt(estadoPillSlot.childCount - 1)
                estadoPillSlot.addView(pill(if (available) getString(R.string.antigravity_pill_ready) else getString(R.string.antigravity_pill_not_responding), available).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
        }.start()
    }

    // ProjectsManager.projectsList() lee directo $HOME/proyectos (misma carpeta
    // compartida con Claude/OpenCode/Codex, sin filtrar por CLI) — antes pasaba por
    // kairos_manager.py "opencode projects-list" (python3, confirmado roto/no confiable
    // en dispositivo real) solo porque era el módulo que ya tenía el dispatch, no porque
    // hiciera falta OpenCode específicamente.
    private fun openInProject() {
        runProjectsAction({ ProjectsManager.projectsList() }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.antigravity_toast_error_reason, json.optString("error", getString(R.string.antigravity_error_unknown))))
                return@runProjectsAction
            }
            val projects = json.optJSONArray("projects")
            if (projects == null || projects.length() == 0) {
                toast(getString(R.string.antigravity_toast_no_projects))
                return@runProjectsAction
            }
            val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
            val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.antigravity_title_open_project))
                .setItems(names.toTypedArray()) { _, which ->
                    launchTerminalCommand("cd '${paths[which]}' && agy")
                }
                .setNegativeButton(getString(R.string.antigravity_btn_cancel), null)
                .show()
        }
    }

    /**
     * Bug real confirmado (ver docs/humano/humano63.md, auditoría de arquitectura central):
     * los 4 `requireActivity().runOnUiThread {...}` de acá abajo no tenían ningún guard de
     * Fragment-adjunto — el más propenso a dispararse de toda la app, porque este flujo abre
     * el navegador externo (`ACTION_VIEW`) y el usuario sale de Kairos un rato real mientras
     * completa el login de Google. `loginRunning` se libera SIEMPRE (no depende de que el
     * Fragment siga adjunto), para no dejar el sign-in "trabado" si el usuario vuelve después.
     */
    private fun startGoogleSignIn() {
        if (loginRunning) { toast(getString(R.string.antigravity_toast_signin_in_progress)); return }
        loginRunning = true
        toast(getString(R.string.antigravity_toast_opening_google))
        val oauth = AntigravityOAuth()
        val thread = Thread {
            try {
                val refreshToken = oauth.signIn { url ->
                    if (!isAdded) return@signIn
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            toast(getString(R.string.antigravity_toast_browser_error, e.message))
                        }
                    }
                }
                tokenFile.parentFile?.mkdirs()
                tokenFile.writeText(refreshToken)
                loginRunning = false
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    toast(getString(R.string.antigravity_toast_signin_success))
                    refreshUi()
                }
            } catch (e: AntigravityOAuth.OAuthException) {
                loginRunning = false
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.antigravity_toast_signin_failed, e.message))
                }
            } catch (e: Exception) {
                loginRunning = false
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.antigravity_toast_unexpected_error, e.message))
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    // Escapado mínimo de comilla simple — mismo patrón que OpenCodeFragment.shellEscape()/
    // CliToolFragment.shellEscape().
    private fun shellEscape(text: String): String = text.replace("'", "'\\''")

    private fun runDirectPrompt() {
        val ctx = requireContext()
        val promptInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.antigravity_hint_prompt)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // Modelo opcional — 8 modelos reales confirmados (Gemini 3.x, Claude Sonnet/Opus 4.6,
        // GPT-OSS 120B), texto libre en vez de spinner cerrado porque el nombre exacto que
        // "agy --model" espera puede variar entre versiones (ej. "Gemini 3.1 Pro (High)") y no
        // hay un subcomando real tipo "agy models list" confirmado para poblar un selector.
        val modelInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.antigravity_hint_model)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(promptInput)
            addView(modelInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.antigravity_title_direct_prompt))
            .setView(layout)
            .setPositiveButton(getString(R.string.antigravity_btn_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.antigravity_toast_prompt_empty)); return@setPositiveButton }
                val model = modelInput.text.toString().trim()
                val modelFlag = if (model.isNotEmpty()) "--model '${shellEscape(model)}' " else ""
                launchTerminalCommand("agy $modelFlag-p '${shellEscape(prompt)}'")
            }
            .setNegativeButton(getString(R.string.antigravity_btn_cancel), null)
            .show()
    }

    private fun logout() {
        if (tokenFile.exists()) tokenFile.delete()
        toast(getString(R.string.antigravity_toast_session_closed))
        refreshUi()
    }

    private fun refreshUi() {
        if (!isAdded) return
        // container (BaseModuleFragment) tiene el header en el índice 0 (addHeader(), privado,
        // no se puede volver a llamar desde acá) — se preserva y solo se limpia/reconstruye lo
        // que agrega buildContent().
        while (container.childCount > 1) container.removeViewAt(1)
        buildContent()
    }
}
