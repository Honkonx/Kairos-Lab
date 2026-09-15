# Entorno (Environment)

**Kairos module** — Managed via the Kairos UI (Modules tab). Installation of the base infrastructure via `ProcessBuilder` → `modulos/entorno.sh`; all subsequent operation (distros, desktops, VNC, GPU) runs **100% natively in Kotlin**, without going through bash scripts or the terminal (except "Login to distro", which deliberately opens a real console).

---

**App:** Kairos (termux-app fork)
**Installation script:** `modulos/entorno.sh`
**Native Kotlin port:** `app/src/main/java/com/termux/app/util/EntornoNative.kt`
**Fragment:** `EntornoFragment.kt`
**`hasSwitch`:** `false` — no single ON/OFF (Entorno installs infrastructure; each component — X11, VNC, PulseAudio, distro — is turned on/off separately)
**`requiresProot`:** `true`
**Estimated size:** ~200MB · **Estimated time:** ~3 min (base infrastructure only — installing a distro/desktop afterward takes longer)

---

## 1. What it is

Entorno turns the phone into a "portable mini PC": it lets you install full Linux distros (via `proot-distro`) with a real graphical desktop (XFCE4/LXQt/MATE/Plasma) displayed through the **X11 server embedded in the APK itself** (Xlorie, `:xserver` process — see `docs/x11/x11-embedded-architecture.md`), with GPU acceleration auto-detected based on the device's hardware, plus VNC as a backup/alternative and PulseAudio for audio.

The X server is embedded directly in Kairos's own APK — it doesn't depend on any external app to display the desktop.

`modulos/entorno.sh` **only installs the base infrastructure** (proot-distro, udocker, embedded-X11-mode registration, PulseAudio, GPU drivers, and 6 management scripts) — installing a specific distro or desktop is done AFTERWARD, from the app's own UI (`EntornoFragment`), not as part of the initial install.

## 2. Permissions

- Generic Kairos wizard permissions (storage).
- Doesn't install any external APK — the X11 server (Xlorie) is embedded in Kairos itself, starting as the Android `:xserver` process (`X11Service.kt`) when requested from the app.
- Doesn't require root at any point — everything runs via `proot`/`proot-distro` (user namespace).

## 3. Installation logic (`modulos/entorno.sh`)

Accepts `--silent` (implicit from the app), `--force`, `--describe`. 8 checkpoints, all verified with real `command -v`/`dpkg -s` checks before being marked done (not blind checkpoints):

| Step (checkpoint) | What it does |
|---|---|
| `entorno_arch` | Checks that `uname -m` is `aarch64`/`arm64` — aborts if not (Entorno is ARM64-only) |
| `entorno_pkg_update` | `pkg update` with a **5-mirror fallback** (`packages.termux.dev`, `grimler.se`, `mirror.accum.se`, 2 Chinese mirrors) — measures the REAL speed of each with `curl -w '%{time_total}'` against `.../dists/stable/InRelease` and uses the fastest one, instead of trying them in a fixed order |
| `entorno_proot_distro` | `pkg install proot-distro` |
| `entorno_udocker` | Downloads `udocker.py` directly from the official repo (`indigo-dc/udocker`) to `$PREFIX/bin/udocker` — if the download fails, just `warn`s and continues (udocker isn't strictly required for Entorno itself) |
| `entorno_x11` | Registers the "embedded X11" mode in the registry (`entorno.x11_mode=embedded`) — no APK to download, the server (Xlorie) already ships inside Kairos itself |
| `entorno_pulse` | `pkg install pulseaudio` + enables `module-native-protocol-tcp` with a `127.0.0.1` ACL in `default.pa` (only if not already set) |
| `entorno_gpu` | See section 4 — detection + driver installation based on SoC |
| `entorno_dirs` | Generates 6 management scripts under `~/scripts/entorno/` (see section 6) |
| `entorno_desktop_tools` | Installs desktop tools on the HOST (outside any proot distro): `xfce4 xfce4-goodies dbus-x11 tigervnc x11vnc pavucontrol`, best-effort (`warn`, doesn't abort if a package fails). If the detected Android SDK is ≥31, warns about disabling "Disable phantom process killer" |
| `entorno_ai_tools` | Installs the base for AI CLIs on the HOST: `python3 nodejs-lts git curl`, also best-effort. This is the base that modules like OpenCode/Claude Code assume is available on the HOST |
| `entorno_registry` | Writes `entorno.installed=true`, `entorno.gpu`, `entorno.gpu_method`, `entorno.version` |

## 4. GPU detection and driver options

`_check_gpu()` reads `getprop ro.board.platform` (not `dmesg`, which requires root on Android) and classifies by known patterns:

| Detected SoC | Type | Packages installed | Default method |
|---|---|---|---|
| `sm*`/`kona*`/`lahaina*`/`shima*` | Adreno (Qualcomm) | `mesa` + `vulkan-loader-generic` (Zink — a Gallium software-GL-over-Vulkan driver — ships inside the regular `mesa` package, it's not a separate package); additionally installs `mesa-vulkan-icd-freedreno` (Turnip's native Vulkan ICD) as an extra option, if the device's mirror has it available | `zink` (Turnip is available as a manually configurable alternative if `mesa-vulkan-icd-freedreno` installed successfully) |
| `mt*`/`t618*`/`g610*`/`g720*` | Mali (MediaTek) | `mesa` + `virglrenderer-android` + `angle-android` (with compatibility symlinks for `libEGL`/`libGLESv1_CM`/`libGLESv2` when needed) | `virgl_angle` |
| `s5e*`/`exynos*` | Xclipse (Samsung Exynos) | `mesa` + `virglrenderer-android` + `angle-android` | `virgl` |
| Any other (`unknown`) | Generic | `mesa` (software rendering, llvmpipe) | `llvmpipe` |

`mesa-demos` (`glxinfo`/`glxgears`) is also installed, used by the app's real GPU diagnostics (`EntornoNative.gpuDiagnostic()`) to report the effective OpenGL renderer, not just the configured method.

The user can change the method manually afterward from the app (`EntornoFragment` → "⚙ Configure GPU method" → `EntornoNative.setGpuMethod()`/`gpuMethodOptions()`), without reinstalling anything.

## 5. Real app screen (`EntornoFragment.kt`)

**STATUS card** (refreshed on every action via `EntornoNative.status()`): GPU, Method, Embedded X11 (● Running / ○ Stopped), VNC, PulseAudio, installed desktops.

**CONTAINERS section — proot-distro**:
- 📦 Install distro → 2-column tile grid — `ubuntu`/`debian`/`alpine` confirmed, `archlinux`/`fedora`/`void` flagged "experimental" with an extra confirmation before installing.
- 🔑 Login to distro → opens a **real console** inside the chosen proot via the terminal overlay (`proot-distro login <name>`).
- 💾 Backup distro (tar.gz), 🗑 Delete distro, 🔗 Link `~/scripts` and `~/proyectos` with the distro.
- 📲 Install app in distro / 🗑 Remove app from distro.

**DESKTOP section**:
- 🖥 Native XFCE4 (no distro) — install + start — XFCE4 desktop directly on Termux, without going through a proot distro.
- ▶ Start desktop (X11 + DE) — if only one DE is installed, launches it directly; if there are several, asks which one.
- 🖥 Install another native desktop (LXQt/MATE).
- 🖥 Update desktop launchers — regenerates the `.desktop` files.
- 🚀 Configure desktop autostart.
- ⏹ Stop current desktop (keeps X11 up) — different from stopping the entire X11 server.
- ⏹ Stop X11 server.

**VNC section** (secondary/optional alongside X11): install TigerVNC, start (`:5901`), configure and start VNC (resolution/quality/password), stop. The user can connect with an external VNC client to `127.0.0.1:5901`, **or with the app's own embedded VNC viewer** — `VncViewerActivity`/`VncClient.kt` (an RFB 3.8 client written from scratch, see `docs/x11/x11-embedded-architecture.md`).

**"Log out" launcher**: `generateDesktopLaunchers()` (`EntornoNative.kt`) creates, alongside the CLI/distro-app icons, an extra `.desktop` file `kairos-cerrar-sesion.desktop` inside the desktop that runs `gui_stop.sh` without `--x11` (kills known DE sessions — xfce4/lxqt/openbox/proot-distro login — but leaves the embedded X11 server up, so a desktop can be reopened without restarting the server).

**AUDIO + GPU section**: PulseAudio on/off, GPU diagnostics (real OpenGL renderer + Vulkan device + installed drivers, via `EntornoNative.gpuDiagnostic()`), manually configure the GPU method.

All actions (except "Login to distro") go directly through `EntornoNative.kt` — **there's no ProcessBuilder + stdout parsing from the Fragment**, it's native Kotlin calling the binaries (`proot-distro`, `pm`, `am`, etc.) directly and returning a `JSONObject`.

## 6. Generated management scripts (`~/scripts/entorno/`)

`entorno.sh` generates them as heredocs during installation — these are the ones `EntornoNative.kt` invokes at runtime:

| Script | What it does |
|---|---|
| `tx11_start.sh` | `am start --user 0 -n com.termux/com.termux.x11.MainActivity` (the viewer Activity INSIDE Kairos's own APK, `sharedUserId=com.termux`) |
| `tx11_stop.sh` | `am broadcast -a com.termux.x11.ACTION_STOP -p com.termux` — closes the embedded viewer |
| `vnc_start.sh` | `vncserver`/`tigervncserver :1 -geometry 1920x1080 -depth 24 -localhost` |
| `vnc_stop.sh` | Kills the VNC server + notifies the event bridge (`~/.kairos_events`) |
| `pulse_start.sh` / `pulse_stop.sh` | `pulseaudio --start --exit-idle-time=-1` / `pkill pulseaudio` |
| `gpu_env.sh` | Exports `GALLIUM_DRIVER`/`MESA_GL_VERSION_OVERRIDE`/etc. based on `entorno.gpu_method` read from the registry — sourced before launching any graphical app |

## 7. Registry

Prefix `entorno.*`: `installed`, `version`, `install_date`, `gpu`, `gpu_method`.

## 8. Scope

`EntornoNative.kt` is a port of the highest-value options from a broader reference bash TUI menu — not a 1:1 reimplementation of every niche option that exists in that original tool.

## Screen controls

The module with the most controls in the project — organized into sections via `sectionLabel()`, without separate cards for everything.

### "STATUS" card (read-only)

GPU, GPU method, X11 (running/stopped), VNC (running/stopped/not installed), PulseAudio, installed desktops — refreshed by `refreshStatus()` via `EntornoNative.status()`, colored green/gray depending on whether it's running or not.

### "📋 INSTALLED" card (read-only)

Inventory of installed `proot-distro` distros (`refreshInventory()`). udocker containers have their own full screen in `UdockerFragment.kt`.

### "NATIVE — X11 + desktop directly on Termux (recommended)" section

| Control | What it does | Why |
|---|---|---|
| "🖥 Native XFCE4 (no distro) — install + start" | `promptXfceNative()` — installs native xfce4+xfce4-terminal (via `pkg`, no proot) if missing, starts it over the embedded X11 | One-tap path to the native graphical interface |
| "▶ Start desktop (X11 + DE)" | `promptStartDesktop()` | Starts the already-installed native desktop |
| "🖥 Install another native desktop (LXQt/MATE)" | `promptInstallDesktop()` | For anyone who doesn't want XFCE4 |
| "🖥 Update desktop launchers" | `runEntornoAction("desktop-launchers")` | `startDesktop()` already regenerates launchers automatically on startup — this button is only for adding a new CLI without restarting the whole DE |
| "🚀 Configure desktop autostart" | `promptAutostart()` — multi-select of which CLIs open on their own when entering the desktop | Evaluated via `EntornoNative.autostartOptions()`/`setAutostart()` |
| "⏹ Stop current desktop (keeps X11 up)" | `stopDesktopSessionAction()` | Different from "Stop X11 server" — allows switching paths (native↔distro) without losing the X11 server |
| "⏹ Stop X11 server" | `stopEmbeddedX11()` | Full shutdown of everything (server + session) |

### "WITH DISTRO (proot-distro) — full Linux system, optional" section

| Control | What it does | Why |
|---|---|---|
| "📦 Install distro" | `promptDistroInstall()` | Secondary/optional path — for when the user needs a real isolated system |
| "🖥 Install desktop in distro" | `promptDistroInstallDesktop()` — same picker pattern (distro → DE) as the native path | No need to run `distro_setup_gui.sh` by hand in the terminal |
| "▶ Start desktop inside the distro" | `promptDistroDesktopStart()` | Shares the same embedded X11 as the native path — only one can be active at a time, enforced in `EntornoNative` (`desktopModeConflict()`) |
| "🔑 Login to distro (terminal)" | `promptDistroLogin()` | Real interactive console inside the proot |
| "💾 Backup distro (tar.gz)" | `promptDistroAction("distro-backup", ...)` | — |
| "🗑 Delete distro" | `promptDistroRemove()` | — |
| "🔗 Link ~/scripts and ~/proyectos" | `promptDistroAction("bridge-mount", ...)` | Shares host folders inside the distro |

### "WITH DISTRO — app catalog (apt, optional)" section

| Control | What it does | Why |
|---|---|---|
| "📲 Install app in distro" | `promptDistroAppInstall()` — curated catalog (`curatedDistroApps`: Firefox, Chromium, GIMP, Inkscape, VLC, LibreOffice, Thunderbird, Blender, OBS Studio, FileZilla) + "Other" free-text option | Suggestions instead of requiring the exact apt package name from memory |
| "🗑 Remove app from distro" | `promptDistroAppRemove()` | — |

### "VNC — secondary/optional" section

| Control | What it does | Why |
|---|---|---|
| "📥 Install TigerVNC" | `vncInstallWithProgress()` | — |
| "▶ Start VNC (:5901)" | `runEntornoAction("vnc-start")` | Quick startup without configuring anything |
| "⚙ Configure and start VNC" | `promptVncConfig()` — resolution, quality (16/24-bit), "Require password" checkbox + real password field | Resolution/quality/password are the 3 real parameters `vncStartWithConfig()` supports without breaking alignment with the embedded `:1` display |
| "⏹ Stop VNC" | `runEntornoAction("vnc-stop")` | — |

### "AUDIO + GPU" section

| Control | What it does | Why |
|---|---|---|
| "🔊 PulseAudio — turn on/off" | `runEntornoAction("pulse-toggle")` | — |
| "🩺 GPU diagnostics" | `showGpuDiagnostic()` | — |
| "⚙ Configure GPU method" | `promptGpuMethod()` — list of real methods based on the detected GPU type (`EntornoNative.gpuMethodOptions()`) | The correct method depends on the device's real hardware, it's not a universal option |

**Non-obvious detail**: `errorDetail()` (a helper shared by several flows in this Fragment) shows the short error message TOGETHER WITH the last 300 characters of `output` (the real apt-get/pkg output from inside the proot), instead of just a short message with no hint about the cause.
