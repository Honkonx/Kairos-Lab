# Real bug history — embedded X11

This document summarizes real bugs found and fixed during several code audits of Kairos's
embedded X11 subsystem, including their confirmed root cause and the fix applied.

## Viewer intents missing `FLAG_ACTIVITY_NEW_TASK`

After fixing the critical shared-`taskAffinity` bug (see [X11_EMBEBIDO.md](X11_EMBEBIDO.md)), it
was found that three different entry points into the X11 viewer screen (installing and starting
native XFCE4, starting the native desktop, starting the desktop inside a distro) built the launch
`Intent` without the `FLAG_ACTIVITY_NEW_TASK` flag — exactly the flag needed for the viewer's
dedicated `taskAffinity` to take effect reliably. Without it, those three paths could silently
reintroduce the original bug (the viewer sharing a task with the main activity). The flag was
added at all three points, with a cross-referencing comment to prevent a future fourth entry
point from repeating the same oversight.

## Missing partial WakeLock

Comparing Kairos's architecture against a reference project with an equivalent design (an
embedded X server running in its own Android process via a foreground service), it was found that
project acquires a partial `WakeLock` while the process is alive — Kairos did not, leaving the
server exposed to being silently killed by Android in the background (the system's "Phantom
Process Killer"/Doze can terminate background processes even for a foreground service). WakeLock
acquisition was added on service start and release on stop, with defensive handling (the server
keeps working even if acquisition fails, just without that extra protection).

## Behavior finding, not a regression

Whether updating the vendored module to the latest upstream project version would be feasible was
reviewed exhaustively. The conclusion is that **it is not feasible today**: the newer version
requires a major build tool version (Android Gradle Plugin 9.x, while Kairos runs 8.x), ships no
precompiled native binary at all (the X server would need to be compiled from scratch via the
NDK, exactly what the current integration deliberately avoids), and several methods Kairos's own
subclasses call directly no longer exist with the same signature in the newer code — it would
require rewriting, not just recompiling. The only reasonably portable piece identified is a
self-contained diagnostic logging module, small in size — an optional, non-priority improvement.

## Missing manifest declarations for Input Control Activities (confirmed crash)

The vendored module includes a set of Activities related to input control profiles (custom
gamepad/touch controls), inherited from the lineage the module was copied from. None of the three
were declared in the application manifest — when the user tapped the settings dialog for a
control profile, Android threw an uncaught "activity not found" exception, closing the entire
application. **Root cause confirmed** by the complete absence of those three classes in the
manifest. **Fix**: the three necessary Activity declarations were added, each with the
appropriate visual theme depending on whether its layout brings its own toolbar or relies on the
system theme — without modifying a single line of the vendored module.

## Second bug in the same flow: NullPointerException on "Open Controller"

After the previous fix, a real usage report on a device confirmed the same flow was still
failing — this time for a **different** cause. The input control configuration dialog assumes
certain fields (references to the real viewer, the controls manager, the input view) are already
initialized — but those fields only get initialized when the Activity opening the dialog is the
real desktop viewer. Kairos's standalone "X11 Configuration" screen is a different subclass that
never goes through that initialization, so those fields stayed null and the dialog threw a null
pointer exception on its very first line.

**Fix**: without modifying the vendored module, the method was overridden in Kairos's own
subclass to check those fields before delegating to the original implementation — if they're
present (real case: opened from the viewer), it delegates normally; if absent (real case:
standalone screen), it shows a notice asking the user to open the desktop viewer first, instead
of crashing. Known, accepted limitation: the standalone screen still can't configure input
controls directly — the user needs to open the viewer first. Truly fixing this (letting the
standalone configuration screen edit profiles without the viewer open) would require changes
inside the vendored module itself, out of scope while it remains protected code.

## Wayland/XWayland investigation in reference projects — no real findings

Two reference projects were investigated to see whether they mentioned real Wayland/XWayland
support that could be adopted. The conclusion, confirmed by reading the source code (not just
those projects' documentation): both are either the same termux-x11/Xlorie lineage Kairos already
uses, or a rewrite in a different framework of that same underlying technology — the
"wayland"/"xwayland" mentions that show up when grepping the code are protocol files from Xorg/X
protocol upstream itself, vendored but unused, the same kind of files that already exist unused
inside Kairos's own module. There's no real Wayland support to port from those projects. Building
a real Wayland compositor from scratch is a months-long project, not something that can be
borrowed.

## VNC configuration from the UI

Before this change, the app only offered installing/starting/stopping the VNC server with fixed
parameters (resolution, color depth) set once during module installation. A real configuration
option was added: a dialog that lets the user choose resolution, color depth, and whether the
server should require a password — validating values against the options the underlying VNC
server actually supports before applying them, and explicitly warning the user of the real risk
if they choose not to require a password (though the connection stays limited to the device
itself). The port/display was deliberately not made configurable: it's fixed to the same display
used by embedded X11, and exposing it as an option would break that alignment with no real
benefit.

## Manifest permission verification

It was confirmed that the permissions the X11 flow actually needs are already declared at the
minimum required (foreground service, service type, notifications, WakeLock) — and that the X11
module doesn't use any screen-overlay permission (the viewer's floating button is a normal view
inside the activity itself, not a system overlay).
