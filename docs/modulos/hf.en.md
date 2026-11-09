# Hugging Face CLI — module sheet (`hf`)

> Cited facts come from the code (file:line); "inference" = deduced without verifying on a device. Nothing was tested on a device.
> Shared family doc: `docs/modulos/cli-tools.md` (may be out of date relative to `CliToolFragment.kt`; this sheet takes precedence where they differ).

## 1. What it is and what it is for
Official Hugging Face CLI (`hf`) for managing models, datasets and spaces: `hf download` (with resume), `hf upload`, `hf auth login`, local cache (`hf.sh:3-28`). It is NOT a conversational agent. The script presents it as a complement to the llama.cpp ecosystem (.gguf files are downloaded from HF repos).

## 2. Classification
**ADD-ON / EXTENSION**

Rationale: no service or state of its own; its buttons are terminal launchers. It has relatively more options than other CLIs, but none is integrated with the app's model manager (`LocalModelManager`).

UI options (approx.): **~11** (5 common + Login + Search model/dataset + Download by ID + Active session + View cache + Clear cache).

## 3. Installation (`modulos/hf.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`hf.sh:83-91`). Version 1.2.0.
- STEP 1 `curl` and `python3` (`:266-291`). STEP 1.5 tries a **prebuilt wheel** of `hf_xet` (`aarch64-android`) from Releases of `Honkonx/kairos-lab`, installing it into the venv `~/.hf-cli/venv` (or `$HF_HOME/cli/venv`) that the official installer reuses (`:156-239`, `:293-309`). STEP 1.6 installs `rust clang make libffi openssl pkg-config` only if there was no wheel (`:311-332`). Exports `ANDROID_API_LEVEL=24`, `CARGO_BUILD_JOBS=1` (`:344-345`).
- STEP 2 (`:347-362`): **`curl -LsSf https://hf.co/cli/install.sh | bash`** (`:353`), `PATH` with `~/.local/bin`, `verify_binary_installed hf`.
- Registry: `installed, version, channel=official_installer, install_date` (`:380-384`).
- Integrity verification: `verify_sha256` of the wheel in "log only" mode because `HF_XET_PREBUILT_SHA256` is empty (`:220`, `lib.sh:592-610`); the official installer is not verified.
- `modules.json`: `installMethods: ["curl"]`, `requires: ["curl"]`, ~20MB (it does not reflect python or the possible Rust toolchain).

## 4. Execution
- Command: `hf` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:310-325`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:61`).
All UI actions end in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. In other words: the app does **not** run the CLI through `ProcessBuilder` or capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false. `Open in terminal` launches plain `hf` (inference: it prints the help; there is no interactive session of its own).

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && hf`) and **Manage projects** (`showProjectsMenu`, launches `cd '<project>' && hf`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:310-325`):
- **Sign in**: `hf auth login` (`:313`).
- **Search model/dataset** (reuses `symbolQueryTemplate`): text dialog -> `hf models ls --search '<term>'` (`:314-316`, `runSymbolQueryButton` `:669-688`). It only searches models despite the label.
- **Download by ID** (reuses `installExtensionTemplate`): dialog with repo_id -> `hf download '<id>'` (`:317-319`, `showInstallExtensionDialog` `:690-707`).
- **Active session**: `hf auth whoami` (`:321`). **View cache**: `hf cache ls` (`:322`). **Clear cache**: `hf cache prune` (`:323`), launched without a confirmation dialog of its own.

## 6. Files and paths it creates
- Binary `~/.local/bin/hf` and the CLI venv `~/.hf-cli/` (or `$HF_HOME/cli/`), created by the official installer.
- Model cache `~/.cache/huggingface/` and config/token `~/.config/huggingface/` (created by the CLI when used; deep uninstall removes them, `ModuleController.kt:998-1006`).
- Temporary wheel in `mktemp -d` (deleted).
- `~/.install_hf_checkpoint` (deleted). Registry: `hf.*`.

## 7. Security
- **Unverified `curl | bash` of the official installer** (`:353`).
- The prebuilt hf_xet wheel is downloaded from a repo of our own with no pinned hash (only the sha256 is logged, `:211-224`); it is installed with `--force-reinstall --no-deps` in the venv.
- The HF token (`hf auth login`) is stored by the CLI in `~/.config/huggingface` / `~/.cache/huggingface`; the app neither reads nor displays it. `hf auth whoami` only prints in the terminal.
- `hf cache prune` deletes user data without confirmation from the app (inference: the CLI may ask for it in the terminal).

## 8. Known limitations
- The header (`hf.sh:34-59`) and the STEP 1.5 comment indicate that a Release with the `hf_xet-*aarch64*android*.whl` wheel may not exist; whether it exists today was not verified. Until confirmed, it is inference that the fast path works.
- `HF_XET_PREBUILT_SHA256` has no default value: wheel verification never blocks.
- `Download by ID` runs `hf download` into the HF cache (`~/.cache/huggingface`); it does not move the GGUF to the directory used by `LocalModelManager`/llama-server, so it does not complete the "download GGUF for local chat" flow suggested by the header (the code does not integrate the two).
- The "Search model/dataset" label only runs `hf models ls --search` (models).
- `--describe-files` declares `version_registry_key: ""` and a `verify_cmd` with only `command -v hf` (`:114`, `:119`).
- The catalog size (~20MB) underestimates python/rust when they are needed.

## 9. Date added
2026-08-14 (`modulos/hf.sh`).
