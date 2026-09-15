# BRIDGE_API.md — Kairos's real bridges

There are 2 separate, unrelated mechanisms, documented below.

## 1. `TermuxBridgeAdapter.kt` — terminal SESSION bridge

Implements the `TermuxBridge` interface (`com.termux.rn`, legacy bridge) — instantiated once
by `TermuxService.java` (`BridgeSingleton.setBridge(new TermuxBridgeAdapter(this))`). Its
scope is exclusively terminal sessions, NOT modules:

| Method | Args | Returns | Real description |
|--------|------|---------|-------------|
| `getSessions` | — | `List<SessionInfo>` | Lists active sessions (`TermuxService.termuxSessions`) — title, shellPath, cwd, isRunning, pid, handle |
| `createSession` | shellPath, cwd? | `String?` (handle) | Creates a new session via `TermuxService.createTermuxSession()` |
| `writeToSession` | sessionId, text | `Boolean` | Writes text to an existing session by index |
| `resizeSession` | sessionId, cols, rows | `Boolean` | Resizes a session's PTY |
| `killSession` | sessionId | `Boolean` | Terminates a session (`finishIfRunning()`) |

It has no method for installing/starting/stopping modules, reading the registry, or
system info — that lives in a completely different place (section 2).

## 2. `ModuleController.kt` — module control (NO bridge layer)

A Kotlin object (`object ModuleController`) called DIRECTLY from Fragments — it doesn't implement
any bridge interface, and doesn't go through `TermuxBridgeAdapter`. Each module Fragment
(`OllamaFragment.kt`, `N8nFragment.kt`, etc.) calls it directly.

| Real function | Signature | Description |
|---|---|---|
| `installModule` | `(moduleId, variant, onProgress, onComplete)` | `ProcessBuilder("bash", script, "--silent"[, "--variant", variant])`, logs line by line to `installLogFile(moduleId)` and to the callback |
| `startModule` | `(moduleId, onResult)` | Starts the module (tmux/process depending on type) |
| `stopModule` | `(moduleId, onResult)` | Stops the module |
| `isRunning` | `(moduleId): Boolean` | LIVE check — `tmux has-session` for tmux-backed modules (ollama/n8n/openclaw/opencode), `pgrep -x` for process-based modules (remote/sshd), always false for CLIs without a switch (claude/codex/antigravity/python/hermes/expo) |
| `installLogFile` | `(moduleId): File` | Path to the persistent install log (`~/kairos_logs/install_<id>.log`) |
| `autoStartEligibleModules` | `(context, onEach)` | Called when the app opens — auto-starts any switch-enabled module that's already installed but stopped |

Modules and how their "running" state is determined (see `getTmuxSession()`/`getProcessName()`
inside `ModuleController.kt` for the real, complete mapping — it's the source of truth: a
module with a switch in `modules.json` but no entry in those maps shows as togglable in the UI
but fails silently).

## 3. `ModuleRegistry.kt` — registry reader

```kotlin
class ModuleRegistry(context: Context) {
    fun load(): ModuleRegistry          // reads $HOME/.android_server_registry line by line
    fun get(key: String): String?       // value for a single key
    fun getModules(): Map<String, String>  // full flat map
    fun isProotInstalled(): Boolean     // checks $PREFIX/bin/proot
}
```

- Real path: `$HOME/.android_server_registry` (via `TermuxConstants.TERMUX_HOME_DIR_PATH`,
  NOT `context.filesDir`).
- Real format: `key=value` per line, written by the bash scripts (`modulos/*.sh`) during
  installation — real keys like `<module>.installed=true`, `<module>.version=X.Y.Z`,
  `<module>.install_mode=gpu`, etc. (the exact prefix varies by module, see
  `docs/modulos/<MODULE>.md` for each one's real key list).
- **There is NO persisted `.status=RUNNING/STOPPED` field here** — the "running" state is
  never saved to the registry, it's computed live every time via `ModuleController.isRunning()`
  (section 2).
- `getModules()` returns a flat `Map<String, String>` — there's NO `ModuleInfo[]` class
  populated from here; `ModuleInfo` (see `model/ModuleInfo.kt`) is a separate data class,
  populated from `modules.json` combined with the registry, with
  `enum Status { NOT_INSTALLED, INSTALLED_STOPPED, RUNNING, INSTALLING, ERROR }`.

## Real `ProcessBuilder` contract (used by `ModuleController` and every Fragment that runs scripts)

```kotlin
val pb = ProcessBuilder("bash", script, "--silent")
pb.applyTermuxEnv()   // see util/ProcessBuilderExt.kt — HOME/PREFIX/PATH/LD_LIBRARY_PATH/SHELL
                       // the app's Java process does NOT inherit Termux's PATH by default
pb.redirectErrorStream(true)
val process = pb.start()
```

- `applyTermuxEnv()` is mandatory for any `ProcessBuilder` invoking a Termux binary (bash,
  python3, git, tmux, pgrep, adb, etc.) — without it, the Java process inherits Android's
  PATH instead of Termux's, and fails with `Cannot run program ...: error=2`.
- The exit code is checked post-execution; some callers also use
  `decodeExitSignal()` (`ProcessBuilderExt.kt`) to translate exit codes 128+N into real
  POSIX signal names (e.g. "SIGSEGV") instead of showing the raw number.
