package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cubre el solapamiento/bonus agregados por el hallazgo de
 *  docs/referencias/ia/AUDITORIA_LOCAL_BROWSER_AI_2026-09-09.md ("gap real de calidad de
 *  recuperación" -- Kairos no tenía overlap ni bonus al primer chunk). */
class DocumentChunkerTest {

    @Test
    fun chunkTextProducesNoChunksForBlankInput() {
        assertEquals(emptyList<DocumentChunker.Chunk>(), DocumentChunker.chunkText("   \n  "))
    }

    @Test
    fun chunkTextNeverSplitsInsideAWord() {
        // "palabra" (7) repetida -- obliga a cortes frecuentes con un chunkSize chico.
        val text = (1..50).joinToString(" ") { "palabra$it" }
        val chunks = DocumentChunker.chunkText(text, chunkSize = 30, overlapChars = 0)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("fragmento no debería empezar/terminar a mitad de palabra: '${chunk.text}'",
                chunk.text == chunk.text.trim())
        }
    }

    @Test
    fun chunkTextOverlapRepeatsTailOfPreviousChunkAtStartOfNext() {
        val text = (1..60).joinToString(" ") { "w$it" }
        val chunks = DocumentChunker.chunkText(text, chunkSize = 40, overlapChars = 10)
        assertTrue("se esperaban al menos 2 fragmentos para probar el overlap", chunks.size >= 2)
        // El final del primer fragmento (los últimos ~10 caracteres) debe reaparecer al
        // principio del segundo -- es la señal real de que el solapamiento se aplicó.
        val firstTail = chunks[0].text.takeLast(8)
        assertTrue(
            "el segundo fragmento debería retomar el final del primero ('$firstTail' no está en '${chunks[1].text.take(40)}')",
            chunks[1].text.contains(firstTail.trim().split(" ").last())
        )
    }

    @Test
    fun chunkTextWithLargeOverlapStillTerminates() {
        // overlapChars >= chunkSize no debe producir un bucle infinito -- start siempre avanza
        // al menos 1 por el coerceAtLeast(start + 1).
        val text = (1..30).joinToString(" ") { "término$it" }
        val chunks = DocumentChunker.chunkText(text, chunkSize = 20, overlapChars = 1000)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun selectRelevantChunksPrefersChunksSharingQueryTerms() {
        val chunks = listOf(
            DocumentChunker.Chunk("hola mundo, esto no tiene relación con la consulta", 0),
            DocumentChunker.Chunk("información real sobre bases de datos postgresql y migraciones", 1),
            DocumentChunker.Chunk("otro texto cualquiera sin relación", 2),
        )
        val result = DocumentChunker.selectRelevantChunks(chunks, "postgresql migraciones", maxChars = 1000)
        assertTrue(result.contains("postgresql"))
    }

    @Test
    fun selectRelevantChunksGivesBonusToFirstChunkEvenWithFewerTermMatches() {
        // El segundo fragmento comparte MÁS términos literales con la consulta, pero el primero
        // (índice 0) recibe el bonus de 0.25 -- con un solo término de diferencia, el bonus debe
        // alcanzar para que el primero aparezca ANTES en el resultado final.
        val chunks = listOf(
            DocumentChunker.Chunk("resumen datos", 0),
            DocumentChunker.Chunk("datos datos datos sin nada mas relevante aca", 1),
        )
        val result = DocumentChunker.selectRelevantChunks(chunks, "datos", maxChars = 1000)
        val firstIndex = result.indexOf("resumen datos")
        val secondIndex = result.indexOf("datos datos datos")
        assertTrue("el primer fragmento (con bonus) debería aparecer antes en el resultado combinado",
            firstIndex in 0 until secondIndex)
    }

    @Test
    fun selectRelevantChunksFallsBackToOriginalOrderWithoutAnySignal() {
        val chunks = listOf(
            DocumentChunker.Chunk("aaa", 0),
            DocumentChunker.Chunk("bbb", 1),
            DocumentChunker.Chunk("ccc", 2),
        )
        val result = DocumentChunker.selectRelevantChunks(chunks, "xyz sin coincidencias", maxChars = 1000)
        assertEquals("aaa\n\nbbb\n\nccc", result)
    }

    @Test
    fun selectRelevantChunksTruncatesLastPieceToFitBudget() {
        val chunks = listOf(DocumentChunker.Chunk("0123456789", 0))
        val result = DocumentChunker.selectRelevantChunks(chunks, "", maxChars = 5)
        assertEquals("01234", result)
    }
}
