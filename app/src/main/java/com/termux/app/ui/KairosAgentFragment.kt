package com.termux.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.AgentAvatarService
import com.termux.app.data.ModuleCatalog
import com.termux.app.util.AgentAvatarKind
import com.termux.app.util.AgentAvatarPrefs
import com.termux.app.util.AiCompany
import com.termux.app.util.AiProviders
import com.termux.app.util.KairosAgentClient
import com.termux.app.util.KairosAgentKnowledge
import com.termux.app.util.OverlayPermissionHelper
import com.termux.app.util.SpeechInputManager
import com.termux.app.util.TelegramNotifier
import com.termux.app.util.kairosThemeColor
import com.termux.app.util.setScreenSecure
import android.widget.Toast

/**
 * Pantalla "Agente Kairos" (menú "Más"): conversar con el agente y pedirle que haga cosas.
 *
 * Orden explícita del usuario: el agente deja de ser un módulo del catálogo, no
 * tiene "Abrir en terminal" y se usa como un asistente personal (tipo Hermes/OpenClaw/Jarvis). Lo que
 * puede hacer hoy es lo que el motor realmente soporta ([KairosAgentClient]): consultar el estado de
 * los módulos, listar procesos, ver su historial y — pidiendo SIEMPRE confirmación antes — instalar,
 * iniciar o detener un módulo. Los pedidos que no entiende se los pasa al router con IA del propio
 * motor (llama-server / Ollama locales) si hay uno disponible; si no, lo dice sin inventar nada.
 */
class KairosAgentFragment : Fragment() {

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var setupCard: LinearLayout
    private lateinit var micButton: TextView
    private var modules: List<Pair<String, String>> = emptyList()
    private var busy = false

    // Voz de entrada (2026-11-02, pedido del usuario: "integración para que el usuario hable
    // con ella") — reusa el mismo motor que ChatFragment/VoiceToAgentDialog ya usan
    // (SpeechInputManager, android.speech.SpeechRecognizer nativo), nunca se reimplementa acá.
    // Mismo criterio de registro: fuera de un click listener, antes de que el Fragment llegue
    // a STARTED (ver ChatFragment.mRecordAudioPermissionLauncher).
    private var speechManager: SpeechInputManager? = null
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_agent_mic_permission_denied), Toast.LENGTH_SHORT).show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                setPadding(dp(16), dp(16), dp(16), dp(2))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_subtitle)
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(16), 0, dp(16), dp(8))
            })
        })
        // 2 ventanas de configuración, pantalla principal solo para escribir (2026-10-31,
        //: "la pantalla princpial de agentes karios es solo para escribir,
        // todo lo que es configuracion debe estar en dos venetnas") — antes Avatar/Voz/
        // Notificaciones vivían como tarjetas sueltas arriba del chat; ahora son 2 diálogos.
        headerRow.addView(TextView(ctx).apply {
            text = "🛠"
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.agent_settings_button)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(18), ctx.kairosThemeColor(R.attr.kairosBorder))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also { it.marginEnd = dp(8) }
            setOnClickListener { showAgentSettingsDialog() }
        })
        headerRow.addView(TextView(ctx).apply {
            text = "⚙"
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.agent_ai_settings_button)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(18), ctx.kairosThemeColor(R.attr.kairosBorder))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also { it.marginEnd = dp(16) }
            setOnClickListener { showAiSettingsDialog() }
        })
        root.addView(headerRow)

        setupCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(12), dp(4), dp(12), dp(8))
            }
        }
        setupCard.addView(TextView(ctx).apply {
            text = getString(R.string.agent_setup_message)
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        })
        setupCard.addView(TextView(ctx).apply {
            text = getString(R.string.agent_setup_button)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBlue), dp(10), null)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
            setOnClickListener { installEngine(this) }
        })
        root.addView(setupCard)

        scroll = ScrollView(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        messages = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        scroll.addView(messages)
        root.addView(scroll)

        // Atajos: lo que el motor sabe hacer sin escribir.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        listOf(R.string.agent_chip_status to "estado", R.string.agent_chip_processes to "procesos", R.string.agent_chip_history to "historial")
            .forEach { (label, query) -> chips.addView(chip(getString(label)) { send(query) }) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        input = EditText(ctx).apply {
            hint = getString(R.string.agent_input_hint)
            textSize = 14f
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setHintTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(20), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
            }
        }
        bar.addView(input)
        micButton = TextView(ctx).apply {
            text = "🎤"
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = if (SpeechInputManager.isAvailable(ctx)) 0.6f else 0.3f
            isEnabled = SpeechInputManager.isAvailable(ctx)
            contentDescription = getString(R.string.voice_agent_mic_dictate)
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(22), ctx.kairosThemeColor(R.attr.kairosBorder))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) }
            setOnClickListener { onMicClicked() }
        }
        bar.addView(micButton)
        bar.addView(TextView(ctx).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.agent_send)
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBlue), dp(22), null)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) }
            setOnClickListener { submit() }
        })
        root.addView(bar)

        addAgentMessage(getString(R.string.agent_welcome))
        refreshEngineState()
        // Anti-tapjacking (mismo criterio que ChatFragment/ConfigFragment/HomelabFragment, ver
        // .claude/rules/kairos-secrets-never-revealed.md): esta pantalla tipea y guarda el token
        // de bot de Telegram desde que Notificaciones se mudó acá (2026-10-30)
        // — hallazgo real de la revisión de seguridad de esa misma ronda, el traslado había
        // copiado el campo/switch pero no las protecciones de pantalla que sí tenía en Config.
        root.filterTouchesWhenObscured = true
        return root
    }

    // FLAG_SECURE por pantalla (ver com.termux.app.util.setScreenSecure) — bloquea screenshots/
    // grabación mientras esta pantalla está en foreground, por la misma razón que arriba.
    override fun onResume() {
        super.onResume()
        setScreenSecure(true)
    }

    override fun onPause() {
        super.onPause()
        setScreenSecure(false)
    }

    // Bug real (2026-11-02, "sigue sin dejar tirar capture"): hide/show
    // (así cambia de tab TermuxActivity) NO dispara onPause()/onResume() — una sola visita al
    // Agente dejaba FLAG_SECURE prendido para toda la app hasta que esta Activity entera
    // pasara a background. onHiddenChanged() sí dispara con hide()/show().
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) setScreenSecure(false) else if (isResumed) setScreenSecure(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechManager?.destroy()
        speechManager = null
    }

    /** Pide RECORD_AUDIO en runtime si falta, o arranca a escuchar directo si ya está concedido
     *  (mismo patrón que ChatFragment.onMicClicked/VoiceToAgentDialog.onMicClicked). */
    private fun onMicClicked() {
        speechManager?.let { if (it.isListening()) { it.stopListening(); return } }
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) startListening() else recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /** Dicta y agrega el texto reconocido AL FINAL de lo que ya había en [input] (nunca lo
     *  reemplaza ni lo envía solo) — el usuario siempre revisa/edita antes de tocar enviar,
     *  mismo criterio que ChatFragment/VoiceToAgentDialog. */
    private fun startListening() {
        val manager = speechManager ?: SpeechInputManager(requireContext()).also { speechManager = it }
        manager.setCallback(object : SpeechInputManager.SpeechCallback {
            override fun onResult(text: String) {
                if (!isAdded) return
                val current = input.text?.toString().orEmpty()
                val separator = if (current.isNotEmpty() && !current.endsWith(" ")) " " else ""
                input.setText(current + separator + text)
                input.setSelection(input.text?.length ?: 0)
            }
            override fun onError(error: String) {
                if (isAdded) Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            }
            override fun onListeningStarted() {
                if (isAdded) micButton.alpha = 1.0f
            }
            override fun onListeningStopped() {
                if (isAdded) micButton.alpha = 0.6f
            }
        })
        manager.startListening()
    }

    // ── Estado del motor ────────────────────────────────────────────────────

    private fun refreshEngineState() {
        Thread {
            val installed = KairosAgentClient.isInstalled()
            val ctx = context?.applicationContext
            if (installed) {
                val list = ctx?.let { ModuleCatalog.load(it).map { m -> m.id to m.name } }.orEmpty()
                if (isAdded) requireActivity().runOnUiThread { if (isAdded) modules = list }
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) setupCard.visibility = if (installed) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun installEngine(button: TextView) {
        button.isEnabled = false
        button.text = getString(R.string.agent_setup_working)
        Thread {
            val (ok, tail) = KairosAgentClient.installEngine()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                button.isEnabled = true
                button.text = getString(R.string.agent_setup_button)
                if (ok) addAgentMessage(getString(R.string.agent_setup_done)) else addAgentMessage(getString(R.string.agent_setup_failed, tail))
                refreshEngineState()
            }
        }.start()
    }

    // ── Avatar flotante ──────────────────────────────────────────────────────

    /**
     * Fila "Avatar flotante del Agente" — enciende/apaga AgentAvatarService.kt (la "cara mínima"
     * que flota en una esquina, ver docs/agente/DECISIONES_AGENTE_2026-10-10.md). Mismo patrón de
     * permiso de superposición que ConfigFragment.kt ya usa para el widget flotante de módulos
     * (FloatingWidgetService): sin el permiso, el switch no se activa y se pide el permiso.
     */
    /** Ventana "Configurar Agente" — avatar flotante, voz, selector de avatar, Notificaciones
     * (Telegram). Todo lo que antes vivía suelto arriba del chat (2026-10-31). */
    private fun showAgentSettingsDialog() {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(buildAvatarToggleRow(ctx))
            addView(buildVoiceToggleRow(ctx))
            addView(buildAvatarKindRow(ctx))
            addView(buildNotificationsSection(ctx))
        }
        val scrollContent = ScrollView(ctx).apply { addView(content) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.agent_settings_dialog_title))
            .setView(scrollContent)
            .setPositiveButton(getString(R.string.gateway_close), null)
            .show()
    }

    private fun buildAvatarToggleRow(ctx: android.content.Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(12), dp(4), dp(12), dp(8))
            }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_avatar_toggle)
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_avatar_toggle_hint)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, dp(2), 0, 0)
            })
        })
        val toggle = SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
            isChecked = AgentAvatarPrefs.isEnabled(ctx)
            setOnCheckedChangeListener { switch, checked ->
                if (checked) {
                    if (hasOverlayPermission(ctx)) {
                        AgentAvatarPrefs.setEnabled(ctx, true)
                        ctx.startService(Intent(ctx, AgentAvatarService::class.java))
                    } else {
                        switch.isChecked = false
                        addAgentMessage(getString(R.string.agent_avatar_need_overlay))
                        OverlayPermissionHelper.requestOverlayPermission(requireActivity())
                    }
                } else {
                    AgentAvatarPrefs.setEnabled(ctx, false)
                    ctx.stopService(Intent(ctx, AgentAvatarService::class.java))
                }
            }
        }
        row.addView(toggle)
        return row
    }

    /**
     * Fila "Voz del Agente" — pedido explícito del usuario (2026-10-30):
     * "parpadear y tener sonido". TextToSpeech nativo real (AgentVoice.kt), apagado por defecto
     * a propósito: no todos quieren que el teléfono hable solo cada tanto, es opt-in.
     */
    private fun buildVoiceToggleRow(ctx: android.content.Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(12), dp(4), dp(12), dp(8))
            }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_voice_toggle)
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.agent_voice_toggle_hint)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(SwitchCompat(ctx).apply {
            thumbTintList = ContextCompat.getColorStateList(ctx, R.color.switch_thumb_color)
            trackTintList = ContextCompat.getColorStateList(ctx, R.color.switch_track_color)
            isChecked = AgentAvatarPrefs.isVoiceEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                AgentAvatarPrefs.setVoiceEnabled(ctx, checked)
                // El servicio ya corriendo (si está activo) prepara/libera el motor TTS al toque
                // — no hace falta reiniciarlo para que el cambio tome efecto en el próximo "dice algo".
                if (checked) com.termux.app.util.AgentVoice.init(ctx)
                else com.termux.app.util.AgentVoice.shutdown()
            }
        })
        return row
    }

    /**
     * Fila "Avatar del Agente" — pedido explícito del usuario (2026-10-30):
     * "poner para escoger si desea a kari la chica, kairo el chico, o kairos la cara". Dropdown
     * en vez de 3 botones (selección excluyente, mismo criterio ya establecido en
     * `MEJORAS_PENDIENTES.md` tras auditar Xed-Editor-main). Si el avatar flotante ya está
     * encendido, cambiar acá lo reinicia al toque para que el cambio se vea sin tener que
     * apagar/prender el switch a mano.
     */
    private fun buildAvatarKindRow(ctx: android.content.Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(12), dp(4), dp(12), dp(8))
            }
        }
        row.addView(TextView(ctx).apply {
            text = getString(R.string.agent_avatar_kind_label)
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val kinds = listOf(AgentAvatarKind.CARA, AgentAvatarKind.KARI, AgentAvatarKind.KAIRO)
        val labels = listOf(
            getString(R.string.agent_avatar_kind_cara),
            getString(R.string.agent_avatar_kind_kari),
            getString(R.string.agent_avatar_kind_kairo),
        )
        row.addView(Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(kinds.indexOf(AgentAvatarPrefs.kind(ctx)).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val chosen = kinds[position]
                    if (chosen == AgentAvatarPrefs.kind(ctx)) return
                    AgentAvatarPrefs.setKind(ctx, chosen)
                    if (AgentAvatarPrefs.isEnabled(ctx)) {
                        // Reiniciar el overlay para que tome el avatar nuevo -- AgentAvatarView
                        // recibe el "kind" solo en su constructor (ver AgentAvatarService.addAvatar()).
                        ctx.stopService(Intent(ctx, AgentAvatarService::class.java))
                        ctx.startService(Intent(ctx, AgentAvatarService::class.java))
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        })
        return row
    }

    /**
     * Notificaciones (Telegram) — movida acá desde Config (2026-10-30,:
     * "meter la pestaña de notificaciones de config a agente"). Lógica idéntica a la que tenía
     * `ConfigFragment.setupTelegramSection()` (token/chat id nunca se pre-cargan, ver
     * `.claude/rules/kairos-secrets-never-revealed.md`) — solo retargeteada a un contenedor
     * propio en vez de un tab de Config, que ya no existe.
     */
    private fun buildNotificationsSection(ctx: android.content.Context): LinearLayout {
        val prefs = ctx.getSharedPreferences("kairos_prefs", 0)
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        section.addView(TextView(ctx).apply {
            text = getString(R.string.config_telegram_header)
            textSize = 10f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(16), dp(4), dp(16), dp(6))
            }
        })

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(14), dp(12), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(12), 0, dp(12), dp(8))
            }
        }

        val hasSavedToken = !prefs.getString(TelegramNotifier.PREF_BOT_TOKEN, null).isNullOrBlank()
        val tokenInput = EditText(ctx).apply {
            hint = getString(if (hasSavedToken) R.string.config_telegram_hint_token_saved else R.string.config_telegram_hint_token)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            textSize = 13f
        }
        fun resolveTelegramToken(): String =
            tokenInput.text.toString().trim().ifBlank { prefs.getString(TelegramNotifier.PREF_BOT_TOKEN, "") ?: "" }
        val chatIdInput = EditText(ctx).apply {
            hint = getString(R.string.config_telegram_hint_chatid)
            setText(prefs.getString(TelegramNotifier.PREF_CHAT_ID, "") ?: "")
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
        }
        card.addView(tokenInput)
        card.addView(chatIdInput)

        card.addView(TextView(ctx).apply {
            text = getString(R.string.config_telegram_test_row)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(2))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setOnClickListener {
                val token = resolveTelegramToken()
                val chatId = chatIdInput.text.toString().trim()
                if (token.isBlank() || chatId.isBlank()) {
                    agentToast(getString(R.string.config_toast_telegram_fill_fields)); return@setOnClickListener
                }
                prefs.edit().putString(TelegramNotifier.PREF_BOT_TOKEN, token).putString(TelegramNotifier.PREF_CHAT_ID, chatId).apply()
                agentToast(getString(R.string.config_toast_telegram_sending))
                Thread {
                    val result = TelegramNotifier.sendMessageDetailed(token, chatId, getString(R.string.config_telegram_test_message))
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        agentToast(if (result.ok) getString(R.string.config_snackbar_telegram_sent)
                        else getString(R.string.config_snackbar_telegram_error, result.error ?: getString(R.string.config_unknown)))
                    }
                }.start()
            }
        })

        val botToggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) }
        }
        botToggleRow.addView(TextView(ctx).apply {
            text = getString(R.string.config_telegram_bot_toggle_label)
            textSize = 13f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val botToggleSwitch = SwitchCompat(ctx).apply {
            isChecked = prefs.getBoolean(com.termux.app.TelegramBotService.PREF_BOT_ENABLED, false)
        }
        botToggleRow.addView(botToggleSwitch)
        card.addView(botToggleRow)

        val botStatusText = TextView(ctx).apply {
            text = getString(if (botToggleSwitch.isChecked) R.string.config_telegram_bot_status_on else R.string.config_telegram_bot_status_off)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(botStatusText)

        var suppressBotToggleEvents = false
        botToggleSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressBotToggleEvents) return@setOnCheckedChangeListener
            if (checked) {
                val token = resolveTelegramToken()
                val chatId = chatIdInput.text.toString().trim()
                if (token.isBlank() || chatId.isBlank()) {
                    agentToast(getString(R.string.config_toast_telegram_fill_fields))
                    suppressBotToggleEvents = true; botToggleSwitch.isChecked = false; suppressBotToggleEvents = false
                    return@setOnCheckedChangeListener
                }
                prefs.edit()
                    .putString(TelegramNotifier.PREF_BOT_TOKEN, token)
                    .putString(TelegramNotifier.PREF_CHAT_ID, chatId)
                    .putBoolean(com.termux.app.TelegramBotService.PREF_BOT_ENABLED, true)
                    .apply()
                val started = com.termux.app.TelegramBotService.start(ctx)
                if (started) {
                    botStatusText.text = getString(R.string.config_telegram_bot_status_on)
                    agentToast(getString(R.string.config_toast_telegram_bot_started))
                    com.termux.app.util.BatteryRestrictionHelper.requestDisableBatteryRestrictions(requireActivity())
                } else {
                    suppressBotToggleEvents = true; botToggleSwitch.isChecked = false; suppressBotToggleEvents = false
                    prefs.edit().putBoolean(com.termux.app.TelegramBotService.PREF_BOT_ENABLED, false).apply()
                    agentToast(getString(R.string.config_toast_telegram_fill_fields))
                }
            } else {
                prefs.edit().putBoolean(com.termux.app.TelegramBotService.PREF_BOT_ENABLED, false).apply()
                com.termux.app.TelegramBotService.stop(ctx)
                botStatusText.text = getString(R.string.config_telegram_bot_status_off)
                agentToast(getString(R.string.config_toast_telegram_bot_stopped))
            }
        }

        section.addView(card)
        return section
    }

    private fun agentToast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun hasOverlayPermission(ctx: android.content.Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    // ── Configurar IA externa ───────────────────────────────────────────────
    // Rediseño 2026-10-31 (docs/arquitectura/PLAN_SHIZUKU_IA_HOMELAB_ADB_
    // 2026-10-31.md): antes eran 3 campos de texto libre (URL/Modelo/Clave) que podían quedar
    // vacíos o con un modelo inventado. Regla nueva ("nunca vacío"): la empresa SIEMPRE tiene un
    // valor elegido (Anthropic/OpenAI/Google/OpenRouter/Ollama Cloud/local) y el modelo SIEMPRE
    // trae algo seleccionado — [AiProviders.fetchModels] trae el catálogo real en vivo (requiere
    // la clave recién tipeada, nunca la guardada — ver nota de abajo), con [AiProviders.
    // fallbackModel] como piso mientras no hay clave o la red falla.

    private fun showAiSettingsDialog() {
        if (!KairosAgentClient.isInstalled()) { addAgentMessage(getString(R.string.agent_need_engine)); return }
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("kairos_prefs", 0)
        var company = AiCompany.fromId(prefs.getString("agent_ai_company", null))

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        form.addView(TextView(ctx).apply {
            text = getString(R.string.agent_ai_company_label)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        val companySpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, AiCompany.entries.map { it.label })
            setSelection(AiCompany.entries.indexOf(company))
        }
        form.addView(companySpinner)

        // "Personalizado (OpenAI-compatible)" — hallazgo real de docs/referencias/ia/
        // REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md, ChatFragment.kt ya lo tenía como proveedor
        // "custom" interno (pedido real de un usuario vía Telegram), el Agente no.
        val baseUrlInput = EditText(ctx).apply {
            hint = getString(R.string.agent_ai_custom_base_url_hint)
            setText(prefs.getString("agent_ai_custom_base_url", ""))
            visibility = if (company == AiCompany.CUSTOM) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
        }
        form.addView(baseUrlInput)

        form.addView(TextView(ctx).apply {
            text = getString(R.string.agent_ai_model_label)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        val modelSpinner = Spinner(ctx).apply {
            visibility = if (company == AiCompany.CUSTOM) View.GONE else View.VISIBLE
        }
        form.addView(modelSpinner)
        // Personalizado no tiene catálogo (ver AiProviders.modelsUrl) — el modelo se escribe a
        // mano, no se elige de una lista.
        val customModelInput = EditText(ctx).apply {
            hint = getString(R.string.agent_ai_custom_model_hint)
            setText(prefs.getString("agent_ai_custom_model", ""))
            visibility = if (company == AiCompany.CUSTOM) View.VISIBLE else View.GONE
        }
        form.addView(customModelInput)

        // "Nunca vacío": siempre hay al menos [AiProviders.fallbackModel] seleccionado, aunque
        // todavía no se haya podido traer el catálogo real (sin clave/sin red).
        fun setModelOptions(models: List<String>) {
            val options = models.ifEmpty { listOf(AiProviders.fallbackModel(company)) }
            modelSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
        }
        setModelOptions(emptyList())

        val keyInput = EditText(ctx).apply {
            hint = getString(if (company.requiresApiKey) R.string.agent_ai_key_hint else R.string.agent_ai_key_hint_local)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = if (company.requiresApiKey) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
        }
        form.addView(keyInput)

        val refreshBtn = TextView(ctx).apply {
            text = getString(R.string.agent_ai_refresh_models)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setPadding(0, dp(8), 0, 0)
            visibility = if (company.requiresApiKey) View.VISIBLE else View.GONE
        }
        form.addView(refreshBtn)

        form.addView(TextView(ctx).apply {
            text = getString(R.string.agent_ai_key_help)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })

        fun refreshLiveModels() {
            if (company == AiCompany.CUSTOM) return // sin catálogo -- ver customModelInput
            if (company == AiCompany.LOCAL) {
                fetchLocalModels { models -> if (isAdded) setModelOptions(models) }
                return
            }
            val key = keyInput.text.toString().trim()
            // OpenRouter expone su catálogo sin token — el resto necesita la clave recién
            // tipeada (nunca la ya guardada, ver cabecera de esta sección).
            if (company.requiresApiKey && key.isBlank() && company != AiCompany.OPENROUTER) {
                setModelOptions(emptyList())
                return
            }
            Thread {
                val models = try { AiProviders.fetchModels(company, key) } catch (_: Exception) { emptyList() }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { if (isAdded) setModelOptions(models) }
            }.start()
        }
        refreshBtn.setOnClickListener { refreshLiveModels() }
        companySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                company = AiCompany.entries[position]
                val isCustom = company == AiCompany.CUSTOM
                keyInput.visibility = if (company.requiresApiKey || isCustom) View.VISIBLE else View.GONE
                refreshBtn.visibility = if (company.requiresApiKey && !isCustom) View.VISIBLE else View.GONE
                keyInput.hint = getString(if (company.requiresApiKey) R.string.agent_ai_key_hint else R.string.agent_ai_key_hint_local)
                baseUrlInput.visibility = if (isCustom) View.VISIBLE else View.GONE
                modelSpinner.visibility = if (isCustom) View.GONE else View.VISIBLE
                customModelInput.visibility = if (isCustom) View.VISIBLE else View.GONE
                setModelOptions(emptyList())
                refreshLiveModels()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        refreshLiveModels()

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.agent_ai_settings_title))
            .setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton(getString(R.string.agent_ai_save)) { _, _ ->
                if (company == AiCompany.CUSTOM) {
                    val baseUrl = baseUrlInput.text.toString().trim()
                    val model = customModelInput.text.toString().trim()
                    if (baseUrl.isBlank() || model.isBlank()) {
                        agentToast(getString(R.string.agent_ai_custom_fields_required))
                        return@setPositiveButton
                    }
                    prefs.edit()
                        .putString("agent_ai_company", company.id)
                        .putString("agent_ai_custom_base_url", baseUrl)
                        .putString("agent_ai_custom_model", model)
                        .apply()
                    saveCustomAiSettings(AiProviders.customCompletionsUrl(baseUrl), model, keyInput.text.toString().trim())
                    return@setPositiveButton
                }
                prefs.edit().putString("agent_ai_company", company.id).apply()
                val model = (modelSpinner.selectedItem as? String).orEmpty().ifBlank { AiProviders.fallbackModel(company) }
                val url = if (company == AiCompany.LOCAL) "" else AiProviders.chatUrl(company)
                saveAiSettings(url, model, keyInput.text.toString().trim())
            }
            .setNeutralButton(getString(R.string.agent_ai_remove_key)) { _, _ -> removeAiKey() }
            .setNegativeButton(getString(R.string.agent_confirm_no), null)
            .show()
    }

    /** Modelos locales reales — mismas 2 fuentes que ya usa ChatFragment.showModelMenu():
     *  Ollama instalado (GET /api/tags) + GGUF embebidos de llama.cpp (LocalModelManager). */
    private fun fetchLocalModels(onResult: (List<String>) -> Unit) {
        Thread {
            val ollama = try { com.termux.app.util.OllamaApiClient.listModels().map { it.name } } catch (_: Exception) { emptyList() }
            val gguf = try {
                com.termux.app.util.LocalModelManager.listChatModels(requireContext().applicationContext).map { it.name }
            } catch (_: Exception) { emptyList() }
            val all = ollama + gguf
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(all) }
        }.start()
    }

    private fun saveAiSettings(url: String, model: String, key: String) {
        Thread {
            val ok = KairosAgentClient.configureAi(url, model, key)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) addAgentMessage(getString(if (ok) R.string.agent_ai_saved else R.string.agent_ai_save_failed))
            }
        }.start()
    }

    /**
     * Variante para [AiCompany.CUSTOM] — hallazgo real de la revisión de seguridad automática de
     * este commit (confirmado leyendo modulos/kairos_agent.sh `_save_ai_config()`: un `key=None`
     * MERGEA, deja la clave que ya hubiera guardada tal cual, "no la borra"). Con un endpoint fijo
     * y conocido (Anthropic/OpenAI/...) ese comportamiento es una comodidad razonable; con un
     * endpoint ARBITRARIO que el usuario tipeó, dejar el campo de clave vacío significaría enviar
     * la clave real de OTRO proveedor (ej. la de Anthropic) al servidor "Personalizado" nuevo sin
     * que el usuario lo haya pedido — una fuga real de credencial a un destino no vetted. Si el
     * campo viene vacío, se limpia explícitamente la clave guardada ANTES de guardar url/modelo,
     * en vez de dejar que el merge la preserve en silencio.
     */
    private fun saveCustomAiSettings(url: String, model: String, key: String) {
        Thread {
            if (key.isBlank()) KairosAgentClient.clearAiKey()
            val ok = KairosAgentClient.configureAi(url, model, key)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) addAgentMessage(getString(if (ok) R.string.agent_ai_saved else R.string.agent_ai_save_failed))
            }
        }.start()
    }

    private fun removeAiKey() {
        Thread {
            val ok = KairosAgentClient.clearAiKey()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) addAgentMessage(getString(if (ok) R.string.agent_ai_saved else R.string.agent_ai_save_failed))
            }
        }.start()
    }

    // ── Conversación ────────────────────────────────────────────────────────

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send(text)
    }

    private fun send(text: String) {
        if (busy) { addAgentMessage(getString(R.string.agent_busy)); return }
        addUserMessage(text)
        if (!KairosAgentClient.isInstalled()) { addAgentMessage(getString(R.string.agent_need_engine)); return }
        val kb = context?.applicationContext?.let { KairosAgentKnowledge.load(it) }
        val parsed = KairosAgentClient.parse(text, modules, kb)
        if (parsed == null) {
            // Preguntas sobre Kairos que tienen respuesta fija en la base de conocimiento: no hace falta IA.
            kb?.answer(text)?.let { addAgentMessage(it); scrollDown(); return }
            askRouter(text); return
        }
        if (parsed.mutates) confirmThen(parsed) else execute(parsed)
    }

    /** Instalar/iniciar/detener cambian el dispositivo: nunca se ejecutan sin que el usuario lo confirme. */
    private fun confirmThen(p: KairosAgentClient.Parsed) {
        val verb = when (p.intent) { "instalar" -> R.string.agent_verb_install; "iniciar" -> R.string.agent_verb_start; else -> R.string.agent_verb_stop }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.agent_confirm_title))
            .setMessage(getString(R.string.agent_confirm_message, getString(verb), p.moduleName ?: p.args.firstOrNull().orEmpty()))
            .setPositiveButton(getString(R.string.agent_confirm_yes)) { _, _ -> execute(p) }
            .setNegativeButton(getString(R.string.agent_confirm_no)) { _, _ -> addAgentMessage(getString(R.string.agent_cancelled)) }
            .show()
    }

    private fun execute(p: KairosAgentClient.Parsed) {
        busy = true
        val working = addAgentMessage(getString(R.string.agent_working))
        Thread {
            // Instalar puede tardar muchos minutos (el motor admite hasta 30).
            val timeout = if (p.intent == "instalar") 1800L else 200L
            val json = KairosAgentClient.run(listOf(p.intent) + p.args, timeout)
            val reply = json?.let { KairosAgentClient.format(p.intent, it) } ?: getString(R.string.agent_no_answer)
            if (!isAdded) { busy = false; return@Thread }
            requireActivity().runOnUiThread {
                busy = false
                if (isAdded) { setText(working, reply); scrollDown() }
            }
        }.start()
    }

    /** Pedido que no encaja con ninguna intent conocida → router con IA del motor, solo si hay uno. */
    private fun askRouter(text: String) {
        busy = true
        val working = addAgentMessage(getString(R.string.agent_thinking))
        Thread {
            val json = KairosAgentClient.run(listOf("interpretar", text), 60)
            if (!isAdded) { busy = false; return@Thread }
            requireActivity().runOnUiThread {
                busy = false
                if (!isAdded) return@runOnUiThread
                val chosen = json?.optString("intent").orEmpty()
                when {
                    json == null || (json.has("error") && !json.optBoolean("ok", true)) ->
                        setText(working, getString(R.string.agent_not_understood))
                    json.optBoolean("pending_confirmation") -> {
                        val args = json.optJSONObject("args")?.let { a -> a.optString("id").takeIf { it.isNotBlank() }?.let { listOf(it) } }.orEmpty()
                        setText(working, getString(R.string.agent_router_chose, chosen, args.joinToString(" ")))
                        confirmThen(KairosAgentClient.Parsed(chosen, args, true, args.firstOrNull()))
                    }
                    else -> setText(working, json.optJSONObject("resultado")?.let { KairosAgentClient.format(json.optString("intent_elegida"), it) }
                        ?: getString(R.string.agent_not_understood))
                }
                scrollDown()
            }
        }.start()
    }

    // ── Burbujas ────────────────────────────────────────────────────────────

    private fun addUserMessage(text: String): TextView = bubble(text, fromUser = true)
    private fun addAgentMessage(text: String): TextView = bubble(text, fromUser = false)

    private fun bubble(text: String, fromUser: Boolean): TextView {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (fromUser) Color.WHITE else ctx.kairosThemeColor(R.attr.kairosText))
            background = rounded(
                if (fromUser) ctx.kairosThemeColor(R.attr.kairosBlue) else ctx.kairosThemeColor(R.attr.kairosBg2),
                dp(16), if (fromUser) null else ctx.kairosThemeColor(R.attr.kairosBorder)
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = if (fromUser) Gravity.END else Gravity.START
                it.topMargin = dp(6)
            }
        }
        messages.addView(tv)
        scrollDown()
        return tv
    }

    private fun setText(view: TextView, text: String) { view.text = text }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            background = rounded(ctx.kairosThemeColor(R.attr.kairosBg2), dp(14), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()
}
