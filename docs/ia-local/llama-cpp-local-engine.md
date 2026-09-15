# Motor local llama.cpp

Kairos embebe [llama.cpp](https://github.com/ggml-org/llama.cpp) directo en el APK, compilado
nativo vía NDK, para correr modelos de lenguaje localmente sin depender de una nube ni de un
proceso externo instalado aparte.

## Dos formas de usar el motor

El módulo `llama-engine/` expone llama.cpp de dos maneras distintas, según qué necesite el
consumidor:

1. **Wrapper JNI en proceso** (`LlamaEngine.kt` + `kairos_llm_jni.cpp`) — usado por el tab de
   chat de IA integrado en la app. Corre dentro del mismo proceso de Kairos, sin puerto de red,
   con llamadas Kotlin bloqueantes directas al motor nativo.
2. **`llama-server` como binario HTTP** — el servidor real de llama.cpp, compatible con la API
   de OpenAI/llama.cpp, para que procesos externos de Termux (agentes de IA como OpenCode,
   Hermes, OpenClaw, que corren como procesos separados y necesitan un endpoint de red) puedan
   usar el mismo motor igual que ya usan Ollama.

## Wrapper JNI (`LlamaEngine.kt`)

`llama-engine/src/main/java/com/termux/llm/LlamaEngine.kt` es un wrapper Kotlin delgado sobre
el motor nativo — todos sus métodos públicos que tocan el motor son **bloqueantes** y deben
correr en un background `Thread` propio del caller (mismo patrón usado en el resto de Kairos
para operaciones largas, sin depender de coroutines).

Flujo típico de uso:

```kotlin
val engine = LlamaEngine()
engine.loadBackends(context.applicationInfo.nativeLibraryDir)   // una sola vez
engine.load(modelPath, LlamaEngine.InferenceParams(nGpuLayers = 20))
engine.addSystemPrompt("...")
engine.addUserMessage("...")
engine.streamResponse(query) { token -> /* actualizar UI */ }
engine.close()
```

`InferenceParams` controla los parámetros reales de inferencia: `temperature`, `topP`, `topK`,
`repeatPenalty`, `contextSize` (si no se especifica, se lee del propio GGUF vía `GGUFReader`),
`numThreads`/`numThreadsBatch`, `useMmap`/`useMlock`, `kvCacheQ8` (KV-cache cuantizada a Q8_0,
reduce a la mitad la memoria en contextos largos) y `nGpuLayers` (capas a offload al backend
GPU activo, 0 = CPU puro).

`loadBackends(nativeLibDir)` carga los plugins de backend de `ggml` desde el
`nativeLibraryDir` real de la instalación (nunca hardcodeado — Android randomiza ese path por
instalación), eligiendo en runtime los mejores kernels de CPU para el dispositivo.
`getGpuDeviceInfo()` devuelve el nombre del primer backend GPU detectado (ej. "Adreno (TM)
640"), o cadena vacía si no hay ninguno usable.

## Backend GPU (Vulkan)

El build de llama.cpp incluido soporta aceleración por GPU vía **Vulkan** (no CUDA/Metal — esos
no aplican en Android). La detección y selección del backend GPU disponible en el dispositivo
vive en `GpuBackend.kt`; si no hay un backend GPU utilizable, el motor cae automáticamente a
CPU pura.

## `llama-server`: motor como servicio HTTP

Además del wrapper en proceso, el build de `llama-engine` compila el binario real
`llama-server` de llama.cpp (vía CMake, target `llama-server` en
`llama-engine/src/main/cpp/CMakeLists.txt`) y lo empaqueta como asset de la APK. Expuesto como
servidor HTTP en el puerto **8085**, compatible con la API de OpenAI/llama.cpp — cualquier
cliente que hable ese protocolo (incluyendo los agentes de IA de línea de comandos que corren
como procesos Termux separados) puede apuntar ahí igual que apuntaría a Ollama (puerto 11434).

## GGUF

Los modelos se distribuyen en formato [GGUF](https://github.com/ggml-org/ggml/blob/master/docs/gguf.md).
`GGUFReader` (Kotlin, con contraparte C++ `GGUFReader.cpp`) lee metadata del archivo sin cargar
el modelo completo — tamaño de contexto nativo y plantilla de chat embebida, usados como
default cuando `InferenceParams` no los especifica explícitamente.

La pantalla "IA Local" ofrece un catálogo curado de modelos GGUF listos para descargar (de ~500MB
a ~9GB) además de la opción de pegar una URL propia. La descarga se valida en varias capas antes
de aceptar el archivo: magic bytes GGUF reales, tamaño descargado contra el `Content-Length`
declarado por el servidor, y un chequeo de espacio libre real en disco (con margen de seguridad)
antes de escribir el primer byte — si no alcanza el espacio, la descarga ni arranca.

## Parámetros configurables de `llama-server`

La configuración de `llama-server` (tamaño de contexto, hilos, capas offload a GPU, clave de API
opcional, número de requests en paralelo, habilitar el endpoint de embeddings, y exponerlo en la
red local en vez de solo `127.0.0.1`) se ajusta desde la propia UI y se persiste en un archivo de
configuración que el script de arranque del módulo lee para construir los flags reales del
binario — no son controles decorativos.

## Build nativo

- El build de `llama-engine` usa una versión de NDK pineada de forma independiente al resto del
  proyecto (distinta del NDK usado por `terminal-emulator/`), elegida específicamente porque es
  la versión con la que la configuración de CMake/Vulkan del proyecto compila y corre de forma
  confiable.
- La configuración de CMake/Vulkan está adaptada del proyecto de referencia
  [jegly/OfflineLLM](https://github.com/jegly/OfflineLLM) (Apache-2.0).
- `llama-engine/llama.cpp/` (el árbol fuente de llama.cpp en sí) se descarga en tiempo de build
  vía una tarea de Gradle dedicada — no se versiona en el repositorio.

## Comparación con Ollama

Kairos ofrece ambos motores de IA local en paralelo, no uno en reemplazo del otro:

| | llama.cpp (este motor) | Ollama |
|---|---|---|
| Empaquetado | Compilado nativo dentro del propio APK | Binario instalado como módulo aparte |
| Puerto HTTP | 8085 | 11434 |
| Gestión de modelos | Descarga directa de GGUF desde la app | Catálogo propio de Ollama |
| Uso en proceso | Sí (wrapper JNI, tab de chat) | No — siempre vía HTTP |
| Aceleración GPU | Vulkan | Según build de Ollama disponible |
</content>
