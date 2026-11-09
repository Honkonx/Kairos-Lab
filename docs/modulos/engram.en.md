# Engram — Kairos Module

**Kairos Module** — managed through the Kairos UI (Modules tab). Installation and state are handled from the app via `ProcessBuilder` → `modulos/engram.sh` (192 lines; the embedded script `app/src/main/assets/scripts/engram.sh` is identical).

---

**Project:** Kairos
**Code origin:** [github.com/Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) (MIT), originally packaged by [core-termux](https://github.com/DevCoreXOfficial/core-termux) (MIT)
**Script:** `modulos/engram.sh` (v1.0.0)
**Fragment:** `EngramFragment.kt` (373 lines; `ModuleDetailNavigator.kt:35`)
**`hasSwitch`:** `false` — no ON/OFF, no persistent background process (`modules.json:330-352`)

---

## 1. What it is

Engram gives persistent memory across sessions to the AI agents running inside Kairos (Claude Code, OpenCode, etc.) — it stores context (observations, decisions, per-project notes) in a local SQLite database, without depending on any external service or the cloud.

Unlike n8n/OpenClaw/OpenCode, it is **not a server process** — it is a CLI binary (`engram`) compiled from Go that runs on demand: when invoked (search, view stats, export, or its interactive TUI), it does not stay resident listening on any port.

**Classification: FUNCTIONAL MODULE** (CLI with its own UI, no service): a dedicated Fragment with ~13 buttons that run real operations of the `engram` binary (`EngramFragment.kt:92-164`), plus 2 pills (status, terminal) and a close-terminal button. There is no service: `hasSwitch=false`, no port (`"port": ""`), no entry in `ModuleController.kt`, no tmux of its own.

## 2. Permissions

It requires no special Android permission — only the generic ones the Kairos wizard already asks for (storage, to export memory to `~/`). It uses no network and exposes no ports.

## 3. Installation logic (`modulos/engram.sh`)

Accepts `--silent` (always implicit when called from the app), `--force` (reinstalls even if already present; deletes the checkpoint, `~/.engram-src` and the binary, `:112`, but **not** the memory data), `--describe` (declarative JSON manifest) and `--describe-files` (`:65-84`, uses `jq`; `package_name kairos-module-engram`, `version_registry_key: engram.version`).

| Step | Checkpoint | What it does |
|---|---|---|
| 1 | `engram_deps` | `pkg install -y golang git sqlite` — aborts with `error()` if `go`/`git`/`sqlite3` are not available after installing (`engram.sh:132-138`) |
| 2 | `engram_clone` | `git clone --depth 1` (without `--quiet`, captures the output and shows the last 3 lines, `engram.sh:149-150`) of `Gentleman-Programming/engram` (`:88`) into `~/.engram-src` — aborts if `cmd/engram/` is not found after cloning (detects whether the real repo changed structure) |
| 3 | `engram_build` | `go build -C ~/.engram-src/cmd/engram -o $PREFIX/bin/engram` — verifies the binary is executable (`-x`), and also tries `engram --version`/`engram --help` as a check that it runs |

At the end: `registry_write engram "installed=true" "install_date=$(date +%Y-%m-%d)"` (`engram.sh:188`) on `~/.android_server_registry`, prefix `engram.*`. **`engram.version` is not written** (it uses `registry_write`, not `registry_install`), so a `moduledeb pack engram` would have no registry version to read.

**Build environment variables**: `GOPATH=$HOME/.local/go`, `GOCACHE=$HOME/.cache/go`, `GOMODCACHE=$GOPATH/pkg/mod` — all under `$HOME`, never `/tmp` (Android 15 mounts it `noexec`).

**"Already installed" detection**: `command -v engram` (`:108`) — if it exists and `--force` was not passed, it exits immediately with `exit 0`.

**Dependencies**: `modules.json` declares `requires: ["go"]` (`:347-349`), although the script installs `golang git sqlite` itself. The catalog `repo` field is `Honkonx/kairos-lab` (a catalog/.deb field), different from the real code origin.

## 4. Options/variants

No variants (`"variants":[]` in the `--describe` manifest) — a single way of installing, with no mode/backend selection.

## 5. State detection in the app

`engram` has `hasSwitch: false` in `modules.json` — it is **not in the `ModuleController.kt` maps** (no entry in `getModuleStartScript()`/`getTmuxSession()`/`getProcessName()`). This means:
- There is no "running/stopped" concept — only "installed/not installed", via `BaseModuleFragment.isModuleInstalled()` reading `engram.installed=true` from the registry; the catalog/Monitor treat it as an installed CLI (`ModuleInstalled.kt:73,229` = `command -v engram`; `MonitorFragment.kt:193`).
- The "Terminal" shown by `EngramFragment` (status pill) is about the TUI session (`engram tui`), not a server process — it uses `terminalStatusPill()`, the same mechanism Claude Code/Codex/OpenCode use to know whether the user left a minimized terminal session running `engram tui`.

## 6. App screen (`EngramFragment.kt`)

"STATUS" card: engine (Go, native binary), storage (local SQLite), "ready/not responding" pill (`isTermuxBinaryAvailable("engram")`, it only checks that the binary exists on PATH, not that the SQLite database is accessible; `Doctor` covers that), terminal status pill.

Buttons (directly via `ProcessBuilder`, without going through the terminal, except where noted):

| Button | Real command | How it is shown |
|---|---|---|
| ⌨ Open TUI in terminal | `engram tui` (terminal) | Terminal overlay — **plain `engram` does NOT open the TUI**, it only prints help and exits with code 1 (per `cmd/engram/main.go` of the real repo) |
| 💾 Save memory manually | `engram save <title> <message>` | Dialog with 2 fields (title + multiline content) — documented in the README (`Gentleman-Programming/engram`, "Save a learning manually"). `--type`/`--project` are deliberately not exposed (optional in the README, with no documented closed list of values) |
| 🔍 Search memory | `engram search <text>` | Dialog with the raw result |
| 🗂 View recent context | `engram context [project]` | Dialog — README: "Displays recent session context". Complements "Search" when you do not know which term to search for |
| 📊 View statistics | `engram stats` | Dialog |
| 📁 List projects | `engram projects list` | Dialog — memory is organized by project; it lets you see which projects exist without guessing the exact name |
| 🩺 Diagnostics | `engram doctor` | Dialog |
| 🔗 Configure agent integration | `engram setup` (terminal, asks interactively) | Terminal overlay — `engram setup [agent]` asks which agent to configure when no argument is passed; it is opened in the terminal instead of guessing the name the flag expects (not documented with closed values in the README) |
| 📤 Export memory to JSON | `engram export ~/engram_export_<timestamp>.json` | Toast with confirmation (checks that the file exists on disk) |
| 📥 Import memory from JSON | `engram import <file>` | Lists the `engram_export_*.json` files in `~/` (`promptImportMemory()`/`promptImportPath()`/`confirmImport()`) so you can choose without typing a path; manual-path option as a fallback for exports elsewhere |
| 🗑 Delete a project's memory (DANGER) | `engram delete project <name>` (soft-delete, `--hard` deliberately not exposed) | 2 confirmations (name + "Delete?") |
| ⚙ Reinstall/recompile | `ModuleController.installModule("engram", ...)` / `reinstallModuleService()` | Toast |
| 🗑 Uninstall | `confirmUninstallModule()` (`EngramFragment.kt:162-164`) | — |

## 6b. Integration with AI agents

`engram setup` (the "🔗 Configure agent integration" button) is the generic, interactive route. In addition, several AI agent modules expose their own Engram integration shortcut:

| Module | Mechanism | Detail |
|---|---|---|
| Claude Code, Codex, OpenCode, Antigravity | `BaseModuleFragment.engramSetupButton(agentSlug)` — a shared button, one per Fragment (`"claude-code"`, `"codex"`, `"opencode"`, `"antigravity-cli"`) | Adds an Engram-specific MCP server to the agent's config (`BaseModuleFragment.kt:1461`). Runs `engram setup <slug> \|\| engram setup`: if the slug is rejected (the README documents no closed values for `engram setup [agent]`), it falls back to Engram's interactive selector |
| OpenClaw | Real MCP client (does not use `engramSetupButton()`) — see the comment in `OpenClawFragment.kt`/`OpenClawNative.kt` | OpenClaw is already an MCP client on its own |
| Hermes | No `engramSetupButton()` or custom tool-calling like OpenClaw — see the comment in `HermesFragment.kt`/`HermesNative.kt` | Hermes has its own Skills mechanism (see `HERMES.md`) |

## 7. Registry

Prefix `engram.*`: `engram.installed`, `engram.install_date`.

## 8. Technical notes

- **`engram` without a subcommand does not open the TUI** — `EngramFragment.kt` always calls an explicit `engram tui`, never the bare command.
- Engram is a CLI-type module (like Claude/Codex/OpenCode) that can leave a minimized terminal session running the TUI, so it includes `terminalStatusPill()`.
- The non-interactive subcommands used by buttons (`save`/`search`/`context`/`stats`/`projects list`/`doctor`/`export`/`import`/`delete`) are confirmed against the repo README.
- **Execution**: `runEngramCommand` (`EngramFragment.kt:335-356`) launches `$TERMUX_PREFIX_PATH/bin/engram` with `ProcessBuilder(list)` + `applyTermuxEnv()` (absolute path, no shell), `redirectErrorStream(true)`, **no timeout** (blocking `process.waitFor()` in a Thread): a hung command (e.g. `engram import` waiting for input) leaves the thread alive. TUI and `setup` go through `launchTerminalCommand`.

## 9. Security

- Nothing goes through a shell except the two fixed terminal commands (`engram tui`, `engram setup`). The title/content of `save`, the text of `search`, the path of `import` and the project name are list arguments — no command injection.
- Minimal validation: only `isEmpty()`. A value starting with `-` (e.g. project `--hard`) could be interpreted by `engram` as a flag (depends on the upstream Go parser; inference).
- `exportMemory()` leaves the full memory JSON at `~/engram_export_<ts>.json` with no `chmod` or cleanup; memory may contain sensitive project context, and exports accumulate unmanaged.
- `delete project` uses a double confirmation and no `--hard`.

## 10. Known limitations

- Execution check is **non-fatal**: `engram --version || engram --help || warn ...` (`:181`) — if the binary does not run it only warns and continues; the registry stays `installed=true` anyway.
- The build clones `--depth 1` without pinning a commit/tag: it depends on upstream `main` at that moment (reproducibility risk).
- The "Save" heuristic depends on the output containing the word `error` (`:213`); a failure without that word would show "Memory saved".
- Inference: it was not verified whether "Uninstall" deletes `~/.engram-src`, the binary or the memory data.
- Go compilation on Termux takes ~2 min, depends on the network and the upstream repo; it was not tested on a device in the latest review.

## 11. Date added

2026-07-29 (script header: "VERSIÓN: 1.0.0 | Julio 2026").
