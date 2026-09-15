# Engram

**Kairos module** — Managed via the Kairos UI (Modules tab). Installation and status are handled from the app via `ProcessBuilder` → `modulos/engram.sh`.

---

**App:** Kairos (termux-app fork)
**Code origin:** [github.com/Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) (MIT)
**Script:** `modulos/engram.sh`
**Fragment:** `EngramFragment.kt`
**`hasSwitch`:** `false` — no ON/OFF, no persistent background process

---

## 1. What it is

Engram gives persistent, cross-session memory to the AI agents running inside Kairos (Claude Code, OpenCode, etc.) — it stores context (observations, decisions, per-project notes) in a local SQLite database, without depending on any external service or the cloud.

Unlike n8n/OpenClaw/OpenCode, **it's not a server process** — it's a Go-compiled CLI binary (`engram`) that runs on demand: when invoked (searching, viewing stats, exporting, or its interactive TUI), it doesn't stay resident listening on any port.

## 2. Permissions

Requires no special Android permission — only the generic ones already requested by the Kairos wizard (storage, for exporting memory to `~/`). Doesn't use the network, doesn't expose ports.

## 3. Installation logic (`modulos/engram.sh`)

Accepts `--silent` (always implicit when called from the app), `--force` (reinstall even if already present), `--describe` (declarative JSON manifest).

| Step | Checkpoint | What it does |
|---|---|---|
| 1 | `engram_deps` | `pkg install -y golang git sqlite` — aborts with `error()` if `go`/`git` aren't available after installing |
| 2 | `engram_clone` | `git clone --quiet --depth 1` of `Gentleman-Programming/engram` into `~/.engram-src` — aborts if `cmd/engram/` isn't found after cloning (detects if the real repo's structure changed) |
| 3 | `engram_build` | `go build -C ~/.engram-src/cmd/engram -o $PREFIX/bin/engram` — verifies the binary ended up executable (`-x`), and additionally tries `engram --version`/`engram --help` as a real check that it runs (not just that the file exists) |

At the end: `_update_reg "installed=true" "install_date=..."` on `~/.android_server_registry`, prefix `engram.*`.

**Build environment variables**: `GOPATH=$HOME/.local/go`, `GOCACHE=$HOME/.cache/go`, `GOMODCACHE=$GOPATH/pkg/mod` — all under `$HOME`, never `/tmp` (Android 15 mounts it `noexec`).

**"Already installed" detection**: `command -v engram` — if it exists and `--force` wasn't passed, exits immediately with `exit 0`.

## 4. Options/variants

No variants (`"variants":[]` in the `--describe` manifest) — a single installation path, no mode/backend selection.

## 5. State detection in the app

`engram` has `hasSwitch: false` in `modules.json` — **it's not in `ModuleController.kt`'s maps** (no entry in `getModuleStartScript()`/`getTmuxSession()`/`getProcessName()`). This means:
- There's no concept of "running/stopped" — only "installed/not installed", via `BaseModuleFragment.isModuleInstalled()` reading `engram.installed=true` from the registry.
- The "Terminal" indicator shown by `EngramFragment` (status pill) refers to the TUI session (`engram tui`), not a server process — it uses `terminalStatusPill()`, the same mechanism used by Claude Code/Codex/OpenCode to know if the user left a minimized terminal session running `engram tui`.

## 6. Real app screen (`EngramFragment.kt`)

"STATUS" card: engine (Go, native binary), storage (local SQLite), "ready" pill, terminal status pill.

Buttons (all via direct `ProcessBuilder`, without going through the terminal, except where noted):

| Button | Real command | How it's shown |
|---|---|---|
| ⌨ Open TUI in terminal | `engram tui` (terminal) | Terminal overlay — `engram` alone does NOT open the TUI, it just prints help and exits with code 1 |
| 💾 Save manual memory | `engram save <title> <message>` | Dialog with 2 fields (title + multiline content) — confirmed against the official README (`Gentleman-Programming/engram`, "Saving a learning manually" section). `--type`/`--project` deliberately not exposed (optional in the README, without a closed list of documented values) |
| 🔍 Search memory | `engram search <text>` | Dialog with the raw result |
| 🗂 View recent context | `engram context [project]` | Dialog — confirmed in the README ("Displays recent session context"). Complements "Search memory" (which requires knowing what to search for) with a quick view of the most recently saved items |
| 📊 View statistics | `engram stats` | Dialog |
| 📁 List projects | `engram projects list` | Dialog — confirmed in the README. Memory is organized by project |
| 🩺 Diagnostic | `engram doctor` | Dialog |
| 🔗 Configure agent integration | `engram setup` (terminal, asks interactively) | Terminal overlay — `engram setup [agent]` asks interactively which agent to configure when no argument is passed; opened in the terminal instead of guessing the exact name the flag expects (not documented with a closed set of values in the README) |
| 📥 Import memory from JSON | `engram import <file>` — lists existing `engram_export_*.json` files under `~/` to choose from without typing a path by hand, with a manual path fallback | Direct complement to "Export memory" — confirmed in the official README |
| 📤 Export memory to JSON | `engram export ~/engram_export_<timestamp>.json` | Toast with real confirmation (checks that the file actually exists on disk) |
| 🗑 Delete a project's memory | `engram delete project <name>` (soft-delete, `--hard` flag deliberately not exposed) | 2 confirmations (name + "Delete?") |
| ⚙ Reinstall/rebuild | `ModuleController.installModule("engram", ...)` | Toast |

## 7. Integration with AI agents

`engram setup` (the "🔗 Configure agent integration" button) is the generic, interactive path. In addition, several AI-agent modules expose their own Engram integration shortcut, without going through this button:

| Module | Mechanism | Detail |
|---|---|---|
| Claude Code, Codex, OpenCode, Antigravity | `BaseModuleFragment.engramSetupButton(agentSlug)` — shared button, one per Fragment (`engramSetupButton("claude-code")`, `"codex"`, `"opencode"`, `"antigravity-cli"`) | Adds an Engram-specific MCP server to the agent's config. The helper runs `engram setup <slug> || engram setup` — falling back to the real interactive selector if the guessed slug is rejected, since `engram setup [agent]` has no closed set of documented values in the README |
| OpenClaw | Real MCP client (doesn't use `engramSetupButton()`) | OpenClaw is already a real MCP client on its own, so its integration with Engram doesn't go through the generic shared button |
| Hermes | No `engramSetupButton()` and no custom tool-calling like OpenClaw | Hermes has its own Skills mechanism (out of scope for this doc — see `docs/modulos/hermes.en.md`) |

## 8. Registry

Prefix `engram.*`: `engram.installed`, `engram.install_date`.

## Screen controls

| Control | What it does | Why |
|---|---|---|
| "Status" pill | Read-only — `isTermuxBinaryAvailable("engram")` | "ready" / "not responding" |
| "Terminal" pill | Read-only — minimized terminal session running the TUI | Visual indicator consistent with the rest of CLI-type modules |
| "⌨ Open TUI in terminal" | `engram tui` (never `engram` alone) | `engram` without a subcommand just prints help and exits with code 1 |
| "💾 Save manual memory" | Dialog (title + content) → `engram save` | Memory fills automatically via AI agents, but manual annotation is also supported |
| "🔍 Search memory" | Text dialog → `engram search` | — |
| "🗂 View recent context" | `engram context` | Complements "Search" when you don't know what term to search for |
| "📊 View statistics (stats)" | `engram stats` | — |
| "📁 List projects" | `engram projects list` | Lets you see what projects exist without guessing the exact name |
| "🩺 Diagnostic (doctor)" | `engram doctor` | — |
| "🔗 Configure agent integration" | Terminal — `engram setup` | Asks interactively which agent to configure — the flag has no closed documented values, none are guessed |
| "📥 Import memory from JSON" | `engram import <file>`, lists existing `engram_export_*.json` files | Restores previous backups without typing a path by hand |
| "📤 Export memory to JSON" | `engram export ~/engram_export_<timestamp>.json` | — |
| "🗑 Delete a project's memory" (DANGER) | Confirmation dialog → `engram delete project <name>` (soft-delete) | `--hard` flag deliberately not exposed — no irreversible deletion is enabled from a simple dialog |
| "⚙ Reinstall / rebuild" | `reinstallModuleService()` | — |
