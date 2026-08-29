package com.termux.app.ui

import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ClaudeNative
import com.termux.app.util.ProjectsManager
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.runProjectsAction
import com.termux.app.util.showProjectsMenu
import com.termux.app.util.kairosThemeColor

class ClaudeFragment : BaseModuleFragment() {
    override fun getModuleId() = "claude"
    override fun getModuleName() = getString(R.string.claude_module_name)

    private lateinit var estadoPillSlot: LinearLayout
    private lateinit var oauthStatusSlot: LinearLayout

    // Permisos por herramienta (--allow-tool/--deny-tool), gap documentado en
    // docs/modulos/CLAUDE_CODE.md (lote 1 de la auditoría de módulos, 2026-08-25). Texto crudo
    // separado por comas, aplicado al mismo flujo de PROMPT DIRECTO ya existente (no una sesión
    // standalone aparte) — se parsea recién al armar el comando en askPermissionModeThenSend().
    private var allowToolsRaw: String = ""
    private var denyToolsRaw: String = ""

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.claude_card_estado)) {
            addView(infoRow(getString(R.string.claude_label_method), getString(R.string.claude_value_native_glibc)))
            // claude.sh escribe claude.version al registry en cada instalación (fix real de
            // esta ronda — antes tenía un mismatch de prefijo, claude_code.* vs claude.*, ver
            // docs/humano/humano34.md) — este valor estaba hardcodeado en "—" sin leerlo nunca.
            val claudeVersion = com.termux.app.data.ModuleRegistry(requireContext()).load().get("claude.version")
            addView(infoRow(getString(R.string.claude_label_version), claudeVersion?.ifBlank { getString(R.string.claude_placeholder_dash) } ?: getString(R.string.claude_placeholder_dash)))
            estadoPillSlot = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.claude_label_status)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(getString(R.string.claude_placeholder_dash), false).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
            addView(estadoPillSlot)
            addView(LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.claude_label_terminal)
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
        // CLAUDE_CODE_OAUTH_TOKEN (2026-08-25, plan aprobado en
        // docs/modulos/CLAUDE_CODE.md \u00A7 "Pendiente aprobado" \u2014 luz verde expl\u00EDcita del
        // usuario). oauthStatusSlot muestra el aviso "usando token fijo" cuando
        // ~/.claude_oauth_token existe, para no repetir la confusi\u00F3n del issue #16238 de
        // GitHub (Claude Code pisa las credenciales de sesi\u00F3n en silencio).
        addCard(getString(R.string.claude_card_cuenta_oauth)) {
            oauthStatusSlot = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(oauthStatusSlot)
            actionButton(getString(R.string.claude_btn_use_oauth_token), GHOST) { promptSetOauthToken() }
            actionButton(getString(R.string.claude_btn_remove_oauth_token), GHOST) { confirmClearOauthToken() }
        }
        refreshOauthStatus()
        // Mockup aprobado por el usuario 2026-08-26 (ver
        // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
        // correcci\u00F3n expl\u00EDcita del usuario \u2014 la ronda anterior hab\u00EDa dejado Claude Code sin
        // tocar asumiendo que "Abrir en proyecto" (lista ~/proyectos) ya cubr\u00EDa el caso; el
        // usuario pidi\u00F3 el mismo di\u00E1logo de 2 opciones ac\u00E1 tambi\u00E9n, que adem\u00E1s permite elegir
        // CUALQUIER carpeta del almacenamiento, no solo un proyecto ya importado.
        actionButton(getString(R.string.claude_btn_open_root_dir), GHOST) {
            promptOpenLocation(
                onDefault = { openClaudeHere(null) },
                onChooseFolder = { path -> openClaudeHere(path) }
            )
        }
        actionButton(getString(R.string.claude_btn_open_in_project), GHOST) {
            openInProject()
        }
        actionButton(getString(R.string.claude_btn_manage_projects), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }
        // Confirmado contra code.claude.com/docs/en/cli-reference (2026-08-17): "-p"/"--print"
        // (modo headless, un prompt entra, una respuesta sale, sin REPL), "--model" (alias
        // sonnet/opus/haiku/fable), "--continue"/"-c" (\u00FAltima conversaci\u00F3n del directorio
        // actual) y "--resume"/"-r" (picker interactivo o por nombre/ID). Todos son argv real
        // del mismo binario/cli.js que ya abre "Abrir en directorio ra\u00EDz" \u2014 ClaudeNative.openCmd
        // ahora acepta extraArgs para reusar exactamente la misma detecci\u00F3n native/legacy.
        addCard(getString(R.string.claude_card_prompt_directo)) {
            actionButton(getString(R.string.claude_btn_send_prompt), PRIMARY) {
                runDirectPrompt()
            }
            // Confirmado contra code.claude.com/docs/en/cli-reference (2026-08-25, gap de
            // docs/modulos/CLAUDE_CODE.md lote 1): "--allow-tool <tool>"/"--deny-tool <tool>"
            // se repiten una vez por herramienta listada \u2014 se aplican al mismo prompt directo
            // de arriba, no como una sesi\u00F3n separada.
            actionButton(getString(R.string.claude_btn_tool_permissions), GHOST) {
                promptToolPermissions()
            }
        }
        addCard(getString(R.string.claude_card_sesion)) {
            actionButton(getString(R.string.claude_btn_continue_last), GHOST) {
                openClaudeHere(null, "--continue")
            }
            actionButton(getString(R.string.claude_btn_resume_session), GHOST) {
                openClaudeHere(null, "--resume")
            }
        }
        // Confirmado contra code.claude.com/docs/en/cli-reference (2026-08-19, ronda de
        // continuación de AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md): "claude doctor"
        // es diagnóstico de solo lectura (instalación/settings/Remote Control), sin sesión
        // interactiva — antes no estaba expuesto en ningún lado y el usuario tenía que saber
        // que existía y abrir la terminal a mano. "claude auth status" imprime JSON con el
        // estado de sesión (exit code 0 logueado / 1 no logueado) — mismo patrón de captura ya
        // usado por Codex (runExecCapture) en vez de abrir terminal para un output de una sola
        // pasada.
        addCard(getString(R.string.claude_card_diagnostico)) {
            actionButton(getString(R.string.claude_btn_doctor), GHOST) {
                runCaptureDialog("doctor", getString(R.string.claude_title_diagnostics))
            }
            actionButton(getString(R.string.claude_btn_auth_status), GHOST) {
                runCaptureDialog("auth status", getString(R.string.claude_title_auth))
            }
        }
        addCard(getString(R.string.claude_card_mcp)) {
            // Antes abr\u00EDa la terminal solo para correr "claude mcp list" y MOSTRAR una
            // lista \u2014 bug real reportado por el usuario (ver
            // docs/arquitectura/AUDITORIA_PANEL_MCP_UI_2026-08-19.md): "esa es la premisa
            // del app, poder hacer todo desde el apk de la interfaz [...] sin necesidad de
            // abrir la terminal". Ahora lee ~/.claude.json directo (ClaudeNative.mcpServers,
            // sin invocar el CLI) y lo muestra en un panel nativo \u2014 "mcp list" en
            // terminal sigue disponible como alternativa expl\u00EDcita dentro del panel.
            actionButton(getString(R.string.claude_btn_view_mcp_servers), GHOST) {
                showMcpPanel(
                    moduleLabel = getString(R.string.claude_module_name),
                    loadServers = { com.termux.app.util.ClaudeNative.mcpServers() },
                    onOpenTerminal = { openClaudeHere(null, "mcp list") }
                )
            }
        }
        engramSetupButton("claude-code")
        actionButton(getString(R.string.claude_btn_install_change_method), GHOST) {
            toast(getString(R.string.claude_toast_reinstalling))
            reinstallModuleService { ok ->
                toast(if (ok) getString(R.string.claude_toast_updated) else getString(R.string.claude_toast_update_failed))
            }
        }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditor\u00EDa de men\u00FAs
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // Claude Code no ten\u00EDa forma de desinstalarse desde su propia pantalla, solo desde
        // Ajustes. Bot\u00F3n suelto (no addMaintenanceCard()) para no duplicar "Instalar / cambiar
        // m\u00E9todo" de arriba, que ya cubre reinstalar/actualizar con el m\u00E9todo real.
        addCard(getString(R.string.claude_card_mantenimiento)) {
            actionButton(getString(R.string.claude_btn_uninstall), DANGER) { confirmUninstallModule() }
        }
    }

    private fun refreshEstadoPill() {
        Thread {
            val available = isTermuxBinaryAvailable("claude")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                estadoPillSlot.removeViewAt(estadoPillSlot.childCount - 1)
                estadoPillSlot.addView(pill(if (available) getString(R.string.claude_pill_ready) else getString(R.string.claude_pill_not_responding), available).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
        }.start()
    }

    // Refresca el aviso de "token OAuth fijo activo" — mismo patrón Thread/isAdded/
    // runOnUiThread que refreshEstadoPill(), pero remueve TODAS las vistas del slot en vez
    // de solo la última (acá no hay una pill fija de fondo que preservar).
    private fun refreshOauthStatus() {
        Thread {
            val active = ClaudeNative.hasOAuthToken()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                oauthStatusSlot.removeAllViews()
                if (active) {
                    oauthStatusSlot.addView(TextView(requireContext()).apply {
                        text = getString(R.string.claude_oauth_warning)
                        textSize = 13f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosAmber))
                        setPadding(dp(14), dp(4), dp(14), dp(12))
                    })
                }
            }
        }.start()
    }

    // Paso 1 del plan: diálogo con EditText para pegar el token generado a mano con
    // "claude setup-token" (flujo OAuth interactivo del propio CLI, no automatizable
    // desde acá). ClaudeNative.writeOAuthToken escribe ~/.claude_oauth_token (chmod 600) +
    // línea de export en ~/.bashrc.
    private fun promptSetOauthToken() {
        val ctx = requireContext()
        val tokenInput = EditText(ctx).apply {
            hint = getString(R.string.claude_hint_paste_token)
            inputType = InputType.TYPE_CLASS_TEXT
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.claude_title_oauth_token))
            .setMessage(getString(R.string.claude_msg_oauth_token))
            .setView(tokenInput)
            .setPositiveButton(getString(R.string.claude_btn_save)) { _, _ ->
                val token = tokenInput.text.toString().trim()
                if (token.isEmpty()) { toast(getString(R.string.claude_toast_token_empty)); return@setPositiveButton }
                Thread {
                    val ok = ClaudeNative.writeOAuthToken(token)
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        toast(if (ok) getString(R.string.claude_toast_token_saved) else getString(R.string.claude_toast_token_save_error))
                        refreshOauthStatus()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // Paso 5 del plan: borra el archivo + la línea de .bashrc, vuelve a usar las
    // credenciales de sesión normales (~/.claude/.credentials.json).
    private fun confirmClearOauthToken() {
        if (!ClaudeNative.hasOAuthToken()) { toast(getString(R.string.claude_toast_no_fixed_token)); return }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.claude_title_remove_token))
            .setMessage(getString(R.string.claude_msg_remove_token))
            .setPositiveButton(getString(R.string.claude_btn_remove)) { _, _ ->
                Thread {
                    val ok = ClaudeNative.clearOAuthToken()
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        toast(if (ok) getString(R.string.claude_toast_token_removed) else getString(R.string.claude_toast_token_remove_error))
                        refreshOauthStatus()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // "Abrir en proyecto"/"Gestionar proyectos" antes navegaban a ProjectsFragment, que
    // opera sobre una carpeta sandbox de la app (context.filesDir), completamente
    // desconectada del Termux real \u2014 los "proyectos" creados ah\u00ED no existen para
    // claude de verdad. Antes pasaban por kairos_manager.py claude projects-* (python3,
    // confirmado roto/no confiable en dispositivo real) \u2014 ahora usan ProjectsManager
    // (Kotlin nativo, symlinks v\u00EDa android.system.Os + registry con file locking real,
    // sin depender de python3 para nada de esto).
    private fun openInProject() {
        runProjectsAction({ ProjectsManager.projectsList() }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.claude_toast_error_reason, json.optString("error", getString(R.string.claude_error_unknown))))
                return@runProjectsAction
            }
            val projects = json.optJSONArray("projects")
            if (projects == null || projects.length() == 0) {
                toast(getString(R.string.claude_toast_no_projects))
                return@runProjectsAction
            }
            val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
            val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.claude_title_open_project))
                .setItems(names.toTypedArray()) { _, which ->
                    openClaudeHere(paths[which])
                }
                .setNegativeButton(getString(R.string.claude_btn_cancel), null)
                .show()
        }
    }

    // Reemplaza el "claude" a secas que había antes (y el "cd ... && claude" armado a
    // mano en openInProject) — cmd_claude open-cmd (ahora ClaudeNative.openCmd) elige el
    // comando real según el método de instalación detectado: instalación native
    // necesita "unset LD_PRELOAD" antes del binario, legacy necesita invocar node sobre
    // cli.js con DISABLE_AUTOUPDATER — un "claude" desnudo podía no arrancar en
    // ninguno de los dos casos, que es exactamente el síntoma reportado por el usuario
    // ("ni siquiera claude code funciona").
    private fun openClaudeHere(project: String?, extraArgs: String? = null) {
        Thread {
            val json = ClaudeNative.openCmd(project, extraArgs)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (json.optBoolean("ok", false)) {
                    launchTerminalCommand(json.optString("command"))
                } else {
                    toast(getString(R.string.claude_toast_error_reason, json.optString("error", getString(R.string.claude_error_unknown))))
                }
            }
        }.start()
    }

    // Escapado mínimo de comilla simple para línea de comandos — mismo patrón que
    // CliToolFragment.shellEscape() (los 11 CLIs "chicos" agregados esta misma sesión).
    private fun shellEscape(text: String): String = text.replace("'", "'\\''")

    private fun runDirectPrompt() {
        val ctx = requireContext()
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.claude_hint_prompt)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.claude_title_direct_prompt))
            .setView(promptInput)
            .setPositiveButton(getString(R.string.claude_btn_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.claude_toast_prompt_empty)); return@setPositiveButton }
                askModelThenSend(prompt)
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // Aliases reales confirmados en code.claude.com/docs/en/cli-reference: sonnet/opus/
    // haiku/fable — "Default" omite --model y usa lo que ya esté configurado en settings.json.
    private fun askModelThenSend(prompt: String) {
        val models = arrayOf(getString(R.string.claude_model_default), "sonnet", "opus", "haiku", "fable")
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.claude_title_model))
            .setItems(models) { _, which ->
                val modelArg = if (which == 0) "" else " --model ${models[which]}"
                askPermissionModeThenSend(prompt, modelArg)
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // Valores reales de --permission-mode confirmados contra code.claude.com/docs/en/cli-reference
    // (2026-08-19): default/acceptEdits/plan/auto/dontAsk/bypassPermissions ("manual" es solo un
    // alias de "default" en la UI, se omite acá para no duplicar la opción). Sin este selector el
    // único modo posible desde la app era el default heredado de settings.json — gap documentado
    // en AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md.
    private fun askPermissionModeThenSend(prompt: String, modelArg: String) {
        val modes = arrayOf(
            getString(R.string.claude_permission_mode_default), "default", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.claude_title_permission_mode))
            .setItems(modes) { _, which ->
                val permArg = if (which == 0) "" else " --permission-mode ${modes[which]}"
                val escaped = shellEscape(prompt)
                openClaudeHere(null, "-p '$escaped'$modelArg$permArg${buildToolPermissionArgs()}")
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // Convierte allowToolsRaw/denyToolsRaw (texto separado por comas, cargado desde
    // promptToolPermissions()) en "--allow-tool 'x' --allow-tool 'y' --deny-tool 'z'" — un flag
    // repetido por herramienta, formato real confirmado contra
    // code.claude.com/docs/en/cli-reference. Devuelve "" si el usuario no configuró nada.
    private fun buildToolPermissionArgs(): String {
        fun parse(raw: String) = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val allowArgs = parse(allowToolsRaw).joinToString("") { " --allow-tool '${shellEscape(it)}'" }
        val denyArgs = parse(denyToolsRaw).joinToString("") { " --deny-tool '${shellEscape(it)}'" }
        return allowArgs + denyArgs
    }

    // Diálogo con 2 EditText (permitir/denegar), separados por coma — mismo criterio simple
    // que el resto del Fragment (sin selector de herramientas conocidas de antemano, porque la
    // lista real de nombres de herramientas depende de qué MCP/servers tenga configurados el
    // usuario, no es un catálogo fijo). El texto se guarda tal cual en los campos de instancia
    // y se re-usa en cada envío de prompt hasta que el usuario lo cambie o cierre la app.
    private fun promptToolPermissions() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val allowInput = EditText(ctx).apply {
            hint = getString(R.string.claude_hint_allow_tools)
            setText(allowToolsRaw)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        val denyInput = EditText(ctx).apply {
            hint = getString(R.string.claude_hint_deny_tools)
            setText(denyToolsRaw)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, dp(4))
        }
        container.addView(allowInput)
        container.addView(denyInput)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.claude_title_tool_permissions))
            .setMessage(getString(R.string.claude_msg_tool_permissions))
            .setView(container)
            .setPositiveButton(getString(R.string.claude_btn_save)) { _, _ ->
                allowToolsRaw = allowInput.text.toString().trim()
                denyToolsRaw = denyInput.text.toString().trim()
                toast(getString(R.string.claude_toast_tool_permissions_saved))
            }
            .setNegativeButton(getString(R.string.claude_btn_cancel), null)
            .show()
    }

    // Captura genérica de un subcomando de una sola pasada (doctor, auth status) — mismo patrón
    // que CodexFragment.runExecCapture: corre en background con ManagerNativeUtils.runShell (PATH
    // de Termux real) en vez de abrir una terminal solo para mostrar output, guard estándar de
    // Fragment-adjunto (kotlin-kairos-android-patterns.md). Reusa ClaudeNative.openCmd para el
    // mismo comando/entorno detectado (native vs legacy) que ya usa "Abrir en directorio raíz".
    private fun runCaptureDialog(subcommand: String, title: String) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(getString(R.string.claude_msg_running))
            .setCancelable(false)
            .show()
        Thread {
            val json = ClaudeNative.openCmd(null, subcommand)
            if (!json.optBoolean("ok", false)) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    progress.dismiss()
                    toast(getString(R.string.claude_toast_error_reason, json.optString("error", getString(R.string.claude_error_unknown))))
                }
                return@Thread
            }
            // 30s -> 60s (2026-08-25, bug real reportado "claude doctor falla"): además del fix
            // de DISABLE_AUTOUPDATER/DISABLE_UPDATES en ClaudeNative.openCmd(), "claude doctor"
            // hace chequeos propios (settings, permisos, posible red) que pueden pasar de 30s en
            // una conexión móvil lenta — mismo margen de seguridad que ya usa Codex (180s) pero
            // proporcional a que esto es diagnóstico, no una llamada real a un modelo.
            val (rc, out, err) = com.termux.app.util.ManagerNativeUtils.runShell(json.optString("command"), 60)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                progress.dismiss()
                val body = (if (out.isNotBlank()) out else err).ifBlank { getString(R.string.claude_output_empty_with_code, rc) }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.claude_btn_close), null)
                    .show()
            }
        }.start()
    }

    // "Gestionar proyectos" (bot\u00f3n de arriba) ahora usa el men\u00fa compartido
    // (com.termux.app.util.showProjectsMenu, ProjectActions.kt) \u2014 reemplaza el duplicado
    // local que hab\u00eda ac\u00e1 (symlink solo desde Download, sin copiar/sincronizar/ayuda) por
    // el mismo men\u00fa completo que ya usan Codex/OpenCode/Antigravity/OpenClaw/Hermes/
    // GenericModuleFragment (2026-08-14, pedido expl\u00edcito del usuario de unificar
    // symlink+copiar+sincronizar en todos los m\u00f3dulos que usan proyectos).
}
