package com.termux.app.util

import com.termux.terminal.TerminalSession

/**
 * Detección de prompts de permiso en CLIs agénticos — lado Kotlin del contrato
 * `session_permission` (ver ModuleEventBridge.kt, header "CONTRATO DE EVENTOS DE SESIÓN").
 *
 * Investigado 2026-08-17 (ronda "faltan session_idle/session_permission"): de los CLIs
 * agénticos que Kairos soporta con `terminalCommand` (Claude Code, Codex CLI, Antigravity CLI,
 * OpenCode), SOLO se pudo confirmar un patrón de texto real y estable — "Do you want to
 * proceed?" para Claude Code, reportado tal cual en issues reales del propio repo de
 * Anthropic (github.com/anthropics/claude-code#11380, #2560) como el texto exacto que
 * imprime antes de pedir aprobación de una herramienta. Codex CLI y OpenCode NO tienen un
 * string de prompt confirmado en ninguna fuente pública encontrada esta ronda — agregarlos a
 * ciegas violaría la regla explícita de esta ronda ("no inventar un regex sobre texto no
 * confirmado"). Quedan deliberadamente fuera hasta poder confirmar el patrón real de cada uno
 * (correr el CLI a mano y capturar el prompt, o encontrar su código fuente).
 *
 * `sessionName` es el nombre real de la TerminalSession (== getModuleName() del fragment que
 * la abrió, ver BaseModuleFragment.launchTerminalCommand) — NO es el `module_id` de
 * modules.json. [moduleIdFor] traduce el único caso confirmado al id que espera el contrato.
 */
object SessionPromptDetector {

    private val moduleIdBySessionName = mapOf(
        "Claude Code" to "claude"
    )

    // Patrones confirmados por module_id — cada entrada con su fuente documentada arriba.
    // IGNORE_CASE porque no hay garantía de que el CLI mantenga las mayúsculas exactas entre
    // versiones; el string en sí ya es lo bastante específico para no dar falsos positivos
    // solo por eso.
    private val confirmedPatterns = mapOf(
        "claude" to Regex("Do you want to proceed\\??", RegexOption.IGNORE_CASE)
    )

    /** module_id del contrato de sesión si [sessionName] es un CLI con detección soportada — null si no. */
    @JvmStatic
    fun moduleIdFor(sessionName: String): String? = moduleIdBySessionName[sessionName]

    /**
     * true si [visibleScreenText] (última pantalla visible de la sesión, NO el scrollback
     * completo — ver [visibleScreenText]) matchea el patrón de permiso confirmado para
     * [moduleId]. false para cualquier módulo sin patrón confirmado (nunca lanza).
     */
    @JvmStatic
    fun looksLikePermissionPrompt(moduleId: String, visibleScreenText: String): Boolean {
        val pattern = confirmedPatterns[moduleId] ?: return false
        if (visibleScreenText.isBlank()) return false
        return pattern.containsMatchIn(visibleScreenText)
    }

    /**
     * Texto de la pantalla VISIBLE de [session] (no el historial de scrollback completo —
     * TerminalBuffer.getTranscriptText() podría ser enorme y no es lo que importa acá: un
     * prompt de permiso siempre está en lo último impreso). Defensivo — nunca lanza, devuelve
     * "" ante cualquier estado inesperado del emulador (ej. sesión recién cerrada).
     */
    @JvmStatic
    fun visibleScreenText(session: TerminalSession): String {
        return try {
            val emulator = session.emulator ?: return ""
            val screen = emulator.screen ?: return ""
            screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
        } catch (_: Exception) {
            ""
        }
    }
}
