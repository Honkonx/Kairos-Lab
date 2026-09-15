# OpenClaw

## 1. What it is

OpenClaw is an AI agent gateway (npm, `openclaw@latest`) with its own web UI and TUI,
compatible with multiple providers (local Ollama, cloud). In Kairos it runs
**exclusively in native mode** (glibc + npm), without proot or a full distro.

`modules.json` describes it as: a Claude-compatible local AI gateway, native glibc
version. Port `18789`, estimated size `~60MB`, with a start/stop switch.

## 2. Permissions

None specific to Android for OpenClaw itself — it uses the Termux infrastructure the app
already requests during the initial setup wizard (storage, process execution via
`ProcessBuilder`). It requires no audio-recording permission, no notification
permission, and no manifest permission declared specifically for this module.

## 3. Installation architecture — native, no proot

```
Native Termux (no proot)
  ~/.npm-global/bin/openclaw          → real npm package
  ~/.openclaw-android/bin/node        → bash wrapper → ld.so (glibc-runner) → node.real
  ~/.openclaw-android/bin/npm, npx    → equivalent wrappers
  ~/.openclaw/glibc-compat.js         → --require in NODE_OPTIONS (os.networkInterfaces/homedir fix)
```

The official `linux-arm64` Node.js (a version targeting the minimum required by
OpenClaw, see `docs.openclaw.ai/install/node`) runs on top of the `glibc-runner` ELF
loader, without ever entering a proot environment.

## 4. Install logic (`modulos/openclaw.sh`, 8 steps)

Accepts `--silent` (no prompts, app mode) and `--force` (reinstalls even if already
present). Also `--describe` (prints a declarative JSON manifest).

1. **glibc + Node infrastructure** — if a Node with the required minimum version
   (system or the app's own wrapper) with a working npm already exists, it's reused. If
   not, it installs `glibc-repo` → `glibc-runner`/`patchelf-glibc` → downloads the
   official `linux-arm64` Node → generates `node`/`npm`/`npx` wrappers that sanitize any
   inherited `NODE_OPTIONS` before invoking the real binary.
2. **Verify Node + npm** — checks the real minimum version (major.minor.patch, not just
   the major number).
3. **Install openclaw** — `npm install -g openclaw@latest --allow-scripts=openclaw`
   (allows openclaw's own postinstall, which applies a real hotfix to the `baileys`
   library). Before skipping this step via checkpoint, it verifies the package still
   exists on disk.
4. **Android patches** — `glibc-compat.js` (fix for `os.networkInterfaces()`/
   `os.homedir()`), a stub for `koffi` (a native module not compiled for
   `android-arm64`), a stub for `clipboardy`, a patch for `/tmp` → `$HOME/tmp` paths and
   `/bin/npm` → the wrapper's real path, inside the installed bundle.
5. **Control scripts** — generates `openclaw_start.sh`/`openclaw_stop.sh` in
   `~/scripts/openclaw/` (detail in section 7).
6. **Aliases** — adds a `# OpenClaw` block to `~/.bashrc` (`openclaw-start`,
   `openclaw-stop`, `openclaw-status`, `openclaw-tui`).
7. **Registry** — updates `~/.android_server_registry`, reading `openclaw --version`
   tolerantly (if the command fails, it falls back to `"unknown"` instead of aborting
   and leaving the registry outdated).
8. **Cleanup** — deletes the checkpoint file.

## 5. Status detection (`ModuleController.kt`)

A module with a start/stop switch and a real tmux session (`"openclaw"`).
`ModuleController` resolves:
- Start script: `$HOME/scripts/openclaw/openclaw_start.sh`
- Stop script: `$HOME/scripts/openclaw/openclaw_stop.sh`
- tmux session name: `"openclaw"`
- Port: `18789`

"Running" is determined via `tmux has-session` (or port polling, depending on the flow
invoking the check) — not just a registry read.

## 6. App screen (`OpenClawFragment.kt`)

Own workspace: OpenClaw uses `$HOME/.openclaw/workspace` (not the shared `~/proyectos`
folder used by Claude/OpenCode/Codex/Antigravity).

"STATUS" card: variant (`native·glibc`), version, gateway status, token (masked), active
model, terminal status pill.

The gateway is controlled with a switch (`gatewaySwitch`): `on=true` → starts
(`openclaw_start.sh`); `on=false` → stops (`openclaw_stop.sh`).

| Control | Action |
|---|---|
| **Gateway** switch | Starts/stops the gateway |
| Restart gateway | Stops and starts it again |
| View logs | Opens the log viewer over `~/openclaw-logs/runtime.log` |
| Show URL with token | Reads `gateway.auth.token` from `~/.openclaw/openclaw.json` (with the runtime log as fallback), builds `http://localhost:18789/#token=...`. The token exists once the gateway has run at least once |
| Open web interface (local) | If it isn't running, starts it first; then opens the internal WebView on `http://localhost:18789` (with the token already included in the URL) |
| Open TUI (terminal) | Launches `openclaw tui` in a terminal session |
| Onboarding | Launches `openclaw onboard` (interactive wizard) — configures a real AI provider; it isn't a prerequisite for the gateway to start |
| AI Provider / Model | View, configure Ollama, custom provider, or restore a config backup |
| Configure channels | Dialog with the 4 confirmed real channels (Discord/Telegram/WhatsApp/Slack) — "Active channel" switch + token field, saves/deletes in `~/.openclaw/openclaw.json` with an automatic backup before writing |
| Manage workspaces | Symlink/import from Downloads, delete, sync all |
| Open workspace in TUI | Lists already-imported workspaces, opens `openclaw tui` in the chosen one |
| Install / update | Full reinstall |
| Connect Engram memory (MCP) | `openclaw mcp add` — OpenClaw is a real, documented MCP client; if the gateway is already running, it offers to restart it to pick up the new server |

**Non-obvious detail**: the "Custom provider" dialog asks for 5 fields (name, base URL,
API key, model ID, context window) for OpenAI-compatible providers (DeepSeek, LM
Studio, etc.) — Gemini/Anthropic with OAuth go through "Onboarding" instead, not through
this dialog.

## 7. Runtime — generated scripts

`openclaw_start.sh`:
- If the gateway already responds on `:18789`, it exits immediately.
- Kills any previous process/session, starts a new tmux session passing the command
  directly to `tmux new -d -s openclaw` (with `TMPDIR`/`NODE_OPTIONS` set inline,
  dynamic heap — see below).
- Accepts `--no-wait` (fires and exits without waiting, for a non-blocking start).
- Without `--no-wait`: real HTTP health check (`curl` against `:18789`, up to 6 attempts
  of 2s each).
- **Dynamic heap**: computes Node's heap as 60% of `MemAvailable` (clamped
  1024–5632MB) instead of a fixed value, to avoid the system killing the process for
  lack of memory right when Node loads the full bundle for the first time.

`openclaw_stop.sh`: kills the process and the tmux session, confirms with a `curl` that
the gateway no longer responds.

## 8. Configuration — `~/.openclaw/openclaw.json`

`OpenClawNative.providersList()` looks for the config in two possible paths (first
`~/.openclaw/config.json`, then `~/.config/openclaw/config.json`, this second one is
only a legacy fallback) and returns the raw JSON as-is — the app doesn't have a full
structured editor for this file, "AI Provider / Model" only shows it read-only except
for the part covered by the channel editor.

The real, current file is `~/.openclaw/openclaw.json` (confirmed against
`docs.openclaw.ai/gateway/configuration`). Each messaging channel (Discord, Telegram,
WhatsApp, Slack, Signal, iMessage, WebChat, and more via plugins) has its own section
under `channels.<provider>` in that same file (auth, access control, multi-account,
mention gating).

Kairos's channel editor covers the minimal schema (`enabled`/`token`), which is the most
common use case (a Discord/Telegram bot). Advanced per-provider fields (multi-account,
mention gating, fine-grained access control) don't have their own editor yet — if
needed, `~/.openclaw/openclaw.json` has to be edited by hand.

The `gateway.mode` field must be set to `"local"` for the gateway to start and
auto-generate its own `gateway.auth.token` — Kairos pre-seeds this field in the config
during installation and reinforces it on every start, so the user doesn't need to
complete the interactive onboarding (choosing an AI provider) just to get the gateway
working and have the token exist; onboarding is still available to configure a real
provider whenever the user wants, while that isn't a prerequisite for the gateway
itself.

## 9. Registry

```
openclaw.installed=true
openclaw.version=<real version, or "unknown" if openclaw --version didn't respond>
openclaw.install_date=<date>
openclaw.location=nativo_termux
openclaw.port=18789
```

## 10. Port

`18789` — fixed, no variants.

## 11. Project folder scope

OpenClaw uses its own workspace (`$HOME/.openclaw/workspace`, separate from the shared
`~/proyectos` folder used by Claude/OpenCode/Codex/Antigravity). To "add a project", the
only sources offered by the shared picker are: symlink/copy from Downloads,
symlink/copy from external storage, or symlink from a project already in `~/proyectos`
— there's no option to browse/symlink an arbitrary folder in Termux's `$HOME`. This is a
design limitation of the shared project picker, not of OpenClaw itself — OpenClaw's
actual process has no fixed working directory imposed by the binary.
