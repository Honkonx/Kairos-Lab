# Vulkan, memoria y cierres del Chat IA local (2026-10-09)

> Investigación hecha en un dispositivo real (Xiaomi 2109119DG, Android 15, 7,4 GB de RAM, GPU Adreno 642L).
> Todo lo marcado **[medido]** sale de logs de ese dispositivo; lo marcado **[no verificado]** no se probó.

## Síntomas reportados

1. Al abrir el Chat IA con el motor local (llama.cpp embebido) la app se cerraba, a veces con texto rojo.
2. Con la preferencia en CPU funcionaba.
3. Pasaba también con Ollama oficial → se sospechó del dispositivo/driver de Vulkan.

## Hallazgos (tres problemas distintos)

### 1. Reserva de memoria de 516 MiB por el vocabulario de Gemma — **propio, corregido** [medido]

`llama_context_params.n_outputs_max = 0` (valor por defecto del wrapper JNI) hace que llama.cpp reserve, como peor caso,
tantas salidas como el lote (`n_ubatch = 512`). Con Gemma 3 (vocabulario de 262 144 palabras) eso son
512 × 262 144 × 4 bytes ≈ **516 MiB de memoria anclada** (`Vulkan_Host compute buffer size = 516.50 MiB`) justo al
crear el contexto. En el teléfono de 7,4 GB con otras apps abiertas, Android declaraba `mem-pressure-event` en ese
mismo instante y mataba procesos (incluida la app).

- El `llama-server` del módulo, con el mismo modelo y `-ub 512`, reservaba **12 MiB** porque fija ese límite
  (`tools/server/server-context.cpp`).
- Arreglo: `ctx_params.n_outputs_max = 1` en `llama-engine/src/main/cpp/LLMInference.cpp` (el motor solo lee los
  logits del último token). Resultado [medido]: `Vulkan_Host compute buffer size = 8.52 MiB`, 0 eventos de presión de memoria.
- El campo existe también en la versión anterior (b10194): **volver a la versión vieja no arregla esto**, lo arregla el parámetro.

### 2. Condición de carrera al cambiar de pantalla — **propio, corregido** [medido]

`SIGSEGV` en `ggml_compute_forward_glu` (backend CPU) dentro de `completionLoop`. Causa: `ChatFragment.onDestroyView`
cerraba el motor (`~llama_context`) en el hilo principal mientras el hilo de generación seguía dentro de `llama_decode`
(`Thread.interrupt()` no detiene código nativo). Cualquier cambio de pestaña a mitad de una respuesta mataba la app.

- Arreglo: `LlamaEngine` serializa las llamadas nativas con un candado y `close()` **espera** al paso en curso; los
  callbacks de token van fuera del candado; `onDestroyView` cierra en un hilo aparte.
- Verificado: cambiar de pestaña a los 4 s de una respuesta → mismo PID, sin señal fatal.

### 3. Fallo del driver de Vulkan en la Adreno 642L — **del dispositivo, mitigado** [medido]

Error mostrado en rojo: `vk::Device::createComputePipeline: ErrorUnknown` al generar con las 27 capas en GPU. Es el
driver quien rechaza compilar un shader. Sí se observó que un mensaje corto ("hola") produjo texto con la GPU (la respuesta salió cortada,
"…¿En qué puedo ayudart") y que otro ("di hola en una frase") falló con este error; **[hipótesis no verificada]**: cada forma de prompt puede necesitar una
variante de shader distinta y solo alguna falla en este driver. No se cierra la app; lo que faltaba era manejarlo.

- Mitigación (`ChatFragment` + `GpuBackend.PREF_VULKAN_DRIVER_FAILED`): si el error contiene `vk::`/Vulkan en plena
  inferencia, se guarda que el driver falló en este dispositivo y, si todavía no se mostró texto, **se reintenta solo en CPU**.
  Elegir un backend a mano en IA Local → Parámetros borra esa marca.
- **[no verificado]** si el fallo desaparece con otra versión de llama.cpp o con otros parámetros de Vulkan (ver sección dedicada).

## Dos versiones de llama.cpp

| | Versión | Estado |
|---|---|---|
| Predeterminada | `e1a1abb` (b10194, 2026-07-30) + parche `llama-engine/patches/kairos-tools-cmake-b10194.patch` | En el APK |
| Nueva | `6184e92` (upstream 2026-10-09) | Etiqueta `kairos-new-6184e92` en el clon vendor + `llama-engine/patches/jni-wrapper-api-6184e92.patch` (2 firmas del wrapper) |

Distancia medida: **1 338 commits y 300 archivos** entre ambas en ~10 semanas (≈19 commits/día); upstream cambió el
esquema de versiones (ya no `bNNNN`, ahora `0.6.0-dev`). La nueva compila con Vulkan y carga Gemma en la Adreno 642L, pero **no** se
probó que arregle el error de pipeline: se reportó el mismo fallo de Vulkan con ella. Entre sus cambios hay
arreglos de Vulkan para Adreno (`argsort`, matrices cooperativas KHR) y para GPUs Samsung/Intel/AMD.
Poder elegir entre ambas dentro de la app exige empaquetar dos juegos de librerías con nombres distintos
(las dos usarían `libllama.so`/`libggml*.so`); queda para la sección dedicada.

Contexto del motor: [`llama-cpp-local-engine.md`](llama-cpp-local-engine.md).

## Sección dedicada "Compatibilidad Vulkan" (pendiente)

Ideas, todas **sin implementar**: elegir versión de llama.cpp (anterior/nueva); lista de dispositivos/GPU con su
configuración conocida (capas en GPU, `n_ubatch`, flash-attention, matrices cooperativas); prueba de humo de Vulkan
al primer uso que decida CPU/GPU por dispositivo; recopilar `vk::` errores con modelo, prompt y driver para parchear.

## Cómo se diagnosticó (para repetirlo)

`adb logcat -b all` filtrando `[ggml]`: líneas `sched_reserve: Vulkan_Host compute buffer size`, `offloaded N/M layers`,
`ActivityManager: ... mem-pressure-event`; `adb logcat -b crash` para el SIGSEGV con la traza nativa; comparar con
`llama-server -lv 4` lanzado a mano desde `$PREFIX/lib` (si no, "no backends are loaded").
