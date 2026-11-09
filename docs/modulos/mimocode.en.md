# MiMo Code (Xiaomi) — module sheet (`mimocode`)

> Sheet based on reading the code. **Confirmed** = read in the cited code (file:line); **Unverified** = inference not checked on a device.

## 1. What it is and what it is for
Xiaomi's coding agent (`mimo` CLI) with persistent memory between sessions. In `modules.json` it appears as `experimental: true`, category `ai`, estimated size ~60MB. The module only installs the CLI and offers terminal launchers; authentication (anonymous MiMo Auto, Xiaomi OAuth, OpenAI-compatible API) is guided by `mimo`'s own TUI on first launch (`mimocode.sh:49-53`).

## 2. Classification
**ADD-ON / EXTENSION (with one real native function: configuring a local AI provider)**

Rationale (confirmed): it has no Fragment of its own (it uses the generic `CliToolFragment`), no service, and no state of its own; all actions open a terminal with a command. The only operation the app runs by itself is **Use local Ollama / llama-server**, which writes `~/.config/mimocode/mimocode.jsonc` from Kotlin (`LocalCliProviderNative.configureMimoCode`, `LocalCliProviderNative.kt:41-58`).

UI options (approx.): **~11** (5 common + Login + Send prompt + Continue session + Sessions + Use Ollama + Use llama-server).

## 3. Installation (`modulos/mimocode.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`mimocode.sh:70-83`). No variants (`--describe` returns `variants: []`, `:88`).
- Script version: 3.0.0 (`:60`). Checkpoint `~/.install_mimocode_checkpoint`; registry `~/.android_server_registry`.
- Actual method order (`:301-358`): (1) **original method** glibc: installs `glibc-repo glibc patchelf-glibc curl` if missing (`:313-326`), downloads `*linux-arm64.tar.gz` from `XiaomiMiMo/MiMo-Code` (GitHub API, `:216-226`), extracts it into `~/.local/share/mimocode`, applies `patchelf --set-interpreter` and creates the wrapper `$PREFIX/bin/mimo` with `unset LD_PRELOAD` (`:248-266`); (2) **fallback**: aarch64 `.deb` from `Honkonx/MiMoCode-Termux` or `Hope2333/MiMoCode-Termux` extracted with `dpkg-deb -x` and copied with `cp -a` into `$PREFIX` (`:151-206`) — pure Bionic binary, no glibc.
- Verification: working `mimo --version` after each method (`:201`, `:269`). Registry: `installed, version, channel (native_arm64 | fork_native_bionic), install_date` (`:366-370`).
- Downloads **without sha256** (direct `curl -fsSL`, `:233`, `:172`; `verify_sha256` is not called).
- Dependencies declared in `modules.json`: `requires: ["nodejs"]`, `installMethods: ["npm"]`, `arch: "bionic"` — see gaps.

## 4. Execution
- Command: `mimo` (`terminalCommand` in `modules.json`; `baseCommand` in `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:205-215`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, routed from `ModuleDetailNavigator.kt:55`).
All UI actions end up in `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = module name)`. That is: the app does **not** run the CLI through `ProcessBuilder` nor capture its output; it opens a real terminal session and types the command. There is no background service, no port (`port: ""`), and no on/off switch (`hasSwitch: false`) in `modules.json`. The "installed" state is detected by a binary on PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> the UI prepends `unset LD_PRELOAD; ` to every command (`CliToolFragment.kt:452`).

## 5. UI functions and options
Controls common to all `CliToolFragment` CLIs (`CliToolFragment.kt:466-620`):
- STATUS card (`:472-476`): id, version read from the registry (`<id>.version`, `:622-628`) and base command. Read-only.
- USE card (`:478-497`): **Open in terminal** (`promptOpenLocation` -> default command or `cd '<folder>' && mimo`) and **Manage projects** (`showProjectsMenu`, runs `cd '<project>' && mimo`).
- MAINTENANCE card (`:604-619`): **Update** (`updateModuleService`), **Diagnose** (`runModuleDoctorForThis`) and **Uninstall** (dialog with a "deep uninstall" checkbox, `confirmUninstall` `:863-897`).

Controls specific to this module (`CliToolFragment.kt:205-215`):
- **Log in**: `mimo auth login` (`:208`).
- **Send prompt**: `mimo run '<prompt>'` (`:210`, text escaped with `shellEscape`, `:642`); no model selector.
- **Continue last session**: `mimo --continue` (with a folder dialog, `:213`, `:516-531`).
- **Sessions** (MORE OPTIONS card): `mimo session list` (`:214`).
- **Use local Ollama / Use local llama-server** (LOCAL AI PROVIDER card, `:597-602`): lists real models (`OllamaApiClient.listModels()` / `LocalModelManager.listModels`) and writes the `custom` provider (`@ai-sdk/openai-compatible`, baseURL `http://127.0.0.1:11434/v1` or `:8085/v1`, apiKey `local`) into `mimocode.jsonc` (`:848-861`, `LocalCliProviderNative.kt:41-58`).

## 6. Files and paths it creates
- `$PREFIX/bin/mimo` (bash wrapper or, in the fallback method, the `.deb`'s `usr/bin` one).
- glibc method: `~/.local/share/mimocode/<binary>` (`MIMO_DIR`, `:124`).
- Fallback method: the `.deb`'s full `usr/` tree, e.g. `$PREFIX/lib/mimocode/runtime/mimocode` (`:196`).
- Temporary files `~/tmp/mimocode_*` (deleted). `~/.install_mimocode_checkpoint` (deleted at the end).
- Registry: `mimocode.*` keys.
- Local provider config: `~/.config/mimocode/mimocode.jsonc` (created by the UI, not by the script).

## 7. Security
- No sha256 on any binary download (official tarball or the fork's .deb); the .deb is dumped with `cp -a` onto `$PREFIX` (it can overwrite any `usr/` file of the package).
- User prompt: single quotes escaped (`shellEscape`) before interpolating it into the command; there is no injection through `'`, but the prompt reaches the terminal as-is.
- The local provider's `apiKey` is fixed to the literal `local` (not a secret). No real secrets are stored from the UI.
- No `curl | bash`.

## 8. Problems and gaps found
- Confirmed: the script header (`mimocode.sh:13-26`, `:60-63`) claims that the Bionic fork is the preferred METHOD 1, but the real flow runs the glibc method first and the fork only as a fallback (`:301-358`, and the comment at `:302-307` confirms it). Outdated header.
- Confirmed: `modules.json` says `installMethods: ["npm"]`, `requires: ["nodejs"]`, `arch: "bionic"`; the script no longer uses npm (`:34-47`) and the main method is glibc+patchelf. The catalog does not reflect the real installation (affects the dependencies shown).
- Confirmed: `ModuleController.deepUninstallPlan` for `mimocode` runs `npm uninstall -g @mimo-ai/cli` (`ModuleController.kt:890`), which removes nothing of what the current script installs (wrapper, `~/.local/share/mimocode`, the .deb tree). Deep uninstall leaves the binary behind.
- Confirmed: `configureMimoCode` does a full `configFile.writeText(...)` (`LocalCliProviderNative.kt:41-58`), overwriting any previous user `mimocode.jsonc` (unlike `configureMistralVibe`, which preserves the rest of the file).
- Confirmed: `--describe-files` only covers the glibc layout (`$HOME/.local/share/mimocode/**`, `:99`); if installed via the fork, the payload is in `$PREFIX/lib/mimocode` and `moduledeb pack` would be incomplete (direct inference from the code, not tested).
- Unverified: `needsGlibcCompat=true` also applies to the fork installation (Bionic), where `unset LD_PRELOAD` is unnecessary but probably harmless.
- Unverified: the MCP/Engram button does not exist on purpose (syntax not confirmed, comment in `CliToolFragment.kt:201-204`).

## 9. Added
2026-08-12.
