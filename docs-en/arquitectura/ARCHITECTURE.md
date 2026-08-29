# ARCHITECTURE.md — Kairos

> Screen-by-screen reference (what each tab/fragment shows, what is wired to real logic and
> what isn't yet): `docs-en/arquitectura/APP_SCREENS.md`.

## Real stack

Primary navigation consists of **5 tabs plus a "More" menu with 6 additional screens**
(Monitor / Files / Tunnel / Processes / Local AI / Cloud).

```
APK
├── UI: Kotlin/Java Fragments (BottomNavigationView, 5 tabs + "More" menu, 6 extra screens)
├── Terminal bridge: TermuxBridgeAdapter.kt — SESSIONS ONLY (getSessions/createSession/
│   writeToSession/resizeSession/killSession), implements the TermuxBridge interface from
│   com.termux.rn (legacy engine bridge, instantiated by TermuxService). Does NOT control
│   modules.
├── Module control: ModuleController.kt — a Kotlin object called directly from Fragments
│   (no bridge/interface layer), ProcessBuilder → bash scripts. See BRIDGE_API.md.
├── Module→app bridge: ModuleEventBridge.kt — background thread that reads a real FIFO
│   ($HOME/.kairos_events) and fires Android notifications, so scripts can notify the app in
│   real time without polling.
├── Engine: Termux Java (TermuxService, TermuxActivity, TermuxInstaller)
│   ├── Service: TermuxService (foreground, bash sessions, processes)
│   ├── Terminal: TerminalView + terminal-emulator (NDK C, VT100)
│   └── Bootstrap: APT package manager, $PREFIX
└── Data: ModuleRegistry.kt reads $HOME/.android_server_registry (a flat Map<String,String>,
    real keys like `<module>.installed`/`<module>.version` — the RUNNING state is not
    persisted there, it's computed live via ModuleController.isRunning())
```

## Flows

### UI → Service (command)
```
Fragment
  → TermuxBridgeAdapter.kt
    → ProcessBuilder
      → bash script
```

### Module status (polling)

`ModuleRegistry.kt` does not expose a "poll" method of its own — only `load()`/`get()`/
`getModules()`/`isProotInstalled()`. Polling is each Fragment's own responsibility, not a
central mechanism:

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
NDK C library with VT100/xterm emulation.
- `TerminalEmulator.java` — JNI wrapper
- `JniTerminal.c` — native implementation
- Escape sequences, scrollback, colors, UTF-8

### terminal-view
Android SurfaceView widget that renders the terminal.
- `TerminalView.java` — main View
- `TranscriptScreen.java` — scrollback buffer
- `ExtraKeysView.java` — extra keys (Tab, Ctrl, Esc)

### termux-shared
Utilities shared across the engine.
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
- `TermuxBridgeAdapter.kt` — terminal session bridge (see note above)
- `ModuleController.kt` — real module control (install/start/stop/isRunning), no bridge layer
- `util/ModuleEventBridge.kt` — module→app event bridge (FIFO)
- `ui/` — dozens of Fragment classes (Modules, AI Chat, System, Settings, Monitor, Files,
  Tunnel, Processes, Local AI, Cloud, Plugins, an integrated IDE, plus dedicated detail
  fragments per module + a generic `CliToolFragment` for CLI tools +
  `GenericModuleFragment` as a fallback for the rest of the catalog — see `docs/modulos/`)
- `data/ModuleRegistry.kt` — reader for the module registry ($HOME/.android_server_registry)
- `model/ModuleInfo.kt` — real data class (id/name/icon/port/hasSwitch/tmuxSession/...) +
  `enum Status { NOT_INSTALLED, INSTALLED_STOPPED, RUNNING, INSTALLING, ERROR }`

## Optimization patterns

### Polling proot-based modules
Every `proot-distro login debian` invocation takes 3-5s on ARM64. To avoid blocking:

- **Batch checks:** a single proot process detects multiple modules at once (e.g. OpenCode + OpenClaw together) and returns separate results.
- **3-tier cache:** (1) in-memory variable (30s TTL), (2) on-disk file (5min TTL), (3) a real query to proot.
- **Parallelize:** independent module checks run on parallel threads with `wait()`.

### Native modules (no proot)
Modules that run in native Termux (Ollama, Python, SSH) are checked with `pgrep` or `tmux has-session` — instant (<10ms), no cache needed.

---

## Inter-module communication

Direct Java/Kotlin calls within the same process — no HTTP, no JS, no IPC.

| From | To | Mechanism |
|--------|---------|-----------|
| Fragment (terminal) | TermuxService | TermuxBridgeAdapter → direct method (getSessions/write/resize/kill) |
| Fragment (modules) | bash | ModuleController.kt → ProcessBuilder → script (bypasses TermuxBridgeAdapter) |
| Fragment | Registry | ModuleRegistry → on-demand file I/O (no central automatic polling — each Fragment re-reads when it needs to) |
| bash script | App | ModuleEventBridge → FIFO ($HOME/.kairos_events) → Android notification |
| TerminalView | bash | PTY master ↔ slave fd |
