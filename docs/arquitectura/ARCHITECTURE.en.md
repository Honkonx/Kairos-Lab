# ARCHITECTURE.md — Kairos

> Screen-by-screen reference (what each tab/fragment shows, what's wired to real logic and what
> isn't): `docs/arquitectura/APP_SCREENS.en.md`.
>
> The app's real navigation is **5 tabs + a "More" menu with several extra screens**
> (Monitor, Files, Tunnel, Processes, Local AI, Cloud). `TermuxBridgeAdapter.kt` is **not** a
> generic module bridge — its scope is exclusively terminal sessions, see the note below and
> `docs/arquitectura/BRIDGE_API.en.md`.

## Real stack

```
APK
├── UI: Kotlin/Java Fragments (BottomNavigationView, 5 tabs + "More" menu, several extra screens)
├── Terminal bridge: TermuxBridgeAdapter.kt — sessions ONLY (getSessions/createSession/
│   writeToSession/resizeSession/killSession), implements the TermuxBridge interface from
│   com.termux.rn (legacy bridge, instantiated by TermuxService). Does NOT control modules.
├── Module control: ModuleController.kt — a Kotlin object called DIRECTLY from the
│   Fragments (no bridge/interface layer), ProcessBuilder → bash scripts. See BRIDGE_API.en.md.
├── Module→app bridge: ModuleEventBridge.kt — background thread that reads a real FIFO
│   ($HOME/.kairos_events) and fires Android notifications, so scripts can notify the
│   app in real time without polling.
├── Engine: Termux Java (TermuxService, TermuxActivity, TermuxInstaller)
│   ├── Service: TermuxService (foreground, bash sessions, processes)
│   ├── Terminal: TerminalView + terminal-emulator (NDK C, VT100)
│   └── Bootstrap: APT package manager, $PREFIX
└── Data: ModuleRegistry.kt reads $HOME/.android_server_registry (a flat Map<String,String>,
    real keys like `<module>.installed`/`<module>.version` — the RUNNING state is not
    persisted there, it's computed live via ModuleController.isRunning())
```

## Flows

### UI → service (command)
```
Fragment
  → TermuxBridgeAdapter.kt
    → ProcessBuilder
      → bash script
```

### Module state (polling)

`ModuleRegistry.kt` does not expose any polling method of its own (only
`load()`/`get()`/`getModules()`/`isProotInstalled()`). Polling is each Fragment's own
responsibility, not a central mechanism:

```
Fragment.startPolling()          ← local Handler, e.g. every 5s (MonitorFragment)
  → ModuleController.isRunning() / ModuleRegistry().load().get()
    → Fragment UI (direct, no central handler.post)
```

### Terminal
```
TerminalView
  ↔ TermuxSession (pty)
    ↔ bash
```

## Engine modules

### terminal-emulator
C NDK library with VT100/xterm emulation.
- `TerminalEmulator.java` — JNI wrapper
- `JniTerminal.c` — native implementation
- Escape sequences, scrollback, colors, UTF-8

### terminal-view
Android SurfaceView widget for drawing the terminal.
- `TerminalView.java` — main View
- `TranscriptScreen.java` — scrollback buffer
- `ExtraKeysView.java` — extra keys (Tab, Ctrl, Esc)

### termux-shared
Utilities shared across Termux modules.
- `TermuxConstants.java` — paths, names
- `TermuxShellManager.java` — session CRUD
- `TermuxShellUtils.java` — shell helpers
- `TermuxAppSharedPreferences.java` — preferences
- `TermuxBootstrap.java` — APT bootstrap

### app (main module)
- `TermuxActivity.java` — main Activity (BottomNav + FAB + fragments)
- `TermuxService.java` — foreground service (bash sessions)
- `TermuxApplication.java` — application entry point
- `TermuxInstaller.java` — bootstrap installation
- `TermuxBridgeAdapter.kt` — terminal SESSION bridge (see note above)
- `ModuleController.kt` — real module control (install/start/stop/isRunning), no bridge layer
- `util/ModuleEventBridge.kt` — module→app event bridge (FIFO)
- `ui/` — dozens of Fragment classes: Modules, AI Chat, System, Settings, Monitor, Files,
  Tunnel, Processes, Local AI, Cloud, Plugins + dedicated per-module detail fragments +
  a generic `CliToolFragment` (CLI tools) + `GenericModuleFragment` as a fallback for the rest
  of the modules in the catalog (see `docs/modulos/`)
- `data/ModuleRegistry.kt` — reader for the module registry ($HOME/.android_server_registry)
- `model/ModuleInfo.kt` — real data class (id/name/icon/port/hasSwitch/tmuxSession/...) + `enum Status { NOT_INSTALLED, INSTALLED_STOPPED, RUNNING, INSTALLING, ERROR }`

## Optimization patterns (derived from the original stack)

### Proot module polling
Each `proot-distro login debian` invocation takes 3-5s on ARM64. To avoid blocking:

- **Batch checks:** a single proot process detects multiple modules (e.g. OpenCode + OpenClaw together) and returns separate results.
- **3-level cache:** (1) in-memory variable (30s TTL), (2) file on disk (5min TTL), (3) real query to proot.
- **Parallelize:** independent module checks run in parallel threads with `wait()`.

### Native modules (no proot)
Modules that run in native Termux (Ollama, Python, SSH) are checked with `pgrep` or `tmux has-session` — instantaneous (<10ms) and require no cache.

---

## Inter-module communication

Direct Java/Kotlin calls within the same process — no HTTP, no JS, no IPC.

| Source | Destination | Mechanism |
|--------|---------|-----------|
| Fragment (terminal) | TermuxService | TermuxBridgeAdapter → direct method call (getSessions/write/resize/kill) |
| Fragment (modules) | bash | ModuleController.kt → ProcessBuilder → script (bypasses TermuxBridgeAdapter entirely) |
| Fragment | Registry | ModuleRegistry → on-demand file I/O (no central automatic polling — each Fragment re-reads when it needs to) |
| bash script | App | ModuleEventBridge → FIFO ($HOME/.kairos_events) → Android notification |
| TerminalView | bash | PTY master ↔ slave fd |
