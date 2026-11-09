# Cybersecurity — Kairos Module

**Kairos Module** — managed through the Kairos UI (Modules → "Ciberseguridad" card).
A network/OSINT/pentesting/self-defense toolkit in 2 levels: a **basic** level that is 100% native (bionic, no proot) and a **pro** level that adds a full Kali Linux container via `proot-distro`, with or without a graphical interface.

---

**Project:** Kairos
**Script:** `modulos/ciberseguridad.sh` (v2.3.0, 918 lines, `:63-64`)
**Fragment:** `app/src/main/java/com/termux/app/ui/CiberseguridadFragment.kt` (1209 lines; `ModuleDetailNavigator.kt:39`)
**`modules.json` (`:547`):** `id: ciberseguridad` — `hasSwitch: false`, `hasVariants: true`, `requiresProot: false` (proot is only used conditionally inside the script for the pro level), `arch: bionic`, `category: seguridad`, `terminalCommand: nmap`, `port: ""`, `repo: Honkonx/kairos-lab`, `requires: ["python"]`, `installMethods: ["pkg","pip"]`, `recommended: false`, size `~30MB (pro: +several hundred MB for the Kali container)`, estimate `~2 min (basic) / ~10-20 min (pro, depending on network)`

---

## 1. Scope and responsibility (read before the rest)

The script itself states this in its header: these are standard network/OSINT/pentesting tools, meant for diagnosing the user's own network and authorized pentesting. A broader catalog of offensive tools (bruteforce, Metasploit automation, Android forensics, DVWA/bWAPP/Mutillidae practice servers) is **not implemented**; do not assume it exists.

**Classification: FUNCTIONAL MODULE.** Own UI with state (reads `ciberseguridad.tier/kali_container/kali_gui` from the registry, `CiberseguridadFragment.kt:179-183`), 3 tabs, native panels with per-tool parameters, detection of an installation in progress (`:219-221`, banner polled every 3 s) and a legal-disclaimer gate (`:236-295`). There is no service or port: it is a toolkit of one-off executions, not a daemon. UI options: ~16 buttons + 1 dropdown (sqlmap actions) + 3 install dialogs (+ a selector of 13 Kali categories) + a maintenance card (`addMaintenanceCard()`, `:226`).

## 2. Permissions

None of its own on Android — it runs in Termux userspace (native bionic for the basic level, `proot-distro` for the pro level), without root, with no permissions beyond Kairos' generic wizard. The pro level downloads a container of several hundred MB — it only requires space and network. `su -c` is only used if root is already present (`RootAccess`).

## 3. Variants / levels (`--variant`)

Flags (`ciberseguridad.sh:104-114`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant basico|pro-headless|pro-gui[:category]`. The variant is chosen at install time (`BottomSheetInstalacion`); there is no central switch at runtime — to change level you must reinstall with another variant. An unknown variant falls back to basic (`:222-226`); an unknown category falls back to `top10` (`:136`).

| Variant (`--variant`) | Internal tier (`$PRO`/`$PRO_GUI`) | What it installs |
|---|---|---|
| `basico` (default) | `PRO=false` | Only the native kit (STEPS 1-5c) |
| `pro-headless` (alias `pro`) | `PRO=true`, `PRO_GUI=false` | Basic + a Kali container without a desktop |
| `pro-gui` | `PRO=true`, `PRO_GUI=true` | Basic + Kali container + XFCE4 inside the container |

STEP 7b installs a Kali metapackage selectable by category: there is a catalog of 13 metapackages via `--variant pro-*:<category>` (`KALI_CATEGORY_CATALOG`, `ciberseguridad.sh:116-137`; e.g. `top10`, `large`, `everything`), exposed in the UI (`showKaliCategoryDialog`, `CiberseguridadFragment.kt:1155-1203`).

The `--describe` manifest declares this table in JSON, with `variant_required: false` and `variant_default: basico`. `--describe-files` (`:171-216`) packages ONLY nikto (see `not_covered`); the rest cannot be packaged as a `.deb`.

**"Already installed" check:** it only evaluates the basic level (`command -v nmap && theHarvester && sqlmap`). If basic is already there but `--variant pro-*` is requested, the script **does not exit** — it goes straight on to steps 6-8 (Kali), allowing a move from basic to pro without reinstalling basic. `--force` deletes the checkpoint and forces repeating all pending steps. Limitation: if the user already has pro and runs again with `--variant basico` without `--force`, the script exits without touching anything (it does not uninstall Kali, but it does not detect it in that early check either).

## 4. Basic level (STEPS 1-5c, native bionic)

Each step has its own checkpoint (`~/.install_ciberseguridad_checkpoint`, keys `ciberseguridad_<step>=done`) — reentrant: if the script is cut off midway it resumes from the next step.

| Step | Tool | How it is installed | Notes |
|---|---|---|---|
| 1 | **nmap** | `pkg install nmap` | Fails with `error` (aborts) if not on PATH after installing |
| 2 | **netcat-openbsd + dirb** | `pkg install netcat-openbsd dirb` | Official packages. Failures are `warn` |
| 2b | **nikto** | `git clone --depth 1 https://github.com/sullo/nikto.git ~/.nikto` + a bash wrapper at `$PREFIX/bin/nikto` (`exec perl ~/.nikto/program/nikto.pl "$@"`) + `cpan -T XML::Writer` | **nikto is NOT a Termux package**. It installs `perl` first if missing. The whole step is `warn`-and-continue |
| 3 | **Python 3** | Detects `python`/`python3` on PATH; if missing, `pkg install python` | Prerequisite of theHarvester/sqlmap |
| 4 | **theHarvester** (OSINT) | `pip install --no-deps git+https://github.com/laramies/theHarvester.git` + manual installation of the remaining dependencies (read dynamically from the repo's `pyproject.toml`) + a **local `playwright` stub** | See section 5 |
| 5 | **sqlmap** | `pip install sqlmap` | Together with nmap, it uses `error` (aborts) if it fails |
| 5b | **MVT (Mobile Verification Toolkit)** | `pip install mvt` | A real Amnesty International tool — spyware/stalkerware forensics (Pegasus-style IOCs) on the user's OWN device, via ADB without root or backup analysis. Verified with `mvt-android version` (the CLI, Click-based, exposes no root-level `--version`). "Self-defense" category |
| 5c | **ClamAV** | `pkg install clamav` | A real open-source antivirus, scanning files/downloads on the device itself. After installing it runs `freshclam` best-effort with a 90 s timeout (non-critical: `clamscan` works with the package's database). "Self-defense" category |

Verification: it uses `verify_binary_installed` for theHarvester, sqlmap, MVT and ClamAV (`clamscan`); nmap/dirb/netcat/nikto use only `command -v` or a file check, without running the binary.

### 4.1 Why `nikto` has its own step

`dirb` **is** a real Termux package (`dirb_2.22-5_aarch64.deb`), but when it was on the same line `pkg install netcat-openbsd dirb nikto`, and `nikto` never existed as a package in any official repo, `apt`/`pkg` resolves **all** names of one installation before installing any — the resolution failure of `nikto` took `dirb` down with it. That is why `nikto` was split into its own step (2b) with git clone + a Perl wrapper.

### 4.2 MVT + ClamAV — "device self-defense" category

Different from the rest of the catalog: nmap/netcat/dirb/nikto/theHarvester/sqlmap (and Kali at the pro level) point **outward** (recon/pentesting of another network or system). MVT and ClamAV point **inward** — they protect the user's own device, with no need for an external target or third-party authorization. **They have no UI control** (they are installed, but can only be used by hand from the terminal): MVT (`mvt-android check-adb --output <dir>`, `mvt-android check-backup`) needs a different flow (it analyzes IOCs against downloaded indicators); ClamAV: `clamscan <path>`. A dedicated native panel remains a candidate improvement.

## 5. theHarvester — the most fragile step (STEP 4)

Two root causes documented in comments in the script (lines 246-286):

1. **The `theHarvester` package on PyPI is an abandoned placeholder** (v0.0.1, February 2019, no `entry_points`). `pip install theHarvester` ended with exit 0 but never left the command on PATH. Fix: install straight from the real repo (`laramies/theHarvester`) with `pip install git+https://...`, which does define `[project.scripts] theHarvester = "theHarvester.theHarvester:main"`.
2. **`playwright` (a hard dependency of theHarvester since its v4.6.0) has no wheel for Bionic libc in any version** (investigated between 1.42.0 and 1.60.0) — a real platform limit (it embeds Chromium binaries, PyPI only publishes manylinux/macOS/Windows wheels). A 3-part fix:
   - `pip install --no-deps` for the theHarvester package (it skips *all* dependencies so as not to abort the whole resolution).
   - The remaining dependencies are installed by hand, **read dynamically** from the repo's `pyproject.toml` (via `tomllib` + `urllib.request` in a Python heredoc, lines 301-324) instead of a hardcoded list. If the download/parse fails, it falls back to a hardcoded list (line 327).
   - A **local `playwright` stub** written into the interpreter's real `purelib` (`sysconfig.get_paths()['purelib']`, `site-packages/playwright/{__init__,async_api}.py`) — necessary because theHarvester's `__main__.py` does `from playwright.async_api import async_playwright` **unconditionally** at startup. The stub defines `async_playwright()` so it raises a `RuntimeError` with a clear message only if `--screenshot` is really used; all the other OSINT engines work normally.

All failures in this step are `warn` (non-critical), so sqlmap is still attempted even if theHarvester fails.

## 6. Pro level — Kali Linux via proot-distro (STEPS 6-8, only if `$PRO=true`)

| Step | What it does | Detail |
|---|---|---|
| 6 | Installs `proot-distro` if missing | Same call as `_install_proot_distro()` in `modulos/entorno.sh` |
| 7 | Creates the `kali` container | `proot-distro install kalilinux/kali-rolling -n kali` (2 attempts). The "does it already exist?" check uses `proot-distro list` (the `list-installed` subcommand no longer exists in `proot-distro` v5.8.0) |
| 7b | Kali metapackage | `proot-distro login kali -- apt-get install -y <metapackage>` wrapped in `timeout 900` (15 min); by default `kali-tools-top10`, not `kali-linux-everything` (responsible scope). If it fails or exceeds the timeout: `warn` + `_KALI_TOOLS="kali-tools-top10-failed"` and the container stays usable headless, and the script still reaches the final `registry_write`. The large metapackages (`large`, `everything`) probably fall short of 900 s on a slow network |
| 8 (`pro-gui` only) | XFCE4 + dbus-x11 inside the container | Reuses `~/scripts/entorno/distro_setup_gui.sh` (the same script generated by `modulos/entorno.sh` for any proot distro); if the Entorno module has not run yet, this step runs `entorno.sh --silent` first. The real exit code of that script (which verifies `startxfce4`) is respected: if it fails, `_GUI_STATUS` stays at `headless` and the container remains usable |

**Why there is no official `kali` alias in proot-distro:** `proot-distro` v5.x no longer has a curated list of distros with fixed aliases; it installs **any** Docker/OCI image by reference. That is why the official Docker Hub image `kalilinux/kali-rolling` is used (it ships with Kali's apt repos already configured) and the name `kali` is forced with `-n`/`--override-alias`, so the rest of the ecosystem (`proot-distro login kali`, `entorno.sh --diagnose`, `gui_start.sh --distro kali`) finds it.

**Kali is also in Mini PC's (Entorno) distro catalog:** `EntornoNative.KNOWN_DISTROS`/`EXPERIMENTAL` include `"kali"`; `distroInstall()` special-cases `name == "kali"` to install the real OCI image (`kalilinux/kali-rolling -n kali`). Installing Kali from Mini PC uses the same image and the same container name (`kali`) as Cybersecurity Pro, so if the user already has a `kali` container from either path, the other detects it as "already installed". In the "Install distro" dialog it appears as "experimental".

**A known limitation of step 7b:** a network or memory failure during the heavy download of metapackages (e.g. `metasploit-framework`/`wireshark` inside `kali-tools-top10`) can kill the `apt-get` child process inside `proot login`; `ModuleController.installModule()` has no timeout on `process.waitFor()` for installations (unlike `SCRIPT_TIMEOUT_SECONDS=120s` for start/stop). The `timeout 900` therefore bounds the symptom, but does not guarantee that the metapackage completes on slow networks; if it persists, the scope of STEP 7b would need reducing (lighter packages or installing them one by one with retries).

## 7. Registry

At the end, `registry_write ciberseguridad` writes (it also writes early after the basic level with tier=basico and after confirming the container, `:~860-885`; if Kali fails it is recorded as basic):

```
installed=true
tier=basico|pro
kali_container=kali|""      (empty if tier=basico)
kali_gui=true|false
tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav[,proot-distro,kali(<kali_tools>:<gui_status>)]
install_date=YYYY-MM-DD
```

`notify_event "ciberseguridad" "install_done" "$_tools"` fires the install-complete notification.

## 8. UI (`CiberseguridadFragment.kt`)

It reads the `ModuleRegistry` to decide what to show — it **does not reinstall anything on its own**, it only reads `ciberseguridad.tier` / `kali_container` / `kali_gui`.

**Responsible-use banner and gate.** There are two layers (key `ciberseguridad_disclaimer_accepted` in `SharedPreferences("kairos_prefs")`, distinct from `PREF_TOS_ACCEPTED`):
1. **Fixed, always-visible banner** (`addDisclaimerBanner()`) — a "⚠ RESPONSIBLE USE" card at the very top: *"Use these tools only against your own systems or with explicit authorization — misuse may be illegal."* + a "View full legal detail" button that expands the long text in a dialog (reuses `showScanResultDialog()`).
2. **One-time acceptance gate** (`showDisclaimerGate()`) — a non-cancelable dialog (`setCancelable(false)`) with a checkbox "I understand and agree to use these tools responsibly and legally", which enables the positive button only when ticked. It is shown BEFORE rendering the rest of the module the first time the user enters with the module installed (`buildContent()` returns early if `!isDisclaimerAccepted()`). It is not asked for again once accepted.

In addition, `sqlmap --dump` (`promptSqlmapDump()`) shows an extra "Extract data from...?" dialog because it is the most sensitive action (it can expose real personal data/credentials).

**"🌐 NETWORK" card** (always visible, it is not a tool):

| Control | What it does | Why |
|---|---|---|
| "Local IP" (read-only) | `RemoteManager.getLocalIp()` (same logic as the Remote module, DRY) | Show real device data |
| "Installed level" / "Kali container" / "Graphical interface" (read-only) | Reads `tier`/`kali_container`/`kali_gui` from the registry | The Fragment only READS the level chosen at install time |
| "🔍 Scan devices on the LAN (nmap -sn)" | `scanLan()` → `nmap -sn <subnet>/24` in the background, 25 s timeout, never automatic (only on tapping the button). It parses with the regex `Nmap scan report for (\S+)(?:\s+\(([\d.]+)\))?` and lists host+IP | It does NOT use `RemoteManager.scanSubnet()` because that algorithm is specific to HTTP health checks for OpenCode/agent servers on a fixed port; generic discovery is needed here |

**"Reconnaissance / Web / Pro" tabs** (a `TabLayout` below the NETWORK card; `buildToolsTabs()` / `renderToolsTab()`, same pattern as `EntornoFragment`; `tabCard()` is a local copy of `BaseModuleFragment.addCard()` that adds to an explicit `parent`). Each tool (except sqlmap) has a native **"quick scan" panel** (a dialog with target + a preset of official flags, stdout parsed into a list, result in a monospaced `AlertDialog`) PLUS a "full terminal" button that opens the binary with no arguments for advanced flags. No panel runs without tapping the button. Timeouts: nmap 45 s, nikto 45 s (`-maxtime 30s`), dirb 45 s, sqlmap 90/120/150 s, theHarvester 60 s. Commands run with `ManagerNativeUtils.runShell(cmd, timeout)` (`bash -c`) in a background thread; the "full terminal" buttons use `launchTerminalCommand`.

*"Reconnaissance" tab* (`:442-449`):

| Control | What it does | Why |
|---|---|---|
| "🎯 nmap — quick port scan (native panel)" | `promptNmapScan()` — asks for a target, runs `nmap -F -T4 -sV` (or a real `-sS` via `RootAccess.runAsRoot` if root is present, `:663-666`), parses a port table | nmap without a target does nothing useful; `-A` was discarded as too slow/intrusive for a quick-scan panel |
| "⌨ nmap (full terminal)" | `launchTerminalCommand("nmap")` | Advanced flags |
| "🔎 theHarvester — quick OSINT (native panel)" | `promptTheHarvesterScan()` — domain + source (spinner of 7 sources without an API key: `duckduckgo, bing, crtsh, otx, rapiddns, threatminer, urlscan`) + limit (`-d -b -l`) | Sources with credentials still have no panel — Kairos does not manage `~/.theHarvester/api-keys.yaml` |
| "⌨ theHarvester (full terminal)" | `launchTerminalCommand("theHarvester")` | Sources with an API key configured separately |

*"Web" tab* (`:451-474`):

| Control | What it does | Why |
|---|---|---|
| "🕷 nikto — quick web vulnerability scan (native panel)" | `promptNiktoScan()` — target + `-Tuning` category (dropdown 1/2/3/4/9/b, default "1"), runs `nikto -h <target> -Tuning <category> -maxtime 30s` | `-Tuning`/`-maxtime` bound the scan; flags from nikto's official wiki |
| "⌨ nikto (full terminal)" | `launchTerminalCommand("nikto")` | — |
| "📁 dirb — quick directory brute force (native panel)" | `promptDirbScan()` — target + wordlist (`common.txt` default / `big.txt`, both packaged by `pkg install dirb` in `$PREFIX/share/dirb/wordlists/`), runs `dirb <target>[ wordlist] -S -w -r` | `-r` (non-recursive) bounds the scan to one level |
| "⌨ dirb (full terminal)" | `launchTerminalCommand("dirb")` | — |
| "💉 sqlmap — choose action" dropdown | 4 mutually exclusive options: quick test (`--batch`) / list databases (`--dbs`) / list tables (`--tables -D`) / dump table (`--dump`) | It does not fit `dropdownSwitchRow()`: `dropdownRow()` + a separate "Run" button is used |
| "▶ Run chosen sqlmap action" | Dispatches to `promptSqlmapScan()`/`promptSqlmapDbs()`/`promptSqlmapTables()`/`promptSqlmapDump()`. The quick test also asks for `--level` (1-5) and `--risk` (1-3), default 1/1 | Official sqlmap flags |
| "⌨ sqlmap (full terminal)" | `launchTerminalCommand("sqlmap")` | — |

*"Pro" tab* (Kali, `:476-498`):

| Control | What it does | Why |
|---|---|---|
| "☠ Enter Kali (proot-distro)" (only if `isPro`: tier=pro and a `kaliContainer` exists) | `proot-distro login <kaliContainer>` in the terminal | — |
| "🖥 Start Kali desktop (XFCE)" (only if `isPro` and `kaliGui`) | `bash ~/scripts/entorno/gui_start.sh --distro <kaliContainer>` | Only appears if the installed level has a GUI |
| "⬆ Install Pro level (Kali)" (if NOT pro) | `showProInstallDialog()` (`:490-495, 1083-1097`): headless/GUI choice and then a Kali category (13), installs in the background | — |

**Silent installation** (before installing): a "Basic" / "Pro without GUI" / "Pro with GUI" dialog (`:1100-1150`), `installModuleInBackground(variant)` — installs in the background, the window closes and the user keeps using the app; it notifies when done.

**"MAINTENANCE" card** — "🔄 Update" (`updateModuleService`) and "🗑 Uninstall" (`ModuleController.uninstallModule`), the shared standard pattern.

## 9. Tools evaluated and not adopted as a native panel

| Tool | Viable as a native panel (bionic, no root) | Verdict |
|---|---|---|
| **hydra** (credential brute force) | Yes — a lightweight C binary, no root or monitor mode; Termux has it in its repo (`pkg install root-repo && pkg install hydra`). It would fit the existing panel pattern (target + wordlist + protocol) | Technically viable candidate; **not implemented** |
| **metasploit-framework** (`msfconsole`) | No — a full Ruby framework (hundreds of MB, optional PostgreSQL database, thousands of modules). It only makes sense inside the Kali container (`kali-tools-top10` includes it) | Not recommended as a native panel; at most a `proot-distro login kali -- msfconsole` shortcut |
| **wireshark / tshark** | Not for live capture — it requires a raw socket (`AF_PACKET`/`libpcap`), which Android blocks for non-rooted apps. `tshark` could analyze an existing `.pcap` (offline analysis) | Not recommended without root |
| **aircrack-ng** | No — it requires the WiFi chipset's monitor mode, which standard Android almost never exposes | Not recommended (hardware/driver limitation) |

The catalog of additional utilities from i-Haklab-style projects (androforensic, payloads, steganography, etc.) has a more offensive/gray profile and falls outside the "own-network diagnosis and standard authorized pentesting" criterion of section 1.

## 10. Known limitations

- **theHarvester `--screenshot` does not work** at any level — it depends on a real Chromium via playwright, which has no wheel for Bionic libc; the stub leaves a clear error message.
- **The Kali metapackage can end up incomplete** if `apt-get install` fails or exceeds the timeout inside the container — the script does not abort; the container stays usable with no guarantee that all tools are installed. Any additional tool inside the Kali container must be installed by hand (`proot-distro login kali -- apt-get install <package>`).
- **Command injection (from reading the code):** the target typed by the user is interpolated between single quotes inside a `bash -c` string without escaping single quotes: `nmap ... '$target'` (`:666`), `nikto -h '$target'` (`:738`), `sqlmap -u '$target'` (`:878`, `:1010`, `:1027`, `:1058`; also `-D '$db' -T '$table'`), `theHarvester -d '$domain'` (`:955`), `dirb '$target'` (`:807`). A value containing `'` breaks the quoting and runs arbitrary commands with Kairos' permissions. The risk is self-inflicted (the user types the value), but it is a real defect.
- **MVT and ClamAV** are installed without a UI.
- **Weak verification** (`command -v`) for nmap/dirb/netcat/nikto.
- `requiresProot:false` in `modules.json` even though pro depends on proot-distro; `terminalCommand: "nmap"` is the generic access.
- No sha256 on any download (git clone, pip, proot-distro image, pyproject over HTTPS). nikto is cloned from GitHub `master` and theHarvester from `git+https` with no commit pin or hash. No `curl|bash`.
- It stores no API keys; theHarvester sources with a key are left to the terminal.
- Inference: the udisks2 workaround (`:~790`) is marked by the script itself as not empirically confirmed.

## 11. Date added

`modulos/ciberseguridad.sh`: 2026-08-12.
