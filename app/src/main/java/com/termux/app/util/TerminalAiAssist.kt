package com.termux.app.util

import com.termux.terminal.TerminalSession

/**
 * "Terminal web con asistencia de IA" (patrón de 1Panel, `agent/utils/terminal/ws_session.go`:
 * intercepta el Enter para que una IA reescriba/sugiera el comando antes de ejecutarlo) --
 * hallazgo real de docs/referencias/vps-hosting/AUDITORIA_CATEGORIA_VPS_HOSTING.md punto 8,
 * "portable HOY a la terminal adaptada ya existente".
 *
 * Deliberadamente NO se interceptó el Enter real (a diferencia de 1Panel) -- forzar eso en
 * TermuxTerminalViewClient.onKeyDown() tocaría el camino de entrada más transitado de toda la
 * app para TODOS los usuarios, incluso quienes nunca pidieron asistencia de IA. En su lugar: una
 * acción explícita del sidebar ("✨ Revisar comando con IA", ver
 * TermuxActivity.populateAdaptedDrawerContent()) que el usuario toca cuando quiere, nunca
 * automática -- mismo criterio que kairos-product-philosophy.md ("asistir la acción, no
 * forzarla"). El usuario revisa la sugerencia y decide si la usa; nunca se envía un comando
 * (Enter) en su nombre sin que él lo presione.
 */
object TerminalAiAssist {

    /**
     * Última línea no vacía del transcript visible -- el PTY ya ecoa lo tipeado tecla por tecla
     * ANTES de que el usuario presione Enter, así que esto captura el comando en curso (mismo
     * supuesto ya usado por TerminalSimpleRenderer.onUserSubmit()). null si no hay sesión/
     * transcript o si la línea quedó vacía tras quitar el prompt.
     */
    fun extractCurrentLine(session: TerminalSession?): String? {
        val emulator = session?.emulator ?: return null
        val transcript = emulator.screen.transcriptText ?: return null
        val lastLine = transcript.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return null
        val stripped = stripPromptPrefix(lastLine)
        return stripped.ifBlank { null }
    }

    // Mismo heurístico que TerminalSimpleRenderer.stripPromptPrefix() (duplicado a propósito --
    // es una función de 2 líneas, no vale la pena exponerla pública desde esa clase solo para
    // esto).
    private fun stripPromptPrefix(line: String): String {
        val idx = line.indexOfLast { it == '$' || it == '>' || it == '#' }
        return if (idx in 0 until line.length - 1) line.substring(idx + 1).trim() else line
    }

    data class Review(val ok: Boolean, val explanation: String, val suggestion: String?)

    /**
     * Corre en el hilo que llama (bloqueante, pensado para un Thread de fondo, ver
     * TermuxActivity) -- reusa el Agente YA instalado (chatDirecto(), mismo motor que el chat
     * directo del Agente) en vez de hablar HTTP de nuevo con el proveedor de IA. [command] nunca
     * se ejecuta -- solo se manda como texto a revisar.
     */
    fun reviewCommand(command: String): Review {
        if (!KairosAgentClient.isInstalled()) {
            return Review(false, "El Agente no está instalado todavía (Más → Agente) -- hace falta para esta función.", null)
        }
        val prompt = buildString {
            append("Estás ayudando a alguien que está usando una terminal Linux/Termux en un teléfono Android. ")
            append("Escribió este comando, todavía SIN presionar Enter: `").append(command).append("`. ")
            append("Si tiene un error de sintaxis obvio, un typo, o se puede mejorar de forma clara y segura, ")
            append("respondé en 2 líneas: la primera con 'SUGERENCIA: <comando corregido>' (una sola línea, sin backticks ni explicación en esa línea), ")
            append("la segunda con una explicación de 1 frase corta de por qué. ")
            append("Si el comando ya está bien tal cual, respondé solo 'OK: el comando se ve bien.' ")
            append("Nunca ejecutes nada ni sugieras comandos destructivos (rm -rf, dd, mkfs, etc.) sin advertir explícitamente del riesgo en la explicación.")
        }
        val json = KairosAgentClient.chatDirecto(prompt) ?: return Review(false, "Ningún modelo de IA (local o configurado) respondió.", null)
        if (!json.optBoolean("ok", true)) {
            return Review(false, json.optString("error").ifBlank { "no se pudo completar" }, null)
        }
        val reply = json.optString("reply").ifBlank { "(sin respuesta)" }
        val suggestionLine = reply.lineSequence().firstOrNull { it.trim().startsWith("SUGERENCIA:", ignoreCase = true) }
        val rawSuggestion = suggestionLine?.substringAfter(":")?.trim()?.trim('`')?.ifBlank { null }
        // Hallazgo real de revisión automática (2026-11-05, "control-character injection"): la
        // respuesta del LLM es texto NO confiable (igual que cualquier salida de red) -- si
        // contuviera un '\n'/'\r' embebido o una secuencia ANSI/ESC, escribirla tal cual al PTY
        // podría "presionar Enter" en nombre del usuario (exactamente lo que este diseño evita a
        // propósito, ver KDoc de la clase) o inyectar control de terminal. sanitizeForPty()
        // quita TODO carácter de control (incluye recorte defensivo del largo); si después de
        // sanitizar la sugerencia queda distinta de lo que el LLM realmente mandó, se descarta
        // entera en vez de usar una versión parcial que el usuario nunca vio en el diálogo.
        val suggestion = rawSuggestion?.let { raw -> sanitizeForPty(raw).takeIf { it == raw } }
        return Review(true, reply, suggestion)
    }

    private const val MAX_SUGGESTION_LENGTH = 500

    /** Quita todo carácter de control (0x00-0x1F, 0x7F, C1 0x80-0x9F) y recorta a
     *  [MAX_SUGGESTION_LENGTH] -- defensa real contra texto no confiable (salida de un LLM)
     *  antes de escribirlo a un PTY. */
    private fun sanitizeForPty(text: String): String =
        text.filter { c -> (c.code !in 0x00..0x1F) && c.code != 0x7F && (c.code !in 0x80..0x9F) }
            .take(MAX_SUGGESTION_LENGTH)

    /** Backspaces (DEL, 0x7F -- el carácter de borrado real que el PTY/termios espera, mismo
     *  valor que ya usa TerminalView.onKeyDown() para KEYCODE_DEL) para borrar [originalLength]
     *  caracteres del comando ya tipeado, seguido del [suggestion] (ya sanitizado por
     *  [reviewCommand], re-sanitizado acá igual por defensa en profundidad) -- NUNCA un '\n'
     *  acá: el usuario revisa y presiona Enter él mismo, esta función solo reemplaza el texto en
     *  la línea de entrada. */
    fun applySuggestion(session: TerminalSession, originalLength: Int, suggestion: String) {
        val clean = sanitizeForPty(suggestion)
        if (clean.isBlank()) return
        val backspaces = "\u007F".repeat(originalLength)
        session.write(backspaces)
        session.write(clean)
    }
}
