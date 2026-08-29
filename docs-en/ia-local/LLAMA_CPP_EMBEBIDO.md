# Embedded local inference engine (llama.cpp)

## Summary

Kairos embeds [llama.cpp](https://github.com/ggml-org/llama.cpp) directly into the APK, compiled
as native code (NDK/CMake) rather than relying on a Termux package. There are two distinct
pieces, sharing the same Gradle module, `llama-engine/`:

| Piece | What it is | Notes |
|---|---|---|
| `kairos_llm` (JNI wrapper) | In-process inference engine used by the Chat screen — no network port | Stable, no architectural changes since its first version |
| `llama-server` (the real llama.cpp binary) | An OpenAI-API-compatible HTTP server, so other Kairos modules (separate Termux processes: terminal-based AI agents, etc.) can use it as a backend | Confirmed working end to end on a real device, serving on `127.0.0.1:8085` |

## Why llama.cpp directly, and why embedded in the APK

Several alternatives were evaluated (including packaging a full build of Ollama's server,
compiled with Go+CGO) — llama.cpp directly was chosen as the option with the most mature codebase
and the best Android GPU acceleration support among the evaluated projects.

It was chosen to be compiled as a `.so` embedded in the APK, rather than distributed as a Termux
package, due to an explicit design requirement: being able to choose at runtime between using
Vulkan acceleration or forcing CPU-only mode — something a Termux package installed with a fixed
GPU variant doesn't allow, since that choice gets frozen at install time.

The implementation was based on an external reference project (Apache-2.0 licensed) that already
had Vulkan genuinely compiling on Android — not just declared in the build script, but actually
linking. The real key to achieving Vulkan on Android turned out to be less about the NDK version,
and more about explicitly adding the Khronos headers (`Vulkan-Headers`, `SPIRV-Headers`) that the
NDK doesn't ship completely, plus a host toolchain for the shader generator
(`vulkan-shaders-gen`, which must compile and run on the build machine, not on the Android
device). That's why the `llama-engine` module pins its own NDK version (27.2.12479018), different
from what the rest of the project uses for other native components.

## Module structure

| File | Role |
|---|---|
| `llama-engine/build.gradle` | CMake/NDK/Vulkan configuration |
| `llama-engine/src/main/cpp/CMakeLists.txt` | Defines the native targets, clones llama.cpp as a subdirectory |
| `llama-engine/src/main/cpp/LLMInference.h/.cpp` | C++ inference engine (sampling, chat template, incremental KV cache) |
| `llama-engine/src/main/cpp/kairos_llm_jni.cpp` | JNI bridge |
| `llama-engine/src/main/cpp/GGUFReader.cpp` | Reads `.gguf` metadata (context size, chat template) without loading the full model |
| `llama-engine/src/main/java/com/termux/llm/LlamaEngine.kt` | Kotlin wrapper (blocking calls + callbacks, no coroutines) |
| `llama-engine/src/main/java/com/termux/llm/GpuBackend.kt` | Backend selection: forced CPU, or Vulkan if available |

The JNI wrapper exposes functions to initialize backends, detect the GPU device name, load a
model, manage a chat turn, measure generation speed and context usage, and start/stop a streaming
generation.

## Build: cloning llama.cpp's source

The module clones the real llama.cpp repository (`ggml-org/llama.cpp`) at build time, pinned to a
specific tag (an upstream CI build — llama.cpp doesn't publish traditional "stable" releases).
After cloning, an idempotent text patch is applied to llama.cpp's tools `CMakeLists.txt`,
commenting out the build of about a dozen benchmarking/debug utilities Kairos doesn't need —
necessary to avoid the build issues described below.

`arm64-v8a` is the only target architecture, consistent with the rest of the project (Android
Bionic ARM64). To compensate for the lack of multi-architecture builds, the options that compile
several ARM kernel variants (dotprod/fp16/i8mm/SVE) as runtime-selected plugins based on the
device's real CPU are enabled.

## The HTTP server (`llama-server`)

Compiling the real `llama-server` binary (not just the JNI wrapper) requires enabling llama.cpp's
"tools" subtree (`LLAMA_BUILD_TOOLS`), which in turn pulls in building llama.cpp's embedded web UI
(explicitly disabled, `LLAMA_BUILD_UI=OFF` — this doesn't fail the build, it just generates empty
code) and the `mtmd` multimodal subsystem (a hard dependency of the server, unavoidable without
patching llama.cpp itself).

### Real build lessons, with confirmed root causes

Getting `llama-server` from "compiles" to "runs on the device" took several iterations, each with
a distinct root cause confirmed by reading the actual CMake/llama.cpp source — worth documenting
because these are generic CMake + Android Gradle Plugin (AGP) integration issues, not specific to
this project:

1. **AGP filters CMake `UTILITY` targets, regardless of the `ALL` property.** A utility target
   defined with `add_custom_target(... ALL DEPENDS ...)` (used to copy the compiled binary into
   the assets folder) never ran, despite being marked `ALL` — AGP doesn't invoke `ninja` with its
   default target; instead it explicitly enumerates the project's targets via CMake's File API and
   only passes to `ninja` the ones it identifies as packageable libraries or executables, ignoring
   `UTILITY` targets. **Real fix**: declare the copy target as an explicit dependency
   (`add_dependencies`) of a target AGP always does build (the main JNI library) — that way it
   rides along as a prerequisite of a build that already happens, instead of relying on AGP
   discovering it on its own.

2. **`EXCLUDE_FROM_ALL` isn't enough to stop AGP from trying to build an unwanted target.** When
   trying to exclude unnecessary debug tools from the build, marking the whole subdirectory with
   `EXCLUDE_FROM_ALL` was tried — with no real effect, because AGP enumerates targets directly via
   CMake's File API without consulting that property. The real fix that worked was a text patch
   on llama.cpp's `CMakeLists.txt` that directly comments out the `add_subdirectory()` lines for
   the unwanted tools — if CMake never defines the target, there's no way for AGP to try to build
   it.

3. **`PUBLIC` symbol visibility accidentally propagated between libraries.** Kairos applies
   hidden-visibility flags (`-fvisibility=hidden`) to llama.cpp's main library, to minimize the
   exported surface of the `.so`. Declaring them as `PUBLIC` in CMake propagated that setting to
   any library linking `PUBLIC` against it — accidentally hiding internal symbols of an auxiliary
   library that the server component actually needed at link time. **Fix**: change those flags to
   `PRIVATE`, so they still apply to the library's own compilation without propagating to whatever
   links against it. General lesson: in CMake, a compiler option declared `PUBLIC` is transitively
   inherited by any target that links `PUBLIC` against the one declaring it — you have to be
   explicit about what actually needs to propagate.

4. **Copying only the executable isn't enough when building with shared libraries.** With
   `BUILD_SHARED_LIBS=ON` (required for llama.cpp's runtime-selectable backend plugin system:
   CPU/Vulkan chosen at runtime), each llama.cpp component compiles as its own dynamic library —
   the `llama-server` executable depends on several auxiliary `.so` files at runtime. The first
   packaging attempt only copied the executable binary, and the device failed with a dynamic
   linker error for a missing library. **Fix**: the same copy step also copies the shared
   libraries generated alongside the executable.

5. **Shell commands embedded directly in a CMake `COMMAND` line get corrupted when passing
   through two layers of re-serialization** (CMake generates the command for ninja's build file,
   and ninja re-interprets it via its own shell) — embedded quotes and redirections don't survive
   both layers of escaping. **Fix**: move all the shell logic into a standalone `.sh` script,
   invoked with simple arguments, with no quotes or redirections in the CMake command line.

6. **Google Play's 16KB memory page size requirement.** The corresponding linker flag
   (`max-page-size=16384`) was added to the module's native targets as a preventive measure
   aligned with Google Play's current requirement for devices with a 16KB memory page size — the
   module pins its own NDK, separate from the rest of the project, so it wasn't safe to assume the
   flag already applied by default.

### External dependency on Vulkan headers

Linking the Vulkan backend depends on two external checkouts, cloned as sibling directories of
the project by the CI pipeline before invoking Gradle (Khronos's `Vulkan-Headers`,
`SPIRV-Headers`) — if they're not present, the Vulkan build fails at the configure stage with only
a warning (not a hard error), which can silently let the build fall back to compiling without
those includes. This is the most fragile point of the pipeline: a future change that reorders or
removes those CI steps would silently break Vulkan.

## Full chain: from the build to the Termux module

1. CMake compiles `llama-server` and its dependent libraries → they're copied into the APK's
   asset folder.
2. Kairos's bootstrap installer extracts those files into the embedded Termux environment's
   script directory on first launch, marking them executable.
3. A dedicated module (`llamaserver`) copies the binary to Termux's standard binary path,
   generates start/stop scripts (using `tmux`, port 8085) and a user config file (chosen model,
   port) — the same pattern used by other Termux-process-based Kairos modules.
4. The module reuses the `.gguf` models already managed by the AI Chat screen (same app storage
   directory, accessible from Termux thanks to the shared `sharedUserId`) — it doesn't download
   models on its own.
5. The module integrates into Kairos's standard module system (install/start/stop/reinstall/
   uninstall, catalog, toggle) with no special-case code.
6. Other Kairos modules (terminal-based AI agents) can be configured to use this server as a
   backend, pointing to the same OpenAI-API-compatible base URL already used to point to Ollama —
   confirming those modules were never specifically tied to Ollama.

## User interface

The "Local AI" screen in the app menu lets the user pick the preferred GPU backend (forced CPU or
Vulkan if available), adjust temperature and context size with a dynamic maximum computed from
the device's real RAM, and manage downloaded `.gguf` models. A curated model catalog (with sizes
verified against each model's real Hugging Face repository) allows direct downloads, in addition
to pasting a manual URL.

Model downloads include multi-layer validation: checking the GGUF format's magic bytes before
accepting the file, comparing the downloaded size against the server-declared `Content-Length`
(with a tolerance threshold), automatic cleanup of orphaned partial downloads, and a free disk
space check before starting a download (with a 15% safety margin over the estimated remaining
bytes).

On the Chat screen, the model selector includes both local `.gguf` models and Ollama models in a
single list — which engine responds is decided by which model is selected, never by a separate
toggle. The chat also resends recent conversation history with every message (a real prior
limitation that was fixed), visually separates the reasoning trace emitted by some models
(`<think>...</think>` format, common in DeepSeek-R1/QwQ-style models) from the rest of the
response, and translates low-level native errors into human-readable messages, while keeping the
technical detail available behind a "View details" button.

## Known, explicitly documented limitations

- No internet search or tool-calling from the local engine.
- No conversation history persistence across app sessions (only within an active session).
- No image/vision support in chat (would require an additional vision projector, not handled
  today by the model download flow).
- Only one GPU compute backend besides CPU (Vulkan) — OpenCL isn't compiled, though llama.cpp's
  dynamic backend mechanism would allow adding it later without architectural changes.
- The HTTP server only serves one model per process — switching models requires restarting the
  service, with no hot-swap mechanism.
- The server port only listens on loopback (`127.0.0.1`) by default, with no LAN exposure.
