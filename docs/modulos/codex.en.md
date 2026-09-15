# Codex CLI

**Kairos module** — installation managed by the app via `ModuleController.installModule()` → `ProcessBuilder` → `modulos/codex.sh`. No on/off switch (pure CLI, no persistent process).

---

**Script:** `modulos/codex.sh`
**Fragment:** `CodexFragment.kt`
**`modules.json`:** `id: "codex"`, `hasSwitch: false`
**Registry prefix:** `codex.*`

---

## 1. Overview

Codex CLI is OpenAI's terminal-based code assistant. Kairos offers 2 fully independent channels: `termux` (default, via npm) and `native` (prebuilt ARM64 binary, no Node.js required).

## 2. Permissions

None specific to Android. Doesn't expose a port or run in the background.

## 3. Variants/channels

| Channel | Package/source | Node.js needed | Notes |
|---|---|---|---|
| `termux` (default) | npm package maintained by a third party | Yes (`nodejs-lts`) | Not an official OpenAI package |
| `native` (`--variant native`) | Prebuilt ARM64 binary, pinned version | No | Single-maintainer repo — a deliberately accepted risk, fixed version (not `latest`) for that exact reason |

## 4. Installation logic

### `native` channel (separate branch, exits before touching Node.js)
1. Downloads the ARM64 binary to a dedicated working directory (never `/tmp/` — some Android versions mount it `noexec`).
2. Extracts it, finds the binary, copies it to its own path, and creates a symlink at `$PREFIX/bin/codex`.
3. **Real execution check** (`codex --version`), not just that the file exists — an ARM64 binary doesn't always run on Bionic. If it fails, deletes the binary and the symlink and aborts.
4. Registry entry with `channel=native`.

### `termux` channel (default)
1. **STEP 1 — Node.js**: installs `nodejs-lts` if not present.
2. **STEP 2 — npm installation** — captures the real exit code of `npm install` (not the exit code of an intermediate logging pipe) so it doesn't rely on a false exit code from a downstream command, aborts if `npm` actually failed.
3. **STEP 3 — Login**: in `--silent` mode, explicitly skipped (can't be automated without credentials). In interactive mode, offers to run `codex login` right there.
4. Registry entry with `channel=termux`, real version read from `codex --version`.

## 5. npm channel choice — important note on dist-tags

The script explicitly installs the `@latest` dist-tag of the package, never `@next` — the
package's documentation describes `next` as candidate versions published after CI validation
(not yet promoted) and recommends `@latest` for end users. In this specific package `next`
can end up behind `latest` (opposite of the usual convention for other npm packages), so
blindly installing `@next` could end up with an older version than expected.

## 6. State detection

Detects the real version by running `codex --version` and parsing the semver from the output. If a version is already detected and `--force` wasn't passed, the script exits immediately without re-syncing the registry.

On the app side, a switch-less module (`hasSwitch: false`) — installation detection gates the UI, with no concept of "running".

## 7. App screen (`CodexFragment.kt`)

- **STATUS card**: channel, version (read from the registry), status/terminal pills.
- **"⌨ Open in terminal"** — opens the CLI directly.
- **"📁 Open in project"** — lists real projects under `~/proyectos` (same shared folder as Claude/Antigravity/OpenCode).
- **"🗂 Manage projects"** — symlink/import a copy from Download, delete, sync all.
- **"🔑 codex login"** — opens the login flow in the terminal.
- **DIRECT PROMPT card — "💬 Send prompt (non-interactive)"** — free-text dialog for the prompt, a free-text dialog for the model (accepts a literal name, empty = default), a sandbox selector (`read-only`/`workspace-write`/`danger-full-access`/default), and an approval selector (`untrusted`/`on-request`/`never`/default) — runs `codex exec` in the background with `--output-last-message` to separate the final message from progress noise.
- **SESSION card** — "🕒 Resume last session" → `codex resume --last` (resumes the most recently recorded session for the current directory; if Codex was never run before from that same directory, the CLI itself replies that there's no session).
- **"⚙ Install / change channel"** — reinstalls via `ModuleController.installModule()`.

## 8. Registry

```
codex.installed=true
codex.version=<x.y.z>
codex.channel=termux|native
codex.install_date=<YYYY-MM-DD>
```
