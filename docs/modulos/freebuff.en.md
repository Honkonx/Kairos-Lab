# Freebuff — Kairos Module

> Based on the real code (`modulos/freebuff.sh` v1.3.0, 627 lines; `CliToolFragment.kt`, config
> `"freebuff"`; `ModuleController.kt:900`; `modules.json`). "Inference" = deduced without testing on a device.

## 1. What it is

Freebuff (CodebuffAI) — a coding agent CLI, free, with no account or API key required (per `github.com/CodebuffAI/freebuff`). Command `freebuff`.

**Classification: ADD-ON/EXTENSION** (with one extra function). No screen of its own: `ModuleDetailNavigator.kt:51` → `CliToolFragment.newInstance(id, name)`, configured by `CLI_MODULE_CONFIGS["freebuff"]`. The CLI documents no login, direct prompt or model selector, so it only has "Open in terminal" + project management + a `--create` scaffolding dialog. No state of its own, no service. **~6 UI controls**.

## 2. Real installation (`modulos/freebuff.sh`)

There are **3 methods in cascade** on aarch64 (`freebuff.sh:11-33`; aarch64 is the only real architecture Termux supports):
1. **Native Bionic Bun** runtime (`Honkonx/bun-termux`, aarch64 `.deb`) + `bun install -g freebuff@latest --os=linux --backend=copyfile` + the launcher downloading the glibc binary with `FREEBUFF_BINARY_TARGET=linux-arm64` + patchelf (`_freebuff_install_via_native_bun` `:397`).
2. CodebuffAI's `freebuff-linux-arm64.tar.gz` binary + glibc + patchelf (`_freebuff_download_native` `:193`). The real binary inside the tarball is called "codecane" (an internal rebrand of the project, not "freebuff"); the script does NOT assume that name, it discovers it live (`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"`) and generates a wrapper in `$TERMUX_PREFIX/bin/freebuff` pointing to the detected binary. The releases repo is `CodebuffAI/freebuff`, all releases are prereleases (you must request `/releases` instead of `/releases/latest`; the tag is read from `api.github.com/repos/CodebuffAI/freebuff/releases?per_page=5`).
3. npm (`freebuff@latest`, `:146`) — the only path outside aarch64.

- Flags (`:90-93`): `--silent`, `--force`, `--describe`, `--describe-files`. No `--variant`.
- Dependencies: `dpkg-deb`, `curl`; for the glibc binary: `glibc-repo`, `glibc`, `patchelf-glibc`; npm path only: `nodejs-lts`.
- **No sha256/checksum on any path**. Version not pinned (`latest`).
- Verification: methods 1 and 2 run `freebuff --version` and discard the method if it does not respond (`:~468`, `:262`); npm uses `fix_npm_shebang_wrapper freebuff freebuff` + `verify_binary_installed` (`:603-604`). Registry: `freebuff.version/channel/arch/install_date` with `channel` ∈ `native_arm64_bun_bionic | native_arm64_glibc | npm` (`:613-623`).
- "Already installed" → `exit 0` before the registry (`:~489`).
- The wrapper of the native methods does `unset LD_PRELOAD` and `exec ~/.config/manicode/freebuff` (path with legacy "manicode" branding) or `$FREE_DIR/<bin>` — the same fix that `CliToolFragment.needsGlibcCompat=true` applies on the UI side (a double safeguard, redundant but harmless).
- `modules.json` declares `installMethods:["npm"]`, `requires:["nodejs"]`, `arch:"bionic"`: outdated metadata (the real preferred method is native Bun + glibc/patchelf and does not need Node).

## 3. Execution

`terminalCommand:"freebuff"`. `needsGlibcCompat=true` → the UI prepends `unset LD_PRELOAD; ` to every command (`CliToolFragment.kt:~461`). No background process.

## 4. Screen controls (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["freebuff"]`)

| Control | What it does | Why |
|---|---|---|
| Status card (id, registry version, command) | Read-only | — |
| "⌨ Open in terminal" (where to open?) | `unset LD_PRELOAD; freebuff` | No documented login/prompt/model — the CLI offers nothing more on the command line |
| "🗂 Manage projects" | `showProjectsMenu()` (with launch-in-project) | Same shared mechanism as the rest of `CliToolFragment` |
| "＋ Create project from template" | `freebuff --create '<template>' '<name>'` | Dialog with 2 `EditText`s (template + name), see section 5 |
| "🔄 Update" / "🔍 Diagnose" / "🗑 Uninstall" | `updateModuleService()` / `runModuleDoctorForThis` / `confirmUninstall()` | Standard maintenance |

## 5. `freebuff --create <template> <name>`

A flag documented in `codebuff-community` (the templates/community repo of the same project). It is implemented via `CliModuleConfig.createFromTemplateTemplate` in `CliToolFragment.kt` (a generic field reusable by any module with similar scaffolding, the same mechanism as `installExtensionTemplate`/`symbolQueryTemplate`), with a 2-`EditText` dialog (`showCreateFromTemplateDialog()`) that builds `freebuff --create '<template>' '<name>'` with the same single-quote escaping as the rest of the Fragment (`shellEscape()`).

## 6. Files and paths

`$PREFIX/bin/freebuff` (wrapper), `~/.config/manicode/freebuff` (glibc binary downloaded by the launcher), `$PREFIX/bin/bun` + `$PREFIX/lib/bun-termux/` (Bun runtime), `~/.bun/` (global install), `$FREE_DIR` (method 2), `~/tmp/bun_native_$$`, `~/tmp/freebuff_<tag>_$$`, checkpoint `~/.install_freebuff_checkpoint`.

## 7. Security

- Downloads third-party binaries (Honkonx/bun-termux with fallback to Hope2333/bun-termux, CodebuffAI) and runs them **without a hash**.
- Copies the whole `usr/` tree of a third-party `.deb` over `$PREFIX` with `cp -a` (`:~334`): a malicious `.deb` could overwrite any Termux binary (the real risk depends on trust in the repo).
- `--create`: template and name are escaped with `shellEscape`.

## 8. Known limitations

- `ModuleController.kt:900` uninstalls only with `npm uninstall -g freebuff` — it does not remove the native wrapper `$PREFIX/bin/freebuff`, `~/.config/manicode/freebuff`, `bun-termux` or `~/.bun`; uninstall is incomplete for the native channels (the most likely ones).
- `modules.json` does not reflect the real installation method.
- No checksum; no version pin.
- The script notes that CodebuffAI's binary from method 2 segfaulted on a real device (comments `:26-31`) — it is kept as a fallback with no guarantee.
- Unverified hypothesis: `find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"` does not filter on whether the file is actually an executable (ELF); if a future release's tarball contained more than one non-`.tar.gz` file (e.g. a `README.md`/`LICENSE` next to the binary), `find` might not return the real binary first (`find` order is not guaranteed alphabetical). Reproduce it with ADB before assuming a fix.
- The install script mixes three long paths; without a device it was not verified which one ends up active on each machine.

## 9. Date added

`modulos/freebuff.sh`: **2026-08-12**.
