package com.termux.app.ui.studio.termux

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.databinding.StudioActivityTerminalBinding

/**
 * Pantalla "Terminal" del IDE -- NO es un emulador de terminal completo, es un log de salida de
 * comandos corridos en Kairos vía [TermuxBridge] (in-process, `bash -c "<comando>"`) más un
 * campo de entrada para el próximo comando. Cada comando se ejecuta en una sesión de bash nueva
 * (no hay estado de shell persistente entre comandos -- cada envío es `bash -c "<comando>"`
 * independiente).
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: StudioActivityTerminalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.runButton.setOnClickListener { sendCommand() }
        binding.commandInput.setOnEditorActionListener { _, actionId, event ->
            val isSendAction = actionId == EditorInfo.IME_ACTION_SEND
            val isEnterKeyDown = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isSendAction || isEnterKeyDown) {
                sendCommand()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.runButton.isEnabled = true
        binding.commandInput.isEnabled = true
        hideBanner()
    }

    private fun showBanner(message: String) {
        binding.statusBanner.visibility = android.view.View.VISIBLE
        binding.statusBannerText.text = message
        binding.statusBannerAction.visibility = android.view.View.GONE
    }

    private fun hideBanner() {
        binding.statusBanner.visibility = android.view.View.GONE
    }

    private fun sendCommand() {
        val command = binding.commandInput.text.toString().trim()
        if (command.isEmpty()) return

        appendOutputLine("$ $command")
        binding.commandInput.text?.clear()
        binding.runButton.isEnabled = false

        TermuxBridge.runShellCommandStreaming(
            command = command,
            onLine = { line -> appendOutputLine(line) },
            onDone = { exitCode ->
                if (isFinishing || isDestroyed) return@runShellCommandStreaming
                binding.runButton.isEnabled = true
                appendOutputLine(getString(R.string.terminal_exit_code, exitCode))
            }
        )
    }

    /** [TermuxBridge] no expone cancelación del comando en curso -- si el usuario cierra esta
     * pantalla mientras un comando largo sigue corriendo, el callback de background sigue
     * llegando igual (mismo caso que [com.termux.app.ui.studio.build.BuildLogActivity]) -- el
     * guard evita tocar `binding` de una Activity ya destruida. */
    private fun appendOutputLine(line: String) {
        if (isFinishing || isDestroyed) return
        binding.outputLog.append(line + "\n")
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}