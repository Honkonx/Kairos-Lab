# Embedded X11 server

Kairos includes a full X11 server running inside the APK itself — it doesn't depend on an
external app or a third-party VNC server. This is what makes a graphical Linux desktop (XFCE4,
MATE) possible directly on the phone, both in native mode over Termux and inside a full distro
via `proot-distro`.

## Code origin

The `x11-server/` module is a fork of [afeimod/linbox](https://github.com/afeimod/linbox)
(itself based on [termux/termux-x11](https://github.com/termux/termux-x11), the upstream
Xlorie/termux-x11 project) — it keeps the real `com.termux.x11.*` package tree and the
precompiled native binary `libXlorie.so` per ABI (`x11-server/libs/<abi>/libXlorie.so`).

## Architecture

The server runs in a **separate Android process** within the same APK
(`android:process=":xserver"`, declared in the app's manifest) — it isn't an independent native
process like `Xorg` on Linux, it's a normal Android service that hosts the native X11 binary.

```
Main process (com.termux)               :xserver process
┌─────────────────────────┐             ┌──────────────────────────┐
│ X11Service.start()       │  Intent     │ X11Service.onCreate()     │
│  → startForegroundService│ ──────────▶ │  → Thread "x11-server"    │
│                           │             │     Looper.prepare()      │
│ MainActivity (viewer,     │             │     CmdEntryPoint.main([":1"])
│ com.termux.x11.*)         │◀── socket ──│       → libXlorie.so       │
│  connects to the display  │  X11        │       (embedded Xorg)      │
│  via ACTION_START         │             └──────────────────────────┘
└─────────────────────────┘
```

- `X11Service.kt` (`app/src/main/java/com/termux/app/X11Service.kt`) is the entry point:
  `X11Service.start(context)`/`X11Service.stop(context)` start and stop the service.
- On creation, it starts a dedicated `Thread` that calls `Looper.prepare()` followed by
  `CmdEntryPoint.main(arrayOf(":1"))` — `CmdEntryPoint`'s static block loads `libXlorie.so`
  via `System.loadLibrary("Xlorie")`, and the native `main()` runs the full X server inside that
  process.
- The display used is `:1` (the `X11Service.DISPLAY` constant).
- The viewer (`com.termux.x11.MainActivity`, accessible from the app's menu) connects to the
  server by listening for the `ACTION_START` broadcast, which `CmdEntryPoint` re-emits every
  second until there's an active connection.
- The server thread stays alive for as long as the service runs, because
  `CmdEntryPoint.main()` enters a blocking `Looper.loop()`.

## X11 socket resolution (`TMPDIR`)

The native binary (`cmdentrypoint.c`, compiled inside `libXlorie.so`) resolves the display's
Unix socket at `$TMPDIR/.X11-unix/X<display>`. If `TMPDIR` isn't explicitly set, the binary
itself tries an internal fallback (`/tmp`, and if that fails, `$PREFIX/tmp`) — but in a "bare"
Android process (the `:xserver` process, which doesn't go through Termux's normal shell) there's
no guarantee what that resolution returns on any given device. To eliminate the ambiguity,
`X11Service` forces `TMPDIR` explicitly before the native code reads it:

```kotlin
Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)
```

`android.system.Os.setenv()` (a direct wrapper around process-level `setenv(3)`) is used instead
of `System.getenv()`/`ProcessBuilder.environment()`, because only `Os.setenv()` is visible to
`getenv()` in native JNI code running in the same process. This ensures that both a native
desktop (`startxfce4` run directly over Termux) and one inside a distro (`proot-distro` with
`--shared-tmp`, which maps `$PREFIX/tmp`) find the same socket.

## Background stability

`X11Service` runs as a foreground service (`startForeground`) with a persistent notification,
and acquires a `PowerManager.PARTIAL_WAKE_LOCK` while the `:xserver` process is alive — without
this, Android mechanisms like the Phantom Process Killer (Android 12+) or Doze can kill the
process in the background with no warning. The wake lock is released in `onDestroy()`; it
carries no timeout because the whole lifecycle is already governed by `start()`/`stop()`.

## Supported desktops

The embedded X11 server is the shared graphical transport for two different usage modes:

- **Native mode** — a desktop environment (XFCE4, MATE) running directly over Termux binaries,
  with no full Linux distro involved.
- **Distro mode** — a desktop environment running inside a full Linux distro (`proot-distro`,
  Debian/Ubuntu/Arch), connecting to the same X server via the shared socket described above.

A VNC viewer (`VncViewerActivity`/`VncCanvasView`) is available as an alternative to the native
X11 viewer for anyone who prefers that connection route. `VncClient.kt` implements the RFB
protocol (RFC 6143) natively in Kotlin — full handshake, VNC authentication via DES challenge,
and **Raw** encoding (no Hextile/Tight compression) — sufficient because the client and server
run on the same device, connected over loopback (`127.0.0.1:5901`), where bandwidth isn't the
bottleneck.

## Upstream references

| Project | Contribution |
|---|---|
| [termux/termux-x11](https://github.com/termux/termux-x11) | Original Xlorie/termux-x11 project |
| [afeimod/linbox](https://github.com/afeimod/linbox) | Actual source of the fork used in `x11-server/` |
| [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop) | Shared-storage and per-distro GPU acceleration patterns |
| [LinuxDroidMaster/Termux-Desktops](https://github.com/LinuxDroidMaster/Termux-Desktops) | Desktop session teardown, per-distro+desktop-environment launch |
