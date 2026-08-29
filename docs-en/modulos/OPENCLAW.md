# OpenClaw — Kairos Module

## 1. What it is

OpenClaw is an AI agent gateway (npm package, `openclaw@latest`) with its own web UI and TUI,
compatible with multiple providers (local Ollama, cloud providers). In Kairos it runs
**exclusively in native mode** (glibc + npm) — it requires neither proot nor a full Linux distro.

Fixed port: `18789`. Estimated install size: ~60MB.

## 2. Permissions

Requires no Android-specific permissions — it uses Termux's existing infrastructure (storage,
process execution) that the app already requests during the initial setup wizard. No audio
recording, push notifications, or module-specific manifest permissions are required.

## 3. Installation architecture — 100% native, no proot

```
Native Termux (no proot)
  ~/.npm-global/bin/openclaw          → the real npm package
  ~/.openclaw-android/bin/node        → bash wrapper → glibc loader → real node
  ~/.openclaw-android/bin/npm, npx    → equivalent wrappers
  ~/.openclaw/glibc-compat.js         → --require in NODE_OPTIONS (fixes os.networkInterfaces/homedir)
```

The official Node.js `linux-arm64` build runs on top of Termux's glibc compatibility ELF loader,
without ever entering a proot environment. OpenClaw requires Node 22.22.3+, 24.15+, or 25.9+
(Node 23 is unsupported, per the project's own documentation).

## 4. Installation logic (step summary)

The installer accepts `--silent` (no prompts) and `--force` (reinstalls even if already
present).

1. **glibc + Node infrastructure** — if a Node build meeting the minimum version (system-wide or
   from a prior wrapper) with working npm already exists, it's reused. Otherwise, it installs the
   glibc compatibility layer, downloads the official `linux-arm64` Node build, and generates
   `node`/`npm`/`npx` wrappers that sanitize any inherited `NODE_OPTIONS` before invoking the
   real binary.
2. **Node + npm verification** — a real minimum-version check (major.minor.patch, not just the
   major version).
3. **OpenClaw installation** — `npm install -g openclaw@latest`, allowing the package's own
   postinstall script to apply its real hotfix to the `baileys` library (a fix for a promise race
   and an Undici-incompatible dispatcher in media downloads, documented by the OpenClaw project
   itself). Before skipping this step via checkpoint, the installer verifies the package still
   exists on disk — this avoids endlessly reinstalling if the checkpoint says "done" but the
   package has disappeared.
4. **Android patches** — `glibc-compat.js` (fixes `os.networkInterfaces()` and `os.homedir()`),
   a stub for the native `koffi` module (not compiled for `android-arm64`), a `clipboardy` stub,
   and `/tmp` → `$HOME/tmp` path patches inside the installed bundle.
5. **Control scripts** — generates start/stop scripts.
6. **Aliases** — adds a block to `~/.bashrc` (`openclaw-start`, `openclaw-stop`,
   `openclaw-status`, `openclaw-tui`).
7. **State registry** — updates Kairos's internal module registry. Reading `openclaw --version`
   for this step is failure-protected: if the command doesn't respond for any reason, the
   registry is still updated with an `"unknown"` version value instead of aborting the whole
   installation (a real bug that was fixed — previously, a transient failure reading the version
   could leave the module marked "not installed" even though the rest of the install had
   completed correctly on disk).
8. **Cleanup** — removes the temporary checkpoint file.

## 5. Status detection

The module starts/stops via a real `tmux` session (session name: `openclaw`). "Running" is
determined via `tmux has-session` or by polling port `18789`, not just by reading the internal
registry.

## 6. App screen

OpenClaw uses its own dedicated workspace at `$HOME/.openclaw/workspace` — separate from the
shared projects folder used by other coding CLIs integrated into Kairos.

Main controls:

| Control | Action |
|---|---|
| Gateway switch | Starts/stops the process — runs the start/stop scripts |
| Restart gateway | Stops and starts again |
| View logs | Shows the gateway's runtime log |
| Show URL with token | Builds the full URL with the auth token (`http://localhost:18789/#token=...`), read from the config file |
| Open web interface (local) | Starts the gateway first if not running, then opens the interface in an internal WebView |
| Open TUI (terminal) | Opens the interactive text interface |
| Onboarding | Runs the interactive initial setup wizard |
| AI provider / model | View and edit provider configuration |
| Configure channels | Dialog to enable/edit Discord, Telegram, WhatsApp, and Slack — bot/API token and active state per channel |
| Manage workspaces | Import/symlink/delete/sync workspaces |
| Install / update | Full reinstallation |

## 7. Runtime — generated scripts

The start script:
- Exits immediately if the gateway already responds on `:18789`.
- Kills any previous process/session and starts a fresh tmux session.
- Supports a "no-wait" mode (fire and exit without blocking).
- By default, performs a real HTTP health check against the port before reporting success (not
  just confirming the tmux session exists).
- Computes the Node heap size dynamically as a percentage of the device's available memory
  (clamped to a sane range) instead of using a fixed value — this prevents the system from
  killing the process for running out of memory right when Node loads the full bundle for the
  first time.

The stop script kills any OpenClaw process/session and confirms via an HTTP request that the
gateway no longer responds.

## 8. Configuration — `~/.openclaw/openclaw.json`

This is OpenClaw's real, current configuration file (confirmed against the project's official
documentation). Each messaging channel (Discord, Telegram, WhatsApp, Slack, Signal, iMessage,
WebChat, and several more via plugins) has its own section under `channels.<provider>` in that
same file.

Kairos's channel editor lets you enable/disable a channel and save its token, with an automatic
backup of the config file before every write and atomic writes (temp file + rename). The
implemented scope covers the most common use case (enabling a channel with its token) — advanced
per-provider fields (multi-account, fine-grained access control) don't have a dedicated editor in
the app; for those, edit `~/.openclaw/openclaw.json` directly.

## 9. Known gotcha: `gateway.mode=local`

OpenClaw's gateway only needs the `gateway.mode` field set to `"local"` inside `openclaw.json` to
start and auto-generate its own auth token (`gateway.auth.token`) — choosing an AI provider is a
separate, later step, not an actual prerequisite for the token. Kairos pre-seeds this field
automatically during installation (and reinforces it on every gateway start) so the user doesn't
need to run the full onboarding wizard just to get the token and access the web interface.

## 10. Workspace scope

OpenClaw does not have arbitrary access to the whole Termux filesystem — it only uses its own
dedicated workspace (`$HOME/.openclaw/workspace`). The app's project manager lets you import
content from Downloads, from external storage, or via symlinks from another already-imported
project — it doesn't let you browse and link an arbitrary folder from the Termux home directory
directly.
