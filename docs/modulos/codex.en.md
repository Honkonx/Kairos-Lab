# Codex CLI — Kairos Module

**Kairos Module** — installation managed by the app via `ModuleController.installModule()` → `ProcessBuilder` → `modulos/codex.sh`. No on/off switch (pure CLI, no persistent process).

---

**Script:** `modulos/codex.sh` (v2.0.0, 361 lines)
**Fragment:** `CodexFragment.kt` (390 lines)
**`modules.json`:** `id: "codex"`, `hasSwitch: false`, `hasVariants: true`, icon `◆`
**Registry prefix:** `codex.*`

---

## 1. Overview

Codex CLI is OpenAI's terminal coding assistant, packaged for Termux/Bionic by third parties: `@mmmbuto/codex-cli-termux` (DioNanos/codex-termux), `@mmmbuto/codex-vl` (DioNanos/codex-vl, with V8 "code-mode") or the native Rust binary from `wallentx/codex-termux`.

**Classification: FUNCTIONAL MODULE** (own screen, `ModuleDetailNavigator.kt:24`): real state (channel/version/pill), a chain of dialogs for a non-interactive prompt with output capture (sandbox + approval), variant selector, projects. ~12 controls. No service or port.

## 2. Permissions

None specific to Android. It exposes no port and does not run in the background.

## 3. Variants/channels

The script has **2 variants** (`--variant termux|vl`, `codex.sh:61`; `--describe` `:69`) plus an **automatic native fallback** (wallentx/codex-termux) that fires only if the `termux` npm route fails.

| Channel | Package/source | Node.js needed | Notes |
|---|---|---|---|
| `termux` (default) | npm `@mmmbuto/codex-cli-termux@latest` | Yes (`nodejs-lts`) | Maintained by a third party (`DioNanos/codex-termux`), not official OpenAI |
| `vl` (`--variant vl`) | npm `@mmmbuto/codex-vl@latest` + symlink `codex -> codex-vl` | Yes | No fallback (`:289-306`) |
| `termux-fallback` | Native Rust binary `*aarch64-linux-android.tar.gz` from `wallentx/codex-termux` | No, but it is only reached with Node already present (`:262-264`) | Used only if `termux` fails; the unstripped binary weighs ~1.4 GB (`:241`) |

## 4. Installation logic

- Flags (`:57-63`): `--silent`, `--force`, `--variant termux|vl`, `--describe`, `--describe-files`. Silent default: `termux` channel.
- Dependency: `nodejs-lts` (`modules.json` `requires:["nodejs"]`).
- Steps: Node (checkpoint `codex_node`) → `npm install -g @mmmbuto/codex-cli-termux@latest` (+ `fix_npm_shebang_wrapper`; it captures `PIPESTATUS[0]` after the pipe to `tail -5` so as not to trust `tail`'s exit code, and aborts with `error` if `npm` really failed) → `verify_binary_installed codex`; if that fails, `_install_codex_fallback_native()` (`:225`): queries `api.github.com/repos/wallentx/codex-termux/releases?per_page=5`, downloads the tarball to `$HOME/tmp/codex_fallback_dl` (it does not use `/tmp/`: Android 15 mounts it `noexec`), copies it to `$PREFIX/opt/codex-wallentx/codex`, symlinks it at `$PREFIX/bin/codex`, and verifies real execution.
- **Login**: in `--silent` mode it is explicitly skipped (`warn` + checkpoint still marked; `:329-331`) — it cannot be automated without credentials. In interactive mode it offers to run `codex login`.
- **No sha256/checksum on any path**; the native fallback downloads a ~GB tarball without verifying integrity. npm integrity depends on the registry itself.
- Real verification with `verify_binary_installed` (`codex --version`); registry `codex.installed/version/channel/install_date` (`:352-358`).

### Why `@latest` and not `@next`

The script explicitly uses `CODEX_PKG="@mmmbuto/codex-cli-termux@latest"`, NEVER `@next`. The repo README (`github.com/DioNanos/codex-termux`) describes `next` as "tested candidate versions published after GitHub Actions validation" (not yet promoted) and says `@latest` is the recommended target for end users. For this specific package, `next` sits BELOW `latest` (the reverse of the usual convention): at the time it was checked, `stable=0.144.4`, `next=0.145.0`, `latest=0.146.0`.

## 5. State detection

`get_installed_ver()`: `command -v codex && codex --version` (regex `[0-9]+\.[0-9]+\.[0-9]+`). If a version is already detected and `--force` is not given, the script exits immediately (`exit 0`) without re-syncing the registry (unlike `claude.sh`).

On the app side, the module has **no switch** — `isModuleInstalled()` gates the UI, with no concept of "running".

## 6. App screen (`CodexFragment.kt`)

| Control | What it does | Notes |
|---|---|---|
| STATUS card (Channel/Version/Status/Terminal) | Read-only: channel read from the registry (`CodexFragment.kt:40`), version from `codex.version`, "Status" pill according to `isTermuxBinaryAvailable("codex")`, terminal pill/close button | — |
| "⌨ Open in terminal" | Where-to-open dialog → `launchTerminalCommand("codex")` | — |
| "📁 Open in project" | `ProjectsManager.projectsList()` over `~/proyectos` and opens `cd ... && codex` | Folder shared with Claude/Antigravity/OpenCode |
| "🗂 Manage projects" | Shared menu: symlink / import copy from Download / delete / sync all | — |
| "🔑 codex login" | `launchTerminalCommand("codex login")` | — |
| "💬 Send prompt (non-interactive)" | Asks for prompt → model (free text, no fixed aliases, e.g. `gpt-5.6-terra`; empty = default) → sandbox (`--sandbox`: `read-only \| workspace-write \| danger-full-access` or default) → approval (`--ask-for-approval`: `untrusted \| on-request \| never` or default) → runs `codex exec` in the background with `--output-last-message <file>` and shows the result in a dialog (`runExecCapture`, `CodexFragment.kt:342`) | `codex exec` (alias `codex e`) runs non-interactively and prints to stdout. `--output-last-message` separates the final message from progress noise (`--json` would require a dedicated event parser). Sandbox/approval values match Codex's official security documentation 1:1. Prompt and model use `shellEscape` |
| "🕒 Resume last session" | `codex resume --last` in the terminal (folder dialog) | Correct syntax per the official documentation (there is no short `-l` flag; when resuming you cannot change model/reasoning-effort). Resume is per recorded session for the current directory: if Codex was never run from that cwd, the CLI itself reports there is no session |
| Engram button | `engramSetupButton("codex")` — runs `engram setup codex \|\| engram setup` (if the slug is rejected, it falls back to Engram's interactive selector) | — |
| "⚙ Install / change channel" | Normal / VL selector → `installCodexVariant`, reinstall with `--variant` (`reinstallModuleService()`) | Same `--force` gap as the rest of the switchless modules |
| "🗑 Uninstall" | `confirmUninstallModule()` (`ModuleController` has a deep plan per channel, `ModuleController.kt:866-877`) | — |

## 7. Registry

```
codex.installed=true
codex.version=<x.y.z>
codex.channel=termux|termux-fallback|vl
codex.install_date=<YYYY-MM-DD>
```

## 8. Checkpoints — own namespace

Unlike other modules, `codex.sh` does NOT use the generic `check_done`/`mark_done` functions of `lib.sh` as they are — it overrides them to namespace the checkpoint keys as `codex_<step>=done`, on the same file `$HOME/.install_codex_checkpoint`.

## 9. Files and paths

Global npm package `@mmmbuto/codex-cli-termux` or `@mmmbuto/codex-vl` under `npm root -g`; `$PREFIX/bin/codex` (npm wrapper or symlink), `$PREFIX/bin/codex-vl`, `$PREFIX/opt/codex-wallentx/codex` (fallback), temporaries `$HOME/tmp/codex_fallback_dl`, checkpoint `~/.install_codex_checkpoint`; Codex's own config (`~/.codex`) is created by the CLI, not by the script.

## 10. Security

- Prompt and model use `shellEscape` (`CodexFragment.kt:214`); sandbox/approval are closed lists.
- The sandbox selector offers `danger-full-access` and approval `never` with no extra visible warning (`CodexFragment.kt:269-279`).
- Third-party packages (DioNanos, wallentx) run with the user's permissions; no hash verification.
- It handles no secrets of its own; login is done by the CLI in the terminal.

## 11. Known limitations

- No checksum on the fallback download (~1.4 GB) or anywhere in the script.
- Inference: `ModuleController.kt:868-886` has an uninstall plan for the 3 current channels (`termux-fallback`, `vl`, `termux`); there is no branch for an old `native` channel, so an old install with `channel=native` might not uninstall completely.
- Inference: after the early `exit 0` for "already installed", the registry is not re-synced.
- "Resume last session" reported an error on a real device that could not be reproduced by reading code; the hypotheses are that Codex rejects `--last` when there is no prior session for that directory, or a syntax change in a more recent version. Reproduce on a device (first confirming a prior session exists) before touching code.

## 12. Date added

`modulos/codex.sh`: **2026-07-24**.
