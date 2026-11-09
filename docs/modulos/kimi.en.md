# Kimi Code (Moonshot AI) — module sheet (`kimi`)

> Sheet based on reading the code. **Confirmed** = read in the cited code (file:line); **Unverified** = inference not checked on a device.

## 1. What it is and what it is for
Official coding CLI from Moonshot AI (Kimi), npm package `@moonshot-ai/kimi-code`, command `kimi`; requires Node.js 22.19+ (`kimi.sh:22-25`). The module only installs the CLI.

## 2. Classification
**ADD-ON / EXTENSION**

Rationale (confirmed): no service, no state of its own, no native function; all actions are terminal launchers.

UI options (approx.): **~8** (5 common + Login + Send prompt (with optional model) + MCP servers).

## 3. Installation (`modulos/kimi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`kimi.sh:45-53`). Version 1.0.0.
- STEP 1 Node: accepts the existing Node only if `node_major >= 22`; otherwise installs `nodejs-lts` and only warns (`warn`) if it is still <22 (`:141-158`). STEP 2 `npm install -g @moonshot-ai/kimi-code@latest` + `fix_npm_shebang_wrapper kimi` + `verify_binary_installed kimi` (`:160-175`). STEP 3 API key skipped (`:177-187`).
- Registry: `installed, version, channel=npm, install_date` (`:193-197`).
- `--describe-files`: wrapper on PATH + `$(npm root -g)/@moonshot-ai/kimi-code/**`; its `verify_cmd` is just `command -v kimi` (`:74`, weaker than the rest).
- `modules.json`: `installMethods: ["npm"]`, `requires: ["nodejs"]`.

## 4. Execution
- Command: `kimi` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:257-266`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:58`).
All UI actions end up in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. That is: the app does **not** run the CLI through `ProcessBuilder` nor capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false.

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && kimi`) and **Manage projects** (`showProjectsMenu`, runs `cd '<project>' && kimi`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:257-266`):
- **Log in**: `kimi login` (device-code flow, `:260`).
- **Send prompt**: `kimi -p '<prompt>'`; if a model is given (free field in a 2nd dialog) `kimi -m '<model>' -p '<prompt>'` (`:262-264`, `askModelThenRun` `:779-795`).
- **MCP servers**: `kimi mcp list` (read-only, `:265`).
- No `continueSessionCommand` and no local mode.

## 6. Files and paths it creates
- Global npm package `@moonshot-ai/kimi-code` and bash wrapper `$PREFIX/bin/kimi`.
- CLI config/credentials in its own directory (not created by the script; location not verified).
- `~/.install_kimi_checkpoint` (deleted at the end). Registry: `kimi.*`.

## 7. Security
- npm `@latest` without a hash. Login is a device-code flow handled by the CLI itself; the app does not store tokens.
- Prompt/model escaped with `shellEscape`.
- No `curl | bash`.

## 8. Problems and gaps found
- Confirmed: there is no plan in `deepUninstallPlan` for `kimi` (it does not appear in `ModuleController.kt:837+`), so deep uninstall only resets state and warns "real uninstall not supported" (`ModuleController.kt:1067-1068`); the npm package and the wrapper stay installed.
- Confirmed: if Node stays <22 only a `warn` is emitted (`:154`) and the installation continues; it can end with a CLI that does not start.
- Confirmed: the model is requested as free text (format not validated).
- Confirmed: `--describe-files` verifies with `command -v kimi` instead of `kimi --version`, inconsistent with `verify_binary_installed`.
- Unverified: there are no buttons for `kimi mcp add/remove/auth` (left to the terminal on purpose, comment in `CliToolFragment.kt:254-256`).

## 9. Added
2026-08-14.
