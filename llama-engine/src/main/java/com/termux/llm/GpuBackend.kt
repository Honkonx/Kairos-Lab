package com.termux.llm

/**
 * Preferencia de backend de cómputo — CPU puro vs offload a GPU (Vulkan,
 * el único backend GPU compilado hoy, ver CMakeLists.txt/build.gradle de
 * este módulo). No hay "OpenGL" real en ggml para cómputo — las opciones
 * reales de llama.cpp son Vulkan/CUDA/OpenCL/CPU; Kairos compila Vulkan
 * (GGML_VULKAN=ON) + CPU (siempre disponible), ver docs/ia-local/LLAMA_CPP_EMBEBIDO.md.
 *
 * La detección real (¿hay un backend GPU registrado en este dispositivo?)
 * la hace [LlamaEngine.getGpuDeviceInfo] en runtime — esto es solo la
 * preferencia del usuario sobre qué hacer con esa info.
 */
enum class GpuBackend {
    /** CPU únicamente — nGpuLayers = 0, sin intentar offload a GPU. */
    CPU_ONLY,

    /**
     * Vulkan si el dispositivo lo soporta (fallback silencioso a CPU si
     * ggml no registró ningún backend GPU, o si falla la inicialización de
     * Vulkan en este dispositivo específico — no todos los devices con
     * Vulkan_ICD funcionan, ver crashes de Adreno 630 documentados en
     * docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md).
     */
    VULKAN_IF_AVAILABLE;

    companion object {
        /** nGpuLayers a pasar a [LlamaEngine.InferenceParams] según la preferencia y si hay GPU real detectada. */
        fun resolveGpuLayers(preference: GpuBackend, gpuDeviceName: String, allLayers: Int = 99): Int =
            when (preference) {
                CPU_ONLY -> 0
                VULKAN_IF_AVAILABLE -> if (gpuDeviceName.isNotEmpty()) allLayers else 0
            }
    }
}
