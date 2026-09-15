package com.termux.app.ui

import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProjectsManager
import com.termux.app.util.shellQuote
import com.termux.app.util.showProjectsMenu
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Módulo de propósito general Git/GitHub — extraído de ExpoFragment (2026-09-08, pedido
 * explícito del usuario: "sobre expo creo que podemos sacar git y github a un modulo
 * independiente, crear un modulo llamado git/github"). A diferencia del botón viejo de Expo
 * (atado al `.eas_active_project` de ESE módulo), esta pantalla opera sobre CUALQUIER carpeta
 * de `~/proyectos` — reusa `showProjectsMenu` (ProjectActions.kt), el mismo menú de
 * symlink/copiar/sincronizar/eliminar que ya usan Claude/Codex/OpenCode/Antigravity/OpenClaw,
 * en vez de reinventar un selector de carpetas propio.
 *
 * Ejecuta git/gh vía ManagerNativeUtils.runShell() (bash -c + PATH real de Termux) — a
 * diferencia del runCommand() a medida que tenía ExpoFragment (con LD_PRELOAD explícito, ver
 * su comentario), git/gh son binarios nativos instalados por `pkg install` (no shims npm con
 * shebang "#!/usr/bin/env node"), así que no necesitan ese workaround — mismo patrón simple
 * que ClaudeFragment.runCaptureDialog()/runShell() ya usa para binarios pkg normales.
 */
class GitFragment : BaseModuleFragment() {
    override fun getModuleId() = "git"
    override fun getModuleName() = getString(R.string.git_module_name)

    // Mismo patrón que ExpoFragment.easProjectFile — un solo "proyecto activo" recordado entre
    // pantallas, pero acá vive en su propio archivo (no comparte estado con Expo).
    private val activeProjectFile get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".git_active_project")

    private var gitVersionValue: TextView? = null
    private var ghVersionValue: TextView? = null
    private var activeProjectValue: TextView? = null

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }

        addCard(getString(R.string.git_card_estado)) {
            addView(infoRow(getString(R.string.git_label_git_version), getString(R.string.git_placeholder_dash)).also { gitVersionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.git_label_gh_version), getString(R.string.git_placeholder_dash)).also { ghVersionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.git_label_active_project), getString(R.string.git_placeholder_dash)).also { activeProjectValue = it.valueTextView() })
        }

        actionButton(getString(R.string.git_btn_manage_projects), PRIMARY) {
            showProjectsMenu(
                onToast = { toast(it) },
                onLaunchInProject = { path -> setActiveProject(path) }
            )
        }
        actionButton(getString(R.string.git_btn_clone), GHOST) { promptCloneRepo() }
        actionButton(getString(R.string.git_btn_status), GHOST) { runGitStatus() }
        actionButton(getString(R.string.git_btn_push), GHOST) { promptCommitAndPush() }
        actionButton(getString(R.string.git_btn_pull), GHOST) { runGitPull() }

        // Acciones básicas de GitHub CLI (gh) — pendiente/anotado a propósito: gestión de
        // issues, más acciones de PR (crear/mergear/revisar), y clonar directo desde un repo
        // propio del usuario listado por "gh repo list" en vez de pedir la URL a mano. No se
        // construyó esta ronda para no sobre-construir de entrada (pedido explícito del
        // usuario) — "gh" ya soporta todo eso, solo falta la pantalla.
        addCard(getString(R.string.git_card_github)) {
            actionButton(getString(R.string.git_btn_gh_auth_status), GHOST) { runGhAuthStatus() }
            actionButton(getString(R.string.git_btn_gh_login), GHOST) { launchTerminalCommand("gh auth login") }
            actionButton(getString(R.string.git_btn_gh_repo_create), GHOST) { promptRepoCreate() }
            actionButton(getString(R.string.git_btn_gh_pr_list), GHOST) { runGhPrList() }
        }

        addMaintenanceCard()
        refreshInfo()
    }

    private fun activeProjectPath(): String? =
        if (activeProjectFile.exists()) {
            activeProjectFile.readText().trim().takeIf { it.isNotBlank() && File(it).isDirectory }
        } else null

    private fun setActiveProject(path: String) {
        activeProjectFile.writeText(path)
        toast(getString(R.string.git_msg_project_set, File(path).name))
        refreshInfo()
    }

    private fun requireActiveProjectOrWarn(): String? {
        val proj = activeProjectPath()
        if (proj == null) toast(getString(R.string.git_toast_no_active_project))
        return proj
    }

    private fun refreshInfo() {
        Thread {
            val (gitRc, gitOut, _) = ManagerNativeUtils.runShell("git --version", 10)
            val (ghRc, ghOut, _) = ManagerNativeUtils.runShell("gh --version", 10)
            val gitVer = gitOut.lineSequence().firstOrNull()?.takeIf { gitRc == 0 && it.isNotBlank() }
            val ghVer = ghOut.lineSequence().firstOrNull()?.takeIf { ghRc == 0 && it.isNotBlank() }
            val proj = activeProjectPath()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                gitVersionValue?.text = gitVer ?: getString(R.string.git_placeholder_dash)
                ghVersionValue?.text = ghVer ?: getString(R.string.git_error_gh_not_installed)
                activeProjectValue?.text = proj?.let { File(it).name } ?: getString(R.string.git_placeholder_dash)
            }
        }.start()
    }

    // Captura genérica de un comando de una sola pasada — mismo patrón que
    // ClaudeFragment.runCaptureDialog(): diálogo "Ejecutando…" mientras corre en background,
    // reemplazado por un diálogo de resultado al terminar. Refresca la card de estado al
    // cerrar por si el comando cambió algo relevante (ej. push cambia el estado del repo).
    private fun runCaptureAction(title: String, cmd: String, timeoutSec: Long = 30) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(getString(R.string.git_msg_running))
            .setCancelable(false)
            .show()
        Thread {
            val (rc, out, err) = ManagerNativeUtils.runShell(cmd, timeoutSec)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                progress.dismiss()
                val body = (if (out.isNotBlank()) out else err).ifBlank { getString(R.string.git_output_empty_with_code, rc) }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(getString(R.string.git_btn_close), null)
                    .show()
                refreshInfo()
            }
        }.start()
    }

    private fun runGitStatus() {
        val proj = requireActiveProjectOrWarn() ?: return
        runCaptureAction(getString(R.string.git_title_status), "cd ${shellQuote(proj)} && git status", 15)
    }

    private fun runGitPull() {
        val proj = requireActiveProjectOrWarn() ?: return
        runCaptureAction(getString(R.string.git_title_pull), "cd ${shellQuote(proj)} && git pull", 60)
    }

    // Portado de ExpoFragment.buildGitPushJson() — mismo comportamiento (add . + commit +
    // push), ";" en vez de "&&" entre commit y push a propósito: un commit vacío (sin cambios
    // nuevos desde el último push) falla con exit != 0, pero igual queremos intentar el push
    // por si hay commits locales previos sin subir todavía.
    private fun promptCommitAndPush() {
        val proj = requireActiveProjectOrWarn() ?: return
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            hint = getString(R.string.git_hint_commit_message)
            setText(getString(R.string.git_default_commit_message))
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.git_title_push))
            .setView(edit)
            .setPositiveButton(getString(R.string.git_btn_push_confirm)) { _, _ ->
                val msg = edit.text.toString().trim().ifBlank { getString(R.string.git_default_commit_message) }
                val cmd = "cd ${shellQuote(proj)} && git add . && git commit -m ${shellQuote(msg)} ; git push"
                runCaptureAction(getString(R.string.git_title_push), cmd, 60)
            }
            .setNegativeButton(getString(R.string.git_btn_cancel), null)
            .show()
    }

    // Clona hacia la carpeta compartida ~/proyectos (ProjectsManager.PROJECTS_DIR) — el mismo
    // directorio que showProjectsMenu()/Claude/Codex/OpenCode ya usan, así que un repo recién
    // clonado acá aparece de inmediato como proyecto elegible en "Gestionar proyectos" de
    // cualquier CLI, sin un paso extra de symlink/importar.
    private fun promptCloneRepo() {
        val ctx = requireContext()
        val edit = EditText(ctx).apply { hint = getString(R.string.git_hint_repo_url) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.git_title_clone))
            .setView(edit)
            .setPositiveButton(getString(R.string.git_btn_clone_confirm)) { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                val dest = ProjectsManager.PROJECTS_DIR.absolutePath
                val cmd = "mkdir -p ${shellQuote(dest)} && cd ${shellQuote(dest)} && git clone ${shellQuote(url)}"
                runCaptureAction(getString(R.string.git_title_clone), cmd, 120)
            }
            .setNegativeButton(getString(R.string.git_btn_cancel), null)
            .show()
    }

    private fun runGhAuthStatus() {
        runCaptureAction(getString(R.string.git_btn_gh_auth_status), "gh auth status", 15)
    }

    // Prompt de 2 pasos (nombre → visibilidad) — mismo criterio simple que el resto del
    // Fragment, sin selector de organización/plantilla (gh repo create soporta más flags, ver
    // comentario "pendiente" en buildContent()). Si hay un proyecto activo, crea el repo A
    // PARTIR de esa carpeta (--source=. --remote=origin --push, deja el remoto conectado y
    // sube lo que ya hay); si no hay proyecto activo, crea un repo vacío en GitHub.
    private fun promptRepoCreate() {
        val ctx = requireContext()
        val nameEdit = EditText(ctx).apply { hint = getString(R.string.git_hint_repo_name) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.git_title_repo_create))
            .setView(nameEdit)
            .setPositiveButton(getString(R.string.git_btn_next)) { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) {
                    toast(getString(R.string.git_toast_repo_name_required))
                    return@setPositiveButton
                }
                AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.git_title_repo_visibility))
                    .setItems(arrayOf(getString(R.string.git_visibility_public), getString(R.string.git_visibility_private))) { _, which ->
                        val visibilityFlag = if (which == 0) "--public" else "--private"
                        val proj = activeProjectPath()
                        val cmd = if (proj != null) {
                            "cd ${shellQuote(proj)} && gh repo create ${shellQuote(name)} $visibilityFlag --source=. --remote=origin --push"
                        } else {
                            "gh repo create ${shellQuote(name)} $visibilityFlag"
                        }
                        runCaptureAction(getString(R.string.git_title_repo_create), cmd, 60)
                    }
                    .setNegativeButton(getString(R.string.git_btn_cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.git_btn_cancel), null)
            .show()
    }

    private fun runGhPrList() {
        val proj = requireActiveProjectOrWarn() ?: return
        runCaptureAction(getString(R.string.git_btn_gh_pr_list), "cd ${shellQuote(proj)} && gh pr list", 30)
    }
}
