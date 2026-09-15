# Mini PC Architecture

Kairos turns the phone into a portable mini PC: a full Linux environment, with a real graphical
desktop and GPU acceleration, running inside the app itself — without depending on an external
server, a third-party app, or another computer. The module that manages all of this is called
**Entorno** ("Mini PC" tab in the app) — see `../modulos/entorno.md` for the full detail of
controls, installation checkpoints and generated scripts; this document covers the architecture
that coordinates them.

## 1. The coordinated subsystems

| Subsystem | What it does | Dedicated document |
|---|---|---|
| **Embedded X11 server** | Renders the graphical desktop (XFCE4, MATE, LXQt...) inside the APK itself, without any external app | `../x11/x11-embedded-architecture.md` |
| **proot-distro** | Installs and manages full Linux distros (Ubuntu, Debian, Alpine, Kali, Arch...) without root, using user namespaces | Section 3 of this document |
| **Embedded VNC** | Alternative/backup to the native X11 viewer, with its own RFB client inside the app | Section 4 of this document |
| **GPU detection** | Identifies the device's SoC and installs the matching accelerated driver | `aceleracion-gpu.en.md` |

All four are installed together as base infrastructure when the Entorno module is activated;
after that, each one is turned on/off separately from the "Mini PC" screen, with no need to
reinstall anything.

## 2. Two paths: native vs. with distro

Kairos offers two ways to have a graphical desktop, aimed at different needs:

| Path | What it is | When it's the right fit |
|---|---|---|
| **Native** (recommended) | A desktop environment (XFCE4, MATE, LXQt) running directly on top of Termux's own binaries, without a full Linux distro in between | Everyday use — lighter, starts faster, doesn't require downloading a full system |
| **With distro** | A desktop running inside a full Linux distro (`proot-distro`: Ubuntu, Debian, Kali, Arch...) | When a real isolated system is needed — for example, Ciberseguridad module tools that expect a full Kali environment |

Both paths share the same embedded X11 server (same `:1` display, same socket) — only one can
have an active desktop session at a time; the app detects the conflict and warns before letting
a second session start on top of the other.

## 3. Distro management (proot-distro)

`proot-distro` runs full Linux systems without root, using user namespaces instead of
privileged containers — the same mechanism regular Termux uses for this. From Kairos's "Mini PC"
screen, without typing a single command:

- **Install/remove a distro** — a catalog of supported distros (Ubuntu, Debian, Alpine confirmed
  stable; Arch Linux, Fedora, Void, Kali, Manjaro, Rocky Linux, openSUSE Tumbleweed also
  available).
- **Install/remove a desktop inside the distro** — the same graphical environment picker
  (XFCE4/LXQt/MATE/KDE) as the native path, applied inside the chosen distro.
- **Terminal login** — opens a real console inside the distro, for anyone who prefers working on
  the command line instead of the graphical desktop.
- **Distro backup** — packages the whole distro into a `.tar.gz`.
- **Link folders** — shares `~/scripts` and `~/proyectos` from Termux's filesystem with the
  distro, without copying files.
- **Desktop app catalog** — install curated applications (Firefox, GIMP, LibreOffice, Blender,
  OBS Studio and others) inside the distro with one button, without having to memorize the exact
  package name.
- **Recommended profile** — automatically builds a distro + desktop + GPU method combination
  based on the RAM and GPU detected on the device.

## 4. VNC as an alternative

VNC (RFB protocol) is available as a secondary/backup path alongside the native X11 viewer,
useful when connecting with an already-familiar client or from another device on the same
network is preferred. Kairos includes:

- A one-button installable VNC server (TigerVNC), configurable for resolution, color quality and
  password from the app's own UI.
- **Its own VNC client embedded in the app** — an RFB 3.8 protocol implementation written for
  Kairos, with no dependency on any external app to connect to the server. It also supports
  connecting with any third-party VNC client to `127.0.0.1:5901`.

## 5. Current status

- **Native path**: works end-to-end, confirmed on a real device — full desktop with icons,
  taskbar and applications rendering correctly, including GPU acceleration with the correct
  driver per manufacturer (see `aceleracion-gpu.en.md`).
- **Installing a desktop inside a distro**: works end-to-end, confirmed on a real device (full
  installation of an XFCE4 desktop inside a Kali distro).
- **Known limitation**: in some distro + desktop combinations, *starting* an already-installed
  desktop session inside a distro can fail. The identified cause is a sandboxing conflict: modern
  GTK/GNOME-based graphical environments load vector icons through a mechanism that isolates each
  load in its own Linux namespace — isolation that can clash with the already-nested namespaces
  of running a full distro inside `proot` on Android. This is a known limitation, under
  investigation — it doesn't affect the native path, which doesn't go through that sandboxing
  layer.

## See also

- `../modulos/entorno.md` — the full Entorno module screen, every control, and the base
  infrastructure installation logic.
- `../x11/x11-embedded-architecture.md` — internal architecture of the embedded X11 server
  (Android process, socket resolution, background stability).
- `aceleracion-gpu.en.md` — GPU detection by manufacturer and installed drivers.
