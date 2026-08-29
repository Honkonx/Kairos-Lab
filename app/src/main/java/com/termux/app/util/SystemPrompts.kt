package com.termux.app.util

import android.content.Context

/**
 * Presets de persona (system prompt) para el Chat IA — presets curados de personalidad/
 * rol que se inyectan como mensaje `system` en los requests de chat (Ollama y llama-server).
 * Selección y persistencia en las MISMAS SharedPreferences que ya usa ChatFragment
 * (`kairos_llm_prefs`), para que LocalAIFragment y el resto del stack compartan el estado.
 *
 * La persona por defecto ("Asistente general") tiene el prompt VACÍO a propósito: con ella,
 * el armado de `messages` de ChatFragment produce exactamente el mismo resultado que antes
 * de existir los presets (solo el system prompt custom de Ollama, sin ninguna inyección extra).
 */
object SystemPrompts {

    private const val PREFS_NAME = "kairos_llm_prefs"
    private const val KEY_CURRENT_ID = "persona_id"

    data class Persona(
        val id: String,
        val name: String,
        val description: String,
        val prompt: String,
    )

    /**
     * Presets disponibles. `asistente-general` va primero y es el default — su prompt vacío
     * significa "no inyectar nada" (comportamiento actual del chat).
     */
    val personas: List<Persona> = listOf(
        Persona(
            id = "asistente-general",
            name = "Asistente general",
            description = "Neutral — comportamiento por defecto del chat",
            prompt = "",
        ),
        Persona(
            id = "programador",
            name = "Programador experto",
            description = "Código, refactor y buenas prácticas",
            prompt = "Sos un programador experto con dominio profundo de varios lenguajes y " +
                "arquitectura de software. Escribís código limpio, correcto y mantenible: " +
                "explicás el razonamiento detrás de cada decisión, preferís soluciones simples " +
                "e idiomáticas, señalás riesgos y edge cases, y ante ambigüedades preguntás " +
                "antes de asumir.",
        ),
        Persona(
            id = "traductor",
            name = "Traductor ES-EN",
            description = "Traducciones naturales entre español e inglés",
            prompt = "Sos un traductor profesional de español e inglés. Traducís con " +
                "naturalidad y fidelidad al registro original (formal, coloquial, técnico), " +
                "preservando matices culturales y de tono. Si el texto es ambiguo o no tiene " +
                "traducción directa, lo aclarás brevemente. No agregás opiniones: solo traducción.",
        ),
        Persona(
            id = "explicador",
            name = "Explicador didáctico",
            description = "Conceptos complejos, paso a paso y con ejemplos",
            prompt = "Sos un explicador didáctico. Explicás conceptos complejos de forma " +
                "clara, estructurada y accesible: arrancás por la intuición central, seguís " +
                "con ejemplos concretos y recién después los detalles técnicos. Adaptás el " +
                "nivel de profundidad a quien pregunta y usás analogías sin sacrificar precisión.",
        ),
        Persona(
            id = "terminal-devops",
            name = "Terminal / DevOps",
            description = "Conciso, orientado a comandos y automatización",
            prompt = "Sos un ingeniero de terminal y DevOps. Respondés CONCISO y orientado a " +
                "acción: comandos exactos, scripts listos para copiar y pasos numerados. Asumís " +
                "entorno Linux/Termux/Android salvo que se indique otra cosa, y ante problemas " +
                "de diagnóstico priorizás el camino más rápido para aislar la causa.",
        ),
    )

    /** ID de la persona activa — el default es "asistente-general" si nunca se eligió otra. */
    @JvmStatic
    fun currentId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, 0).getString(KEY_CURRENT_ID, null)
        if (stored != null && personas.any { it.id == stored }) return stored
        return personas.first().id
    }

    @JvmStatic
    fun save(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, 0).edit().putString(KEY_CURRENT_ID, id).apply()
    }

    /** Persona activa (o la por defecto si el id guardado ya no existe en [personas]). */
    @JvmStatic
    fun current(context: Context): Persona =
        personas.firstOrNull { it.id == currentId(context) } ?: personas.first()

    /** Prompt de la persona activa — vacío para "Asistente general" (= no inyectar nada). */
    @JvmStatic
    fun currentPrompt(context: Context): String = current(context).prompt
}