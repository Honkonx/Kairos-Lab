# OpenClaw

**Kairos module** — managed through the Kairos UI (Modules tab). Installation, start/stop and state are handled by the app via `ProcessBuilder` → `modulos/openclaw.sh`.

---

**Script:** `modulos/openclaw.sh` (v5.0.0, 865 lines) — synchronized copy in `app/src/main/assets/scripts/openclaw.sh`.
**Port:** `:18789` (fixed, no variants; `--bind loopback`, `modulos/openclaw.sh:679`)
**Registry:** prefix `openclaw.*`
**Fragment:** `OpenClawFragment.kt` (628 lines), with `OpenClawNative.kt` (423 lines)

> Convention: **confirmed** = verified by reading the code; **unverified** = inference without a runtime test.

---

## 1. What it is

OpenClaw is an AI agent gateway (npm, `openclaw@latest`) with its own web interface and TUI, compatible with multiple providers (local Ollama, cloud). In Kairos it runs **exclusively in native mode** (glibc + npm), without proot (`modulos/openclaw.sh` line 28: *"proot branch removed — no longer needed... the native (glibc-runner) variant is the correct and the only maintained one"*). It listens on `127.0.0.1:18789`.

`modules.json`: `"description": "Gateway de IA local compatible con Claude. Versión nativa glibc."`, port `18789`, estimated size `~60MB`, `hasSwitch: true`, `arch: glibc`, `requires: [glibc]`, `installMethods: [glibc]`, `catalogVersion 4.1.0` (the script says v5.0.0: mismatch). Module added: 2026-07-19.

Classification: **functional module** — own UI with state and service (`OpenClawFragment`, `ModuleDetailNavigator.kt:27`), start/stop switch with tmux session and port (`ModuleController.kt:1164,1186,1199,1237`), registry, gateway token, provider and channel editors that write `~/.openclaw/openclaw.json`. ~15 controls in `buildContent()` (`OpenClawFragment.kt:44-172`).

## 2. Permissions

No Android permission specific to OpenClaw itself — it uses the Termux infrastructure already present (storage, process execution via `ProcessBuilder`) that the app already requests in the general wizard. It does not require `RECORD_AUDIO`, `POST_NOTIFICATIONS`, or any permission of its own declared in the manifest on behalf of this module.

## 3. Installation architecture — 100% native, no proot

```
Native Termux (no proot)
  ~/.npm-global/bin/openclaw          → real npm package (bash wrapper)
  ~/.openclaw-android/bin/node        → bash wrapper → ld.so (glibc-runner) → node.real
  ~/.openclaw-android/bin/npm, npx    → equivalent wrappers
  ~/.openclaw/glibc-compat.js         → --require in NODE_OPTIONS (os.networkInterfaces/homedir fix)
```

The official `linux-arm64` Node.js (target version `22.23.2`, `NODE_VERSION_TARGET`, `:66`; minimum required by OpenClaw: *"Node 22.22.3+, 24.15+, or 25.9+... Node 23 unsupported"*, source `docs.openclaw.ai/install/node`) runs on the `glibc-runner` ELF loader, without entering any proot environment.

## 4. Installation (`modulos/openclaw.sh`, 8 steps)

Flags (`:96-102`): `--silent` (no prompts, app mode), `--force` (reinstalls even if already installed; clears the checkpoint, `:181`), `--describe` (declarative JSON manifest, `"variants": []`, `:109`), `--describe-files`, `--variant` (accepted and ignored). Without `--force` and with `openclaw.location=nativo_termux` in the registry, it exits with "already installed" (`:168-177`).

1. **glibc + Node infrastructure** (`_ensure_glibc_node`) — if a Node ≥ the required minimum (system or own wrapper) with a working npm already exists, it reuses it. Otherwise it installs `glibc-repo` → `glibc-runner`/`patchelf-glibc` → downloads the official `linux-arm64` Node → generates `node`/`npm`/`npx` wrappers that sanitize inherited `NODE_OPTIONS` before invoking the real binary (a `--require` to a deleted `glibc-compat.js` would break ANY node/npm invocation).
2. **Verify Node + npm** — real minimum-version check (major.minor.patch, not just the major — `_node_meets_openclaw_min()`, `:222`, accepts 22.22.3+, 24.15+, 25.9+, or any 26.x+).
3. **Install openclaw** — `npm install -g openclaw@latest --ignore-scripts` with `NODE_LLAMA_CPP_SKIP_DOWNLOAD=true` (`:450-453`), followed by manually running openclaw's OWN postinstall with `npm_config_ignore_scripts=true node <postinstall>` (`:458-470`). `--allow-scripts=openclaw` is NOT an npm flag (`:437-448`); the manual postinstall recovers the `baileys` hotfix (promise race and a dispatcher incompatible with Undici in media downloads, see `scripts/postinstall-bundled-plugins.mjs` of the `github.com/openclaw/openclaw` repo) without running the native scripts of transitive dependencies. A real bash wrapper replaces npm's symlink (`:498-515`, because of the symlink execution restriction). Before skipping this step via checkpoint, it verifies that the package still exists on disk (`$NPM_GLOBAL/lib/node_modules/openclaw`).
   After installing, it **pre-seeds** `~/.openclaw/openclaw.json` with `gateway.mode=local` **and** `gateway.auth.mode=token` + a random 24-byte hex token (`:511-553`; it merges without overwriting anything else if the file already exists) — see section 10.
4. **Android patches** — `glibc-compat.js` (`os.networkInterfaces()`/`os.homedir()` fix), a `koffi` stub (native module not compiled for `android-arm64`), a `clipboardy` stub, a patch of `/tmp` → `$HOME/tmp` paths and `/bin/npm` → the wrapper's real path, inside the installed bundle.
5. **Control scripts** — generates `openclaw_start.sh`/`openclaw_stop.sh` in `~/scripts/openclaw/` (`:626-762`, detail in section 7).
6. **Aliases** — adds a `# OpenClaw` block to `~/.bashrc` (`openclaw-start`, `openclaw-stop`, `openclaw-status`, `openclaw-tui`; `:769-790`).
7. **Registry** — updates `~/.android_server_registry` (`update_registry`, `:162-165`). Reading `openclaw --version` uses `|| true` so a failure there does not abort the script under `set -euo pipefail`; it falls back to `"unknown"` and the registry is ALWAYS updated if this point was reached (`:793-818`).
8. **Cleanup** — deletes the checkpoint file (`~/.install_openclaw_native_checkpoint`); best-effort `openclaw update` with `timeout 120` (`:824-850`).

Verification: the script does **not** use `verify_binary_installed()` nor require the binary to run before `installed=true` (only `--describe-files` includes a `verify_cmd` with `openclaw --version`, used by moduledeb).

Global effects: it adds `NODE_OPTIONS=… --max-old-space-size=5632` to `~/.bashrc` (`:402-404`) and, after step 4, a `--require ~/.openclaw/glibc-compat.js` (`:574-575`); it creates a `$PREFIX/bin/systemctl` stub if none exists (`:412-418`).

## 5. State detection (`ModuleController.kt`)

A module with `hasSwitch: true` and a real tmux session — `tmuxSession: "openclaw"` in `modules.json`. `ModuleController` resolves:
- Start script: `$HOME/scripts/openclaw/openclaw_start.sh`
- Stop script: `$HOME/scripts/openclaw/openclaw_stop.sh`
- tmux session name: `"openclaw"`
- Port: `18789`

"Running" is determined via `tmux has-session` or an open port (`ModuleController.kt:~370-390`, `OpenClawNative.info()`) — not just reading the registry. There is no tunnel tmux session.

## 6. App screen (`OpenClawFragment.kt`)

Own workspace: OpenClaw uses `$HOME/.openclaw/workspace` (NOT the shared `~/proyectos` folder of Claude/OpenCode/Codex/Antigravity), registry prefix `openclaw_workspace`. `ProjectsManager` supports `baseDir`/`regPrefix` as parameters for this case.

"STATUS" card: variant (fixed text `native·glibc`), version (`openclaw.version` from the registry), Gateway switch, masked token (`take(6)…takeLast(4)`, `:225`; amber as "— (onboarding missing)" if the field is empty, string `openclaw_token_missing`), terminal status pill.

| Control | What it does |
|---|---|
| **Gateway** switch | `on=true` → `startGatewayGuarded()` (calls `startModuleService()` → `openclaw_start.sh`); `on=false` → `stopModuleService()` → `openclaw_stop.sh` |
| Token (info row) | Read-only — the gateway's real token, read with `OpenClawNative.gatewayUrl()` |
| "Terminal TUI" pill | Read-only — whether a minimized terminal session is running (with close) |
| ↻ Restart gateway | Stops and starts again with `startGatewayGuarded()` |
| 📋 View logs | Opens `LogsFragment` over `~/openclaw-logs/runtime.log` |
| 🔗 Show URL with token | `OpenClawNative.gatewayUrl()` — reads `gateway.auth.token` from `~/.openclaw/openclaw.json` (with the runtime log as a fallback via the regex `token=([a-f0-9]+)`), builds `http://localhost:18789/#token=...`; dialog with a copy button |
| 🌐 Open web interface (local) | If it is not running, it starts it first; then opens `ModuleWebViewFragment` with the full URL with `#token=...` (falls back to the bare URL only if there is no token yet) |
| ⌨ Open TUI (terminal) | `launchTerminalCommand("openclaw tui")` (with a folder selector) |
| ⚙ Onboarding | `launchTerminalCommand("openclaw onboard")` — interactive wizard to configure a real AI provider; it is not a prerequisite for the gateway/token (see section 10) |
| 🔌 AI Provider / Model | Submenu: view full config / configure Ollama / custom provider (5 fields: name, base URL, API key, model ID, context window; for OpenAI-compatible providers such as DeepSeek, LM Studio, etc.) / restore backup. Gemini/Anthropic with OAuth go through "Onboarding" |
| 📡 Configure channels | Dialog with 4 channels (Discord/Telegram/WhatsApp/Slack); see section 8 |
| 🗂 Manage workspaces | `showProjectsMenu()` over `~/.openclaw/workspace`: symlink/import from Download, delete, sync all |
| 📁 Open workspace in TUI | Lists already-imported workspaces, opens `openclaw tui` in the chosen one |
| ↑ Install / update | `reinstallModuleService()` (full reinstall; no way to pass an explicit `--force` from here) |
| 🔗 Connect Engram memory (MCP) | `openclaw mcp add engram …` via `OpenClawNative.mcpConnectEngram()` (`OpenClawNative.kt:397`) — OpenClaw is a real, documented MCP client; if the gateway is already running, it offers to restart it to pick up the new server |
| 🗑 Uninstall | `confirmUninstallModule()` (`OpenClawFragment.kt:168-170`) |

## 7. Runtime — generated scripts

`openclaw_start.sh` (generated by STEP 5, `:626-735`):
- If the gateway already responds on `:18789`, it exits immediately (`[OK] Gateway already running`).
- It kills any previous `openclaw gateway` (a 2-word pattern so it does not kill itself), creates the tmux session `openclaw` passing the command DIRECTLY to `tmux new -d -s openclaw` (with `TMPDIR`/`NODE_OPTIONS` set inline), and launches `openclaw gateway --bind loopback --port 18789 | tee ~/openclaw-logs/runtime.log`.
- It accepts `--no-wait` (fires and exits without waiting, for a non-blocking start).
- Without `--no-wait`: a real HTTP health-check (`curl` against `:18789`), 22 attempts of 2 s (~44 s); if it does not respond it runs `openclaw doctor --fix` and retries another ~44 s (`:695-725`).
- **Dynamic heap**: it computes Node's heap as 60% of `MemAvailable` (clamp 1024–5632MB) instead of a fixed value, to keep Android from killing the process for OOM right when Node loads the full bundle for the first time.

The Kotlin path (`OpenClawNative.gatewayStart`, `:90-103`) reinforces `gateway.mode` and runs the script with a 90 s timeout.

`openclaw_stop.sh` (`:736-762`): `pkill -TERM -f "openclaw gateway"` + `tmux kill-session -t openclaw` + a 3 s wait + `pkill -9`, and it confirms with a `curl` that the gateway no longer responds. The Kotlin fallback without the script uses `pkill -9 -f openclaw` (`OpenClawNative.kt:119`), with the single-word pattern the script itself documents as self-killing; it only runs if the script is missing.

## 8. Configuration — `~/.openclaw/openclaw.json`

The real, current file is `~/.openclaw/openclaw.json` (confirmed against `docs.openclaw.ai/gateway/configuration`); `OpenClawNative.providersList()` looks there first, and `~/.config/openclaw/config.json` is only a legacy fallback. "AI Provider / Model" > "View full config" shows the raw JSON (`showProviders`, `OpenClawFragment.kt:601-619`; `providersList`, `OpenClawNative.kt:168`).

Each messaging channel (Discord, Telegram, WhatsApp, Slack, Signal, iMessage, WebChat, and more via plugins — Matrix, Nostr, Twitch, Zalo) has its own section under `channels.<provider>` in that same file (auth, access control, multi-account, mention gating) — `docs.openclaw.ai/gateway/config-channels` documents the full per-channel schema. SMS is not a supported channel.

**"📡 Configure channels" button**: a dialog lists 4 channels (Discord/Telegram/WhatsApp/Slack; the others are real but left out of the menu: Signal/iMessage/WebChat and the plugin ones). Tapping a channel opens a simple editor: an "Active channel" switch + a text field for the bot/API token, preloaded if the channel already had a configuration. "Save" writes/updates `channels.<provider> = {"enabled": bool, "token": "..."}` in `~/.openclaw/openclaw.json`; if the channel already existed, a "Delete" button also appears that removes that section from the config.

It reuses the same mechanism as "AI Provider / Model" (`writeProviderEntry()`) — `OpenClawNative.kt` has `channelsList()`/`writeChannelEntry()`/`channelDelete()` with an automatic backup before writing (`openclaw.json.pre-channel-<provider>-<timestamp>`, the same prefix as "Restore backup") and atomic writes via a `.tmp` file + rename. The minimal schema (`enabled`/`token`) covers "enable a channel with its token"; advanced per-provider fields (multi-account, mention gating, fine-grained access control) have no editor of their own — `~/.openclaw/openclaw.json` must be edited by hand.

## 9. Registry

```
openclaw.installed=true
openclaw.version=<real version, or "unknown" if openclaw --version did not respond>
openclaw.install_date=<date>
openclaw.location=nativo_termux
openclaw.port=18789
```

## 10. Gateway token and `gateway.mode=local`

The token lives in `gateway.auth.token` of `~/.openclaw/openclaw.json` and is generated by the `openclaw gateway` process itself on its first start. The only condition that blocks the gateway from starting and self-provisioning its token is that `gateway.mode=local` be present in the config; choosing an AI provider (what the interactive `openclaw onboard` does) is a later, separable step, not a prerequisite for the token.

Other Android OpenClaw wrappers (e.g. `mithun50/openclaw-termux`) had the same symptom ("gateway blocked with 'set gateway.mode=local (current: unset)'") and resolved it by pre-seeding `gateway.mode=local` in `openclaw.json` during initial setup and reinforcing it on every start. Kairos does the same:

1. `modulos/openclaw.sh` pre-seeds `~/.openclaw/openclaw.json` with `gateway.mode=local` and `gateway.auth.mode=token` + a random 24-byte hex token (if the file does not exist, or merges the fields without overwriting anything else), after installing the binary (STEP 3).
2. `OpenClawNative.kt` — `ensureGatewayModeLocal()` (`:57`, idempotent), called at the start of `gatewayStart()`, reinforces the field on every start, covering a config that already existed beforehand.
3. `OpenClawFragment.startGatewayGuarded()` does not block with an "Onboarding pending" dialog: it starts directly. The "⚙ Onboarding" button remains available to configure a real AI provider whenever the user wants.

### Workspace scope

OpenClaw uses its own workspace (`workspaceDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".openclaw/workspace")`, `OpenClawFragment.kt` lines 28-29), separate from `~/proyectos`. To "add a project" (`showProjectsMenu()`, `ProjectActions.kt`), the picker's only sources are: symlink/copy from Downloads, symlink/copy from external storage (any Android folder), or symlink from a project that is ALREADY in `~/proyectos` — there is no option to browse/symlink an arbitrary folder of Termux's `$HOME`. It is a design limitation of Kairos's shared picker, not of upstream OpenClaw: the OpenClaw process has no fixed `cwd` imposed by the binary, and launched manually from any Termux folder via the terminal it works the same.

## 11. Security

- The channel editor dialog preloads the stored token into the `EditText` (`OpenClawFragment.kt:428-431`, `setText(existing.optString("token"))`), i.e. it **shows an already-stored secret**.
- "AI Provider / Model" > "View full config" shows the whole `openclaw.json`, which contains provider `apiKey`s, channel tokens and the gateway token in plain text.
- "Show URL with token" and the WebView use the full URL with `#token=` (by design; the card does mask it). `view.filterTouchesWhenObscured = true` against tapjacking (`:26-29`).
- Neither `openclaw.json` nor its `openclaw.json.pre-*` backups receive `chmod 600` on either path (script `:540` and Kotlin `OpenClawNative.kt:224-231`, `:341`); they depend on `$HOME` isolation.
- Network surface: gateway bound to `loopback`, not exposed to the LAN. No tunnel.
- The custom provider and channel forms write via `org.json` (no shell). `launchTerminalCommand("cd '$path' && openclaw tui")` (`:125,276`) interpolates the path in single quotes; a path containing `'` would break the command (paths come from the folder picker/`ProjectsManager`, not free text; unverified).
- `openclaw update` and `npm install -g openclaw@latest` download without pinning a version or checksum.

## 12. Known limitations

- The installation flow does not validate that the `openclaw` binary runs (it may end up `installed=true` with version `unknown`).
- The global `NODE_OPTIONS` in `~/.bashrc` with `--max-old-space-size=5632` and `--require glibc-compat.js` affects all Node run from that shell (including other modules); the sanitization pattern only exists for the case of a deleted `glibc-compat.js`.
- `modules.json`'s description does not mention channels; the channel editor covers only 4 of them (Signal/iMessage/WebChat/plugins are left out, `OpenClawFragment.kt:389-394`).
- "Install / update" runs `reinstallModuleService` with no way to pass an explicit `--force`.
- No execution tests that `openclaw tui` starts with the bash wrapper over glibc Node on every device (unverified).
