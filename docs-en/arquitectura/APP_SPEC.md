# APP_SPEC.md — Kairos Specification

**Current version:** `0.118.0` (`versionCode` 118, see `app/build.gradle`)
**APK:** built via GitHub Actions/GitLab CI, or locally (see `BUILD.md`).

---

## What it is

A fork of termux-app (github.com/termux/termux-app) that unifies, in a single APK:
- **Termux engine** (Java) — real bash sessions, processes, APT bootstrap,
  VT100 terminal (`terminal-emulator/` NDK C + `terminal-view/`).
- **Native UI** (Kotlin, `app/src/main/java/com/termux/app/ui/`) — a dashboard for
  local AI modules, chat, system monitor, settings, file manager, code editor, tunnels,
  and a personal storage "cloud".

No React Native — the entire UI is native Java/Kotlin. No root required. AI models run
locally (native Ollama or llama.cpp embedded via NDK), with no runtime dependency on the
internet except for the initial model download. Targets `arm64-v8a` only.

---

## Fixed platform constraints

| Parameter | Real value (`gradle.properties`/`build.gradle`) | Reason |
|-----------|-------|--------|
| `targetSdkVersion` | 28 | Higher values block shell `exec()` on Android 10+ |
| `minSdkVersion` | 26 | Android 8.0 minimum |
| `compileSdkVersion` | 36 | Can go up, `targetSdk` cannot |
| `NDK` | 29.0.14206865 (r29) | Required by `terminal-emulator` C code and `llama-engine/` |
| `AGP` | 8.13.2 | `com.android.tools.build:gradle` in the root `build.gradle` |
| `Gradle` | 9.2.1 | `gradle/wrapper/gradle-wrapper.properties` |
| `Kotlin` | 2.2.21 | |
| `Java` | 17 | Engine and build |
| `sharedUserId` | `com.termux` | Preserved for compatibility with the original Termux's permissions/packages (see `AndroidManifest.xml`) |

---

## Directory architecture

```
kairos/
├── app/                              ← all app work lives here
│   └── src/main/
│       ├── assets/
│       │   ├── modules.json          ← module catalog definition
│       │   └── scripts/              ← embedded copy of modulos/*.sh (offline fallback)
│       ├── java/com/termux/app/
│       │   ├── TermuxActivity.java       ← main Activity, terminal overlay, FAB, adapted mode
│       │   ├── TermuxService.java        ← foreground service, real bash sessions
│       │   ├── TermuxInstaller.java      ← APT bootstrap (first run)
│       │   ├── TermuxApplication.java    ← entry point, starts ModuleEventBridge
│       │   ├── ModuleController.kt       ← installs/starts/stops modules via ProcessBuilder
│       │   ├── ui/                       ← per-module Fragments + a generic CliToolFragment
│       │   │                                for CLI tools + a GenericModuleFragment fallback
│       │   │                                + core screens (see APP_SCREENS.md)
│       │   ├── wizard/                   ← WizardActivity.java + fragments (ViewPager2)
│       │   ├── terminal/                 ← TermuxTerminalSessionActivityClient.java
│       │   └── util/                     ← Helpers (BatteryRestrictionHelper, PhantomProcessKillerHelper, RootfsInstaller, BackupManager, etc.)
│       ├── java/com/termux/rn/           ← legacy bridge (BridgeSingleton, SessionInfo)
│       └── res/
│           ├── layout/activity_kairos.xml    ← BottomNav + FAB + fragment container
│           ├── layout/activity_termux.xml    ← terminal overlay (normal + adapted mode)
│           ├── menu/bottom_nav_menu.xml      ← main tabs
│           ├── menu/more_nav_menu.xml        ← "More" menu screens
│           └── values/colors_kairos.xml      ← design system (see below)
├── llama-engine/                     ← NDK module, embedded llama.cpp
├── terminal-emulator/                ← NDK C, VT100 (inherited from termux-app)
├── terminal-view/                    ← Android widget (inherited from termux-app)
├── termux-shared/                    ← shared utilities (inherited from termux-app)
├── modulos/                          ← the real bash scripts that install/start each module
├── tools/rootfs/                     ← `build_rootfs.py` — assembles the embedded rootfs (see ROOTFS_EMBEBIDO.md)
├── .github/workflows/                ← CI workflows (see BUILD.md)
└── docs/                             ← this documentation
```

---

## UI — Navigation

### Bottom navigation

```
⊞ Modules  |  ◈ AI Chat  |  ◉ System  |  ⚙ Settings  |  ⋯ More
```

`BottomNavigationView` has a hard limit of 5 items (a real library limit, not a suggestion).
The 5th item ("More") opens a menu with the screens that don't fit in the bar.

### "More" menu

| id | Title | Fragment |
|---|---|---|
| `nav_monitor` | Monitor | `MonitorFragment.kt` |
| `nav_files` | Files | `FileManagerFragment.kt` |
| `nav_tunnel` | Tunnel | `TunnelFragment.kt` |
| `nav_procesos` | Processes | `ProcesosFragment.kt` |
| `nav_local_ai` | Local AI | `LocalAIFragment.kt` |
| `nav_nube` | Cloud | `NubeFragment.kt` |

See `docs-en/arquitectura/APP_SCREENS.md` for details on each one.

### Wizard (first launch)

`ViewPager2` + `FragmentStateAdapter`, with several sequential screens:

1. `WizardWelcomeFragment` — welcome screen.
2. `WizardPermissionsFragment` — Android permissions (storage, notifications).
3. `WizardPhantomProcessFragment` — optional help disabling Android 12+'s phantom process
   limit (guided wireless-ADB auto-detection, or a manual walkthrough).
4. `WizardBatteryFragment` — optional help removing battery restrictions.
5. `WizardInstallFragment` — Termux bootstrap + rootfs (embedded or runtime download) + the
   initial setup script, with live step-by-step progress.
6. `WizardCheckFragment` — optional final check, "Check & update" or "Skip".

See `docs/bootstrap/ROOTFS_EMBEBIDO.md` for the embedded rootfs design.

---

## Module system

**`app/src/main/assets/modules.json`** defines the full catalog of available modules: local
AI and coding-agent tools (Ollama, llama.cpp/`llamaserver`, Claude Code, Codex CLI, OpenCode,
OpenClaw, Kilo, Kimi, and other AI CLIs), automation (n8n), an embedded Linux desktop
environment (`entorno`/Mini PC, proot-distro), remote access (`remote`, SSH + tunnels),
databases (`db`), containers (`docker`, `udocker`), virtualization (`qemu`), an integrated
IDE (`ide`), and general utilities (Python, package manager, security tooling, etc.). Each
entry looks like this:

```json
{
  "id": "ollama", "name": "Ollama", "description": "...",
  "script": "ollama.sh",
  "icon": "⬡", "iconBg": "#1A4A2E",
  "port": "11434", "size": "~850MB", "type": "Nativo",
  "estimate": "~2 min", "requiresProot": false,
  "hasVariants": true, "hasSwitch": true,
  "tmuxSession": "ollama-server"
}
```

- **`hasSwitch`**: whether the module has a real server process (Ollama, n8n, OpenClaw,
  OpenCode, Remote...) vs. CLI tools with no server of their own (Python, Claude Code, Codex,
  Antigravity CLI, Hermes, Expo) — the latter have no ON/OFF switch, just "open terminal".
- **`requiresProot`**: whether it depends on proot-distro (Debian) instead of running
  natively.
- **`ModuleController.kt`** is the source of truth for installing (`installModule()`),
  starting/stopping (`startModule()`/`stopModule()`), and checking real status
  (`isRunning()`, `waitForPortOpen()` — a real TCP poll after a successful exit code, not
  just trusting the script's own checkpoint).
- **Registry**: `~/.android_server_registry`, `module.key=value` format, read/written by the
  `modulos/*.sh` scripts themselves — the UI reads it, it doesn't write to it directly.

See `docs/modulos/` for full per-module details (permissions, installation, options,
detection).

---

## Design system — 3 selectable themes

Kairos ships **3 selectable themes** (Settings → 🎨 Theme). The code never references fixed
colors directly — everything goes through **theme attributes** (`?attr/kairosX` in XML,
`ctx.kairosThemeColor(R.attr.kairosX)` in Kotlin, see
`app/src/main/java/com/termux/app/util/KairosThemeColors.kt`), resolved at runtime by the
active style (`app/src/main/res/values/themes_kairos.xml`:
`Theme.Kairos.Oscuro`/`Theme.Kairos.Senal`/`Theme.Kairos.Claro`). The selection persists in
`SharedPreferences` (`KairosThemePrefs.kt`) and is applied with `setTheme()` before
`super.onCreate()` in `TermuxActivity`.

**Dark** (default — `colors_kairos.xml`):

| Token (attribute) | Hex | Use |
|-------|-----|-----|
| `kairosBg` | `#050505` | Main background |
| `kairosBg2` | `#0A0A0A` | Cards |
| `kairosBg3` | `#111111` | List items |
| `kairosBgElevated` | `#1A1A1A` | Elevated elements (dialogs) |
| `kairosBgSurface` | `#0D0D0D` | Surfaces |
| `kairosNavBg` | `#080808` | Navigation bar background |
| `kairosText` | `#E8E8E8` | Primary text |
| `kairosText2` | `#888888` | Secondary text |
| `kairosText3` | `#555555` | Labels, subtitles |
| `kairosBlue` | `#3B82F6` | Info accent |
| `kairosGreen` | `#22C55E` | Success/active accent |
| `kairosRed` | `#EF4444` | Error/danger |
| `kairosAmber` | `#F59E0B` | Warning |
| `kairosBorder` / `kairosDivider` | `#1F1F1F` / `#151515` | Borders/dividers |
| `kairosStatusRunning`/`Stopped`/`Installing`/`Error`/`NotInstalled` | see above | Per-module visual status — also colors the small status badge overlaid on each row's icon |

**Signal** (cool cyan-teal, `colors_kairos_senal.xml`) — same tokens, different palette:
background `#0A0E14`→`#1B222E`, text `#E4EAF2`/`#7C8B9E`/`#4A5568`, accents
`kairosBlue=#4FD1C5` (cyan, not pure blue), `kairosGreen=#48BB78`, `kairosRed=#F56565`,
`kairosAmber=#ECC94B`.

**Light** (`colors_kairos_claro.xml`, a genuine light mode — not a simple color inversion) —
same token set with light backgrounds and `android:windowLightStatusBar`/
`windowLightNavigationBar` enabled in the style.

### Reusable UI components (`BaseModuleFragment.kt`)

Inherited by most module fragments, these replace mutually-exclusive button groups with more
compact controls:

| Component | Use | Real example |
|---|---|---|
| `dropdownSwitchRow()` | Pick 1 of N options + an on/off switch that locks the dropdown while ON | n8n (🏠 local / 🌐 Cloudflare + switch), OpenCode (port 3000/4096 + switch) |
| `switchRow()` | Simple on/off, no options | Remote (SSH, Cloudflare tunnel), OpenClaw (gateway), Db (MySQL/PostgreSQL/Redis — 3 independent switches), Ollama, Hermes Gateway, LlamaServer |
| `dropdownRow()` | Pick 1 of N options that are NOT a binary toggle (no switch) | Security tooling (sqlmap actions), Hermes (local AI provider: Ollama vs llama-server) |

---

## Code conventions

**Java (inherited Termux engine):** no lambdas, explicit `this.`, `@Override` on every
overridden method, no dependencies outside the Android SDK.

**Kotlin (UI and utilities):** idiomatic, `if (!isAdded) return` before any
`requireActivity()`/`requireContext()` in async callbacks (the project's standard guard
against fragment-detached crashes), file I/O always on a separate `Thread` with
`runOnUiThread`/`Handler.post()` to update the UI.

**Kotlin↔Java interop:** any function on a Kotlin `object` called from `.java` needs
`@JvmStatic` (otherwise it only exists as an instance method on `INSTANCE`) and
`@JvmOverloads` if it has default parameters (otherwise Java doesn't see the lower-arity
overload). When a default parameter is added in the middle of a signature (not at the end),
`@JvmOverloads` won't generate the overload Java needs — an explicit manual overload has to
be added.

**XML layouts:** snake_case, `@+id/` prefix, avoid hardcoded dp values.

---

## Build

```bash
./gradlew :app:assembleDebug
```

See `docs-en/arquitectura/BUILD.md` for the full details on CI workflows and local builds.

---

## Implementation status

| Component | Status |
|-----------|--------|
| Modules UI + install sheet | ✅ Working |
| Start/stop switch system | ✅ Working (`ModuleController.kt`, with `waitForPortOpen()`) |
| Chat (separate Ollama and llama.cpp engines) | ✅ Working |
| System/Monitor/Settings/Files/Tunnel/Processes/Local AI/Cloud | ✅ Working |
| Terminal overlay + adapted mode (bars + sidebar) | ✅ Working |
| First-launch wizard | ✅ Working |
| Embedded rootfs (optional) + runtime download | ✅ Working |
| llama.cpp NDK (`llama-engine/`) | ✅ Working |
| Phantom process killer fix (Android 12+) | ✅ Working — 3 paths (guided/beta/manual) |

---

## See also

- `docs-en/arquitectura/APP_SCREENS.md` — every real screen, in detail.
- `docs/modulos/` — per-module documentation.
- `docs/bootstrap/ROOTFS_EMBEBIDO.md` — rootfs mechanism.
- `docs/ia-local/LLAMA_CPP_EMBEBIDO.md` — llama.cpp module.
