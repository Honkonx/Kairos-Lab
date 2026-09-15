# Local llama.cpp engine

Kairos embeds [llama.cpp](https://github.com/ggml-org/llama.cpp) directly in the APK, compiled
natively via the NDK, to run language models locally without depending on the cloud or a
separately installed external process.

## Two ways to use the engine

The `llama-engine/` module exposes llama.cpp in two different ways, depending on what the
consumer needs:

1. **In-process JNI wrapper** (`LlamaEngine.kt` + `kairos_llm_jni.cpp`) — used by the AI chat
   tab built into the app. Runs inside Kairos's own process, with no network port, using direct
   blocking Kotlin calls into the native engine.
2. **`llama-server` as an HTTP binary** — llama.cpp's real server, compatible with the
   OpenAI/llama.cpp API, so that external Termux processes (AI agents such as OpenCode, Hermes,
   or OpenClaw, which run as separate processes and need a network endpoint) can use the same
   engine the same way they already use Ollama.

## JNI wrapper (`LlamaEngine.kt`)

`llama-engine/src/main/java/com/termux/llm/LlamaEngine.kt` is a thin Kotlin wrapper around the
native engine — every public method that touches the engine is **blocking** and must run on a
caller-owned background `Thread` (the same pattern used throughout the rest of Kairos for
long-running operations, without relying on coroutines).

Typical usage flow:

```kotlin
val engine = LlamaEngine()
engine.loadBackends(context.applicationInfo.nativeLibraryDir)   // once
engine.load(modelPath, LlamaEngine.InferenceParams(nGpuLayers = 20))
engine.addSystemPrompt("...")
engine.addUserMessage("...")
engine.streamResponse(query) { token -> /* update the UI */ }
engine.close()
```

`InferenceParams` controls the actual inference parameters: `temperature`, `topP`, `topK`,
`repeatPenalty`, `contextSize` (if not specified, it's read from the GGUF file itself via
`GGUFReader`), `numThreads`/`numThreadsBatch`, `useMmap`/`useMlock`, `kvCacheQ8` (Q8_0-quantized
KV cache, cuts memory in half on long contexts), and `nGpuLayers` (layers to offload to the
active GPU backend, 0 = CPU-only).

`loadBackends(nativeLibDir)` loads `ggml`'s backend plugins from the actual `nativeLibraryDir`
of the installation (never hardcoded — Android randomizes that path per install), picking the
best CPU kernels for the device at runtime. `getGpuDeviceInfo()` returns the name of the first
detected GPU backend (e.g. "Adreno (TM) 640"), or an empty string if none is usable.

## GPU backend (Vulkan)

The included llama.cpp build supports GPU acceleration via **Vulkan** (not CUDA/Metal — those
don't apply on Android). Detection and selection of the GPU backend available on the device
lives in `GpuBackend.kt`; if no usable GPU backend is found, the engine automatically falls back
to pure CPU.

## `llama-server`: the engine as an HTTP service

Besides the in-process wrapper, the `llama-engine` build compiles llama.cpp's real
`llama-server` binary (via CMake, the `llama-server` target in
`llama-engine/src/main/cpp/CMakeLists.txt`) and bundles it as an APK asset. It's exposed as an
HTTP server on port **8085**, compatible with the OpenAI/llama.cpp API — any client that speaks
that protocol (including command-line AI agents running as separate Termux processes) can point
at it just like it would point at Ollama (port 11434).

## GGUF

Models are distributed in [GGUF](https://github.com/ggml-org/ggml/blob/master/docs/gguf.md)
format. `GGUFReader` (Kotlin, with a C++ counterpart, `GGUFReader.cpp`) reads a file's metadata
without loading the full model — native context size and embedded chat template, used as
defaults when `InferenceParams` doesn't specify them explicitly.

The "Local AI" screen offers a curated catalog of ready-to-download GGUF models (from ~500MB to
~9GB) besides the option to paste a custom URL. The download is validated in several layers
before accepting the file: real GGUF magic bytes, downloaded size against the server-declared
`Content-Length`, and a real free-disk-space check (with a safety margin) before writing the
first byte — if there isn't enough space, the download doesn't even start.

## Multimodal support (image + text)

The in-process engine (`kairos_llm`, the one used by the chat tab) supports real multimodal
GGUF models — image + text, via llama.cpp's `libmtmd` library. It isn't an experimental fork:
`libmtmd` was already being compiled as part of Kairos's build (it's a dependency of the
`llama-server` binary), so adding support to the in-process engine meant linking a component
that already existed in the project, without changing the vendored llama.cpp version or the
Vulkan/NDK build pipeline.

How an image turn works:

- The text model is loaded first, and on top of it, the multimodal projector (`mmproj`) — a
  second `.gguf` file that ships alongside certain vision models (following the
  `mmproj-*.gguf` naming convention).
- Text and image are tokenized together and processed in a single pass.
- **Deliberate limitation**: a turn with an image always reprocesses the whole conversation from
  scratch (it doesn't reuse the incremental cache that text-only turns use) — mixing both
  schemes would have been a real source of synchronization bugs for a first pass. Text turns
  that come AFTER one with an image go back to being incremental as normal.
- On the "Local AI" screen, `.gguf` files that Kairos detects as a multimodal projector (by
  name) get their own icon/label under "Downloaded models".
- In the chat, the image-attach button (which already existed for Ollama) is also enabled for
  local models as soon as at least one multimodal projector has been imported — it reuses the
  same picker and image compression already used for Ollama, no new picker involved.

**Still missing**: an explicit way to manually pair a model with its projector when several are
imported (today it heuristically uses the most recently imported one), multimodal support in
`llama-server` (the HTTP binary, for external consumers), and audio/video support.

## Configurable `llama-server` parameters

`llama-server`'s configuration (context size, threads, GPU offload layers, optional API key,
number of parallel requests, enabling the embeddings endpoint, and exposing it on the local
network instead of just `127.0.0.1`) is adjusted from the UI itself and persisted to a config
file that the module's startup script reads to build the binary's real flags — these aren't
decorative controls.

## Native build

- The `llama-engine` build uses an NDK version pinned independently from the rest of the
  project (different from the NDK used by `terminal-emulator/`), chosen specifically because it
  is the version the project's CMake/Vulkan configuration compiles and runs reliably with.
- The CMake/Vulkan configuration is adapted from the reference project
  [jegly/OfflineLLM](https://github.com/jegly/OfflineLLM) (Apache-2.0).
- `llama-engine/llama.cpp/` (the llama.cpp source tree itself) is downloaded at build time via a
  dedicated Gradle task — it isn't versioned in the repository.

## Comparison with Ollama

Kairos offers both local AI engines in parallel, not one replacing the other:

| | llama.cpp (this engine) | Ollama |
|---|---|---|
| Packaging | Compiled natively into the APK itself | Installed as a separate module binary |
| HTTP port | 8085 | 11434 |
| Model management | Direct GGUF download from the app | Ollama's own catalog |
| In-process use | Yes (JNI wrapper, chat tab) | No — always over HTTP |
| GPU acceleration | Vulkan | Depends on the available Ollama build |
