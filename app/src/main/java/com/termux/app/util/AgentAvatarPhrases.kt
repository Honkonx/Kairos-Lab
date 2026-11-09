package com.termux.app.util

/**
 * Lógica pura (sin Android) del avatar flotante del Agente (AgentAvatarService.kt): qué frase
 * mostrar y cada cuánto "dice algo". Separada de la vista/servicio a propósito para poder
 * probarla con JUnit puro, sin Robolectric — ver docs/agente/DECISIONES_AGENTE_2026-10-10.md
 * ("cara mínima" que flota en una esquina y de vez en cuando dice algo breve).
 */
object AgentAvatarPhrases {

    /** Intervalo por defecto entre apariciones del globo de texto: 4 minutos. */
    const val DEFAULT_INTERVAL_MS: Long = 4 * 60 * 1000L

    /** Variación máxima (+/-) sobre el intervalo, para que no se sienta mecánico: 1 minuto. */
    const val DEFAULT_JITTER_MS: Long = 60 * 1000L

    /** Cuánto queda visible el globo de texto antes de desaparecer solo. */
    const val BUBBLE_VISIBLE_MS: Long = 5000L

    /**
     * Elige una frase de la lista según [randomDouble] (se espera un valor en [0, 1), como
     * `Math.random()`). Devuelve null si la lista está vacía — el llamador decide qué hacer
     * (típicamente: no mostrar nada).
     */
    fun pickRandom(phrases: List<String>, randomDouble: Double): String? {
        if (phrases.isEmpty()) return null
        val clamped = randomDouble.coerceIn(0.0, 0.999999999)
        val index = (clamped * phrases.size).toInt().coerceIn(0, phrases.size - 1)
        return phrases[index]
    }

    /**
     * Próximo intervalo de espera, con variación aleatoria simétrica alrededor de [baseMs].
     * [randomDouble] en [0, 1) (como `Math.random()`); nunca devuelve menos de 1 segundo, para
     * que un jitter mal configurado no deje al servicio llamándose a sí mismo sin pausa real.
     */
    fun nextIntervalMs(baseMs: Long, jitterMs: Long, randomDouble: Double): Long {
        if (jitterMs <= 0) return baseMs.coerceAtLeast(1000L)
        val clamped = randomDouble.coerceIn(0.0, 0.999999999)
        val offset = ((clamped * 2.0 - 1.0) * jitterMs).toLong()
        return (baseMs + offset).coerceAtLeast(1000L)
    }
}
