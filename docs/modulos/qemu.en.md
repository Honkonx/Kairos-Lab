# QEMU (`qemu`) — Kairos Module

**Kairos module** — managed through the Kairos UI (Modules/Store tab). Installation is handled by the app through `ProcessBuilder` → `modulos/qemu.sh`; the screen (`QemuFragment.kt`) is a dedicated Fragment (it does not fall back to `GenericModuleFragment`).

---

**Project:** Kairos
**Script:** `modulos/qemu.sh` (v1.4.0) — mirrored byte for byte in `app/src/main/assets/scripts/qemu.sh` (same `lib.sh` contract)
**Fragment:** `app/src/main/java/com/termux/app/ui/QemuFragment.kt`
**`id` in `modules.json`:** `qemu` — `hasSwitch: false` (not a persistent service with a background process; see §5), `type: "Nativo"`, `arch: "bionic"`, `category: "dev"`, `size: "~150MB"`, `estimate: "~3 min"`, `terminalCommand: "qemu-system-x86_64"`, `requires: []`, `installMethods: ["pkg"]`, `port: ""`

> Sources for this sheet: `modulos/qemu.sh` (520 lines), `app/src/main/assets/modules.json`, `QemuFragment.kt` (1607 lines), `ModuleDetailNavigator.kt:41` and `QmpClient.kt` (`app/src/main/java/com/termux/app/qemu/`). The copy `app/src/main/assets/scripts/qemu.sh` is identical (`cmp`).

---

## 1. What it is and what it is for

A rootless QEMU toolbox: (a) `qemu-user` (x86_64/arm) to run static binaries of another architecture; (b) headless `qemu-system` **without KVM** (software TCG, `/dev/kvm` does not exist without root) to boot Alpine/Debian/Ubuntu on a serial console or with a screen through the embedded VNC, with live control through QMP and SSH access through `hostfwd` (host port 2222 → guest 22).

Findings that shape the design (documented in the script header):

- Termux has real QEMU packages in its repo (some via `x11-repo`): `qemu-system-x86-64(-headless)`, `qemu-system-aarch64`, `qemu-system-i386`, `qemu-system-arm`, `qemu-utils`, and the `qemu-user-<arch>` packages (`qemu-user-x86-64`, `qemu-user-arm`, etc.).
- **Android does not expose `/dev/kvm` to apps without root.** There is no way to give hardware acceleration to `qemu-system` without rooting the device. Without KVM, `qemu-system` runs on **TCG** (Tiny Code Generator — software instruction translation): it works, but far below native. Useful for testing a binary or booting a light headless distro; **not** suitable for smooth "desktop" use — a heavy graphical VM (e.g. Windows) would be practically unusable.
- `qemu-user` (`qemu-x86_64`, `qemu-arm`, etc.) is genuinely useful without root: it runs a **static** binary of another architecture directly (e.g. an x86_64 binary on an ARM64 phone), without needing a full VM or `binfmt_misc` (which does require root) — it is invoked explicitly: `qemu-x86_64 ./my_x86_64_binary`. It is QEMU's most solid use case in rootless Termux.

| | Capability |
|---|---|
| ✅ | `qemu-user-x86-64` + `qemu-user-arm` — run static binaries of another architecture (fast, real use, no root) |
| ✅ | `qemu-system-*-headless` + `qemu-utils` — boot a VM **without KVM**, over serial console or with a screen through VNC. Pure software, slow (minutes to boot, not seconds) |
| ❌ | Does not include any OS image/ISO by default in the script (the downloadable catalog lives in the UI) |
| ❌ | Not an alternative to a desktop VM with a GPU |

The Fragment repeats the same criterion: there is no "graphical VM" UI or KVM/HAX acceleration selector — promising it would be aspirational UI with no real backing.

## 2. Classification

**FUNCTIONAL MODULE.** Dedicated Fragment with live state (`refreshStatus()` measures a real `command -v`), VM lifecycle (start `bootVm()`, reconnect, stop `stopVm()`), image management (create/list/import/info/resize/convert with `qemu-img`), download with progress, QMP control and SSH. It has no `hasSwitch` or TCP port, so it does not use the standard service switch; the "service" is the VM's tmux session. **Approximate UI options: ~30** (2 run binary + 4 images + 6 downloads + 3 arch/RAM/res chips + path/RAM field + pick/boot/reconnect/stop + 6 QMP + SSH + install package + terminal + maintenance).

## 3. Permissions

- **Android**: nothing specific beyond the app's generic wizard (storage). It needs no root or extra `INTERNET` of its own (catalog image downloads use `HttpURLConnection` from Kotlin, inside the same app process).
- **Termux internal**: runs 100% in user space, without `/dev/kvm` and without `binfmt_misc` (both require root on Android).

## 4. Installation (`modulos/qemu.sh`)

`bash qemu.sh --silent` (flags `--silent --force --describe --describe-files`). Standard `lib.sh` contract (`log`/`warn`/`error`/`info`/`step` + `check_done`/`mark_done`/`registry_write`). It short-circuits if `qemu-x86_64` and some `qemu-system-*` already exist and `--force` was not passed.

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts |
| `--force` | Deletes the checkpoint and reinstalls even if already installed |
| `--describe` | JSON manifest (`{"id":"qemu","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"..."}`) |
| `--describe-files` | File manifest for `moduledeb` |

Steps with checkpoint (idempotent via `check_done`/`mark_done`):

1. **`1/3 x11-repo`** — some `qemu-system` packages live in `x11-repo`; followed by `pkg_update_with_fallback`.
2. **`2/3 qemu-user-x86-64 qemu-user-arm`**.
3. **`3/3 qemu-system-aarch64-headless` + `qemu-system-x86-64-headless` + `qemu-utils`**. aarch64 is the RECOMMENDED guest (same arch as the host; uses `-machine virt -cpu max -bios edk2-aarch64-code.fd`, `virtio-blk-pci`, `virtio-gpu-pci` on VNC); x86_64 uses `-machine q35`.

`pkg install` failures only produce a `warn` (they do not abort): a "degraded, not all-or-nothing" philosophy, and `qemu-user` keeps working even if step 3 fails. That is why **the module can finish with `qemu.installed=true` even if no `qemu-system` was left**; the Fragment compensates with `refreshStatus()` and a dialog. It does not use `verify_binary_installed` (only `command -v`). No sha256 of its own: integrity is that of Termux's apt/dpkg.

### Generated wrapper scripts (`~/scripts/qemu/`)

- **`run_user.sh <arch> <binary> [args...]`** — validates arguments, checks that `qemu-$ARCH` exists, and runs `exec "qemu-$ARCH" "$BIN" "$@"`. Supported `<arch>`: `x86_64` | `arm`.
- **`run_vm.sh <image> [ram] [ssh_port] [console|vnc] [WxH] [x86_64|aarch64]`** — default RAM `512` MB, default host SSH port `2222`, default mode `console`. Validates that the image exists, warns that without KVM the boot will take a while, and tries `-drive file=$IMG,format=qcow2` first and then `-cdrom` (covers disk images and ISOs). No acceleration flags (`-enable-kvm`/`-accel`) in any mode.
  - **`console` mode** — `-nographic` (serial console in the terminal, `exec`).
  - **`vnc` mode** — screen served through a **unix socket** `$HOME/scripts/qemu/vncsocket` (`-vnc unix:`); starts in tmux session `kairos_qemu_vnc` with wrapper `.vm_vnc_cmd.sh` and log `~/kairos_logs/qemu_vm_vnc.log`. Without `-nographic`, the real output becomes the graphical framebuffer.
  - In all modes a **QMP socket** `$HOME/scripts/qemu/qmpsocket` is also opened.

### Registry (`~/.android_server_registry`)

```
qemu.installed=true
qemu.version=1.4.0
qemu.kvm=false
qemu.mode=tcg_software
qemu.user_mode=<true|false>
qemu.system_mode_aarch64=<true|false>
qemu.system_mode_x86_64=<true|false>
```

`user_mode`/`system_mode_*` reflect the real installation result at the time the script ran; the UI (`QemuFragment.refreshStatus()`) re-checks live with `command -v` every time the screen opens, so it can diverge from the registry if the user uninstalled a package by hand.

## 5. Execution

There is no service start/stop: it is a **toolbox** installed once and invoked on demand.

- Console mode: `bootVmConsole()` opens a terminal session with `run_vm.sh ... console`.
- VNC mode: `bootVmVnc()` runs `run_vm.sh ... vnc`, which launches tmux `kairos_qemu_vnc` and exits; the app waits for the socket (`waitForQemuVncSocket()`) and opens `VncViewerActivity`.
- Stop: `stopVm()` = `tmux kill-session -t kairos_qemu_vnc; pkill -f qemu-system-x86_64`.
- Control: `QmpClient` through `LocalSocket` (powerdown, stop/cont, screendump `.ppm`, change/eject medium).

## 6. Real app screen (`QemuFragment.kt`)

`getModuleId()` → `"qemu"`, `getModuleName()` → `"QEMU"`. A **Status** card and **4 tabs** (Binary, Images, VM, Advanced; `buildTabLayout()`). If the module is not installed (`isModuleInstalled()` false), it shows the standard "Module not installed" screen.

- **Status** (before the tabs): rows `qemu-user (x86_64/arm)` and `qemu-system (headless)` (refreshed by `refreshStatus()`: `command -v qemu-x86_64 || command -v qemu-arm`, and `command -v qemu-system-x86_64`) + a fixed row "KVM acceleration: Not available without root".
- **Binary**: path field + "▶ Run as x86_64 (terminal)" / "▶ Run as arm (terminal)" → `launchTerminalCommand("qemu-<arch> '<path>'")`, opens a real terminal session (it does not run silently in the background).
- **Images** (folder `~/qemu_images`):
  - "＋ Create disk image": name + size → `qemu-img create -f <qcow2|raw> <file> <size>` (format by extension). `normalizeQemuImgSize()` accepts `1gb`/`1GB`/`1g`/`1G` and normalizes to the real format `qemu-img` accepts (a single-letter suffix `K`/`M`/`G`/`T`, no trailing `b`); if the text is not recognizable, it passes it through as is and `qemu-img` gives its own error.
  - "☰ List images": `qcow2`/`img`/`iso`/`raw` files; tapping one fills in the VM path.
  - "🛠 Image info / resize / convert": `qemu-img info`/`resize`/`convert`. The resize dialog warns that shrinking (`-`) can destroy data if the internal filesystem does not shrink as well; growing (`+`) is safe.
  - Import from storage (SAF → copy to a real path).
  - **Download catalog (6 images)**: Alpine 3.24.1 virt, Debian 12 generic and Ubuntu 22.04 cloudimg, each in **aarch64 and x86_64**. The URLs are official domains over HTTPS (e.g. `dl-cdn.alpinelinux.org`, `cloud.debian.org`, `cloud-images.ubuntu.com`). The download (`downloadDiskImage`/`downloadFileWithProgress`) shows real progress (%, speed, ETA every 500 ms), uses `HttpURLConnection` with redirects, 15 s/30 s timeouts, downloads to a `.part` file and only does `renameTo()` if the final file is at least 95% of the expected size.
  - Note: Alpine boots straight to a root login with no cloud-init; the Debian/Ubuntu cloud images are designed for `cloud-init` and may require a seed ISO with a user/password to log in over the console — the app does not automate that part.
- **VM**: image path, guest architecture chips (aarch64/x86_64), RAM (+ presets, recommendation `recommendedRamMb()`), resolution (presets, x86_64 only), "📂 Pick image from list", "▶ Start VM…" (console/VNC dialog), "Reconnect VNC", "Stop VM", 6 QMP controls (ACPI shutdown, pause, resume, screenshot, change/eject medium), "Connect via SSH" (`connectSsh()`) and "Install package in the VM" (apt/apk over SSH).
  - "Console / SSH" (default): `run_vm.sh ... console` in a terminal session, with the toast "Starting without KVM — boot will take a while (TCG software)".
  - "VNC": `run_vm.sh ... vnc` and automatic opening of `VncViewerActivity` pointing at the unix socket; it reuses the existing native VNC viewer.
  - The "Native X11" option (`-display gtk/sdl` on the embedded X11) is not implemented: it depends on the headless package supporting GTK/SDL, which is unconfirmed.
- **Advanced**: "Open terminal" in `~/qemu_images` + maintenance (update/uninstall).

## 7. Performance limitations (documented in the script, the Fragment and the UI)

- **No `/dev/kvm`** → `qemu-system` runs on **TCG**, **not** hardware-accelerated.
- Booting a headless VM takes **minutes, not seconds**; the script warns about it in `run_vm.sh` and the UI repeats it in the "Start VM" toast and in the downloads card text.
- **Not recommended** for a heavy graphical VM (e.g. Windows).
- `qemu-user` does not suffer this limitation — it is not a full VM, it just translates a static binary's instructions on the fly.

## 8. Quick reference commands

```bash
# Run a static x86_64 binary on an ARM64 phone
bash ~/scripts/qemu/run_user.sh x86_64 ./my_x86_64_binary [args...]

# Run a static ARM binary
bash ~/scripts/qemu/run_user.sh arm ./my_arm_binary

# Create an empty disk image
qemu-img create -f qcow2 ~/qemu_images/disk1.qcow2 4G

# Start a VM in console mode (no graphics, no KVM — slow)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512

# Start a VM in VNC mode (unix socket, tmux session kairos_qemu_vnc)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512 2222 vnc x86_64
```

## 9. Files and paths

`~/scripts/qemu/{run_user.sh,run_vm.sh,.vm_vnc_cmd.sh,vncsocket,qmpsocket}`, `~/qemu_images/` (images, `.ppm` screenshots), `~/kairos_logs/qemu_vm_vnc.log`, `~/kairos_logs/install_qemu.log`, `~/.install_qemu_checkpoint`, registry.

## 10. Security

- VNC and QMP are local unix sockets with no password (protected by the permissions of the app's `$HOME`, not by authentication).
- The SSH `hostfwd` opens `tcp::2222-:22` on SLIRP's default interface (it listens on all host interfaces unless restricted; inference: it was not verified whether Android exposes it outside the device).
- Image downloads over HTTPS from official domains, **without sha256/signature verification** (only a size check ≥95%).
- No `curl | bash`. SSH passwords are not stored in this Fragment (they are asked for in the terminal's SSH client).

## 11. Issues / limitations

1. `stopVm()` kills by pattern only `qemu-system-x86_64`; an aarch64 VM started in CONSOLE mode is not stopped by "Stop VM" (in VNC mode it is, through tmux).
2. The installation can succeed with missing packages (§4); it does not use `verify_binary_installed`.
3. Catalog without sha256; pinned image version (`3.24.1`; Debian's `latest` changes).
4. `terminalCommand: qemu-system-x86_64` in `modules.json` contrasts with the recommended aarch64 guest.
5. A single fixed socket/session: only one VM at a time (by design, documented in `run_vm.sh`).
6. Inference: that aarch64 without KVM is "usable" for a desktop; performance was not measured.

## 12. Added on

`modulos/qemu.sh`: **2026-08-16**.
