# llama-server / Local AI (llama.cpp)

**Kairos module** — managed through the Kairos UI (Modules tab, id `llamaserver`, name
"IA Local (llama.cpp)"). Installation, start/stop and state are handled by the app via `ProcessBuilder`
→ `modulos/llamaserver.sh`.

llama.cpp has **two pieces** that coexist in the `llama-engine/` module: the embedded
JNI engine (in-process, no port) and the `llama-server` server (HTTP `:8085`) that **any
module/CLI can use as an OpenAI-compatible backend**.

---

**Script:** `modulos/llamaserver.sh` (362 lines; synchronized copy in `app/src/main/assets/scripts/llamaserver.sh`).
**Port:** `:8085` (loopback `127.0.0.1` by default; the `LLAMA_SERVER_LAN` toggle binds it to `0.0.0.0`).
**Registry:** prefix `llamaserver.*`
**Gradle module:** `llama-engine/` (NDK/CMake/JNI, adapted from OfflineLLM, Apache-2.0).
**Technical docs:** `docs/ia-local/llama-cpp-local-engine.md` (build log).

> Convention in this document: **confirmed** = verified by reading the code; **unverified** =
> inference without a test on a device.

---

## 1. Overview

llama.cpp is the local LLM inference engine that Kairos ships **compiled inside the APK**
(`llama-engine/` module, NDK, ABI `arm64-v8a`). It is not a Termux package: it is built in
CI/GitHub Actions and the binary travels as an APK asset. In `modules.json` (`:757-777`): name
"IA Local (llama.cpp)", `category: "ai"`, `port: "8085"`, `hasSwitch: true`,
`tmuxSession: "llamaserver"`, no variants. It is a **functional module**: own UI with live state
(`GET /health`), a service with a switch, and real options that translate into binary flags.

It has **two pieces** that share the same module and the same models directory:

| Piece | What it is | Port | Use |
|---|---|---|---|
| `kairos_llm` (JNI wrapper `LlamaEngine`) | **In-process** engine for the "Local AI" tab/native Chat | no port | Inference inside the app's process (no server in between). Incremental history with KV-cache |
| `llama-server` (the real llama.cpp binary) | **OpenAI-compatible** HTTP server | `127.0.0.1:8085` | Serves separate CLIs/agents/Termux processes (OpenCode, Hermes, OpenClaw, cactus) and acts as a backend for any module |

**Why both exist:** Termux CLIs (OpenCode/Hermes/OpenClaw) are **separate processes** and need
a network endpoint to use the engine — a purely in-process approach (".so + pure JNI") could not
offer that. The HTTP server solves it: same `.gguf`, same engine, but reachable over a socket.

**Reusable backend (not only for Chat):** `POST http://127.0.0.1:8085/v1/chat/completions`
is OpenAI-compatible. Any module, CLI, agent or script that accepts an `openai-compatible`
provider can point there (see section 8).

## 2. Permissions

No special Android permissions are required. The binary and its `.so` files travel as APK assets
(extracted by `KairosBootstrap` to `~/scripts/install/`), and the embedded engine uses JNI inside
the process. Access to the `.gguf` models (private storage
`/data/data/com.termux/files/models`) works from Termux scripts because the app and Termux share
`sharedUserId` (`com.termux`).

## 3. Installation — `modulos/llamaserver.sh`

Accepts `--silent [--force]`, `--describe` and `--describe-files` (`:45-60`). With `--describe`
it returns the declarative JSON manifest that `ModuleController.kt` uses:
`{"id":"llamaserver","supports_silent":true,"supports_force":true,...}`. No variants (a single
installation path, `:57`). Declared dependencies: none (`dependencies: []`, `:99`);
`tmux` and `jq` are assumed present.

**It downloads nothing**: it copies `~/scripts/install/llama-server` (extracted by `KairosBootstrap`) to
`$PREFIX/bin` and the `.so` files to `$PREFIX/lib` (`:141-160`). If the source does not exist → `error` (`:142`).

### Steps (3 total, with checkpoint in `~/.install_llamaserver_checkpoint`)

```
STEP 1/3  Copy the compiled binary:
            $HOME/scripts/install/llama-server → $PREFIX/bin/llama-server  (chmod 755)
            Copy dependent .so files to $PREFIX/lib/:
              libggml-base.so, libllama.so, libllama-common.so, libmtmd.so, libllama-server-impl.so
            → fix for the bug "CANNOT LINK EXECUTABLE: library libllama-server-impl.so not found"
            → defensive check: timeout 5 llama-server --version
STEP 2/3  Control scripts: ~/scripts/llamaserver/start.sh and stop.sh
STEP 3/3  ~/.llamaserver_user_config (LLAMA_SERVER_MODEL= + LLAMA_SERVER_PORT=8085)
          + registry_install llamaserver "<version>" "port=8085"
```

Registry: `registry_install llamaserver <version> port=8085` (`:112-115`).

**How the binary reaches the APK:** the Gradle task `downloadLlamaCpp` of the `llama-engine` module
clones llama.cpp at build time (tag `b10194` in `llama-engine/build.gradle:286`, overridable
with env `KAIROS_LLAMACPP_TAG`), builds `llama-server` with CMake (flags: `LLAMA_BUILD_SERVER=ON`,
`GGML_VULKAN=ON`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `BUILD_SHARED_LIBS=ON`,
OpenMP/LLAMAFILE OFF) and copies it to `app/src/main/assets/scripts/llama-server` (via
`copy_shared_libs.sh` + `add_dependencies`). `KairosBootstrap.kt` extracts that asset to
`~/scripts/install/llama-server` at runtime. A text patch over llama.cpp's `tools/CMakeLists.txt`
disables the ~10 unneeded debug/bench tools that broke the link.

### Resolved problems

- **`libllama-server-impl.so not found`** — llama-server needs its dependent `.so` files next to it
  or on the load path. They are explicitly copied to `$PREFIX/lib/` in STEP 1.
- **Backends not found** — `GGML_BACKEND_DIR` is a **compile-time macro**, never a
  runtime environment variable. llama.cpp looks for the backend `.so` files in
  `get_executable_path()` (the binary's directory) and `fs::current_path()` — which is why `start.sh`
  does `cd '$PREFIX/lib'` before launching the binary (confirmed by reading
  `ggml/src/ggml-backend-reg.cpp` of tag `b10194`). The more robust alternative (copying the
  `.so` files to `$PREFIX/bin`) remains a future option.
- **`send-keys` / interactive shell**: not applicable — the whole command goes straight to
  `tmux new-session`.

## 4. Startup — generated `start.sh`

- tmux session **`llamaserver`**; idempotent (if it is already running, exit 0) (`:181-273`).
- Reads `~/.llamaserver_user_config` **at runtime** with `grep|cut|tr` (no `eval`): `LLAMA_SERVER_MODEL`,
  `LLAMA_SERVER_PORT`, `LLAMA_SERVER_CTX_SIZE`, `LLAMA_SERVER_THREADS`, and the optional flags
  `-c`, `-t`, `-ngl`, `--api-key`, `--embeddings`, `--parallel`.
- **Model required**: if the `.gguf` does not exist in `MODELS_DIR` it fails with a clear message (`:245-248`).
- **Configurable context (tokens) and CPU threads**: `LLAMA_SERVER_CTX_SIZE`/`LLAMA_SERVER_THREADS`
  (default `0` = use the binary's default: `-c 0` = the model's training context,
  `-t` auto-detected) are appended as `-c $CTX_SIZE`/`-t $THREADS` to `EXTRA_ARGS` only if the
  stored value is not `0`.
- Host `127.0.0.1`, or `0.0.0.0` if `LLAMA_SERVER_LAN=1` (`:241-243`).
- **Actual command** (`:250-264`):
  ```sh
  cd '$TERMUX_PREFIX/lib' && '$BIN' -m '$MODELS_DIR/$MODEL_FILE' --host 127.0.0.1 --port $PORT \
    $EXTRA_ARGS > '~/kairos_logs/llamaserver_serve.log' 2>&1
  ```
- **MODELS_DIR** = `/data/data/com.termux/files/models` = `LocalModelManager.modelsDir()`
  (`context.filesDir/models`, fixed path at `:70`). Shared with the embedded engine and with Chat.
- `stop.sh` kills the session.

## 5. State detection — `ModuleController.kt`

- `getTmuxSession("llamaserver")` → `"llamaserver"` (`:1203`) — `isRunning()` checks `tmux has-session`.
- `getModulePort("llamaserver")` → `8085` (`:1244`) — `waitForPortOpen()` polls TCP up to 8s.
- `getModuleStartScript` → `~/scripts/llamaserver/start.sh` (`:1168`); stop → `.../stop.sh` (`:1189`).
- `stopAllModules` includes it (`:322`).
- `verificar.sh` (`_ver_cmd` strategy) validates `command -v llama-server` live.

## 6. App screens

### `LlamaServerFragment.kt`

- Compact `:8085` row + ⚙ + switch (`:52-81`); a card with the chosen model and state via
  `GET /health` (`checkHealth()`, `:135-187`).
- **Real state via `/health`**: `llama-server` can take several seconds to load the model
  (mmap + backend) before accepting requests and returns `{"status":"loading model"}` in that
  window. `refreshStatus()` runs on a background `Thread`: if the process is running, it does
  `GET http://127.0.0.1:$PORT/health` (2s timeout) and shows "● Active (model loaded)"
  (`status: ok`), "◐ Loading model…" (`status: loading model`), the raw status if it is any other
  value, or "● Process active (not responding yet)" if `/health` does not answer.
- **Model picker** ("Choose model (.gguf)", `showModelPicker`, `:189-210`): lists
  `LocalModelManager.listModels` and writes `LLAMA_SERVER_MODEL` to `~/.llamaserver_user_config`
  (there is no `/api/tags` as in Ollama — it lists the files directly). If there are none, it
  tells the user to download from Chat IA.
- "Catalog" → `LocalAIFragment`.
- Turning the switch on without a chosen model reverts it to OFF (`:85-90`) and notifies. It also
  **checks free RAM** with `MemoryMonitor` (dialog if critical, toast if in warning, `:97-109`).

### `LlamaServerConfigFragment.kt`

Concentrates all parameters (the main screen only has the switch, ⚙, "Choose model" and
"Catalog"). `onResume` enables `FLAG_SECURE` and `filterTouchesWhenObscured` (`:46-66`).
"Save parameters" validates integers ≥ 0 and writes 7 `LLAMA_SERVER_*` keys (`:254-271`); it also
saves `OLLAMA_SYSTEM_PROMPT` (a key shared with Ollama) on a thread (`:272-278`). The temperature/context sliders
and the CPU/Vulkan radio write to the `kairos_llm_prefs` `SharedPreferences`
and affect **only the embedded engine**, not `llama-server` (`:282-386`) — the screen groups them
under the same title. Server changes apply on the next start, not live.

### `LocalAIFragment.kt` ("Local AI" in the More menu)

- **Detected GPU backend state** (via `LlamaEngine.getGpuDeviceName()`).
- **CPU-only / Vulkan-if-available** selector (`GpuBackend` enum) — the runtime switch that
  Ollama's GPU variant does not allow.
- `temperature` / `context_size` sliders with a dynamic `safeMax` based on device RAM.
- Management of downloaded `.gguf` models.

### `LocalModelManager.kt` (util) — `.gguf` download with resume

- Directory `filesDir/models`. `.part` pattern → `name.gguf.part`.
- **Resume via `Range: bytes=N-`**: `206` → append (progress starts at N); `416` → discard
  `.part` and retry once; `200` (server without Range) → truncate and start from zero.
- **Multi-layer validation**: downloaded/`Content-Length` ratio ≥ 0.95, absolute minimum of 1024
  bytes, and **real `GGUF` magic bytes** (first 4 bytes) before renaming to the final destination.
- Speed/ETA every 500ms.
- `cleanupOrphanedPartFiles()` deletes orphaned `.part` files except the one in progress.

### `LocalAIFragment.CATALOG` — curated models (Q4_K_M, 0.5B–3B, Hugging Face)

`Qwen2.5-0.5B`, `Qwen2.5-1.5B`, `SmolLM2-1.7B`, `Llama-3.2-1B`, `Llama-3.2-3B`, `Gemma-2-2B`
(URLs `resolve/main/<file>.gguf`). A model can also be added by direct URL + name
(`showAddModelDialog`, advanced option).

## 7. The embedded JNI engine (`LlamaEngine`)

- Kotlin wrapper of `kairos_llm` (JNI `com.termux.llm.LlamaEngine`). **No coroutines** —
  blocking calls + callbacks (same criterion as `RootfsInstaller.kt`).
- **`loadBackends(nativeLibDir)`** → `ggml_backend_load_all_from_path` (or `ggml_backend_load_all`),
  idempotent. Loads `libggml-cpu-android_*.so` (dotprod/fp16/i8mm/SVE variants, ggml scores the
  best one) + `libggml-vulkan.so`.
- **GPU detection**: `getGpuDeviceName()` walks `ggml_backend_dev_count()` looking for the first
  `GGML_BACKEND_DEVICE_TYPE_GPU/IGPU` device.
- **`GpuBackend.resolveGpuLayers()`**: `CPU_ONLY → 0`; `VULKAN_IF_AVAILABLE → 99` (full
  offload) if there is a GPU, otherwise `0` with a silent fallback.
- **Inference** (`LLMInference.cpp`): maps `useMmap/useMlock` → `llama_load_mode`, sampler
  chain, **incremental history with KV-cache** (`_prevLen` avoids re-tokenizing everything),
  `_assistantRole = "model"` for Gemma-style templates.
- **Actionable errors**: `llama_log_set` captures WARN/ERROR lines from ggml/llama.cpp into
  `_lastErrorLog` and appends them to the exception — it is the source of the `LlmErrorMapper`
  patterns (it translates native engine errors and Ollama HTTP errors by reading
  `{"error": ...}`).
- **`GGUFReader`**: reads the `.gguf` metadata (`context_length`, `chat_template`) without loading the
  model; falls back to 2048 and the chatml template.
- `numThreads = min(availableProcessors, 4)`. `temperature`/`context_size` from prefs.

## 8. Use as an AI backend for ANY module/purpose

`llama-server` is a **reusable local HTTP service** — any module, CLI, agent or
script that accepts an `openai-compatible` provider can point it to
`http://127.0.0.1:8085/v1`. Pattern already used in the app:

| Consumer | How it connects | Code |
|---|---|---|
| **ChatFragment** ("local" engine, `http` transport) | `POST /v1/chat/completions` SSE with `messages` + system prompt, model `"local"` (the server serves a single model) | `ChatFragment.makeLlamaServerRequest()` |
| **Embedded JNI engine** (fallback) | `embedded` transport; if it fails, it automatically falls back to `llama-server` if available | `ChatFragment.makeLocalRequest()` (catch → fallback) |
| **OpenCode** | Provider `@ai-sdk/openai-compatible`, `baseURL: http://127.0.0.1:8085/v1`, `apiKey: "llamaserver"`, model `llamaserver/<model>` | `OpenCodeNative.llamaServerConfig()`, "Configure local llama-server" button in `OpenCodeFragment` |
| **Hermes** | OpenAI-compatible local provider; lists `.gguf` directly from `LocalModelManager` (no `/api/tags`) | `HermesFragment` ("Use local llama-server") |
| **AI CLIs** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:8085/v1", model)` | `LocalCliProviderNative.kt`, `GenericModuleFragment.useLlamaServerLocal` |
| **cactus** | `/ai` → reasoner via Ollama 11434 or llama-server 8085 | `ChatFragment.dispatchCactusRun()` |
| **Any custom bash script** | `curl http://127.0.0.1:8085/v1/chat/completions -d '{...}'` | — |

**Key endpoint:** `POST http://127.0.0.1:8085/v1/chat/completions` — OpenAI-compatible,
SSE streaming (`data: {...}` + `[DONE]` sentinel). **Stateless per request**: the full
`messages` array + system prompt must be resent each time (unlike the JNI engine, which
keeps incremental history). The `model` field is ignored (it serves **a single model**, the one in
`LLAMA_SERVER_MODEL`).

**Web Search (internet for models):** Chat's "Web: ON/OFF" toggle uses Ollama's Web Search API
as a **shared service** (ollama + llama-server + cloud) — see `ollama.en.md` §8.
Context is injected in `makeLlamaServerRequest` the same way as in Ollama.

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
llama-server --version                                   # installed binary
# Actual startup (equivalent to what start.sh does):
cd $PREFIX/lib && llama-server -m $HOME/../files/models/<model>.gguf --host 127.0.0.1 --port 8085
# Test the OpenAI-compatible endpoint:
curl -s http://127.0.0.1:8085/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"local","messages":[{"role":"user","content":"hola"}]}'
# .gguf models (private storage shared via sharedUserId):
ls -lh /data/data/com.termux/files/models/
```

## 11. Comparison with Ollama

See the full comparison table in `ollama.en.md` §1. In short: Ollama = external multi-model
server (REST + OpenAI-compatible, can bind to LAN); llama-server = **single-model** `.gguf`
server (OpenAI-compatible; loopback by default, optional LAN via `LLAMA_SERVER_LAN`) + in-process
embedded JNI engine. Chat treats them as engines with a separate selector, but they
share the models directory and the Web Search service.

## 12. Status / verification

- Builds and links in CI (GitHub Actions + GitLab); a local Windows build was verified (universal
  APK ~241MB with llama-engine/Vulkan included).
- HTTP server confirmed working end to end.
- Connecting more modules to the backend: already implemented for OpenCode/Hermes/AI CLIs/cactus —
  see §8. Any future module that accepts an `openai-compatible` provider can reuse the same
  pattern without touching llama.cpp.
- Pending: test the full flow on a real device and the embedded JNI engine with models ≥3B.

## 13. Screen controls

The main screen (`LlamaServerFragment.kt`) only has the switch, ⚙, "Choose model" and
"Catalog"; the numeric fields, API key, parallel, embeddings and LAN, "Save parameters",
Restart/Update/Uninstall are in `LlamaServerConfigFragment`. Approximate control count:
**~28** (≈ 4 on the main screen; ≈ 24 on the configuration screen: 7 server ones
[ctx, threads, `-ngl`, API key, parallel, embeddings, LAN] + Save, system prompt, chat context in
RAM [4 presets + custom], embedded engine [2 CPU/Vulkan radios + temperature slider + context
slider], maintenance [Restart, Detail, GPU info, Update, Diagnose, Uninstall]).

| Control | What it does | Why |
|---|---|---|
| "llama-server" switch | `switchRow()` — ON starts the service (`startModuleService`), OFF stops it. If turned on without a chosen model, it reverts to OFF (`setSwitchState(false)`) and notifies "Pick a model first" | The "model first" validation avoids a switch that is on but started nothing |
| "🗂 Choose model (.gguf)" | Opens `showModelPicker()` — lists the already-downloaded `.gguf` files (`LocalModelManager.listModels()`) and stores the choice in `~/.llamaserver_user_config` | This module does not download models itself — it reuses the Chat IA/`LocalAIFragment` catalog |
| "📥 Model catalog (GGUF)" | Navigates to `LocalAIFragment` (download catalog) | Lives inside the Local AI module instead of separately |
| "🔄 Update" | `updateModuleService()` | — |
| "🗑 Uninstall" (MAINTENANCE card) | `confirmUninstallModule()` | A standalone button instead of `addMaintenanceCard()` — avoids duplicating "Update" (consistent with Db/Entorno/Qemu/Remote/Ciberseguridad) |
| "Context (tokens)" / "CPU threads" / "GPU layers (-ngl)" fields | Numeric, `0` = binary default (auto-detection). Written to `~/.llamaserver_user_config`, read by `start.sh` on the next start | `-ngl` is real: the binary builds with a Vulkan backend (`GGML_VULKAN=ON`) and without that flag GPU acceleration stays inactive |
| "API key (optional)" field | The real server's `--api-key` flag | Confirmed against `tools/server/README.md` of ggml-org/llama.cpp — without it the server only runs unauthenticated |
| "Concurrent slots (--parallel)" field | `--parallel` flag | — |
| "Embeddings mode (--embeddings)" switch | `--embeddings` flag | — |
| "Listen on LAN (0.0.0.0)" switch | Binds the server to all interfaces instead of loopback only | Explicit warning text on screen: "setting an API key before enabling LAN is recommended" — without it, any device on the network can use the server unrestricted (the UI warns with text, it does not block, `:226-231`) |
| "Save parameters" | Validates that ctx/threads/ngl/parallel are integers ≥ 0 before writing; otherwise "Invalid values" | Changes only apply on the next start of the service, not live — clarified in the confirmation toast |

**Non-obvious detail**: the "Active" state is not just "the process is running" — it does a real
`GET /health` against the server (`checkHealth()`) because `llama-server` can take several seconds
to load the model before accepting requests.

## 14. Files and paths it creates

`$PREFIX/bin/llama-server`, `.so` files in `$PREFIX/lib/` (libggml-base, libllama, libllama-common,
libmtmd, libllama-server-impl), `~/scripts/llamaserver/start.sh|stop.sh`,
`~/.llamaserver_user_config`, `~/kairos_logs/llamaserver_serve.log`,
`~/.install_llamaserver_checkpoint` (deleted at the end), registry entry. Models in
`/data/data/com.termux/files/models` (shared with Chat).

## 15. Security and known limitations

- **Visible API key**: `LlamaServerConfigFragment.kt:150` does
  `apiKeyInput?.setText(readConfigValue("LLAMA_SERVER_API_KEY"))` — the stored API key is shown
  again in plain text in an `EditText` (`InputType.TYPE_CLASS_TEXT`, `:527`, unmasked), and
  it is stored in clear in `~/.llamaserver_user_config`. Existing mitigation: `FLAG_SECURE` and
  anti-tapjacking on that screen.
- The key travels as an argument (`--api-key $API_KEY`, `:233`), visible in the process list
  (`ps`/`/proc/<pid>/cmdline`) to the same UID; there is no explicit `chmod 600` on the config
  (`$HOME` files are private to the app's UID; real risk low, unverified).
- Although there is no `eval`/`source`, `API_KEY`, `MODEL_FILE` and the other values are
  interpolated unquoted into the string that goes to `tmux new-session` (shell) (`:264`). `tr -d`
  removes quotes and spaces, not `;`/`$()`. The numeric ones are validated by the UI; the API key
  and the model name are not → local injection is possible if the config is edited by hand
  (severity unverified). `LLAMA_SERVER_MODEL`/`PORT` have no character validation before being
  interpolated into the shell.
- LAN: `LLAMA_SERVER_LAN=1` → `0.0.0.0` (`:241-243`); without an API key it stays unauthenticated.
- Post-install verification is weak: `timeout 5 llama-server --version || warn` (`:166`);
  if it fails, it only warns and continues, and then records the version as `unknown` (`:344-346`).
  It does not use `verify_binary_installed()`. The "already installed" check (`:118`) only uses `[ -x ]`.
- The script header says "VERSIÓN 1.0.0 | 2026-08-05" (`:33`) even though the script has grown
  (API key, LAN, parallel, embeddings…): outdated header version.
- `LlamaServerConfigFragment` mixes HTTP server options and embedded engine options; users may
  get confused because the sliders do not affect `llama-server` (UX, unverified).
- `ModuleController` does not use `isRunning` through `/health`: the list's "active" = live tmux
  session (full `isRunning` unverified).

## 16. Added

**2026-08-05** (first commit of `modulos/llamaserver.sh`).
