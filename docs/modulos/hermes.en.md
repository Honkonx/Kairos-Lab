# Hermes Agent

**Kairos module** — Managed via the Kairos UI (Modules tab). Installation, start/stop, and status checks are handled by the app via ProcessBuilder → bash scripts.

---

**App:** Kairos (termux-app fork)
**Documented version:** Hermes Agent v0.16.0
**Original author:** Nous Research
**Status:** Production — Telegram gateway active

---

## 1. General Description

Hermes Agent is an open-source (MIT) AI agent framework developed by Nous Research. Unlike similar tools such as OpenClaw or OpenCode, Hermes runs **natively on Termux** without needing proot, without Debian containers, without Node.js inside proot, and without root.

Its architecture is based on Python with a virtual environment (`venv`) installed at `~/.hermes/hermes-agent/venv/`. It exposes an interactive TUI, a multi-channel messaging gateway (Telegram, Discord, Slack, SMS, Signal), and an optional OpenAI-compatible API server on `:8642`.

Within the stack, Hermes is classified under the **[1] Services** module alongside n8n and OpenClaw, since its main function is to act as a communication gateway to messaging platforms.

---

## 2. Architecture within the Stack

```
Kairos
│
├── [1] Services
│     ├── n8n          :5678  proot Debian   — workflow automation
│     ├── OpenClaw     :18789 proot Debian   — multi-provider AI gateway
│     └── Hermes Agent        NATIVE Termux  — AI agent + messaging gateway
│
├── [2] Code Tools
│     ├── Claude Code         NATIVE Termux
│     └── OpenCode     :3000  proot Debian
│
├── [3] Ollama         :11434 NATIVE Termux  — local AI models
└── ...
```

### Key differences vs OpenClaw

| Aspect | OpenClaw | Hermes |
|---|---|---|
| Runtime | proot Debian + Node.js | Native Termux Python |
| Process | Permanent HTTP daemon | Interactive TUI + optional gateway |
| Port | `:18789` fixed | Gateway `:8642` optional / not exposed by default |
| Status check | `curl :18789` | `pgrep -f hermes` + `tmux has-session -t hermes-gw` |
| Telegram | Integration via n8n | Direct native integration |
| Installation | proot + npm | pip in native Termux venv |

---

## 3. Paths and File Structure

```
~/.hermes/                          # Root data directory
├── config.yaml                     # Main configuration (provider, model, agent)
├── .env                             # API keys and tokens (chmod 600)
├── SOUL.md                         # Agent personality (loaded on every message)
├── hermes-agent/                   # Source code (git clone)
│   ├── venv/                       # Python virtual environment
│   │   └── bin/hermes              # Agent's real binary
│   ├── constraints-termux.txt      # pip constraints for Termux/Android
│   └── scripts/
│       └── install_psutil_android.py  # ARM64 psutil patch
├── sessions/                       # Per-ID session history
├── memories/                       # Agent's persistent memory
├── skills/                         # Loaded skills (built-in + custom)
├── logs/                           # Gateway logs
│   └── gateway.log
├── cron/                           # Scheduled tasks
├── hooks/                          # Event hooks
├── image_cache/                    # Processed-image cache
└── audio_cache/                    # Audio cache (TTS/STT)

$PREFIX/bin/hermes                  # Launcher shim (chmod +x)
```

### Launcher shim (`$PREFIX/bin/hermes`)

The installer doesn't create a direct symlink but rather a **bash shim** that clears inherited environment variables that break the venv:

```bash
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/hermes" "$@"
```

This is critical in Termux because nested sessions (tmux, proot) can inherit `PYTHONPATH` from other Python installations in the stack, causing Hermes to import the wrong modules.

---

## 4. Main Configuration — `~/.hermes/config.yaml`

### Correct structure for Hermes v0.16+

Hermes v0.16 requires model configuration in a **nested YAML block**. The flat format (`model: ollama/name`) from earlier versions is no longer valid.

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

> **Common mistake:** If you write `model: ollama/name` (a single line), Hermes v0.16 ignores it and uses the default provider, producing the error `No models provided` against OpenRouter.

### Environment variables — `~/.hermes/.env`

```bash
# ── Telegram ───────────────────────────────
TELEGRAM_BOT_TOKEN=<token-from-BotFather>
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

The file has `600` permissions — only the owning user can read it.

---

## 5. Installation

Installation is handled via Kairos. The process adapts Nous Research's official installer for Android + Termux ARM64 constraints:

### Differences vs the official installer

| Aspect | Official | Kairos |
|---|---|---|
| Python package manager | `uv` | Direct `pip` (uv not available on Termux) |
| Temp directories | `mktemp` → `/tmp/` | Avoided — noexec on Android |
| Interactive prompts | Free `read -r -p` | Always `read -r ... < /dev/tty` |
| Failure recovery | No checkpoint | Per-step checkpoint at `~/.install_hermes_checkpoint` |
| System integration | No registration | Writes to `~/.android_server_registry` |
| Wizard on finish | Automatic | Asks before launching |
| Post-install gateway | Offers to install as a service | Skipped — managed from the Module Manager |

### Installer steps (with checkpoints)

```
STEP 1/6  System packages
          pkg install python git clang rust make pkg-config
                      libffi openssl curl ripgrep ffmpeg nodejs

STEP 2/6  Clone repository
          git clone --depth 1 (SSH first, HTTPS fallback)
          → ~/.hermes/hermes-agent/

STEP 3/6  Python virtual environment
          python -m venv ~/.hermes/hermes-agent/venv

STEP 4/6  Python dependencies (3 fallback levels)
          pip install -e '.[termux-all]'   ← attempt 1
          pip install -e '.[termux]'       ← fallback 2
          pip install -e '.'              ← fallback 3
          * psutil precompiled with the Android patch before pip

STEP 5/6  Shim at $PREFIX/bin/hermes
          Protects PYTHONPATH/PYTHONHOME

STEP 6/6  Configuration files
          ~/.hermes/.env · config.yaml · SOUL.md
          Skills sync via tools/skills_sync.py

WIZARD    hermes setup (interactive via /dev/tty)
          → AI provider and API key selection
```

### Critical environment variables for ARM64

```bash
ANDROID_API_LEVEL=35          # Required for Rust/maturin wheels (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Prevents uv from inheriting broken configuration
```

---

## 6. Module Manager Integration

### 6.1 Module components

| Component | Function |
|---|---|
| `modulos/hermes.sh` | Wrapper script — status (`check_hermes()`), gateway start/stop, config |
| Kairos module registry | Entries for Hermes (version, model, status) |
| `HermesFragment.kt` / `HermesGatewayFragment.kt` | Hermes module UI with status and messaging-gateway controls |

### 6.2 State detection — `check_hermes()`

```bash
check_hermes() {
  command -v hermes &>/dev/null || { echo "not_installed||"; return; }
  local ver; ver=$(get_reg hermes version)
  [ -z "$ver" ] && \
    ver=$(hermes version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$ver" ] && ver="?"
  tmux has-session -t "hermes-gw" 2>/dev/null \
    && echo "running|${ver}|gw" \
    || echo "stopped|${ver}|"
}
```

Possible states: `not_installed`, `stopped`, `running` (gateway active in tmux `hermes-gw`).

### 6.3 Module in the Module Manager

The Hermes module in Kairos shows the current status and the following options:

| Option | Description |
|---|---|
| Open Hermes (TUI) | Launches Hermes's interactive TUI |
| Gateway (Telegram/Discord/SMS) | Starts/stops the messaging gateway in tmux |
| Commands — executable reference | List of runnable Hermes commands |
| Configure AI provider | Change AI provider/model |
| Local AI provider (dropdown) + Configure | Dropdown ("Local Ollama" / "Local llama-server") + a "Configure local AI provider" button that dispatches to `useOllamaLocal()`/`useLlamaServerLocal()` depending on the chosen option |
| Status and diagnostics | View agent status and run diagnostics |
| Full wizard (hermes setup) | Run the interactive configuration wizard |
| Update Hermes | Update to the latest version |
| Install / reinstall | Install or reinstall Hermes |

The module header always shows the real provider/model read from `~/.hermes/config.yaml`.

### 6.4 Gateway Control

The gateway runs in a `tmux` session called `hermes-gw` using `hermes gateway run` (foreground mode recommended for Termux, no systemd).

```bash
# Start
tmux new-session -d -s "hermes-gw" "hermes gateway run"

# View logs
tmux attach-session -t "hermes-gw"   # Ctrl+B D to detach without killing it

# Stop
tmux kill-session -t "hermes-gw"
pkill -f "hermes gateway"
```

### 6.5 Module Commands — executable reference

| Option | Command | Description |
|---|---|---|
| [1] | `hermes` | Main interactive TUI |
| [2] | `hermes chat` | Interactive chat |
| [3] | `hermes model` | Change provider/model |
| [4] | `hermes setup` | Full wizard |
| [5] | `hermes status` | Agent + auth status |
| [6] | `hermes doctor` | System diagnostics |
| [7] | `hermes version` | Installed version |
| [8] | `hermes send` | One-shot send to Telegram/Discord/SMS |
| [9] | `hermes kanban` | Task board |

### 6.6 Configuring local Ollama — `_hermes_set_ollama()`

Centralized helper that generates the correct YAML structure for Hermes v0.16:

```bash
_hermes_set_ollama "qwen2.5:7b"
# Generates in ~/.hermes/config.yaml:
#   model:
#     provider: custom
#     base_url: http://127.0.0.1:11434/v1
#     default: qwen2.5:7b
#     ollama_num_ctx: 65536
#     context_length: 65536
```

Includes verification of the model's context window via `curl http://127.0.0.1:11434/api/show` — warns if the model has less than 64k tokens (Hermes's minimum requirement for reliable tool calling).

### 6.7 `_hermes_read_config()`

Reads the real active model from `~/.hermes/config.yaml` with a Python parser that has no dependency on PyYAML. The module header always shows the real provider/model, not the registry value, which can become stale.

### 6.8 Registry — Hermes entries

```
hermes.installed=true
hermes.version=0.16.0
hermes.install_date=YYYY-MM-DD
hermes.install_dir=/data/data/com.termux/files/home/.hermes/hermes-agent
hermes.model=openrouter/google/gemini-flash-1.5
```

---

## 7. Telegram Integration

### Requirements

- A bot created via `@BotFather` on Telegram — generates the `TELEGRAM_BOT_TOKEN`
- Your numeric user ID via `@userinfobot` — goes into `TELEGRAM_ALLOWED_USERS`
- Gateway running (`hermes gateway run`)

### Configuration in `~/.hermes/.env`

```bash
TELEGRAM_BOT_TOKEN=7123456789:AAH1bGci...
TELEGRAM_ALLOWED_USERS=6254844983
TELEGRAM_HOME_CHANNEL=6254844983
```

`TELEGRAM_ALLOWED_USERS` is the access control list. Without this field configured, the gateway accepts the connection but silently drops all messages — the log shows `Channel directory built: 0 target(s)`.

### Connection mode

Hermes uses **long polling** (not webhooks) — the gateway periodically polls Telegram's API. No public URL required, no tunnel required, works with any internet connection.

### Available Telegram slash commands

| Command | Function |
|---|---|
| `/help` | Show available commands |
| `/new` | New session (clears history) |
| `/status` | View current session status |
| `/sessions` | Browse previous sessions |
| `/model` | Change model for this session |
| `/stop` | Stop background processes |
| `/update` | Update Hermes from the bot |
| `/commands` | View all commands (paginated) |

---

## 8. Local Ollama with Hermes

### Critical requirement

Hermes requires a model with **at least 64,000 tokens of context** for reliable tool calling. Models with a smaller window are rejected on startup with the message:

```
Ollama loaded <model> with only 32,768 tokens of runtime context,
but Hermes needs at least 64,000 tokens for reliable tool use.
```

### Compatible models by device

| Model | Base RAM | Context | 11GB device | 16GB device |
|---|---|---|---|---|
| `qwen2.5:7b` | ~5 GB | 128k native | ⚠️ tuned | ✅ |
| `qwen2.5:14b` | ~10 GB | 128k native | ❌ | ✅ |
| `llama3.1:8b` | ~6 GB | 128k native | ⚠️ tuned | ✅ |
| `deepseek-r1:7b` | ~5 GB | 64k native | ⚠️ tuned | ✅ |
| `qwen2.5:3b` | ~2 GB | 32k native | ⚠️ with Modelfile | ✅ |
| `qwen2.5:0.5b` | ~400 MB | 32k | ❌ insufficient ctx | ❌ |
| `moondream:1.8b` | ~1.5 GB | 2k | ❌ insufficient ctx | ❌ |

### Configuring context window via Modelfile

When a model loads with insufficient default context, a Modelfile is created that forces `num_ctx`:

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

### Limitation on mid-range devices

With the full stack running (n8n, Ollama, Hermes gateway), the RAM available for the model can drop to ~3-4 GB. This causes `num_ctx: 131072` to segfault the `llama-server` process on ARM64. The safe value is `65536` (the minimum required).

For devices with more available RAM, `qwen2.5:14b` with `num_ctx: 65536` is the recommended option — enough context, better quality, more precise tool calling.

---

## 9. Quick Reference Commands

```bash
# Status
hermes version
hermes status
hermes doctor

# Usage
hermes                          # Interactive TUI
hermes chat                     # Interactive chat
hermes -z "reply with just OK"  # Non-interactive one-shot (test)

# Configuration
hermes model                    # Provider/model wizard
hermes setup                    # Full wizard
hermes config set model.provider custom
hermes config set model.base_url http://127.0.0.1:11434/v1
hermes config set model.default qwen2.5:7b-65k

# Gateway
hermes gateway run              # Foreground (recommended on Termux)
hermes gateway status
hermes gateway stop

# Update
hermes update

# Direct send (no agent, no LLM)
hermes send "message"           # Sends to the configured platform

# Maintenance
hermes kanban                   # Task board
hermes migrate                  # Migrate config to the new format
hermes cron                     # Scheduled task management
```

---

## 10. Uninstallation

The Module Manager includes an option to uninstall Hermes Agent, which runs:

```bash
# Stop gateway
tmux kill-session -t "hermes-gw" 2>/dev/null
pkill -f "hermes gateway" 2>/dev/null

# Remove code (keeps config)
rm -rf ~/.hermes/hermes-agent/
rm -rf ~/.hermes/venv/
rm -f  $PREFIX/bin/hermes
rm -f  ~/.local/bin/hermes

# Clean up registry
grep -v "^hermes\." ~/.android_server_registry > /tmp/reg.tmp
mv /tmp/reg.tmp ~/.android_server_registry
```

> `~/.hermes/config.yaml` and `~/.hermes/.env` are **intentionally preserved** to avoid losing API keys and Telegram tokens on reinstall.

---

## 11. Known Issues and Solutions

| Issue | Cause | Solution |
|---|---|---|
| `No models provided` (HTTP 400) | `config.yaml` with flat format | Use the nested YAML structure (section 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` not configured | Add the numeric ID in `~/.hermes/.env` |
| Segfault llama-server | `num_ctx` too high for available RAM | Reduce to `65536`, stop other services |
| `API call failed: No models provided` | OpenRouter configured without a default model | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway doesn't start after reboot | tmux session doesn't persist without a startup manager | Start manually from the Module Manager |
| `hermes: command not found` after install | Shim in `$PREFIX/bin` not in the active PATH | `source ~/.bashrc` or open a new Termux session |

---

## Screen Controls

### HermesFragment (main screen)

| Control | What it does | Why |
|---|---|---|
| "🤖 Open Hermes (TUI)" | `hermes --tui` (NOT `hermes` alone) | The binary with no flags opens the classic REPL (prompt_toolkit) — a different mode |
| "💬 Chat (hermes chat)" | `hermes chat` | A 3rd entry point from the same binary (REPL/TUI/chat) |
| "📡 Gateway" | Navigates to `HermesGatewayFragment` | — |
| "🗂 Manage projects" | `showProjectsMenu()` | — |
| "📖 Commands — reference" | Dialog with the full command list and what each does | — |
| "🔀 Choose model/provider (hermes model)" | `launchTerminalCommand("hermes model")` | Fully interactive TTY selector (arrow keys), no documented `--list`/`--json` flag |
| "🔌 Configure AI provider (quick, no terminal)" | Dialog with provider + API key → writes `~/.hermes/.env` directly | Faster than the real wizard, but without its `config.yaml` validation/formatting |
| Dropdown "Local AI provider" (Ollama/llama-server) + "🔌 Configure local AI provider" | Chooses which local backend Hermes uses, the button dispatches to the real flow | Mutually exclusive choice with no associated ON/OFF |
| "📤 Send message (hermes send)" | Dialog with a message → `hermes send` | Sends as-is to the configured platform (Telegram/Discord/SMS) — does **not** go through the LLM |
| "🤖 Direct prompt to the agent (hermes -z)" | Dialog with a prompt → `hermes -z` | Different from the one above: it does go through the LLM and returns a response, without opening the TUI |
| "⏹ Stop gateway" | `HermesNative.gatewayStop()` | — |
| "🔍 Status and diagnostics" | Dialog with installed/version/gateway/config/provider | — |
| "⚙ Full wizard (hermes setup)" | `launchTerminalCommand("hermes setup")` | — |
| "🩺 Diagnostics (hermes doctor)" | Terminal — free-text output, not JSON | Doesn't make sense to wrap it in a native dialog |
| "📋 Task board (kanban)" | `launchTerminalCommand("hermes kanban")` | — |
| "⏰ Scheduled tasks (hermes cron)" | `launchTerminalCommand("hermes cron")` | Management of the agent's own scheduled tasks |
| "↑ Update Hermes (hermes update)" | Lightweight path, only falls back to reinstall if it fails | Different from "Install/reinstall" |
| "↑ Install / reinstall" | `reinstallModuleService()` | — |
| "🔗 Connect Engram memory (skill)" | `HermesNative.installEngramSkill()` | Hermes has a real Skills system that the agent loads only when relevant |
| "🗑 Uninstall" (MAINTENANCE card) | `confirmUninstallModule()` | — |

### HermesGatewayFragment ("Gateway")

| Control | What it does | Why |
|---|---|---|
| "Gateway" switch | `hermes gateway` in tmux (`hermes-gw`) / stops it | Termux is a "best-effort" platform per Hermes's official docs |
| "View status" | Dialog with running/provider/saved config | — |
| "View logs" | `tmux attach -t hermes-gw` | No known static log path — connects to the live session (same pattern as n8n) |
