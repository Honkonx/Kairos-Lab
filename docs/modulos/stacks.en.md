# Stacks / Test Environments — Kairos Module

**Kairos module** — managed through the Kairos UI (Modules tab, "Test Environments" screen). Unlike the rest of `modulos/*.sh`, `stacks.sh` **does not install a package of its own** — it is a catalog that reuses already-existing modules (`python.sh`, `db.sh`) and adds Node.js/PHP/Go/Rust/Java directly through `pkg`.

---

**Project:** Kairos
**Script:** `modulos/stacks.sh` (v1.5.0) — identical to `app/src/main/assets/scripts/stacks.sh`
**Fragments:** `app/src/main/java/com/termux/app/ui/StacksFragment.kt` (real projects, routed from `ModuleDetailNavigator.kt:38`) + `StacksPackagesFragment.kt` ("📦 Required packages", presets)
**`id` in `modules.json`:** `stacks` — `category: dev`, `installMethods: [pkg, proot-distro]`, `port: ""`, `hasSwitch: false`, `catalogVersion: 1.1.0`. No central switch: each preset/action invokes the script directly through `ProcessBuilder`, it does not go through `ModuleController.installModule()`.

> Based on reading `app/src/main/assets/modules.json`, `modulos/stacks.sh` (1122 lines), `StacksFragment.kt` (1014), `StacksPackagesFragment.kt` (336) and `ModuleController.kt`.

---

## 1. What it is and what it is for

A "Test Environments" catalog for development inside the phone, with two modes:

1. **Presets** (ready-made recipes that install a language/stack, native in Termux or inside a proot distro) — they live in `StacksPackagesFragment`, reachable from a project's detail ("📦 Required packages").
2. **Real projects** — user folders for which the stack is detected from the files present, real dependencies are installed (`npm install` / `venv+pip`) and which are run in the background (tmux) on 3 targets (native / proot distro / udocker container), with the option of exposing them through Cloudflare Tunnel.

## 2. Classification: FUNCTIONAL MODULE

It is not just "install and open terminal": it has its own UI with persistent state (SharedPreferences `kairos_stacks_prefs`: `project_paths`, `project_states`, `project_subdirs`), multi-project management, monorepos, background execution with a switch, live logs and a tunnel. Approximate UI options: **~20** (project list with add/remove; detect subprojects; "Start all" with delay; Required packages; target dropdown; distro selector; udocker simple/full mode; install dependencies; command field; background switch; view logs (+ full terminal); expose with Cloudflare; and in `StacksPackagesFragment` 9 preset rows with a native button, a distro button, an extras selector and a flavor selector). Since it has no `hasSwitch` or module process of its own, `ModuleController` has no `"stacks"` entry: the "state" lives per project, not per module.

## 3. Permissions

None of its own — it uses the permissions already granted by the Kairos wizard (storage for the folder browser). It opens no ports of its own (the processes it starts do, depending on the user's command).

## 4. Installation (`modulos/stacks.sh`)

- `stacks.sh --silent` without `--preset` is a no-op that only writes the registry with `presets=python-postgres,php-mysql,react-vite,html,go,rust,java,dotnet,linux-completo` (`stacks.sh:723-731`).
- Flags (`stacks.sh:149-162`): `--silent --force --describe --describe-files --preset --distro --flavor --extra --project-path --project-action --project-target --project-distro --project-cmd --project-udocker-mode`.
- The `catalogVersion` in `modules.json` (1.1.0) lags behind the script (1.5.0); the script's interactive banner still says `v1.1.0`.

### Presets (`VALID_PRESETS`, `stacks.sh:723`) — 9

| Preset | Native (Termux, `pkg`) | Distro (`apt-get` inside proot) |
|---|---|---|
| `python-postgres` | `python` + PostgreSQL through chained `db.sh` | `python3 python3-pip postgresql` |
| `php-mysql` | `php` + MySQL/MariaDB through chained `db.sh` | `php php-mysql mysql-server` |
| `react-vite` | `nodejs` (includes npm — covers React/Vite/TypeScript/JavaScript) | `nodejs npm` |
| `html` | `python` (no build, `python3 -m http.server`) | `python3` |
| `go` | `golang` → `go` binary | `golang-go` |
| `rust` | `rust` → `cargo` binary | `rustc cargo` |
| `java` | `openjdk-21` → `java`/`javac` | `default-jdk` |
| `dotnet` | **No native package** (the .NET SDK is only packaged for real Ubuntu/Debian through apt, not for Bionic/Termux) — error if `TARGET != distro` (`stacks.sh:747`) | `dotnet-sdk-8.0` (LTS). Compiling may fail on some devices due to a known `csc.dll` SIGSEGV on ARM64/proot, a .NET limitation in this environment |
| `linux-completo` | — (ALWAYS proot-distro) installs a full real Linux distro (Debian Bookworm by default, or Ubuntu with `--flavor ubuntu`); asks for a debian/ubuntu flavor | — |

- **Target** (`--distro <name>`): if passed, it installs INSIDE an already-installed proot distro (`proot-distro login <name> -- apt-get install`) instead of natively. The UI disables the "install in distro" button if no distro is installed (`EntornoNative.distroList()`). `modulos/stacks.sh` also has real guards: "proot-distro is not installed — install the Entorno module first" and "udocker is not installed — install the udocker module first".
- **Chained database**: native `python-postgres`/`php-mysql` run `bash db.sh --silent` (`ensure_db_native`); `db.sh` is idempotent. If it is not available next to `stacks.sh`, it only warns and continues.
- **Final summary**: each preset prints `[RESUMEN]` lines with the real commands to start each piece (e.g. `python3 your_app.py`, `bash ~/scripts/db/postgres_start.sh`); the UI filters them and shows them in the success dialog (`ProgressDialogController.success()`).
- **Verification**: `install_native_preset` runs `command -v <binary>` after `pkg install` (`stacks.sh:~890-893`); it does not use `verify_binary_installed()` (it does not run `--version`). In a distro it does not verify the resulting binary, only `apt-get`'s exit code.
- **`--extra`** (catalog `extra_packages_catalog`): `pip install --user` / `npm install -g` (or `pip3 --break-system-packages` / `npm -g` inside the distro). Extras: `python-postgres`: fastapi, django, flask, uvicorn, sqlalchemy, requests, numpy, pandas; `react-vite`: express, next, vue, axios; `php-mysql`: laravel (installs Composer only in a distro).

## 5. Real Project mode (`--project-path`)

Flags: `--project-path <folder> --project-action detect|install|start|stop|status|logs [--project-target native|distro|udocker] [--project-distro <name>] [--project-cmd <command>] [--project-udocker-mode simple|full] --silent`.

### 5.1 Stack detection

A simple detector based on the files present (deliberately not exhaustive), implemented in duplicate for performance (it avoids spawning a process to check 4-5 files): `project_detect()` in `stacks.sh` and `detectProjectStack()` in the Fragment.

| Signal in the folder | Tag |
|---|---|
| `package.json` | `node` |
| `requirements.txt` or any `*.py` | `python` |
| `*.db`/`*.sqlite`/`*.sqlite3` | `sqlite` |
| `composer.json` (or a broad PHP marker) | `php` |
| `index.html` without `package.json` | `html` |

### 5.2 Targets (`--project-target`)

| Target | What it really does |
|---|---|
| `native` (default) | `npm install` if there is a `package.json`; `python3 -m venv venv` + `pip install -r requirements.txt` if there is Python. Installs Node/Python if missing |
| `distro` | Installs Debian automatically if missing (`_ensure_distro_installed`), copies the project INSIDE the rootfs (`$PREFIX/var/lib/proot-distro/installed-rootfs/<distro>/root/<name>`; it **first deletes** `rm -rf $rootfs/root/$name`), runs `_install_essentials` (apt set `_ESSENTIAL_APT_PACKAGES`: build-essential, cmake, python3 +pip/venv, sqlite3, postgresql, mariadb-server, nodejs, npm, php...; plus the cloudflared `.deb`) with a prior `dpkg --configure -a` as self-heal, and runs `npm install`/`pip3 install -r requirements.txt` INSIDE the distro (`proot-distro login <distro> -- ...`). `apt-get`/`npm`/`pip` over proot are slower |
| `udocker` | The project is NOT copied — it is mounted with a **bind-mount** (`udocker run --volume=<host>:/app`) over an official Docker Hub image: `simple` mode (`node:20` / `python:3.12` / `php:8.3-cli`; `html` falls back to `python:3.12`) or `full` mode (`debian:bookworm` + essentials). Container name `kairos-proj-<hash>` |

`composer.json` (PHP) is detected on all 3 targets but **Composer is not automated** — it only warns (`warn`) that `composer install` must be run by hand.

### 5.3 Start / stop / status / logs

- **`start`**: requires `--project-cmd`. Starts in `tmux new-session -d -s proj-<md5[:10] of the path> "<cmd> 2>&1 | tee ~/kairos_logs/stacks_project_<hash>.log"` (`stacks.sh:666`), with the command wrapped according to the target: native `cd '<p>' && <cmd>`; distro `proot-distro login <d> -- bash -c "cd '<p>' && <cmd>"`; udocker `udocker run --volume='<p>':/app ... sh -c "cd /app && <cmd>"`.
- **`stop`**: `tmux kill-session -t proj-<hash>`.
- **`status`**: `tmux has-session` → `[RESUMEN] running=true|false session=... log=...`.
- **`logs`**: `tail -n 200` of the persistent log.
- The UI calls the script with timeouts: install 900 s, start 30 s, stop/status 15 s.

## 6. Real app screen

### `StacksFragment.kt` — projects

- **Project list** (`buildProjectListState`): row = name + detected stack + state; tapping the chip activates that folder; "✕" removes it from the list (it does not delete from disk). "+ Add project folder" (`pickProjectFolder`: root Termux Home or internal storage, browses real folders with direct File I/O, shows the detection as confirmation).
- **Project detail** (`buildProjectDetailState`):
  - "DETECTION" card (read-only): detected stack + target recommendation (PHP recommends a distro; the rest, native).
  - "📦 Required packages" → `StacksPackagesFragment`.
  - "Detect subprojects" (monorepo): first-level subprojects with a stack signal, each behaves as an independent project; "Start all" (`startAllSubProjects`) with an optional delay.
  - "Target" dropdown (Native/Distro/udocker): "Distro" opens `pickDistroTarget()` (offers to install Debian if there is no distro); udocker simple/full mode.
  - "⬇ Install real dependencies" → `stacks.sh --project-action install`; the confirmation message changes according to the target.
  - "Command to run" field, prefilled with a suggestion by stack (`suggestedRunCommand()`): `npm run dev -- --host` / `python3 app.py` / `php -S 0.0.0.0:8080 -t .` / `python3 -m http.server 8080`.
  - "Background process" switch → `startProject()`/`stopProject()` (tmux); it reverts to `false` if validation fails (empty command, no distro chosen).
  - "📋 View live logs": native panel polling `tail -n 150` every 2 s; the "Full terminal" button opens `tail -f` in the terminal. The poll stops when the dialog is closed.
  - "🌐 Expose with Cloudflare (tunnel)": asks for the port and calls `TunnelManager.start(port, "cloudflared", null)`.

### `StacksPackagesFragment.kt` — presets

9 preset rows with a "⚙ Install native (Termux)" button and a "📦 Install in distro…" button (picks a distro if more than one is installed; disabled if there is none). `dotnet` offers no native installation: tapping it opens the distro selector directly. Everything is translated into `stacks.sh --preset ... [--distro|--flavor|--extra]` with a 900 s timeout. There is no dedicated "Open in terminal" button; only `launchTerminalCommand` for the logs.

## 7. State detection

- **Installation**: registry (`stacks.installed`) — written as `true` even if no preset has been run.
- **"Running"**: does not apply at module level; each project has its own state via `tmux has-session`.

## 8. Registry (`~/.android_server_registry`)

```
stacks.installed=true
stacks.version=1.5.0
stacks.presets=python-postgres,php-mysql,react-vite,html,go,rust,java,dotnet,linux-completo   # only if run without --preset
stacks.last_preset=<preset>       # after running a preset with --preset
stacks.last_target=native|distro|fulldistro
stacks.last_distro=<name or empty>
stacks.last_flavor=debian|ubuntu
stacks.last_extra=<extras>
```

## 9. Files and paths it creates

`~/kairos_logs/stacks_project_<hash>.log` (execution) and `..._install.log` (installation); `<rootfs>/root/<project-name>/` inside the distro (copy); udocker containers `kairos-proj-<hash>`; tmux sessions `proj-<hash>`; `venv/` and `node_modules/` inside the project folder (native); SharedPreferences `kairos_stacks_prefs`; module registry. The UI invokes the script from `~/scripts/install/stacks.sh`.

## 10. Security

- Secrets: the module stores none. Cloudflare Tunnel exposes the port publicly without authentication of its own (the user's decision on tapping).
- **Arbitrary command execution by design**: the text of the command field goes as is inside `bash -c`/tmux; the **project path is interpolated between single quotes** (`cd '$p'`): a folder with a `'` in its name would break the command (robustness, not privilege escalation, because the user already controls the command).
- `curl -sS https://getcomposer.org/installer | php` (`stacks.sh:1003`) is a curl|interpreter pattern with no hash; the cloudflared `.deb` is downloaded through `releases/latest/download` without a hash (`stacks.sh:392,429`). No sha256 for anything it downloads outside apt/pkg.
- `--extra` installs pip/npm packages with the name received without validating against the catalog (the UI only sends those in the catalog, but the script accepts any value).
- Permissions: it does no `chmod`; it uses the storage permissions already granted by the wizard.

## 11. Design notes and limitations

- `linux-completo` can also be installed from the **Entorno** module ("📦 Install distro"), which covers the same case with its own flow.
- **udocker does NOT expand `$HOME` in `--volume`** (see `UDOCKER.md`) — it requires an absolute path, which is what `$PROJECT_PATH` already carries from the folder browser.
- **`udocker cp` deliberately discarded**: bind-mount (`--volume`) was preferred, confirmed in production (udocker variant of `n8n.sh`).
- **Real Docker is not an option**: without root, `dockerd` (kernel namespaces/cgroups) is not possible on Android; udocker (via PRoot) is the "containers" alternative in this environment (see the header of `modulos/docker.sh`).
- **`project_start` with `target=distro`** does `cd '$p'` with the **host** path, while `project_install_distro` copied the project to `/root/<name>` inside the rootfs (`stacks.sh:459-466` vs `656`). Unless proot exposes that host path inside the distro (not verified), the distro launch would point to a different folder from the installed one. This is a code fact; the effect on a device is not verified.
- Composer is not automated in projects (only a `warn`), and `--extra laravel` only works in a distro.
- Inferences (not verified): that the full `cp -r` of large projects (with `node_modules`) into the distro is slow; that the cloudflared `.deb` fails on non-Debian-based distros (apt is assumed).

## 12. Added on

`modulos/stacks.sh`: **2026-08-13**.
