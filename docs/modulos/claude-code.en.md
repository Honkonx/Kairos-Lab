# Claude Code — Kairos Module

**Kairos Module** — installation managed by the app via `ModuleController.installModule()` → `ProcessBuilder` → `modulos/claude.sh`. No on/off switch (it is not a persistent service, it is a CLI invoked in the terminal).

---

**Script:** `modulos/claude.sh` (1017 lines, v4.0.0)
**Fragment:** `ClaudeFragment.kt` (509 lines; `ModuleDetailNavigator.kt:23`)
**`modules.json`:** `id: "claude"`, `hasSwitch: false`, `terminalCommand: "claude"`, icon `A\`
**Registry prefix:** `claude.*`

---

## 1. Overview

Claude Code is Anthropic's AI coding agent CLI (`claude`). In Kairos it runs 100% inside Termux, without proot — two possible installation methods, chosen by the user or passed through `--variant`. Kairos gives it a screen with: open TUI in a folder, non-interactive direct prompt (`-p`), continue/resume session, diagnostics, native MCP panel, fixed OAuth token, per-tool permissions and project management.

**Classification: FUNCTIONAL MODULE** (own screen): real state (registry version, status pill, terminal pill), flows with chained dialogs and config read/write (`~/.claude.json`, `~/.claude_oauth_token`). No switch or port. ~14 UI controls.

## 2. Permissions

None specific to Android — only those already covered by the general wizard (storage, so projects in `~/proyectos`/Download are accessible). It exposes no port and does not run in the background — it is a binary invoked on demand from the terminal.

## 3. Installation variants

| Variant | Method | Pinned version | When to use |
|---|---|---|---|
| `native` (recommended) | Real ELF binary, patched with `patchelf` to run through `glibc-runner` on Bionic | `CLAUDE_VERSION_NATIVE="2.1.272"` (`claude.sh:133`; raised for 2 CVEs, GHSA-7835-87q9-rgvv and GHSA-fg94-h982-f3mm, both < 2.1.163), or `latest` if `--version latest` is passed | Default — faster, no Node.js overhead |
| `legacy` | npm package `@anthropic-ai/claude-code`, runs on Node.js | `2.1.111` (deliberately fixed, "no extra deps") | Fallback if `native` fails or the device has trouble with `glibc-runner` |

Both variants also accept `--source clean` (clean download, default) or `--source github` (restores from a backup in the repo's own GitHub Releases, via `restore.sh --module claude-native`/`claude`; it downloads `restore.sh` from `raw.githubusercontent.com/Honkonx/termux-ai-stack/main/restore.sh` and runs it, `:755-764`, with no hash or signature).

Flags (`claude.sh:59-65`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant native|legacy`, `--source clean|github`, `--version latest`. Without `--variant` in silent mode, `native` is assumed (`:96-99`). In `--silent` mode the script forces `USE_LATEST=true` (`:76`): it queries `downloads.claude.ai/claude-code-releases/latest` and uses the pin only as a fallback.

## 4. Installation logic (step by step, `modulos/claude.sh`)

### `native` variant
1. **`_ensure_termux()`** — `pkg update` (checkpoint `termux_update`, skipped if `ANDROID_SERVER_READY` is already set by the app bootstrap).
2. **`_ensure_glibc()`** (`:394-412`) — installs `glibc-repo` → `pkg update` → `glibc-runner`/`patchelf-glibc`/`jq` (checkpoint `glibc_deps`), adds `~/.local/bin` to `PATH` in `.bashrc` if missing.
3. **Download** (there are two mechanisms; the first is the primary one):
   - **`_download_wallentx_native()`** (`:532`): downloads `claude-termux-aarch64.tar.gz` from `github.com/wallentx/claude-code-termux/releases/latest` (`:148`), extracts `claude`, `claude.glibc` and `claude-termux-update`, with backup/rollback and `--version` verification. SHA256 is **best-effort** (if the `.sha256` does not exist it continues without verifying, `:561-575`).
   - **`_download_and_patch_native()`** (`:460`) — the classic mechanism, if the previous one fails (or `_install_native_github()` with `--source github`): resolves the version (fixed or `latest` via `downloads.claude.ai/claude-code-releases/latest/manifest.json`); downloads the ARM64 binary (`linux-arm64/claude`); **verifies SHA256** against the real `manifest.json` — if it does not match, it deletes the binary and retries with the pin; if the manifest cannot be read, it only warns and continues without verifying (`:478-492`); `chmod +x` + `patchelf --set-interpreter` pointing to `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` (which lets it run a real glibc ELF on Bionic without proot).
   - Generates a wrapper at `~/.local/bin/claude` that does `unset LD_PRELOAD` before running the real binary (needed so the patched interpreter does not inherit Termux's `LD_PRELOAD`, which would break the glibc binary).
   - Writes `~/.claude/settings.json` (see section 6).
   - Updates the registry and verifies that `claude --version` responds before accepting the installation; on failure it writes `installed=false reason=binario_no_ejecuta` (`:740-749`, `:615-625`).

### `legacy` variant
1. `_ensure_termux()` (same as above).
2. `_ensure_nodejs()` (`:373`) — installs `nodejs-lts` if there is no Node ≥18, plus `npm install -g npm`.
3. `_install_legacy_clean()` (or `_install_legacy_github()`) — **4 strategies in cascade** (`:796`), each verified with `_validate_legacy_cli()` (that `cli.js` exists, is not empty, and `node cli.js --version` does not throw `SyntaxError`/`not found`) before being accepted:
   1. `npm install -g @anthropic-ai/claude-code@2.1.111 --save-exact` directly.
   2. If it fails: the same `npm install` with `--ignore-scripts`.
   3. If it still fails: downloads the tarball directly from `registry.npmjs.org` and extracts it by hand.
   4. If it still fails: repairs only `cli.js` from a backup in the repo's own GitHub Releases (`part2-claude`).
   - Generates the wrapper `$PREFIX/bin/claude` (`node cli.js` with `DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1`), writes `settings.json` and aliases (`claude`, `claude-check`).

## 5. State detection (`_detect_method()`/`_detect_version()`)

It does not rely on the registry to decide whether it is already installed — it checks the real filesystem on every run:
- `native`: `~/.local/share/claude-code/claude` exists and is executable.
- `legacy`: `<npm root global>/@anthropic-ai/claude-code/cli.js` exists.
- `broken`: the wrapper exists but not the real binary/package behind it — triggers a reinstall.
- `none`: none of the above.

If already installed with the requested method (or any, if `--variant` is not specified) and `--force` is not given, the script **still re-syncs the registry** before exiting — this prevents a working binary from showing "Not installed" in the app because of an outdated registry.

On the app side, Claude Code is a module **without a switch** — `isModuleInstalled()` in `ClaudeFragment.kt` is the only thing gating the UI, with no "running/stopped" concept.

## 6. `settings.json`

`~/.claude/settings.json` does NOT write an `"autoUpdates": false` key — that key **does not exist in Claude Code's real schema** (per `code.claude.com/docs/en/settings` and issues `anthropics/claude-code#10079`/`#56723`). Instead:
```json
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1"
  }
}
```
(the `native` variant also adds `LD_PRELOAD` pointing to `libtermux-exec-ld-preload.so` inside `env`). Without this, the `native` binary (patched with `patchelf`) may auto-update and overwrite itself with an unpatched ELF that no longer runs on Bionic — the failure shows up on a later use, not at installation.

## 7. App screen (`ClaudeFragment.kt`)

| Control | What it does | Why |
|---|---|---|
| STATUS card (Method/Version/Status/Terminal) | Read-only: method (fixed text `native·glibc`, even if legacy was installed, `:49`), version (`claude.version` from the registry), "Status" pill (`isTermuxBinaryAvailable("claude")`), terminal pill/close button | — |
| ACCOUNT — FIXED OAUTH TOKEN card | "🔑 Use fixed OAuth token" (`promptSetOauthToken`), "🚫 Remove fixed token" (`confirmClearOauthToken`, with confirmation); amber notice "⚠ Using fixed OAuth token (not the session account)" if `~/.claude_oauth_token` exists | See section 9 |
| "▶ Open in root directory (~)" | Where-to-open dialog → `ClaudeNative.openCmd(null)` → opens terminal | Picks the real command according to the detected method (native needs `unset LD_PRELOAD`, legacy runs node on `cli.js`) — a bare `claude` might not start in either case |
| "▶ Open in project" | Lists real projects via `ProjectsManager.projectsList()` (native Kotlin, symlinks via `android.system.Os` + registry with file locking) and opens in the chosen one | — |
| "📁 Manage projects" | Shared menu: symlink from Download / delete / sync all | — |
| "💬 Send prompt (non-interactive)" | Asks for prompt → model (`Default` omits `--model`, or `sonnet`/`opus`/`haiku`/`fable`) → `--permission-mode` (`askPermissionModeThenSend()`: `Default (settings.json)` omits the flag, or `default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions`; `manual` is omitted since it is only an alias of `default`) → `-p '<escaped prompt>' --model <alias> --permission-mode <mode>` opened in the terminal via `ClaudeNative.openCmd(null, extraArgs)` | The CLI's headless mode (`-p`/`--print`), values confirmed against `code.claude.com/docs/en/cli-reference`. `bypassPermissions` is available with no extra visible warning (`ClaudeFragment.kt:402`) |
| "🔧 Tool permissions" (DIRECT PROMPT card) | Dialog with 2 `EditText`s (allow/deny, free text separated by commas — the real list depends on which MCP/servers each user has); `buildToolPermissionArgs()` builds `--allow-tool '<tool>'` / `--deny-tool '<tool>'` repeated per tool (`:421-422`), applied to the direct-prompt flow | See limitations: verify the flag names |
| "↻ Continue last conversation" | `openClaudeHere(null, "--continue")` | — |
| "🕒 Resume session…" | `openClaudeHere(null, "--resume")` (the CLI's own interactive picker) | — |
| "🔍 claude doctor" | `runCaptureDialog("doctor", ...)` — read-only diagnostic (installation/settings/Remote Control) in the background via `ManagerNativeUtils.runShell()` (60 s timeout), result in an `AlertDialog` | Same pattern as `CodexFragment.runExecCapture`; no terminal for a single-pass output |
| "🔑 Authentication status" | `runCaptureDialog("auth status", ...)` — prints JSON with the session state | — |
| "🔌 View MCP servers" | `showMcpPanel()` reads `~/.claude.json` directly via `ClaudeNative.mcpServers()` (without invoking the CLI) and shows it in a native read-only panel; `claude mcp list` in the terminal remains as an explicit alternative inside the panel (`onOpenTerminal`) | App premise: being able to do everything from the interface without opening the terminal |
| Engram button | `engramSetupButton("claude-code")` — configures Engram persistent memory for this CLI | — |
| "⚙ Install / change method" | `reinstallModuleService()` (without `--force`/`--variant`, `:197-201`) | See section 11 |
| "🗑 Uninstall" | `confirmUninstallModule()` — a standalone button (not the full `addMaintenanceCard()`) to avoid duplicating "Install / change method" | — |

Anti-tapjacking: `filterTouchesWhenObscured=true` (`:36`).

`ClaudeNative.openCmd(project, extraArgs)` (`app/src/main/java/com/termux/app/util/ClaudeNative.kt:109`) builds the final command: `[CLAUDE_CODE_OAUTH_TOKEN=...] export DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1; unset LD_PRELOAD; "<wrapper>" [extraArgs]` (native) or `... node cli.js` (legacy), with `cd "<project>" &&` if a folder was chosen. `extraArgs` is additional argv already built/escaped by the caller, concatenated after the binary/`cli.js` and before the `cd`, so the same command works for both `native` and `legacy`. The `DISABLE_AUTOUPDATER`/`DISABLE_UPDATES` variables are exported inline because `ManagerNativeUtils.runShell()` runs a non-interactive `bash -c <cmd>`, which does not source `.bashrc` (where `claude.sh` leaves them for the interactive terminal).

The direct prompt is sent with minimal single-quote escaping (`shellEscape`, `'` → `'\''`, `:359`) — the same pattern as `CliToolFragment.shellEscape()`; `--permission-mode` comes from a closed list.

## 8. Registry (`~/.android_server_registry`)

```
claude.installed=true
claude.version=<x.y.z>
claude.method=native|legacy
claude.install_date=<YYYY-MM-DD>
claude.location=termux_native
```
(`registry_install claude ...`, `:264`.)

## 9. Fixed OAuth token (`CLAUDE_CODE_OAUTH_TOKEN`)

It lets Claude Code always open with the account of a fixed token instead of the account tied to the session's interactive login.

**Claude Code facts** (`code.claude.com/docs/en/authentication`):
- The token is generated with `claude setup-token` — it requires a Pro/Max/Team/Enterprise plan; it is a long-lived OAuth token meant for CI/scripts without a browser. Kairos does not automate it (it needs the CLI's own interactive OAuth flow): the user pastes it by hand.
- It is activated with `export CLAUDE_CODE_OAUTH_TOKEN="..."`. When set, Claude Code silently uses it instead of the credentials in `~/.claude/.credentials.json`.
- Limits: (a) scoped to inference only — it **cannot open Remote Control sessions**; (b) there is an open issue (`anthropics/claude-code#16238`) asking Claude Code to warn when this variable overrides the normal credentials — which is why Kairos makes it explicit in its UI.

**Code**:
- `ClaudeNative.kt`: `oauthTokenFile` (`~/.claude_oauth_token`), `hasOAuthToken()`, `writeOAuthToken(token)` (writes the file + `chmod 600` + adds a block delimited by the marker `# Claude Code OAuth token fijo (Kairos)` to `~/.bashrc`, with dedupe), `clearOAuthToken()` (deletes the file + filters any line mentioning the marker or `CLAUDE_CODE_OAUTH_TOKEN` out of `.bashrc`) and `oauthTokenPrefix()` (private) — prepends `export CLAUDE_CODE_OAUTH_TOKEN="$(cat ...)"; ` to the command built by `openCmd()` in BOTH branches (native and legacy), covering both the interactive terminal and background calls.
- `ClaudeFragment.kt`: the "ACCOUNT — FIXED OAUTH TOKEN" card (use/remove buttons and amber notice, `oauthStatusSlot` + `refreshOauthStatus()`).
- The UI never shows the token. The value does not remain literal in the command but does appear in the child process environment (visible via `/proc/<pid>/environ` only to the same uid; low risk).

## 10. Files and paths

`~/.local/share/claude-code/claude` (binary), `~/.local/share/claude-code/claude.glibc` and `claude-termux-update` (wallentx method), wrapper `~/.local/bin/claude` (native) or `$PREFIX/bin/claude` (legacy), `~/.claude/settings.json` (env: `DISABLE_AUTOUPDATER`, `DISABLE_UPDATES`, `LD_PRELOAD` termux-exec), `~/.claude_oauth_token` (chmod 600), marked block in `~/.bashrc`, checkpoint `~/.install_claude_checkpoint`, temporaries `~/.claude_wallentx_dl.tar.gz` / `~/.claude_wallentx_extract`.

## 11. Security and known limitations

- Download: the wallentx method uses a third-party repo and runs an unsigned binary (optional SHA256), and `restore.sh` is not verified → weak chain of trust (no literal `curl|bash`, but download+exec). Checksum is not mandatory on 2 of 3 paths.
- The fallback comment at `claude.sh:~675` still says "2.1.152" (out of date; the pinned value is 2.1.272).
- **Registry prefix**: the UI always reads `claude.*` (not `claude_code.*`).
- **Method conflict**: if the user has `legacy` installed and asks for `native` (or vice versa) in `--silent` mode, the script uninstalls the old method automatically before installing the new one (in interactive mode, it asks first).
- **The "Install / change method" button is effectively a no-op if already installed**: `ModuleController.installModule()` cannot pass `--force`/`--variant`, and the script exits early if it already detects the installed method; it does not allow changing method or updating if a binary exists.
- The "Method" row is hardcoded to `native·glibc` (`ClaudeFragment.kt:49`) even if the installation is legacy.
- Inference (high): `--allow-tool/--deny-tool` (`ClaudeFragment.kt:421-422`) are GitHub Copilot CLI flags; Claude Code uses `--allowedTools`/`--disallowedTools` as far as is known. The code comment claims it was confirmed against the documentation, but it was not checked by running `claude --help`; verify on a device before trusting the button.
- Inference: the module does not declare `hasVariants:true` in modules.json even though the script has native/legacy → the app does not offer choosing a variant when installing.
- The fixed OAuth flow and per-tool permissions have not been tested on a device.

## 12. Date added

`modulos/claude.sh`: **2026-07-19** (in this repo; the module existed earlier in the original scripts project).
