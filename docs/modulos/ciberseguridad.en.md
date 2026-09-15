# Cybersecurity

**Kairos module** — managed through the Kairos UI (Modules → "Ciberseguridad" card). A
network/OSINT/pentesting toolkit with 2 tiers: a **basic** tier that's 100% native (bionic, no
proot) and a **pro** tier that adds a full Kali Linux container via `proot-distro`, with or
without a graphical interface.

---

**Script:** `modulos/ciberseguridad.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/CiberseguridadFragment.kt`
**`id` in `modules.json`:** `ciberseguridad` — `hasSwitch: false`, `hasVariants: true`,
`requiresProot: false` (proot is only used conditionally inside the script for the pro tier),
`arch: bionic`, `category: seguridad`, `terminalCommand: nmap`

---

## 1. Scope and responsibility

Standard network/OSINT/pentesting tools, intended for diagnosing the user's own network and
for authorized pentesting. A broader catalog of offensive tools (bruteforcing, Metasploit
automation, Android forensics, vulnerable practice servers) is deliberately out of scope.

## 2. Permissions

None specific to Android — runs in Termux userspace (native bionic for the basic tier,
`proot-distro` for the pro tier), no root, no permissions beyond Kairos's generic first-run
wizard. The pro tier downloads a container several hundred MB in size — it only requires
storage space and network access, not an Android permission.

## 3. Variants / tiers (`--variant`)

`ciberseguridad.sh` accepts `--silent [--force] [--variant basico|pro-headless|pro-gui]
[--describe]`. The tier is chosen at install time, there's no runtime central switch — to
change tier you have to reinstall with a different variant.

| Variant (`--variant`) | Internal tier | What it installs |
|---|---|---|
| `basico` (default) | `PRO=false` | Only the native toolkit (STEPS 1-5) |
| `pro-headless` (alias `pro`) | `PRO=true`, `PRO_GUI=false` | Basic + Kali container without a desktop |
| `pro-gui` | `PRO=true`, `PRO_GUI=true` | Basic + Kali container + XFCE4 inside the container |

**"Already installed" check**: only evaluates the basic tier (`command -v nmap &&
theHarvester && sqlmap`). If basic is already present but `--variant pro-*` is requested, the
script doesn't exit — it proceeds straight to steps 6-8 (Kali), allowing an upgrade from basic
to pro without reinstalling the basic tier. `--force` deletes the checkpoint and forces every
pending step to repeat.

## 4. Basic tier (STEPS 1-5, native bionic)

Each step has its own checkpoint — reentrant: if the script is interrupted partway through, it
resumes from the next step without repeating the ones already done.

| Step | Tool | How it's installed |
|---|---|---|
| 1 | **nmap** | `pkg install nmap` |
| 2 | **netcat-openbsd + dirb** | `pkg install netcat-openbsd dirb` |
| 2b | **nikto** | `git clone --depth 1 https://github.com/sullo/nikto.git ~/.nikto` + a bash wrapper at `$PREFIX/bin/nikto` that does `exec perl ~/.nikto/program/nikto.pl "$@"` — nikto isn't an official Termux package |
| 3 | **Python 3** | Detects `python`/`python3` on the PATH; if missing, `pkg install python` |
| 4 | **theHarvester** (OSINT) | `pip install --no-deps git+https://github.com/laramies/theHarvester.git` + manual installation of the rest of the real dependencies (read dynamically from the repo's `pyproject.toml`) + a local `playwright` stub (see §5) |
| 5 | **sqlmap** | `pip install sqlmap` |
| 5b | **MVT (Mobile Verification Toolkit)** | `pip install mvt` |
| 5c | **ClamAV** | `pkg install clamav` (runs `freshclam` best-effort after installing) |

### 4.1 MVT and ClamAV — "device self-defense" category

Unlike the rest of the catalog (nmap/netcat/dirb/nikto/theHarvester/sqlmap and Kali in the pro
tier, which point outward — recon/pentesting of another network or system), MVT and ClamAV point
inward: they protect the user's own device, with no external target or third-party authorization
needed to use them.

- **MVT** (a real Amnesty International tool) does spyware/stalkerware forensics (Pegasus-style
  IOCs) on the device itself, via ADB without root or backup analysis
  (`mvt-android check-adb`/`check-backup`). It doesn't have a native panel on the module screen
  yet — its real workflow (matching indicators of compromise against an output directory, not a
  simple target+flags form) needs a different UI design than the rest of the tools, so for now
  it's used from the terminal.
- **ClamAV** is a real open-source antivirus for scanning the device's own files/downloads
  (`clamscan <path>`). It also doesn't have a native panel yet — direct terminal use is simple
  enough that it doesn't need a dedicated dialog for now.

## 5. theHarvester

Two real platform limitations the script works around:

1. **PyPI's `theHarvester` package is an abandoned placeholder**, with no `entry_points` — it's
   installed straight from the real repo (`laramies/theHarvester`) via `pip install git+...`.
2. **`playwright` (a hard dependency of theHarvester) has no wheel for Bionic libc** in any
   version — a real platform limitation (it embeds Chromium binaries, and PyPI only publishes
   manylinux/macOS/Windows wheels). Three-part solution: install theHarvester with `--no-deps`,
   install the rest of the real dependencies read dynamically from the repo's
   `pyproject.toml`, and a **local `playwright` stub** that defines `async_playwright()` to
   throw a clear error only if `--screenshot` is actually used — every other OSINT search
   engine works normally.

A failure in this step is non-critical (`warn`), so step 5 (sqlmap) is still attempted even if
theHarvester fails.

## 6. Pro tier — Kali Linux via proot-distro (STEPS 6-8, only if `$PRO=true`)

| Step | What it does |
|---|---|
| 6 | Installs `proot-distro` if missing |
| 7 | Creates the `kali` container — `proot-distro install kalilinux/kali-rolling -n kali` |
| 7b | `proot-distro login kali -- apt-get install -y kali-tools-top10` — Kali's official curated metapackage, not `kali-linux-everything`. Failure is non-critical — the container remains usable regardless |
| 8 (only `pro-gui`) | XFCE4 + dbus-x11 inside the container — reuses the same script generated by the Entorno module for any proot distro |

There's no official `kali` alias in `proot-distro` since its version 5 (it installs any
Docker/OCI image by reference) — the official `kalilinux/kali-rolling` image from Docker Hub is
used and forced to the name `kali` with `-n`/`--override-alias`, so the rest of the ecosystem
(`proot-distro login kali`, the Entorno module, desktop startup) finds it under the expected
name.

Step 8 respects the real exit code of the desktop installation script (which verifies
`startxfce4`) — if it fails, the container is registered as headless and remains usable without
a desktop.

## 7. Registry

```
installed=true
tier=basico|pro
kali_container=kali|""      (empty if tier=basico)
kali_gui=true|false
tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav[,proot-distro,kali(<kali_tools>:<gui_status>)]
install_date=YYYY-MM-DD
```

## 8. Real UI (`CiberseguridadFragment.kt`)

Dedicated Fragment. Reads the registry to decide what to show — doesn't reinstall anything on
its own.

- **"🌐 NETWORK" card**: local IP, installed tier, Kali container/graphical interface (if pro),
  and a **"🔍 Scan devices on the LAN (nmap -sn)"** button — runs `nmap -sn <subnet>/24` in the
  background with a 25s timeout, parses live hosts.
- **"Reconnaissance / Web / Pro" tabs**:
  - **Reconnaissance**: nmap and theHarvester, each with a native "quick scan" panel (dialog
    with target + a preset of official flags, result parsed into a monospace dialog) plus a
    "full terminal" button for advanced flags.
  - **Web**: nikto (with a category selector `-Tuning`), dirb (with a wordlist selector
    `common.txt`/`big.txt`) and sqlmap (dropdown of 4 actions: quick test `--batch` / list
    databases `--dbs` / list tables `--tables` / dump a table `--dump`, with optional
    `--level`/`--risk` on the quick test and an extra warning before `--dump`).
  - **Pro**: if the installed tier is pro, a "☠ Enter Kali (proot-distro)" button and, if it
    has a GUI, "🖥 Start Kali desktop (XFCE)"; if not pro, redirects to the installation
    variant selector.
- **"MAINTENANCE" card** — Update/Uninstall, the standard pattern shared with the rest of the
  modules.

Every native panel requires an explicit tap from the user — none of them run without user
action.

## 9. `modules.json` — real declaration

```json
{
  "id": "ciberseguridad",
  "name": "Ciberseguridad",
  "script": "ciberseguridad.sh",
  "size": "~30MB (pro: +several hundred MB for the Kali container)",
  "type": "Nativo",
  "requiresProot": false,
  "hasVariants": true,
  "estimate": "~2 min (basic) / ~10-20 min (pro, depending on network)",
  "hasSwitch": false,
  "terminalCommand": "nmap",
  "arch": "bionic",
  "category": "seguridad",
  "installMethods": ["pkg", "pip"],
  "requires": ["python"],
  "recommended": false
}
```

## 10. Kali in the Mini PC distro catalog

The "Mini PC" tab's distro catalog (independent of the Kali container this module installs)
also includes `kali` as an option — it installs the same OCI image
(`kalilinux/kali-rolling -n kali`) this module uses. If the user already has a `kali` container
from either path, the other one detects it as "already installed" without duplicating it. It's
marked as an experimental distro (less tested on real devices than ubuntu/debian/alpine).

## 11. Responsible use — in-app disclaimer

The module screen shows:

1. **Always-visible fixed banner** — a "⚠ RESPONSIBLE USE" card at the very top, with short
   text and a "View full legal detail" button that expands the long text into a dialog.
2. **One-time acceptance gate** — a non-cancelable dialog with a checkbox reading "I understand
   and agree to use these tools responsibly and legally", which enables the positive button
   only when checked. Shown the first time the user enters with the module already installed,
   before the rest of the screen is rendered. Not asked again once accepted.

In addition, the `sqlmap --dump` action (real data extraction) shows an extra warning dialog
since it's the most sensitive action in the catalog.

## 12. Tools evaluated and not adopted (basic tier)

To keep the basic tier 100% native (bionic, no root), these were evaluated and dropped due to
real platform limitations:

- **wireshark/tshark** (live capture) — requires raw sockets, blocked on Android without root.
- **aircrack-ng** (WiFi monitor mode) — the WiFi chip driver on the vast majority of
  non-rooted Android devices doesn't expose monitor mode to userspace.
- **metasploit-framework** — a full Ruby framework (hundreds of MB, optional database), doesn't
  fit a lightweight native panel; real usage stays within the Kali Pro container.

**hydra** (credential bruteforcing) is technically viable (lightweight C binary, no root,
available as a Termux package) and remains a candidate for a future basic-tier expansion.

## 13. Known limitations

- **theHarvester's `--screenshot` doesn't work** on any tier — it depends on a real Chromium
  via playwright, which has no wheel for Bionic libc.
- **`kali-tools-top10` may end up incomplete** if the installation inside the container fails
  or is cut short by a time limit — the script doesn't abort, the container stays usable but
  with no guarantee that all 10 top Kali tools are installed.
- **The "already installed" check doesn't verify the pro tier** — it only checks whether the
  basic tier is on the PATH.
