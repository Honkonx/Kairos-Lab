package com.termux.app.terminal.simple

import com.termux.terminal.TerminalSession

/**
 * Adapter default de modo Simple — asume una interacción tipo "chat" con un CLI de línea de
 * comandos (Claude Code, Codex, OpenCode, o cualquier otro no reconocido explícitamente): agrupa
 * la salida entre dos Enter del usuario en UNA respuesta, separando pasos (glifos conocidos),
 * bloques de diff (+/-) y texto plano. Ver KDoc de [TerminalSimpleAdapter] para el porqué de la
 * interfaz genérica (este es UN adapter, no "el" mecanismo de modo Simple).
 *
 * Heurística, no un parser de terminal completo — riesgo ya documentado en
 * MEJORAS_PENDIENTES.md "Roadmap de terminal — 2 fases". Consume el texto YA decodificado que
 * expone TerminalBuffer (getTranscriptTextWithFullLinesJoined(), sin secuencias ANSI, líneas
 * envueltas ya unidas) en vez de reimplementar un parser VT100 — no intenta reconstruir una app de
 * pantalla completa. La detección de "no se puede simplificar" (TUIs de pantalla completa como
 * htop/vim, que redibujan la pantalla entera todo el tiempo) vive en TerminalSimpleRenderer (mide
 * redibujos, no parseo de línea) — este adapter solo agrupa texto ya dado por bueno.
 */
class CliChatSimpleAdapter : TerminalSimpleAdapter {
    override val id = "cli_chat"

    // Siempre puede "manejar" cualquier sesión — es el fallback genérico, ver
    // TerminalSimpleAdapterRegistry.
    override fun canHandle(session: TerminalSession): Boolean = true

    override fun parse(previousTranscript: String, currentTranscript: String, userJustSubmitted: Boolean): List<SimpleBlock> {
        if (currentTranscript.isEmpty()) return emptyList()

        // Solo procesamos texto NUEVO desde el refresh anterior — evita re-renderizar toda la
        // sesión en cada refresh (TerminalSimpleRenderer llama a parse() en cada onTextChanged,
        // que puede disparar decenas de veces por segundo durante un stream largo de tokens).
        val appended = newSuffix(previousTranscript, currentTranscript)
        if (appended.isBlank()) return emptyList()

        val steps = mutableListOf<SimpleStep>()
        val diffLines = mutableListOf<SimpleDiffLine>()
        val plainLines = mutableListOf<String>()

        for (rawLine in appended.split("\n")) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            val stepGlyph = STEP_GLYPHS.firstOrNull { trimmed.startsWith(it) }
            when {
                stepGlyph != null ->
                    steps.add(SimpleStep(stepGlyph, trimmed.removePrefix(stepGlyph).trim()))
                isDiffAddedLine(trimmed) -> diffLines.add(SimpleDiffLine(added = true, text = trimmed.substring(1)))
                isDiffRemovedLine(trimmed) -> diffLines.add(SimpleDiffLine(added = false, text = trimmed.substring(1)))
                isShellPromptEcho(trimmed) -> { /* eco del prompt del shell — no aporta a la burbuja */ }
                else -> plainLines.add(line)
            }
        }

        // "Un resumen final en negrita" (mockup aprobado) — heurística simple: si quedan varias
        // líneas de texto plano, la ÚLTIMA (corta, <=100 chars) se separa como resumen en vez de
        // quedar mezclada en el cuerpo. Con una sola línea de texto plano no hay "resumen" que
        // separar de un cuerpo — sería la misma línea duplicada.
        var summary: String? = null
        if (plainLines.size > 1 && plainLines.last().length <= 100) {
            summary = plainLines.removeAt(plainLines.lastIndex)
        }

        if (steps.isEmpty() && diffLines.isEmpty() && plainLines.isEmpty() && summary == null) return emptyList()

        return listOf(SimpleBlock.AssistantMessage(steps, diffLines, plainLines.joinToString("\n"), summary))
    }

    private fun isDiffAddedLine(line: String) =
        line.startsWith("+") && !line.startsWith("++") && line.length > 1 && line[1] != ' '

    private fun isDiffRemovedLine(line: String) =
        line.startsWith("-") && !line.startsWith("--") && line.length > 1 && line[1] != ' '

    // Solo se descarta como "prompt vacío" si la línea entera es corta y termina en el glifo de
    // prompt — una línea larga de texto real que TERMINE en "$"/">"/"#" (bastante común en
    // prosa/código) no debe perderse silenciosamente, así que el umbral de longitud es angosto
    // a propósito.
    private fun isShellPromptEcho(line: String): Boolean =
        line.length <= 40 && PROMPT_SUFFIXES.any { line.endsWith(it) }

    /** Texto agregado desde el último refresh — si el buffer circular descartó líneas viejas por
     *  overflow (o hubo un redibujo parcial), [currentTranscript] deja de empezar con
     *  [previousTranscript] y no hay forma barata de diffear de forma robusta; se devuelve todo el
     *  texto actual y se deja que TerminalSimpleRenderer decida si eso cuenta como "redibujo
     *  grande" para el fallback (ver su detectBigRedraw()). */
    private fun newSuffix(previous: String, current: String): String {
        if (previous.isEmpty()) return current
        if (!current.startsWith(previous)) return current
        return current.substring(previous.length)
    }

    companion object {
        // Glifos reales usados por Claude Code/Codex/OpenCode para marcar pasos de progreso (el
        // sidebar de terminal adaptada de TermuxActivity ya usa "●"/"⎿" para el mismo propósito
        // en otro contexto — ver populateAdaptedDrawerContent()).
        private val STEP_GLYPHS = listOf("●", "⎿", "✓", "✗", "⏺", "🔍", "✎", "✅", "❌")
        private val PROMPT_SUFFIXES = listOf("$ ", "$", "> ", "#")
    }
}
