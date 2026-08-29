package com.termux.app.ui

import android.text.InputType
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
import com.termux.app.util.ProjectsManager
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.app.util.promptOpenLocation
import com.termux.app.util.runProjectsAction
import com.termux.app.util.showProjectsMenu
import com.termux.app.util.kairosThemeColor

class CodexFragment : BaseModuleFragment() {
    override fun getModuleId() = "codex"
    override fun getModuleName() = "Codex CLI"

    private lateinit var estadoPillSlot: LinearLayout

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        addCard(getString(R.string.codex_card_estado)) {
            addView(infoRow(getString(R.string.codex_label_canal), "termux"))
            // codex.sh (_update_reg, prefijo "codex.") escribe codex.version al registry en
            // cada instalación — este valor estaba hardcodeado en "—" sin leerlo nunca (bug
            // confirmado en auditoría 2026-08-01, ver docs/humano/humano34.md).
            val codexVersion = com.termux.app.data.ModuleRegistry(requireContext()).load().get("codex.version")
            addView(infoRow(getString(R.string.codex_label_version), codexVersion?.ifBlank { getString(R.string.codex_dash) } ?: getString(R.string.codex_dash)))
            estadoPillSlot = LinearLayout(requireContext()).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.codex_label_estado)
                    textSize = 13f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
                })
                addView(pill(getString(R.string.codex_dash), false).also {
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
                    text = getString(R.string.codex_label_terminal)
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
        // corrección explícita del usuario (ver mismo comentario en ClaudeFragment/
        // AntigravityFragment — la ronda anterior lo dejó afuera acá también).
        actionButton(getString(R.string.codex_btn_open_terminal), GHOST) {
            promptOpenLocation(
                onDefault = { launchTerminalCommand("codex") },
                onChooseFolder = { path -> launchTerminalCommand("cd '$path' && codex") }
            )
        }
        actionButton(getString(R.string.codex_btn_open_in_project), GHOST) {
            openInProject()
        }
        // Nunca había una opción para importar/sincronizar/symlink acá — el usuario
        // reportó explícitamente que Codex (y Antigravity) se quedaron atrás de
        // Claude/OpenCode en esto. Mismo patrón de UI que ClaudeFragment.manageProjects(),
        // sobre la misma carpeta compartida ~/proyectos (ProjectsManager.PROJECTS_DIR).
        actionButton(getString(R.string.codex_btn_manage_projects), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }
        actionButton(getString(R.string.codex_btn_login), GHOST) {
            launchTerminalCommand("codex login")
        }
        // Confirmado contra learn.chatgpt.com/docs/developer-commands (surface=cli, doc real
        // del proyecto openai/codex que @mmmbuto/codex-cli-termux empaqueta para Bionic/ARM64
        // — este fork no tiene doc propio de flags, se asume paridad con el CLI real que
        // envuelve, mismo criterio ya usado en CliToolFragment para otros CLIs "fork"):
        // "codex exec" (alias "codex e") corre no interactivo, imprime a stdout y respeta
        // "--model"/"-m"; "codex resume" continúa una sesión previa por ID o la última.
        addCard(getString(R.string.codex_card_prompt_directo)) {
            actionButton(getString(R.string.codex_btn_send_prompt), PRIMARY) {
                runDirectPrompt()
            }
        }
        addCard(getString(R.string.codex_card_sesion)) {
            actionButton(getString(R.string.codex_btn_resume_session), GHOST) {
                launchTerminalCommand("codex resume --last")
            }
        }
        engramSetupButton("codex")
        actionButton(getString(R.string.codex_btn_install_change_channel), GHOST) {
            toast(getString(R.string.codex_reinstalling))
            reinstallModuleService { ok ->
                toast(if (ok) getString(R.string.codex_updated) else getString(R.string.codex_update_failed))
            }
        }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditoría de menús
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // Codex CLI no tenía forma de desinstalarse desde su propia pantalla. Botón suelto
        // (no addMaintenanceCard()) para no duplicar "Instalar / cambiar canal" de arriba.
        addCard(getString(R.string.codex_card_mantenimiento)) {
            actionButton(getString(R.string.codex_btn_uninstall), DANGER) { confirmUninstallModule() }
        }
    }

    private fun refreshEstadoPill() {
        Thread {
            val available = isTermuxBinaryAvailable("codex")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                estadoPillSlot.removeViewAt(estadoPillSlot.childCount - 1)
                estadoPillSlot.addView(pill(if (available) getString(R.string.codex_status_ready) else getString(R.string.codex_status_not_responding), available).also {
                    (it.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        gravity = android.view.Gravity.END
                    }
                })
            }
        }.start()
    }

    // submenu_codex() de termux-ai-stack (menu_nativo.sh) tiene "[2] Abrir en proyecto"
    // (lista ~/proyectos con `ls -1`, sin registry de origen) — antes se listaba el
    // directorio directo con File sin pasar por ProjectsManager. Ahora usa
    // ProjectsManager.projectsList() (misma carpeta ~/proyectos, mismo prefijo de
    // registry "project_origin" que Claude/OpenCode/Antigravity) para que el origen de
    // cada proyecto quede consistente entre todos los CLIs que comparten la carpeta.
    // Escapado mínimo de comilla simple — mismo patrón que CliToolFragment.shellEscape().
    private fun shellEscape(text: String): String = text.replace("'", "'\\''")

    private fun runDirectPrompt() {
        val ctx = requireContext()
        val promptInput = EditText(ctx).apply {
            hint = getString(R.string.codex_prompt_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.codex_dialog_title_prompt))
            .setView(promptInput)
            .setPositiveButton(getString(R.string.codex_dialog_send)) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) { toast(getString(R.string.codex_prompt_empty)); return@setPositiveButton }
                askModelThenSend(prompt)
            }
            .setNegativeButton(getString(R.string.codex_dialog_cancel), null)
            .show()
    }

    // Sin alias fijos documentados (a diferencia de Claude Code) — "--model"/"-m" real
    // acepta el nombre de modelo literal (ej. "gpt-5.6-terra"), texto libre con default vacío.
    private fun askModelThenSend(prompt: String) {
        val ctx = requireContext()
        val modelInput = EditText(ctx).apply {
            hint = getString(R.string.codex_model_hint)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.codex_dialog_title_model))
            .setView(modelInput)
            .setPositiveButton(getString(R.string.codex_dialog_continue)) { _, _ ->
                val model = modelInput.text.toString().trim()
                askSandboxThenSend(prompt, model)
            }
            .setNegativeButton(getString(R.string.codex_dialog_cancel), null)
            .show()
    }

    // Confirmado contra developers.openai.com/codex/concepts/sandboxing y
    // developers.openai.com/codex/agent-approvals-security (2026-08-19, ronda de continuación
    // de la auditoría terminal→UI): "codex exec" nunca pregunta en modo no interactivo — el
    // control real de seguridad es "--sandbox" (qué puede tocar el proceso) más
    // "--ask-for-approval" (cuándo Codex se detiene a pedir permiso). Sin flags, "codex exec"
    // usa el default del perfil configurado (normalmente "workspace-write"/"on-request"), pero
    // antes la app no daba forma de elegir explícitamente ninguno de los dos — el usuario tenía
    // que abrir terminal para pasar los flags a mano. Selector nativo de una sola pasada
    // (AlertDialog.setSingleChoiceItems), sin abrir terminal.
    // Listas construidas en el momento de uso (no como `val` de clase): getString() necesita
    // el Fragment adjunto a un Context, y una propiedad de clase se inicializaría en el
    // constructor, antes de onAttach() — evaluarlas recién dentro de las funciones de abajo
    // evita ese crash potencial.
    private fun sandboxModes() = listOf(
        getString(R.string.codex_sandbox_default) to "",
        getString(R.string.codex_sandbox_readonly) to "read-only",
        getString(R.string.codex_sandbox_workspace_write) to "workspace-write",
        getString(R.string.codex_sandbox_danger_full_access) to "danger-full-access",
    )
    private fun approvalModes() = listOf(
        getString(R.string.codex_sandbox_default) to "",
        getString(R.string.codex_approval_untrusted) to "untrusted",
        getString(R.string.codex_approval_on_request) to "on-request",
        getString(R.string.codex_approval_never) to "never",
    )

    private fun askSandboxThenSend(prompt: String, model: String) {
        var selectedSandbox = 0
        val sandboxModes = sandboxModes()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.codex_dialog_title_sandbox))
            .setSingleChoiceItems(sandboxModes.map { it.first }.toTypedArray(), 0) { _, which ->
                selectedSandbox = which
            }
            .setPositiveButton(getString(R.string.codex_dialog_continue)) { _, _ ->
                askApprovalThenSend(prompt, model, sandboxModes[selectedSandbox].second)
            }
            .setNegativeButton(getString(R.string.codex_dialog_cancel), null)
            .show()
    }

    private fun askApprovalThenSend(prompt: String, model: String, sandbox: String) {
        var selectedApproval = 0
        val approvalModes = approvalModes()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.codex_dialog_title_approval))
            .setSingleChoiceItems(approvalModes.map { it.first }.toTypedArray(), 0) { _, which ->
                selectedApproval = which
            }
            .setPositiveButton(getString(R.string.codex_dialog_send)) { _, _ ->
                runExecCapture(prompt, model, sandbox, approvalModes[selectedApproval].second)
            }
            .setNegativeButton(getString(R.string.codex_dialog_cancel), null)
            .show()
    }

    // Confirmado contra learn.chatgpt.com/docs/non-interactive-mode (2026-08-19): "codex exec"
    // es un modo no-interactivo real pensado para scripts/CI — sin flags especiales imprime el
    // mensaje final del agente a stdout y el progreso a stderr, sin ningún TUI de por medio.
    // Antes esto SIEMPRE abría una terminal nueva (launchTerminalCommand) para un comando que
    // la propia doc describe como apto para correr embebido — auditoría de esta ronda (parte
    // del pedido "cuales opciones se abren en terminal pudiendo ser nativas"). Se corre en
    // background con ManagerNativeUtils.runShell (aplica el mismo entorno/PATH de Termux que
    // el resto de los *Native.kt del proyecto) y el resultado se muestra en un diálogo nativo
    // en vez de una sesión de terminal — mismo guard de Fragment-adjunto que el resto del
    // proyecto (kotlin-kairos-android-patterns.md). Timeout largo (180s) porque es una llamada
    // real a un modelo, no un comando instantáneo.
    //
    // "--output-last-message <archivo>" (confirmado en developers.openai.com/codex/cli/reference,
    // ronda de continuación 2026-08-19) escribe SOLO el mensaje final del agente a un archivo,
    // separado del ruido de progreso que "codex exec" manda a stdout/stderr — se usa en vez de
    // parsear stdout crudo para que el diálogo de resultado muestre la respuesta real, no logs
    // de progreso. Se pidió confirmar si "--json" servía para lo mismo: sí existe y emite un
    // stream de eventos JSONL, pero requeriría un parser de eventos dedicado (out of scope de
    // un cambio acotado) — "--output-last-message" es la vía oficial más simple para el mismo
    // resultado que ya perseguía este diálogo.
    private fun runExecCapture(prompt: String, model: String, sandbox: String, approval: String) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.codex_dialog_title_codex_cli))
            .setMessage(getString(R.string.codex_executing_prompt))
            .setCancelable(false)
            .show()
        val modelArg = if (model.isEmpty()) "" else " -m '${shellEscape(model)}'"
        val sandboxArg = if (sandbox.isEmpty()) "" else " --sandbox $sandbox"
        val approvalArg = if (approval.isEmpty()) "" else " --ask-for-approval $approval"
        val outFile = "\$HOME/.kairos_codex_last_message.txt"
        val cmd = "rm -f $outFile; codex exec --output-last-message $outFile" +
            "$modelArg$sandboxArg$approvalArg '${shellEscape(prompt)}'"
        Thread {
            val (rc, out, err) = com.termux.app.util.ManagerNativeUtils.runShell(cmd, 180)
            val (_, lastMessage, _) = com.termux.app.util.ManagerNativeUtils.runShell(
                "cat $outFile 2>/dev/null", 5
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                progress.dismiss()
                val body = when {
                    rc != 0 -> getString(R.string.codex_error_with_code, rc, err.ifBlank { out })
                    lastMessage.isNotBlank() -> lastMessage
                    else -> out.ifBlank { getString(R.string.codex_no_output) }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.codex_dialog_title_result))
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.codex_dialog_close), null)
                    .show()
            }
        }.start()
    }

    private fun openInProject() {
        runProjectsAction({ ProjectsManager.projectsList() }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.codex_error_prefix, json.optString("error", getString(R.string.codex_error_unknown))))
                return@runProjectsAction
            }
            val projects = json.optJSONArray("projects")
            if (projects == null || projects.length() == 0) {
                toast(getString(R.string.codex_no_projects))
                return@runProjectsAction
            }
            val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
            val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.codex_dialog_title_open_project))
                .setItems(names.toTypedArray()) { _, which ->
                    launchTerminalCommand("cd '${paths[which]}' && codex")
                }
                .setNegativeButton(getString(R.string.codex_dialog_cancel), null)
                .show()
        }
    }

}
