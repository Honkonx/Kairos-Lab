# PHANTOM_PROCESS_KILLER.md — Android 12+ Phantom Process Killer

## The problem

Android 12+ introduced the "phantom process killer": a system mechanism that kills background
processes whose originating app is no longer "in the foreground" — meant for generic
orphaned/zombie processes, but it hits Termux/Kairos hard because every active module
(Ollama `serve`, n8n, OpenClaw, every per-CLI terminal session via tmux) is exactly that
pattern: a long-running child process that stays alive even though the app's Activity isn't on
screen. With several modules active at once, it's easy to cross the threshold that triggers the
killer. An unexpected `SIGKILL` on a module (detectable via `decodeExitSignal()` in
`ProcessBuilderExt.kt`) is the typical sign that it happened.

## The 3 paths implemented in Kairos

All of them live in `PhantomProcessKillerHelper.kt` (`app/src/main/java/com/termux/app/util/`), with
2 entry points in the UI:

- **Wizard**: a dedicated first-launch step, with paths (b)/(c)/(d) below offered as explicit
  options — the beta path (auto-detection) comes first/recommended (less manual data),
  the guided path second (fallback if beta fails), the tutorial third. Doesn't block wizard
  progress.
- **Monitor → DIAGNOSTICS section**: same engine, to re-run it later (some devices need it
  repeated after a reboot) or if the wizard step was skipped. First tries (a) silent root; if
  that fails, offers the same 3 paths in an options dialog.

### (a) Silent root attempt — first step, automatic

First tries `su -c "settings put global settings_enable_monitor_phantom_procs false"`. If
the device is rooted, the user never sees any dialog — it just works. If it fails
(no root, or no `su` permission), it moves on to offering the next 2 paths.

### (b) Guided PC-free path, via wireless ADB — the main path for non-rooted devices

When you run `adb shell <command>`, that command runs as Android's `shell` user, which
already natively has the `WRITE_SECURE_SETTINGS` permission — no need to "grant" it
to Termux or Kairos, just an authenticated `adb shell` session. Android 11+ allows that
session entirely from the device itself via "Wireless debugging", no cable or PC needed.

**Real flow:**
1. If Developer Options aren't enabled, it explains how to enable them (7 taps on "Build
   number").
2. Opens Developer Options and asks for 3 pieces of data that **only Android shows on its
   own native screen** — there's no public API to read them programmatically: pairing
   port, 6-digit code, connection port (the first two appear when tapping "Pair device with
   pairing code" inside Wireless debugging; the connection port is at the top of the
   main screen, next to the IP).
3. Runs the full sequence:
   ```
   pkg update -y
   pkg install -y android-tools
   adb pair 127.0.0.1:<pairing_port> <code>
   adb connect 127.0.0.1:<connection_port>
   adb shell "settings put global settings_enable_monitor_phantom_procs false"
   adb shell "device_config set_sync_disabled_for_tests persistent"
   adb shell "device_config put activity_manager max_phantom_processes 2147483647"
   adb disconnect
   ```
4. Before disconnecting, it also runs the 4 background-survival reinforcement commands
   (see below).
5. **Never trusts the exit code** — it rereads the real value with
   `settings get global settings_enable_monitor_phantom_procs` (must return `"false"`) and
   `device_config get activity_manager max_phantom_processes` (must return `"2147483647"`) before
   reporting success.

**Hard, unavoidable limitation**: the pairing port, the code, and the connection port are
only ever exposed on Android's own native screen — no mechanism (ADB, Shizuku, not even
root) can read them via API. The user always has to see that screen once and transcribe
those 3 values by hand.

### (c) Beta path with port auto-detection — cuts manual input from 3 fields to 2

Instead of asking for the connection port by hand (the value that changes every time
Wi-Fi/debugging is toggled, the most error-prone one), it's detected with
`nmap -sT -p 10000-65535 --open -T4 127.0.0.1` against the device's own local IP,
trying `adb connect` against each open port found until one accepts the connection.
Requires the `nmap` package — it's installed first, with a "installing required package"
notice before offering to open Wireless debugging, so the user understands why that extra step
takes time. Explicitly marked "beta" because the scan range can take several
seconds and the real port doesn't always fall in the same range across every manufacturer.

### (d) Tutorial — leads with the native toggle, ADB as a fallback

Shared by Monitor and the wizard. The text leads with the native toggle ("Disable child
process restrictions"/"Desactivar restricciones de procesos secundarios" in Spanish, look
for it under Developer Options) and leaves the ADB commands (with a "Copy ADB commands" button)
as an alternative for devices that don't have it.

## The 4 background-survival reinforcement commands

Run in the same ADB session as paths (b) and (c), right before `adb disconnect`:
```
adb shell "dumpsys deviceidle whitelist +com.termux"
adb shell "cmd appops set com.termux RUN_IN_BACKGROUND allow"
adb shell "cmd appops set com.termux WAKE_LOCK allow"
adb shell "am set-inactive com.termux false"
```
These target the same underlying problem through 3 mechanisms the phantom process killer fix
does NOT cover: Doze/App Standby exclusion (without the system dialog that the battery
exemption needs), `appops` permissions independent of what's declared in the manifest, and
immediately pulling the app out of the "inactive" state. `com.termux` is Kairos's real
`applicationId` (same `sharedUserId` as the original Termux), so these commands apply without
adapting names.

## Android 14+'s native toggle — the only real exception without ADB

On Android 14+ there's a **native** toggle in Developer Options ("Stop restricting
child processes") — zero ADB, zero Termux, a single tap. You don't need to scroll to
the bottom of the screen — it's near "Reset ShortcutManager rate-limiting". **Caveat**: not
every manufacturer exposes it (there are reports of devices without it even on Android 14+) —
worth checking first, but it can't be relied on as always available.

## Why there's no 100% ADB-free path

`WRITE_SECURE_SETTINGS`/`WRITE_DEVICE_CONFIG` are signature/system permissions
(`protectionLevel="signature|privileged"`) — Android denies them to any regular app without
exception by design, Termux/Kairos included (they run with the same restricted UID as any
other app). There's no documented route that avoids ADB or root to modify these settings
from a third-party app.
