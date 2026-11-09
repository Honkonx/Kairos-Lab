package com.termux.app.terminal.simple

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.termux.R
import com.termux.app.util.kairosThemeColor
import com.termux.terminal.TerminalSession

/**
 * Motor de renderizado del modo Simple de la terminal adaptada (ver MEJORAS_PENDIENTES.md
 * "Roadmap de terminal — 2 fases", Fase 2). Consume el buffer YA decodificado de una
 * [TerminalSession] (TerminalBuffer#getTranscriptTextWithFullLinesJoined(),
 * terminal-emulator/, protegido, solo lectura acá) y lo traduce a burbujas de chat usando el
 * [TerminalSimpleAdapter] que corresponda (ver [TerminalSimpleAdapterRegistry]).
 *
 * Es una capa de PRESENTACIÓN pura sobre la sesión real: nunca crea/destruye la sesión ni el
 * proceso (eso lo sigue manejando TermuxService/TermuxActivity exactamente igual que en modo
 * Adaptada clásico), y solo escribe a la sesión por una acción EXPLÍCITA del usuario
 * ([SimpleAction.command] — ver [buildActionRow]). Un mismo contenedor se reusa entre sesiones
 * (mismo patrón que TerminalView, que también es una única vista reasignada entre sesiones vía
 * attachSession()) — el historial ya parseado de cada sesión se conserva en [states] mientras el
 * renderer viva, para no re-parsear todo al volver a una pestaña.
 *
 * Diseño extensible (pedido explícito del usuario durante esta implementación: el modo Simple no
 * es solo "chat para CLIs de IA" — es el paraguas general de convertir cualquier interacción
 * hoy solo-terminal, ej. Ciberseguridad, en botones/controles reales). Este renderer no sabe nada
 * de CLIs de IA en particular — solo sabe pintar [SimpleBlock]s; toda la lógica de qué CUENTA
 * como paso/diff/acción vive en el [TerminalSimpleAdapter] activo.
 */
class TerminalSimpleRenderer(
    private val context: Context,
    private val scrollView: ScrollView,
    private val messagesContainer: LinearLayout,
    private val fallbackBanner: TextView
) {
    private val density = context.resources.displayMetrics.density
    private val states = HashMap<String, SessionState>()
    private var currentSessionHandle: String? = null

    private class SessionState(val adapter: TerminalSimpleAdapter) {
        var lastTranscript: String = ""
        val blocks = mutableListOf<SimpleBlock>()
        var pendingUserSubmit = false
        var actionsRendered = false
        var fallbackActive = false
        // Timestamps de "redibujos grandes" recientes — ver detectBigRedraw(). Un CLI
        // conversacional casi siempre APPENDEA texto; una TUI de pantalla completa (htop, vim,
        // top) REDIBUJA la pantalla entera todo el tiempo. Varios redibujos grandes en poco
        // tiempo es la señal de que esta sesión no es simplificable.
        val bigRedrawTimestamps = ArrayDeque<Long>()
    }

    fun isFallbackActive(session: TerminalSession): Boolean = states[session.mHandle]?.fallbackActive ?: false

    /** Monta (o remonta) el modo Simple para [session] — reconstruye la lista visible a partir del
     *  estado ya parseado si la sesión ya se había visto (cambiar de pestaña no reparsea todo el
     *  transcript de nuevo), y corre un refresh real por si la sesión ya tenía salida pendiente
     *  de la primera vez que se activa Simple para ella. */
    fun attach(session: TerminalSession) {
        currentSessionHandle = session.mHandle
        val state = states.getOrPut(session.mHandle) { SessionState(TerminalSimpleAdapterRegistry.adapterFor(session)) }
        messagesContainer.removeAllViews()
        if (!state.actionsRendered) {
            val actions = state.adapter.quickActions(session)
            if (actions.isNotEmpty()) messagesContainer.addView(buildActionRow(session, actions))
            state.actionsRendered = true
        }
        state.blocks.forEach { renderBlock(session, it) }
        fallbackBanner.visibility = if (state.fallbackActive) View.VISIBLE else View.GONE
        scrollToBottom()
        refresh(session)
    }

    fun detach() {
        currentSessionHandle = null
    }

    /** Llamado desde TermuxTerminalSessionActivityClient#onTextChanged() cuando [session] cambió
     *  de contenido — no hace nada si no es la sesión adjuntada actualmente (mismo guard que ya
     *  usa esa clase antes de tocar TerminalView#onScreenUpdated()). */
    fun refresh(session: TerminalSession) {
        if (session.mHandle != currentSessionHandle) return
        val state = states[session.mHandle] ?: return

        val current = readTranscript(session) ?: return
        detectBigRedraw(state, current)

        val userSubmitted = state.pendingUserSubmit
        state.pendingUserSubmit = false

        val newBlocks = state.adapter.parse(state.lastTranscript, current, userSubmitted)
        state.lastTranscript = current
        if (newBlocks.isEmpty()) return

        newBlocks.forEach {
            state.blocks.add(it)
            renderBlock(session, it)
        }
        scrollToBottom()
    }

    /** Ver TermuxTerminalViewClient#onKeyDown() — Enter con la sesión corriendo. Se captura la
     *  ÚLTIMA línea del transcript actual (el eco local del comando recién tipeado — el PTY ya la
     *  refleja en pantalla tecla por tecla, antes de que Enter llegue a este handler) como
     *  burbuja de usuario, y se marca el próximo refresh() para que el adapter sepa que ahí
     *  empieza un turno nuevo. Es una heurística (ver KDoc de CliChatSimpleAdapter) — no un hook
     *  real de "el usuario envió esto", que requeriría interceptar cada tecla individual. */
    fun onUserSubmit(session: TerminalSession) {
        val state = states[session.mHandle] ?: return
        state.pendingUserSubmit = true

        val current = readTranscript(session) ?: return
        val lastLine = current.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return
        if (session.mHandle != currentSessionHandle) return

        val block = SimpleBlock.UserCommand(stripPromptPrefix(lastLine))
        state.blocks.add(block)
        renderBlock(session, block)
        scrollToBottom()
    }

    private fun readTranscript(session: TerminalSession): String? {
        return try {
            session.emulator?.screen?.transcriptTextWithFullLinesJoined?.trim()
        } catch (t: Throwable) {
            // Defensivo — getEmulator()/getScreen() pueden quedar en null en la carrera entre que
            // una sesión termina y el callback de refresh todavía llega (mismo tipo de carrera
            // que ya documenta BaseModuleFragment para callbacks async de UI).
            null
        }
    }

    private fun stripPromptPrefix(line: String): String {
        // Prompts típicos ("user@host:~$ comando", "$ comando", "> comando") — nos quedamos solo
        // con lo que el usuario escribió, no con el prompt del shell.
        val idx = line.indexOfLast { it == '$' || it == '>' || it == '#' }
        return if (idx in 0 until line.length - 1) line.substring(idx + 1).trim() else line
    }

    private fun detectBigRedraw(state: SessionState, current: String) {
        val previous = state.lastTranscript
        if (previous.isEmpty() || current.isEmpty()) return
        if (current.length >= previous.length && current.startsWith(previous)) return // append-only, normal

        val commonPrefix = longestCommonPrefixLength(previous, current)
        val changedRatio = 1.0 - (commonPrefix.toDouble() / previous.length)
        if (changedRatio < 0.5) return

        val now = System.currentTimeMillis()
        state.bigRedrawTimestamps.addLast(now)
        while (state.bigRedrawTimestamps.isNotEmpty() && now - state.bigRedrawTimestamps.first() > FALLBACK_WINDOW_MS) {
            state.bigRedrawTimestamps.removeFirst()
        }
        if (state.bigRedrawTimestamps.size >= FALLBACK_THRESHOLD && !state.fallbackActive) {
            state.fallbackActive = true
            fallbackBanner.visibility = View.VISIBLE
        }
    }

    private fun longestCommonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    // ---- rendering helpers ----

    private fun renderBlock(session: TerminalSession, block: SimpleBlock) {
        when (block) {
            is SimpleBlock.UserCommand -> messagesContainer.addView(buildUserBubble(block.text))
            is SimpleBlock.AssistantMessage -> messagesContainer.addView(buildAssistantCard(block))
            is SimpleBlock.ActionRow -> messagesContainer.addView(buildActionRow(session, block.actions))
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun buildUserBubble(text: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
        }
        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(context.kairosThemeColor(R.attr.kairosPurple), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(40)
            }
        }
        row.addView(bubble)
        return row
    }

    private fun buildAssistantCard(block: SimpleBlock.AssistantMessage): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(context.kairosThemeColor(R.attr.kairosBg2), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
                rightMargin = dp(40)
            }
        }

        block.steps.forEach { step ->
            card.addView(TextView(context).apply {
                text = "${step.icon} ${step.text}"
                setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                textSize = 13f
                setPadding(0, dp(2), 0, dp(2))
            })
        }

        if (block.diffLines.isNotEmpty()) {
            val diffBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundedDrawable(context.kairosThemeColor(R.attr.kairosBg), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            }
            block.diffLines.forEach { diff ->
                diffBox.addView(TextView(context).apply {
                    text = (if (diff.added) "+ " else "- ") + diff.text
                    typeface = Typeface.MONOSPACE
                    textSize = 12.5f
                    setTextColor(context.kairosThemeColor(if (diff.added) R.attr.kairosGreen else R.attr.kairosRed))
                })
            }
            card.addView(diffBox)
        }

        if (block.plainText.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = block.plainText
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
                textSize = 13f
                setPadding(0, dp(4), 0, dp(4))
            })
        }

        block.summary?.let { summaryText ->
            card.addView(TextView(context).apply {
                text = summaryText
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            })
        }

        if (card.childCount == 0) {
            card.addView(TextView(context).apply {
                text = "…"
                setTextColor(context.kairosThemeColor(R.attr.kairosText3))
            })
        }
        return card
    }

    private fun buildActionRow(session: TerminalSession, actions: List<SimpleAction>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        actions.forEach { action ->
            val btn = MaterialButton(context).apply {
                text = action.label
                textSize = 12.5f
                isAllCaps = false
                setOnClickListener { session.write(action.command + "\n") }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            }
            row.addView(btn)
        }
        return row
    }

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val FALLBACK_WINDOW_MS = 4000L
        private const val FALLBACK_THRESHOLD = 3
    }
}
