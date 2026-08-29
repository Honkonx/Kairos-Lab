# Entorno module architecture (proot-distro, GPU, VNC)

The **Entorno** module is the foundation of Kairos's "mini PC" functionality: it manages full
Linux distributions inside rootless `proot-distro` containers, GPU rendering method selection,
desktop application launchers, and the exclusivity between Termux's native desktop and a desktop
running inside a distro. This document describes the real architecture of that layer and the
concrete technical issues that were identified and fixed as the feature matured.

## 1. Distribution management (`proot-distro`)

Every operation on a distribution (install, remove, install an app inside a distro, remove an
app) follows the same validation contract before running anything: check that the distro name
is known and that the `proot-distro` binary is available. Functions that depend on the distro
already being installed (installing a desktop environment inside it, starting it, backing it up,
mounting the project bridge) use a stricter criterion: they resolve the actual installed
distro's directory instead of just validating the name.

| Operation | Requires "known name" | Requires "distro actually installed" |
|---|---|---|
| List distros | — | — |
| Install distro | Yes | — |
| Remove distro | Yes | — |
| Install desktop in distro | Yes (via directory resolution) | Yes |
| Start desktop in distro | Yes (via directory resolution) | Yes |
| Install/remove app in distro | Yes | — |
| Back up distro | Yes (implicit) | Yes |
| Mount project bridge | Yes (implicit) | Yes |

When either validation fails, the app returns an actionable message (e.g. "proot-distro not
available — install Entorno first") instead of propagating the raw error from the underlying
process.

Every install/remove operation for an app inside a distro persists its state to a local JSON
file (`distro_apps.json`). Since these operations can be triggered from independent threads
(for example, installing one app while removing another from a different dialog), the
read-modify-write cycle on that file is protected by a dedicated file lock, preventing race
conditions that would otherwise corrupt the persisted state.

## 2. Exclusivity between the native desktop and a desktop inside a distro

Both modes — the native desktop on top of Termux and a desktop inside a proot distro — share the
same embedded X11 server, so only one can be active at a time. The mechanism that guarantees
this:

- Before starting either mode, it checks against whichever mode is currently active, if any.
- Active state isn't based solely on a persisted flag: it's verified with `pgrep` that the real
  process is still alive. If the flag became stale (for example after an unexpected crash or an
  external kill), it's cleaned up automatically instead of permanently blocking the user.
- If the user tries to start one mode while the other is active, the UI offers to stop the active
  session and retry the original action in a single step, instead of leaving a dead end.

## 3. Desktop launchers and module uninstallation

Kairos automatically generates `.desktop` launchers (application menu entries plus optional
autostart entries) for every CLI installed through the app. When a module is uninstalled, those
launchers are removed along with its other files and its registry entry — otherwise they'd be
left as broken desktop icons pointing to a command that no longer exists.

## 4. GPU acceleration

The rendering method (pure software, Zink, VirGL, depending on the hardware) is chosen by the
user and applied through standard Mesa environment variables (`GALLIUM_DRIVER`,
`MESA_GL_VERSION_OVERRIDE`, etc.). An important architectural detail: `proot-distro` starts a
clean environment for every session — variables exported in the host shell are **not**
automatically inherited inside the distro login. Because of that, when the user enables GPU
acceleration, those variables are passed explicitly as part of the login command's own
environment (`proot-distro login ... -- env <variables> <command>`), for both the native and the
distro mode. Without this explicit step, a desktop inside a distro always renders in pure
software (`llvmpipe`) regardless of which GPU method the user chose.

## 5. The X11 server runs in a separate Android process — startup implications

The embedded X11 server (based on a fork of termux-x11) runs in its own Android process,
separate from the app's main process. Its startup is asynchronous: the Unix socket it exposes
(`$PREFIX/tmp/.X11-unix/X<n>`) isn't guaranteed to exist the instant the command that starts the
desktop environment is fired. If an X client tries to connect before the socket exists, the
connection fails immediately ("Can't open display") and the desktop never comes up — a symptom
that reads as a black screen or "nothing happens."

The fix is a bounded retry loop (up to 10 seconds) that actively waits for the socket to exist
before attempting the desktop environment's login/startup, for both the native path and the
inside-a-distro path. The "is the process still alive?" check the app runs to confirm startup
succeeded uses a larger time margin than that retry loop, to avoid reporting a false negative on
a startup that's still in progress.

## 6. VNC — password requirements and progress feedback

TigerVNC requires an interactive password the first time it's used, unless told otherwise
explicitly. Since Kairos launches the process without an interactive terminal available to
answer that prompt, if a password file hasn't been generated yet (`~/.vnc/passwd`), VNC startup
is automatically configured without authentication (`-SecurityTypes None`). This is safe in this
specific context because the VNC server only binds to `localhost` and the viewer bundled with the
app only ever connects to that same local address — the passwordless session is never exposed to
the network. If the user sets a real password, that configuration is respected unchanged.

Both installing TigerVNC and starting the desktop server show live progress in the UI (instead
of a static indicator that doesn't update for the entire operation), consistent with GPU
acceleration and the rest of the module's long-running operations.

## 7. Actionable error handling

When a long-running operation fails (for example, installing a desktop environment inside a
distro due to a network issue or lack of disk space), the interface shows the real error detail
— not just a generic message — by capturing the underlying process's output instead of
discarding it. This turns what used to require reproducing the problem just to diagnose it into
a single attempt.

## 8. Orderly shutdown of desktop sessions

When stopping a desktop session in order to retry starting the other mode, the shutdown process
waits, within a bounded window (up to a few seconds), for the processes to actually terminate
before reporting success, instead of assuming a termination signal implies an immediate stop.
This prevents a quick retry from colliding with processes that technically already received the
shutdown signal but hadn't yet released the X11 display.
