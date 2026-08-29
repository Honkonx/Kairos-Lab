# Hermes Agent — Kairos Module

**Kairos module** — managed from the app's Modules tab. Installation, start/stop, and status
checks are handled by bash scripts the app invokes.

---

**Documented version:** Hermes Agent v0.16.0
**Status:** Production — Telegram gateway active

---

## 1. Overview

Hermes Agent is an open-source (MIT-licensed) AI agent framework developed by Nous Research.
Unlike other similar tools Kairos integrates (OpenClaw, OpenCode), Hermes runs **natively in
Termux** — no proot, no Debian containers, no bundled Node.js, and no root required.

Its architecture is Python-based, using a virtual environment (`venv`) installed at
`~/.hermes/hermes-agent/venv/`. It exposes an interactive TUI, a multi-channel messaging gateway
(Telegram, Discord, Slack, SMS, Signal), and an optional OpenAI-compatible API server on port
`:8642`.

## 2. Architecture in the stack

```
Kairos
│
├── Services
│     ├── n8n          :5678  proot Debian   — workflow automation
│     ├── OpenClaw     :18789 proot Debian   — multi-provider AI gateway
│     └── Hermes Agent        native Termux  — AI agent + messaging gateway
│
├── Code Tools
│     ├── Claude Code         native Termux
│     └── OpenCode     :3000  proot Debian
│
└── Ollama         :11434 native Termux  — local AI models
```

### Key differences vs. OpenClaw

| Aspect | OpenClaw | Hermes |
|---|---|---|
| Runtime | proot Debian + Node.js | Native Termux, Python |
| Process | Long-running HTTP daemon | Interactive TUI + optional gateway |
| Port | Fixed `:18789` | Gateway `:8642`, optional, not exposed by default |
| Status check | `curl :18789` | `pgrep -f hermes` + `tmux has-session -t hermes-gw` |
| Telegram | Integration via n8n | Direct native integration |
| Installation | proot + npm | pip inside a native Termux venv |

## 3. Paths and file layout

```
~/.hermes/                          # Root data directory
├── config.yaml                     # Main config (provider, model, agent)
├── .env                            # API keys and tokens (600 permissions)
├── SOUL.md                         # Agent personality (loaded on every message)
├── hermes-agent/                   # Source code (git clone)
│   ├── venv/                       # Python virtual environment
│   │   └── bin/hermes              # Real agent binary
│   ├── constraints-termux.txt      # pip constraints for Termux/Android
│   └── scripts/
│       └── install_psutil_android.py  # ARM64 psutil patch
├── sessions/                       # Session history by ID
├── memories/                       # Agent's persistent memory
├── skills/                         # Loaded skills (built-in + custom)
├── logs/                           # Gateway logs
├── cron/                           # Scheduled tasks
├── hooks/                          # Event hooks
├── image_cache/                    # Processed image cache
└── audio_cache/                    # Audio cache (TTS/STT)

$PREFIX/bin/hermes                  # Launcher shim
```

### Launcher shim (`$PREFIX/bin/hermes`)

The installer doesn't create a direct symlink — it generates a **bash shim** that clears
inherited environment variables that would otherwise break the venv:

```bash
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/hermes" "$@"
```

This matters on Termux because nested sessions (tmux, proot) can inherit `PYTHONPATH` from other
Python installs in the stack, causing Hermes to import the wrong modules.

## 4. Main configuration — `~/.hermes/config.yaml`

Hermes v0.16 requires model configuration as a **nested YAML block**. The flat format
(`model: ollama/name`) used in older versions is no longer valid.

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

> **Common mistake:** writing `model: ollama/name` on a single line causes Hermes v0.16 to
> silently ignore it and fall back to the default provider, producing a `No models provided`
> error against OpenRouter.

### Environment variables — `~/.hermes/.env`

```bash
# Telegram
TELEGRAM_BOT_TOKEN=<token-from-BotFather>
TELEGRAM_ALLOWED_USERS=<your-numeric-Telegram-ID>
TELEGRAM_HOME_CHANNEL=<your-numeric-Telegram-ID>

# AI providers
OPENROUTER_API_KEY=sk-or-v1-...
GOOGLE_API_KEY=AIza...
# ANTHROPIC_API_KEY=sk-ant-...

# Gateway API server (optional)
# API_SERVER_ENABLED=true
# API_SERVER_KEY=your-secret-key
# API_SERVER_PORT=8642

# Home Assistant (optional)
# HASS_TOKEN=...
# HASS_URL=http://homeassistant.local:8123
```

The file has `600` permissions — readable only by its owner.

## 5. Installation

Installation adapts the official Nous Research installer to Android/Termux ARM64 constraints:

| Aspect | Official installer | Kairos adaptation |
|---|---|---|
| Python package manager | `uv` | Plain `pip` (`uv` isn't available on Termux) |
| Temp directories | `mktemp` → `/tmp/` | Avoided — `/tmp` is noexec on Android 15 |
| Interactive prompts | Free `read -r -p` | Always `read -r ... < /dev/tty` |
| Failure recovery | No checkpointing | Per-step checkpoint |
| Final wizard | Automatic | Asks before launching |

### Installer steps

```
1/6  System packages (python, git, clang, rust, make, pkg-config,
     libffi, openssl, curl, ripgrep, ffmpeg, nodejs)
2/6  Clone the repository (SSH first, HTTPS fallback) → ~/.hermes/hermes-agent/
3/6  Python virtual environment
4/6  Python dependencies, with 3 fallback levels:
       pip install -e '.[termux-all]'   ← attempt 1
       pip install -e '.[termux]'       ← fallback 2
       pip install -e '.'               ← fallback 3
     (psutil is precompiled with an Android-specific patch beforehand)
5/6  Shim at $PREFIX/bin/hermes (protects PYTHONPATH/PYTHONHOME)
6/6  Configuration files (.env, config.yaml, SOUL.md)

Wizard: hermes setup (interactive via /dev/tty) — AI provider and API key selection
```

### Critical environment variables for ARM64

```bash
ANDROID_API_LEVEL=35          # Required for Rust/maturin wheels (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Prevents uv from inheriting broken config
```

## 6. Status detection

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

Possible states: `not_installed`, `stopped`, `running` (gateway active in the `hermes-gw` tmux
session).

### Module screen controls

| Option | Description |
|---|---|
| Open Hermes (TUI) | Launches the interactive Hermes TUI |
| Gateway (Telegram/Discord/SMS) | Starts/stops the messaging gateway in tmux |
| Commands — quick reference | List of available Hermes commands |
| Configure AI provider | Change provider/model |
| Local AI provider (Ollama / llama-server) | Configures the chosen local provider |
| Status and diagnostics | View agent status and run diagnostics |
| Full wizard (`hermes setup`) | Interactive configuration wizard |
| Update / install / reinstall | Installation management |
| Scheduled tasks (`hermes cron`) | Manage scheduled jobs |

### Gateway control

The gateway runs in a `tmux` session named `hermes-gw` using `hermes gateway run` (foreground
mode, recommended for Termux, no systemd):

```bash
# Start
tmux new-session -d -s "hermes-gw" "hermes gateway run"

# View logs
tmux attach-session -t "hermes-gw"   # Ctrl+B D to detach without killing it

# Stop
tmux kill-session -t "hermes-gw"
pkill -f "hermes gateway"
```

### Configuring a local Ollama model

A centralized helper generates the correct YAML structure for Hermes v0.16:

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

It also checks the model's context window (via `curl http://127.0.0.1:11434/api/show`) and
warns if the model has less than 64k tokens (Hermes's minimum requirement for reliable tool
calling).

## 7. Telegram integration

### Requirements

- A bot created via `@BotFather` on Telegram — generates the `TELEGRAM_BOT_TOKEN`
- Your numeric user ID via `@userinfobot` — goes in `TELEGRAM_ALLOWED_USERS`
- The gateway running (`hermes gateway run`)

`TELEGRAM_ALLOWED_USERS` is the access-control list. Without it, the gateway accepts the
connection but silently drops every message — the log shows `Channel directory built: 0
target(s)`.

### Connection mode

Hermes uses **long polling** (not webhooks) — the gateway periodically polls the Telegram API.
No public URL or tunnel is required; it works over any internet connection.

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

## 8. Local Ollama with Hermes

### Critical requirement

Hermes requires a model with **at least 64,000 tokens of context** for reliable tool calling.
Models with a smaller window are rejected at startup.

### Roughly compatible models by device

| Model | Base RAM | Native context | ~11GB RAM device | 16GB RAM device |
|---|---|---|---|---|
| `qwen2.5:7b` | ~5 GB | 128k | tight | recommended |
| `qwen2.5:14b` | ~10 GB | 128k | not recommended | recommended |
| `llama3.1:8b` | ~6 GB | 128k | tight | recommended |
| `deepseek-r1:7b` | ~5 GB | 64k | tight | recommended |
| `qwen2.5:3b` | ~2 GB | 32k | with a Modelfile | recommended |

### Setting the context window via a Modelfile

When a model loads with an insufficient default context, create a Modelfile that forces
`num_ctx`:

```bash
cat > ~/qwen25_7b_65k.modelfile << 'EOF'
FROM qwen2.5:7b
PARAMETER num_ctx 65536
EOF

ollama create qwen2.5:7b-65k -f ~/qwen25_7b_65k.modelfile
```

### Note on RAM on mid-range devices

With the full stack running (n8n, Ollama, the Hermes gateway), the RAM available to the model
can shrink to just a few GB. An excessively high `num_ctx` (e.g. 131072) can cause the
`llama-server` process to segfault on ARM64 due to memory pressure — the recommended safe value
is `65536` (Hermes's minimum requirement).

## 9. Quick reference commands

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

# Direct send (bypasses the agent/LLM)
hermes send "message"

# Maintenance
hermes kanban                   # Task board
hermes migrate                  # Migrate config to a new format
hermes cron                     # Scheduled task management
```

## 10. Uninstallation

```bash
# Stop the gateway
tmux kill-session -t "hermes-gw" 2>/dev/null
pkill -f "hermes gateway" 2>/dev/null

# Remove code (keeps configuration)
rm -rf ~/.hermes/hermes-agent/
rm -rf ~/.hermes/venv/
rm -f  $PREFIX/bin/hermes
rm -f  ~/.local/bin/hermes
```

`~/.hermes/config.yaml` and `~/.hermes/.env` are **deliberately kept** so API keys and Telegram
tokens aren't lost on reinstall.

## 11. Known issues and fixes

| Issue | Cause | Fix |
|---|---|---|
| `No models provided` (HTTP 400) | Flat-format `config.yaml` | Use the nested YAML structure (section 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` not set | Add your numeric ID to `~/.hermes/.env` |
| `llama-server` segfault | `num_ctx` too high for available RAM | Reduce to `65536`, stop other services |
| `API call failed: No models provided` | OpenRouter configured without a default model | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway doesn't start after a device reboot | The tmux session doesn't persist without an autostart mechanism | Start it manually from the module manager |
| `hermes: command not found` after install | The shim isn't on the active session's PATH | `source ~/.bashrc` or open a new Termux session |
