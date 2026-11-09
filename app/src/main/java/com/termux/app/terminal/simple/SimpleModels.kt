package com.termux.app.terminal.simple

/**
 * Modelo de datos del modo Simple de la terminal adaptada (ver MEJORAS_PENDIENTES.md "Roadmap de
 * terminal — 2 fases", Fase 2). Deliberadamente separado de [com.termux.terminal.TerminalSession]
 * y de cualquier CLI puntual — un [TerminalSimpleAdapter] traduce salida cruda a estos bloques,
 * TerminalSimpleRenderer solo sabe pintarlos.
 */

/** Un paso individual dentro de una respuesta de CLI (ej. "🔍 Buscando X…", "✓ Encontrado: Y"). */
data class SimpleStep(val icon: String, val text: String)

/** Una línea de diff (+/-) dentro de una respuesta — coloreada verde/rojo en el renderer. */
data class SimpleDiffLine(val added: Boolean, val text: String)

/**
 * Una acción/botón ofrecido por un [TerminalSimpleAdapter] — al tocarla, escribe [command] + "\n"
 * en la sesión real (mismo mecanismo que los "comandos personalizados" del drawer normal, ver
 * TerminalCustomCommandsManager.kt). Nunca reemplaza la sesión ni el proceso, solo la maneja por
 * el usuario — es el mecanismo genérico para que un módulo (ej. Ciberseguridad, ver
 * MEJORAS_PENDIENTES.md "Modo Simple — adapters futuros") ofrezca controles reales en vez de solo
 * texto reformateado, cumpliendo `.claude/rules/kairos-product-philosophy.md`.
 */
data class SimpleAction(val label: String, val command: String)

/** Un bloque renderizable dentro del modo Simple — ver [TerminalSimpleAdapter.parse]. */
sealed class SimpleBlock {
    /** Burbuja del usuario (comando tipeado, capturado en Enter — ver
     *  TerminalSimpleRenderer#onUserSubmit()). */
    data class UserCommand(val text: String) : SimpleBlock()

    /** Respuesta del CLI: pasos + diffs + texto plano + resumen final, todos opcionales salvo que
     *  al menos uno tenga contenido real (ver CliChatSimpleAdapter). */
    data class AssistantMessage(
        val steps: List<SimpleStep> = emptyList(),
        val diffLines: List<SimpleDiffLine> = emptyList(),
        val plainText: String = "",
        val summary: String? = null
    ) : SimpleBlock()

    /** Fila de controles (botones) específicos del adapter activo — a diferencia de
     *  [AssistantMessage], no viene del parseo de la salida, es un bloque persistente que
     *  [TerminalSimpleAdapter.quickActions] ofrece de entrada. */
    data class ActionRow(val actions: List<SimpleAction>) : SimpleBlock()
}
