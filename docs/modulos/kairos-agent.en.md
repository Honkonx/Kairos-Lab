# Kairos Agent (`kairos_agent`) — Kairos Add-on

> Sheet based on reading the code. Convention: **Confirmed** = verified by reading the cited file; **Unverified** = inference not checked on a device.

**Script:** `modulos/kairos_agent.sh` (860 lines, byte-for-byte identical to `app/src/main/assets/scripts/kairos_agent.sh`)
**Fragment:** none of its own — uses `GenericModuleFragment` (`ModuleDetailNavigator.kt:65`, `else` branch)
**Added:** 2026-09-16 (`catalogVersion` 1.0.0)

## 1. What it is and what it is for

A 100% stdlib Python engine (embedded as a heredoc in the `.sh`, `kairos_agent.sh:133-821`) that
is installed in `~/.kairos_agent/` and invoked through the `kairos-agent` wrapper. It keeps a
SQLite database (`modules`, `state`, `history`; `:324-345`) with the Kairos module catalog, their
observed state and the action history, and exposes a **fixed catalog of intents**
(`INTENTS`, `:637-652`): `refrescar`, `procesos`, `estado`, `instalar`, `iniciar`, `detener`,
`historial`, plus the CLI subcommands `tools` and `interpretar`. Optionally a local LLM
(llama-server :8085 / Ollama :11434 / `KAIROS_AGENT_LLM_URL`) can *pick an intent* from natural
language (`interpret_with_ai`, `:688-729`).

It is an orchestration CLI (JSON on stdout), not a service or a chat.

## 2. Classification

**ADD-ON/EXTENSION (with its own CLI).** Rationale (confirmed): there is no dedicated Fragment
(`ModuleDetailNavigator.kt` does not list it; the only mention in `app/src/main/java` is a comment
in `KairosBootstrap.kt:61`), it has no `hasSwitch` (`modules.json:1771`), no port, no resident
process, and no entry in `ModuleController.kt`. The UI is the generic one: ~5 controls (see §5).
All the real functionality lives in the CLI.

## 3. Installation

- Flags: `--silent`, `--force`, `--describe` (`:49-63`). `--describe` returns
  `{"id":"kairos_agent","supports_silent":true,"supports_force":true,"variants":[]...}`.
  It does not implement `--describe-files` (no `moduledeb` support).
- Dependencies: `python3` and `sqlite` (`modules.json:1781-1784`); the script installs them if
  missing (`pkg install python` `:107`, `pkg install sqlite` `:122`). `installMethods: ["stdlib"]`.
- Steps: 1 Python, 2 sqlite3 (checked with `python3 -c "import sqlite3"` `:124`), 3 writes
  the engine `~/.kairos_agent/kairos_agent_engine.py` (`:131-824`), 4 wrapper
  `$PREFIX/bin/kairos-agent` (`:828-834`) + a best-effort first `kairos-agent refrescar` (`:838`).
- Checkpoints: `~/.install_kairos_agent_checkpoint` (`kairos_agent_python`, `_sqlite`).
- "Already installed": `command -v kairos-agent` + engine exists + no `--force` -> `exit 0` (`:86`).
  `--force` only deletes the checkpoint (`:90`); it does not delete the engine or the DB, the
  engine is rewritten anyway (`cat >` `:133`).
- Verification: sqlite3 is verified; the final wrapper/engine is not executed as a
  post-condition (it does not use `verify_binary_installed`) — the initial `refrescar` only emits
  a `warn` on failure (`:838`), and `installed=true` is still written.
- Registry (`registry_install`, `:841-845`): `kairos_agent.installed=true`, `version=1.0.0`,
  `install_date`, `engine`, `db`, `intents`, `ai_hook`. Also `notify_event ... install_done`.

## 4. Execution

There is no start/stop: the CLI runs on demand and exits (`main()` `:793-816`). No port of its
own (`"port": ""`), no tmux session of its own, no resident process.

The agent *controls* other modules through `MODULE_CONTROL` (`:202-242`), a manual copy of the
`ModuleController.kt` routes for 7 modules (ollama, n8n, openclaw, opencode, remote,
llamaserver, db). The routes match those in `ModuleController.kt:1162-1171` for the ones that were
checked (ollama/remote/llamaserver/db) — this is manual duplication: if `ModuleController`
changes, the agent falls out of sync without warning. For any other module only `instalar`/`estado`
(`:558-568`, `:575-579`).

Subprocesses used: `bash <script>`, `tmux has-session/kill-session`, `pgrep -x`, `pkill -x`,
`ps -ef`/`ps aux`, `su -c id`/`su -c ps -ef` (only `procesos` with confirmed root). Timeouts:
install 1800 s, start 180 s, stop 60 s.

## 5. UI functions/options

Fragment: `GenericModuleFragment.kt` (generic fallback). Controls it gets: Status card
(id, registry version, type, native execution), status pill, **Open in terminal** button
(runs a bare `kairos-agent`, `terminalCommand` from `modules.json:1774`), **Manage
projects** (`ProjectActions` menu — hardly relevant here: the agent has no concept of
projects), and the Maintenance card: **Update**, **Diagnose**, **Uninstall**. ~5-6
controls, none specific to the agent.

`kairos-agent` with no arguments prints `{"ok": false, "error": "uso: ..."}` and
`sys.exit(1)` (`:794-797`), so "Open in terminal" opens a session that shows the usage and
exits; it is not a useful flow on its own. No intent (install/start/status/history) is
reachable from buttons.

Related but separate: `VoiceToAgentDialog.kt` (button in `ModulesFragment.kt:143`) is the
"Voice -> Agent" feature that dictates a prompt to AI CLIs (claude, codex, opencode, hermes...);
its `CLI_OPTIONS` list does not include `kairos_agent`. They are not the same thing as this add-on.

## 6. Files and paths

- `~/.kairos_agent/kairos_agent_engine.py`, `~/.kairos_agent/kairos_agent.db`,
  `~/.kairos_agent/catalog.json` (`source/count/generated_at` summary, `:462`).
- `$PREFIX/bin/kairos-agent` (bash wrapper -> `exec python3 engine`).
- `~/.install_kairos_agent_checkpoint` (temporary), registry `~/.android_server_registry`
  (prefix `kairos_agent.`).
- Reads: `~/scripts/modules.json` (extracted by `KairosBootstrap.kt:66`, versioned by
  `isAlreadyExtracted` `:37-45`), `~/scripts/install/*.sh` (installers) and start/stop paths
  of other modules under `~/scripts/<module>/`.

## 7. Security

Is it true that it "never runs free-form commands"? **Yes, in the code reviewed:**
- No `shell=True`, `eval`, `exec` or `os.system` in the engine; all subprocesses use an
  argument list (`_run(cmd)` -> `subprocess.run(list)`, `:248-250`).
- Module `id`s are validated with `^[a-z0-9_]+$` (`:193`, `:258`) before building the path
  `~/scripts/install/<id>.sh` (no path traversal), and the variant with `^[a-z0-9_-]+$` (`:194`).
- The LLM can only return `{"intent","args"}` and the result is rejected if the intent is not in
  `INTENTS` or args is not a dict (`:723-726`); mutating intents (`instalar/iniciar/detener`)
  require `--auto` or return `pending_confirmation` (`:756-762`).

Caveats and risks:
1. "No free-form commands" does not mean "no script execution": `instalar` runs
   `bash ~/scripts/install/<id>.sh --silent` — it runs that file's content, which is trust in the
   app bootstrap (it is not LLM input).
2. `_VARIANT_RE` accepts a value starting with `-` (e.g. `--force` as a "variant"): it is passed
   as an argument to a Kairos script, not to a shell; low impact, but the validation is loose.
3. `--auto` in `interpretar` lets the LLM trigger an install/start/stop of *any* known module
   with no further confirmation; there is no per-module allowlist and no UI confirmation
   (because there is no UI).
4. `KAIROS_AGENT_LLM_URL` is an arbitrary URL that receives the request and the full intent
   catalog; `urllib.request.urlopen` also accepts `file://` and other schemes (not tested at
   runtime; the absence of a scheme filter is a fact of the code). No authentication toward the LLM.
5. Robustness: `int(args.get("limit"...))` (`:622`) raises an uncaught `ValueError` with a
   non-numeric limit (CLI or LLM output) — the process ends, it is not a vulnerability.
6. Secrets: the agent neither stores nor reads credentials; `history.detail` stores up to 2000
   characters of installer output (`:355`) in the DB without specific restrictive permissions
   (no `chmod`; the directory inherits Termux's umask, private thanks to the sandbox). Installer
   output could contain tokens (unverified).
7. `procesos` with root runs `su -c ps -ef` (fixed command).

## 8. Problems / gaps

Confirmed in code:
- No UI of its own: no intent is reachable from buttons; "Open in terminal" opens a bare `kairos-agent`, which only prints the usage and exits with 1 (`:794-797`).
- "Installed" detection by the UI: `kairos_agent` is not in `ModuleInstalled.BINARY_FALLBACK`/`LIVE_FALLBACK`; it relies only on the registry (`kairos_agent.installed`); if the live fallback were used it would probe `command -v kairos_agent` (underscore) while the real binary is `kairos-agent` (hyphen).
- No real post-condition for the wrapper (see §3); `--force` does not clean engine/DB.
- No `--describe-files` (cannot be packaged with `moduledeb`).
- `MODULE_CONTROL` duplicates `ModuleController.kt` by hand (§4); only 7 modules have start/stop.
- The `_describe_installed_scripts` fallback (`:375-408`) leaves name/port/category empty (documented in the code itself).
- After `refrescar`, `refresh_catalog` does `DELETE FROM modules` and re-inserts everything (`:420`); the `running` state is not transactional (low severity, unverified).
- `history` grows indefinitely (`log_history` never purges; declared "never deleted" `:151`).

Unverified on a device: the execution of `instalar`, the AI interpretation, and the behavior of the generic fragment's "Uninstall" with respect to `~/.kairos_agent`/the wrapper (the script has no uninstall logic).

## 9. Added

2026-09-16 (script header "Septiembre 2026").
