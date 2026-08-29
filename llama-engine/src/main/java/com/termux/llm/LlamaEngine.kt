package com.termux.llm

/**
 * Wrapper Kotlin del motor de inferencia llama.cpp — adaptado de SmolLM.kt
 * de OfflineLLM (jegly/OfflineLLM, Apache-2.0), ver docs/referencias/REFERENCIA_OFFLINELLM.md
 * y docs/ia-local/LLAMA_CPP_EMBEBIDO.md.
 *
 * A diferencia del original (que usa kotlinx.coroutines Flow), esta versión
 * usa llamadas bloqueantes simples + callbacks — mismo patrón que el resto
 * de Kairos (ver RootfsInstaller.kt, WizardInstallFragment.kt): el caller
 * es responsable de correr esto en su propio background Thread, no hay
 * dependencia nueva de coroutines en el proyecto.
 *
 * Todos los métodos públicos que tocan el motor nativo son bloqueantes —
 * NUNCA llamar desde el hilo principal.
 */
class LlamaEngine {
    companion object {
        private const val TAG = "LlamaEngine"

        init {
            // La optimización específica de CPU vive en las variantes del
            // plugin ggml-cpu (libggml-cpu-android_*.so), seleccionadas en
            // runtime por initBackends() — el wrapper JNI en sí tiene un
            // único build.
            System.loadLibrary("kairos_llm")
        }
    }

    @Volatile private var nativePtr = 0L

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        // 0 = CPU puro. > 0 = intenta offload de N capas al backend GPU
        // activo (Vulkan) si está disponible — ver GpuBackend.kt.
        val nGpuLayers: Int = 0,
        // Threads para la fase de procesamiento de prompt (compute-bound);
        // <= 0 significa "igual a numThreads". Normalmente todos los cores,
        // incluidos los de eficiencia.
        val numThreadsBatch: Int = -1,
        // KV-cache cuantizada a Q8_0 — reduce a la mitad la memoria de KV en
        // contextos largos.
        val kvCacheQ8: Boolean = false,
    )

    object DefaultParams {
        const val CONTEXT_SIZE: Long = 2048L
        const val CHAT_TEMPLATE: String =
            "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system You are a helpful AI assistant.<|im_end|> ' }}{% endif %}{{'<|im_start|>' + message['role'] + ' ' + message['content'] + '<|im_end|>' + ' '}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant ' }}{% endif %}"
    }

    /** Bloqueante — correr en un background Thread. */
    fun load(modelPath: String, params: InferenceParams = InferenceParams()) {
        val ggufReader = GGUFReader()
        ggufReader.load(modelPath)
        val modelContextSize = ggufReader.getContextSize() ?: DefaultParams.CONTEXT_SIZE
        val modelChatTemplate = ggufReader.getChatTemplate() ?: DefaultParams.CHAT_TEMPLATE
        ggufReader.close()
        nativePtr = loadModel(
            modelPath,
            params.minP,
            params.temperature,
            params.topP,
            params.topK,
            params.repeatPenalty,
            params.storeChats,
            params.contextSize ?: modelContextSize,
            params.chatTemplate ?: modelChatTemplate,
            params.numThreads,
            params.useMmap,
            params.useMlock,
            params.nGpuLayers,
            params.numThreadsBatch,
            params.kvCacheQ8,
        )
    }

    /**
     * Carga los plugins de backend de ggml desde el nativeLibraryDir de la
     * app, eligiendo los mejores kernels de CPU para este dispositivo. Debe
     * correr una vez antes del primer [load] o [getGpuDeviceInfo]; llamadas
     * siguientes son no-op.
     */
    fun loadBackends(nativeLibDir: String) = initBackends(nativeLibDir)

    /**
     * Descripción del primer backend de tipo GPU registrado por ggml (ej.
     * "Adreno (TM) 640"), o "" si no hay ninguno usable en este dispositivo.
     * No requiere un modelo cargado.
     */
    fun getGpuDeviceInfo(): String = try {
        getGpuDeviceName()
    } catch (_: Throwable) {
        ""
    }

    /** Agrega un mensaje con un rol específico — necesario para modelos como Gemma que usan "model" en vez de "assistant". */
    fun addChatMessage(role: String, message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, role)
    }

    fun addUserMessage(message: String) = addChatMessage("user", message)
    fun addSystemPrompt(prompt: String) = addChatMessage("system", prompt)
    fun addAssistantMessage(message: String) = addChatMessage("assistant", message)

    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }

    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }

    fun stop() {
        val ptr = nativePtr
        if (ptr != 0L) stopCompletion(ptr)
    }

    /**
     * Streaming de la respuesta — bloqueante, correr en background Thread.
     * [onToken] se llama en el mismo hilo por cada fragmento de texto nuevo;
     * el caller decide cómo postear a UI (mismo patrón que
     * RootfsInstaller.install()'s onProgress).
     */
    fun streamResponse(query: String, onToken: (String) -> Unit) {
        verifyHandle()
        val ptr = nativePtr
        startCompletion(ptr, query)
        try {
            while (nativePtr != 0L) {
                val piece = completionLoop(nativePtr)
                if (piece == "[EOG]") break
                if (piece.isNotEmpty()) onToken(piece)
            }
        } finally {
            if (nativePtr != 0L) stopCompletion(nativePtr)
        }
    }

    /** Bloqueante, sin streaming — junta la respuesta completa antes de devolver. */
    fun getResponse(query: String): String {
        val sb = StringBuilder()
        streamResponse(query) { sb.append(it) }
        return sb.toString()
    }

    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }

    fun close() {
        if (nativePtr != 0L) {
            val ptr = nativePtr
            nativePtr = 0L  // en cero antes del free nativo para que streamResponse() lo vea de inmediato
            close(ptr)
        }
    }

    fun isLoaded(): Boolean = nativePtr != 0L

    private fun verifyHandle() {
        check(nativePtr != 0L) { "Modelo no cargado — llamar LlamaEngine.load() primero." }
    }

    private external fun loadModel(
        modelPath: String, minP: Float, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, storeChats: Boolean, contextSize: Long, chatTemplate: String,
        nThreads: Int, useMmap: Boolean, useMlock: Boolean, nGpuLayers: Int,
        nThreadsBatch: Int, kvCacheQ8: Boolean
    ): Long
    private external fun initBackends(nativeLibDir: String)

    private external fun getGpuDeviceName(): String
    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
