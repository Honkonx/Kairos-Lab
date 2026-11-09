# Hermes Agent — Kairos Module

**Kairos Module** — This module is managed from the Kairos UI (Modules tab). Installation, gateway start/stop and status checks are handled by the app via ProcessBuilder → bash scripts and Kotlin code.

---

**Project:** Kairos
**Upstream agent:** `NousResearch/hermes-agent` (MIT, Nous Research). The agent version depends on the `main` cloned at each installation (the script comment cites v0.21.0; earlier versions used v0.16.0)
**Script:** `modulos/hermes.sh` (v2.1.0, 609 lines; identical to `app/src/main/assets/scripts/hermes.sh`)
**UI:** `HermesFragment.kt` (463 lines), `HermesGatewayFragment.kt` (102 lines), `HermesNative.kt` (241 lines)
**Date added:** 2026-07-19

---

## 1. Overview

Hermes Agent is an open-source (MIT) AI agent framework developed by Nous Research. Unlike similar tools such as OpenClaw or OpenCode, Hermes runs **natively in Termux** (it never had a proot mode) without Debian containers, without Node.js in proot, and without root.

Its architecture is Python-based, with a virtual environment (`venv`) installed at `~/.hermes/hermes-agent/venv/` (`pip install -e`), with a shim at `$PREFIX/bin/hermes`. It exposes an interactive TUI/REPL/chat interface, a multichannel messaging gateway (Telegram, Discord, Slack, SMS, Signal; `hermes gateway`) with tokens configured in `~/.hermes/.env`, and an optional OpenAI-compatible API server on `:8642` (neither `modules.json` nor Kairos code uses it).

Within the stack, Hermes is classified alongside n8n and OpenClaw, since its main function is to act as a communication gateway to messaging platforms.

**Classification: FUNCTIONAL MODULE** (of mixed nature: many of its options just open the terminal). It has its own screen (`HermesFragment`, `ModuleDetailNavigator.kt:28`) and a service sub-screen (`HermesGatewayFragment`, reached via `navigateTo(HermesGatewayFragment())`, `HermesFragment.kt:110`), live state (version/gateway/provider), a real gateway start/stop switch, real config writing (`.env`, `hermes config set`) and project management. `modules.json` declares it `hasSwitch:false`, `port:""`, with no `tmuxSession`, and `ModuleController.kt` has no `hermes` entry; the gateway lifecycle is handled by `HermesNative` on its own. ~24 buttons: `HermesFragment` ~21 and `HermesGatewayFragment` 3 (switch, status, logs). Of those, ~13 are a plain `launchTerminalCommand("hermes …")`; ~6 have a native operation of their own (gateway switch/stop, quick provider, local provider, status, Engram, projects).

---

## 2. Architecture in the Stack

```
Kairos
│
├── Services
│     ├── n8n          :5678  proot Debian   — workflow automation
│     ├── OpenClaw     :18789 proot Debian   — multi-provider AI gateway
│     └── Hermes Agent        NATIVE Termux  — AI agent + messaging gateway
│
├── Code Tools
│     ├── Claude Code         NATIVE Termux
│     └── OpenCode     :3000  proot Debian
│
├── Ollama             :11434 NATIVE Termux  — local AI models
└── ...
```

### Key differences vs OpenClaw

| Aspect | OpenClaw | Hermes |
|---|---|---|
| Runtime | proot Debian + Node.js | Native Termux Python |
| Process | Permanent HTTP daemon | Interactive TUI + optional gateway |
| Port | Fixed `:18789` | Optional gateway `:8642` / not exposed by default |
| Status check | `curl :18789` | `pgrep -f hermes` + `tmux has-session -t hermes-gw` |
| Telegram | Integration via n8n | Direct native integration |
| Installation | proot + npm | pip in a native Termux venv |

---

## 3. Paths and File Structure

```
~/.hermes/                          # Data root directory (HERMES_HOME configurable via environment)
├── config.yaml                     # Main configuration (provider, model, agent)
├── .env                            # API keys and tokens (chmod 600)
├── SOUL.md                         # Agent personality (loaded on every message)
├── hermes-agent/                   # Source code (git clone)
│   ├── venv/                       # Python virtual environment
│   │   └── bin/hermes              # Real agent binary
│   ├── constraints-termux.txt      # pip constraints for Termux/Android
│   └── scripts/
│       └── install_psutil_android.py  # ARM64 psutil patch
├── sessions/                       # Session history by ID
├── memories/                       # Agent persistent memory
├── skills/                         # Loaded skills (built-in + custom, e.g. custom/engram-memory/SKILL.md)
├── logs/                           # Gateway logs
│   └── gateway.log
├── cron/                           # Scheduled tasks
├── hooks/                          # Event hooks
├── pairing/
├── image_cache/                    # Processed-image cache
└── audio_cache/                    # Audio cache (TTS/STT)

$PREFIX/bin/hermes                  # Launcher shim (chmod +x)
~/.install_hermes_checkpoint        # hermes_<step>=done keys
```

### Launcher shim (`$PREFIX/bin/hermes`)

The installer does not create a direct symlink but a **bash shim** that clears inherited environment variables that break the venv:

```bash
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/hermes" "$@"
```

This is critical in Termux because nested sessions (tmux, proot) may inherit `PYTHONPATH` from other Python installations in the stack, making Hermes import the wrong modules.

---

## 4. Main Configuration — `~/.hermes/config.yaml`

### Correct structure for Hermes v0.16+

Hermes v0.16 requires the model configuration as a **nested YAML block**. The flat format (`model: ollama/name`) of earlier versions is no longer valid.

```yaml
# Cloud provider (OpenRouter, Anthropic, Gemini, etc.)
model:
  provider: openrouter
  default: google/gemini-flash-1.5

# Local Ollama provider
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b
  ollama_num_ctx: 65536
  context_length: 65536
```

> **Common error:** If `model: ollama/name` (a single line) is written, Hermes v0.16 ignores it and uses the default provider, producing the error `No models provided` against OpenRouter.

### Environment variables — `~/.hermes/.env`

```bash
# ── Telegram ───────────────────────────────
TELEGRAM_BOT_TOKEN=<BotFather-token>
TELEGRAM_ALLOWED_USERS=<your-numeric-Telegram-ID>
TELEGRAM_HOME_CHANNEL=<your-numeric-Telegram-ID>

# ── AI providers ────────────────────────────
OPENROUTER_API_KEY=sk-or-v1-...
GOOGLE_API_KEY=AIza...
# ANTHROPIC_API_KEY=sk-ant-...

# ── Gateway API server (optional) ───────────
# API_SERVER_ENABLED=true
# API_SERVER_KEY=your-secret-key
# API_SERVER_PORT=8642

# ── Home Assistant (optional) ───────────────
# HASS_TOKEN=...
# HASS_URL=http://homeassistant.local:8123
```

The file has `600` permissions — only the owning user can read it (it is created that way in the script, `:554`, and in `configSetProvider`).

---

## 5. Installation (`modulos/hermes.sh`, v2.1.0)

The process adapts Nous Research's official installer to the constraints of Android 15 + Termux ARM64. Flags (`:71-74`): `--silent`, `--force`, `--describe`, `--describe-files`; `--describe` declares `variants: []`. No variants. Global `set -e` (`:55`); it disables `PYTHONPATH/PYTHONHOME`, `UV_NO_CONFIG=1`.

### Differences vs the official installer

| Aspect | Official | Kairos |
|---|---|---|
| Python package manager | `uv` | Direct `pip` (uv is not available in Termux) |
| Temporary directories | `mktemp` → `/tmp/` | Avoided — noexec on Android 15 |
| Interactive prompts | Free `read -r -p` | Always `read -r ... < /dev/tty` |
| Failure recovery | No checkpoint | Per-step checkpoint in `~/.install_hermes_checkpoint` |
| System integration | No registration | Writes to `~/.android_server_registry` |
| Wizard at the end | Automatic | Does not launch it (header `:25-26`: the app handles it afterward); the user opens it with the "Full wizard (hermes setup)" button |
| Post-install gateway | Offers to install as a service | Omitted — managed from the app |

### Installer steps (with checkpoint)

```
STEP 1/6  System packages (:235-282)
          pkg install python git clang rust make pkg-config
                      libffi openssl ca-certificates curl ripgrep ffmpeg nodejs-lts
          (if it fails, it only warns and does NOT mark the checkpoint: it does not abort)

STEP 2/6  Clone repository (:284-307)
          git clone --depth 1 --branch main (SSH with BatchMode first, HTTPS fallback)
          → ~/.hermes/hermes-agent/   (no commit/tag pinned)

STEP 3/6  Python virtual environment (:309-327)
          python -m venv ~/.hermes/hermes-agent/venv

STEP 3.5  Prebuilt wheel (:329-349)
          tries to download firecrawl-anydoc==0.2.4 from Releases of
          Honkonx/kairos-lab before pip install (if it does not exist, it builds from source)

STEP 4/6  Python dependencies (3 fallback levels, all with
          --ignore-requires-python -c constraints-termux.txt)
          pip install -e '.[termux-all]'   ← attempt 1
          pip install -e '.[termux]'       ← fallback 2
          pip install -e '.'              ← fallback 3
          * system python-psutil / python-cryptography + a cryptography symlink into the venv
          * psutil prebuilt with the Android patch (install_psutil_android.py) before pip

STEP 5/6  Shim at $PREFIX/bin/hermes (:492-530)
          Protects PYTHONPATH/PYTHONHOME; hard-fails if `hermes --version` does not respond

STEP 6/6  Configuration files (:543-590)
          ~/.hermes/.env (copied from .env.example, chmod 600) · config.yaml · SOUL.md
          Skills sync via tools/skills_sync.py
          registry_install hermes <ver> install_dir model="no configurado" location=termux_native
```

- "Already installed" shortcut: `command -v hermes` and `$INSTALL_DIR` exist without `--force` → exits (`:196-199`) WITHOUT rewriting the registry; if the registry were left false with a healthy binary, it is not repaired.
- `--force` deletes `INSTALL_DIR` and the shim (`:201-207`), not `.env` or `config.yaml`.
- If the version cannot be obtained it falls back to `"unknown"` and registers anyway (`:588`).
- Prebuilt-wheel integrity check: only if `HERMES_FIRECRAWL_PREBUILT_SHA256` exists (a variable that is not defined), so `verify_sha256` only logs the hash and returns 0 without pinning (`lib.sh:595-606`). The script itself acknowledges that a Release with that asset may not yet exist (`:46-52`).
- `modules.json`: `requires: [python3, nodejs]`, `installMethods: [npm, venv]`, `arch: bionic`, `catalogVersion "0.x"`, `size ~90MB`, `estimate ~1 min` (the real install compiles/downloads considerably more; the time is only a label).

### Critical environment variables for ARM64

```bash
ANDROID_API_LEVEL=35          # Required for Rust/maturin wheels (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Prevents uv from inheriting a broken configuration
```

---

## 6. Integration with the app

### 6.1 Module components

| Component | Function |
|---|---|
| `modulos/hermes.sh` | Only INSTALLS (6 steps + STEP 3.5); it has no gateway start/stop logic (its own `--describe` says "sin gateway/tmux implementado todavia", `modulos/hermes.sh:81`) |
| `app/src/main/java/com/termux/app/util/HermesNative.kt` | Kotlin logic: `info()` (registry + `.env` + `pgrep`), `gatewayStart()`, `gatewayStop()`, `configSetProvider`, `configSetLocalProvider`, `ollamaModels`, `installEngramSkill` |
| `HermesFragment.kt` + `HermesGatewayFragment.kt` | The module's real UI (see 6.3); `ModulesFragment.kt` is only the generic list |

### 6.2 State detection

"Running" state = `pgrep -f` (`ManagerNativeUtils.pgrepF`, `:50-51`) over `hermes.*gateway`, or `tmux has-session -t hermes-gw`. Possible states: not installed, stopped, running (gateway active in tmux `hermes-gw`). The STATUS card is populated by `HermesNative.info()`: version, gateway, provider (`AI_PROVIDER`/`HERMES_PROVIDER` from `.env`). Inference: a `pgrep` launched from the app process may not see processes of the terminal session tree under `ptrace_scope=1`; since the tmux is launched by the app itself it may be visible, but this is not confirmed, and there is a risk of a false negative in `running`.

### 6.3 App screen

The header always shows the real provider/model read from `~/.hermes/config.yaml` (not the registry value, which can be outdated).

**HermesFragment (main screen):**

| Control | What it does | Why |
|---|---|---|
| "🤖 Open Hermes (TUI)" | `hermes --tui` (with folder selector; NOT bare `hermes`) | The binary without flags opens the classic REPL (prompt_toolkit) — a different mode |
| "💬 Chat (hermes chat)" | `hermes chat` | A distinct 3rd entry point of the same binary (REPL/TUI/chat), not duplicated |
| "📡 Gateway" | Navigates to `HermesGatewayFragment` | — |
| "🗂 Manage projects" | `showProjectsMenu()` | — |
| "📖 Commands — reference" | Dialog with the full list of commands and what each one does | — |
| "🔀 Choose model/provider (hermes model)" | `launchTerminalCommand("hermes model")` | A selector that is 100% interactive over TTY (arrow keys), with no documented `--list`/`--json` flag — no parsing is invented that the binary does not support |
| "🔌 Configure AI provider (quick, no terminal)" | Dialog with provider + API key → `configSetProvider` writes `AI_PROVIDER` and `<PROVIDER>_API_KEY` straight into `~/.hermes/.env` (`chmod 600`, `upsertKeyValueLine`, no shell) | Faster than the real wizard, but without its validation/`config.yaml` format. Fields start empty and the stored key is never shown again (`HermesFragment.kt:339-366`) |
| "Local AI provider" dropdown (local Ollama / local llama-server) + "🔌 Configure local AI provider" | Chooses which local backend Hermes uses; the button dispatches to `useOllamaLocal()`/`useLlamaServerLocal()`. `configSetLocalProvider` makes 3 calls `hermes config set model.provider\|base_url\|default` (`HermesNative.kt:136-`); lists Ollama models (`ollamaModels`, HTTP client) or the GGUFs from `LocalModelManager` | An exclusive choice with no ON/OFF attached — fits `dropdownRow()` |
| "📤 Send message (hermes send)" | Dialog with message → `hermes send` in the terminal | Sends as-is to the configured platform (Telegram/Discord/SMS) — it does **not** go through the LLM |
| "🤖 Direct prompt to the agent (hermes -z)" | Dialog with prompt → `hermes -z` in the terminal | Different from the one above: it does go through the LLM and returns a response, without opening the TUI |
| "⏹ Stop gateway" | `HermesNative.gatewayStop()` | — |
| "🔍 Status and diagnostics" | Dialog with installed/version/gateway/config/provider | — |
| "⚙ Full wizard (hermes setup)" | `launchTerminalCommand("hermes setup")` | — |
| "🩺 Diagnostics (hermes doctor)" | Terminal — free-text output, not JSON | It makes no sense to wrap it in a native dialog |
| "📋 Task board (kanban)" | `launchTerminalCommand("hermes kanban")` | — |
| "⏰ Scheduled tasks (hermes cron)" | `launchTerminalCommand("hermes cron")` | A subcommand documented in the official command reference ("Scheduled job management") |
| "↑ Update Hermes (hermes update)" | Lightweight path, only falls back to reinstalling if it fails | Different from "Install / reinstall" |
| "↑ Install / reinstall" | `reinstallModuleService()` | — |
| "🔗 Connect Engram memory (skill)" | `HermesNative.installEngramSkill()` — writes `~/.hermes/skills/custom/engram-memory/SKILL.md` | Hermes does not use an MCP slug like others — it has a Skills system that the agent loads only when applicable |
| "🗑 Uninstall" (MAINTENANCE card) | `confirmUninstallModule()` | It does not duplicate the "Update"/"Install/reinstall" above |

"Send message" and "Direct prompt" open a terminal with the command instead of showing the response in the UI; messages are escaped with `shellQuote` (`ProcessBuilderExt.kt:254`). `view.filterTouchesWhenObscured = true` (`HermesFragment.kt:31-34`).

**HermesGatewayFragment ("Gateway"):**

| Control | What it does | Why |
|---|---|---|
| "Gateway" switch | `hermes gateway` in tmux (`hermes-gw`) / stops it | Replaces separate Start/Stop buttons. Termux is "Tier 2, best-effort" in Hermes' official docs — not tested on a real device |
| "View status" | Dialog with running/provider/saved config | — |
| "View logs" | `tmux attach -t hermes-gw` | No known static log path — it attaches to the live session |

### 6.4 Gateway control

The gateway runs in a `tmux` session called `hermes-gw`. The command Kairos launches is `hermes gateway` (without `run`): `HermesNative.kt` `gatewayStart()` runs `tmux new-session -d -s hermes-gw "hermes gateway"` and waits a fixed 2 s (`Thread.sleep(2000)`) before checking with `pgrep -f "hermes.*gateway"` or `tmux has-session`; `gatewayStop()` kills the session and does `pkill -f "hermes.*gateway"`. It is not verified that the gateway really connects.

```bash
# Start (what the app uses)
tmux new-session -d -s "hermes-gw" "hermes gateway"
# (`hermes gateway run` is the foreground mode for manual terminal use)

# View logs
tmux attach-session -t "hermes-gw"   # Ctrl+B D to exit without killing

# Stop
tmux kill-session -t "hermes-gw"
pkill -f "hermes gateway"
```

### 6.5 Module commands — executable reference

| Option | Command | Description |
|---|---|---|
| [1] | `hermes` | Main interactive TUI |
| [2] | `hermes chat` | Interactive chat |
| [3] | `hermes model` | Change provider/model |
| [4] | `hermes setup` | Full wizard |
| [5] | `hermes status` | Agent status + auth |
| [6] | `hermes doctor` | System diagnostics |
| [7] | `hermes version` | Installed version |
| [8] | `hermes send` | One-shot send to Telegram/Discord/SMS |
| [9] | `hermes kanban` | Task board |

### 6.6 Configure local Ollama

For Hermes v0.16+ the correct YAML structure is:

```yaml
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b
  ollama_num_ctx: 65536
  context_length: 65536
```

It is recommended to check the model's context window with `curl http://127.0.0.1:11434/api/show` — Hermes requires at least 64k tokens for reliable tool calling.

### 6.7 Registry — Hermes entries

`modulos/hermes.sh` writes (via `registry_install`) `installed`, `version`, `install_date`, `install_dir`, `model=no configurado` and `location=termux_native` (`:590`):

```
hermes.installed=true
hermes.version=<version>
hermes.install_date=<YYYY-MM-DD>
hermes.install_dir=/data/data/com.termux/files/home/.hermes/hermes-agent
hermes.model=no configurado
hermes.location=termux_native
```

---

## 7. Telegram Integration

### Requirements

- A bot created via `@BotFather` on Telegram — it generates the `TELEGRAM_BOT_TOKEN`
- The user's numeric ID via `@userinfobot` — goes in `TELEGRAM_ALLOWED_USERS`
- Gateway running (`hermes gateway`)

### Configuration in `~/.hermes/.env`

```bash
TELEGRAM_BOT_TOKEN=<BotFather-token>
TELEGRAM_ALLOWED_USERS=<your-numeric-Telegram-ID>
TELEGRAM_HOME_CHANNEL=<your-numeric-Telegram-ID>
```

`TELEGRAM_ALLOWED_USERS` is the access-control list. Without this field configured, the gateway accepts the connection but silently drops all messages — the log shows `Channel directory built: 0 target(s)`.

### Connection mode

Hermes uses **long polling** (not a webhook) — the gateway makes periodic requests to the Telegram API. It needs no public URL, no cloudflared, and works with any internet connection.

### Slash commands available on Telegram

| Command | Function |
|---|---|
| `/help` | Show available commands |
| `/new` | New session (clears history) |
| `/status` | View current session status |
| `/sessions` | Browse previous sessions |
| `/model` | Change the model for this session |
| `/stop` | Stop background processes |
| `/update` | Update Hermes from the bot |
| `/commands` | View all commands (paginated) |

---

## 8. Local Ollama with Hermes

### Critical requirement

Hermes requires a model with a **minimum of 64,000 context tokens** for reliable tool calling. Models with a smaller window are rejected on startup with the message:

```
Ollama loaded <model> with only 32,768 tokens of runtime context,
but Hermes needs at least 64,000 tokens for reliable tool use.
```

### Compatible models per device

| Model | Base RAM | Context | 11GB device | 16GB device |
|---|---|---|---|---|
| `qwen2.5:7b` | ~5 GB | 128k native | ⚠️ tight | ✅ |
| `qwen2.5:14b` | ~10 GB | 128k native | ❌ | ✅ |
| `llama3.1:8b` | ~6 GB | 128k native | ⚠️ tight | ✅ |
| `deepseek-r1:7b` | ~5 GB | 64k native | ⚠️ tight | ✅ |
| `qwen2.5:3b` | ~2 GB | 32k native | ⚠️ with Modelfile | ✅ |
| `qwen2.5:0.5b` | ~400 MB | 32k | ❌ insuff. ctx | ❌ |
| `moondream:1.8b` | ~1.5 GB | 2k | ❌ insuff. ctx | ❌ |

### Configure the context window via Modelfile

When the model loads with insufficient context by default, create a Modelfile that forces `num_ctx`:

```bash
cat > ~/qwen25_7b_65k.modelfile << 'EOF'
FROM qwen2.5:7b
PARAMETER num_ctx 65536
EOF

ollama create qwen2.5:7b-65k -f ~/qwen25_7b_65k.modelfile
```

Then in `~/.hermes/config.yaml`:

```yaml
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b-65k
  ollama_num_ctx: 65536
  context_length: 65536
```

### Limitation on 11 GB devices

With the whole stack running (n8n, Ollama, Hermes gateway), the RAM available to the model is ~3-4 GB. This makes `num_ctx: 131072` cause a segfault of the `llama-server` process on ARM64. The safe value is `65536` (the required minimum).

For 16 GB RAM devices, `qwen2.5:14b` with `num_ctx: 65536` is the recommended option — enough context, higher quality, more precise tool calling.

---

## 9. Quick Reference Commands

```bash
# Status
hermes version
hermes status
hermes doctor

# Use
hermes                          # Interactive TUI
hermes chat                     # Interactive chat
hermes -z "reply only OK"       # Non-interactive one-shot (test)

# Configuration
hermes model                    # Provider/model wizard
hermes setup                    # Full wizard
hermes config set model.provider custom
hermes config set model.base_url http://127.0.0.1:11434/v1
hermes config set model.default qwen2.5:7b-65k

# Gateway
hermes gateway run              # Foreground (recommended for manual terminal use)
hermes gateway status
hermes gateway stop

# Update
hermes update

# Direct send (no agent, no LLM)
hermes send "message"           # Sends to the configured platform

# Maintenance
hermes kanban                   # Task board
hermes cron                     # Scheduled tasks
hermes migrate                  # Migrate config to the new format
```

---

## 10. Uninstallation

The module includes the option to uninstall Hermes Agent, which runs:

```bash
# Stop gateway
tmux kill-session -t "hermes-gw" 2>/dev/null
pkill -f "hermes gateway" 2>/dev/null

# Remove code (keeps config)
rm -rf ~/.hermes/hermes-agent/
rm -rf ~/.hermes/venv/
rm -f  $PREFIX/bin/hermes
rm -f  ~/.local/bin/hermes

# Clean registry
grep -v "^hermes\." ~/.android_server_registry > /tmp/reg.tmp
mv /tmp/reg.tmp ~/.android_server_registry
```

> `~/.hermes/config.yaml` and `~/.hermes/.env` are **intentionally kept** so API keys and Telegram tokens are not lost when reinstalling.

---

## 11. Security

- `~/.hermes/.env` stores API keys and platform tokens: it is created with `chmod 600`. `configSetModel` rewrites the `.env` with `writeText` without re-applying `chmod 600` (`HermesNative.kt`; it has no callers in the UI).
- `HermesNative.configGet()` would return the `.env` values as JSON (including keys) and `soulGet/soulSet` expose/write SOUL.md; none has calls from the UI, so today there is no leak, but they are latent surface (~60 lines of unused code; there is no SOUL.md or `config.yaml` editor in the UI).
- Supply chain: `git clone` of `main` with no pin or signature verification, `pip install -e` without hashes, a prebuilt wheel from `kairos-lab` without a pinned SHA. The gateway connects to Telegram/Discord/SMS/Signal with tokens from `.env`.
- Injection: user arguments to the terminal go through `shellQuote` (send/-z); `launchTerminalCommand("cd '$path' && hermes --tui")` (`:96,118`) interpolates the path between single quotes without escaping (inference: paths come from the selector/`ProjectsManager`, not free text).
- The agent runs tools with terminal access (skills, `hermes send`, cron): a risk inherent to an agent with tools, not mitigated by Kairos.

---

## 12. Known Problems and Solutions

| Problem | Cause | Solution |
|---|---|---|
| `No models provided` (HTTP 400) | `config.yaml` in flat format | Use the nested YAML structure (section 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` not configured | Add the numeric ID in `~/.hermes/.env` |
| llama-server segfault | `num_ctx` too high for the available RAM | Reduce to `65536`, stop other services |
| `API call failed: No models provided` | OpenRouter configured without a default model | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway does not start after reboot | tmux session does not persist without Termux:Boot | Start it manually from the app |
| `hermes: command not found` after installing | Shim in `$PREFIX/bin` not in the active PATH | `source ~/.bashrc` or open a new Termux session |

### Known limitations

- `modules.json` says `hasSwitch:false` and `port:""` although the screen has a gateway switch; the generic Modules list cannot show its status like others.
- There is no verification that the gateway really connects (it only waits 2 s and checks the process/tmux).
- The installer does not repair the registry in the "already installed" branch; STEP 1 continues even if it fails.
- `catalogVersion "0.x"` vs script v2.1.0: no coherent version source.
- Inference: installing `.[termux-all]` building Rust (firecrawl-anydoc, cryptography) on the device may fail or take long; the "~1 min" estimate in `modules.json` seems unrealistic; the prebuilt wheel may not exist.
- Inference: Termux is "Tier 2, best-effort" per the upstream docs; the gateway is not tested on a real device according to the comments in `HermesGatewayFragment.kt`.

---

## 13. Pending and Future Improvements

- Add `hermes gateway run` to Termux:Boot autostart.
- Evaluate Open WebUI as a web frontend for Hermes (requires resolving the Python 3.13 incompatibility).
- Test `qwen2.5:7b-65k` on a 16GB device to validate full local Ollama.
- Document Hermes + Home Assistant integration (`HASS_TOKEN` already in `.env`).
