# Embedded X11 server infrastructure audit

Scope: the embedded X11 server infrastructure itself (`x11-server` module, the `X11Service`
service, the `:xserver` process lifecycle, the socket, and the GPU layer) — not the Mini PC user
interface, nor the X11/Wayland/VNC/proot-distro comparison (see
[AUDITORIA_XLORIE_WAYLAND_2026-08-25.md](AUDITORIA_XLORIE_WAYLAND_2026-08-25.md)).

## Which fork the module actually comes from

Comparing the file structure confirms the vendored tree is **not** the official termux-x11
project: it's missing a few pieces from the official project (a custom loader, the original extra
keys package), and has an entire Wine-oriented tree on top (Windows process handler, container,
compression utilities, Windows registry editor) plus Meta Quest/XR viewer support — confirming
that the real vendored tree comes from a fork oriented toward running Wine/Windows games on
Termux, not a direct copy of the official pure-Linux-desktop repository. The embedded precompiled
`.so` is self-consistent with that same source tree (the exact commit/version of upstream
termux-x11 the fork is based on couldn't be confirmed, since no changelog exists in the
intermediate project and the binary is precompiled).

Kairos's own patches on top of this fork (native library loading via `System.loadLibrary`,
removal of the internal-Android-API fallback, context retrieval via reflection, Kotlin/Java
interop annotations, obfuscation rules) were confirmed present in the real code, with no drift
from the documentation.

## `:xserver` process lifecycle

Startup: `X11Service.start(context)` starts a foreground service that creates the separate
Android `:xserver` process. On creation, it acquires a **partial WakeLock** (added to prevent the
system from silently killing the background process — a real risk on modern Android, where the
"Phantom Process Killer" can terminate background processes even for a foreground service),
forces the `TMPDIR` variable (see [X11_EMBEBIDO.md](X11_EMBEBIDO.md)), and starts the thread that
boots the native X server.

**Improved "server alive" detection.** The original check only confirmed the Android *process*
existed (via a system command that searches processes by name), not that the X server was
actually accepting connections on the socket — a real window between "the process exists" and
"the socket is published and accepting clients." A lightweight additional check was added (a
direct file-existence check on the socket, with no external process invocation), and the server's
"running" state now requires both conditions. This significantly narrows the false-positive
window without adding perceptible latency.

**No explicit crash/restart handling.** If the native server process terminates prematurely,
there is no proactive detection or automatic restart mechanism — the user would have to notice the
server stopped responding and start it manually again. The service doesn't override Android's
default restart behavior (`START_STICKY`), so if the system kills the process under memory
pressure, it's plausible Android would revive it automatically — but this hasn't been empirically
confirmed.

**Shutdown**: since the native server exposes no clean-stop mechanism of its own, stopping the
service ends up force-terminating the entire `:xserver` process — safe, since it runs isolated in
its own process without affecting the rest of the app.

## GPU: two completely separate layers

An important finding of this audit is that the embedded viewer's rendering and the app's
configurable GPU acceleration are two completely different things that shouldn't be conflated:

1. **Embedded viewer rendering**: real compositing happens inside the precompiled native X server
   binary (`libXlorie.so`), opaque from the Java/Kotlin code — there's no indication this path
   uses any of the app's configurable GPU options.
2. **GPU for X clients inside Termux/proot**: the GPU method selection the app does expose
   (auto, software, Zink, VirGL, etc.) installs Mesa/VirGL/Zink packages inside the Termux/proot
   environment so that *client applications* running inside the desktop (for example, a program
   using OpenGL) get acceleration — this is client-side X acceleration, not server/viewer-side.

GPU hardware detection is based on the chip platform name reported by the vendor (a heuristic
matching known name patterns for Adreno/Mali/Xclipse families), not a real query of
Vulkan/OpenGL capabilities — reasonable as a basis for a recommendation menu, but it can
underestimate hardware with atypical platform names (in that case it falls back to a safe generic
software profile).

## Socket and connection: two distinct paths

There are two distinct connection paths to the same X server, not one:

- **Real X clients inside Termux** (for example, a native desktop launched from the app) connect
  through the traditional filesystem Unix socket.
- **The app's own embedded viewer** (main app process) doesn't use that filesystem socket at
  all — it connects via Android IPC (Binder/AIDL): the X socket's file descriptor is transported
  directly that way, without opening a second filesystem socket for the viewer.

## Security consideration: `sharedUserId`

Kairos declares a `sharedUserId` inherited from the original Termux project (a compatibility
requirement of the bootstrap). Any app installed with that same `sharedUserId` runs under the
same Linux system UID, and could therefore in theory read/write the filesystem X socket directly,
bypassing any Android mechanism (Binder, export permissions). However, Android requires a shared
`sharedUserId` to be signed with the same cryptographic key as the app that first declared it —
it's not "any app can join by naming the same identifier," it requires being signed with the same
certificate. The real risk is therefore bounded: only another app built and signed with the same
key as Kairos/Termux could exploit this path. The X socket itself has no additional
authentication (no `xauth`/MIT-MAGIC-style cookies) — it's fully trusted for any process that
manages to open it. This is a low-probability but real risk vector, not mitigated by any
additional control on the socket side, and not previously documented.

## Overall verdict

**Functional, with non-trivial robustness gaps, no known blocking bugs left unfixed.** The
viewer (rendering + input) is confirmed working on a real device. Desktop startup has fixes
applied with a root cause well grounded in reading the source code of multiple related projects.
The gaps identified in this audit are about robustness, not broken functionality: "alive"
detection improved but doesn't close the theoretical window entirely, there's no explicit
crash/restart handling for the native process, and there is a direct-socket-access vector via a
shared `sharedUserId` (low risk, bounded by cryptographic signing, but real). None of the three
justify blocking anything — they are candidates for future hardening, not active incidents.
