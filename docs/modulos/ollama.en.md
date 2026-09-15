# Ollama

**Kairos module** — managed from the UI (Modules tab). Installation, start/stop and
status are handled by the app via `ProcessBuilder` → `modulos/ollama.sh`.

Ollama is an **AI backend reusable by any module** of Kairos, not just the app's Chat.
Any CLI/agent/project that accepts an OpenAI-compatible endpoint or Ollama's REST API
can point to it at `http://127.0.0.1:11434`.

---

**Script:** `modulos/ollama.sh` — copy synced to `app/src/main/assets/scripts/ollama.sh`.
**Port:** `:11434`
**Registry:** prefix `ollama.*`

---

## 1. Overview

Ollama runs **native in Termux** (no proot, no container) — an inference engine for
local LLM models (quantized GGUF). It's Kairos's main chat engine alongside llama.cpp;
unlike that one, Ollama is a **persistent HTTP service** (`ollama serve`, port `11434`)
that the app talks to via REST API, and that **any Termux process** (OpenCode, Hermes,
OpenClaw, cactus, custom scripts) can consume.

**Reusable service (not just for Chat):**
- Exposes **two HTTP APIs** on `127.0.0.1:11434`:
  1. **Ollama's native REST API** (`/api/tags`, `/api/chat`, `/api/generate`,
     `/api/pull`, `/api/delete`, `/api/show`, `/api/embeddings`) — used by Kairos's Chat
     via `OllamaApiClient`.
  2. **OpenAI-compatible endpoint** `/v1/chat/completions` and `/v1/models` — the one
     used by the CLIs (OpenCode, Hermes, cactus, etc.) to treat it as an
     `openai-compatible` provider.
- `OLLAMA_HOST` bind: default `127.0.0.1` (this phone only). With the "Listen on LAN"
  toggle (`OLLAMA_LAN=1` in `~/.ollama_user_config`) it binds to `0.0.0.0` and becomes
  available to other devices on the local network.
- **Models downloaded via `ollama pull`** land in `~/.ollama/models` and are managed
  from the app (catalog + real list via `/api/tags`) or from the terminal (`ollama
  list`/`ollama rm`).

### When to use Ollama vs llama-server

| Criterion | Ollama (`:11434`) | llama-server (`:8085`) |
|---|---|---|
| Installation | `pkg install ollama` or `@mmmbuto/ollama-termux` (Vulkan) | Binary compiled into the APK (llama-engine NDK) |
| Models | `ollama.com/library` catalog + `ollama pull <tag>` | `.gguf` files in `filesDir/models` (shared) |
| API | Native REST + `/v1` OpenAI-compatible | Only `/v1/chat/completions` (one model at a time) |
| History | `messages` in every request (stateless) | Stateless per request (resend full `messages`) |
| GPU | Vulkan in the `termux_npm` variant | Vulkan in the embedded engine (runtime switch) |
| Serving several models | Yes (any tag) | No (one: `LLAMA_SERVER_MODEL`) |
| LAN | Optional `OLLAMA_LAN=1` | Loopback only (design decision) |

## 2. Permissions

Requires no special Android permissions of its own — uses `INTERNET` (implicit) to
serve its local API and for `ollama pull`. The GPU variant needs no privileged
permissions: Vulkan is enabled via Termux packages (`vulkan-tools`,
`mesa-vulkan-icd-freedreno`).

## 3. Installation — `modulos/ollama.sh`

Accepts `--silent --variant <gpu|standard> [--force]`. With `--describe` it returns a
declarative JSON manifest that `ModuleController.kt` uses so it doesn't have to guess
conventions:
`{"id":"ollama","supports_silent":true,"supports_force":true,"variants":["termux_npm","standard"],"variant_aliases":{"termux_npm":["gpu","termux"],"standard":["pkg","cpu"]},"variant_required":true}`.

### Variants

| Variant | Accepted aliases | What it installs | CPU/GPU |
|---|---|---|---|
| `standard` | `pkg`, `cpu` | `pkg install ollama` (generic ARM64 package from the Termux repo) | CPU only |
| `termux_npm` | `gpu`, `termux` | `@mmmbuto/ollama-termux` via npm — ARM64 build optimized with Vulkan support | GPU (Vulkan) if the driver supports it, falls back to CPU otherwise |

### Steps (6 total, with checkpoint per step)

```
STEP 1/6  Termux update + dependencies (tmux/curl/wget)
STEP 2/6  Install Ollama per variant:
            standard:   pkg install ollama
            termux_npm: pkg install vulkan-tools vulkan-loader-android
                        pkg install mesa-vulkan-icd-freedreno (Turnip/Adreno driver)
                        pkg install nodejs-lts
                        npm install -g @mmmbuto/ollama-termux@latest
                        → runs "ollama-termux" (downloads the real binary from a GitHub Release)
                        → verifies with ollama_binary_works() (ollama --version, not just command -v)
STEP 3/6  Control scripts: ollama_start.sh, ollama_stop.sh → ~/scripts/ollama/
STEP 4/6  ~/.ollama_user_config (inference parameters — only if it doesn't exist)
STEP 5/6  Aliases in .bashrc (ollama-start/-stop/-status/-list/-run/-pull, export OLLAMA_VULKAN=1)
STEP 6/6  Registry
```

**Relevant architecture notes**: `npm install` alone doesn't leave the real binary
working — the `@mmmbuto/ollama-termux` package only installs the CLI wrapper; the real
binary (`bin/ollama` + runtime) is downloaded from a GitHub Release and verified via
SHA256 the first time `ollama-termux` is run. For this reason, install verification uses
`ollama --version` (confirms the binary actually responds) instead of just `command -v`
(which only confirms the file exists with execute permission). The default bind is
`127.0.0.1` (this phone only); `0.0.0.0` only if the user enables the "Listen on LAN"
toggle. Startup runs in a `tmux` session, with output redirected to
`~/kairos_logs/ollama_serve.log`.

## 4. Status detection — `ModuleController.kt`

- `getTmuxSession("ollama")` → `"ollama-server"` — `isRunning()` checks `tmux
  has-session -t ollama-server`.
- `getModulePort("ollama")` → `11434` — used by `waitForPortOpen()` to confirm a real
  startup (TCP poll, up to 8s) before reporting success to the UI.
- `getModuleStartScript("ollama")` → `$HOME/scripts/ollama/ollama_start.sh`;
  `getModuleStopInfo("ollama")` → `.../ollama_stop.sh`.

## 5. App screens

### `OllamaFragment.kt` (module's main screen)

- STATUS card: process (`ollama serve`), port (`:11434`), version.
- ACTIVE MODEL card — queries `OllamaApiClient.psModels()` (`GET /api/ps`, models
  currently loaded in memory/VRAM) and shows name + memory size per model, or "None" if
  nothing is loaded.
- DOWNLOADED MODELS card — queries `OllamaApiClient.listModels()` (`GET /api/tags`),
  showing name, size and family per model.
- Buttons: "Open Chat AI with this model" (navigates to `ChatFragment`), "Restart
  service" (stop+start), start/stop switch.
- "Download model" → navigates to `ModelsFragment` (catalog + full model management).
- CONFIGURATION card → "Inference parameters" → navigates to `OllamaConfigFragment`.
- **"Update Ollama"** — reinstalls, reusing the variant saved in `ollama.install_mode`
  in the registry.
- **"GPU / Vulkan Info"** → dialog with real data: detected Vulkan device
  (`vulkaninfo`), whether `OLLAMA_VULKAN=1` is exported, and CPU features relevant for
  inference (`i8mm`/`dotprod`/`sve`, read from `/proc/cpuinfo`).
- Conditional banner "Running on CPU": reads `~/.ollama_backend_status` (a marker left
  by `ollama_start.sh`) — if the GPU variant fails to use Vulkan, Ollama silently falls
  back to CPU.
- "Free" (per active model) — `/api/generate` with `keep_alive:0`, so there's no need to
  wait for the timeout or restart the whole service.

### `ModelsFragment.kt`

- CATALOG card: curated, pre-loaded models (confirmed tags) — `qwen2.5:0.5b/1.5b/3b`,
  `gemma2:2b`, `llama3.2:1b/3b`. Each row shows "Installed" (if already in
  `OllamaApiClient.listModels()`) or "Download".
- INSTALLED MODELS card: real list via `OllamaApiClient.listModels()` — tapping a model
  opens detail (parameters, family) with a "Delete" option.
- "Download model (advanced)": free-text dialog for any tag not listed in the catalog.
- Download with real live progress (%, speed, ETA) via `OllamaApiClient.pullModel()`
  with streaming.

### `OllamaConfigFragment.kt`

Edits `~/.ollama_user_config` (7 keys) via
`OllamaApiClient.readConfig()`/`writeConfigValue()`/`resetConfig()`:

| Field | Default | Range/note |
|---|---|---|
| `OLLAMA_TEMP` | 0.7 | 0.0–2.0 (validated before saving) |
| `OLLAMA_TOP_P` | 0.9 | — |
| `OLLAMA_TOP_K` | 40 | — |
| `OLLAMA_REP_PENALTY` | 1.1 | — |
| `OLLAMA_NUM_CTX` | 2048 | context tokens |
| `OLLAMA_NUM_PREDICT` | 2048 | max. response tokens |
| `OLLAMA_SYSTEM_PROMPT` | (long text, in Spanish) | multiline |
| `OLLAMA_LAN` | `0` | "Listen on LAN" toggle — `1` = `0.0.0.0`, `0` = `127.0.0.1`. Applies only the next time the service starts |

Also covers: LAN networking, keep-alive/num_parallel/max_loaded_models, history
(RAM+disk), creating custom models via a Modelfile (`ollama create`), and full
maintenance.

## 6. Registry (`~/.android_server_registry`)

```
ollama.installed=true
ollama.version=<real version from "ollama --version">
ollama.install_date=YYYY-MM-DD
ollama.install_mode=termux_npm|standard
ollama.commands=ollama serve,ollama run,ollama list,ollama pull,ollama rm
ollama.port=11434
ollama.location=termux_native
```

## 7. Reference commands

```bash
ollama-start          # alias -> ollama_start.sh (tmux, log at ~/kairos_logs/ollama_serve.log)
ollama-stop           # alias -> ollama_stop.sh
ollama-status         # curl -s http://localhost:11434
ollama list           # downloaded models
ollama pull <model>   # download
ollama run <model>    # direct CLI chat (outside the app)
ollama rm <model>     # delete
```

## 8. Use as an AI backend for ANY module/purpose

Ollama **isn't just the app's Chat**: it's a reusable local HTTP service. Any Kairos
module, CLI, agent or custom script that accepts an "openai-compatible" provider (or
Ollama's REST API) can point to `http://127.0.0.1:11434`:

| Consumer | How it connects |
|---|---|
| **ChatFragment** ("ollama" engine) | `OllamaApiClient` → `POST /api/chat` with `messages` + `options` + `images` (base64) |
| **OpenCode** | `@ai-sdk/openai-compatible` provider with `baseURL: http://127.0.0.1:11434/v1` |
| **Hermes** | Local OpenAI-compatible provider (lists models via `/api/tags`) |
| **AI-agent CLIs** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:11434/v1", model)` |
| **cactus** | Reasoner via Ollama (or llama-server) |
| **Any custom bash script** | `curl http://127.0.0.1:11434/api/chat -d '{...}'` or `--base-url http://127.0.0.1:11434/v1` in compatible CLIs |

**Key endpoints (native REST API):**
- `GET  /api/tags` — installed models.
- `POST /api/chat` — chat (recommended: keeps `messages`, supports `images`).
- `POST /api/generate` — simple generation.
- `POST /api/pull` — download a model (progress streaming).
- `DELETE /api/delete` — delete a model.
- `POST /api/show` — model metadata (family, parameters, image capability).
- `POST /api/embeddings` — embeddings (for RAG/vectors).
- `GET  /api/ps` — models currently loaded in memory/VRAM.

**OpenAI compatibility:** `POST /v1/chat/completions` and `GET /v1/models` — this is the
bridge that lets Ollama be used as a backend for OpenCode, Hermes, or any tool that
speaks `openai-compatible`.

**Web Search (internet for models):** the Chat's "Web: ON/OFF" toggle uses **Ollama's
Web Search API** (`POST https://ollama.com/api/web_search`, a free Bearer key from
`ollama.com/settings/keys`, up to 5 results, 10s timeout) as a shared service for the
local engines (Ollama and llama-server) and cloud — the results are injected as prompt
context. It doesn't require Ollama's model to have internet access: the context is added
by Chat before the request.

## 9. Difference from embedded llama.cpp / llama-server

Ollama is an **external, multi-model HTTP service** (Termux process, REST + OpenAI-
compatible API); llama.cpp has two pieces — the **embedded JNI engine** (`LlamaEngine`,
in-process, no port) and the **`llama-server` server** (`:8085`, a single `.gguf`
model, OpenAI-compatible only). `ChatFragment` treats them as distinct engines with a
separate model selector, but they share the Web Search service.
