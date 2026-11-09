# Pi Coding Agent — module sheet (`pi`)

> Anything marked "Verified in code" was read in the cited code (file:line); "Inference" = not verified on a device. Nothing was tested on a device.
> Shared document of the family: `docs/modulos/cli-tools.md`. Where they differ, `CliToolFragment.kt` takes precedence.

## 1. What it is and what it is for
Terminal coding agent (`earendil-works/pi`, npm package `@earendil-works/pi-coding-agent`, command `pi`). According to the KDoc in `CliToolFragment.kt:327-340` it has no MCP or subagents; login is `/login` inside the session.

## 2. Classification
**ADD-ON / EXTENSION**

Rationale (verified in code): no service, no state of its own, no native function; actions = terminal launchers. It is the declared dependency of `ohmypi`.

UI options (approx.): **~11** (5 common + Login (hint) + Send prompt (with optional model) + Continue session + Install extension + Installed extensions + Pick session).

## 3. Installation (`modulos/pi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`pi.sh:53-61`). Version 1.0.0.
- STEP 1 (`:153-180`): Node/npm (`nodejs-lts` if missing) + `ripgrep git fd` if missing. STEP 2 (`:182-200`): `npm install -g @earendil-works/pi-coding-agent@latest --ignore-scripts` + `fix_npm_shebang_wrapper pi` + `verify_binary_installed pi`.
- Registry: `installed, version, channel=npm, install_date` (`:206-210`). No hash or version pin.
- `--describe-files` resolves the binary with `command -v pi` (to support an `npm` prefix redirected to `~/.npm-global`, `:78-85`) and packages `$(npm root -g)/@earendil-works/**`.
- `modules.json`: `installMethods: ["npm"]`, `requires: []` (does not declare nodejs although the script installs it), `catalogVersion: "1.0.0"`.

## 4. Execution
- Command: `pi` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:341-356`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:62`).
All UI actions end in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. In other words: the app does **not** run the CLI through `ProcessBuilder` or capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by the binary being in PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` (`CliToolFragment.kt:350`): prepends `unset LD_PRELOAD; ` even though the module is installed through npm. The field's KDoc (`:77-79`) reserves that flag for glibc/Bun and says npm shims need `LD_PRELOAD`; here the wrapper has an absolute shebang, so it is probably harmless (inference).

## 5. UI functions and options
Controls common to all CLIs in `CliToolFragment` (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && pi`) and **Manage projects** (`showProjectsMenu`, launches `cd '<project>' && pi`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:341-356`):
- **Log in**: no `authCommand`; toast `clitool_cfg_authhint_pi` (`/login` inside the session) and opens `pi` (`:344`).
- **Send prompt**: `pi -p '<prompt>'`; with a model `pi --model '<model>' -p '<prompt>'` (`:346-348`).
- **Continue last session**: `pi -c` (with a folder dialog, `:349`).
- **Install extension** (EXTENSIONS card): free-text dialog (path/npm/git) -> `pi install '<source>' -l` (`:351`).
- **Installed extensions**: `pi list` (`:353`). **Pick session**: `pi -r` (`:354`).

## 6. Files and paths it creates
- Global npm package `@earendil-works/pi-coding-agent` and bash wrapper `pi` (in `$PREFIX/bin`, or `~/.npm-global/bin` if the npm prefix is redirected).
- `~/.pi` (config/state, created by the binary itself, not the installer; comment `pi.sh:26-27`).
- `~/.install_pi_checkpoint` (deleted). Registry: `pi.*`.

## 7. Security
- **Install extension** runs `pi install <source> -l` with a free-form source (path/npm/git): it installs and runs arbitrary third-party code chosen by the user; quotes are escaped, but the UI shows no warning.
- `npm install -g ... --ignore-scripts` reduces the risk of postinstall scripts (`:188`).
- The app does not store Pi credentials.

## 8. Issues and limitations found
- There is no plan in `deepUninstallPlan` for `pi`; deep uninstall only resets state and leaves the npm package and `~/.pi`.
- The script's `--describe` and the `modules.json` description say "no confirmed own flags/login" (`pi.sh:66`, `modules.json`), while `CliToolFragment.kt` already exposes `-p`, `-c`, `--model`, `-r` and `install/list`: outdated text.
- `modules.json` has `requires: []` but the script installs Node, ripgrep, git, fd; the dependency is not shown in the catalog.
- The comment at `pi.sh:12-15` says npm publishes the command "without a wrapper", contradicted by the script's own `fix_npm_shebang_wrapper` at `:194` (corrected in the `--describe-files` block, not in the header).
- Inference: `needsGlibcCompat=true` is inconsistent with the documented criterion.

## 9. Added on
2026-08-18 (first commit adding `modulos/pi.sh`).
