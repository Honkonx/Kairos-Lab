package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAvatarPhrasesTest {

    @Test
    fun `pickRandom returns null for an empty list`() {
        assertNull(AgentAvatarPhrases.pickRandom(emptyList(), 0.5))
    }

    @Test
    fun `pickRandom picks the first phrase near 0`() {
        val phrases = listOf("a", "b", "c")
        assertEquals("a", AgentAvatarPhrases.pickRandom(phrases, 0.0))
    }

    @Test
    fun `pickRandom picks the last phrase near 1`() {
        val phrases = listOf("a", "b", "c")
        assertEquals("c", AgentAvatarPhrases.pickRandom(phrases, 0.999))
    }

    @Test
    fun `pickRandom clamps out-of-range values instead of crashing`() {
        val phrases = listOf("a", "b")
        assertEquals("a", AgentAvatarPhrases.pickRandom(phrases, -5.0))
        assertEquals("b", AgentAvatarPhrases.pickRandom(phrases, 5.0))
    }

    @Test
    fun `nextIntervalMs with zero jitter returns the base interval`() {
        assertEquals(10_000L, AgentAvatarPhrases.nextIntervalMs(10_000L, 0L, 0.73))
    }

    @Test
    fun `nextIntervalMs stays within base plus-minus jitter`() {
        val base = 60_000L
        val jitter = 10_000L
        val low = AgentAvatarPhrases.nextIntervalMs(base, jitter, 0.0)
        val high = AgentAvatarPhrases.nextIntervalMs(base, jitter, 0.999999)
        val mid = AgentAvatarPhrases.nextIntervalMs(base, jitter, 0.5)
        assertEquals(base - jitter, low)
        assertTrue(high in (base + jitter - 50)..(base + jitter))
        assertTrue(mid in (base - 200)..(base + 200))
    }

    @Test
    fun `nextIntervalMs never returns less than one second`() {
        val result = AgentAvatarPhrases.nextIntervalMs(500L, 5_000L, 0.0)
        assertEquals(1_000L, result)
    }

    @Test
    fun `default constants are sane`() {
        assertTrue(AgentAvatarPhrases.DEFAULT_INTERVAL_MS > AgentAvatarPhrases.DEFAULT_JITTER_MS)
        assertTrue(AgentAvatarPhrases.BUBBLE_VISIBLE_MS > 0)
    }
}
