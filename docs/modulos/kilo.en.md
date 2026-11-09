# Kilo Code — module sheet (`kilo`)

> Sheet based on reading the code. **Confirmed** = read in the cited code (file:line); **Unverified** = inference not checked on a device.

## 1. What it is and what it is for
A coding CLI (fork of the OpenCode engine, `Kilo-Org/kilocode`) with its own provider ecosystem; command `kilo` (`kilo.sh:3-17`, `:39-43`). It is kept as a separate module from `opencode`.

## 2. Classification
**ADD-ON / EXTENSION**

Rationale (confirmed): no service, no state of its own, no native function; actions = terminal launchers.

UI options (approx.): **~9** (5 common + Login + Send prompt (with optional model) + Continue session + Usage and costs).

## 3. Installation (`modulos/kilo.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`kilo.sh:63-71`). Version 2.0.0 (native binary + glibc, no npm, `:20-33`).
- STEP 1 installs `glibc-repo glibc patchelf-glibc curl` if missing (`:251-273`). STEP 2 `_kilo_download_native` (`:151-224`): reads `releases?per_page=20` from `Kilo-Org/kilocode`, takes the first `*linux-arm64.tar.gz` asset (avoiding the JetBrains plugin releases, `:135-150`), extracts to `~/.local/share/kilo`, prefers the file named `kilo` (the tarball also ships `bwrap`, `kilo-sandbox-*`, `:174-188`), `patchelf --set-interpreter`, and a `$PREFIX/bin/kilo` wrapper with `unset LD_PRELOAD` and `TMPDIR=$PREFIX/tmp` (`:203-217`).
- Verification: working `kilo --version` (`:219`). Registry: `installed, version, channel=native_arm64, install_date` (`:302-306`).
- Download **without sha256** (`curl -fsSL`, `:170`).
- `modules.json`: `installMethods: ["npm"]`, `requires: ["nodejs"]`, `arch: "bionic"`.

## 4. Execution
- Command: `kilo` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:273-284`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:59`).
All UI actions end up in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. That is: the app does **not** run the CLI through `ProcessBuilder` nor capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> `unset LD_PRELOAD; ` is prepended (`CliToolFragment.kt:281`, `:452`); the wrapper itself already does `unset LD_PRELOAD`.

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && kilo`) and **Manage projects** (`showProjectsMenu`, runs `cd '<project>' && kilo`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:273-284`):
- **Log in**: `kilo auth login` (`:276`).
- **Send prompt**: `kilo run '<prompt>'`; with a model `kilo run '<prompt>' -m '<model>'` (provider/model format in free text, `:278-280`).
- **Continue last session**: `kilo --continue` (with a folder dialog, `:282`).
- **Usage and costs**: `kilo stats` (read-only, `:283`).

## 6. Files and paths it creates
- `~/.local/share/kilo/` (patched `kilo` binary, plus `bwrap`, `kilo-sandbox-seccomp`, .js scripts from the tarball).
- `$PREFIX/bin/kilo` (bash wrapper).
- `~/tmp/kilo_*` temporary (deleted). `~/.install_kilo_checkpoint` (deleted). Registry: `kilo.*`.
- `kilo` config/state in its own directory (not created by the script).

## 7. Security
- Downloads an executable binary without integrity verification; it runs with the app's permissions. The tarball's auxiliary binaries (`bwrap`, seccomp) remain unpatched and are not invoked by the wrapper.
- Prompt/model escaped with `shellEscape`.
- No `curl | bash`. Provider credentials are handled by the CLI itself (`kilo auth login`); the app does not store them.

## 8. Problems and gaps found
- Confirmed: `modules.json` still declares `installMethods: ["npm"]`, `requires: ["nodejs"]` and `arch: "bionic"`, but `kilo.sh` v2.0.0 does not use npm and the binary is patched glibc: the catalog is out of sync with the script.
- Confirmed: there is no plan in `deepUninstallPlan` for `kilo`; deep uninstall only resets state (`ModuleController.kt:1067-1068`) and leaves `~/.local/share/kilo` and the wrapper.
- Confirmed: the tarball's sha256 is missing.
- Confirmed: the model is entered as free text; it is neither validated nor listed (`kilo models` exists but requires parameters and was left out, comment in `CliToolFragment.kt:270-272`).
- Unverified: the dependency on `GET api.github.com` without a token (60 req/h per IP limit) may make the installation fail on shared networks.

## 9. Added
2026-08-14.
