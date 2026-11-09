# Cursor CLI (cursor-agent) — module sheet (`cursor`)

> Cited facts come from the code (file:line); "inference" = deduced without verifying on a device. Nothing was tested on a device.
> Shared family doc: `docs/modulos/cli-tools.md` (may be out of date relative to `CliToolFragment.kt`; this sheet takes precedence where they differ).

## 1. What it is and what it is for
Cursor's coding agent in the terminal. The binary actually installed is `cursor-agent` (not `cursor`), distributed by the official `cursor.com/install` installer with an embedded glibc Node.js (`cursor.sh:14-31`).

## 2. Classification
**ADD-ON / EXTENSION**

Rationale: no service, no state of its own, no native function; actions = terminal launchers.

UI options (approx.): **~9** (5 common + Login + Send prompt (with optional model) + Continue session + Account status).

## 3. Installation (`modulos/cursor.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`cursor.sh:51-59`). Version 1.0.0.
- STEP 1 (`:155-193`): installs `glibc-repo` (+`pkg update`), `glibc-runner`, `patchelf-glibc`, `curl`, `ca-certificates` if missing. STEP 2 (`:195-250`): runs **`curl https://cursor.com/install -fsSL | bash`** (`:201`), then fixes the `#!/usr/bin/env bash` shebang of the real script to `$PREFIX/bin/bash` with `sed -i` (`:227-230`) and applies `patchelf --set-interpreter` to the embedded `node` in `~/.local/share/cursor-agent/` (`:231-241`). `verify_binary_installed cursor-agent` (`:246`).
- Registry: `installed, version, channel=official_installer, glibc=true, install_date` (`:268-273`).
- No sha256 (the remote installer is not verified). `modules.json`: `arch: "glibc"`, `installMethods: ["glibc"]`, `requires: ["glibc"]`, ~150MB.
- `--describe-files` defines a `patch_cmd` with patchelf to re-apply after unpacking (`:74-110`).

## 4. Execution
- Command: `cursor-agent` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:290-301`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:60`).
All UI actions end in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. In other words: the app does **not** run the CLI through `ProcessBuilder` or capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> `unset LD_PRELOAD; ` prepended (`CliToolFragment.kt:298`).

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && cursor-agent`) and **Manage projects** (`showProjectsMenu`, launches `cd '<project>' && cursor-agent`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:290-301`):
- **Sign in**: `cursor-agent login` (`:293`).
- **Send prompt**: `cursor-agent -p '<prompt>'`; with a model `... --model '<model>'` (`:295-297`).
- **Continue last session**: `cursor-agent --continue` (with a folder dialog, `:299`).
- **Account status**: `cursor-agent status` (read-only, `:300`).

## 6. Files and paths it creates
- `~/.local/bin/cursor-agent` (symlink to a bash script) and `~/.local/share/cursor-agent/` (script, patched embedded `node`, `index.js`).
- `~/.cursor_install` (variable `CURSOR_WORKDIR` declared, `:114`; no use was observed in the script).
- `~/.install_cursor_checkpoint` (deleted). Registry: `cursor.*`.
- Cursor credentials live in the CLI's own directory (not created by the script).

## 7. Security
- **Unverified `curl | bash`** (`:201`): remote code from the `cursor.com` domain runs with the app's permissions.
- `sed -i` on the installed script and `patchelf` on the embedded `node`: they modify downloaded binaries.
- Prompt/model escaped with `shellEscape`. The app does not store Cursor tokens.

## 8. Known limitations
- Installation through `curl | bash` with no sha256 or version pin (`:201`).
- There is no `deepUninstallPlan` entry for `cursor`; deep uninstall only resets state and leaves `~/.local/share/cursor-agent` and the symlink.
- `get_installed_ver` only looks at `~/.local/bin/cursor-agent` (`:121-128`); if the official installer changes path the module would appear "not installed".
- The model is requested as free text without validation.
- Inference: each update of the official installer may reintroduce a different ELF/shebang and break the patch (the current fix depends on the structure observed on 2026-08-24, comment `:206-221`).

## 9. Date added
2026-08-14 (`modulos/cursor.sh`).
