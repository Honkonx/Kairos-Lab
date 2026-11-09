package com.termux.app.util

/**
 * Trocea texto largo en fragmentos acotados y selecciona los más relevantes a una consulta,
 * sin embeddings ni tokenizer real — mismo algoritmo que `documents.ts::chunkDocument()`/
 * `selectRelevantChunks()` de `referencia/ia/Local-Browser-AI` (ver
 * docs/referencias/ia/AUDITORIA_LOCAL_BROWSER_AI_2026-09-09.md, hallazgo #1): trocear por
 * tamaño con corte en límite de palabra/línea, puntuar cada fragmento por cuántos términos
 * comparte con la consulta (TF simple, sin idf ni embeddings — honesto sobre lo que hace: no
 * es búsqueda semántica real, es superposición de palabras), y llenar un presupuesto de
 * caracteres empezando por los fragmentos más relevantes.
 *
 * Utilidad genérica a propósito (repropósito creativo, ver
 * `.claude/rules/kairos-reference-fragment-extraction.md`) — no vive dentro de ChatFragment
 * porque el mismo mecanismo sirve para cualquier lugar de Kairos con más texto disponible que
 * contexto de LLM: recortar un log largo de MonitorFragment antes de pasarlo a un agente de
 * IA, o seleccionar solo las partes relevantes de un archivo grande antes de dárselo como
 * contexto a un módulo CLI (OpenClaw, Claude Code, Kilo Code) desde la terminal adaptada, en
 * vez de truncar a lo bruto por tamaño.
 */
object DocumentChunker {

    data class Chunk(val text: String, val index: Int)

    private const val DEFAULT_CHUNK_SIZE_CHARS = 800

    // Solapamiento entre fragmentos consecutivos (hallazgo real de
    // docs/referencias/ia/AUDITORIA_LOCAL_BROWSER_AI_2026-09-09.md -- la referencia usa 150
    // caracteres): sin esto, una idea que cae justo en el límite del corte quedaba repartida
    // entre 2 fragmentos sin que ninguno de los dos tuviera el contexto completo. El tamaño de
    // chunk propio de Kairos (800, vs. 1200 de la referencia) no se tocó -- es una decisión ya
    // tomada aparte, el gap real era la falta de overlap/bonus, no el tamaño.
    private const val DEFAULT_OVERLAP_CHARS = 150

    // Bonus de relevancia al PRIMER fragmento (mismo valor que la referencia, documents.ts:43-48):
    // intros/resúmenes suelen concentrar la idea central de un documento aunque compartan pocos
    // términos EXACTOS con la consulta -- sin este empuje, un documento largo con la respuesta
    // resumida en la primera línea podía perder ante fragmentos intermedios con más repeticiones
    // literales de palabras de la consulta pero menos señal real.
    private const val FIRST_CHUNK_BONUS = 0.25

    // Solo letras/dígitos (Unicode) cuentan como "término" — puntuación y espacios no aportan
    // señal de relevancia.
    private val WORD_REGEX = Regex("[\\p{L}\\p{Nd}]+")

    // Términos demasiado cortos (artículos, preposiciones cortas) casi no aportan señal real
    // de relevancia — heurístico simple a propósito, sin una lista completa de stopwords por
    // idioma (sería sobre-ingeniería para un scoring TF tan básico).
    private const val MIN_TERM_LENGTH = 3

    /**
     * Trocea `text` en fragmentos de hasta `chunkSize` caracteres, cortando en el límite de
     * palabra/línea más cercano (nunca a mitad de una palabra) — mismo criterio que
     * `chunkDocument()` de la referencia. `overlapChars` repite esa cantidad de caracteres del
     * final de un fragmento al principio del siguiente (ver [DEFAULT_OVERLAP_CHARS]), para que
     * una idea que cae justo en el límite del corte no quede sin contexto completo en ningún
     * fragmento.
     */
    fun chunkText(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<Chunk> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < normalized.length) {
            var end = (start + chunkSize).coerceAtMost(normalized.length)
            if (end < normalized.length) {
                // Retrocede hasta el último salto de línea/espacio antes del corte crudo para
                // no partir una palabra al medio. Si no hay ninguno cerca (texto sin espacios,
                // ej. una URL larga), se corta igual en el límite crudo.
                val lastBreak = normalized.lastIndexOfAny(charArrayOf('\n', ' '), end - 1)
                if (lastBreak > start) end = lastBreak
            }
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks.add(Chunk(piece, chunks.size))
            if (end >= normalized.length) break
            // El siguiente fragmento retrocede `overlapChars` desde este final -- nunca menos
            // de `start + 1`, para que `start` siempre avance y el bucle termine aunque
            // `overlapChars` sea >= `chunkSize`. El punto de retroceso crudo puede caer a mitad
            // de palabra (ej. "...w10..." retrocedido justo al medio de "w10") -- se avanza
            // hasta el siguiente separador y se salta el separador en sí, para que el fragmento
            // nuevo arranque siempre en el principio de una palabra real, nunca cortada.
            var candidate = (end - overlapChars).coerceAtLeast(start + 1)
            while (candidate < normalized.length && normalized[candidate] != ' ' && normalized[candidate] != '\n') candidate++
            while (candidate < normalized.length && (normalized[candidate] == ' ' || normalized[candidate] == '\n')) candidate++
            start = candidate.coerceAtMost(end)
        }
        return chunks
    }

    /** Términos significativos (>= MIN_TERM_LENGTH), en minúsculas — usado tanto para indexar
     *  cada fragmento como para interpretar la consulta. */
    private fun significantTerms(text: String): List<String> =
        WORD_REGEX.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= MIN_TERM_LENGTH }
            .toList()

    /**
     * Devuelve el texto combinado de los fragmentos más relevantes a `query` (conteo de
     * términos compartidos, sin idf/embeddings) hasta llenar `maxChars`. Si ningún fragmento
     * comparte términos con la consulta, se toma desde el principio del documento en vez de
     * devolver vacío — un documento adjunto sin ningún término en común con la pregunta igual
     * puede aportar contexto real, no hay que descartarlo en silencio.
     */
    fun selectRelevantChunks(chunks: List<Chunk>, query: String, maxChars: Int): String {
        if (chunks.isEmpty() || maxChars <= 0) return ""
        val queryTerms = significantTerms(query).toSet()
        val scored = chunks.map { chunk ->
            val termScore = if (queryTerms.isEmpty()) 0 else significantTerms(chunk.text).count { it in queryTerms }
            // Bonus al primer fragmento del documento (ver FIRST_CHUNK_BONUS) -- se suma SIEMPRE,
            // no solo cuando queryTerms no está vacío, para que un documento sin ningún término
            // en común con la consulta igual priorice su propio inicio sobre el resto.
            val score = termScore + if (chunk.index == 0) FIRST_CHUNK_BONUS else 0.0
            chunk to score
        }
        // sortedByDescending es estable: a igual score se conserva el orden original del
        // documento. Si ningún fragmento tiene señal de relevancia real (todos con termScore 0,
        // la comparación es contra FIRST_CHUNK_BONUS porque el primer chunk ya no queda en 0 por
        // el bonus), se conserva el orden original tal cual en vez de "ordenar por 0 en todos".
        val ordered = if (scored.any { it.second > FIRST_CHUNK_BONUS }) scored.sortedByDescending { it.second }.map { it.first } else chunks

        val sb = StringBuilder()
        for (chunk in ordered) {
            val separator = if (sb.isNotEmpty()) "\n\n" else ""
            val remaining = maxChars - sb.length - separator.length
            if (remaining <= 0) break
            val piece = if (chunk.text.length > remaining) chunk.text.take(remaining) else chunk.text
            sb.append(separator).append(piece)
        }
        return sb.toString()
    }
}
