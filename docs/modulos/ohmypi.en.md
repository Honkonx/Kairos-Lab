# Oh-My-Pi (omp) — module sheet (`ohmypi`)

> Sheet based on reading the code. **Confirmed** = read in the cited code (file:line); **Unverified** = inference not checked on a device.

## 1. What it is and what it is for
An improved/standalone version of Pi Coding Agent (`can1357/oh-my-pi`): a binary compiled with `bun build --compile` against glibc, with Rust addons (AST grep, diff, fuzzy find, shell exec), sessions and MCP; command `omp` (`ohmypi.sh:6-26`). It ALWAYS installs through the native glibc method.

## 2. Classification
**ADD-ON / EXTENSION**

Rationale (confirmed): no service, no state of its own, no native function; actions = terminal launchers. The closest thing to a function of its own is **Assisted commit**, which also just launches `omp commit` in a terminal.

UI options (approx.): **~9** (5 common + Send prompt (with optional model) + Continue session + Configure provider + Assisted commit (no login)).

## 3. Installation (`modulos/ohmypi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`ohmypi.sh:71-79`). Version 1.0.0.
- STEP 1 (`:170-205`): installs `glibc-repo` (+`pkg update`), `glibc` and `jq curl tar clang` if missing. STEP 2 (`:207-227`): queries `releases?per_page=5` from `can1357/oh-my-pi`, takes the first tag starting with `v` and downloads `omp-linux-arm64` to `~/.local/share/kairos-data/oh-my-pi/omp` (no sha256, `:218`); writes the version to `.../version`. STEP 3 (`:229-291`): compiles with `clang` a C helper that does `execv` of the glibc loader (`ld-linux-aarch64.so.1 --library-path ... omp`), with `unsetenv(LD_PRELOAD)` and embedded absolute paths, at `$PREFIX/bin/omp`; `verify_binary_installed omp`.
- Registry: `installed, version, channel=github_release, install_method=glibc_native, install_date` (`:297-302`).
- `modules.json`: `arch: "glibc"` (`:98-101`), `installMethods: ["github_release"]`, `requires: ["pi"]`, ~90MB.
- Declaring `requires: ["pi"]` is informational only: the script does NOT install or check `pi` (header `:15-19`).

## 4. Execution
- Command: `omp` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:408-419`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:64`).
All UI actions end up in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. That is: the app does **not** run the CLI through `ProcessBuilder` nor capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` (`CliToolFragment.kt:415`); the helper already clears `LD_PRELOAD` by itself.

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && omp`) and **Manage projects** (`showProjectsMenu`, runs `cd '<project>' && omp`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:408-419`):
- **Send prompt**: `omp -p '<prompt>'`; with a model `omp --model '<model>' -p '<prompt>'` (`:411-413`).
- **Continue last session**: `omp -c` (with a folder dialog, `:414`).
- **Configure provider** (MORE OPTIONS): `omp setup` (interactive terminal configuration, `:416`).
- **Assisted commit** (ACTION card): picks a project with `showProjectsMenu` and runs `cd '<project>' && omp commit` (`:417-418`).
- No login button (`hasAuth=false`: no confirmed subcommand of its own, comment `:392-401`).

## 6. Files and paths it creates
- `$PREFIX/bin/omp` (compiled ELF helper, not a script).
- `~/.local/share/kairos-data/oh-my-pi/omp` (binary) and `.../version`.
- `$PREFIX/tmp/omp_helper.c` (temporary, deleted).
- `~/.omp` (the binary's own config/state).
- `~/.install_ohmypi_checkpoint` (deleted). Registry: `ohmypi.*`.

## 7. Security
- Executable binary downloaded from GitHub Releases without sha256 or signature; it runs with the app's permissions.
- The helper fixes absolute paths and `SSL_CERT_FILE`/`TMPDIR`; it does not use `system()`: it passes `argv` to `execv` without a shell (no injection through arguments).
- Prompt/model/project path are interpolated into shell commands; prompt and model with `shellEscape`, the project path in single quotes (`:569-571`).
- No `curl | bash`.

## 8. Problems and gaps found
- Confirmed: `requires: ["pi"]` is not enforced at installation; Oh-My-Pi works or not by itself (the script does not use Pi), but the catalog suggests a dependency that the code does not require.
- Confirmed: there is no plan in `deepUninstallPlan` for `ohmypi`; deep uninstall only resets state and leaves the helper, binary and `~/.omp`.
- Confirmed: no sha256 for the binary (`:218`).
- Confirmed: the helper embeds literal HOME/PREFIX paths (acknowledged in `--describe-files`, `:129`): not relocatable.
- Confirmed: `get_installed_ver` returns the tag (`v...`) read from a file, not the output of `omp --version`.
- Unverified: the model selector is free text; `omp --smol/--slow/--plan` are left out on purpose (`:396-399`).

## 9. Added
2026-08-18.
