# APP_SPEC.md — Kairos Specification

**Repo:** github.com/Honkonx/kairos-lab
**APK:** built via GitHub Actions/GitLab CI, or locally with Android Studio + NDK — see `Build` below.

---

## What it is

A fork of termux-app (github.com/termux/termux-app) that unifies into a single APK:
- **Termux engine** (Java, protected) — real bash sessions, processes, APT bootstrap,
  VT100 terminal (`terminal-emulator/` NDK C + `terminal-view/`).
- **Native UI** (Kotlin, `app/src/main/java/com/termux/app/ui/`) — local AI module
  dashboard, chat, system monitor, settings, file manager, code editor, tunnels, a personal
  "cloud".

No React Native — the entire UI is native. No root required. AI models run locally
(native Ollama or embedded llama.cpp via NDK), with no runtime dependency on the internet
except for the initial download. ARM64 (`arm64-v8a`) only.

---

## Fixed constraints — don't change without a documented reason

| Parameter | Real value (`gradle.properties`/`build.gradle`) | Reason |
|-----------|-------|--------|
| `targetSdkVersion` | 28 | Higher values block shell `exec()` on Android 10+ |
| `minSdkVersion` | 26 | Android 8.0 minimum |
| `compileSdkVersion` | 36 | Can go up, `targetSdk` can't |
| `NDK` | 29.0.14206865 (r29) | Required by `terminal-emulator` C code and `llama-engine/` |
| `AGP` | 8.13.2 | `com.android.tools.build:gradle` in the root `build.gradle` |
| `Gradle` | 9.2.1 | `gradle/wrapper/gradle-wrapper.properties` |
| `Kotlin` | 2.2.21 | Required for metadata compatibility with `sora-editor` |
| `Java` | 17 | Engine and build |
| `sharedUserId` | `com.termux` | Must be preserved — the original Termux shares this UID, every Termux permission/package depends on it (see `AndroidManifest.xml`) |

---

## Architecture

```
kairos/
├── app/                              ← ALL app work goes here
│   └── src/main/
│       ├── assets/
│       │   ├── modules.json          ← module definitions (see below)
│       │   └── scripts/              ← embedded copy of modulos/*.sh (offline fallback)
│       ├── java/com/termux/app/
│       │   ├── TermuxActivity.java       ← main Activity, terminal overlay, FAB, adapted mode
│       │   ├── TermuxService.java        ← foreground service, real bash sessions
│       │   ├── TermuxInstaller.java      ← APT bootstrap (first run)
│       │   ├── TermuxApplication.java    ← entry point, starts ModuleEventBridge
│       │   ├── ModuleController.kt       ← installs/starts/stops modules via ProcessBuilder
│       │   ├── ui/                       ← dedicated per-module fragments + generic
│       │   │                                CliToolFragment for CLI tools + GenericModuleFragment
│       │   │                                fallback + core screens (see APP_SCREENS.md)
│       │   ├── wizard/                   ← WizardActivity.java + fragments (ViewPager2)
│       │   ├── terminal/                 ← TermuxTerminalSessionActivityClient.java
│       │   └── util/                     ← helpers (BatteryRestrictionHelper, PhantomProcessKillerHelper, RootfsInstaller, BackupManager, etc.)
│       ├── java/com/termux/rn/           ← legacy bridge (BridgeSingleton, SessionInfo)
│       └── res/
│           ├── layout/activity_kairos.xml    ← BottomNav + FAB + fragment container
│           ├── layout/activity_termux.xml    ← terminal overlay (normal + adapted mode)
│           ├── menu/bottom_nav_menu.xml      ← real tabs
│           ├── menu/more_nav_menu.xml        ← "More" menu screens
│           └── values/colors_kairos.xml      ← design system (see below)
├── llama-engine/                     ← NDK module, embedded llama.cpp
├── terminal-emulator/                ← 🔒 Protected — NDK C, VT100
├── terminal-view/                    ← 🔒 Protected — Android widget
├── termux-shared/                    ← 🔒 Protected (except `module/`)
├── modulos/                          ← real scripts that install/start each module (bash)
├── tools/rootfs/                     ← `build_rootfs.py` — assembles the embedded rootfs
├── .github/workflows/                ← build-app.yml (lightweight), build-app-rootfs.yml,
│                                        build-app-rootfsv1.yml, build-rootfs.yml
└── docs/                             ← this documentation
```

---

## UI — Real navigation

### Bottom navigation — 5 tabs (`bottom_nav_menu.xml`)

```
⊞ Modules  |  ◈ AI Chat  |  ◉ System  |  ⚙ Settings  |  ⋯ More
```

`BottomNavigationView` has a hard limit of 5 items (a real library limit, not a
suggestion). The 5th item ("More") opens a `PopupMenu` with the screens that don't fit.

### "More" menu — additional screens (`more_nav_menu.xml`)

| id | Title | Fragment |
|---|---|---|
| `nav_monitor` | Monitor | `MonitorFragment.kt` |
| `nav_files` | Files | `FileManagerFragment.kt` |
| `nav_tunnel` | Tunnel | `TunnelFragment.kt` |
| `nav_procesos` | Processes | `ProcesosFragment.kt` |
| `nav_local_ai` | Local AI | `LocalAIFragment.kt` |
| `nav_nube` | Cloud | `NubeFragment.kt` |

See `docs/arquitectura/APP_SCREENS.en.md` for the detail of each one.

### Wizard (first launch)

`ViewPager2` + `FragmentStateAdapter`, with dedicated screens for:

1. `WizardWelcomeFragment` — welcome.
2. `WizardPermissionsFragment` — Android permissions (storage, notifications).
3. `WizardInstallFragment` — Termux bootstrap + rootfs (embedded or runtime download) + `kairos.sh`.
4. `WizardCheckFragment` — final check, optional.

See `docs/bootstrap/rootfs-embebido.md` for the embedded rootfs design and its two
build variants (lightweight vs. with rootfs included in the APK).

---

## Module system

**`app/src/main/assets/modules.json`** defines the full catalog of modules: AI engines
(`ollama`, `python`, `claude`, `codex`, `antigravity`, `openclaw`, `opencode`, `hermes`,
`remote`, `expo`, `engram`, and other AI/development CLIs), automation (`n8n`), security
(`ciberseguridad`), desktop environment (`entorno`), database (`db`), containers/VMs
(`udocker`, `qemu`, `docker`), local inference (`llamaserver`, `cactus`), IDE (`ide`), and more.
Each entry has the following fields:

```json
{
  "id": "ollama", "name": "Ollama", "description": "...",
  "repo": "Honkonx/kairos-lab", "script": "ollama.sh",
  "icon": "⬡", "iconBg": "#1A4A2E",
  "port": "11434", "size": "~850MB", "type": "Native",
  "estimate": "~2 min", "requiresProot": false,
  "hasVariants": true, "hasSwitch": true,
  "tmuxSession": "ollama-server"
}
```

- **`hasSwitch`**: whether the module has a real server process (Ollama, n8n, OpenClaw,
  OpenCode, Remote) vs. CLI tools with no server of their own (Python, Claude, Codex,
  Antigravity, Hermes, Expo) — the latter have no ON/OFF switch, just "open
  terminal".
- **`requiresProot`**: whether it depends on proot-distro (Debian) instead of running natively.
- **`ModuleController.kt`** is the source of truth for installing (`installModule()`),
  starting/stopping (`startModule()`/`stopModule()`) and checking real state
  (`isRunning()`, `waitForPortOpen()` — a real TCP poll after a successful exit code, it
  doesn't just trust the script's own checkpoint).
- **Registry**: `~/.android_server_registry`, format `module.key=value`, read/written
  by the `modulos/*.sh` scripts themselves — the UI reads it, it doesn't write to it directly.

See `docs/modulos/` for the full detail per module (permissions, installation,
options, detection — one dedicated doc per module).

---

## Design system — 3 selectable themes

Kairos has **3 selectable themes** (Settings → 🎨 Theme), and the code never references
fixed colors anywhere in the UI — everything goes through **theme attributes** (`?attr/kairosX` in
XML, `ctx.kairosThemeColor(R.attr.kairosX)` in Kotlin, see
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
| `kairosStatusRunning`/`Stopped`/`Installing`/`Error`/`NotInstalled` | see above | Per-module visual status — also paints the circular badge overlaid on each row's icon (`ModuleListAdapter`/`PluginListAdapter`, a pattern inspired by home-lab management panels) |

**Signal** (cool cyan-teal, `colors_kairos_senal.xml`) — same tokens, different palette: background
`#0A0E14`→`#1B222E`, text `#E4EAF2`/`#7C8B9E`/`#4A5568`, accents `kairosBlue=#4FD1C5` (cyan, not
pure blue), `kairosGreen=#48BB78`, `kairosRed=#F56565`, `kairosAmber=#ECC94B`.

**Light** (`colors_kairos_claro.xml`, a real light mode — not a simple inversion) — same set of
tokens with light backgrounds and `android:windowLightStatusBar`/`windowLightNavigationBar`
enabled in the style.

### Reusable UI components (`BaseModuleFragment.kt`, inherited by module fragments)

Replace groups of mutually exclusive buttons with more compact controls:

| Component | Use | Real example |
|---|---|---|
| `dropdownSwitchRow()` | Pick 1 of N options + an on/off switch that locks the dropdown while ON | n8n (🏠 local / 🌐 Cloudflare + switch), OpenCode (port 3000/4096 + switch) |
| `switchRow()` | Simple on/off, no options | Remote (SSH, Cloudflare tunnel), OpenClaw (gateway), Db (MySQL/PostgreSQL/Redis — 3 independent switches), Ollama, Hermes Gateway, LlamaServer |
| `dropdownRow()` | Pick 1 of N options that are NOT a binary toggle (no switch) | Ciberseguridad (4 sqlmap actions), Hermes (local AI provider: Ollama vs llama-server) |

---

## Code conventions

**Java (Termux engine, protected):** no lambdas, explicit `this.`, `@Override` on
every overridden method, no dependencies external to the Android SDK.

**Kotlin (UI and utilities):** idiomatic, `if (!isAdded) return` before any
`requireActivity()`/`requireContext()` in async callbacks (the project's standard guard
against fragment-detached crashes), file I/O always on a separate `Thread` with
`runOnUiThread`/`Handler.post()` to update the UI.

**Kotlin↔Java interop:** any function on a Kotlin `object` called from `.java`
needs `@JvmStatic` (otherwise it only exists as an instance method on `INSTANCE`) and
`@JvmOverloads` if it has default parameters (otherwise Java doesn't see the
lower-arity overload) — and watch out for a default parameter that isn't last in the
signature, where `@JvmOverloads` won't generate the needed overload and a manual one is
required.

**XML layouts:** snake_case, `@+id/` prefix, avoid hardcoded dp values.

---

## Build

```bash
./gradlew :app:assembleDebug
```

Built via **GitHub Actions/GitLab CI** (primary path, reproducible) or **local build on
Windows** (`tools/build-local.ps1`, with Android Studio + SDK/NDK installed — see
`docs/arquitectura/BUILD.en.md` for details) — 4 real workflows in `.github/workflows/`:
- **`build-app.yml`** ("Build Kairos APK") — lightweight build, no embedded rootfs, the
  wizard downloads it at runtime.
- **`build-app-rootfs.yml`** ("Build Kairos APK (with embedded rootfs)") — with the rootfs
  embedded as an APK asset (requires a prior Release from `build-rootfs.yml` +
  `GITHUB_TOKEN` passed explicitly).
- **`build-app-rootfsv1.yml`** ("Build Kairos APK (with embedded rootfs) v1") — improved
  variant of `build-app-rootfs.yml`: auto-detects the most recent release with the
  `rootfs-` prefix instead of requiring the tag by hand.
- **`build-rootfs.yml`** ("Build Kairos rootfs") — assembles the rootfs from
  `tools/rootfs/build_rootfs.py` + `tools/rootfs/package_list.txt`, publishes a GitHub
  Release (not an app release, an internal artifact).

See `docs/bootstrap/rootfs-embebido.md` for the rootfs mechanism and
`docs/arquitectura/BUILD.en.md` for the full detail of local vs. CI builds.

---

## Implementation status

| Component | Status |
|-----------|--------|
| Modules UI + install BottomSheet | ✅ Functional |
| Start/stop switch system | ✅ Functional (`ModuleController.kt`, with `waitForPortOpen()`) |
| Chat (separate Ollama and llama.cpp engines) | ✅ Functional |
| System/Monitor/Settings/Files/Tunnel/Processes/Local AI/Cloud | ✅ Functional |
| Terminal overlay + adapted mode (bars + sidebar) | ✅ Functional |
| First-launch wizard | ✅ Functional |
| Embedded rootfs (optional) + runtime download | ✅ Functional (the lightweight variant requires the download repo to be public or authenticated) |
| llama.cpp NDK (`llama-engine/`) | ✅ Functional |
| Phantom process killer fix (Android 12+) | ✅ Functional — 3 paths (guided/beta/manual), see dedicated doc |
| Real `--force` on the "Update" button of several modules | ❌ Known gap |
| A way to "update without reinstalling everything" | ❌ Known gap |

---

## See also

- `docs/arquitectura/APP_SCREENS.en.md` — every real screen, in detail.
- `docs/modulos/` — per-module docs.
- `docs/bootstrap/rootfs-embebido.md` — rootfs mechanism.
- `docs/ia-local/llama-cpp-local-engine.md` — llama.cpp module.
