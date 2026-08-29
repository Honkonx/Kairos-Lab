# Embedded X11 — X11 server (Xlorie/termux-x11) inside the Kairos APK

## Goal

Kairos can open a full X11 server **inside its own APK**, without needing to install the
official termux-x11 companion APK. From the "Mini PC" section of the main menu, a button starts
the Xlorie server in a separate Android process (`:xserver`) within the same APK and opens the
viewer (`LorieView`/`MainActivity`).

## Code origin

Kairos's `:x11-server` module is a fork of the official `termux/termux-x11` project — more
specifically, of an intermediate "Linbox" fork (oriented toward running Wine/Windows games on
Termux), which contributes the `com.termux.x11.*` class tree, its resources, and a precompiled
`libXlorie.so` for all four Android ABIs. This lineage was chosen because it's self-consistent
(the same source tree that produced the binary `.so`) and because the official upstream
integration patch for termux-app requires AGP 9.x, while Kairos still runs on AGP 8.x.

## The `:x11-server` module

- Path: `x11-server/` (standalone Gradle module, namespace `com.termux.x11`).
- Contains the `com.termux.x11.*` classes (`CmdEntryPoint`, `MainActivity`, `LorieView`,
  `LoriePreferences`, input controllers, extra-keys, utilities, Wine handler), resources, AIDL
  definitions, and `libXlorie.so` for each ABI.
- The upstream `cpp/` tree is deliberately excluded — Xorg is not compiled from source, the
  precompiled `.so` is used instead — as are the Wine assets (tens of MB) that a pure X viewer
  doesn't need.

## Integration into the main app

- The app manifest declares the X11 `MainActivity` (not exported, `singleTask`, handles full
  configuration changes and picture-in-picture) and an `X11Service` (not exported, running in its
  own `:xserver` process, foreground service type `dataSync`).
- `X11Service.start(context)` triggers a `startForegroundService`, which spins up the separate
  `:xserver` process. In its `onCreate()`, the service creates the notification channel and
  launches a thread that prepares an Android `Looper` and calls
  `CmdEntryPoint.main(arrayOf(":1"))` — the native entry point that starts the real X server and
  blocks the thread while it runs.
- The user entry point is the app's "Mini PC" section: it shows server status (whether the
  `:xserver` process is alive), an "Enter X11" button that starts the service and opens the
  viewer, an "X11 Configuration" screen (resolution, scale, forced orientation, keyboard, touch,
  picture-in-picture, fullscreen), and a button to stop the server (which broadcasts internally so
  the viewer closes and then stops the service).
- The connection between the `:xserver` process and the viewer (main app process) happens via an
  internal broadcast that carries an AIDL binder — the viewer receives that binder and connects
  the real X socket.
- Pressing "back" in the viewer shows a dialog with two options: **Minimize** (the server keeps
  running) or **Close X11 server** (stops everything).

## Patches applied over the inherited code

1. **Native library loading**: the original code resolved the `.so` with
   `ClassLoader.getResource()` + `System.load(path)`, which requires disabling native library
   extraction from the APK. Changed to `System.loadLibrary("Xlorie")`, which resolves the library
   from the app's standard native directory and is compatible with normal Android packaging.
2. **Removal of hidden internal Android APIs**: a fallback mechanism that relied on internal
   framework classes not available in the public SDK was removed — it's unnecessary because the
   server runs in the app's own process, where normal broadcast sending always works.
3. **Android context retrieval via reflection**: an internal framework class (`ActivityThread`) is
   accessed via reflection instead of a direct cast, since it's not part of the documented public
   API — it works the same at runtime.
4. Kotlin/Java interop adjustments (`@JvmStatic` annotations) to allow invoking the service from
   the legacy Java code inherited from the termux-app fork.
5. Obfuscation (ProGuard) rules that preserve the X11 module classes, which are invoked via
   reflection/manifest.

## Critical bug fixed: shared task with the main activity

`MainActivity` (the X11 viewer) initially had no dedicated `taskAffinity` and shared the same
system "task" with the app's main activity — as a result, "Minimize" or "Close" from the viewer's
exit dialog would kill the entire application, not just the X11 viewer. The fix: give the viewer
a dedicated `taskAffinity` plus `FLAG_ACTIVITY_NEW_TASK` on the launching `Intent`, along with two
custom subclasses that adapt the inherited fork's behavior to Kairos's context (which doesn't
have the same `TermuxActivity` host the original project expected).

## Touch input bug

A review comparing three versions of the code (the official project, the intermediate fork the
module was copied from, and Kairos) confirmed that the real touch event handler had been
commented out since the module was first integrated — Kairos inherited the bug from the fork
rather than introducing it. Full event dispatch (touch, hover, generic motion, captured pointer)
was restored.

## Desktop startup: root cause of connection failures

After confirming the viewer itself (display, touch input) worked, it was found that no X client
(neither a native XFCE4 desktop nor one running inside a Linux distro via proot) could connect or
render. Investigation, comparing the source code that produced the embedded `.so` against the
code of three related projects, found two combined causes:

1. **Unset `TMPDIR` on the server side.** The native code that resolves the X server's Unix
   socket (`$TMPDIR/.X11-unix/X<display>`) falls back to an unset `TMPDIR`, first checking whether
   `/tmp` exists. Since `X11Service` is a pure Android `Service` (with no Termux shell involved),
   it never set that variable, so the result depended on a non-deterministic device detail — if
   the check resolved to `/tmp` (instead of Termux's real temp directory), the server published
   the socket somewhere no client would ever find it. **Fix**: explicitly force `TMPDIR` (using
   Android's process-level environment API, not child-process environment variables) before
   invoking the native server startup.
2. **Missing `LD_PRELOAD` on the client side.** The mechanism that launches the X client (e.g.
   `startxfce4`) invokes a non-interactive shell, so Termux's profile files never load — and with
   them, the path-interception mechanism (`termux-exec`) that Termux needs so hardcoded paths like
   `/tmp/.X11-unix/X<n>` get redirected to the app's real directory. **Fix**: explicitly export
   `LD_PRELOAD` pointing to Termux's interception library before launching the graphical client.

A diagnostic check was also added: before launching any X client, the code verifies that the Unix
socket actually exists in the expected directory.

## Embedded VNC viewer

As an alternative to embedded X11 (for cases where the user prefers a simpler or more widely
compatible protocol), Kairos includes its own VNC viewer, implemented from scratch following the
RFB protocol specification (RFC 6143) — there is no reusable native-Kotlin VNC client to build
on, so one was written from the ground up:

- **Pure Kotlin RFB client** — full handshake (version negotiation + VNC authentication with DES
  challenge), pixel format fixed at 32-bit ARGB, and **Raw encoding only** (the only one
  universally supported without negotiating compression — acceptable here because client and
  server run on the same device over loopback, where bandwidth isn't the bottleneck). More
  efficient encodings (Hextile, Tight) remain a documented future improvement, not implemented.
- An Android view that draws the received framebuffer (letterboxed) and translates touches into
  RFB pointer events.
- A screen that connects to the local VNC server, prompts for a password if the server requires
  one, and maps the Android keyboard to X11 keysyms (direct ASCII and basic control keys — no
  support yet for dead keys, accents, or non-QWERTY layouts).
- **Bidirectional clipboard sync**: a full implementation of the RFB `ServerCutText`/
  `ClientCutText` messages (RFC 6143 §7.5.6) in both directions, subject to the classic protocol's
  real limitation of only supporting Latin-1 text (characters outside that range are substituted).

The viewer requires the VNC server (TigerVNC) to already have a password configured in order to
start non-interactively — if one was never set, the server startup can fail before reaching that
negotiation.

## Application menu inside the desktop

So that Kairos modules (AI agents, development tools, etc.) are accessible from inside the
graphical desktop as normal applications, Kairos generates `.desktop` files (the standard
freedesktop.org format) both in the user's desktop folder and in the standard XDG path that
XFCE4/LXQt/MATE scan to build the real "Applications" menu (`~/.local/share/applications/`). Each
entry carries a real category (Development, AI, System, Network, etc.) taken from the module
catalog, so the desktop's native application menu already groups/filters modules by category
without needing any custom panel. See [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md) for the full
design of this feature.

## Equivalent manual commands

For reference, these are the Android (`am`) commands equivalent to what the Kairos UI runs
internally:

```sh
export DISPLAY=:1
am start-foreground-service -n com.termux/.app.X11Service
sleep 2
am start --user 0 -n com.termux/com.termux.x11.MainActivity  # opens the viewer
```

Stopping the server:

```sh
am broadcast -a com.termux.x11.ACTION_STOP -p com.termux
am stopservice -n com.termux/.app.X11Service
```
