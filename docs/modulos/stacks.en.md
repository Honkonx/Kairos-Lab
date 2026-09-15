# Stacks / Test Environments

**Kairos module** — managed from the UI ("Test Environments" screen). Unlike the rest of `modulos/*.sh`, `stacks.sh` **does not install a package of its own** — it's a catalog that reuses existing modules (`python.sh`, `db.sh`) and adds Node.js/PHP directly via `pkg`.

---

**Script:** `modulos/stacks.sh`
**Fragments:** `app/src/main/java/com/termux/app/ui/StacksFragment.kt` (fixed presets) + `StacksProjectFragment.kt` (real project)
**`id` in `modules.json`:** `stacks` — no central switch (each preset/action invokes the script directly, without going through the standard module install lifecycle)

---

## 1. Overview

Stacks (the "Test Environments" screen) has two completely different modes, each with its own Fragment:

1. **Fixed presets** (`StacksFragment.kt`) — prebuilt recipes: pick a known stack (Python+PostgreSQL, PHP+MySQL, React+Vite, plain HTML, or a full Linux distro) and install it in a couple of taps, either native in Termux or inside a proot distro.
2. **Real project** (`StacksProjectFragment.kt`) — operates on a project folder the user has already picked: detects the stack from the files present, installs real dependencies (`npm install` / `venv+pip`), and starts/monitors the process in the background (tmux), with the option to expose it via a Cloudflare Tunnel.

## 2. Permissions

None of its own — uses the same permissions already granted by Kairos's setup wizard (storage for Project mode's folder browser). Doesn't open any ports of its own (the processes it starts do, depending on whatever the user sets as the command).

## 3. Fixed presets (`StacksFragment.kt` + `modulos/stacks.sh --preset`)

`stacks.sh` accepts `--silent --preset <id> [--distro <name>] [--flavor debian|ubuntu] [--force] [--describe]`.

| Preset | Native pieces (Termux, `pkg`) | Distro pieces (`apt-get` inside proot) |
|---|---|---|
| `python-postgres` | `pkg python` + PostgreSQL chained via `db.sh` | `python3 python3-pip postgresql` |
| `php-mysql` | `pkg php` + MySQL/MariaDB chained via `db.sh` | `php php-mysql mysql-server` |
| `react-vite` | `pkg nodejs` (covers React/Vite/TypeScript/JavaScript) | `nodejs npm` |
| `html` | `pkg python` (no build step, `python3 -m http.server`) | `python3` |
| `linux-completo` | — (always proot-distro, never native) — installs a full real Linux distro (Debian Bookworm by default, or Ubuntu with `--flavor ubuntu`) | — |

**Script/UI discrepancy**: the script supports all 5 presets (including `linux-completo`), but `StacksFragment.kt` only exposes 4 cards. To install a full Linux distro from the app, use the **Entorno** module ("Install distro"), which covers the same use case with its own flow — `stacks.sh --preset linux-completo` remains available only via CLI/script, with no button on this screen.

**Destination** (`--distro <name>`, applies only to the 4 lightweight presets): if passed, installs inside an already-installed proot distro instead of natively. The UI disables the "Install in distro…" button if no distro is installed.

**Chained database**: the `python-postgres`/`php-mysql` presets run `bash db.sh --silent` instead of just warning — `db.sh` is idempotent (checks binaries before installing).

**Final summary**: each preset ends by printing the real commands to start each piece — the UI filters and shows them in the success dialog.

## 4. Real Project mode (`StacksProjectFragment.kt` + `modulos/stacks.sh --project-path`)

Flags: `--project-path <folder> --project-action detect|install|start|stop|status|logs [--project-target native|distro|udocker] [--project-distro <name>] [--project-cmd <command>] --silent`.

### 4.1 Stack detection

A simple, deliberately non-exhaustive file-based detector, implemented identically in the script and in Kotlin — same logic on both sides:

| Signal in the folder | Tag |
|---|---|
| `package.json` | `node` |
| `requirements.txt` or some `*.py` | `python` |
| `*.db`/`*.sqlite`/`*.sqlite3` | `sqlite` |
| `composer.json` | `php` |
| `index.html` with no `package.json` | `html` |

### 4.2 Install destinations (`--project-target`)

| Destination | What it actually does | Automation level |
|---|---|---|
| `native` (default) | `npm install` if there's a `package.json`; `python3 -m venv venv` + `pip install -r requirements.txt` if there's Python | Full — installs Node/Python if missing |
| `distro` | Copies the project inside the distro's real rootfs, installs whatever system dependencies are missing (`nodejs npm`, `python3 python3-pip`, `php`) via `apt-get`, and runs `npm install`/`pip3 install -r requirements.txt` inside the distro | Full — automates just as much as `native`/`udocker`, with the honest caveat documented in the script itself that `apt-get`/`npm`/`pip` over proot are slower |
| `udocker` | The project isn't copied — it's bind-mounted directly onto an official Docker Hub image matching the detected stack (`node:20` / `python:3.12` / `php:8.3-cli`, `html` falls back to `python:3.12`) | Full — same level as `native`, without copying anything |

`composer.json` (PHP) is detected across all 3 destinations, but Composer is not automated in any of them — it just warns that `composer install` needs to be run by hand.

### 4.3 Start / stop / status / logs

- **`start`**: requires `--project-cmd`. Starts in its own tmux session (MD5 hash of the path as the identifier), with the command wrapped according to the destination. Persistent log at `~/kairos_logs/stacks_project_<hash>.log`.
- **`stop`**: kills the project's tmux session.
- **`status`**: reports whether the session is still alive.
- **`logs`**: `tail -n 200` of the persistent log.

### 4.4 `StacksProjectFragment.kt` screen

Cards: **PROJECT** (choose folder) → **DETECTION** (tags + a text recommendation: PHP recommends distro due to heavier system dependencies, everything else recommends native) → **EXECUTION DESTINATION** (Native/Distro/udocker; the Distro option opens a selector of installed distros) → **DEPENDENCIES** (install button) → **EXECUTION AND MONITORING** (editable command field, pre-filled with a suggestion based on the stack — `npm run dev -- --host` / `python3 app.py` / `python3 -m http.server 8080` —, start/stop, a status pill, "View live logs") → **EXPOSE** (button that asks for a port and reuses the same Cloudflare Tunnel mechanism as the rest of the app).

## 4.5 Monorepos — sub-project detection

When a folder added to Real Project mode contains several independent sub-projects (for
example `frontend/` and `backend/` inside the same repo), Kairos can detect them and manage
each one separately instead of forcing the whole folder to be treated as a single stack:

- When adding a new folder (or by manually requesting it on an already-saved project with
  "Detect sub-projects"), Kairos scans first-level subfolders for their own stack signal (the
  same files normal detection already uses — `package.json`, `requirements.txt`,
  `composer.json`, etc.), skipping typical dependency/build folders (`node_modules`, `.git`,
  `venv`, `dist`, `build`, and similar) to avoid false positives. The scan is single-level, with
  no recursion — a sub-project is never scanned again looking for sub-sub-projects.
- If more than one sub-project is found, a dialog lets you pick which ones to treat as
  independent sub-projects (all checked by default) or dismiss the detection and keep treating
  the folder as a single-stack project.
- Each confirmed sub-project then behaves exactly like a regular, complete project — its own
  detection, its own install destination (native/distro/udocker), its own command, and its own
  background session — the only new piece is the visual grouping under the parent project and a
  **"Start all"** button.
- **"Start all"** launches each sub-project in the order they appear in the list, with an
  optional delay between one and the next (useful, for example, to give a backend time to come
  up before starting the frontend that depends on it) — there's no automatic dependency
  resolution between sub-projects; it's up to the user to order the list or set the delay as
  needed.
- A "Detect again" button inside a monorepo's view adds any newly-appeared sub-projects without
  touching the already-saved configuration of the ones already there.

## 5. State detection

- **Installation**: registry (`stacks.installed`) — gets written as `true` even without any preset having been run.
- **"Running"**: not applicable at the module level — each individual Real Project has its own state via a tmux session.

## 6. Registry (`~/.android_server_registry`)

```
stacks.installed=true
stacks.version=1.4.0
stacks.presets=python-postgres,php-mysql,react-vite,html,linux-completo   # only if run without --preset
stacks.last_preset=<preset>       # after running a preset with --preset
stacks.last_target=native|distro|fulldistro
stacks.last_distro=<name or empty>
stacks.last_flavor=debian|ubuntu
```

## 7. Design notes

- **`linux-completo` has no button in `StacksFragment.kt`** — not a bug (the Entorno module covers the same use case), but it can confuse anyone who only reads the script and expects to see all 5 options in the UI.
- **udocker does not expand `$HOME` in `--volume`** — it requires an absolute path, which is already what comes from the project's path via the app's folder browser.
- **Real Docker is not an option** — without root, the Docker daemon (kernel namespaces/cgroups) isn't possible on Android; udocker (via PRoot) is the real "container" alternative in this environment.

## Screen controls

### `StacksFragment.kt` — main screen ("Test Environments")

| Control | What it does |
|---|---|
| "Project (real folder)" | Navigates to `StacksProjectFragment` |
| "Install native (Termux)" (per preset) | Runs `stacks.sh --preset <id> --silent` |
| "Install in distro…" (per preset) | Picks a distro if more than one is installed, runs with `--distro <name>` — disabled if no proot distro is installed |

### `StacksProjectFragment.kt` — "Project" (its own screen)

| Control | What it does |
|---|---|
| "PROJECTS" card: row per folder | Multiple folder selection — the backend supports concurrent projects (session/log/hash per path) |
| "Add project folder" | Choose root (Termux Home / internal storage), browse real folders |
| "DETECTION" card (read-only) | Detected stack + destination recommendation |
| "Destination" dropdown (Native/Distro/udocker) | Changes the install destination — "Distro" opens a sub-dialog before confirming |
| "Install real dependencies" | Real `npm install`/`venv+pip install`, not simulated — the confirmation message changes based on the chosen destination |
| "Command to run" text field | Editable, pre-filled with a suggestion based on the detected stack |
| "Background process" switch | Starts/stops the project in tmux |
| "View live logs" | Native panel polling the project's log every 2s |
| "Full terminal" (inside the logs dialog) | For very long logs where scrolling/searching in the real overlay is more convenient |
| "Expose with Cloudflare (tunnel)" | Asks for the port, reuses the same tunnel mechanism as the rest of the app |

## Additional presets (go/rust/java/dotnet)

Note: these presets live in `StacksPackagesFragment.kt` ("Required packages", accessible from a project's detail view), not in `StacksFragment.kt`.

4 additional presets, confirmed against real Termux and Debian/Ubuntu packages:

| Preset | Native (Termux) | Distro (Debian/Ubuntu via apt) |
|---|---|---|
| `go` | `pkg install golang` → `go` binary | `golang-go` (this is how Debian packages Go) |
| `rust` | `pkg install rust` → `cargo` binary | `rustc cargo` (Debian splits them into 2 packages) |
| `java` | `pkg install openjdk-21` → `java`/`javac` binaries | `default-jdk` (real metapackage that resolves to the available JDK) |
| `dotnet` | No native package — the .NET SDK is only packaged for real Ubuntu/Debian via apt, it doesn't exist for Termux's libc | `dotnet-sdk-8.0` (LTS) — real note added to the post-install summary: compiling can fail on some devices due to a known `csc.dll` issue on ARM64/proot, a real limitation of .NET in this environment |

`dotnet` is a special case in the UI: since it has no native install, its row doesn't offer the usual "install native" button — tapping it opens the distro selector directly.

**proot-distro/udocker detection**: `modulos/stacks.sh` has real guards that clearly warn the user if `proot-distro` or `udocker` aren't installed, pointing to which module to install first. The UI also disables the "install in distro" button when no distro is installed, with the same message.
