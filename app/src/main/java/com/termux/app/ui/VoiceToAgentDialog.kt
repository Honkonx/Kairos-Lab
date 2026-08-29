package com.termux.app.ui

import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.termux.app.TermuxActivity
import com.termux.app.util.ClaudeNative
import com.termux.app.util.OpenCodeNative
import com.termux.app.util.SpeechInputManager
import com.termux.app.util.kairosThemeColor

/**
 * "Voz → Agente" — punto de entrada global (pestaña Módulos, ver ModulesFragment) para
 * dictar un pedido por voz y mandarlo como prompt inicial a cualquiera de los CLIs de IA
 * que Kairos ya instala/corre. Reusa SpeechInputManager (el mismo motor que ya usa el
 * dictado dentro del chat, ver ChatFragment.onMicClicked/startListening) — NO se
 * reimplementa SpeechRecognizer acá.
 *
 * Diferencia clave con el dictado del chat: ahí el texto siempre cae en el EditText fijo
 * del chat. Acá el destino es elegible (spinner de CLIs) y el texto transcripto queda en
 * un EditText EDITABLE dentro de este mismo diálogo — el usuario puede corregir errores de
 * reconocimiento antes de mandarlo, nunca se lanza el prompt "en crudo" sin revisión.
 *
 * Decisión de diseño (documentada acá porque no había un patrón previo que confirmar echando
 * un vistazo a los fragments existentes — todos ellos abren su CLI SIN ningún prompt inicial,
 * ver ClaudeFragment/CodexFragment/OpenCodeFragment): el prompt dictado se pasa como
 * argumento posicional al final del comando base de cada CLI (`claude "prompt"`, `codex
 * "prompt"`, `openclaw tui "prompt"`, etc.) — mismo patrón documentado para Claude Code CLI y
 * replicado por la mayoría de sus forks/clones de terminal (Codex CLI, Antigravity, etc.):
 * abre una sesión interactiva CON ese mensaje ya cargado como primer turno, no un modo
 * "print-and-exit". Si algún CLI puntual no soportara un positional así, el usuario sigue
 * viendo la terminal real (mismo overlay que cualquier otro launchTerminalCommand) y puede
 * corregir/reescribir a mano — nada de esto es una caja negra.
 */
class VoiceToAgentDialog : DialogFragment() {

    companion object {

        data class AgentCliOption(val id: String, val displayName: String, val baseCommand: String)

        /**
         * Universo de CLIs de agentes de IA que Kairos ya tiene como módulo instalable (ver
         * app/src/main/assets/modules.json) — id y displayName calcados de ahí (displayName ==
         * "name" de modules.json == getModuleName() de cada fragment dedicado, para que la
         * sesión de terminal que abre este diálogo sea la MISMA que ya reconocen
         * isTerminalSessionActive()/stopSessionByName() en los fragments de cada módulo, ver
         * BaseModuleFragment.launchTerminalCommand). baseCommand es el comando real que cada
         * fragment ya usa para abrir su CLI en terminal:
         * - claude: no tiene un baseCommand fijo (depende del método de instalación detectado,
         *   ver ClaudeNative.openCmd() usado en tiempo de construcción del comando final).
         * - opencode: idem, vía OpenCodeNative.tuiCmd() ("cd ~ && opencode .").
         * - hermes: "hermes --tui" (ver HermesFragment, botón "Abrir Hermes (TUI)").
         * - openclaw: "openclaw tui" (ver OpenClawFragment, botón "Abrir TUI (terminal)").
         * - el resto: terminalCommand tal cual figura en modules.json (agy/freebuff/codebuff/
         *   copilot/mmx/mimo/vibe/qwen/codex).
         */
        private val CLI_OPTIONS = listOf(
            AgentCliOption("claude", "Claude Code", ""),
            AgentCliOption("codex", "Codex CLI", "codex"),
            AgentCliOption("opencode", "OpenCode", ""),
            AgentCliOption("hermes", "Hermes", "hermes --tui"),
            AgentCliOption("antigravity", "Antigravity CLI", "agy"),
            AgentCliOption("openclaw", "OpenClaw", "openclaw tui"),
            AgentCliOption("freebuff", "Freebuff", "freebuff"),
            AgentCliOption("codebuff", "Codebuff", "codebuff"),
            AgentCliOption("copilotcli", "GitHub Copilot CLI", "copilot"),
            AgentCliOption("minimaxcli", "MiniMax CLI", "mmx"),
            AgentCliOption("mimocode", "MiMo Code", "mimo"),
            AgentCliOption("mistralvibe", "Mistral Vibe", "vibe"),
            AgentCliOption("qwencode", "Qwen Code", "qwen")
        )
    }

    private var mSpeechManager: SpeechInputManager? = null
    private lateinit var mPromptInput: EditText
    private lateinit var mCliSpinner: Spinner
    private lateinit var mMicButton: TextView
    private lateinit var mMicStatus: TextView
    private lateinit var mMicSpinner: ProgressBar
    private var mSelectedCli: AgentCliOption = CLI_OPTIONS[0]

    // Mismo criterio de campo de instancia que ChatFragment.mRecordAudioPermissionLauncher
    // (registerForActivityResult debe registrarse fuera de callbacks de click, antes de que
    // el Fragment llegue a STARTED) — RECORD_AUDIO se pide recién al tocar el micrófono, no
    // al abrir el diálogo.
    private val mRecordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast(getString(com.termux.R.string.voice_agent_mic_permission_denied))
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val root = buildRootView(ctx)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.voice_agent_dialog_title))
            .setView(root)
            .setPositiveButton(getString(com.termux.R.string.voice_agent_send), null)
            .setNegativeButton(getString(com.termux.R.string.voice_agent_cancel), null)
            .create()

        // setOnShowListener + override del positive button en vez de pasar el listener directo
        // a setPositiveButton: así se puede vetar el cierre del diálogo si el prompt está
        // vacío (mostrar un toast en vez de cerrar sin hacer nada) — patrón estándar de
        // AlertDialog para validar antes de dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onSendClicked(dialog)
            }
        }
        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mSpeechManager?.destroy()
        mSpeechManager = null
    }

    private fun buildRootView(ctx: android.content.Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        root.addView(TextView(ctx).apply {
            text = getString(com.termux.R.string.voice_agent_intro)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText2))
            setPadding(0, 0, 0, dp(14))
        })

        // Fila del micrófono: botón + estado + spinner de "escuchando".
        val micRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        mMicButton = TextView(ctx).apply {
            text = getString(com.termux.R.string.voice_agent_mic_dictate)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosGreen))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(ctx.kairosThemeColor(com.termux.R.attr.kairosBg3))
            }
            setOnClickListener { onMicClicked() }
        }
        micRow.addView(mMicButton)
        mMicSpinner = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = dp(12) }
        }
        micRow.addView(mMicSpinner)
        mMicStatus = TextView(ctx).apply {
            text = ""
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText3))
            setPadding(dp(10), 0, 0, 0)
        }
        micRow.addView(mMicStatus)
        root.addView(micRow)

        if (!SpeechInputManager.isAvailable(ctx)) {
            mMicButton.alpha = 0.35f
            mMicButton.isEnabled = false
            mMicStatus.text = getString(com.termux.R.string.voice_agent_mic_unavailable)
        }

        root.addView(TextView(ctx).apply {
            text = getString(com.termux.R.string.voice_agent_prompt_label)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText3))
            setPadding(0, dp(4), 0, dp(4))
        })
        mPromptInput = EditText(ctx).apply {
            hint = getString(com.termux.R.string.voice_agent_prompt_hint)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText))
            setHintTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText3))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), ctx.kairosThemeColor(com.termux.R.attr.kairosBorder))
                setColor(ctx.kairosThemeColor(com.termux.R.attr.kairosBg2))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(mPromptInput)

        root.addView(TextView(ctx).apply {
            text = getString(com.termux.R.string.voice_agent_send_to_label)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText3))
            setPadding(0, dp(14), 0, dp(4))
        })
        mCliSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                CLI_OPTIONS.map { it.displayName }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    mSelectedCli = CLI_OPTIONS[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(mCliSpinner)

        return root
    }

    private fun onMicClicked() {
        mSpeechManager?.let { if (it.isListening()) { it.stopListening(); return } }
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startListening() else mRecordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Mismo criterio que ChatFragment.startListening(): agrega el texto reconocido AL FINAL
     * de lo que ya había en el input (nunca lo reemplaza ni envía solo) — el usuario siempre
     * revisa/edita antes de tocar "Enviar".
     */
    private fun startListening() {
        val manager = mSpeechManager ?: SpeechInputManager(requireContext()).also { mSpeechManager = it }
        manager.setCallback(object : SpeechInputManager.SpeechCallback {
            override fun onResult(text: String) {
                if (!isAdded) return
                val current = mPromptInput.text?.toString().orEmpty()
                val separator = if (current.isNotEmpty() && !current.endsWith(" ")) " " else ""
                mPromptInput.setText(current + separator + text)
                mPromptInput.setSelection(mPromptInput.text?.length ?: 0)
            }
            override fun onError(error: String) {
                if (isAdded) toast(error)
            }
            override fun onListeningStarted() {
                if (!isAdded) return
                mMicButton.text = getString(com.termux.R.string.voice_agent_mic_stop)
                mMicSpinner.visibility = View.VISIBLE
                mMicStatus.text = getString(com.termux.R.string.voice_agent_listening)
            }
            override fun onListeningStopped() {
                if (!isAdded) return
                mMicButton.text = getString(com.termux.R.string.voice_agent_mic_dictate)
                mMicSpinner.visibility = View.GONE
                mMicStatus.text = ""
            }
        })
        manager.startListening()
    }

    private fun onSendClicked(dialog: Dialog) {
        val prompt = mPromptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            toast(getString(com.termux.R.string.voice_agent_empty_prompt_toast))
            return
        }
        mSpeechManager?.stopListening()
        val command = buildAgentCommand(mSelectedCli, prompt)
        (activity as? TermuxActivity)?.openTerminalWithCommand(command, mSelectedCli.displayName)
        dialog.dismiss()
    }

    /** Arma el comando final: comando base del CLI elegido + el prompt como argumento
     *  posicional entre comillas (ver docstring de clase para el porqué de este patrón). */
    private fun buildAgentCommand(cli: AgentCliOption, prompt: String): String {
        val quotedPrompt = quoteForShell(prompt)
        val base = when (cli.id) {
            "claude" -> claudeBaseCommand()
            "opencode" -> OpenCodeNative.tuiCmd().optString("command", "cd ~ && opencode .")
            else -> cli.baseCommand
        }
        return "$base $quotedPrompt"
    }

    /** Comando real para abrir Claude Code, mismo detector que usa ClaudeFragment (ver
     *  ClaudeNative.openCmd) — si no se pudo detectar ningún método de instalación, cae al
     *  binario "claude" a secas (mismo comportamiento visible que tocar "claude" a mano en
     *  una terminal sin el CLI instalado: el propio shell avisa "command not found"). */
    private fun claudeBaseCommand(): String {
        val result = ClaudeNative.openCmd()
        return if (result.optBoolean("ok", false)) result.optString("command") else "claude"
    }

    private fun quoteForShell(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
