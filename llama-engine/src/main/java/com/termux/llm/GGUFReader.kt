package com.termux.llm

/**
 * Lee metadata de un archivo .gguf (context_length, chat_template) sin
 * cargar el modelo completo — adaptado de GGUFReader.kt de OfflineLLM
 * (jegly/OfflineLLM, Apache-2.0). Bloqueante (I/O de archivo) — correr en
 * background Thread, mismo criterio que [LlamaEngine].
 */
class GGUFReader {
    companion object {
        init {
            System.loadLibrary("kairos_ggufreader")
        }
    }

    private var nativeHandle: Long = 0L

    fun load(modelPath: String) {
        nativeHandle = getGGUFContextNativeHandle(modelPath)
    }

    fun getContextSize(): Long? {
        check(nativeHandle != 0L) { "Usar GGUFReader.load() para inicializar el reader" }
        val contextSize = getContextSize(nativeHandle)
        return if (contextSize == -1L) null else contextSize
    }

    fun getChatTemplate(): String? {
        check(nativeHandle != 0L) { "Usar GGUFReader.load() para inicializar el reader" }
        val chatTemplate = getChatTemplate(nativeHandle)
        return chatTemplate.ifEmpty { null }
    }

    /** Libera el gguf_context nativo — llamar siempre después de leer lo necesario. */
    fun close() {
        if (nativeHandle != 0L) {
            freeGGUFContext(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun getGGUFContextNativeHandle(modelPath: String): Long
    private external fun getContextSize(nativeHandle: Long): Long
    private external fun getChatTemplate(nativeHandle: Long): String
    private external fun freeGGUFContext(nativeHandle: Long)
}
