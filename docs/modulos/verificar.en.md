# Verification (`verificar`) — Kairos Module

**Kairos module** — managed through the Kairos UI (Modules tab, `VerificarFragment`).
It is a **cross-cutting diagnostic** tool: it does not install/manage a service of its own, but
audits live whether the OTHER modules that the registry says are `installed=true` still have their
real binary/folder/package on the filesystem. It does not replace each individual module's
install/update flow — it complements it.

---

**Project:** Kairos
**Script:** `modulos/verificar.sh` (v1.0.0) — mirror in `app/src/main/assets/scripts/verificar.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/VerificarFragment.kt`
**`id` in `modules.json`:** `verificar` — `hasSwitch: false`, `category: "system"`,
`type: "Diagnóstico"`, `requiresProot: false`, `estimate: "<10 s"`, `terminalCommand: "verificar"`

---

## 1. Overview

`verificar.sh` reads the registry (`~/.android_server_registry`) to learn which modules declare
`installed=true`, and checks **each one live** against the real filesystem using the strategy
that fits how that module installs its binary. The goal is to detect the case
"installed on paper but broken in practice" — for example, the user deleted a folder by
hand, or a `pkg remove` broke an installation — something the registry alone cannot
detect, because it only reflects what the installer wrote when it finished, not the current state of
the filesystem.

It is a port of the `list` command from an external Termux management project — it reuses the same
concept of "6 verification strategies" (`_check_cmd`/`_check_pkg`/`_check_dir`/
`_check_file`/`_check_plugin`/`_check_grep_config` in the original) adapted to the registry and to
Kairos's own module catalog.

**It modifies nothing**: it only reads the registry and the filesystem.

## 2. Permissions

- **Android**: none specific — it runs in the wizard's same Termux process, with no additional
  permissions.
- **Termux internal**: read-only access to `~/.android_server_registry` and query-only commands
  (`command -v`, `dpkg -s`, `test -d`/`-f`, `grep`) — it installs no packages, downloads nothing, and has
  no network.

## 3. The 6 verification strategies

Each strategy is implemented as a one-line function that returns `0` (verified OK) or
`1` (fails), with no output of its own — the caller (`verify_one`) decides what to print:

| Strategy | Function | Real command | What it confirms |
|---|---|---|---|
| `cmd` | `_ver_cmd()` | `command -v "$1"` | The binary is on the `PATH` (CLIs installed via npm/curl/own installers) |
| `pkg` | `_ver_pkg()` | `dpkg -s "$1" \| grep -q "Status: install ok installed"` | The Termux `pkg`/`apt` package is still registered as installed |
| `dir` | `_ver_dir()` | `test -d "$1"` | A folder exists (proot-distro rootfs, configs, plugins) |
| `file` | `_ver_file()` | `test -f "$1"` | A file exists (wrappers, keystores, tokens) |
| `plugin` | `_ver_plugin()` | `test -d "$1"` | A zsh plugin's folder exists (`~/.zsh-plugins/<name>`) — same logic as `dir`, with its own name for readability |
| `config` | `_ver_config()` | `[ -f "$2" ] && grep -qF "$1" "$2"` | A config file exists AND contains a specific marker line (`$1`=pattern, `$2`=file) |

## 4. Module → strategy table (`MOD_STRAT`)

`verificar.sh` ships a hardcoded Bash associative array (`declare -A MOD_STRAT`) — format
`"id|strategy|target"` (for `config` the target is `"pattern|file"`):

| id | Strategy | Target |
|---|---|---|
| `ollama` | cmd | `ollama` |
| `n8n` | cmd | `n8n` |
| `python` | cmd | `python3` |
| `claude` | cmd | `claude` |
| `codex` | cmd | `codex` |
| `antigravity` | cmd | `agy` |
| `openclaw` | cmd | `openclaw` |
| `opencode` | cmd | `opencode` |
| `hermes` | cmd | `hermes` |
| `remote` | cmd | `cloudflared` |
| `ssh` | cmd | `cloudflared` |
| `expo` | cmd | `expo` |
| `engram` | cmd | `engram` |
| `freebuff` | cmd | `freebuff` |
| `codebuff` | cmd | `codebuff` |
| `copilotcli` | cmd | `copilot` |
| `minimaxcli` | cmd | `mmx` |
| `mimocode` | cmd | `mimo` |
| `mistralvibe` | cmd | `vibe` |
| `qwencode` | cmd | `qwen` |
| `ciberseguridad` | cmd | `nmap` |
| `entorno` | cmd | `proot-distro` |
| `db` | cmd | `mariadbd` |
| `stacks` | cmd | `proot-distro` |
| `llamaserver` | cmd | `llama-server` |
| `cactus` | cmd | `cactus` |
| `ide` | cmd | `nvim` |
| `apk` | cmd | `compil-apk-termux` |
| `kimi` | cmd | `kimi` |
| `kilo` | cmd | `kilo` |
| `cursor` | cmd | `cursor-agent` |
| `hf` | cmd | `hf` |
| `codegraph` | cmd | `codegraph` |
| `ohmypi` | cmd | `omp` |
| `pi` | cmd | `pi` |
| `mysql` | cmd | `mariadbd` |
| `postgres` | cmd | `postgres` |
| `sqlite` | cmd | `sqlite3` |
| `redis` | cmd | `redis-server` |
| `kairos` | dir | `$HOME/kairos` |

`mysql`/`postgres`/`sqlite`/`redis` are sub-services of the `db` module, each with its own
`<id>.installed=true` key in the registry.

No entry currently uses the `pkg`/`file`/`plugin`/`config` strategies — they are implemented
and available, but the current Kairos module catalog is verified entirely with `cmd`
(binary on PATH) except `kairos` (folder, `dir`). A real `modules.json` catalog module
that does NOT appear in this table falls into `[SKIP]` — see §5.

## 5. `verify_one()` logic — 3 possible results

For each id to verify (`verify_one "$id"`):

1. If `$id` has no entry in `MOD_STRAT` → **SKIP** (return 2). It cannot be verified live
   because no strategy is defined for that module — it does not mean it is badly installed, only
   that `verificar.sh` does not yet know how to check it.
2. If it has an entry, it runs the matching `_ver_<strategy>` function against the target:
   - Returns `0` → **OK** (return 0): the registry said installed and the filesystem confirms it.
   - Returns `1` → **WARN** (return 1): the registry says `installed=true` but the live
     check fails — broken installation/manually deleted/partial `pkg remove`.

Each module's result is printed in a different format depending on the active mode (plain text
`[OK]`/`[WARN]`/`[SKIP]` in `--silent`, `log`/`warn` colors from `lib.sh` in interactive mode, or
accumulated as an object for the `JSON_MODULES` array in `--json`).

## 6. Flags and usage modes

| Flag | Effect |
|---|---|
| `--silent` | App mode: no banner or colors, `[OK]`/`[WARN]`/`[SKIP]` lines + `[STEP] RESUMEN: ...` summary |
| `--force` | Parsed but not used in the script's current logic (reserved — the script reinstalls nothing) |
| `--describe` | Prints the one-line JSON manifest and exits: `{"id":"verificar","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |
| `--all` | Checks all modules with `installed=true` in the registry (fallback: all known `MOD_STRAT` ids if the registry is empty) |
| `--json` | A single JSON object on stdout, with no free text mixed in — automatically implies `--silent` |
| `-h` / `--help` | Parsed (`HELP=true`) but the script prints no help and does not exit — a flag with no real effect today |
| `<id1> <id2> ...` (positional) | Checks **only** those ids, ignoring the registry (manual use: `bash verificar.sh ollama n8n`) |
| *(no flags/args)* | Default: all modules with `installed=true` in the registry; if the registry is empty, all known `MOD_STRAT` ids |

Positional args take priority over `--all` only if `--all` is not present (`if [ ${#ARGS[@]} -gt 0 ] && ! $ALL`); if both are passed, `--all` wins.

## 7. Output — 3 formats

**Interactive text** (without `--silent` or `--json`): ASCII banner with a 🔍 emoji, then one colored
line per module (`log`/`warn` from `lib.sh`), final summary with a `═══` separator and
green/yellow/dim colors for OK/WARN/SKIP.

**`--silent`**: no banner, one line per module:
```
[OK]   ollama  (cmd: ollama)
[WARN] n8n  registry dice installed pero falla (cmd: n8n)
[SKIP] pi — sin estrategia definida
```
and a final summary `[STEP] RESUMEN: $OK ok, $WARN warn, $SKIP skip ($total total)`.

**`--json`**: a single object on stdout, format
```json
{"total":N,"ok":N,"warn":N,"skip":N,
 "modules":[{"id":"ollama","status":"ok","strategy":"cmd","target":"ollama"}, ...]}
```
`status` ∈ `"ok" | "warn" | "skip"`. For `"skip"`, `strategy`/`target` are empty
strings. String values are escaped with `_json_escape()` (backslash and double quotes) before
being inserted into the JSON assembled by hand with `printf`/string concatenation — there is no JSON
library, it is manual construction.

If there is nothing to check (empty registry and no arguments), it exits early printing the
corresponding empty JSON/text without iterating anything.

## 8. Exit code

- `0` if `$WARN -eq 0` (everything checked is OK or SKIP).
- `1` if `$WARN -gt 0` (at least one module declared installed failed the live check).

This allows using `verificar.sh --all --json` in a pipeline and checking the exit code as a quick
"is anything broken?" signal, without having to parse the JSON.

## 9. Checkpoint and `lib.sh`

It sources `lib.sh` (same directory) for the shared functions `log`/`warn`/`error`/`info`/
`step` and the colors (`CYAN`/`BOLD`/`GREEN`/`YELLOW`/`DIM`/`NC`). It defines its own checkpoint at
`~/.install_verificar_checkpoint` with `mark_done()`/`check_done()` (standard pattern of
`modulos/*.sh`), but **no part of the current script invokes them** — they remain declared by
template convention but with no real use in the verification logic (which has no
sequential steps that need resuming after an interruption).

## 10. Real app screen (`VerificarFragment.kt`)

`getModuleId()` → `"verificar"`, `getModuleName()` → `"Verificación"`. It extends
`BaseModuleFragment` (dedicated Fragment, routed from `ModuleDetailNavigator.kt:46`).

Three cards:

**LIVE DIAGNOSTIC** — explanatory text + a "🔍 Verify all modules" button. When tapped
(`runVerification()`), it shows a `ProgressDialogController` ("Verifying modules… running the
6 strategies live") and runs in a separate `Thread`:
```
bash <TERMUX_HOME>/scripts/install/verificar.sh --all --json
```
via `ManagerNativeUtils.runShell(...)` with `timeoutSeconds = 40`. The script's exit code is **not**
used to decide the run's success/failure (it can intentionally be `1` if there are WARNs) —
it only checks that `stdout` is not empty and is parseable JSON (`JSONObject(stdout.trim())`); if
parsing fails or stdout is empty, the run is treated as an error (`outcome == null`).

**RESULTS** — a list of rows, one per module, generated by `renderResults()`. Fixed order:
`warn` first (the most useful to see at the top), then `skip`, `ok` last
(`statusOrder(status)`: warn=0, skip=1, ok=2). Each row (`buildResultRow`) shows:

| Status | Icon | Text |
|---|---|---|
| `ok` | ✅ | "Installed and verified" (green `kairos_green`) |
| `warn` | ⚠️ | "Registry says installed but the real binary is not there" (amber `kairos_amber`) |
| `skip` (or anything else) | ○ | "Installed (no verification strategy defined yet)" (gray `kairos_text3`) |

`skip` only happens when the registry already confirms `installed=true` but no strategy is
defined, so the module is in fact installed. There is no repair action from a WARN.

If `strategy` is not empty, a monospaced line `strategy: <strategy> → <target>` is added
below (e.g. `strategy: cmd → ollama`).

If `modules` comes back empty: a different message depending on whether the run failed entirely
("Verification failed — check that \"verificar\" is installed.") or the registry was
empty ("Nothing to verify (empty registry).").

**MAINTENANCE** — standard `BaseModuleFragment` buttons:
- "🔄 Update" → `updateModuleService`, success/failure toast pointing to
  `~/kairos_logs/install_verificar.log`.
- "🗑 Uninstall" → `ModuleController.uninstallModule("verificar")`, with `popBackStack()` on
  success.

If the module is not installed (`!isModuleInstalled()`), the standard "not installed" state is shown
with an install button (`installModuleInBackground`), like any other module.

## 11. `modules.json` entry

```json
{
  "id": "verificar",
  "name": "Verificación",
  "description": "Verifica en vivo que los módulos instalados sigan funcionando (6 estrategias: cmd, pkg, dir, file, plugin, config).",
  "repo": "Honkonx/kairos-lab",
  "script": "verificar.sh",
  "port": "",
  "size": "",
  "type": "Diagnóstico",
  "requiresProot": false,
  "estimate": "<10 s",
  "hasSwitch": false,
  "terminalCommand": "verificar",
  "arch": "bionic",
  "category": "system",
  "catalogVersion": "0.x",
  "installMethods": ["standard"],
  "requires": [],
  "recommended": false,
  "downloads": 0
}
```

`hasSwitch: false` because there is no persistent process to start/stop — the module only
"runs" when the verify button is tapped; it does not live in the background. `requiresProot: false` because the
script only performs checks in the native Termux environment (although some of the modules it
verifies do use proot, `verificar.sh` itself does not enter there).

`--describe-files` declares only the script and the `jq` dependency. There are no downloads or sha256.

## 12. Known limitations

- **`MOD_STRAT` is a static table, not derived from the real catalog** (`modules.json`) — a
  new module added to `modules.json` without also updating `MOD_STRAT` in `verificar.sh`
  ends up in `[SKIP]` (not a visible bug, but it reduces diagnostic coverage).
  Keeping both lists in sync is manual.
- **The `cmd` strategy only does `command -v`** (`_ver_cmd`): it does not run the binary (unlike
  `verify_binary_installed`), so a broken wrapper passes as OK.
- **`remote` and `ssh` check `cloudflared`, not `sshd`** (`MOD_STRAT[remote]="cmd|cloudflared"`):
  since in `ssh.sh` downloading cloudflared is optional (only a `warn` if it fails), a Remote with healthy SSH
  and no cloudflared would give a false WARN.
- **`--force` and `-h`/`--help` are parsed but do nothing** — they are read as valid flags
  (they do not fall into the `*` catch-all of positional args) but no branch of the script consults them
  after parsing.
- **The checkpoint (`~/.install_verificar_checkpoint`) and its `mark_done`/`check_done` functions
  are defined but never invoked** — a leftover from the standard `modulos/*.sh` template, with no
  functional impact.
- **The script never writes to the registry** — the header comment mentions that `--all` mode
  can "re-endorse" `installed=true`, but there is no `registry_write` or registry
  edit anywhere in the script: it is a discrepancy between the header comment and the real
  behavior.
- **Possible false WARNs with the `cmd` strategy** — if a module installs its binary outside the
  `PATH` that `verificar.sh` sees (for example an inactive Python venv), the check may
  report WARN even though the module works fine from its own flow — the `cmd` strategy
  knows nothing about virtual environment/activation context, only the `PATH` of the process running the script.
- `collect_installed` takes the prefix of each `installed=true` line: ids like `ssh`, `mysql`,
  `postgres` are reported separately; ids with no entry in `MOD_STRAT` fall into SKIP.

**Added on.** `modulos/verificar.sh` added on **2026-08-14**.
