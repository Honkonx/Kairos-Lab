# GitHub Copilot CLI — Kairos Module

> Sources: `modulos/copilotcli.sh` (256 lines), `app/src/main/assets/modules.json`,
> `CliToolFragment.kt` (`"copilotcli"` config, lines 169-182), `ModuleController.kt:918`.
> "Inference" = deduced without testing on a device.

## 1) What it is and what it is for
GitHub Copilot agent CLI (`copilot`, npm package `@github/copilot`). In Kairos it is installed through npm and, since the official platform package does not resolve on Android, the script patches it by hand (see §3).

## 2) Classification: **ADD-ON/EXTENSION**
No Fragment of its own: `ModuleDetailNavigator.kt:53` → parameterized `CliToolFragment`. No state of its own, no service. It installs the tool, opens a terminal and offers login, a direct prompt and an informational command. **~8 UI controls**.

## 3) Installation (`modulos/copilotcli.sh`)
- Flags (`:43-46`): `--silent`, `--force`, `--describe`, `--describe-files`. No variants.
- Dependencies: `nodejs-lts` (STEP 1, `:135`); for the platform binary: `glibc`, `glibc-repo`, `patchelf-glibc` (installed only if needed, `:210-217`).
- STEP 2: `npm install -g @github/copilot@latest` (`COPILOT_PKG` `:93`) → `fix_npm_shebang_wrapper copilot @github/copilot`. If `copilot --version` fails (`:190`): it manually installs `@github/copilot-linux-arm64@<version>` with `--force`, **copies** the package to `@github/copilot-android-arm64` and rewrites `name` in its `package.json` (because the loader resolves `@github/copilot-${process.platform}-${process.arch}` and Android reports `android`), and applies `patchelf --set-interpreter` to the inner `copilot` against Termux's glibc loader (`:197-222`). If it still does not run → `error`.
- **No sha256/checksum**; integrity depends on the npm registry.
- Registry: `copilotcli.installed/version/channel=npm/install_date` (`:247-251`). "Already installed" (`get_installed_ver` non-empty and no `--force`) → `exit 0` without touching the registry.
- Login is skipped in `--silent`: the script warns that you must run `copilot` and use `/login` or export `GH_TOKEN`/`GITHUB_TOKEN` (`:236`).

## 4) Execution
`terminalCommand:"copilot"`; `needsGlibcCompat=true` → the UI prepends `unset LD_PRELOAD; ` (`CliToolFragment.kt:launchCliTerminalCommand`). No background process and no port. It always launches in the Kairos terminal.

## 5) UI controls
Status (id/registry version/command) · Open in terminal (where to open?) · Manage projects (with launch-in-project) · **Sign in** (`copilot login`, terminal) · **Send prompt** (`copilot -p '<prompt>'`, escaped) · **Plugins/MCP** (`copilot plugins list`) · Update · Diagnose · Uninstall (`npm uninstall -g @github/copilot`, `ModuleController.kt:918`).

## 6) Files and paths
Global npm package `@github/copilot` and `@github/copilot-android-arm64` (copy created by the script) under `$(npm root -g)/@github/`, wrapper `$PREFIX/bin/copilot`, checkpoint `~/.install_copilotcli_checkpoint`. Copilot credentials are stored by the CLI itself (inference: where was not verified).

## 7) Security
- The prompt is escaped with `shellEscape` before being interpolated into `copilot -p '{PROMPT}'`.
- `patchelf` and package copies under `node_modules` run with the user's permissions; no hash.
- Token: the script only suggests `GH_TOKEN`/`GITHUB_TOKEN`; Kairos does not store Copilot secrets (no code reads them).
- A non-interactive prompt (`-p`) using tools may require agent permissions; the UI exposes no permission flags (inference).

## 8) Known limitations
- **Contradiction pending verification**: the script says login is done with `/login` inside `copilot` (`copilotcli.sh:236`), but the UI runs the `copilot login` subcommand (`authCommand` config). One of the two may not be valid in the real version; without a device it has not been verified which.
- `modules.json` declares `arch:"bionic"`, `installMethods:["npm"]`, `requires:["nodejs"]` but the real binary is patched glibc → incomplete metadata.
- No checksum; unpinned `@latest`.
- Inference: the `copilot-android-arm64` patch depends on the internal format of the minified `@github/copilot` loader; an update may break it, and the "Update" button does not re-apply the patch if `copilot --version` already responds.
- No resume-session or model selector in the UI (the config does not define them; whether the CLI offers them was not verified).

## 9) Date added
`modulos/copilotcli.sh`: **2026-08-12**.
