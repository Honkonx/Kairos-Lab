# APP_SCREENS.md — Kairos Screen Reference

> Factual documentation of every screen/interface in the app, based on the real source code.
> When a button or field isn't wired to real logic, it's explicitly marked "not implemented"
> instead of being left out. Line-by-line detail for each module (permissions, installation,
> options, detection) lives in `docs/modulos/<MODULE>.md` — this document is the screen/UI
> view, it doesn't replace those docs.

## 1. Wizard (first launch)

**Files:** `app/src/main/java/com/termux/app/wizard/WizardActivity.java` (host, `ViewPager2`
with swipe disabled) + `WizardPagerAdapter.kt` + 6 `Wizard*Fragment.kt`, one per screen.

Shown the first time the app is opened (as long as `~/.kairos_ready` doesn't exist). There are
6 screens, each its own step:

0. **`WizardWelcomeFragment`** — welcome + summary, "Get started" button.
1. **`WizardPermissionsFragment`** — storage permission
   (`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, mandatory) and notifications (optional, Android
   13+). "Continue" stays disabled until both are resolved.
2. **`WizardPhantomProcessFragment`** — helps disable Android 12+'s phantom process limit, 3
   methods (same engine as Monitor's diagnostic, `PhantomProcessKillerHelper.kt`):
   **nmap auto-detection** first (recommended, less manual data entry — asks for the pairing
   port + code, auto-detects the connection port; falls back to recommending the manual method
   if it fails), **manual code and port** second, **fully manual walkthrough** third. Doesn't
   block — "Continue" is always enabled.
3. **`WizardBatteryFragment`** — "Remove restrictions" button
   (`BatteryRestrictionHelper.requestDisableBatteryRestrictions()`, can open 2 system screens
   back to back — the user is warned upfront). Doesn't block — "Next" is always enabled.
4. **`WizardInstallFragment`** — Termux bootstrap + rootfs (optional) + setup script, with live
   progress. The only screen that can't be exited with "back" (install in progress). The
   rootfs text distinguishes 2 fixed cases: **"Extracting rootfs"** if it's embedded in the
   APK (`RootfsInstaller.isEmbedded()`, no network needed) or **"Downloading and installing
   rootfs"** if it has to be fetched from a Release — if neither works, the "Rootfs
   unavailable" dialog appears, asking whether to fall back to the classic package-by-package
   install; that dialog is exclusive to this screen.
5. **`WizardCheckFragment`** (last, optional) — "Check & update" packages or "Skip", both end
   the wizard and navigate to `TermuxActivity`.

Inside screen 4, it lists 11 numbered steps with a status circle
(pending/in-progress/completed) and a progress bar:

1. Checking permissions
2. Updating Termux
3. Installing core packages
4. Installing compilers
5. Installing glibc
6. Installing multimedia libraries
7. Updating pip
8. Installing global npm packages
9. Configuring theme
10. Creating directory structure
11. Finishing up

Real sequence running underneath (not a 1:1 mapping to the labels above, which are
approximate):
1. `TermuxInstaller.setupBootstrapIfNeeded()` — unpacks Termux's base bootstrap
   (busybox/bash/coreutils/apt/dpkg). Idempotent: if `$PREFIX` already exists and isn't empty,
   it's a no-op. Has a concurrency guard (`sBootstrapLock`/`sBootstrapInProgress`) — a second
   call while the first is still in flight gets queued instead of starting its own extraction,
   to avoid corrupting the result if two wizard screens trigger the bootstrap almost
   simultaneously.
2. `ensureBootstrapSecondStage()` — fires a throwaway login shell (`bash -l -c true`) to force
   Termux's postinst "second stage" (busybox/coreutils/npm/openssh/proot-distro/python-pip/
   termux-exec/etc.), which would otherwise only trigger the first time a real login shell is
   opened.
3. `KairosBootstrap.extractAssetsSync()` — copies scripts from `assets/scripts/` to
   `~/scripts/install/` and `~/scripts/kairos.sh`/`~/kairos_manager.py`.
4. `installRootfsThenContinue()` — embedded or downloaded rootfs (see above); if it fails
   entirely, asks whether to fall back to the classic install.
5. `runKairosSetup()` — runs `~/scripts/kairos.sh --silent`, parsing `[STEP] n/total message` /
   `[OK]` / `[WARN]` / `[ERROR]` lines from its stdout to drive the step-by-step UI.
6. On success (`kairos.sh` exits 0 and creates `~/.kairos_ready`), moves on to screen 5
   (package check).

If `kairos.sh` fails, a "Retry" button appears that repeats steps 2-5 (it does not repeat the
base bootstrap, which should already be done by then).

**A note on path resolution:** inside the wizard, `bash`/`apt` are invoked by absolute path
(`TERMUX_BASH_PATH`/`TERMUX_APT_PATH` in `ProcessBuilderExt.kt`) rather than by relative name —
PATH resolution by relative name isn't 100% reliable right after the bootstrap finishes
extracting. There's also `WizardDebugLog.kt`, a persistent log at
`~/kairos_logs/wizard_debug.log`, active from screen 2 onward, instrumenting every phase of
`TermuxInstaller`/`RootfsInstaller`/the wizard — useful for diagnosing failed installs without
needing to read logcat.

## 2. Modules (main tab)

**Files:** `ModulesFragment.kt`, `ModuleListAdapter.kt`, `item_module_row.xml`,
`BottomSheetInstalacion.kt`, `ModuleController.kt`.

**Stats header:** installed count, active count, RAM used/total (read from `/proc/meminfo`,
refreshed every 5s), "↻ Update" button (runs `git -C ~/termuxapp pull` to self-update the
app's own repo).

**Module list:** the main list only shows installed modules
(`ModulesFragment.pollStatus()` filters by `ModuleInstalled.isInstalled()`, which checks the
`~/.android_server_registry` registry or falls back to checking for the real binary). `python`
always appears (the wizard installs it and `kairos.sh` registers it). If no module is
installed, an empty state (`modules_empty`) is shown with a "Go to Plugins →" button that
navigates to the Store (`TermuxActivity.openPlugins()`). The full catalog is browsable in the
**Store** (More menu → Plugins). Each row shows an icon, name, status subtitle, and:
- If `hasSwitch: true` in `modules.json` (ollama, n8n, openclaw, opencode, remote, db,
  llamaserver, ...): a `SwitchCompat` that starts/stops the real process.
- If `hasSwitch: false` (python, claude, codex, antigravity, hermes, expo, ...): no switch,
  just a status subtitle.
- A "›" chevron always visible, indicating the whole row is tappable.
- **Status badge overlaid on the icon** (a pattern inspired by homelab management panels like
  Proxmox VE): a small colored circle in the bottom-right corner of each row's icon
  (green=running, gray=installed-and-stopped, etc., via
  `ModuleRowRenderer.bindStatusBadge()`/`statusBadgeColor()`), in addition to the existing
  status text — it doesn't replace it, the text subtitle remains the primary information
  source. The same mechanism is reused in the Store (`PluginListAdapter`, section 15 below).

Tapping a row: if not installed → opens the install sheet (`BottomSheetInstalacion`); if
installed → navigates to the module's detail screen.

Status is recomputed every 5s (`pollStatus()`): reads `~/.android_server_registry` (the real
source, written by the bash scripts) to answer "was this ever installed?" (along with
`ModuleInstalled.isInstalled()` falling back to checking the real binary), and
`ModuleController.isRunning()` (tmux has-session, or pgrep for modules without tmux) to
answer "is it running now?".

**Install sheet (`BottomSheetInstalacion`):** shows icon/name/description/chips (size, type,
port, estimated time), a variant selector where applicable (ollama: GPU/standard; claude:
native/legacy; n8n: proot/udocker), an "▼ Install" button (or "Change method" when opened from
the Store for a module already installed). On tapping install: a spinner +
"Installing…"/"Changing method…" (no raw pkg/apt output on screen) while
`~/scripts/install/<id>.sh --silent` runs via `ProcessBuilder`; the full log is written to
`~/kairos_logs/install_<id>.log`. If the module requires proot and it's not installed, the
button changes to "Install proot first" and is disabled.

## 3. Module detail screens

All extend `BaseModuleFragment.kt`, which provides: a header with a back button + name,
`addCard(title) { ... }` (a card with an optional title), `infoRow(key, value)`,
`actionButton(text, style, onClick)` (PRIMARY/DANGER/GHOST styles), `pill(text, isActive)`,
`divider()`, `launchTerminalCommand(cmd)` / `startModuleService()` / `stopModuleService()` /
`isModuleRunning()` / `toast()`. Row components for options panels (see
`docs-en/arquitectura/APP_SPEC.md` § Design system for the full table with examples):
`dropdownSwitchRow()` (pick 1 of N + a switch that locks the dropdown while ON, e.g. n8n
local/Cloudflare), `switchRow()` (simple on/off), `dropdownRow()` (pick 1 of N without a
switch). All of them check `isModuleInstalled()` (reads `<id>.installed` from the real
registry) on entry — if not installed, they show a "Module not installed" screen with a back
button instead of the normal content.

| Module | Info shown | Real actions |
|---|---|---|
| **Ollama** | process, port :11434, version; active model | Start/Restart (real start/stop); "Open AI Chat" navigates to the Chat tab; "Download model" navigates to `ModelsFragment` (a real list via `models-list`, tapping a model opens detail/delete, a button to download a new one via `models-pull`); "Inference parameters" navigates to `OllamaConfigFragment` (loads/saves real parameters via `config-get/set/reset`, genuinely consumed by chat) |
| **n8n** | environment (proot), version, tunnel URL, status pill | Real start/stop; "Open web UI" (starts if needed, then WebView); "View tunnel URL"; "View logs"/"Backup"/"Update" (each opens a terminal session) |
| **OpenClaw** | variant, version, gateway, token, active model | Real start/stop/restart of the gateway; View logs → `LogsFragment`; open web UI (start-if-needed); TUI (`openclaw tui`); Onboarding (`openclaw onboard`); Reinstall/update; "Show URL with token" and "AI Provider/Model" (using real backend commands) |
| **OpenCode** | variant, version, "Web server" pill port 3000 | Terminal TUI (`opencode`); web server (start-if-needed + WebView); Stop server; Reinstall; Import/Sync projects and Manage projects (real backend) |
| **Claude Code** | method, version, status pill | Open in terminal (`claude`); Open in project/Manage projects (lists real projects and opens `claude` with `cd` into the chosen one); Reinstall/change method |
| **Codex CLI** | channel, version, status | Open in terminal (`codex`); `codex login`; Reinstall/change channel |
| **Antigravity CLI** | method, version, status | Open in terminal (`agy`); Reinstall |
| **Python** | version, pip | View version/info, Open REPL (`python3`), Install package (pip, with a text dialog), List packages, Run a .py script |
| **Expo** | EAS CLI version, Node, expo.dev user, active project | Preview/production build, View builds, Login (`eas login` in terminal), Info, Configure active project (selection dialog), Git push |
| **Remote** | SSH/IP/User/Connections, Tunnel — refreshed every 5s | Start/stop SSH, Connection info, Add public key, Change password, Start/stop Cloudflare tunnel, Configure CF token, How to connect |
| **Hermes** | version, gateway, active model | Open TUI (`hermes`), Full wizard (`hermes setup`), Reference commands (static dialog), Configure AI provider / Use local Ollama, Status/diagnostics, Update/Install-reinstall. `HermesGatewayFragment` (Start/Stop/View status/View logs) is also wired to real commands |

**`DbFragment`** (the "Database" `db` module — full detail in `docs/modulos/DB.md`): a STATUS
card (MySQL/MariaDB and PostgreSQL with live `pgrep -x` + registry versions, SQLite with
`sqlite.version`), a SERVERS card (▶ Start / ■ Stop per server via dedicated wrapper
scripts), a SQLITE card with real actions (list DBs under `~`, open an interactive DB with
the `sqlite3` CLI in terminal, view tables, n8n's DB, export to CSV, create an empty DB, run a
SQL query) using `android.database.sqlite.SQLiteDatabase` directly, no Python subprocess. The
module's switch in the list starts/stops both servers together.

**`LogsFragment`**: a log viewer with live search/filter and level-based coloring (`[OK]`/
`[INFO]` blue, `[WARN]` amber, `[ERROR]` red, "✓"/"success" green). Takes a file path as an
argument (`LogsFragment.newInstance(path)`) and reads it with `BufferedReader` (a snapshot on
open, doesn't tail the file live). OpenClaw's "View logs" button navigates here
(`~/openclaw-logs/runtime.log`). n8n's uses `launchTerminalCommand` instead, because it
attaches a live tmux session — that doesn't fit `LogsFragment`'s static-snapshot model.

## 4. Monitor ("More" menu)

**`MonitorFragment`**: live status of modules with a process (ollama/n8n/openclaw/opencode/
remote, via `ModuleController.isRunning()`), network connectivity (wifi/cellular/ethernet type
+ internet validation, via Android's native `ConnectivityManager`), a count of installed
Termux packages and pip packages. Refreshes modules/network every 5s; package counts load
once on entry.

It also includes a **"DEVICE"** section (the screen's first section): a RAM ring
(`Canvas`/`Paint` over `/proc/meminfo`), a storage ring (`StatFs` over
`Environment.getDataDirectory()` — if the "all files" permission is missing, the card becomes
tappable and triggers the storage-permission flow, polling until it's granted), and device
info (local IP via `NetworkInterface`, uptime from `/proc/uptime`, Android API level, ABI
architecture).

**DIAGNOSTICS section — phantom process killer** (see
`docs/modulos/PHANTOM_PROCESS_KILLER.md` for full detail): a row with status ("Android can
kill background modules..." / "Disabled and verified on this device") and a
"Disable"/"Reapply" button. On tap: a silent attempt via `su` (if the device is rooted); if
that fails, a dialog with 3 paths — **(a) Configure automatically (no PC needed)**: a guided
3-step flow via Wireless Debugging (ADB), asking for the pairing port/code/connection port and
applying the necessary commands with real verification; **(b) Auto-detect port (beta)**: same
flow but detects the connection port with `nmap`; **(c) View manual walkthrough**: plain text
+ copyable commands, nothing automated. No path reports success without real verification.

**Bottom nav — 5 fixed tabs** (Modules/Chat/System/Settings/**More**) plus the 5th item, which
opens a menu with the screens that don't fit (Monitor/Files/Tunnel/Processes/Local AI/Cloud).

**`ModelsFragment`** and **`OllamaConfigFragment`**: `ModelsFragment` has a real list of
installed models via `models-list`/`models-pull`/`models-delete` plus a curated catalog of
models to download with one tap (qwen2.5, gemma2, llama3.2 in various sizes), with live real
speed/ETA during download (real streaming from the Ollama API). `OllamaConfigFragment`
loads/saves real inference parameters (`config-get/set/reset`), genuinely consumed by chat.

## 5. AI Chat (tab)

**File:** `ChatFragment.kt`.

Chats directly against the Ollama API (`http://127.0.0.1:11434`), without going through any
intermediate module. On entry, it does a `GET` to that URL (2s timeout) to decide whether to
show the chat UI or an "Ollama inactive" overlay. Model selector (popup menu, hardcoded
models: qwen2.5:0.5b/1.5b, qwen3:4b, gemma3:4b/1b, deepseek-coder:1.3b-instruct, llama3.2:1b —
it doesn't read actually-installed models). Sends messages via `POST /api/generate` with
`stream: true`, parsing each NDJSON line of the response and appending text incrementally to
the assistant's bubble. Cancel button (interrupts the request thread), clear history button,
message counter, error bar.

## 6. Settings (tab)

**File:** `ConfigFragment.kt`.

"General" section: "Auto-start modules" switch — it has real effect, scoped to what the app
can offer without a system boot receiver — when Kairos is opened (not when the phone boots),
`TermuxActivity.onCreate()` calls `ModuleController.autoStartEligibleModules()`, which starts
any module with `hasSwitch:true` that's already installed but stopped; "Battery optimization"
row; "Crashed module notifications" switch — `ModulesFragment`'s poll loop detects
RUNNING→INSTALLED_STOPPED transitions and fires a real local notification if the switch is
on.

"Info" section: architecture (real), and other informational fields.

New **"Environment variables"** section: lists/adds/removes `export KEY=value` variables in
their own isolated block inside `~/.bashrc` (delimited by dedicated markers, never touching
the block written by the setup script) — these only apply to new terminal sessions.

Three maintenance buttons: "Rerun setup" (deletes `~/.kairos_ready` and relaunches the wizard,
without touching already-installed modules); "Full backup" (runs a real backup — a tar.gz of
scripts/registry/.bashrc/module configs to the device's Downloads folder); "Reinstall" (a
"type REINSTALL" confirmation gate, deletes `~/scripts`, the registry, the setup markers, and
relaunches the wizard).

## 7. Terminal overlay

**Files:** `TermuxActivity.java` (`toggleTerminalOverlay()`, `openTerminalWithCommand()`),
layout `activity_termux.xml`, `TerminalBridge.java`, `TermuxActivityRootView.java`.

Opens/closes via the floating FAB over the bottom nav. The first time it's inflated, it sets
up: `TerminalView`, a session drawer (list + new-session button, long-press for a named
session / failsafe mode), an extra-keys toolbar (ESC/TAB/CTRL/arrows, inherited from the
engine), a keyboard-toggle button, a "quick settings" button (a dialog with a font-size
`SeekBar`, applied live and persisted in preferences), and an insets listener that applies
`systemBars()` + `Type.ime()` as padding so the keyboard doesn't cover content.

On show: hides the bottom nav + FAB + current fragment; attaches the first existing session or
creates one if there isn't one. On hide: restores them.

**`openTerminalWithCommand(command)`**: a public method used by
`BaseModuleFragment.launchTerminalCommand()` — makes sure the overlay is visible, creates a
new session, and writes `command + "\n"` to it — so the CLI modules' "Open in terminal"
buttons actually run the command instead of just opening an empty shell.

### 7.1 Terminal — adapted mode

When the terminal is opened for a specific CLI (Claude, OpenCode, Hermes, etc., via
`launchTerminalCommand`) instead of the generic terminal, `activity_termux.xml` switches to a
different visual mode:

- **Adapted top bar** (2 rows): the module's title + a second row with live real status/
  version — "● Active · v1.18.3" or "○ Inactive", refreshed on a background thread.
- **Bottom bar**: if the module has a real server running, shows "⏺ listening on
  http://127.0.0.1:<port>" (a real TCP poll every 500ms) — hidden otherwise. Also wraps the
  inherited extra-keys toolbar.
- **A slide-out sidebar with its own content**, distinct from the normal session-list
  content: Minimize, End session, Restart module, View logs (opens a dialog with real content
  from `~/kairos_logs/`).

This is applied automatically to every CLI module without touching each Fragment
individually — it's a shared mechanism in `TermuxActivity.java`/`activity_termux.xml`.

## 8. ModuleWebViewFragment (generic web UI screen)

**File:** `ModuleWebViewFragment.kt`.

A programmatic WebView (no XML layout of its own) used by n8n, OpenClaw, and OpenCode to show
their local web UI (`webviewUrl` from `modules.json`) inside the app instead of exposing a
raw terminal. Top bar with a back button + title + WebView history back/forward + reload; a
read-only address bar below (shows the current URL, also updates on internal SPA navigation);
a loading progress bar. The system back button navigates the WebView's history first, and only
closes the screen once there's no history left. JavaScript and DOM storage enabled, zoom
supported. It doesn't check beforehand that the server is responding — if it isn't, the
WebView shows Android's standard load error (each fragment that opens it tries to start the
service first if it isn't running, before navigating here). No multiple tabs, no history
persisted across sessions — deliberately out of scope, this isn't a general-purpose browser,
it's a viewer for a single local service at a time.

## 9. Files — CRUD + text editor

`FileManagerFragment.kt`: a long press on a row opens a menu (Copy/Cut/Rename/Delete, plus
"Paste here" if something is on the clipboard) — a single-item clipboard, "move" is cut+paste.
Copy/cut use `copyRecursively()`/`copyTo()` without overwriting; delete asks for confirmation;
rename validates against empty names/collisions. Tapping a text file (known extension, or no
extension and <256KB) navigates to `EditorFragment` instead of just showing name+size.

**`EditorFragment.kt`**: a real text editor built on `io.github.rosemoe.sora.widget.CodeEditor`
(the `sora-editor` library, LGPL-2.1, added as a Gradle dependency without modifying its
code). Loads the file with `File.readText()`, "Save" writes with `writeText()`, confirms
before exiting if there are unsaved changes, rejects files >5MB or nonexistent ones. Real
syntax highlighting for 12 languages (Java, Kotlin, Python, XML, HTML, JS, TS, Markdown,
JSON, YAML, shell, CSS) via real TextMate grammars, `darcula.json` theme. No binary file
support.

## 10. Tunnel ("More" menu)

**File:** `TunnelFragment.kt`. One card per module with a known port (Ollama :11434, n8n
:5678, OpenClaw :18789, OpenCode :3000): service status (is the module running?) and tunnel
status (no tunnel / starting / active with a URL), a "Start tunnel" button (anonymous
cloudflared quick tunnel, no account), a "With token" button (an authenticated named tunnel,
asks for a Cloudflare token via a dialog), and a "Stop" button. On start, it polls `tunnel
status` every 2s for up to ~14s waiting for the URL to appear in cloudflared's log. The first
time a tunnel is started in the app's session: a warning dialog ("this exposes the module to
the internet without authentication").

Uses a generic backend (`tunnel start/stop/status/list`, by port) — it doesn't replace the
cloudflared tunnels that already existed for n8n (inside `N8nFragment`/`modulos/n8n.sh`, with
its own URL file) or for Remote/SSH (its own tmux session) — those keep working the same way
from their own screens. This is a new, independent, unified control surface for any module
with a port.

## 11. Processes ("More" menu)

**File:** `ProcesosFragment.kt`. Lists processes managed by **pm2** (already installed as part
of the wizard, among the global npm packages). Runs `pm2 jlist` directly via `ProcessBuilder`,
without going through Python. It explicitly distinguishes "pm2 isn't in PATH" (a reinstall
message) from "pm2 is present but the command failed" (a possibly-crashed daemon). It isn't a
`modules.json` module (no switch of its own, no install screen) — it's assumed to already be
installed by the bootstrap.

## 12. Local AI ("More" menu, embedded llama.cpp)

**File:** `LocalAIFragment.kt`. Manages GGUF models and parameters for the embedded inference
engine (`llama-engine/`, llama.cpp directly via NDK — see
`docs/ia-local/LLAMA_CPP_EMBEBIDO.md`) — an engine kept deliberately separate from Ollama: chat
uses a single model selector that lists both Ollama's remote models and local GGUF ones, but
which engine actually answers is decided by which one was picked, never mixed within the same
conversation.

**Curated catalog** (6 real models, with direct HuggingFace URLs): Qwen2.5-0.5B-Instruct,
SmolLM2-1.7B-Instruct, Qwen2.5-1.5B-Instruct, Llama-3.2-1B-Instruct, Gemma-2-2B-it,
Llama-3.2-3B-Instruct — all Q4_K_M/q4_k_m quantization. Download with live real speed/ETA
(same pattern as Ollama's catalog). The screen's own copy reads: *"Inference with embedded
llama.cpp — no Termux, no network once the model is downloaded. Models from here also show
up in chat."*

## 13. Cloud ("More" menu)

**File:** `NubeFragment.kt`. Turns the phone into a minimal Drive/Mediafire-style storage
cloud, scoped to ONE fixed folder (`$HOME/nube`) and reachable from any browser on the local
network, not just from the app. Reuses two existing pieces: **`NubeServer`** (a hand-rolled
embedded HTTP server, token-gated, with path-traversal validation) and **`TunnelManager`**
(the same cloudflared/ngrok logic used by `TunnelFragment`, pointed at `NubeServer`'s port
instead of a module). The file list uses the same in/out navigation mechanism as
`FileManagerFragment` but scoped to the cloud folder — the user can't navigate outside it or
see the rest of the phone from here. It's its own screen because it needs server/tunnel
controls that `FileManagerFragment` doesn't have.

## 14. GenericModuleFragment (generic module detail screen)

**File:** `app/src/main/java/com/termux/app/ui/GenericModuleFragment.kt`.

A metadata-driven module detail screen: instead of one class per module, this fragment
renders the full sub-menu of any module from `ModuleInfo` (`modules.json`) + the real registry
state. It's the `else` branch of the dispatch in
`ModulesFragment.navigateToModuleDetail()`: modules with a specific UI (Ollama, N8n, Claude,
...) keep their dedicated fragment; any new module added to the catalog lands here without
writing new Kotlin.

Cards/actions it draws automatically (based on what the module declares in `modules.json`):

- **STATUS** — ID, real registry version, port, type, execution mode (native in gray / proot
  in amber), tmux session, a terminal-TUI pill (if it has `terminalCommand`), and a
  running/stopped server pill (polled on a background thread).
- **WHAT IT IS** — the catalog's `description`.
- **CONTROL** (only if `hasSwitch=true`) — ▶ Start / ■ Stop server.
- **🌐 Open web UI** (if `webviewUrl`) — navigates to `ModuleWebViewFragment`.
- **⌨ Open in terminal** (if `terminalCommand`) — `launchTerminalCommand()` with the module's CLI.
- **DETAILS** — size and install-time estimate, if available.
- **MAINTENANCE** — 🔄 Update and 🗑 Uninstall (with a confirmation dialog that stops the
  module and removes scripts/checkpoints/registry entries, without touching shared packages).

## 15. Plugins — module store ("More" menu)

**File:** `PluginsFragment.kt` (+ `PluginListAdapter.kt`, layouts `fragment_plugins.xml` /
`item_plugin_row.xml`). Lists the full plugin/module catalog from `ModuleCatalog` (bundled +
cache + hybrid remote refresh).

- **Ordering**: recommended items first, sorted by downloads desc; the rest after, also by
  downloads.
- **Search**: by name / id / description / category.
- **Badges**: ★ Recommended (green), architecture (bionic/glibc/proot/proot-distro,
  color-coded by type), category, ⬇ downloads.
- **Real status**: `ModuleRegistry` + `ModuleController.isRunning()` — polled on re-entry to
  the screen and after every action.
- **Per-status actions**: Install (`BottomSheetInstalacion`), Uninstall (confirmation), Open
  (navigates to the corresponding detail screen).
- **"↻ Catalog"**: refreshes the remote catalog with a silent fallback to cache/bundled data.
- Starting/stopping stays on the detail screen (same pattern as the rest of the app).

## 16. X11 — functionality merged into Mini PC

The embedded X11 server (Xlorie, `:x11-server`) no longer has a screen of its own — it lives
entirely inside **`EntornoFragment.kt`** (the "Mini PC" tab, see section 17), in the "NATIVE —
X11 + direct desktop" section:

- **Server status**: checks whether the `:xserver` process is alive and shows "● Server
  active" / "○ Server stopped" + display `:1`. Refreshes on re-entry to the screen.
- **🚀 Enter X11**: starts the X11 service + opens the viewer
  (`com.termux.x11.MainActivity`, LorieView).
- **⚙ X11 configuration**: opens `LoriePreferences` — termux-x11's original preferences screen
  with resolution mode (native/scaled/exact/custom), display scale, density, stretch, forced
  horizontal/vertical orientation, fullscreen, notch hiding, PiP, extra keyboard, touch mode,
  sensitivity, and more. Changes apply live via broadcast.
- **✕ Close X11 server**: confirms → closes the viewer + stops the X11 service.
- **Viewer back = Minimize/Close dialog**: `MainActivity.onBackPressed()` shows a dialog with
  "Minimize" (the server keeps running) and "Close X11 server" (same contract as the ✕ above).

## 17. Mini PC (Environment) — main tab

**File:** `EntornoFragment.kt` (extends `BaseModuleFragment`, layout
`fragment_module_detail.xml`, logic in `EntornoNative.kt`). Its own tab on the
`BottomNavigationView`, with an alternate entry point from the Modules catalog.

- **`nav_minipc` item** (`bottom_nav_menu.xml`, a dedicated icon, "Mini PC" title) — given
  `BottomNavigationView`'s hard limit of 5 items, X11's functionality was merged into this tab
  (the "NATIVE — X11 + direct desktop" section from section 16 above) rather than living as
  its own secondary screen. Entorno/X11 share the same desktop ecosystem
  (`EntornoNative.startDesktop()`/`startDistroDesktop()` already start the X11 service and
  open the embedded viewer directly).
- **Dual access path**: searching for "Entorno" from the Modules catalog also opens the same
  screen (with a real backstack, not as the root tab).
- **`BaseModuleFragment.showBackButton`**: as the root tab, `EntornoFragment` is added
  directly to the fragment container (not via the backstack) — the header's "←" arrow, which
  by default assumes the fragment was opened via `addToBackStack()`, is disabled here
  (`showBackButton = false`) because there's nowhere to go back to. The rest of the modules,
  which only ever open via the backstack, keep the default behavior.
