# OpenCode — Kairos Module

**Port:** `:3000` (main web server) + `:4096` (optional second instance).

## 1. Overview

OpenCode is an AI-powered code editor (TUI + web server) that supports local Ollama as a
provider. The official binary is compiled against glibc and doesn't run directly on Android's
native libc: Kairos uses the same approach as other coding CLIs it integrates (Termux's glibc
layer, no proot, no full Linux distro).

## 2. Permissions

Requires no Android-specific permissions — it runs entirely inside Termux (Termux's glibc layer,
no proot, no external storage access beyond what the user already granted globally in the
initial setup wizard).

## 3. Installation

The installer accepts `--silent [--force]`.

### Steps (6 total)

```
1/6  glibc dependencies: glibc-repo, glibc, openssl-glibc, ncurses
2/6  Detect the latest available version (with retry)
3/6  Download the matching package
4/6  Install into Termux
5/6  Control scripts: opencode_start.sh, opencode_stop.sh
6/6  Aliases + state registry
```

`ncurses` is a required dependency (documented as such by the real project): the binary uses a
terminal library for its TUI. Without `ncurses`, `opencode --version` still works, but the TUI
itself fails at runtime.

Release metadata downloads automatically retry on interrupted connections (a real issue observed
on mobile networks, where a download can report success despite being truncated).

## 4. Status detection

- `opencode` tmux session — "running" is confirmed via `tmux has-session`.
- Port `3000` — used to confirm the server actually started before reporting success to the UI.
- The secondary instance on port `4096` is managed separately, with its own tmux session
  (`opencode-4096`).

## 5. App screen

Main controls:

| Control | Action |
|---|---|
| Web server (port selector :3000 / :4096) | Choose the port; a switch starts/stops the server on that port |
| Open (visible only while the server is running) | Opens the web interface in an internal WebView |
| TUI in terminal | Opens the interactive text interface (`opencode .`, run from the home directory) |
| Manage projects | Import/symlink/delete/sync projects in the shared projects folder |
| Send prompt (non-interactive) | Free-text dialog, option to continue the last session, and an optional model — runs the prompt without opening the full interactive interface |
| Configure provider (login) | Configures cloud provider API keys |
| View MCP servers | Panel listing configured MCP servers, per-server enable/disable |
| Configure local Ollama | Reads available Ollama models and writes the matching configuration |
| Configure local llama-server | Same mechanism as Ollama, pointing at the local llama.cpp server |
| Stop server | Stops all web server sessions/instances |
| Reinstall / update | Full reinstallation |
| Uninstall | Removes the installation |

Stopping the web server kills ALL active sessions (not just the default port), avoiding secondary
instances left running indefinitely with no way to stop them from the UI.

## 6. Reference commands

```bash
opencode-web          # starts the web server (tmux "opencode", port 3000)
opencode-stop         # stops all opencode* sessions
opencode-status       # tmux has-session -t opencode
opencode-tui          # direct TUI
```

Real non-interactive command (confirmed against the project's official documentation):

```bash
opencode run [--continue] '<prompt>' [--model '<provider/model>']
```

`opencode run` executes the prompt non-interactively, passing it as an argument; `--model`/`-m`
accepts the `provider/model` format; `--continue`/`-c` continues the last session and can be
combined with `run` to send a follow-up message without opening the TUI.

`opencode auth login` configures cloud provider API keys (Anthropic, OpenAI, etc.) — distinct
from pointing at a local provider (Ollama/llama-server), which only requires writing an
OpenAI-compatible `baseURL`, with no login involved.
