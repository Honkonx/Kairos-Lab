# Ollama

**Kairos module** — managed through the Kairos UI (Modules tab). Installation, start/stop and state are handled by the app via `ProcessBuilder` → `modulos/ollama.sh`.

Ollama is a **reusable AI backend for any Kairos module** — not just the app's Chat. Any CLI/agent/project that accepts an OpenAI-compatible endpoint or Ollama's REST API can point to `http://127.0.0.1:11434`.

---

**Script:** `modulos/ollama.sh` (v5.0.0, 864 lines) — synchronized copy in `app/src/main/assets/scripts/ollama.sh`.
**Port:** `:11434`
**Registry:** prefix `ollama.*`

> Convention: **confirmed** = verified by reading the code; **unverified** = inference without a test on a device.

---

## 1. Overview

A local LLM inference server (`ollama serve`, `:11434`), multi-model, native REST + OpenAI-compatible endpoint. It runs **natively in Termux** (no proot, no container) — an inference engine for local LLM models (quantized GGUF). It is Kairos's main chat engine alongside llama.cpp (see `llama-server.en.md`); unlike that one, Ollama is a **persistent HTTP service** that the app talks to over REST, and that **any Termux process** (OpenCode, Hermes, OpenClaw, cactus, custom scripts) can consume.

Catalog entry (`modules.json:3-29`): `category: "ai"`, `port: "11434"`, `hasVariants: true`, `hasSwitch: true`, `tmuxSession: "ollama-server"`, `installMethods: [termux_npm, standard]`.

Classification: **functional module** (the most complete in the group) — own UI with live state (`/api/ps`, `/api/tags`), a service with a switch, and real configuration. Approximate UI control count: **~40** (`OllamaFragment` ≈ 5: switch, ⚙, chat FAB, "Download model", per-model "Unload"; `OllamaConfigFragment` ≈ 35: 12 inference/server fields + LAN switch + Save/Restore + history 2×(4 presets + custom) + statistics + delete history + create model via Modelfile + 6 maintenance ones) plus `ModelsFragment` (catalog of 6 models, advanced download by tag, delete).

**Reusable service (not just for Chat):**
- It exposes **two HTTP APIs** on `127.0.0.1:11434`:
  1. **Ollama's native REST API** (`/api/tags`, `/api/chat`, `/api/generate`, `/api/pull`, `/api/delete`, `/api/show`, `/api/embeddings`) — used by Kairos's Chat via `OllamaApiClient`.
  2. **OpenAI-compatible endpoint** `/v1/chat/completions` and `/v1/models` — the one consumed by CLIs (OpenCode, Hermes, cactus `/ai`, etc.) to treat it as an `openai-compatible` provider.
- `OLLAMA_HOST` bind: default `127.0.0.1` (this phone only). With the **"Listen on LAN"** toggle (`OLLAMA_LAN=1` in `~/.ollama_user_config`) it binds to `0.0.0.0` and becomes available to other devices on the local network.
- **Models downloaded by `ollama pull`** end up in `~/.ollama/models` and are managed from the app (catalog + real list via `/api/tags`) or from the terminal (`ollama list`/`ollama rm`).

### When to use Ollama vs llama-server (summary for picking a backend)

| Criterion | Ollama (`:11434`) | llama-server (`:8085`) |
|---|---|---|
| Installation | `pkg install ollama` or `@mmmbuto/ollama-termux` (Vulkan) | Binary compiled in the APK (llama-engine NDK) |
| Models | `ollama.com/library` catalog + `ollama pull <tag>` | `.gguf` files in `filesDir/models` (shared) |
| API | Native REST + OpenAI-compatible `/v1` | Only `/v1/chat/completions` (a single model at a time) |
| History | `messages` in each request (stateless) | Stateless per request (resend the full `messages`) |
| GPU | Vulkan in the `termux_npm` variant | Vulkan in the embedded engine (runtime switch) |
| Serving several models | Yes (any tag) | No (one: `LLAMA_SERVER_MODEL`) |
| LAN | Optional `OLLAMA_LAN=1` | Optional `LLAMA_SERVER_LAN=1` (see `llama-server.en.md`) |

## 2. Permissions

It does not require special Android permissions of its own — it uses `INTERNET` (implicit) to serve its local API and for `ollama pull`. The GPU variant needs neither `WRITE_SECURE_SETTINGS` nor anything privileged: Vulkan is enabled through Termux packages (`vulkan-tools`, `mesa-vulkan-icd-freedreno`), not through Android permissions.

## 3. Installation — `modulos/ollama.sh`

Flags (`ollama.sh:55-64`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant <gpu|standard>`. `--variant` is **mandatory in silent mode** (`ollama.sh:136-140`) — which is why the UI cannot use the generic update helper. With `--describe` it returns a declarative JSON manifest that `ModuleController.kt` uses: `{"id":"ollama","supports_silent":true,"supports_force":true,"variants":["termux_npm","standard"],"variant_aliases":{"termux_npm":["gpu","termux"],"standard":["pkg","cpu"]},"variant_required":true}`.

### Variants

| Variant | Accepted aliases | What it installs | CPU/GPU |
|---|---|---|---|
| `standard` | `pkg`, `cpu` | `pkg install ollama` (generic ARM64 package from Termux's repo) | CPU only |
| `termux_npm` | `gpu`, `termux` | `@mmmbuto/ollama-termux` via npm — optimized ARM64 build with Vulkan support (`:303-586`) | GPU (Vulkan) if the driver supports it, falls back to CPU otherwise |

A `--variant` value that matches neither pattern ABORTS with an explicit `[ERROR]` (`:142-154`) instead of leaving `INSTALL_MODE` unnormalized and marking the checkpoint as done.

### Steps (6 total, with a per-step checkpoint in `~/.install_ollama_checkpoint`)

```
STEP 1/6  Termux update + dependencies (tmux/curl/wget) — skipped if instalar.sh/wizard already ran
STEP 2/6  Install Ollama according to the variant:
            standard:   pkg install ollama
            termux_npm: pkg install vulkan-tools vulkan-loader-android
                        driver according to vendor (see below)
                        pkg install nodejs-lts
                        npm install -g @mmmbuto/ollama-termux@latest
                        → runs "ollama-termux" (downloads the real binary from a GitHub Release)
                        → verifies with ollama_binary_works() (ollama --version, not just command -v)
STEP 3/6  Control scripts: ollama_start.sh, ollama_stop.sh → ~/scripts/ollama/
STEP 4/6  ~/.ollama_user_config (inference parameters — only if it does not exist, never overwrites an edited one)
STEP 5/6  Aliases in .bashrc (ollama-start/-stop/-status/-list/-run/-pull, export OLLAMA_VULKAN=1)
STEP 6/6  Registry
```

**GPU branch** (`termux_npm`):
(a) detects the vendor via `getprop ro.board.platform` (adreno → `mesa-vulkan-icd-freedreno`; mali/xclipse → `mesa virglrenderer-android`; other → only the generic loader) (`:344-378`);
(b) `npm install -g` does not abort if it fails: it only `warn`s and continues (`:411-412`) because the upstream `install.js` asks for a `.sha256` per file that does not exist in the Releases;
(c) a loop of up to 3 `ollama-termux` attempts validating with `ollama --version` (`:444-456`);
(d) **direct-download fallback** of the Release tarball + verification against the combined `SHA256SUMS`, official repo first and the `Honkonx/ollama-termux` fork as backup (`:478-568`);
(e) if nothing leaves a working binary → `error` (does not mark the checkpoint) (`:570-579`).

Dependencies: tmux/curl/wget (STEP 1, `:259-293`), nodejs-lts (GPU only, with `retry_cmd`).

Verification: `verify_binary_installed ollama` in the "already installed" check (`:214`) and `ollama_binary_works()` in the GPU branch (`:438`). The `standard` branch does not use real verification after `pkg install` (only `|| error` by exit code, `:309-313`), although STEP 6 does read `ollama --version` for the registry (`:832`).

Registry (`update_registry`, `:165-169`): `ollama.installed/version/install_date` (via `registry_install`), `install_mode`, `commands`, `port=11434`, `location=termux_native`. Notification to the app: `notify_event "ollama" "install_done"` (`:861`).

### Resolved behaviors (avoid reintroducing them)

- **`npm install` does NOT leave the real binary working by itself** — according to the README of `DioNanos/ollama-termux` (the project behind the npm package), the install only leaves the CLI wrapper; the real binary (`bin/ollama` + runtime) is downloaded from a GitHub Release and verified by SHA256 only the FIRST time `ollama-termux` runs. Without that step, `ollama` never appears on PATH no matter how many times it is reinstalled.
- **`EEXIST` on partial reinstalls** — `DioNanos/ollama-termux`'s `install.js` writes the wrapper with Node's exclusive flag (`"wx"` — fails if the file already exists). If a previous attempt was left half-done (network cut while downloading the Release), any retry of `ollama-termux` blows up with `EEXIST` without re-downloading the real binary — and `command -v ollama` still reports "found" (the wrapper exists and has `+x`) even though the real binary is missing/corrupt. That is why real verification uses `ollama --version`, not `command -v`.
- **Safe bind by default** — `ollama_start.sh` reads `OLLAMA_LAN` from `~/.ollama_user_config` at runtime — default `127.0.0.1` (this phone only), `0.0.0.0` only if the user turns on the "Listen on LAN" toggle from `OllamaConfigFragment`.
- **No fragile `send-keys`** — the whole command (including `OLLAMA_VULKAN=1` if the variant is GPU, read from the registry at runtime) is passed DIRECTLY to `tmux new-session`, instead of creating an empty session and "typing" the command into it afterwards.
- **Persistent log** — `ollama serve` output is redirected to `~/kairos_logs/ollama_serve.log`.

## 4. Execution and state detection

- Generated script `~/scripts/ollama/ollama_start.sh` (`:611-723`): tmux session `ollama-server`, idempotent; bind `127.0.0.1` by default, `0.0.0.0` if `OLLAMA_LAN=1`; exports `OLLAMA_KEEP_ALIVE`/`OLLAMA_NUM_PARALLEL`/`OLLAMA_MAX_LOADED_MODELS` only if ≠ empty/0; `OLLAMA_VULKAN=1` only if `install_mode=termux_npm`; log `~/kairos_logs/ollama_serve.log`. After starting (GPU) it waits 3 s and writes `~/.ollama_backend_status` (`gpu|cpu|unknown`) by looking for `library=` in the log (`:713-722`).
- `ModuleController.kt`: `getTmuxSession("ollama")` → `ollama-server` (`:1199`) — `isRunning()` checks `tmux has-session -t ollama-server`; `getModulePort("ollama")` → `11434` (`:1239`) — `waitForPortOpen()` polls TCP (up to 8s) to confirm a real start before reporting success to the UI; start `~/scripts/ollama/ollama_start.sh` (`:1162`); stop `ollama_stop.sh` (`:1184`); `stopAllModules` includes it (`:322`). Uninstall depends on `install_mode` (`:928-940`).
- `verificar.sh` (`_ver_cmd` strategy) validates `command -v ollama` live.

## 5. App screens

### `OllamaFragment.kt` (the module's main screen)

(`OllamaFragment.kt:12-22`) The main screen is compact: it has no STATUS card with RAM/Uptime nor "Restart"/"Update"/"GPU info"/"Uninstall" buttons (those live in `OllamaConfigFragment`).

- Compact `:11434 · vX` row + ⚙ + switch (`:36-56`). Switch → `startModuleServiceWithPolling`/`stopModuleService` (`:73-85`); polling every 2.5s keeps the screen from looking "stuck" while the model loads.
- Amber "⚠ Running on CPU" banner if `~/.ollama_backend_status` = `cpu` and mode `termux_npm` (`:123-155`): if the GPU variant fails to use Vulkan, Ollama silently falls back to CPU without telling the user.
- Models card with `modelRow` (`:157-237`): models loaded in memory (`OllamaApiClient.psModels()`, `GET /api/ps`) with "Unload" (`OllamaApiClient.unloadModel`, `/api/generate` with `keep_alive:0`; without it one could only wait for the `OLLAMA_KEEP_ALIVE` timeout or restart the WHOLE service), and downloaded models (`OllamaApiClient.listModels()`, `GET /api/tags`: name · size · family). `refreshModelsInfo()` runs both queries on a background `Thread` when the screen opens.
- "Download model" button → `ModelsFragment` (`:64`); 💬 FAB → `ChatFragment` (`:66`).

### `OllamaConfigFragment.kt` (configuration and maintenance)

6 parameters + system prompt (`:71-92`), Network/LAN (`:101-125`), Server: keep-alive, parallel, max models, **num_thread, num_gpu** (`:132-155`), Save/Restore (`:156-157`), History (RAM presets 2/4/6/8 + custom 1..20; disk 20/50/100/∞ + custom 10..500) with statistics and deletion (`:300-345`), Custom model (real Modelfile, `ollama create`, `:490-575`), Maintenance: Restart, Process detail (RAM/Uptime are shown as "—": there is no real source, note at `:197-199`), GPU info, Update (reinstalls with the variant from the registry, `:211-220`), Diagnose, Uninstall (`:168-181`).

It edits `~/.ollama_user_config` (**12 keys**, `OllamaApiClient.CONFIG_DEFAULTS` `:235-266`) via `OllamaApiClient.readConfig()`/`writeConfigValue()`/`resetConfig()` — without going through any intermediate script:

| Field | Default | Range/note |
|---|---|---|
| `OLLAMA_TEMP` | 0.7 | 0.0–2.0 (validated before saving) |
| `OLLAMA_TOP_P` | 0.9 | — |
| `OLLAMA_TOP_K` | 40 | — |
| `OLLAMA_REP_PENALTY` | 1.1 | — |
| `OLLAMA_NUM_CTX` | 2048 | context tokens |
| `OLLAMA_NUM_PREDICT` | 2048 | max response tokens |
| `OLLAMA_SYSTEM_PROMPT` | (long Spanish text, see the generated `~/.ollama_user_config`) | multiline |
| `OLLAMA_LAN` | `0` | "Listen on LAN" toggle — `1` = `0.0.0.0`, `0` = `127.0.0.1`. It saves by itself (no separate "Save" button), applying only the next time the service starts |
| `OLLAMA_KEEP_ALIVE`, `OLLAMA_NUM_PARALLEL`, `OLLAMA_MAX_LOADED_MODELS` | — | Server |
| `OLLAMA_NUM_THREAD`, `OLLAMA_NUM_GPU` | — | Only in Kotlin's `CONFIG_DEFAULTS` (not in `ollama.sh`'s default config); the UI applies the default |

### `ModelsFragment.kt`

- CATALOG card: 6 curated, pre-loaded models (tags confirmed against `ollama.com/library`) — `qwen2.5:0.5b/1.5b/3b`, `gemma2:2b`, `llama3.2:1b/3b`. Each row shows "✓ Installed" (if it is already in `OllamaApiClient.listModels()`) or "Download" (triggers `pullModel()` directly, without typing the name by hand).
- INSTALLED MODELS card: real list via `OllamaApiClient.listModels()` (direct HTTP to `:11434`, no intermediate script) — tapping a model opens its detail (parameters, family) with a "Delete" option.
- "+ Download model (advanced)": a free-text dialog for any tag not listed in the catalog.
- Download with real live progress (%, speed, ETA) via `OllamaApiClient.pullModel()` with streaming (`"stream": true`).

### Main-screen and maintenance controls

| Control | What it does | Notes |
|---|---|---|
| "Ollama" switch (start/stop) | `startModuleServiceWithPolling()`/`stopModuleService()` | Polling every 2.5s while the model loads |
| "⚠ Running on CPU" banner (conditional) | Reads `~/.ollama_backend_status` (marker left by `ollama_start.sh`) | Warns that the GPU variant fell back to CPU |
| ACTIVE MODEL card | Read-only, `/api/ps` (`OllamaApiClient.psModels()`) | — |
| "Unload" (per active model) | `/api/generate` with `keep_alive:0` | Unloads the model from memory without restarting the service |
| DOWNLOADED MODELS card | Read-only, `/api/tags` | The same data as `ModelsFragment` |
| "＋ Download model" | Navigates to `ModelsFragment` | — |
| 💬 FAB | Navigates to `ChatFragment` | Direct shortcut to the chat |
| "⚙ Inference parameters" | Navigates to `OllamaConfigFragment` | Separates configuration from the main screen |
| "↻ Restart service" | `stopModuleService()` → `startModuleService()` chained | One-tap action |
| "🎮 GPU / Vulkan info" | Runs `vulkaninfo`, reads `/proc/cpuinfo` and `~/.bashrc` (looks for `export OLLAMA_VULKAN=1`) | Shows the detected Vulkan device (`deviceName`), whether `OLLAMA_VULKAN=1` is exported, and relevant CPU features (`i8mm`/`dotprod`/`sve`) |
| "🔄 Update Ollama" | `ModuleController.installModule()` with `--force` and the variant stored in `ollama.install_mode` | `ollama.sh` requires `--variant` in silent mode: the original variant is reused, the generic helper cannot be used |
| "🗑 Uninstall" | `confirmUninstallModule()` | — |

## 6. Files and paths it creates

`$PREFIX/bin/ollama` (pkg or wrapper), `$PREFIX/lib/ollama/**` (runtime ~120 MB, GPU only), `~/scripts/ollama/ollama_start.sh|ollama_stop.sh`, `~/.ollama_user_config`, `~/.ollama_backend_status`, `~/kairos_logs/ollama_serve.log`, `~/.ollama/models` (models), `~/.install_ollama_checkpoint` (deleted at the end), entries in `~/.bashrc` (STEP 5, `:801-825`) and `~/.android_server_registry`.

## 7. Registry (`~/.android_server_registry`)

```
ollama.installed=true
ollama.version=<real version from "ollama --version">
ollama.install_date=YYYY-MM-DD
ollama.install_mode=termux_npm|standard
ollama.commands=ollama serve,ollama run,ollama list,ollama pull,ollama rm
ollama.port=11434
ollama.location=termux_native
```

## 8. Use as an AI backend for ANY module/purpose

Ollama is **not only the app's Chat**: it is a reusable local HTTP service. Any Kairos module, CLI, agent or custom script that accepts an "openai-compatible" provider (or Ollama's REST API) can point it to `http://127.0.0.1:11434`. Pattern already used in the app:

| Consumer | How it connects | Code |
|---|---|---|
| **ChatFragment** ("ollama" engine) | `OllamaApiClient` → `POST /api/chat` with `messages` + `options` + `images` (base64) | `util/OllamaApiClient.kt`, `ChatFragment.makeOllamaRequest()` |
| **OpenCode** | Provider `@ai-sdk/openai-compatible` with `baseURL: http://127.0.0.1:11434/v1` | `OpenCodeNative.ollamaConfig()` |
| **Hermes** | OpenAI-compatible local provider (lists models via `/api/tags`) | `HermesNative.kt`, `HermesFragment` |
| **AI CLIs** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:11434/v1", model)` | `LocalCliProviderNative.kt`, `GenericModuleFragment.useLocalServer` |
| **cactus** | `/ai` → reasoner via Ollama (or llama-server) | `ChatFragment.dispatchCactusRun()` |
| **Any custom bash script** | `curl http://127.0.0.1:11434/api/chat -d '{...}'` or `--base-url http://127.0.0.1:11434/v1` in compatible CLIs | — |

**Key endpoints (native REST API):**
- `GET  /api/tags` — installed models.
- `POST /api/chat` — chat (recommended: keeps `messages`, supports `images`).
- `POST /api/generate` — simple generation.
- `POST /api/pull` — download a model (progress streaming).
- `DELETE /api/delete` — delete a model.
- `POST /api/show` — model metadata (family, parameters, image capability).
- `POST /api/embeddings` — embeddings (for RAG/vectors).
- `GET  /api/ps` — models currently loaded in memory/VRAM (`OllamaApiClient.psModels()`).

The real API's `/api/embeddings`, `/api/push` and `/api/copy` endpoints have no UI of their own; they are of low value for Kairos's use case (local consumption, not model publishing).

**OpenAI compatibility:** `POST /v1/chat/completions` and `GET /v1/models` — the bridge that lets Ollama be used as a backend for OpenCode, Hermes, or any tool that speaks `openai-compatible`.

**Web Search (internet for models):** Chat's "Web: ON/OFF" toggle uses **Ollama's Web Search API** (`POST https://ollama.com/api/web_search`, free Bearer key from `ollama.com/settings/keys`, max 5 results, 10s timeout) as a **shared service** for the local engines (ollama AND llama-server) and cloud — results are injected as prompt context (`augmentPromptWithWebSearch`). It does not require the Ollama model to be connected to the internet: the context is added by Chat before the request. Key stored in `kairos_llm_prefs` (`ollama_web_api_key`), toggle `web_search_enabled`.

## 9. Reference commands

```bash
ollama-start          # alias -> ollama_start.sh (tmux, log in ~/kairos_logs/ollama_serve.log)
ollama-stop           # alias -> ollama_stop.sh
ollama-status         # curl -s http://localhost:11434
ollama list           # downloaded models
ollama pull <model>   # download
ollama run <model>    # direct CLI chat (outside the app)
ollama rm <model>     # delete
```

## 10. Difference from embedded llama.cpp / llama-server

See the table in section 1 and `llama-server.en.md`. In short: Ollama is an **external multi-model HTTP service** (Termux process, REST API + OpenAI-compatible); llama.cpp has two pieces — the **embedded JNI engine** (`LlamaEngine`, in-process, no port) and the **`llama-server` server** (`:8085`, a single `.gguf` model, OpenAI-compatible). `ChatFragment` treats them as separate engines with separate model selectors, but they share the Web Search service.

## 11. Security and known limitations

- Loopback bind by default (`:641-642`); `ollama_start.sh` does NOT `source` the config (comment `:631-638`) and extracts values with `grep|cut|tr`.
- The values extracted from `OLLAMA_KEEP_ALIVE`, `OLLAMA_NUM_PARALLEL` and `OLLAMA_MAX_LOADED_MODELS` are interpolated **unvalidated** into the string passed to `tmux new-session` (`:653-663`, `:691-692`), which runs it with a shell. `tr -d` only removes quotes/spaces/CR, not `;` or `$()`. Today only the user themselves (or the UI) writes that file, and the UI validates numeric fields but **Keep-alive is free text** (`OllamaConfigFragment.kt:625-`): a low-severity local injection vector (severity untested).
- `OLLAMA_LAN=1` exposes the API without authentication (Ollama has no native auth) — the toggle carries warning text (`strings_ollama.xml:16`).
- `export OLLAMA_VULKAN=1` is written to `~/.bashrc` for **all** variants (`ollama.sh:813`, unconditional), even though `standard` is CPU-only; the start script does condition it properly (`:675`). Harmless but imprecise; also the `ollama-lan` alias (`:820`) binds `0.0.0.0` without going through the toggle.
- Secrets: it handles no credentials of its own (the Web Search API key lives in `kairos_llm_prefs`, outside this module).
- The `standard` branch has no real binary verification after `pkg install` (see §3).
- Unverified on a device: the direct-download fallback and the CPU banner (documented as ADB-tested in the script's comments, not re-tested).

## 12. Added

**2026-07-19** (first commit of `modulos/ollama.sh`).
