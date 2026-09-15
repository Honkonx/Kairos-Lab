# Claude Code

**Kairos module** — installation managed by the app via `ModuleController.installModule()` →
`ProcessBuilder` → `modulos/claude.sh`. No on/off switch (not a persistent service, it's a CLI
invoked in the terminal).

---

**Script:** `modulos/claude.sh`
**Fragment:** `ClaudeFragment.kt`
**`modules.json`:** `id: "claude"`, `hasSwitch: false`
**Registry prefix:** `claude.*`

---

## 1. Overview

Claude Code is Anthropic's AI coding CLI. In Kairos it runs 100% inside Termux, no proot — two
possible installation methods, chosen by the user or passed via `--variant`.

## 2. Permissions

None specific to Android — only what's already covered by the general first-run wizard
(storage, so that projects in `~/proyectos`/Download are accessible). It exposes no port and
doesn't run in the background — it's a binary invoked on demand from the terminal.

## 3. Installation variants

| Variant | Method | When to use it |
|---|---|---|
| `native` (recommended) | Real ELF binary (`downloads.claude.ai`), patched with `patchelf` to run via `glibc-runner` on top of Bionic | Default — faster, no Node.js overhead |
| `legacy` | npm package `@anthropic-ai/claude-code`, runs on Node.js | Fallback if `native` fails or the device has issues with `glibc-runner` |

Both variants also accept `--source clean` (clean download, default) or `--source github`
(restore from a backup hosted on GitHub Releases).

## 4. Installation logic (step by step)

### `native` variant
1. Updates Termux (`pkg update`, with checkpoint).
2. Installs `glibc-repo` → `pkg update` → `glibc-runner`/`patchelf-glibc`/`jq`, adds
   `~/.local/bin` to the `PATH` in `.bashrc` if missing.
3. Downloads and installs the binary:
   - Resolves the version (fixed or `latest` via the official release manifest).
   - Downloads the ARM64 binary.
   - **Verifies SHA256** against the real manifest — if it doesn't match, deletes the binary
     and aborts, rather than trusting a silently corrupted download.
   - `chmod +x` + `patchelf --set-interpreter` pointing to
     `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` — this is what lets a real glibc ELF run on
     Bionic without proot.
   - Generates a wrapper that does `unset LD_PRELOAD` before running the real binary (needed
     so the patched interpreter doesn't inherit Termux's `LD_PRELOAD`, which would break the
     glibc binary).
   - Writes `~/.claude/settings.json` (see section 6).
   - Updates the registry and verifies `claude --version` responds before considering the
     installation successful.

### `legacy` variant
1. Updates Termux (same as above).
2. Installs Node.js (`nodejs-lts` if there's no Node ≥18) + `npm install -g npm`.
3. Installs the package with **4 cascading strategies**, each verified (that `cli.js` exists,
   isn't empty, and `node cli.js --version` responds) before being considered good:
   1. `npm install -g @anthropic-ai/claude-code --save-exact` directly.
   2. If it fails: the same `npm install` with `--ignore-scripts`.
   3. If it still fails: downloads the tarball directly from the npm registry and extracts it
      by hand.
   4. If it still fails: repairs just `cli.js` from a backup.
   - Generates the wrapper (`node cli.js` with `DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1`),
     writes `settings.json` and aliases.

## 5. Status detection

Doesn't rely on the registry to decide if it's already installed — it checks the real
filesystem on every run:
- `native`: the installed native binary exists and is executable.
- `legacy`: the npm global package's `cli.js` exists.
- `broken`: the wrapper exists but the real binary/package behind it doesn't — triggers
  reinstallation.
- `none`: none of the above.

If it's already installed with the requested method (or any method, if `--variant` wasn't
specified) and there's no `--force`, the script still re-syncs the registry before exiting —
this avoids a working binary showing up as "Not installed" in the app because of a stale
registry.

On the app side, Claude Code is a module with no switch (`hasSwitch: false`) — installation
detection is the only thing gating the UI, with no "running/stopped" concept.

## 6. `settings.json`

`~/.claude/settings.json` doesn't write an `"autoUpdates": false` key — that key doesn't exist
in Claude Code's real schema. Instead:
```json
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1"
  }
}
```
(the `native` variant also adds `LD_PRELOAD` pointing to `libtermux-exec-ld-preload.so` inside
`env`). Without this, the `native` binary (patched with `patchelf`) could auto-update and
overwrite itself with an unpatched ELF that no longer runs on Bionic.

## 7. App screen (`ClaudeFragment.kt`)

- **STATUS card**: method, version (read from the registry), status pill, terminal status pill.
- **"▶ Open in root directory (~)"** — builds the real command based on the detected method
  (`unset LD_PRELOAD && ...` for native, `node cli.js` with env vars for legacy).
- **"▶ Open in project"** — lists real projects via the shared project manager (symlinks +
  file-locked registry).
- **"📁 Manage projects"** — symlink from Download, delete project, sync all.
- **DIRECT PROMPT card — "💬 Send prompt (non-interactive)"** — free-text dialog for the
  prompt, followed by a dialog to choose the model (`Default` omits `--model`, or
  `sonnet`/`opus`/`haiku`/`fable`), and a third dialog to choose `--permission-mode`
  (`Default`, or `default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions`). Builds
  `-p '<escaped prompt>' --model <alias> --permission-mode <mode>` — the CLI's real headless
  mode (`-p`/`--print`: one prompt in, one response out, no REPL).
- **SESSION card**:
  - **"↻ Continue last conversation"** — `claude --continue`.
  - **"🕒 Resume session…"** — `claude --resume` (the CLI's own interactive picker).
- **DIAGNOSTICS card**:
  - **"🔍 claude doctor"** — read-only diagnostics (installation/settings/Remote Control), no
    interactive session, result shown in a dialog.
  - **"🔑 Auth status"** — `claude auth status`, prints JSON with the session status, shown in a
    dialog.
  - Both run in the background and show the result without opening a terminal.
- **MCP card** — "🔌 View MCP servers" reads `~/.claude.json` directly (without invoking the
  CLI) and shows it in a native panel; `claude mcp list` in the terminal remains an explicit
  alternative inside that same panel, not the primary action.
- **ACCOUNT card — FIXED OAUTH TOKEN** — lets you pin the `CLAUDE_CODE_OAUTH_TOKEN` variable
  (generated with `claude setup-token`, requires a Pro/Max/Team/Enterprise plan) so the app
  always opens with that token's account instead of the interactive session's account. The
  token is saved at `~/.claude_oauth_token` (`chmod 600`) and exported both in `.bashrc`
  (interactive terminal) and inline in every command built by the app (non-interactive
  background calls don't source `.bashrc`). When the file exists, the UI shows "⚠ Using a fixed
  OAuth token (not the session account)" — this matters because, with the variable set, Claude
  Code silently uses it instead of normal session credentials, a known point of confusion in
  the CLI itself. Real limitation: a fixed OAuth token is scoped to inference only — it can't
  open Remote Control sessions.
- **DIRECT PROMPT card — "🔧 Tool permissions"** — a dialog with 2 text fields (allow/deny,
  comma-separated) applied to the same direct-prompt flow as `--allow-tool '<tool>'`/
  `--deny-tool '<tool>'` repeated per tool.
- **"⚙ Install / change method"** — reinstalls via `ModuleController.installModule()`.
- **"MAINTENANCE" card — "🗑 Uninstall"**.

## 8. Registry

```
claude.installed=true
claude.version=<x.y.z>
claude.method=native|legacy
claude.install_date=<YYYY-MM-DD>
claude.location=termux_native
```

## 9. Technical notes

- **Method conflict**: if the user has `legacy` installed and requests `native` (or vice
  versa) in `--silent` mode, the script automatically uninstalls the old method before
  installing the new one (in interactive mode, it asks first).
- The direct prompt is sent with minimal single-quote escaping (not through a full shell) to
  prevent injection.
