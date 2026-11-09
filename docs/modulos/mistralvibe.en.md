# Mistral Vibe — module sheet (`mistralvibe`)

> Sheet based on reading the code. **Confirmed** = read in the cited code (file:line); **Unverified** = inference not checked on a device.

## 1. What it is and what it is for
Mistral AI's official terminal coding agent (PyPI package `mistral-vibe`, command `vibe`). The module installs the package via pip and leaves model/provider configuration to `~/.vibe/config.toml` or `MISTRAL_API_KEY` (`mistralvibe.sh:18-20`).

## 2. Classification
**ADD-ON / EXTENSION (with one real native function: configuring a local AI provider)**

Rationale (confirmed): no Fragment of its own, no service or state of its own; everything opens a terminal. The only native operation: writing the local provider block into `~/.vibe/config.toml` (`LocalCliProviderNative.configureMistralVibe`, `LocalCliProviderNative.kt:70-100`).

UI options (approx.): **~9** (5 common + Send prompt + Continue session + Use Ollama + Use llama-server (no login button: no confirmed shell subcommand)).

## 3. Installation (`modulos/mistralvibe.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`mistralvibe.sh:61-69`). Script version 1.2.1.
- Steps: STEP 1 checks/installs `python` (`:196-213`); STEP 1.5 installs the build toolchain `rust clang make libffi openssl pkg-config` (`:225-238`) because the transitive dependency `rpds-py` is compiled from source; STEP 2 `pip_install "$PIP_PYTHON" mistral-vibe` with `ANDROID_API_LEVEL=24`, `GYP_DEFINES`, `CARGO_BUILD_JOBS=1` (`:245-271`).
- Verification: `verify_binary_installed vibe` (`:273`) + `_vibe_runtime_check` (`vibe --version` with `timeout 15`, looking for Traceback/ImportError, `:150-158`, `:275-284`); on failure it repairs `charset-normalizer` (clean reinstall and then pin `3.3.2`, `:164-169`, `:131-134`).
- Registry: `installed, version, channel=pip, install_date` (`:293-297`). `pip_install` serializes with flock against other pip modules (`lib.sh:219`).
- No hashes (PyPI package without `--require-hashes`, no version pin for `mistral-vibe`).
- `modules.json`: `installMethods: ["pip"]`, `requires: ["python"]`, size ~40MB.

## 4. Execution
- Command: `vibe` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:223-229`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:56`).
All UI actions end up in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. That is: the app does **not** run the CLI through `ProcessBuilder` nor capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false (default): `unset LD_PRELOAD` is not prepended.

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && vibe`) and **Manage projects** (`showProjectsMenu`, runs `cd '<project>' && vibe`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:223-229`):
- **Send prompt**: `vibe --prompt '<prompt>'` (`:226`); no model selector.
- **Continue last session**: `vibe --continue` (with a folder dialog, `:228`).
- **Use local Ollama / llama-server**: generates a marker-delimited block (`# --- kairos-local-provider ... ---`) with `active_model`, `[[providers]]` (`backend=generic`, `api_style=openai`) and `[[models]]` at the top of `~/.vibe/config.toml`, replacing only the previous block and keeping the rest of the file (`LocalCliProviderNative.kt:61-100`).
- There is no login button (`hasAuth=false`, comment `CliToolFragment.kt:217-219`).

## 6. Files and paths it creates
- pip package `mistral-vibe` + dependencies in Termux's Python site-packages (shared with other Python modules).
- `vibe` binary/wrapper in `$PREFIX/bin` (created by pip).
- `~/.vibe/config.toml` (created by the UI if a local provider is configured; `vibe` itself stores its config there).
- Rust/clang toolchain installed as a side effect (several hundred MB, size unverified).
- `~/.install_mistralvibe_checkpoint` (deleted at the end). Registry: `mistralvibe.*`.

## 7. Security
- `pip install mistral-vibe` with no version pin or hash verification: PyPI is trusted.
- Prompt escaped with `shellEscape` before launching `vibe --prompt`.
- `api_key_env_var = ""` in the local provider: no key is written (`LocalCliProviderNative.kt:70-100`). No secrets stored by the app.
- No `curl | bash`.

## 8. Problems and gaps found
- Confirmed: `deepUninstallPlan` only does `pip uninstall -y mistral-vibe` (`ModuleController.kt:896-900`): it does not remove `~/.vibe`, the transitive dependencies, or the rust/clang toolchain installed by STEP 1.5.
- Confirmed: there is no login from the UI; the user must edit `config.toml` or export `MISTRAL_API_KEY` on their own (no UI for it).
- Confirmed: the catalog size (~40MB) does not include the Rust toolchain that the script always installs (`:225-238`), even though pip might not need it (the script does not try a prebuilt wheel before installing rust; unverified).
- Confirmed: `charset-normalizer` is pinned to `3.3.2` only in the repair path; the normal installation does not pin it.
- Unverified: `CARGO_BUILD_JOBS=1` avoids the cargo/maturin race (comment `:251-260`) but makes the installation noticeably longer.

## 9. Added
2026-08-12.
