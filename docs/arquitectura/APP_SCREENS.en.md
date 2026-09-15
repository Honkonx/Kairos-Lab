# APP_SCREENS.md — Kairos screen reference

> What each screen of the app is and does: purpose, main controls, and which
> modules/features it exposes. The line-by-line detail of each module (permissions, installation,
> options, detection) lives in `docs/modulos/<MODULE>.md` — this is the screen/UI view.

## 1. Wizard (first launch)

**Files:** `app/src/main/java/com/termux/app/wizard/WizardActivity.java` (host, `ViewPager2`
with swipe disabled) + `WizardPagerAdapter.kt` + several `Wizard*Fragment.kt`, one per screen.

Shown the first time the app is opened (while `~/.kairos_ready` doesn't exist). Each step
is an independent screen:

0. **`WizardWelcomeFragment`** — welcome + summary, "Get started" button.
1. **`WizardPermissionsFragment`** — storage permission (`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`,
   mandatory) and notifications (optional, Android 13+). "Continue" stays disabled until
   both are resolved.
2. **`WizardPhantomProcessFragment`** — removes Android 12+'s phantom process limit,
   with 3 methods (same engine as Monitor's diagnostic,
   `PhantomProcessKillerHelper.kt`): **auto-detection with nmap** first (recommended, less
   manual data — asks for the pairing port + code, auto-detects the connection port;
   falls back to recommending the manual method if it fails), **manual code and port** second,
   **100% manual tutorial** third. Doesn't block the wizard from advancing.
3. **`WizardBatteryFragment`** — "Remove restrictions" button
   (`BatteryRestrictionHelper.requestDisableBatteryRestrictions()`, can open 2 system screens
   in a row — the user is warned upfront). Doesn't block advancing.
4. **`WizardInstallFragment`** — Termux bootstrap + rootfs (optional) + `kairos.sh`, with
   live progress. This is the only screen that can't be dismissed with "back" (a process is
   in progress). The rootfs text distinguishes 2 cases: **"Extracting rootfs"** if it's
   embedded in the APK (`RootfsInstaller.isEmbedded()`, no network) or **"Downloading and
   installing rootfs"** if it needs to be pulled from a Release — if neither works, a "Rootfs
   not available" dialog appears asking whether to use the classic installation (package by
   package).
5. **`WizardCheckFragment`** (last, optional) — "Check for updates" or "Skip", both of them
   end the wizard and navigate to `TermuxActivity`.

Inside the installation screen, 11 numbered steps are listed with a status circle
(pending/in progress/completed) and a progress bar: checking permissions, updating
Termux, installing core packages, compilers, glibc, multimedia, updating pip, installing
global npm packages, configuring theme, creating structure, finishing.

Real sequence in the background:
1. `TermuxInstaller.setupBootstrapIfNeeded()` — extracts Termux's base bootstrap
   (busybox/bash/coreutils/apt/dpkg). Idempotent: if `$PREFIX` already exists and isn't empty,
   it does nothing.
2. `ensureBootstrapSecondStage()` — fires a disposable login shell (`bash -l -c true`)
   to force Termux's postinst "second stage" (busybox/coreutils/npm/openssh/
   proot-distro/python-pip/termux-exec/etc.), which otherwise only triggers when a real
   login shell is opened.
3. `KairosBootstrap.extractAssetsSync()` — copies scripts from `assets/scripts/` to
   `~/scripts/install/` and `~/scripts/kairos.sh`/`~/kairos_manager.py`.
4. `installRootfsThenContinue()` — embedded or downloaded rootfs; if it fails entirely, asks
   whether to use the classic installation.
5. `runKairosSetup()` — runs `~/scripts/kairos.sh --silent`, parsing
   `[STEP] n/total message` / `[OK]` / `[WARN]` / `[ERROR]` lines from its stdout to update the UI
   step by step.
6. On successful completion (`kairos.sh` exits 0 and creates `~/.kairos_ready`), it moves on to
   the package-check screen.

If `kairos.sh` fails, a "Retry" button appears that repeats steps 2-5 (it doesn't repeat the
base bootstrap, which should already be done). The bootstrap has a concurrency guard
(`sBootstrapLock`/`sBootstrapInProgress`) to prevent two simultaneous calls from running the
same extraction in parallel over the same directory.

## 2. Modules (main tab)

**Files:** `ModulesFragment.kt`, `ModuleListAdapter.kt`, `item_module_row.xml`, `BottomSheetInstalacion.kt`, `ModuleController.kt`.

**Stats header:** installed (count), active (count), RAM used/total (read from
`/proc/meminfo`, refreshed every 5s), "↻ Update" button (runs `git -C ~/termuxapp pull` to
self-update the app's own repo).

**Module list:** the main list shows **only installed modules**
(`ModulesFragment.pollStatus()` filters by `ModuleInstalled.isInstalled()`, which checks the
`~/.android_server_registry` registry or the real binary with `BINARY_FALLBACK`). `python` always
appears (the wizard installs it and `kairos.sh` registers it). If no module is installed, an
**empty state** is shown (`modules_empty`) with a "Go to Plugins →" button that navigates to the
Store (`TermuxActivity.openPlugins()`). The full catalog is visible in the **Store** (More menu →
Plugins). Each row shows an icon, name, status subtitle, and:
- If the module has a real server (ollama, n8n, openclaw, opencode, remote, db, llamaserver):
  a `SwitchCompat` that starts/stops the real process.
- If it has no switch (python, claude, codex, antigravity, hermes, expo): no switch, just
  a status subtitle.
- A chevron "›" always visible, indicating the whole row is tappable.
- **Status badge overlaid on the icon**: a small colored circle in the icon's bottom-right
  corner (green=running, gray=installed and stopped, etc., via
  `ModuleRowRenderer.bindStatusBadge()`/`statusBadgeColor()`), in addition to the status text —
  it doesn't replace it. The same mechanism is reused in the Store (`PluginListAdapter`).

Tapping the row: if not installed → opens the install sheet (`BottomSheetInstalacion`); if
installed → navigates to the module's detail screen.

Status is recomputed every 5s (`pollStatus()`): it reads `~/.android_server_registry` (the real
source, written by the bash scripts) to know "was this ever installed?", and
`ModuleController.isRunning()` (tmux has-session, or pgrep for modules without tmux) to know
"is it running now?".

**Install sheet (`BottomSheetInstalacion`):** shows icon/name/description/chips
(size, type, port, estimated time), a variant selector when applicable (ollama: GPU/standard;
claude: native/legacy; n8n: proot/udocker), an "▼ Install" button (or "Change method" when
opened in forced mode from the Store). On tapping install: spinner + "Installing…"/"Changing
method…" (no raw pkg/apt output on screen) while
`~/scripts/install/<id>.sh --silent` runs via `ProcessBuilder`; the full log is written to
`~/kairos_logs/install_<id>.log`. If it requires proot and proot isn't installed, the button
changes to "Install proot first" and stays disabled.

## 3. Module detail screens

All of them extend `BaseModuleFragment.kt`, which provides: a header with a back button + name,
`addCard(title) { ... }` (a card with optional title), `infoRow(key, value)`,
`actionButton(text, style, onClick)` (PRIMARY/DANGER/GHOST styles), `pill(text, isActive)`,
`divider()`, `launchTerminalCommand(cmd)` / `startModuleService()` / `stopModuleService()` /
`isModuleRunning()` / `toast()`. Row components for options panels (see
`docs/arquitectura/APP_SPEC.en.md` § Design system for the full table with examples):
`dropdownSwitchRow()` (pick 1 of N + a switch that locks the dropdown while ON, e.g. n8n
local/Cloudflare), `switchRow()` (simple on/off), `dropdownRow()` (pick 1 of N without a
switch). All of them check `isModuleInstalled()` (reads the install flag from the real registry)
on entry — if it's not installed, they show a "Module not installed" screen with a back button,
instead of the normal content.

| Module | Info shown | Real actions |
|---|---|---|
| **Ollama** | process, port :11434, version, active model | Start/Restart; "Open AI Chat" navigates to the Chat tab; "Download model" navigates to `ModelsFragment` (a real list via `models-list`, tapping a model opens details/delete, a button to download a new one via `models-pull`); "Inference parameters" navigates to `OllamaConfigFragment` (loads/saves real parameters via `config-get/set/reset`, used by the chat) |
| **n8n** | environment (proot), version, tunnel URL, status | Real start/stop; "Open web interface" (starts if needed, then WebView); "View tunnel URL"; "View logs"/"Backup"/"Update" (each opens a terminal session) |
| **OpenClaw** | variant, version, gateway, token, active model | Real start/stop/restart of the gateway; view logs; open web interface (start-if-needed); TUI (`openclaw tui`); Onboarding (`openclaw onboard`); reinstall/update; show URL with token, AI provider/model |
| **OpenCode** | variant, version, "Web server" pill port 3000 | TUI in terminal (`opencode`); web server (start-if-needed + WebView); stop server; reinstall; import/sync and manage projects |
| **Claude Code** | method, version, status | Open in terminal (`claude`); open in project/manage projects (lists real projects and opens `claude` with `cd` into the chosen project); reinstall/change method |
| **Codex CLI** | channel, version, status | Open in terminal (`codex`); `codex login`; reinstall/change channel |
| **Antigravity CLI** | method, version, status | Open in terminal (`agy`); reinstall |
| **Python** | version, pip | View version/info, open REPL (`python3`), install package (pip, with a text dialog), list packages, run a .py script |
| **Expo** | EAS CLI version, Node, expo.dev user, active project | Build preview/production, view builds, login (`eas login` in terminal), info, configure active project, git push |
| **Remote** | SSH/IP/User/Connections, Tunnel — refreshed every 5s | Start/stop SSH, connection info, add public key, change password, start/stop Cloudflare tunnel, configure CF token, how to connect |
| **Hermes** | version, gateway, active model | Open TUI (`hermes`), full wizard (`hermes setup`), reference commands, configure AI provider / use local Ollama, status/diagnostics, update/install-reinstall. `HermesGatewayFragment` (start/stop/view status/view logs) |

**`DbFragment`** (the "Database" module — see `docs/modulos/base-de-datos.md`): a STATUS card (MySQL/MariaDB
and PostgreSQL with live checks + versions from the registry, SQLite with its own version),
a SERVERS card (▶ Start / ■ Stop per server), a SQLITE card to list databases in `~`, open a
database interactively with the `sqlite3` CLI in the terminal, view tables, n8n's database,
export to CSV, create an empty database, run a SQL query — using
`android.database.sqlite.SQLiteDatabase` directly. The module's switch in the list starts/stops
both servers together.

**`LogsFragment`**: a log viewer with live search/filtering and level-based coloring
(`[OK]`/`[INFO]` blue, `[WARN]` amber, `[ERROR]` red, "✓"/"success" green). It receives a file
path as an argument and reads it with `BufferedReader` (a snapshot on open, doesn't tail the file
live). OpenClaw's "View logs" button navigates here; n8n's attaches a live tmux session
directly from the terminal instead, since it isn't a static file.

## 4. Monitor (tab)

**`MonitorFragment`**: live status of modules with a process (ollama/n8n/openclaw/opencode/
remote, via `ModuleController.isRunning()`), network connectivity (wifi/data/ethernet type +
internet validation, via Android's native `ConnectivityManager`), count of installed Termux
packages and pip packages. Refreshes modules/network every 5s; package counts are
loaded once on entry.

Includes a **"DEVICE"** section with a RAM ring (`Canvas`/`Paint` over
`/proc/meminfo`), a storage ring (`StatFs` over `Environment.getDataDirectory()` —
if the "all files" permission is missing, the card becomes tappable and triggers the
storage settings screen, polling until it's granted), and device info (local IP, uptime,
Android API version, ABI architecture).

**DIAGNOSTICS section — phantom process killer** (see `docs/modulos/PHANTOM_PROCESS_KILLER.md`
for the full detail): a row with a status label ("Android can kill background modules..." /
"Disabled and verified on this device") and a "Disable"/"Reapply" button. On tap: a silent
attempt via `su` (if the device is rooted); if that fails, a dialog with 3 paths —
**(a) Configure automatically (no PC)**: a guided 3-step flow via Wireless
debugging (ADB), asking for the pairing port/code/connection port, applies the needed
system settings and background-survival commands, with real verification
before confirming success; **(b) Auto-detect port (beta)**: same flow but only asks for the
pairing port+code, detects the connection port with `nmap`; **(c) View manual
tutorial**: text and copyable commands only, nothing automated. No path reports success
without real verification (settings are re-read after applying).

**`ModelsFragment`** and **`OllamaConfigFragment`**: see the Ollama table above. `ModelsFragment`
has a real list of installed models via `models-list`/`models-pull`/`models-delete` plus a
curated catalog of one-tap downloadable models (qwen2.5, gemma2, llama3.2 in various
sizes), with real live speed/ETA during download (streaming from the Ollama API).
`OllamaConfigFragment` loads/saves real inference parameters, consumed by the chat.

## 5. AI Chat (tab)

**File:** `ChatFragment.kt`.

Direct chat against the Ollama API (`http://127.0.0.1:11434`), without going through any
intermediate module. On entry, it makes a `GET` request to that URL (2s timeout) to decide
whether to show the chat interface or an "Ollama inactive" overlay. Model selector (popup
menu). Sends messages via `POST /api/generate` with `stream: true`, parsing each NDJSON line
of the response and appending the text incrementally to the assistant's bubble. Cancel button
(interrupts the request thread), clear history button, message counter, error bar.

## 6. Settings (tab)

**File:** `ConfigFragment.kt`.

"General" section: "Auto-start modules" switch — when Kairos is OPENED (not on device
boot, there's no system boot receiver), `TermuxActivity.onCreate()` calls
`ModuleController.autoStartEligibleModules()`, which starts any switch-enabled module that's
already installed but stopped; "Battery optimization" row; "Notify on crashed modules"
switch — `ModulesFragment`'s polling loop detects RUNNING→INSTALLED_STOPPED
transitions and fires a local notification if the switch is enabled.

"Info" section: architecture (real), Kairos version.

**"Environment variables"** section: list/add/remove `export KEY=value` variables in its
own isolated block inside `~/.bashrc` (delimited by dedicated markers) — applies
only to new terminal sessions.

Buttons: "Rerun setup" (deletes `~/.kairos_ready` and relaunches the wizard, without touching
already-installed modules); "Full backup" (a real tar.gz of scripts/registry/.bashrc/module
configs to the device's downloads folder); "Reinstall" (a confirmation gate requiring typing
"REINSTALAR", deletes scripts/registry/checkpoints and relaunches the wizard).

## 7. Terminal overlay

**Files:** `TermuxActivity.java` (`toggleTerminalOverlay()`, `openTerminalWithCommand()`), layout `activity_termux.xml`, `TerminalBridge.java`, `TermuxActivityRootView.java`.

Opens/closes with the floating FAB over the bottom nav. The first time it's inflated, it
sets up: `TerminalView`, a session drawer (list + new session button, long-press for a named
session / failsafe mode), an extra-keys toolbar (ESC/TAB/CTRL/arrows, inherited from
termux-app), a keyboard toggle button, a "quick settings" button (a dialog with a font-size
`SeekBar`, applied live and persisted in preferences), and inset handling that respects the
system bar and on-screen keyboard as padding.

On show: it hides the bottom nav + FAB + current fragment; it attaches the first existing
session or creates one if there is none. On hide: it restores them.

**`openTerminalWithCommand(command)`**: a public method used by
`BaseModuleFragment.launchTerminalCommand()` — makes sure the overlay is visible, creates a
new session and writes `command + "\n"` to it — so CLI modules' "Open in terminal" buttons
run the command instead of just opening an empty shell.

### 7.1 Terminal — adapted mode

When the terminal is opened for a specific CLI (Claude, OpenCode, Hermes, etc., via
`launchTerminalCommand`) instead of the generic terminal, the screen switches to a
different visual mode:

- **Adapted top bar** (2 rows): the module's title + a second row with real live
  status/version — "● Active · v1.18.3" or "○ Inactive", refreshed on a background thread.
- **Bottom bar**: if the module has a real server running, it shows "⏺ listening on
  http://127.0.0.1:<port>" (a real TCP poll every 500ms) — hidden if not applicable. It also
  wraps the inherited extra-keys toolbar.
- **Slide-out sidebar with its own content** (different from the normal drawer, which
  remains the generic session list): Minimize, Close session, Restart module, View logs
  (opens a dialog with real content from `~/kairos_logs/`).

This applies automatically to **every** module with a CLI without touching each Fragment
individually — it's a mechanism shared inside `TermuxActivity.java`/`activity_termux.xml`.

## 8. ModuleWebViewFragment (generic web-interface screen)

**File:** `ModuleWebViewFragment.kt`.

A programmatic WebView (no XML of its own) used by n8n, OpenClaw, and OpenCode to show their
local interface (`webviewUrl` from `modules.json`) inside the app instead of exposing the raw
terminal. A top bar with a back button + title + WebView history back/forward + reload; a
read-only address bar below it (also updated on internal SPA navigation); a loading progress
bar. The system back button navigates the WebView's history first, and only closes the
screen once there's no more history. JavaScript and DOM storage are
enabled, zoom is supported. Each fragment that invokes it tries to start the service first if
it isn't running, before navigating here. No multiple tabs or history persisted between
sessions — deliberately out of scope, it's a viewer for a single local service at a time, not
a general browser.

## 9. Files — CRUD + text editor

`FileManagerFragment.kt`: long-pressing a row opens a menu (Copy/Cut/Rename/
Delete, plus "Paste here" if there's something in the clipboard) — a single-item
clipboard, "move" is cut+paste. Copy/cut don't overwrite; delete asks for confirmation; rename
validates an empty name/collision. Tapping a text file (known extension, or no extension and
<256KB) navigates to `EditorFragment` instead of just showing name+size.

Additional functions on the listing:

- **Multiple selection**: a selection mode with a batch action bar (copy, cut, compress, delete
  several files at once).
- **Bookmarks**: any folder can be bookmarked (persisted), with a dialog to jump straight to any
  saved one.
- **Browser-style navigation history** — back/forward buttons between visited folders, not just
  "go up one level".
- **Show/hide hidden files** with a toggle.
- **Real "Open with"** — hands a file off to another installed app capable of handling it, via a
  dedicated content provider (never exposes raw file paths outside the app).

**`EditorFragment.kt`**: a real text editor on top of `io.github.rosemoe.sora.widget.CodeEditor`
(the `sora-editor` library, LGPL-2.1). Loads the file, "Save" writes the changes, confirms
before leaving if there are unsaved changes, rejects files >5MB or nonexistent ones. Real
syntax highlighting for 12 languages (Java, Kotlin, Python, XML, HTML, JS, TS, Markdown, JSON, YAML,
shell, CSS) via TextMate grammars, Darcula theme. For `.md` files there's a preview button that
renders the Markdown (via `Markwon`, already present as a project dependency) instead of showing
raw text. No binary file support.

## 10. Tunnel ("More" menu)

**File:** `TunnelFragment.kt`. One card per module with a known port (Ollama :11434, n8n
:5678, OpenClaw :18789, OpenCode :3000): service status (is the module running?) and
tunnel status (no tunnel / starting / active with URL), an "Start tunnel" button (anonymous
cloudflared quick-tunnel, no account needed), a "With token" button (a named authenticated
tunnel, prompts for a Cloudflare token via a dialog), a "Stop" button. On start, it polls the
status every 2s for up to ~14s waiting for the URL to appear. The first time a tunnel is
started in the app session: a warning dialog ("this exposes the module to the internet
without authentication").

It's a unified control surface, independent of the tunnels n8n and Remote/SSH already have
from their own detail screens.

## 11. Processes ("More" menu)

**File:** `ProcesosFragment.kt`. Lists the processes managed by **pm2** (already installed
as part of the wizard). Runs `pm2 jlist` directly via `ProcessBuilder`, independent of Python.
It explicitly distinguishes "pm2 isn't on the PATH" (a reinstall message) from "pm2 is present
but the command failed" (a possibly crashed daemon). Not a `modules.json` module — assumed
already installed by the bootstrap.

## 12. Local AI ("More" menu, embedded llama.cpp)

**File:** `LocalAIFragment.kt`. Manages GGUF models and parameters for the embedded
inference engine (`llama-engine/`, llama.cpp directly via NDK — see
`docs/ia-local/llama-cpp-local-engine.md`) — a deliberately separate engine from Ollama: the chat
uses a single model selector that lists both Ollama's remote models and local GGUF ones,
but the actual engine that answers depends on which one was chosen, never mixed in the same
conversation.

A curated catalog of models with a direct Hugging Face URL (Qwen2.5-0.5B-Instruct,
SmolLM2-1.7B-Instruct, Qwen2.5-1.5B-Instruct, Llama-3.2-1B-Instruct, Gemma-2-2B-it,
Llama-3.2-3B-Instruct, Q4_K_M quantization). Download with real live speed/ETA (same
pattern as Ollama's catalog).

## 13. Cloud ("More" menu)

**File:** `NubeFragment.kt`. Turns the device into a minimal Drive/Mediafire-style storage
cloud, scoped to ONE fixed folder (`$HOME/nube`) and accessible from any browser on the local
network, not just from the app. It reuses two existing pieces: **`NubeServer`**
(an embedded HTTP server, token-gated, with path-traversal validation) and
**`TunnelManager`** (the same cloudflared/ngrok logic Tunnel uses, pointed at `NubeServer`'s
port). The file list uses the same in/out navigation mechanism as Files but
scoped to the "cloud" folder — the user can't navigate outside it or see the rest of the
device from here.

## 14. GenericModuleFragment (generic module detail)

**File:** `app/src/main/java/com/termux/app/ui/GenericModuleFragment.kt`.

A METADATA-DRIVEN module detail screen: instead of one class per module, this fragment renders
the full sub-menu for any module from `ModuleInfo` (modules.json) + the registry's real
state. It's `ModulesFragment.navigateToModuleDetail()`'s fallback: modules with real
dedicated UI (Ollama, N8n, Claude, ...) keep their own dedicated fragment; any new module
in the catalog falls back here with no new code needed.

Cards/actions it draws automatically (based on what the module declares in modules.json):

- **STATUS** — ID, real version from the registry, port, type, execution (native/proot),
  tmux session, a TUI-in-terminal pill (if it has a terminal command) and a server
  running/stopped pill.
- **WHAT IT IS** — the catalog description.
- **CONTROL** (only if it has a switch) — ▶ Start / ■ Stop server.
- **🌐 Open web interface** (if it declares a URL) — navigates to `ModuleWebViewFragment`.
- **⌨ Open in terminal** (if it declares a command) — opens the module's CLI.
- **DETAILS** — size and install estimate, if present.
- **MAINTENANCE** — 🔄 Update and 🗑 Uninstall (with a confirmation dialog that stops the
  module and deletes scripts/checkpoints/registry, without touching shared packages).

## 15. Plugins — module store ("More" menu)

**File:** `PluginsFragment.kt` (+ `PluginListAdapter.kt`, layouts `fragment_plugins.xml` /
`item_plugin_row.xml`). Lists the **full catalog** of plugins/modules from `ModuleCatalog`
(bundled + cache + hybrid remote refresh).

- **Order**: recommended ones on top, sorted by downloads desc; the rest after, also by
  downloads.
- **Search**: by name / id / description / category.
- **Badges**: ★ Recommended (green), architecture (bionic/glibc/proot/proot-distro, color-coded
  by type), category, ⬇ downloads.
- **Real status**: registry + `ModuleController.isRunning()` — polled on screen open and after
  each action.
- **Per-status actions**: Install, Uninstall (with confirmation), Open (navigates to detail).
- **"↻ Catalog"**: refreshes the remote catalog with a silent fallback to cache/bundled.
- Starting/stopping is left to the detail screen (same pattern as the rest of the app).

## 16. X11 — integrated inside Mini PC

X11 has no screen of its own: all of its functionality lives inside **`EntornoFragment.kt`**
(the "Mini PC" tab, see section 17), in the "NATIVE — X11 + direct desktop" section:

- **Server status**: reads whether the X11 server process is alive and shows
  "● Server active" / "○ Server stopped" + display `:1`. Refreshes when returning to the screen.
- **🚀 Enter X11**: starts the X11 service and opens the embedded viewer (based on the
  termux-x11 fork, see `docs/x11/x11-embedded-architecture.md`).
- **⚙ X11 configuration**: opens termux-x11's original preferences with resolution mode
  (native/scaled/exact/custom), display scale, density, stretch, forced landscape/portrait
  orientation, fullscreen, hide notch, PiP, extra keyboard, touch mode,
  sensitivity, and more. Changes apply live.
- **✕ Close X11 server**: confirms → closes the viewer and stops the X11 service.
- **Viewer back = Minimize/Close dialog**: the viewer's back button shows a dialog with
  "Minimize" (the server keeps running) and "Close X11 server".

## 17. Mini PC (Entorno) — main tab

**File:** `EntornoFragment.kt` (extends `BaseModuleFragment`, logic in `EntornoNative.kt`).
It's its own tab in the `BottomNavigationView` (`nav_minipc`) — Entorno and X11 share the same
desktop ecosystem, so they're grouped under the same screen instead of keeping X11 as a
separate secondary screen.

Also accessible from the Modules catalog (with a real backstack, unlike the root tab).
It's the entry point to the full desktop: Linux distros via proot-distro, graphical
environments (XFCE/LXQt/MATE), and the embedded X11 server described in section 16.
