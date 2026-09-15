# OpenCode

**Kairos module** — managed from the UI (Modules tab). Installation, start/stop and
status are handled by the app via `ProcessBuilder` → `modulos/opencode.sh`.

---

**Script:** `modulos/opencode.sh` — copy synced to
`app/src/main/assets/scripts/opencode.sh`.
**Port:** `:3000` (main web server) + `:4096` (optional second instance).
**Registry:** prefix `opencode.*`
**No switch in the module list:** it's a CLI/server with no toggle in the general list,
but it does have its own start/stop from its own screen.

---

## 1. Overview

OpenCode is an AI-powered code editor (TUI + web server) — supports local Ollama as a
provider. The official binary is built with glibc and doesn't run directly on Android's
native libc: Kairos uses the same approach as Claude Code/Codex/Antigravity, Termux's
glibc without proot or a full distro.

## 2. Permissions

Requires no special Android permissions — runs entirely inside Termux (Termux's glibc,
no proot, no external storage access beyond what the user already granted globally
during the setup wizard).

## 3. Installation — `modulos/opencode.sh`

Accepts `--silent [--force] [--describe]`. With `--describe` it returns a declarative
JSON manifest, no variants.

### Steps (6 total, with checkpoints)

```
STEP 1/6  glibc dependencies: glibc-repo, glibc, openssl-glibc, ncurses
STEP 2/6  Detect the latest version — GitHub API, with retry
STEP 3/6  Download the package (.pkg.tar.xz, fallback .deb) from the detected release
STEP 4/6  Install into Termux (extract and copy to $PREFIX, or dpkg -i depending on format)
STEP 5/6  Control scripts: opencode_start.sh, opencode_stop.sh → ~/scripts/opencode/
STEP 6/6  Aliases in .bashrc + Registry
```

`ncurses` is a required dependency (the TUI uses `@opentui/solid`) — without it,
`opencode --version` still works but `opencode .` (TUI in the terminal) fails at
runtime. The stop script kills any `opencode`/`opencode-*` session (covering both the
`:3000` server and the optional `:4096` instance), plus a safety `pkill -f 'opencode
web'`. The web server's real output is redirected persistently to
`~/kairos_logs/opencode_web.log`.

## 4. Status detection — `ModuleController.kt`

- `getTmuxSession("opencode")` → `"opencode"` — `isRunning()` checks `tmux has-session
  -t opencode`.
- `getModulePort("opencode")` → `3000` — used by `waitForPortOpen()` to confirm a real
  startup before reporting success to the UI.
- `getModuleStartScript("opencode")` → `$HOME/scripts/opencode/opencode_start.sh`;
  `getModuleStopInfo("opencode")` → `.../opencode_stop.sh`.
- The `:4096` instance has no entry in `ModuleController.kt` or `modules.json` — it's
  handled separately, directly from `OpenCodeFragment` via `OpenCodeNative.kt` (pure
  Kotlin, no intermediate script).

## 5. App screen — `OpenCodeFragment.kt`

STATUS card:
- Variant: `native·glibc` (fixed).
- Version.
- "Web server" pill — real status.
- "Terminal TUI" pill — status of a minimized terminal session.

Dropdown + switch for "Web server" (`:3000` / `:4096`): the dropdown picks the port, the
switch starts/stops on that port — locked while it's on, you have to turn it off before
changing port.

Buttons:
- **"TUI in terminal"** — runs `cd ~ && opencode .` (not `opencode` by itself).
- **"Open"** (visible only if the server is running) — opens the WebView to the chosen
  port.
- **"Manage projects"** — the same shared 4-option menu (symlink/import copy/delete/sync
  all) as the rest of the CLIs, over `~/proyectos`.
- **DIRECT PROMPT card — "Send prompt (non-interactive)"** — dialog with a free-text
  prompt, a "Continue the last session (--continue)" checkbox and an optional model
  field (`provider/model` format). Runs `opencode run [--continue] '<prompt>'
  [--model '<model>']` in the terminal — different from the "TUI in terminal" button,
  which opens the full interactive interface.
- **ACCOUNT card — "Configure provider (auth login)"** — launches `opencode auth
  login`: configures cloud provider API keys (Anthropic, OpenAI, etc.), different from
  "Configure local Ollama/llama-server" (which only write `opencode.json` pointing to a
  local endpoint, with no login or API key).
- **"View MCP servers"** — panel with a list, an enabled/disabled toggle, and per-server
  detail view. OpenCode supports a flat `enabled` field per server in its real schema,
  unlike other CLIs.
- **"Configure local Ollama"** — reads Ollama's real models and writes
  `~/.config/opencode/opencode.json` with the chosen provider — fails with a clear
  message if Ollama isn't running.
- **"Configure local llama-server"** — same generic OpenAI-compatible `baseURL`
  mechanism, pointing at `llama-server`'s port (`:8085`) instead of Ollama's; lists the
  already-downloaded `.gguf` files (llama-server has no endpoint to list available
  models like Ollama does).
- **"Stop server"** — kills all `opencode`/`opencode-*` sessions, not just the `:3000`
  one.
- **"Reinstall / update"** — full reinstall.
- **"Uninstall"** (maintenance card).

## 6. Registry (`~/.android_server_registry`)

```
opencode.installed=true
opencode.version=<real version from "opencode --version">
opencode.install_date=YYYY-MM-DD
opencode.location=termux_native
opencode.port=3000
```

## 7. Reference commands

```bash
opencode-web          # alias -> opencode_start.sh (tmux "opencode", port 3000)
opencode-stop         # alias -> opencode_stop.sh (kills every opencode* session)
opencode-status       # tmux has-session -t opencode
opencode-tui          # alias -> opencode (direct TUI)
```
