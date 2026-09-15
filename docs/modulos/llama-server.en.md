# llama-server / Local AI (llama.cpp)

**Kairos module** — managed via the Kairos UI (Modules tab, id `llamaserver`, name
"Local AI (llama.cpp)"). Installation, start/stop and status are handled by the app via
`ProcessBuilder` → `modulos/llamaserver.sh`.

llama.cpp has **two pieces** that coexist in the `llama-engine/` module: the embedded
JNI engine (in-process, no port) and the `llama-server` server (HTTP `:8085`) that
**any module/CLI can use as an OpenAI-compatible backend**.

---

**Script:** `modulos/llamaserver.sh` (copy synced to
`app/src/main/assets/scripts/llamaserver.sh`).
**Port:** `:8085` (loopback `127.0.0.1` only — a deliberate decision, no LAN toggle by
default).
**Registry:** prefix `llamaserver.*`
**Gradle module:** `llama-engine/` (NDK/CMake/JNI).
**Technical docs:** `docs/ia-local/llama-cpp-local-engine.md` (build log).

---

## 1. Overview

llama.cpp is the local LLM inference engine that Kairos ships **compiled inside the
APK** (`llama-engine/` module, NDK, `arm64-v8a` ABI). It's not a Termux package: it's
compiled in CI/GitHub Actions and the binary travels as an APK asset. It has **two
pieces** that share the same module and the same models directory:

| Piece | What it is | Port | Use |
|---|---|---|---|
| `kairos_llm` (`LlamaEngine` JNI wrapper) | **In-process** engine for the native "Local AI"/Chat tab | no port | Inference inside the app's own process (no server in between). Incremental history with KV-cache |
| `llama-server` (llama.cpp's real binary) | **OpenAI-compatible** HTTP server | `127.0.0.1:8085` | Serves separate CLIs/agents/Termux processes (OpenCode, Hermes, OpenClaw, cactus) and acts as a backend for any module |

**Why both exist:** Termux CLIs (OpenCode/Hermes/OpenClaw) are **separate processes**
and need a network endpoint to use the engine — a purely in-process approach (".so + raw
JNI") couldn't offer that. The HTTP server solves it: same `.gguf`, same engine, but
reachable over a socket.

**Reusable backend (not just for Chat):**
`POST http://127.0.0.1:8085/v1/chat/completions` is OpenAI-compatible. Any module, CLI,
agent or script that accepts an `openai-compatible` provider can point to it.

## 2. Permissions

Requires no special Android permissions. The binary and its `.so` files travel as APK
assets (extracted by Kairos's bootstrap to `~/scripts/install/`), and the embedded
engine uses JNI within the process. Access to `.gguf` models (private storage
`/data/data/com.termux/files/models`) works from Termux scripts because the app and
Termux share `sharedUserId` (`com.termux`).

## 3. Installation — `modulos/llamaserver.sh`

Accepts `--silent [--force]`. With `--describe` it returns the declarative JSON
manifest that `ModuleController.kt` uses. It has no variants (a single install path).

### Steps (3 total, with checkpoint at `~/.install_llamaserver_checkpoint`)

```
STEP 1/3  Copy the compiled binary:
            $HOME/scripts/install/llama-server → $PREFIX/bin/llama-server  (chmod 755)
            Copy dependent .so files to $PREFIX/lib/:
              libggml-base.so, libllama.so, libllama-common.so, libmtmd.so, libllama-server-impl.so
            → avoids "CANNOT LINK EXECUTABLE: library libllama-server-impl.so not found"
            → defensive verification: timeout 5 llama-server --version
STEP 2/3  Control scripts: ~/scripts/llamaserver/start.sh and stop.sh
STEP 3/3  ~/.llamaserver_user_config (LLAMA_SERVER_MODEL= + LLAMA_SERVER_PORT=8085)
          + registry_install llamaserver "<version>" "port=8085"
```

**How the binary gets into the APK:** the `downloadLlamaCpp` Gradle task in the
`llama-engine` module clones llama.cpp at build time, compiles `llama-server` with
CMake (flags: `LLAMA_BUILD_SERVER=ON`, `GGML_VULKAN=ON`, `GGML_BACKEND_DL=ON`,
`GGML_CPU_ALL_VARIANTS=ON`, `BUILD_SHARED_LIBS=ON`, OpenMP/LLAMAFILE OFF) and copies it
to `app/src/main/assets/scripts/llama-server`. Kairos's bootstrap extracts that asset to
`~/scripts/install/llama-server` at runtime. A text patch over llama.cpp's
`tools/CMakeLists.txt` disables the unneeded debug/bench tools that broke the link.

### Technical notes

- **Server `.so` dependencies** — `llama-server` needs its dependent `.so` files next
  to it or on the load path. They're copied explicitly to `$PREFIX/lib/` in STEP 1.
- **Backend resolution** — `GGML_BACKEND_DIR` is a **compile-time macro**, never a
  runtime environment variable. llama.cpp looks for backend `.so` files in
  `get_executable_path()` (the binary's directory) and `fs::current_path()` — that's why
  `start.sh` does `cd '$PREFIX/lib'` before launching the binary. Copying the `.so`
  files to `$PREFIX/bin` is noted as a more robust alternative for the future.
- The full command goes directly to `tmux new-session` — no `send-keys` or intermediate
  interactive shell.

## 4. Startup — generated `start.sh`

- tmux session **`llamaserver`**; idempotent (exits 0 if already running).
- Reads `LLAMA_SERVER_MODEL`, `LLAMA_SERVER_PORT`, `LLAMA_SERVER_CTX_SIZE` and
  `LLAMA_SERVER_THREADS` from the `~/.llamaserver_user_config` config **at runtime**
  with `grep+cut` (no `eval` — a security decision).
- **Model required**: if the `.gguf` doesn't exist in `MODELS_DIR` it fails with a clear
  message.
- **Configurable context (tokens) and CPU threads** — `LLAMA_SERVER_CTX_SIZE`/
  `LLAMA_SERVER_THREADS` (default `0` = use the binary's own default for both) are
  added as `-c $CTX_SIZE`/`-t $THREADS` to `EXTRA_ARGS` only if the stored value isn't
  `0`.
- **Real command**:
  ```sh
  cd '$TERMUX_PREFIX/lib' && '$BIN' -m '$MODELS_DIR/$MODEL_FILE' --host 127.0.0.1 --port $PORT \
    $EXTRA_ARGS > '~/kairos_logs/llamaserver_serve.log' 2>&1
  ```
- **MODELS_DIR** = `/data/data/com.termux/files/models` = `LocalModelManager.modelsDir()`
  (`context.filesDir/models`). Shared with the embedded engine and with Chat.

## 5. Status detection — `ModuleController.kt`

- `getTmuxSession("llamaserver")` → `"llamaserver"` — `isRunning()` checks `tmux
  has-session`.
- `getModulePort("llamaserver")` → `8085` — `waitForPortOpen()` TCP-polls up to 8s.
- `getModuleStartScript` → `~/scripts/llamaserver/start.sh`; stop →
  `.../stop.sh`.
- The verification check validates `command -v llama-server` live.

## 6. Real app screens

### `LlamaServerFragment.kt`

- Service status, Start/Restart buttons.
- **Model selector** (`showModelPicker`): picks `LLAMA_SERVER_MODEL` among the `.gguf`
  files already downloaded in `LocalModelManager` (it has no `/api/tags` like Ollama —
  it lists the files directly). If there are none, it prompts to download one from Chat
  AI.
- Config at `~/.llamaserver_user_config` via `readConfigValue`/`writeConfigValue`.
- **PARAMETERS card** — two numeric fields, "Context (tokens)" and "CPU Threads", that
  write `LLAMA_SERVER_CTX_SIZE`/`LLAMA_SERVER_THREADS` into the config (`0` = the
  binary's own default). Simple validation (integers ≥ 0) before saving; it only takes
  effect the next time the service starts, not live.
- **Real status via `GET /health`** — "active" doesn't mean just "the tmux session
  exists"; `llama-server` can take several seconds to load the model (mmap + backend)
  before accepting requests, and returns `{"status":"loading model"}` during that
  window. `refreshStatus()` runs on a background `Thread`: if the process is running, it
  does `GET http://127.0.0.1:$PORT/health` (2s timeout) and shows "● Active (model
  loaded)" (`status: ok`), "◐ Loading model…" (`status: loading model`), the raw status
  for any other value, or "● Process active (not responding yet)" if `/health` doesn't
  answer.

### `LocalAIFragment.kt` ("Local AI" in the More menu)

- **Detected GPU backend status** (via `LlamaEngine.getGpuDeviceName()`).
- **CPU-only / Vulkan-if-available** selector (`GpuBackend` enum) — the runtime switch
  that Ollama's GPU variant doesn't allow.
- `temperature` / `context_size` sliders with a dynamic `safeMax` based on the device's
  RAM.
- Management of downloaded `.gguf` models.

### `LocalModelManager.kt` (util) — `.gguf` download with resume

- Directory `filesDir/models`. Pattern `.part` → `name.gguf.part`.
- **Resume via `Range: bytes=N-`**: `206` → append (progress starts at N); `416` →
  discards the `.part` and retries once; `200` (server without Range support) →
  truncates and starts from scratch.
- **Multi-layer validation**: downloaded/`Content-Length` ratio ≥ 0.95, an absolute
  minimum of 1024 bytes, and **real `GGUF` magic bytes** (first 4 bytes) before
  renaming to the final destination.
- Speed/ETA every 500ms.
- `cleanupOrphanedPartFiles()` deletes orphaned `.part` files except the one in
  progress.

### `LocalAIFragment.CATALOG` — curated models (Q4_K_M, 0.5B–3B, Hugging Face)

`Qwen2.5-0.5B`, `Qwen2.5-1.5B`, `SmolLM2-1.7B`, `Llama-3.2-1B`, `Llama-3.2-3B`,
`Gemma-2-2B` (URLs `resolve/main/<file>.gguf`). Models can also be added by direct URL +
name (`showAddModelDialog`, advanced option).

## 7. The embedded JNI engine (`LlamaEngine`)

- Kotlin wrapper around `kairos_llm` (JNI `com.termux.llm.LlamaEngine`). No coroutines —
  blocking calls + callbacks.
- **`loadBackends(nativeLibDir)`** → `ggml_backend_load_all_from_path` (or
  `ggml_backend_load_all`), idempotent. Loads `libggml-cpu-android_*.so` (dotprod/fp16/
  i8mm/SVE variants, ggml scores the best one) + `libggml-vulkan.so`.
- **GPU detection**: `getGpuDeviceName()` walks `ggml_backend_dev_count()` looking for
  the first `GGML_BACKEND_DEVICE_TYPE_GPU/IGPU` device.
- **`GpuBackend.resolveGpuLayers()`**: `CPU_ONLY → 0`; `VULKAN_IF_AVAILABLE → 99` (full
  offload) if there's a GPU, otherwise `0` with a silent fallback.
- **Inference** (`LLMInference.cpp`): maps `useMmap/useMlock` → `llama_load_mode`,
  sampler chain, **incremental history with KV-cache** (`_prevLen` avoids re-tokenizing
  everything), `_assistantRole = "model"` for Gemma-style templates.
- **Actionable errors**: `llama_log_set` captures WARN/ERROR lines from ggml/llama.cpp
  in `_lastErrorLog` and appends them to the exception — this is the source of the
  patterns used by `LlmErrorMapper` (translates native-engine errors and Ollama HTTP
  errors by reading `{"error": ...}`).
- **`GGUFReader`**: reads `.gguf` metadata (`context_length`, `chat_template`) without
  loading the model; falls back to 2048 and the chatml template.
- `numThreads = min(availableProcessors, 4)`. `temperature`/`context_size` from prefs.

## 8. Use as an AI backend for ANY module/purpose

`llama-server` is a **reusable local HTTP service** — any module, CLI, agent or script
that accepts an `openai-compatible` provider can point it at
`http://127.0.0.1:8085/v1`. Pattern already used in the app:

| Consumer | How it connects | Code |
|---|---|---|
| **ChatFragment** ("local" engine, `http` transport) | `POST /v1/chat/completions` SSE with `messages` + system prompt, model `"local"` (the server serves a single model) | `ChatFragment.makeLlamaServerRequest()` |
| **Embedded JNI engine** (fallback) | `embedded` transport; if it fails, automatically falls back to `llama-server` if available | `ChatFragment.makeLocalRequest()` (catch → fallback) |
| **OpenCode** | `@ai-sdk/openai-compatible` provider, `baseURL: http://127.0.0.1:8085/v1`, `apiKey: "llamaserver"`, model `llamaserver/<model>` | `OpenCodeNative.llamaServerConfig()`, "Configure local llama-server" button in `OpenCodeFragment` |
| **Hermes** | Local OpenAI-compatible provider; lists `.gguf` files directly from `LocalModelManager` (no `/api/tags`) | `HermesFragment` ("Use local llama-server") |
| **Additional CLIs** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:8085/v1", model)` | `LocalCliProviderNative.kt`, `GenericModuleFragment.useLlamaServerLocal` |
| **cactus** | `/ai` → reasoner via Ollama 11434 or llama-server 8085 | `ChatFragment.dispatchCactusRun()` |
| **Any custom bash script** | `curl http://127.0.0.1:8085/v1/chat/completions -d '{...}'` | — |

**Key endpoint:** `POST http://127.0.0.1:8085/v1/chat/completions` — OpenAI-compatible,
SSE streaming (`data: {...}` + `[DONE]` sentinel). **Stateless per request**: the full
`messages` array + system prompt has to be resent every time (unlike the JNI engine,
which keeps incremental history). The `model` field is ignored (it serves **a single
model**, the one set in `LLAMA_SERVER_MODEL`).

**Web Search (internet for models):** the Chat's "Web: ON/OFF" toggle uses Ollama's Web
Search API as a **shared service** (ollama + llama-server + cloud) — see
`docs/modulos/ollama.en.md` §8. Context is injected in `makeLlamaServerRequest` the same
way as in Ollama.

## 9. Registry (`~/.android_server_registry`)

```
llamaserver.installed=true
llamaserver.version=<llama-server version>
llamaserver.install_date=YYYY-MM-DD
llamaserver.commands=llama-server -m <model> --host 127.0.0.1 --port 8085
llamaserver.port=8085
llamaserver.location=termux_native
```

## 10. Reference commands (from a Termux shell)

```bash
llama-server --version                                   # binary installed
# Real startup (equivalent to what start.sh does):
cd $PREFIX/lib && llama-server -m $HOME/../files/models/<model>.gguf --host 127.0.0.1 --port 8085
# Test the OpenAI-compatible endpoint:
curl -s http://127.0.0.1:8085/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"local","messages":[{"role":"user","content":"hello"}]}'
# .gguf models (private storage shared via sharedUserId):
ls -lh /data/data/com.termux/files/models/
```

## 11. Comparison with Ollama

See the full comparison table in `docs/modulos/ollama.en.md`. In short: Ollama =
external multi-model server (REST + OpenAI-compatible, can bind to LAN); llama-server =
**single-model** `.gguf` server (OpenAI-compatible only, loopback only) + in-process JNI
embedded engine. Chat treats them as engines with a separate selector, but they share
the models directory and the Web Search service.

## Screen controls (`LlamaServerFragment.kt`)

| Control | What it does | Why |
|---|---|---|
| "llama-server" switch | `switchRow()` — ON starts the service (`startModuleService`), OFF stops it. If enabled without a chosen model, it reverts to OFF (`setSwitchState(false)`) and warns "Pick a model first" | The "model first" validation prevents an enabled switch that started nothing |
| "🗂 Choose model (.gguf)" | Opens `showModelPicker()` — lists the `.gguf` files already downloaded (`LocalModelManager.listModels()`) and saves the choice in `~/.llamaserver_user_config` | This module doesn't download its own models — it reuses Chat AI/`LocalAIFragment`'s catalog |
| "📥 Model catalog (GGUF)" | Navigates to `LocalAIFragment` (download catalog) | — |
| "🔄 Update" | `updateModuleService()` | — |
| "🗑 Uninstall" (MAINTENANCE card) | `confirmUninstallModule()` | Standalone button instead of a full card — avoids duplicating "Update" above |
| "Context (tokens)" / "CPU Threads" / "GPU layers (-ngl)" field | Numeric, `0` = the binary's default (auto-detection). Written to `~/.llamaserver_user_config`, read by `start.sh` on the next start | `-ngl` is real: the binary is built with the Vulkan backend (`GGML_VULKAN=ON`) |
| "API key (optional)" field | The real server's `--api-key` flag | Confirmed against ggml-org/llama.cpp's `tools/server/README.md` |
| "Concurrent slots (--parallel)" field | `--parallel` flag | — |
| "Embeddings mode (--embeddings)" switch | `--embeddings` flag | — |
| "Listen on LAN (0.0.0.0)" switch | Binds the server to all interfaces instead of loopback only | Explicit warning text on screen: "setting an API key before enabling LAN is recommended" — without it, any device on the network can use the server with no restriction |
| "Save parameters" | Validates that ctx/threads/ngl/parallel are integers ≥ 0 before writing; if not, "Invalid values" | Changes take effect only on the next service start, not live — clarified in the confirmation toast |

**Non-obvious detail**: the "Active" status isn't just "the process is running" — it
does a real `GET /health` against the server (`checkHealth()`) because `llama-server`
can take several seconds to load the model (mmap + backend) before accepting requests.
