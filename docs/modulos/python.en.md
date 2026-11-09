# Python — Kairos Module

**Kairos module** — managed through the Kairos UI (Modules tab). Installation is handled by the app through `ProcessBuilder` → `modulos/python.sh`; all runtime interaction (info, pip, REPL, scripts) runs directly from `PythonFragment.kt` without going through the install script.

---

**Project:** Kairos
**Script:** `modulos/python.sh` (v3.0.0) — mirror in `app/src/main/assets/scripts/python.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/PythonFragment.kt`
**`id` in `modules.json`:** `python` — `hasSwitch: false` (CLI tool, no process of its own to start/stop)

---

## 1. Overview

Python is the stack's native scripting base — Python 3 (the real Termux `python` package, not a fixed version) plus a broad set of scientific/network libraries, built-in SQLite, and a folder structure (`~/sports`, `~/trading`, `~/bots`) used by scripts downloaded from the `Honkonx/termux-ai-stack` repo (Telegram bots, trading signals, image archive with vision).

It is not a service with a persistent process — it has no ON/OFF switch (`hasSwitch: false` in `modules.json`); it is a CLI tool that other modules (n8n for vision scripts, the Database module for SQLite) and the user (REPL, `pip install`, running `.py` scripts) use on demand.

Database management (including the SQLite that ships with Python) lives in the **Database** module (`id: "db"`, see `docs/modulos/base-de-datos.md`). The SQLite version is reported as data within the Python info (`import sqlite3`), but there is no dedicated SQLite button on this screen.

## 2. Permissions

- **Android**: none specific to this module — it uses the same Termux process that already has its base permissions (storage, granted in the wizard). It needs no extra `INTERNET` (the app already has it), no overlay, no notifications.
- **Termux internal**: nothing beyond package installation via `pkg`/`pip` — it does not touch `sshd`, does not open ports, needs no root.

## 3. Full installation logic (`modulos/python.sh`)

A 10-step script, with checkpoints (`check_done`/`mark_done`, file `$HOME/.install_python_checkpoint`) so it can resume after an interruption without repeating completed steps:

| Step | What it does | Checkpoint |
|---|---|---|
| 1 | `pkg update && pkg upgrade` (with fallback to 3 mirrors if the main one fails: `packages.termux.dev`, `mirror.accum.se`, `mirrors.tuna.tsinghua.edu.cn`) | `termux_update` |
| 2 | Installs `tur-repo` (Termux User Repository) | `tur_repo` |
| 3 | Installs `python` (pkg) + updates `pip` | `python_install` — **verifies that `python3` really responds before skipping the step** (see section 7) |
| 4 | `proot-distro`, `binutils`, `libopenblas`, `clang` | `system_pkgs` |
| 5 | SQLite CLI + image deps (`libjpeg-turbo`, `libpng`, `zlib`) | `sqlite_image_deps` |
| 6 | 12 pip packages, in strict order: `six`, `certifi`, `idna`, `urllib3`, `charset-normalizer`, `soupsieve`, `typing_extensions`, `python-dateutil`, `beautifulsoup4`, `requests`, `websockets`, `pillow` | `pip_packages` — each package is tried individually; one failing does not abort the rest |
| 7 | `numpy`/`scipy`/`pandas` — first tries the precompiled ARM64 `pkg` package (`python-numpy`/`python-scipy`/`python-pandas`), falling back to `pip install` if that fails | `scientific_pkgs` |
| 8 | Downloads scripts from `github.com/Honkonx/termux-ai-stack` (folders `python/sports`, `python/trading`, `python/bots`) through the GitHub API (dynamic listing of `.py` files, not a hardcoded list) + the 3 vision scripts (`vision_bot.py`, `bot_utils.py`, `image_archive.py`) | `all_scripts` |
| 9 | Aliases in `.bashrc`: `py3`, `pip3-install`, `sqlite-n8n` | `python_aliases` |
| 10 | Verifies `python3` with `verify_binary_installed` and writes the registry (see section 6) | — |

**Folder structure created**: `~/sports/{scripts,db,logs,models}`, `~/trading/{scripts,db}`, `~/bots/{scripts,db}`.

**Supported flags**: `--silent` (no prompts, app mode), `--force` (reinstall even if already installed), `--describe` (declarative JSON manifest — `{"id":"python","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}`, no variants), `--describe-files` (deliberately returns `files: []`). The pip calls use the `flock` wrapper from `lib.sh` (pip lock, 600 s).

## 4. State detection

- **Installation**: the registry (`~/.android_server_registry`) — but `PythonFragment.isModuleInstalled()` does not blindly trust it: it also checks `isTermuxBinaryAvailable("python3")` (actually runs `command -v python3` through `bash -c`) before considering the module installed, because the registry can say `python.installed=true` while `python3` is broken in practice.
- **"Running"**: not applicable — `hasSwitch: false`, it is a CLI tool with no persistent process of its own.

## 5. Real app screen (`PythonFragment.kt`)

Classification: **functional module** (own `PythonFragment` screen, routed from `ModuleDetailNavigator.kt`). No service or switch, but with 9 top-level buttons (info, REPL, pip install, packages by category, pip list, pip outdated, run .py, venv, update) and sub-flows (venv: create/requirements/delete; catalog of 8 categories). In `modules.json`: `recommended: true`, size ~80MB, `terminalCommand: python3`, no `requires`.

"STATUS" card (version, pip — filled in when tapping "View version and info") + buttons:

| Button | Real action |
|---|---|
| ℹ View version and info | Runs `python3 --version`, `pip --version`, `command -v python3`, and tests `import` of 7 packages (numpy/scipy/pandas/requests/websockets/PIL/bs4) to build the real state |
| ⌨ Open REPL (python3) | `launchTerminalCommand("python3")` — opens the adapted terminal with the interpreter running |
| 📦 Install package (pip) | Free-text prompt → `python3 -m pip install --break-system-packages <package>` |
| 🗂 Packages by category | Curated catalog of 8 categories (Algorithms/Math, Data, AI/ML, NLP/Text, Web/Scraping, Science, Tools, Cybersecurity) — the user picks category → package → confirms, without having to memorize PyPI names. Installs the same way as the button above (`pip install --break-system-packages <package>`) |
| 📋 List installed packages | `pip list --format=json` (with a fallback to plain-text parsing if the JSON fails) |
| ⬆ Outdated packages | `pip list --outdated --format=json` — each row is tappable and offers to update that specific package |
| ▶ Run .py script | Looks for real `.py` files in `~/python`, `~` (depth 1) and `/storage/emulated/0/Download` (depth 2), skipping `node_modules`/`.git`/`venv`/`.venv`/`.gradle`/`build`/`__pycache__` — shows them in a numbered list; if it finds nothing, it falls back to asking for a manual path |
| 🧪 Virtual environment (venv) | **Per-project** venv management (pick a folder from `~/proyectos`) — see section 5b |
| 🔄 Update Python | `updateModuleService()` — reinstalls/updates through `modulos/python.sh`, same mechanism as the rest of the modules without a dedicated CLI |

All the logic of these actions lives in Kotlin (`PythonFragment.kt`, `runCommand()` with `ProcessBuilder` + `applyTermuxEnv()`) — it does not go through any intermediate Python script. The STATUS card loads by itself when the screen opens. The REPL uses `launchTerminalCommand()` (the real mechanism for opening the terminal with a command); no other action uses the `terminalCommand` field of `modules.json`.

## 5b. Per-project virtual environments (venv)

Lets you isolate a project's dependencies without touching the rest of the system (without this, `pip install` always installs globally with `--break-system-packages`).

- **Pick project**: lists folders in `~/proyectos` (the same shared folder used by `ExpoFragment.listProjectDirs()` and the rest of the modules with "Manage projects") — if there are none, it says so and offers nothing else.
- **Create venv** (`Create venv (--system-site-packages)`) → `python3 -m venv --system-site-packages <project>/.venv`. The `--system-site-packages` flag is deliberate: in Termux the heavy packages (numpy/scipy/pandas) come precompiled via `pkg install`, not as ARM64 wheels on PyPI — a fully isolated venv could not install them through pip, so the venv inherits those system packages and only isolates what the project installs with pip.
- **Install requirements.txt**: if the project has a `requirements.txt`, installs with `<project>/.venv/bin/pip install -r requirements.txt` (if a venv exists) or with global pip `--break-system-packages` (if no venv has been created).
- **Delete venv**: recursively deletes `<project>/.venv`.
- The actions menu (`showVenvActions()`) is contextual: it only shows "Create venv" if none exists, only shows "Install requirements.txt" if the file exists, and only shows "Delete venv" if one already exists.
- **Path resolution**: `runCommand()` prepends `$PREFIX/bin/` to the first element of the command (e.g. `python3` → `$PREFIX/bin/python3`), unless it is already an absolute path (e.g. `<project>/.venv/bin/pip`), which is left as is.

## 6. Registry (`~/.android_server_registry`)

```
python.installed=true
python.version=<real version, e.g. 3.13.2>
python.install_date=<YYYY-MM-DD>
python.location=termux_native
python.sqlite=true
python.pillow=true
python.numpy=true
python.scipy=true
python.pandas=true
python.requests=true
python.websockets=true
python.beautifulsoup4=true
python.sports=true
python.bots=true
```

## 7. Implementation notes and limitations

- **Checkpoint verified against the real binary**: if `check_done "python_install"` says true, the step is skipped only after confirming with `command -v python3` that `python3` actually responds now; the UI (`isModuleInstalled()` in `PythonFragment.kt`) applies the same criterion.
- **Version**: the real version comes from the pkg package; `modules.json` shows `catalogVersion: 3.13.x` and the description "Python 3.13" only as catalog text.
- **External network dependency**: `python.sh` declares `github.com/Honkonx/termux-ai-stack` as its repo and STEP 8 downloads the sports/trading/bots/vision scripts from there through the GitHub API. If that repo changes or disappears, STEP 8 fails without preventing the installation (best-effort, `|| warn` branches; inferred behavior, not tested).
- **Security**: installed with `--break-system-packages` (global pip); no secrets. The free-text pip install accepts any package name (typosquatting risk inherent to pip; no allowlist).
- **Installation weight**: `proot-distro`, `clang` and `libopenblas` are always installed even if the user only wants Python (size/time).
- **Uninstall**: not defined in `ModuleController` — Python is treated as a shared runtime and is never uninstalled.
- **Added on**: `modulos/python.sh` 2026-07-19.
