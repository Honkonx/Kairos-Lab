package com.termux.app.ui.studio.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.databinding.StudioActivityGitPanelBinding
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var githubAuthPrefs: GitHubAuthPrefs
    private var projectPath: String? = null
    private var operationInProgress = false
    /** Cancela el polling del diálogo de Device Flow en curso, si hay uno (ver [showGitHubConnectDialog]). */
    private var deviceFlowCancelled: AtomicBoolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityGitPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        githubAuthPrefs = GitHubAuthPrefs(this)
        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)

        binding.commitButton.setOnClickListener { onCommitClicked() }
        binding.pushButton.setOnClickListener { confirmAndPush() }
        binding.pullButton.setOnClickListener { confirmAndPull() }
        binding.stashButton.setOnClickListener { confirmAndStash() }
        binding.stashPopButton.setOnClickListener { confirmAndStashPop() }
        binding.githubConnectButton.setOnClickListener { onGithubConnectClicked() }
    }

    override fun onStart() {
        super.onStart()
        refreshAvailability()
        refreshGithubStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceFlowCancelled?.set(true)
    }

    // ── Conectar/desconectar GitHub (Device Flow) ──────────────────────────────────────

    private fun refreshGithubStatus() {
        if (githubAuthPrefs.hasToken()) {
            val login = githubAuthPrefs.getLogin()
            binding.githubStatusText.text = if (login.isNullOrBlank()) {
                getString(R.string.git_github_connected_no_login)
            } else {
                getString(R.string.git_github_connected, login)
            }
            binding.githubConnectButton.text = getString(R.string.git_github_disconnect_button)
        } else {
            binding.githubStatusText.text = getString(R.string.git_github_not_connected)
            binding.githubConnectButton.text = getString(R.string.git_github_connect_button)
        }
    }

    private fun onGithubConnectClicked() {
        if (githubAuthPrefs.hasToken()) {
            confirmAndDisconnectGithub()
        } else {
            startGithubDeviceFlow()
        }
    }

    private fun confirmAndDisconnectGithub() {
        AlertDialog.Builder(this)
            .setTitle(R.string.git_github_disconnect_confirm_title)
            .setMessage(R.string.git_github_disconnect_confirm_message)
            .setPositiveButton(R.string.git_confirm_yes) { _, _ ->
                githubAuthPrefs.clearToken()
                refreshGithubStatus()
            }
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .show()
    }

    private fun startGithubDeviceFlow() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.git_github_device_dialog_title)
            .setMessage(R.string.git_github_device_requesting)
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .setCancelable(false)
            .show()

        GitHubDeviceAuth.requestDeviceCode { result ->
            if (isFinishing || isDestroyed) return@requestDeviceCode
            result.fold(
                onSuccess = { device ->
                    progressDialog.dismiss()
                    showGitHubDeviceDialog(device)
                },
                onFailure = { error ->
                    progressDialog.dismiss()
                    showGithubError(error.message)
                }
            )
        }
    }

    private fun showGitHubDeviceDialog(device: GitHubDeviceAuth.DeviceCode) {
        val cancelled = AtomicBoolean(false)
        deviceFlowCancelled = cancelled

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val messageView = TextView(this).apply {
            text = getString(R.string.git_github_device_dialog_message, device.verificationUri, device.userCode)
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        container.addView(messageView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val topMargin = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, topMargin, 0, 0)
        }
        val copyButton = android.widget.Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            text = getString(R.string.git_github_device_copy_code)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("github_device_code", device.userCode))
                android.widget.Toast.makeText(this@GitPanelActivity, R.string.git_github_device_code_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val openButton = android.widget.Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            text = getString(R.string.git_github_device_open_browser)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUri)))
            }
        }
        buttonRow.addView(copyButton)
        buttonRow.addView(openButton)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.git_github_device_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.git_confirm_cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(true)
            .setOnCancelListener { cancelled.set(true) }
            .show()

        GitHubDeviceAuth.pollForToken(device, cancelled) { result ->
            if (isFinishing || isDestroyed) return@pollForToken
            dialog.dismiss()
            deviceFlowCancelled = null
            result.fold(
                onSuccess = { token -> onGithubTokenReceived(token) },
                onFailure = { error ->
                    if (!cancelled.get()) showGithubError(error.message)
                }
            )
        }
    }

    private fun onGithubTokenReceived(token: String) {
        githubAuthPrefs.setToken(token)
        refreshGithubStatus()
        android.widget.Toast.makeText(this, R.string.git_github_device_success, android.widget.Toast.LENGTH_SHORT).show()
        GitHubDeviceAuth.fetchLogin(token) { login ->
            if (isFinishing || isDestroyed || login.isNullOrBlank()) return@fetchLogin
            githubAuthPrefs.setLogin(login)
            refreshGithubStatus()
        }
    }

    private fun showGithubError(message: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.git_github_device_error_title)
            .setMessage(message ?: "Error desconocido.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        binding.stashButton.isEnabled = canInteract
        binding.stashPopButton.isEnabled = canInteract
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
                    GitBridge.push(path, githubAuthPrefs.getToken()) { result ->
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
                    GitBridge.pull(path, githubAuthPrefs.getToken()) { result ->
                        onOperationFinished(result) { loadStatusAndLog(path) }
                    }
                }
            }
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .show()
    }

    private fun confirmAndStash() {
        val path = projectPath ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.git_stash_confirm_title)
            .setMessage(R.string.git_stash_confirm_message)
            .setPositiveButton(R.string.git_confirm_yes) { _, _ ->
                runOperation {
                    GitBridge.stashSave(path) { result ->
                        onOperationFinished(result) { loadStatusAndLog(path) }
                    }
                }
            }
            .setNegativeButton(R.string.git_confirm_cancel, null)
            .show()
    }

    private fun confirmAndStashPop() {
        val path = projectPath ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.git_stash_pop_confirm_title)
            .setMessage(R.string.git_stash_pop_confirm_message)
            .setPositiveButton(R.string.git_confirm_yes) { _, _ ->
                runOperation {
                    GitBridge.stashPop(path) { result ->
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
