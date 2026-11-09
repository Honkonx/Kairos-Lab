# Environment (Mini PC) — Kairos Module

**Kairos Module** — Managed through the Kairos UI. The base infrastructure is installed via `ProcessBuilder` → `modulos/entorno.sh`; all later operation (distros, desktops, VNC, GPU) runs **100% native in Kotlin**, without going through bash scripts or the terminal (except "Distro login", which opens a real console on purpose).

---

**Project:** Kairos
**Based on:** the environment bash TUI menu of the original scripts project (~25 options) — Kairos natively reimplements the highest-value ones, not all of them
**Installation script:** `modulos/entorno.sh` (identical to `app/src/main/assets/scripts/entorno.sh`; the header cites v1.4.0 but `update_registry "1.2.0"` in the final step writes 1.2.0 to the registry — a version inconsistency)
**Native Kotlin port:** `app/src/main/java/com/termux/app/util/EntornoNative.kt` (~2451 lines, ~90 functions; JSON in/out)
**Fragment:** `EntornoFragment.kt` + 4 tabs (`EntornoNativoTab`, `EntornoDistrosTab`, `EntornoX11Tab`/`EntornoVncTab` grouped in the Display tab, `EntornoSistemaTab`) + `EntornoTabUi.kt` (components `rowList/actionRow/switchRow`); `ModuleDetailNavigator.kt:34`
**`modules.json`:** `hasSwitch: false`, `port: ""`, `requiresProot: false`, `arch: "distro"`, `requires: ["proot-distro"]`, `repo: Honkonx/kairos-lab`, `hideFromCatalog: true` (`:601`; it does not appear in the normal catalog, it is reached through the Mini PC screen / direct navigation)
**Estimated size:** ~200MB · **Estimated time:** ~3 min (base infrastructure only — installing a distro/desktop afterwards takes longer)

---

## 1. What it is and what it is for

Entorno turns the phone into a "portable mini PC": it installs the base infrastructure (proot-distro, udocker, PulseAudio, Mesa/GPU drivers, desktop tools) and offers from the UI native desktops (XFCE4/LXQt/MATE on the **X11 server embedded in the APK itself**, Xlorie, `:xserver` process — see `docs/x11/x11-embedded-architecture.md`), proot-distro distros with a desktop (XFCE4/LXQt/MATE/Plasma), VNC (TigerVNC) as an alternative/fallback, PulseAudio and GPU/audio diagnostics, with GPU acceleration detected according to the hardware.

`modulos/entorno.sh` **only installs the base infrastructure** — installing a specific distro or desktop is done AFTER, from the UI itself.

**Classification: FUNCTIONAL MODULE.** Own screen, live state (STATUS card, polled every 3 s, `EntornoFragment.kt:269-280`), services that are switched on/off separately (X11, VNC, PulseAudio, desktop session) and a large Kotlin backend. Inside the tiles there are sub-dialogs (VNC config with resolution/orientation/quality/password, GPU selector, multi-select autostart, webapp/app/command shortcuts, wallpaper change, script editor).

## 2. Permissions

- Generic permissions from the Kairos wizard (storage).
- It installs no external APK — the X11 server (Xlorie) is embedded in Kairos itself and starts as the Android process `:xserver` (`X11Service.kt`) when requested from the app.
- No root needed — everything runs through `proot`/`proot-distro` (user namespace).

## 3. Installation logic (`modulos/entorno.sh`)

Flags (`entorno.sh:93-103`): `--silent` (implicit from the app), `--force`, `--describe`, `--describe-files`, `diagnose|--diagnose`. No variants (`variants: []`). ARM64 only.

Checkpoints (`check_done`/`mark_done`), verified with `command -v`/`dpkg -s` before being marked:

| Step (checkpoint) | What it does |
|---|---|
| `entorno_arch` | Verifies `uname -m` is `aarch64`/`arm64` — aborts if not |
| `entorno_pkg_update` | `pkg update` with a **5-mirror fallback** (`packages.termux.dev`, `grimler.se`, `mirror.accum.se`, 2 Chinese mirrors) — measures the real speed of each with `curl -w '%{time_total}'` against `.../dists/stable/InRelease` and uses the fastest |
| `entorno_proot_distro` | `pkg install proot-distro` |
| `entorno_udocker` | `pip3 install --upgrade udocker` (`entorno.sh:210-224`; no version pin). If it fails, only `warn` and continue (udocker is not strictly needed). `--describe-files` keeps an informational `install_hint` with an old `udocker.py` URL (`:136`, not executed) |
| `entorno_x11` | Registers the "embedded X11" mode in the registry (`entorno.x11_mode=embedded`, `x11_display=:1`) — there is no APK to download |
| `entorno_pulse` | `pkg install pulseaudio` + modifies `$PREFIX/etc/pulse/default.pa` (`module-aaudio-sink`, `module-native-protocol-tcp auth-ip-acl=127.0.0.1`, `module-sles-source`; `:241-282`) only if not already done |
| `entorno_gpu` | See section 4 |
| `entorno_dirs` | Generates the management scripts in `~/scripts/entorno/` (see section 7) |
| `entorno_desktop_tools` | Installs desktop tools on the HOST (outside any distro): enables `x11-repo` and then `xfce4 xfce4-goodies tigervnc x11vnc pavucontrol` (without `dbus-x11`, which does not exist in Termux), best-effort (`warn`). If the Android SDK is ≥31, it warns about disabling "Disable phantom process killer" (`entorno.sh:1626-1680`) |
| `entorno_ai_tools` | Installs the base for AI CLIs on the HOST: `python`, `nodejs-lts`, `git`, `curl`, best-effort |
| `entorno_registry` | `registry_install entorno`: `installed=true`, `gpu`, `gpu_method`, `x11_mode=embedded`, `x11_display=:1` |

At the end it calls `notify_event "entorno" "install_done" "GPU: ${GPU_TYPE}"` (FIFO `~/.kairos_events`, `ModuleEventBridge.kt`). Installation from the UI: `installEntornoSilently()` (`EntornoFragment.kt:341`).

- It does NOT use `verify_binary_installed()` in the main flow; the `pkg install ... || warn` for desktop/ai/gpu do not abort: the module is marked installed even if packages are missing.
- No sha256 in `entorno.sh`. In Kotlin, `tryKaliPrebuilt()` downloads a tar.gz from kairos-lab (`EntornoNative.kt:505-545`) and extracts it with `tar` without a checksum.

## 4. GPU detection and driver options

`_check_gpu()` reads `getprop ro.board.platform` (not `dmesg`, which requires root on Android 15), with more codenames and a sysfs kgsl fallback (`entorno.sh:173-196`), and classifies:

| Detected SoC | Type | Installed driver | `GPU_METHOD` |
|---|---|---|---|
| `sm*`/`kona*`/`lahaina*`/`shima*` and similar | Adreno (Qualcomm) | `mesa` + `vulkan-loader-generic` (+ `mesa-vulkan-icd-freedreno` best-effort) (`entorno.sh:283-310`) | `zink` |
| `mt*`/`t618*`/`g610*`/`g720*` | Mali (MediaTek) | `mesa` + `virglrenderer-android` + `angle-android` (with manual `libEGL`/`libGLESv1_CM`/`libGLESv2` symlinks if `angle-android` does not leave them ready) | `virgl_angle` |
| `s5e*`/`exynos*` | Xclipse (Samsung Exynos) | `mesa` + `virglrenderer-android` + `angle-android` | `virgl` |
| Anything else (`unknown`) | Generic | `mesa` (softGPU) | `llvmpipe` |

It also installs `mesa-demos` at the end. The user can change the method manually from the app ("⚙ Configure GPU method" → `EntornoNative.setGpuMethod()`/`gpuMethodOptions()`), without reinstalling anything.

## 5. Execution

- There is no single process or port of its own; `ModuleController.kt` has no `entorno`-specific entry. Operation goes through `EntornoNative.kt`.
- Components started/stopped separately: embedded X11 (`X11Service`, `:xserver` process, display `:1`; `EntornoX11Tab.kt:57-66`), native/distro desktop (`startDesktop`/`startDistroDesktop`, `EntornoNative.kt:848/924`; native↔distro mutual exclusion via `desktopModeConflict`, `:185`), VNC (`vncStart/vncStartWithConfig`, `:1538/1607`, display `:5901`), PulseAudio (`pulseToggle`, `:1654`). "Distro login" opens a real terminal (`launchTerminal`). Distro sessions are monitored with TermuxService's tmux/terminal session poll (`EntornoFragment.kt:449-520`).
- All actions (except "Distro login") go straight through `EntornoNative.kt` — native Kotlin calling the binaries (`proot-distro`, `pm`, `am`, etc.) and returning `JSONObject`; there is no `ProcessBuilder` + stdout parsing from the Fragment.

## 6. App screen (4 tabs: Native · Distros · Display · System)

Top cards: STATUS (GPU, GPU method, X11 ● Running/○ Stopped — there is no "APK not installed" state, the server is inside Kairos itself —, VNC, PulseAudio, installed desktops; refreshed with `EntornoNative.status()`), INSTALLED (inventory of `proot-distro` distros, `refreshInventory()`), DISTRO SESSIONS, and maintenance (update/uninstall via `addMaintenanceCard()`).

**Native** (`EntornoNativoTab.kt`):

| Control | What it does | Why |
|---|---|---|
| "🖥 Native XFCE4 (no distro) — install + start" | `promptXfceNative()` — installs native xfce4+xfce4-terminal (`pkg` packages, no proot) if missing, starts on the embedded X11 | The recommended "bionic graphical interface", a single-tap path |
| "▶ Start desktop (X11 + DE)" | `promptStartDesktop()` — if only one DE is installed it starts it directly, if several it asks which | — |
| "🖥 Install another native desktop (LXQt/MATE)" | `promptInstallDesktop()` (the only action using `ProgressDialogController` for installation) | For whoever does not want XFCE4 |
| "🖥 Update desktop launchers" | `runEntornoAction("desktop-launchers")` — regenerates the `.desktop` files | `startDesktop()` already regenerates them on start; this button is for adding a new CLI without restarting the DE |
| "🚀 Configure desktop autostart" | `promptAutostart()` — multi-select of which CLIs open on their own when entering the desktop (`EntornoNative.autostartOptions()`/`setAutostart()`) | "Complete mini PC" concept |
| "⏹ Stop current desktop (keeps X11 up)" | `stopDesktopSessionAction()` | Different from stopping the X11 server — it lets you switch path (native↔distro) without losing the server |
| Change wallpaper / create shortcut (webapp/Android app/command) | See `webapp_launchers.sh` in section 7 | — |

**Distros** (`EntornoDistrosTab.kt`): one card per installed distro (Login, Start/Install desktop, Close session, ⋮ maintenance menu) plus a recommended profile (`recommendedProfile()`):

| Control | What it does | Why |
|---|---|---|
| "📦 Install distro" | `promptDistroInstall()` — a 2-column tile grid: `ubuntu`/`debian`/`alpine` confirmed; `archlinux`/`fedora`/`void` marked "experimental" with extra confirmation | Secondary/optional path for a real isolated system |
| "🖥 Install desktop in distro" | `promptDistroInstallDesktop()` — distro → DE picker | — |
| "▶ Start desktop inside the distro" | `promptDistroDesktopStart()` | Shares the same embedded X11 as the native path — only one can be active at a time (`desktopModeConflict()`, `showConflictDialog()`) |
| "🔑 Distro login (terminal)" | `promptDistroLogin()` — a real console via `proot-distro login <name>` | — |
| Maintenance (⋮): backup (tar.gz), restore, reset (`distroRestore/Reset`), delete, link `~/scripts` and `~/proyectos` (`bridge-mount`), change wallpaper | `promptDistroAction(...)`, `promptDistroRemove()` | Shares host folders inside the distro |
| "📲 Install app in distro" | `promptDistroAppInstall()` — curated catalog (`curatedDistroApps`: Firefox, Chromium, GIMP, Inkscape, VLC, LibreOffice, Thunderbird, Blender, OBS Studio, FileZilla) + an "Other" free-text option | Saves the user from typing the apt package from memory |
| "🗑 Remove app from distro" | `promptDistroAppRemove()` | — |

**Display** (X11 and VNC together, in rows with descriptions):

| Control | What it does | Why |
|---|---|---|
| Enter X11 / X11 settings (`KairosX11PreferencesActivity`) / "⏹ Stop X11 server" | `EntornoX11Tab.kt:29-37`, `stopEmbeddedX11()` | Full shutdown (server + session) |
| "📥 Install TigerVNC" | `vncInstallWithProgress()` | VNC is secondary/optional compared to X11 |
| "▶ Start VNC (:5901)" | `runEntornoAction("vnc-start")` | Quick start with nothing to configure |
| "⚙ Configure and start VNC" | `promptVncConfig()` — resolution, orientation, quality (16/24 bit), "Ask for password" checkbox + a real password field (starts empty; `vncSetPassword()`, `EntornoNative.kt:1593`) | Resolution/quality/password are the parameters `vncStartWithConfig()` supports without breaking alignment with the embedded display |
| Open embedded VNC viewer | `VncViewerActivity`/`VncClient.kt` (RFB 3.8 client written from scratch; see `docs/x11/x11-embedded-architecture.md`). An external VNC client can also connect to `127.0.0.1:5901` | — |
| "⏹ Stop VNC" | `runEntornoAction("vnc-stop")` | — |

**System** (`EntornoSistemaTab.kt`): PulseAudio on/off (switch), "🩺 GPU diagnostics" (real OpenGL renderer + Vulkan device + installed drivers, `EntornoNative.gpuDiagnostic()`), GPU benchmark (`gpuBenchmark`), "⚙ Configure GPU method" (`promptGpuMethod()` — a list of real methods according to the detected GPU type), session resources (`sessionResourceUsage`), edit script (`gui_start.sh`, `gui_stop.sh`, `distro_setup_gui.sh`).

`errorDetail()` (a helper shared by several flows) shows the short error message together with the last 300 characters of `output` (the real apt-get/pkg output inside proot) instead of only a generic message.

**"Log out" launcher**: `generateDesktopLaunchers()` (`EntornoNative.kt`) creates, next to the icons for CLIs and distro apps, an extra `.desktop` `kairos-cerrar-sesion.desktop` inside the desktop that runs `gui_stop.sh` without `--x11` (it kills the known DE sessions — xfce4/lxqt/openbox/proot-distro login — but leaves the embedded X11 server up). It solves the case of being INSIDE XFCE4 with the terminal overlay hidden and no way to close the session (which would otherwise keep running and drain battery).

## 7. Generated management scripts (`~/scripts/entorno/`)

`entorno.sh` generates them as heredocs (`_create_scripts`, `entorno.sh:522-1585`); they are what `EntornoNative.kt` invokes at runtime: `tx11_start.sh`, `tx11_stop.sh`, `vnc_start.sh`, `vnc_stop.sh`, `pulse_start.sh`, `pulse_stop.sh`, `gpu_env.sh`, `pdrun`, `x11_setup_env.sh`, `gui_start.sh`, `gui_stop.sh`, `distro_setup_gui.sh`, `proot_menu_sync.sh`, `webapp_launchers.sh`.

| Script | What it does |
|---|---|
| `tx11_start.sh` | Legacy name — today it does `am start --user 0 -n com.termux/com.termux.x11.MainActivity` (the viewer Activity INSIDE the APK itself, `sharedUserId=com.termux`), not an external app |
| `tx11_stop.sh` | `am broadcast -a com.termux.x11.ACTION_STOP -p com.termux` — closes the embedded viewer |
| `vnc_start.sh` | `vncserver`/`tigervncserver :1 -geometry 1920x1080 -depth 24 -localhost` (VNC starts with `-localhost`; it can be started without a password after confirming a dialog, `EntornoVncTab.kt:173-176`) |
| `vnc_stop.sh` | Kills the VNC server + notifies the event bridge (`~/.kairos_events`, inline, standalone) |
| `pulse_start.sh` / `pulse_stop.sh` | `pulseaudio --start --exit-idle-time=-1` / `pkill pulseaudio` |
| `gpu_env.sh` | Exports `GALLIUM_DRIVER`/`MESA_GL_VERSION_OVERRIDE`/etc. according to `entorno.gpu_method` read from the registry — sourced before starting any graphical app |

## 8. Files, registry and other

`~/scripts/entorno/`, `~/.android_server_registry` (`entorno.*` keys: `installed`, `version`, `install_date`, `gpu`, `gpu_method`, `x11_mode`, `x11_display`, `desktop_mode`), `~/.install_entorno_checkpoint`, `~/kairos_logs/x11_diagnose.txt` (diagnostics), `$PREFIX/etc/pulse/default.pa`, `~/.local/share/applications/kairos-webapp-*.desktop` and `~/Desktop/`. From Kotlin: temporary `.kali_prebuilt_download.tar.gz`, distro backups, `.desktop` launchers.

## 9. Technical notes

- **Unexported `TMPDIR` breaks embedded X11**: libX11 resolves the socket via `$TMPDIR/.X11-unix/X<display>`; if `TMPDIR` is not set before the native code reads it, the X client fails to connect even though the socket exists in `$PREFIX/tmp/.X11-unix`. `X11Service.onCreate()` forces `TMPDIR` via `Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)`; it is also in `applyTermuxEnv()` (`ProcessBuilderExt.kt`) for the rest of the modules.
- **Missing `LD_PRELOAD` breaks X11 clients launched from `EntornoNative.startDesktop()`**: `ManagerNativeUtils.runShell()` invokes `bash -c` (neither login nor interactive), so the `LD_PRELOAD` that Termux sets in `/etc/profile`/`bashrc` (`libtermux-exec-ld-preload.so`, which remaps `/tmp` → `$PREFIX/tmp`) is not applied, and libX11 (which resolves the socket with the hardcoded path `/tmp/.X11-unix/X<n>`) may fail. `startDesktop()` exports an explicit `LD_PRELOAD`, scoped to that command (same precedent as `modulos/claude.sh`), runs a socket diagnostic (`[diag] socket X11 presente/ausente`) before connecting, and waits up to 5 s for the X11 server (a separate Android process) because of intermittent failures on slow devices.
- **Closing the X11 server kills the `:xserver` process**: `CmdEntryPoint.main()` (native code from `x11-server/`) runs `Looper.prepare()+Looper.loop()` in a separate `Thread` that `stopService()`/`onDestroy()` does not interrupt. Since `X11Service` runs isolated in its own process (`android:process=":xserver"`), the real mechanism is to kill the whole process: `android.os.Process.killProcess(Process.myPid())` at the end of `onDestroy()`.
- `infoRow()` returns a `LinearLayout` of 2 `TextView`s (key/value); the STATUS card keeps the reference to the 2nd child (`valueTextView()`) so it can be updated.

## 10. Security

- No persistent secrets of its own except the VNC password: the UI password field starts empty (the stored one is not shown). Inference: the permissions of the `~/.vnc/passwd` file are not verified.
- PulseAudio TCP is limited to 127.0.0.1 (`auth-ip-acl`). VNC with `-localhost`.
- No `curl | bash` in `entorno.sh`. There is a download of the Kali tar without a checksum and `pip install udocker` without a version pin.
- Injection: distro/package names are passed to shell commands from dialogs; `sanitizeId()` exists for launcher ids (`EntornoNative.kt:2136`), but the free-text "Other package" entry for distro apps was not audited line by line.

## 11. Known limitations

- Inconsistent version: header v1.4.0 vs `update_registry "1.2.0"`.
- No `verify_binary_installed()`; `pkg install` failures degrade to `warn` and the registry still ends up `installed=true`.
- Kali tar downloaded/extracted without a checksum.
- Inference: the real functional state of desktop-in-distro / VNC / GPU has not been confirmed on a device in the latest code review.

## 12. Date added

`modulos/entorno.sh`: 2026-07-28.
