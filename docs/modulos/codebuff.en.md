# Codebuff — Kairos Module

> Based on the real code (`modulos/codebuff.sh` v1.5.0, 553 lines; `CliToolFragment.kt`, config
> `"codebuff"`; `ModuleController.kt:901`; `modules.json`). "Inference" = deduced without testing on a device.

## 1. What it is

Codebuff (CodebuffAI): a multi-model coding agent (provider via OpenRouter or another, configured in an internal TUI wizard; no automatable login/API key of its own). Command `codebuff`. The npm package is only a launcher; the real binary (ARM64 glibc) is downloaded on first launch.

**Classification: ADD-ON/EXTENSION** (with one extra function). It uses the shared `CliToolFragment` Fragment (`ModuleDetailNavigator.kt:52`). No state of its own or service; it opens a terminal and offers `--create` scaffolding. **~6 UI controls**. `modules.json`: `installMethods:["npm"]`, `requires:["nodejs"]`, `catalogVersion:"1.x"`.

## 2. Real installation (`modulos/codebuff.sh`) — a non-obvious 2-stage mechanism

The real npm package `codebuff` is NOT the app itself — it is a **thin launcher** (~35KB, with no native addon) that only downloads the real binary the **first time the `codebuff` command is run** (to `$HOME/.config/manicode/codebuff`, according to the package's `launcher.js`). The script triggers that first run on purpose (`timeout 90 codebuff --version`) to force the download, and only then applies `patchelf` (same glibc pattern as `freebuff.sh`).

- Flags: `--silent`, `--force`, `--describe`, `--describe-files`. No `--variant`.
- STEP 1: glibc (`glibc-repo`, `glibc`, `patchelf-glibc`, `curl`). STEP 2: `nodejs-lts`, `npm install -g codebuff@latest --force` (`CODEBUFF_PKG` `:167`) + `fix_npm_shebang_wrapper` (the `npm install -g` symlink has the shebang `#!/usr/bin/env node`, which does not exist in Termux). STEPS 3-5: run the launcher with `CODEBUFF_BINARY_TARGET=linux-arm64` (an official override the launcher exposes through the environment; needed because it computes the key `${process.platform}-${process.arch}` = `android-arm64`, which does not exist in its target map, `:73,108`) to force the download to `~/.config/manicode/codebuff`, and `patchelf --set-interpreter` (`:420-426`). STEP 5b: fallback using the `.deb` of the `Honkonx/codebuff-termux` fork (`_codebuff_download_native_fork` `:210`) with `dpkg-deb -x` + `cp -a` of the `usr/` tree (binary already pre-patched). STEP 7: model configuration is skipped in silent mode.
- Method order: the original flow (npm launcher + deferred download + patchelf, STEPS 2-5) is the primary one and the fork is the **fallback** in STEP 5b, only if no executable binary remained (`:440-461`). The script header (`:12-30,92-94`) describes the fork as "METHOD 1 (preferred)", but the body takes precedence and does the opposite.
- STEP 6 verification: `timeout 25 codebuff --version`; it fails (`error`) if "Unsupported platform|ENOENT" appears or if there is no executable native binary. `codebuff --version` never responds quickly — the launcher draws an animated ASCII banner before any output — so a parsed semver is not required (`:464-486`).
- Persistence: `export CODEBUFF_BINARY_TARGET=linux-arm64` only lives while the script runs, so it is persisted in `~/.bashrc` (`:523-532`, the same idempotent pattern `ssh.sh` uses for its aliases) so that a new terminal does not reproduce the "Unsupported platform" error.
- Registry: `codebuff.version/channel/arch/install_date`, `channel` ∈ `npm | npm_native_arm64 | fork_native_arm64`.
- **No sha256**; no version pin.

The real-binary detection mechanism (`$CODEBUFF_NATIVE_BIN = $HOME/.config/manicode/codebuff`) is a known fixed path (not a `find` over a directory with several files), which is more robust than Freebuff's in that respect (see `FREEBUFF.md`).

## 3. Execution

`terminalCommand:"codebuff"`; `needsGlibcCompat=true` → the UI prepends `unset LD_PRELOAD; ` (consistent with the other glibc+patchelf modules). No background process. For scaffolding: `codebuff --create '<template>' '<name>'`.

## 4. Screen controls (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["codebuff"]`)

| Control | What it does | Why |
|---|---|---|
| Status (id/version/command) | Read-only | — |
| "⌨ Open in terminal" (where to open?) | `unset LD_PRELOAD; codebuff` | No documented login/prompt/model — the model provider configuration (OpenRouter) is done by the user inside the session |
| "🗂 Manage projects" | `showProjectsMenu()` | Same shared mechanism |
| "＋ Create project from template" | `codebuff --create '<template>' '<name>'` | Dialog with 2 `EditText`s (template + name); generic mechanism `CliModuleConfig.createFromTemplateTemplate` + `showCreateFromTemplateDialog()` (see `FREEBUFF.md` §5) |
| "🔄 Update" / "🔍 Diagnose" / "🗑 Uninstall" | `updateModuleService()` / `confirmUninstall()` | Standard maintenance |

## 5. Files and paths

`$PREFIX/bin/codebuff` (npm symlink or fork wrapper), `~/.config/manicode/codebuff` (real binary), `$PREFIX/lib/codebuff/runtime/codebuff` (fork), global npm package `codebuff`, line in `~/.bashrc`, checkpoint `~/.install_codebuff_checkpoint`, download temporaries.

## 6. Security

- The launcher downloads from `codebuff.com/api/releases/download/...` → GitHub `CodebuffAI/codebuff-community` and runs without verifying a hash (comment `:51`); the fallback fork has no checksum.
- `cp -a` of a third-party `.deb` over `$PREFIX` (same caveat as Freebuff).
- Modifies `~/.bashrc` idempotently (filters and inserts a block). Provider/API key are handled by the CLI itself (Kairos does not touch them).

## 7. Known limitations

- Incomplete uninstall: `ModuleController.kt:901` only runs `npm uninstall -g codebuff`; it leaves `~/.config/manicode/codebuff`, `$PREFIX/lib/codebuff` and the `.bashrc` line.
- `modules.json` does not declare glibc even though the binary requires it.
- STEP 4 does not use `verify_binary_installed` (the launcher does not have a binary yet) — it is only verified in STEP 6.
- Inference: tolerance for "prints no version" may produce false positives if the binary starts the banner but fails afterward.
- If an error is reported when opening Codebuff in a terminal, it is best to reproduce it with ADB/`uiautomator` before assuming a cause.

## 8. Date added

`modulos/codebuff.sh`: **2026-08-12**.
