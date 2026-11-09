# OpenCode

**Kairos module** — managed through the Kairos UI (Modules tab). Installation, start/stop and state are handled by the app via `ProcessBuilder` → `modulos/opencode.sh`.

---

**Script:** `modulos/opencode.sh` (855 lines) — synchronized copy in `app/src/main/assets/scripts/opencode.sh`.
**Port:** `:3000` (main web server) + `:4096` (optional second instance).
**Registry:** prefix `opencode.*`
**`hasSwitch`:** `false` in `modules.json` — it is a CLI/server with no toggle in the module list, but it DOES have its own start/stop from its own screen (via `ModuleController`, just like a module with a switch).

> Convention: **confirmed** = verified by reading the code; **unverified** = inference without a device.

---

## 1. Overview

OpenCode is an AI coding agent (TUI + `opencode web` web server) — it supports local Ollama and llama-server as providers. Kairos installs it as a binary from the Termux fork, brings the web server up at `127.0.0.1:3000` (or `:4096`) and shows it in a WebView, and can also open the TUI in the terminal.

The official binary is compiled against glibc and does not run directly on Bionic (Android's native libc): Kairos uses the same approach as Claude Code/Codex/Antigravity, Termux's glibc (`glibc-repo`) without proot or a full distro. There is no proot mode.

Classification: **functional module** (the most complete of the AI CLIs) — `OpenCodeFragment` (`ModuleDetailNavigator.kt:26`) with a real service (switch + port dropdown, tmux sessions, WebView), local provider configuration (Ollama / llama-server) and an MCP panel with toggles. **~16 controls**. It is the only AI CLI with a port (`3000`, `webviewUrl`, `tmuxSession:"opencode"`). `modules.json` records `size:~480MB` and `catalogVersion:"2.0.1"` (static catalog; not the real installed version, which comes from the registry).

## 2. Permissions

No special Android permissions are required — it runs 100% inside Termux (Termux's glibc, no proot, no access to external storage beyond what the user has already granted globally through the Wizard).

## 3. Installation — `modulos/opencode.sh`

Flags (`opencode.sh:77-81`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant glibc|bionic`. `modules.json` reflects `hasVariants:true`, `installMethods:["glibc","bionic"]`, `requires:["glibc"]`.

The script has **2 paths + a universal fallback** (`opencode.sh:10-35`):
- `--variant glibc` (default): `Honkonx/opencode-termux` pure-android branch, `.pkg.tar.xz` with a fallback to `.deb` (`install_opencode_glibc`, `:358`).
- `--variant bionic`: native Bionic ELF, native-android branch (`:570`).
- Automatic fallback to `wallentx/opencode-termux` (`:647`).

### Steps (checkpoints in `~/.install_opencode_checkpoint`, with intermediate data `$CHECKPOINT.data.glibc` to resume the download)

```
STEP 1  glibc dependencies: glibc-repo, glibc, openssl-glibc, ncurses
STEP 2  Detect the latest version — GitHub API (api.github.com/repos/<owner>/opencode-termux/releases/latest), with retry
STEP 3  Download the package (.pkg.tar.xz, fallback .deb) from the detected release
STEP 4  Install into Termux (extract and copy to $PREFIX, or dpkg -i depending on the format)
STEP 5  Control scripts: opencode_start.sh, opencode_stop.sh → ~/scripts/opencode/
STEP 6  Aliases in .bashrc + Registry
```

- Download: `curl`/`wget` with timeouts and an explicit User-Agent (`_oc_download` `:229`, `_oc_fetch_json` `:243`); it selects an `aarch64|arm64` asset excluding `sha256|sig|asc` (`:603`, `:667`). **No checksum is downloaded or verified** (the only occurrences of `sha256` in the script are filters to exclude it from asset selection).
- Verification: `verify_binary_installed opencode` in each path (`:550`, `:627`, `:696`) before recording; registry `opencode.version/location/port=3000/variant` (`update_registry` `:121-126`).
- `--describe` returns the module's declarative JSON manifest (id, `--silent`/`--force` support, variants).

### Resolved behaviors (avoid reintroducing them)

- **`ncurses` is mandatory** — the README of `Honkonx/opencode-termux` (`pure-android` branch, "Dependencies" section) lists `ncurses` as mandatory ("TUI support") along with glibc/openssl-glibc — the binary uses `@opentui/solid` for the TUI. Without `ncurses`, `opencode --version` still works (STEP 1 passed green), but `opencode .` (the "TUI in terminal" button) failed at runtime.
- **GitHub metadata download cut short** — on a mobile connection, curl may report success with a truncated file if the server closes the connection, which produced "No aarch64 binary found in release" even though the release DID have the right assets. It now retries once with `sleep 2` in between, and if it fails again the error message shows what was actually downloaded.
- **Complete stop script** — `OpenCodeFragment.kt` offers a web server on `:3000` (tmux session `opencode`) and `:4096` (separate tmux session `opencode-4096`, started via `OpenCodeNative.webStart(4096)`, with no entry of its own in `modules.json`). `opencode_stop.sh` kills any `opencode`/`opencode-*` session, plus a safety `pkill -f 'opencode web'` — and on the Kotlin side, the "Stop server" button also calls `OpenCodeNative.stopAll()` (it enumerates and kills all `opencode*` tmux sessions directly, without depending on the script installed on the device being up to date).
- **Persistent output** — if `opencode web` died before the `sleep 2`, the tmux session closed with it and the output was lost. It now redirects to `~/kairos_logs/opencode_web.log`.

## 4. Execution and state detection — `ModuleController.kt`

- `getTmuxSession("opencode")` → `"opencode"` — `isRunning()` checks `tmux has-session -t opencode`.
- `getModulePort("opencode")` → `3000` (`:1238`) — used by `waitForPortOpen()` to confirm a real start before reporting success to the UI.
- `getModuleStartScript("opencode")` → `$HOME/scripts/opencode/opencode_start.sh` (`:1165`); `getModuleStopInfo("opencode")` → `.../opencode_stop.sh` (`:1187`).
- The generated script (`opencode.sh:747-783`) starts `tmux new-session -d -s opencode "cd '$CWD' && BROWSER= opencode web --port 3000 --hostname 127.0.0.1 > ~/kairos_logs/opencode_web.log"`.
- The `:4096` instance (session `opencode-4096`, log `.opencode_web_4096.log`) has NO entry in `ModuleController.kt` or `modules.json` — it is handled separately, directly from `OpenCodeFragment` via `OpenCodeNative.webStart()` (`OpenCodeNative.kt:88`, pure Kotlin, no intermediate script), with an optional cwd. It is only killed with `stopAll()`.
- TUI: `OpenCodeNative.tuiCmd(path)` → `cd <path> && opencode .`.

## 5. App screen — `OpenCodeFragment.kt`

STATUS card: variant (from the registry), version (read from `OpenCodeNative.info()`), "Web server" pill (real state, `isModuleRunning()`), "Terminal TUI" pill (via `terminalStatusPill()`, a helper shared with other CLIs).

### Screen controls

| Control | What it does | Notes |
|---|---|---|
| "Web server" dropdown+switch (:3000 / :4096) | Picks the port, the switch starts/stops on that port | Locked while ON — it must be turned off before changing port. Unrelated to the TUI |
| "🌐 Open" (visible only if the server is running) | Opens the WebView at `http://localhost:<port>` | — |
| "Terminal TUI" pill | Read-only — minimized terminal session running | With an option to close the TUI terminal |
| "⌨ TUI in terminal" | `cd ~ && opencode .` (not plain `opencode`); asks where to open | `OpenCodeNative.tuiCmd()` replicates exactly the real command |
| "🗂 Manage projects" | Shared 4-option menu (`showProjectsMenu`, `ProjectActions.kt`: symlink/import copy/delete/sync all) over `~/proyectos`, with `onLaunchInProject`; `project_origin` registry prefix shared with `ClaudeFragment`/`CodexFragment`/`AntigravityFragment` | — |
| "💬 Send prompt (non-interactive)" | Dialog with a free-text prompt, a "Continue the last session (--continue)" checkbox and an optional model field (`provider/model` format, e.g. `anthropic/claude-sonnet-5`) → `opencode run [--continue] '<escaped prompt>' [--model '<model>']` in the terminal | Per `opencode.ai/docs/cli/`: `opencode run` runs non-interactively with the prompt as a positional argument, `--model`/`-m` accepts `provider/model`, `--continue`/`-c` continues the last session. Different from "TUI in terminal", which opens the full interactive interface. Prompt/model with `shellEscape` |
| "🔑 Configure provider (auth login)" | `opencode auth login` in the terminal | Configures cloud provider API keys (Anthropic, OpenAI, etc.), different from "Configure local Ollama/llama-server" (they only write `opencode.json` to a local endpoint, with no login) |
| "🔌 View MCP servers" | Native panel (reads `~/.config/opencode/opencode.json`): list, enabled/disabled toggle, command/args/env detail per server | OpenCode supports a flat `enabled` field per server in its schema |
| "🤖 Configure local Ollama" | Reads real Ollama models (`OpenCodeNative.ollamaModels()`) and writes `~/.config/opencode/opencode.json` with the chosen provider | Fails with a clear message if Ollama is not running |
| "◍ Configure local llama-server" | The same generic OpenAI-compatible `baseURL` mechanism, pointing to `http://127.0.0.1:8085/v1`; lists the `.gguf` files already downloaded in `LocalModelManager` | llama-server has no `/api/tags` to list models. See `llama-server.en.md` §8. Not confirmed on a real device |
| "↑ Reinstall / update" | `reinstallModuleService()` | — |
| "🗑 Uninstall" (MAINTENANCE card) | `confirmUninstallModule()` — deep plan `rm -f $PREFIX/bin/opencode; rm -rf $PREFIX/lib/opencode` (`ModuleController.kt:895`) | — |

OpenCode covers `--continue`, the `web`/headless server mode, MCP with a real toggle and local providers (Ollama/llama-server). No other documented flag/subcommand is exposed (`opencode debug config`, the `serve` mode's `--port`/`--hostname`) because of their low value without a clear use case in the app.

## 6. Registry (`~/.android_server_registry`)

```
opencode.installed=true
opencode.version=<real version from "opencode --version">
opencode.install_date=YYYY-MM-DD
opencode.location=termux_native
opencode.port=3000
opencode.variant=<glibc|bionic>
```

## 7. Reference commands

```bash
opencode-web          # alias -> opencode_start.sh (tmux "opencode", port 3000)
opencode-stop         # alias -> opencode_stop.sh (kills all opencode* sessions)
opencode-status       # tmux has-session -t opencode
opencode-tui          # alias -> opencode (TUI directly)
```

## 8. Files and paths

`$PREFIX/bin/opencode`, `$PREFIX/lib/opencode/runtime/opencode`, `~/scripts/opencode/opencode_start.sh` and `opencode_stop.sh`, `~/kairos_logs/opencode_web.log`, `~/.opencode_web.log`, `~/.opencode_web_4096.log`, `~/.config/opencode/opencode.json`, `~/.bashrc` (aliases `opencode-web`, `opencode-status`, among others), temporary files `~/.opencode_install_tmp`, checkpoint `~/.install_opencode_checkpoint`.

## 9. Security

- The web server is bound to `127.0.0.1` (not exposed to the network). There is no password/token at startup (`OPENCODE_SERVER_PASSWORD` appears in neither `opencode.sh` nor `OpenCodeNative.kt`): any local app with access to loopback can talk to it (real risk unverified).
- Third-party downloads (`Honkonx/…`, `wallentx/…`) without a checksum. Extraction of `.pkg.tar.xz`/`.deb` into `$PREFIX` with the user's permissions.
- The Ollama/llama `opencode.json` uses a dummy `apiKey` (`"ollama"`, `"llamaserver"`), not real secrets.

## 10. Known limitations

- **Data loss**: `ollamaConfig()` and `llamaServerConfig()` create a new `JSONObject` and do `configFile.writeText(...)` (`OpenCodeNative.kt:168-181`, `:200+`), **overwriting all** of `opencode.json` — any `mcp`, other providers or settings that the user or the MCP panel had saved are lost. `ollamaRemoveConfig()` deletes the whole file (`:188-191`).
- The `:4096` server has no entry in `modules.json` → Processes/Monitor/ModuleController do not manage it.
- `:3000` ignores the chosen cwd (toast `opencode_port_3000_default_cwd`); only `:4096` honors a folder.
- No checksum on any path.
- Web server without authentication (see §9).
- `opencode.sh` has no incremental update function (comparing the current version against the latest release); "Reinstall / update" reinstalls.

## 11. Added

`modulos/opencode.sh`: **2026-07-19**.
