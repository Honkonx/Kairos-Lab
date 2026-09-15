# Freebuff

Based on the real code (`modulos/freebuff.sh`, `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`).

## 1. What it is

Freebuff (CodebuffAI) — a free coding-agent CLI, no account/API key required (confirmed against `github.com/CodebuffAI/freebuff`). In Kairos it falls into the shared `CliToolFragment.kt` (it has no screen of its own) — `CLI_MODULE_CONFIGS["freebuff"]` reflects the fact that the real CLI documents no login, direct prompt, or model selector, so it's left with just "Open in terminal" + project management.

## 2. Real installation (`modulos/freebuff.sh`)

On ARM64 (aarch64, the only real architecture Termux supports): downloads the real native binary from `github.com/CodebuffAI/freebuff/releases` (glibc, patched with `patchelf` to Termux's loader) — with a fallback to npm if the download fails. The real binary inside the tarball is named "codecane" (the project's internal rebrand, not "freebuff") — the script does NOT assume that name, it discovers it live (`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"`) and generates a real wrapper at `$TERMUX_PREFIX/bin/freebuff` that points to the actually-detected real binary, whatever its internal name is.

Real details to keep in mind when maintaining the script: the real releases repo is `CodebuffAI/freebuff` (not `codebuff-community`), all releases are prereleases (you have to request `/releases` instead of `/releases/latest`), and the real binary name inside the tarball is `codecane`, not `freebuff`.

The generated wrapper (`$TERMUX_PREFIX/bin/freebuff`) internally does `unset LD_PRELOAD` before executing the real binary — necessary for glibc compatibility inside Termux (bionic). Real functional verification (`freebuff --version`) before declaring the native method successful — if it fails, it falls back to npm instead of marking success with a broken binary.

## 3. Known limitation — discovering the binary inside the tarball

`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"` doesn't filter by whether the file is actually an executable (ELF) — if a future release's tarball included more than one non-`.tar.gz` file (e.g., a `README.md`/`LICENSE` next to the binary), `find` might not return the real binary first (`find`'s order isn't guaranteed to be alphabetical). This is an edge case not reproduced against a real release with that kind of content — it remains a known risk to watch for if the upstream project changes its release contents.

## 4. Screen controls (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["freebuff"]`)

| Control | What it does | Why |
|---|---|---|
| "⌨ Open in terminal" | `unset LD_PRELOAD; freebuff` | No login/prompt/model documented — the real CLI offers nothing more than this from the command line |
| "🗂 Manage projects" | `showProjectsMenu()` | Same mechanism shared by the rest of `CliToolFragment` |
| "＋ Create project from template" | `freebuff --create '<template>' '<name>'` | Dialog with 2 `EditText` fields (template + name) |
| "🔄 Update" / "🗑 Uninstall" | `updateModuleService()`/`confirmUninstall()` | Standard maintenance |

## 5. `freebuff --create <template> <name>`

Real flag documented in `codebuff-community` (the same project's templates/community repo) — implemented via `CliModuleConfig.createFromTemplateTemplate` in `CliToolFragment.kt` (a generic field reusable by any module with similar scaffolding), with a 2-`EditText` dialog (`showCreateFromTemplateDialog()`) that assembles `freebuff --create '<template>' '<name>'` with the same single-quote escaping used by the rest of the Fragment (`shellEscape()`).
