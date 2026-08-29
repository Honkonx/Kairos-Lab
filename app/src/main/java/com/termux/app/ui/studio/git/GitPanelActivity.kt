package com.termux.app.ui.studio.git

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.databinding.StudioActivityGitPanelBinding

/**
 * Panel "Git" del IDE -- rama actual, archivos modificados/nuevos/borrados/renombrados (via
 * `git status --porcelain`, parseado en [GitBridge]), commit + push/pull con confirmación
 * explícita, y un log corto de los últimos commits. Todo corre el `git` real instalado dentro
 * de la sesión de Termux/Kairos -- este panel no reimplementa ni simula ningún comportamiento
 * de git, solo arma comandos reales y muestra su salida real (ver [GitBridge]).
 *
 * Mismo criterio de confirmación explícita que usa el resto de Kairos para acciones que tocan
 * un remoto/estado persistente (ver `ProjectActions`/toggles de módulos en la app principal) --
 * push y pull piden confirmación antes de ejecutar, commit no (solo toca el repo local).
 */
class GitPanelActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_PATH = "project_path"
    }

    private lateinit var binding: StudioActivityGitPanelBinding
    private var projectPath: String? = null
    private var operationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityGitPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)

        binding.commitButton.setOnClickListener { onCommitClicked() }
        binding.pushButton.setOnClickListener { confirmAndPush() }
        binding.pullButton.setOnClickListener { confirmAndPull() }
    }

    override fun onStart() {
        super.onStart()
        refreshAvailability()
    }

    private fun refreshAvailability() {
        val path = projectPath

        if (path.isNullOrEmpty()) {
            showBanner(getString(R.string.git_error_no_project), null)
        } else {
            hideBanner()
            loadStatusAndLog(path)
        }

        setControlsEnabled(enabled = !path.isNullOrEmpty())
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val canInteract = enabled && !operationInProgress
        binding.commitButton.isEnabled = canInteract
        binding.pushButton.isEnabled = canInteract
        binding.pullButton.isEnabled = canInteract
        binding.commitMessageInput.isEnabled = canInteract
    }

    private fun showBanner(message: String, actionLabel: String?) {
        binding.statusBanner.visibility = android.view.View.VISIBLE
        binding.statusBannerText.text = message
        if (actionLabel != null) {
            binding.statusBannerAction.visibility = android.view.View.VISIBLE
            binding.statusBannerAction.text = actionLabel
        } else {
            binding.statusBannerAction.visibility = android.view.View.GONE
        }
    }

    private fun hideBanner() {
        binding.statusBanner.visibility = android.view.View.GONE
    }

    private fun loadStatusAndLog(path: String) {
        binding.branchLabel.text = getString(R.string.git_branch_placeholder)
        binding.changesText.text = getString(R.string.git_loading)
        binding.logText.text = getString(R.string.git_loading)

        GitBridge.status(path) { result ->
            if (isFinishing || isDestroyed) return@status
            when (result) {
                is GitBridge.GitResult.Success -> renderStatus(result.value)
                is GitBridge.GitResult.Failure -> {
                    binding.branchLabel.text = getString(R.string.git_branch_placeholder)
                    binding.changesText.text = result.message
                }
            }
        }

        GitBridge.log(path) { result ->
            if (isFinishing || isDestroyed) return@log
            when (result) {
                is GitBridge.GitResult.Success -> renderLog(result.value)
                is GitBridge.GitResult.Failure -> binding.logText.text = result.message
            }
        }
    }

    private fun renderStatus(status: GitBridge.StatusResult) {
        binding.branchLabel.text = getString(R.string.git_branch_label, status.branch)
        binding.changesText.text = if (status.changes.isEmpty()) {
            getString(R.string.git_no_changes)
        } else {
            status.changes.joinToString(separator = "\n") { change ->
                "${change.statusLabel.padEnd(12)} ${change.path}"
            }
        }
    }

    private fun renderLog(entries: List<GitBridge.LogEntry>) {
        binding.logText.text = if (entries.isEmpty()) {
            getString(R.string.git_no_commits)
        } else {
            entries.joinToString(separator = "\n") { entry ->
                "${entry.shortHash}  ${entry.date}  ${entry.subject}"
            }
        }
    }

    private fun onCommitClicked() {
        val path = projectPath ?: return
        val message = binding.commitMessageInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.git_commit_empty_message, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        runOperation {
            GitBridge.commit(path, message) { result ->
                onOperationFinished(result) {
                    binding.commitMessageInput.text?.clear()
                    loadStatusAndLog(path)
                }
            }
        }
    }

    private fun confirmAndPush() {
        val path = projectPath ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.git_push_confirm_title)
            .setMessage(R.string.git_push_confirm_message)
            .setPositiveButton(R.string.git_confirm_yes) { _, _ ->
                runOperation {
                    GitBridge.push(path) { result ->
                        onOperationFinished(result) { loadStatusAndLog(path) }
                    }
                }
            }
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .show()
    }

    private fun confirmAndPull() {
        val path = projectPath ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.git_pull_confirm_title)
            .setMessage(R.string.git_pull_confirm_message)
            .setPositiveButton(R.string.git_confirm_yes) { _, _ ->
                runOperation {
                    GitBridge.pull(path) { result ->
                        onOperationFinished(result) { loadStatusAndLog(path) }
                    }
                }
            }
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .show()
    }

    private fun runOperation(block: () -> Unit) {
        operationInProgress = true
        setControlsEnabled(enabled = true)
        binding.changesText.text = getString(R.string.git_operation_running)
        block()
    }

    private fun onOperationFinished(result: GitBridge.GitResult<String>, onSuccess: () -> Unit) {
        if (isFinishing || isDestroyed) return
        operationInProgress = false
        setControlsEnabled(enabled = true)
        when (result) {
            is GitBridge.GitResult.Success -> {
                android.widget.Toast.makeText(this, result.value, android.widget.Toast.LENGTH_SHORT).show()
                onSuccess()
            }
            is GitBridge.GitResult.Failure -> AlertDialog.Builder(this)
                .setTitle(R.string.ai_error_title)
                .setMessage(result.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
