# QEMU

**Kairos module** — managed from the UI (Modules/Store tab). Installation handled by the app via `ProcessBuilder` → `modulos/qemu.sh`; the screen (`QemuFragment.kt`) is a dedicated Fragment.

---

**Script:** `modulos/qemu.sh` — mirrored byte-for-byte at `app/src/main/assets/scripts/qemu.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/QemuFragment.kt`
**`id` in `modules.json`:** `qemu` — no switch (not a persistent service with a background process; see §4), native type, development category, size ~150MB, terminal command `qemu-system-x86_64`

---

## 1. Overview

A CPU/binary emulation module built around an explicit honesty criterion: don't promise anything a rootless Android environment can't actually deliver.

Relevant findings about the environment:

- Termux has real QEMU packages in its repo (some via `x11-repo`): `qemu-system-x86-64(-headless)`, `qemu-system-aarch64`, `qemu-system-i386`, `qemu-system-arm`, `qemu-utils`, and the `qemu-user-<arch>` packages (`qemu-user-x86-64`, `qemu-user-arm`, etc.).
- **Android does not expose `/dev/kvm` to unrooted apps.** There is no way to give `qemu-system` hardware acceleration without rooting the device. Without KVM, `qemu-system` runs in **TCG** (Tiny Code Generator — software instruction translation): it works, but far below native speed. Useful for testing a binary or booting a lightweight headless distro; not suited for a smooth "desktop" experience.
- `qemu-user` (`qemu-x86_64`, `qemu-arm`, etc.) is genuinely useful without root: it runs a **static** binary from another architecture directly (e.g. an x86_64 binary on an ARM64 phone), without needing a full VM or `binfmt_misc` (which does require root) — it's invoked explicitly: `qemu-x86_64 ./my_x86_64_binary`. It's QEMU's strongest use case on rootless Termux.

What the module exposes:

| | Capability |
|---|---|
| Yes | `qemu-user-x86-64` + `qemu-user-arm` — run static binaries from another architecture (fast, real-world use, no root) |
| Yes | `qemu-system-x86-64-headless` + `qemu-utils` — boot a graphics-free x86_64 VM (`-nographic`, serial console), useful for testing a lightweight kernel/ISO/image. Without KVM → pure software, slow (minutes to boot, not seconds) |
| No | Does not bundle any operating-system image/ISO by default in the script (the downloadable catalog actually lives in the UI — see §6) |
| No | Not an alternative to a desktop VM with GPU |

The Fragment follows the same criterion: any "graphical VM" UI or KVM/HAX acceleration selector was deliberately left out.

## 2. Permissions

- **Android**: none specific beyond the app's generic setup wizard (storage). No root required, no extra `INTERNET` permission of its own (catalog image downloads use `HttpURLConnection` from Kotlin, inside the app's own process).
- **Internal Termux**: runs entirely in user space, with no `/dev/kvm`, no `binfmt_misc` (both require root on Android). `qemu-user` needs no additional permission when invoking a foreign binary directly.

## 3. Install logic (`modulos/qemu.sh`)

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts |
| `--force` | Reinstall even if already present |
| `--describe` | JSON manifest — user-mode + system-mode without KVM |

Does not implement `--status`, `--uninstall`, or `--start`/`--stop` (there's no persistent background process — see §4).

**"Already installed" guard**: if `qemu-x86_64` and `qemu-system-x86_64` both exist and `--force` wasn't passed, it logs the version and exits without doing anything.

### Install steps (3 checkpointed steps, each idempotent)

1. **Enabling x11-repo** — some `qemu-system` packages live in `x11-repo`. If it fails, it only warns (doesn't abort).
2. **Installing qemu-user (x86_64 + arm)** — `qemu-user-x86-64 qemu-user-arm`. Verifies the binary to log OK/warning, but doesn't abort on failure.
3. **Installing qemu-system-x86-64-headless + qemu-utils** — if it fails, warns that it may not be available for this Termux architecture, noting that `qemu-user` (step 2) still works fine even if this step fails. No step in the script is blocking for the others — a "degrade gracefully, not all-or-nothing" philosophy.

### Generated wrapper scripts (`~/scripts/qemu/`)

- **`run_user.sh <arch> <binary> [args...]`** — validates that `arch`/`binary` aren't empty, checks that `qemu-$ARCH` exists, and runs `qemu-$ARCH "$BIN" "$@"`. Supported `<arch>` values: `x86_64` | `arm`.
- **`run_vm.sh <image.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc]`** — default RAM `512` MB, default host SSH port `2222`, default mode `console`. Validates that the image exists. Prints reminders that without KVM the boot will take a while. First tries `-drive file=$IMG,format=qcow2`; if that fails, retries with `-cdrom "$IMG"` — covers both disk images (`.qcow2`) and install/boot ISOs. No acceleration flag in either mode.
  - **`console` mode** (default) — `-nographic` (serial console redirected to the terminal, no graphical window).
  - **`vnc` mode** — replaces `-nographic` with `-vnc 127.0.0.1:2` (QEMU's VNC server on port **5902**, display `:2` — Kairos's native VNC viewer already uses `:1`/5901 for Mini PC/Entorno, so QEMU uses the next display to avoid a collision). Without `-nographic`, the actual output becomes the graphical framebuffer served over VNC.

### Registry

`registry_install qemu "1.0.0" "kvm=false" "mode=tcg_software" "user_mode=<true|false>" "system_mode=<true|false>"` — the last two fields are computed on the spot with real binary verification, reflecting the actual outcome of the install.

## 4. State detection — not a module with a background process

QEMU has no persistent daemon to start/stop from a switch. It's a toolbox: installed once, then invoked on demand (running a binary, or starting a VM that runs in the terminal while the user uses it).

`QemuFragment.refreshStatus()` checks real installation on a separate thread, without relying on the registry:
- **user mode**: presence of `qemu-x86_64` or `qemu-arm`
- **system mode**: presence of `qemu-system-x86_64`

Each result is drawn in its own status row. If the whole module isn't installed, the Fragment shows the "Module not installed" screen directly.

## 5. App screen (`QemuFragment.kt`)

Six cards:

**STATUS** — rows `qemu-user (x86_64/arm)`, `qemu-system (headless)` + a fixed informational row "KVM acceleration: not available without root".

**RUN BINARY FROM ANOTHER ARCHITECTURE** — a field for the binary's path + two buttons "Run as x86_64 (terminal)" / "Run as arm (terminal)", which open a real terminal session.

**DISK IMAGES (qemu-img)** — informational row with the `~/qemu_images` folder + three buttons:
- "Create disk image" — dialog with name and size; runs `qemu-img create -f <qcow2|raw> '<target>' <size>` (format decided by extension).
- "List images" — lists files in `~/qemu_images` with `qcow2`/`img`/`iso`/`raw` extensions, selecting one fills in the VM path field.
- "Info / resize / convert image" — pick an image → `qemu-img info`/`resize`/`convert`. The resize dialog explicitly warns that shrinking can destroy data if the internal filesystem doesn't shrink along with it — growing is always safe.

**Size normalization**: the function normalizes formats like `"1gb"`/`"1GB"`/`"1g"`/`"1G"` → `"1G"`, `"512mb"`/`"512M"` → `"512M"` — the actual format `qemu-img create` accepts is a single-letter suffix (`K`/`M`/`G`/`T`), with no trailing `b`; if the text doesn't match a recognizable pattern, it's passed through as-is.

**DOWNLOADABLE IMAGES** — a curated catalog of 3 real x86_64 operating-system images, with confirmed URLs and sizes:

| Image | File | Confirmed size | URL |
|---|---|---|---|
| Alpine Linux 3.24 (virt, x86_64) | `alpine-virt-3.24.1-x86_64.iso` | ~66 MB | `https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-virt-3.24.1-x86_64.iso` |
| Debian 12 (Bookworm) cloud, amd64 | `debian-12-generic-amd64.qcow2` | ~427 MB | `https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-amd64.qcow2` |
| Ubuntu 22.04 LTS (Jammy) cloud, amd64 | `ubuntu-22.04-server-cloudimg-amd64.img` | ~700 MB | `https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64.img` |

Alpine boots straight to a root login without cloud-init (ideal for testing the headless VM quickly); the Debian/Ubuntu cloud images are meant for `cloud-init` and may need a seed ISO with a user/password to log in over the console — the app doesn't automate that part.

The download uses the same pattern as the rest of the app's downloaders (real progress with %, speed, ETA every 500ms, 15s connect timeout / 30s read timeout), without the GGUF magic-bytes validation (here the file is `.iso`/`.qcow2`, not a model). It downloads to a temp file and renames it to the final name only if the download completed.

**HEADLESS VM (no KVM — software TCG, slow)** — image path field + RAM field in MB (default 512), a "Pick image from list" button and "Start VM…" which opens a **display mode** selector:

| Option | What it does |
|---|---|
| "Console / SSH" (default) | Runs `run_vm.sh '<path>' <ram> 2222 console` in a terminal, with the warning "Starting without KVM — boot will take a while (software TCG)" |
| "VNC" | Runs `run_vm.sh '<path>' <ram> 2222 vnc` in a terminal (fire-and-forget), and after a short delay (time for QEMU to bring up the server) automatically opens the native VNC viewer pointed at port 5902 |

The "native X11" option (over the embedded X11) was not implemented — it depends on whether the headless package supports GTK/SDL, which hasn't been confirmed.

**MAINTENANCE** — "Update QEMU" invokes the module's standard update flow.

## 6. Performance limitations

- **No `/dev/kvm`** (Android doesn't expose it to unrooted apps) → `qemu-system` runs in **TCG** (software instruction translation), not hardware-accelerated.
- Practical consequence: a headless VM's boot takes minutes, not seconds.
- Explicitly not recommended for a heavy graphical VM (e.g. Windows) — it would be practically unusable in this environment.
- `qemu-user` doesn't suffer this limitation — it's not a full VM, it just translates a static binary's instructions on the fly, real and reliable use without root.

## 7. Quick reference commands

```bash
# Run a static x86_64 binary on an ARM64 phone
bash ~/scripts/qemu/run_user.sh x86_64 ./my_x86_64_binary [args...]

# Run a static ARM binary on any supported host
bash ~/scripts/qemu/run_user.sh arm ./my_arm_binary

# Create an empty disk image
qemu-img create -f qcow2 ~/qemu_images/disk1.qcow2 4G

# Boot a headless VM in console mode (no graphics, no KVM — slow)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512

# Boot a headless VM in VNC mode (QEMU server on 127.0.0.1:5902)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512 2222 vnc
```

## 8. Registry (`~/.android_server_registry`)

```
qemu.installed=true
qemu.version=1.0.0
qemu.kvm=false
qemu.mode=tcg_software
qemu.user_mode=<true|false>
qemu.system_mode=<true|false>
```

`user_mode`/`system_mode` reflect the actual outcome of the install at the time the script ran (they aren't re-verified afterward unless the user runs the module again) — the UI, on the other hand, does re-check live every time the screen opens, so it can diverge from the registry if, for example, the user manually uninstalled a package from the terminal.
