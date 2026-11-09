package com.termux.app.terminal.simple

import com.termux.terminal.TerminalSession

/**
 * Contrato de un "adapter" de modo Simple — traduce la salida cruda de una sesión de terminal a
 * [SimpleBlock]s presentables como chat/controles, para UN tipo de sesión/módulo específico.
 *
 * Por qué existe esta interfaz (no un parser hardcodeado dentro del renderer): el modo Simple no
 * es solo "chat para CLIs de IA" — pedido explícito del usuario durante esta misma ronda de
 * implementación: "ojo no solo para los cli la terminal debe funcionar y ser simple para cada
 * cosa, sea cli, ciber seguridad, practicamente muchas cosas que pasaron de terminal a interfaz
 * [...] es intentar pasar las cosas de terminal a interfaz con botones, opciones y facil de
 * usar". Un adapter nuevo (ej. un futuro CiberseguridadSimpleAdapter con campos de IP/puerto y un
 * botón "Escanear" real) puede implementar [quickActions] con controles reales sin tocar
 * TerminalSimpleRenderer ni [CliChatSimpleAdapter] — ver MEJORAS_PENDIENTES.md "Modo Simple —
 * adapters futuros" para la lista de candidatos identificados en esta ronda.
 */
interface TerminalSimpleAdapter {
    /** Identificador corto, usado solo para logs/debug. */
    val id: String

    /** ¿Este adapter sabe presentar la sesión dada? El primero que devuelva true, en el orden de
     *  [TerminalSimpleAdapterRegistry], gana. */
    fun canHandle(session: TerminalSession): Boolean

    /**
     * Traduce el estado actual de la sesión a bloques renderizables NUEVOS (no re-emite bloques
     * ya devueltos en una llamada anterior — TerminalSimpleRenderer acumula el resultado).
     *
     * @param previousTranscript texto completo (líneas envueltas unidas) visto en el refresh anterior
     * @param currentTranscript texto completo actual
     * @param userJustSubmitted true si el usuario presionó Enter desde el refresh anterior (ver
     *   TermuxTerminalViewClient#onKeyDown()) — señal de que ahí empieza/termina un turno
     */
    fun parse(previousTranscript: String, currentTranscript: String, userJustSubmitted: Boolean): List<SimpleBlock>

    /** Controles persistentes de este adapter (ej. botones de un futuro adapter de
     *  Ciberseguridad) — se muestran siempre arriba de los mensajes, sin depender del parseo de
     *  salida. Vacío por default: la mayoría de los adapters (ej. CLIs de IA conversacionales) no
     *  necesitan controles propios más allá de la burbuja de chat. */
    fun quickActions(session: TerminalSession): List<SimpleAction> = emptyList()
}

/**
 * Selecciona qué [TerminalSimpleAdapter] usar para una sesión dada. Hoy solo existe
 * [CliChatSimpleAdapter] (genérico, siempre puede manejar cualquier sesión) — el orden de la
 * lista de abajo es donde un adapter futuro más específico (Ciberseguridad, u otro módulo hoy
 * solo-terminal) se insertaría ANTES del genérico, para que gane cuando su [canHandle] aplique.
 */
object TerminalSimpleAdapterRegistry {
    private val specificAdapters: List<TerminalSimpleAdapter> = emptyList()
    private val fallback: TerminalSimpleAdapter = CliChatSimpleAdapter()

    fun adapterFor(session: TerminalSession): TerminalSimpleAdapter =
        specificAdapters.firstOrNull { it.canHandle(session) } ?: fallback
}
