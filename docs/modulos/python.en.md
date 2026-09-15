# Python

**Kairos module** — managed from the UI (Modules tab). Installation handled by the app via `ProcessBuilder` → `modulos/python.sh`; all runtime interaction (info, pip, REPL, scripts) runs directly from `PythonFragment.kt` without going through the install script.

---

**Script:** `modulos/python.sh` — mirrored at `app/src/main/assets/scripts/python.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/PythonFragment.kt`
**`id` in `modules.json`:** `python` — no switch (CLI tool, no service process of its own to start/stop)

---

## 1. Overview

Python is the native scripting foundation of the stack — Python 3 (Termux's actual `python` package, not a pinned version) plus a broad set of scientific/networking libraries, built-in SQLite, and a folder layout (`~/sports`, `~/trading`, `~/bots`) used by reference scripts (Telegram bots, trading signals, a vision-enabled image archiver).

It is not a service with a persistent process — it has no ON/OFF switch, it's a CLI tool that other modules (n8n for vision scripts, the Database module for SQLite) and the user themselves (REPL, `pip install`, running `.py` scripts) use on demand.

Database management (including the SQLite bundled with Python) lives in the **Database** module. The SQLite version is still reported as a data point inside Python's info (`import sqlite3`), with no dedicated SQLite button on this screen.

## 2. Permissions

- **Android**: none specific to this module — it uses the same Termux process that already has its base permissions (storage, granted in the setup wizard). No extra `INTERNET` permission needed, no overlay, no notifications.
- **Internal Termux**: nothing beyond installing packages via `pkg`/`pip` — it doesn't touch `sshd`, doesn't open ports, doesn't need root.

## 3. Install logic (`modulos/python.sh`)

A 10-step script with checkpoints so it can resume after an interruption without repeating steps already done:

| Step | What it does |
|---|---|
| 1 | `pkg update && pkg upgrade` (with fallback to 3 mirrors if the main one fails) |
| 2 | Installs `tur-repo` (Termux User Repository) |
| 3 | Installs `python` (pkg) + upgrades `pip` — verifies that `python3` actually responds before skipping the step |
| 4 | `proot-distro`, `binutils`, `libopenblas`, `clang` |
| 5 | SQLite CLI + image deps (`libjpeg-turbo`, `libpng`, `zlib`) |
| 6 | 12 pip packages, in strict order: `six`, `certifi`, `idna`, `urllib3`, `charset-normalizer`, `soupsieve`, `typing_extensions`, `python-dateutil`, `beautifulsoup4`, `requests`, `websockets`, `pillow` — each package is attempted individually, one failing doesn't abort the rest |
| 7 | `numpy`/`scipy`/`pandas` — tries the precompiled ARM64 `pkg` package first, falling back to `pip install` if that fails |
| 8 | Downloads reference scripts (`python/sports`, `python/trading`, `python/bots` folders, dynamic listing of `.py` files) + 3 vision scripts (`vision_bot.py`, `bot_utils.py`, `image_archive.py`) |
| 9 | Aliases in `.bashrc`: `py3`, `pip3-install`, `sqlite-n8n` |
| 10 | Registry |

**Folder structure created**: `~/sports/{scripts,db,logs,models}`, `~/trading/{scripts,db}`, `~/bots/{scripts,db}`.

**Supported flags**: `--silent` (no prompts, app mode), `--force` (reinstall even if already present), `--describe` (declarative JSON manifest, no variants).

## 4. State detection

- **Installation**: the registry (`~/.android_server_registry`) — but `PythonFragment.isModuleInstalled()` doesn't blindly trust it: it also verifies that `python3` actually responds (`command -v python3` via shell) before considering the module installed. Reason: the registry can end up marked as installed while `python3` is broken in practice.
- **"Running"**: not applicable — it's a CLI tool with no persistent process of its own.

## 5. App screen (`PythonFragment.kt`)

"Status" card (version, pip) + 5 buttons:

| Button | Real action |
|---|---|
| View version and info | Runs `python3 --version`, `pip --version`, `command -v python3`, and tries `import` on 7 packages (numpy/scipy/pandas/requests/websockets/PIL/bs4) to build the real status |
| Open REPL (python3) | Opens the adapted terminal with the interpreter running |
| Install package (pip) | Free-text prompt → `python3 -m pip install --break-system-packages <package>` |
| Packages by category | Curated catalog of 8 categories (Algorithms/Math, Data, AI/ML, NLP/Text, Web/Scraping, Science, Tools, Cybersecurity) — the user picks a category → package → confirms, without needing to memorize PyPI names |
| List installed packages | `pip list --format=json` (with fallback to plain-text parsing if the JSON call fails) |
| Outdated packages | `pip list --outdated --format=json` — each row is tappable, offering to update that specific package |
| Run .py script | Searches for real `.py` files in `~/python`, `~` (depth 1), and Downloads storage (depth 2), skipping `node_modules`/`.git`/`venv`/`.venv`/`.gradle`/`build`/`__pycache__` — shows them in a numbered list; if nothing is found, falls back to asking for a manual path |
| Virtual environment (venv) | Per-project venv management (pick a folder from `~/proyectos`) — see section 5b |
| Update Python | Reinstalls/updates via `modulos/python.sh` |

All of this action logic lives in Kotlin (`PythonFragment.kt`, using `ProcessBuilder`) — none of it goes through an intermediate Python script.

## 5b. Per-project virtual environments (venv)

Lets you isolate a project's dependencies without touching the rest of the system, with detection/installation of an existing `requirements.txt`.

- **Choose project**: lists folders from `~/proyectos` (the same shared folder used by the rest of the modules for "Manage projects") — if there are none, it warns and offers nothing further.
- **Create venv** → `python3 -m venv --system-site-packages <project>/.venv`. The `--system-site-packages` flag is deliberate: in Termux, the heavy packages (numpy/scipy/pandas) come precompiled via `pkg install`, not as ARM64 wheels on PyPI — a fully isolated venv wouldn't be able to install them via pip, so the venv inherits those packages from the system and only isolates whatever the project installs with pip.
- **Install requirements.txt**: if the project has a `requirements.txt`, installs it with `<project>/.venv/bin/pip install -r requirements.txt` (if a venv exists) or with global pip `--break-system-packages` (if no venv was created).
- **Delete venv**: recursively removes `<project>/.venv`.
- The action menu is contextual: "Create venv" only shows if it doesn't exist yet, "Install requirements.txt" only shows if the file exists, "Delete venv" only shows if one already exists.
- **Non-obvious detail**: the path resolver normally prepends `$PREFIX/bin/` to the first element of the command (e.g. `python3` → `$PREFIX/bin/python3`) — but if the element is already an absolute path (e.g. `<project>/.venv/bin/pip`), it's left as-is, so the venv install invokes the correct path.

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

## 7. Architecture notes

- Installation verification always confirms that the actual binary responds (not just that the checkpoint says "done") — both in the script (a real `command -v python3` before trusting the checkpoint) and in the UI (same criterion in `isModuleInstalled()`).
- All runtime logic (info/pip-install/pip-list/find-scripts/run-script) runs directly in Kotlin, without going through any intermediate Python subprocess.
