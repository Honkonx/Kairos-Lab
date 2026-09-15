# Verify (`verificar`)

**Kairos module** — managed from the UI (Modules tab, `VerificarFragment`). It's a **cross-cutting diagnostic tool**: it doesn't install/manage a service of its own, but instead audits live whether the other modules the registry marks `installed=true` still have their real binary/folder/package on the filesystem. It doesn't replace each individual module's install/update flow — it complements it.

---

**Script:** `modulos/verificar.sh` — mirrored at `app/src/main/assets/scripts/verificar.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/VerificarFragment.kt`
**`id` in `modules.json`:** `verificar` — no switch, system category, diagnostic type, no proot required, terminal command `verificar`

---

## 1. Overview

`verificar.sh` reads the registry (`~/.android_server_registry`) to find out which modules declare `installed=true`, and checks **each one live** against the real filesystem using whichever strategy fits how that module installs its binary. The goal is to catch the "installed on paper but broken in practice" case — for example, the user manually deleted a folder, or a partial package uninstall broke an install — something the registry alone can't detect, since it only reflects what the installer wrote when it finished, not the current truth on the filesystem.

It reuses the concept of "6 verification strategies" (command in PATH, system package, directory, file, plugin, config pattern) adapted to the registry and to Kairos's own module catalog.

**It doesn't modify anything** in normal mode: it only reads the registry and the filesystem. The only possible write happens in `--all` mode (see §4), where the script's header comment mentions it may "re-endorse" `installed=true` in the registry — see the note in §12 about this discrepancy.

## 2. Permissions

- **Android**: none specific — runs in the same Termux process, no additional permissions.
- **Internal Termux**: only reads `~/.android_server_registry` and runs read-only commands (`command -v`, `dpkg -s`, `test -d`/`-f`, `grep`) — doesn't install packages, doesn't download anything, has no network access.

## 3. The 6 verification strategies

Each strategy is implemented as a one-line function that returns `0` (verified OK) or `1` (failed), with no output of its own:

| Strategy | Real command | What it confirms |
|---|---|---|
| `cmd` | `command -v "$1"` | The binary is on `PATH` (CLIs installed via npm/curl/their own installers) |
| `pkg` | `dpkg -s "$1" \| grep -q "Status: install ok installed"` | Termux's `pkg`/`apt` package is still registered as installed |
| `dir` | `test -d "$1"` | A folder exists (proot-distro rootfs, configs, plugins) |
| `file` | `test -f "$1"` | A file exists (wrappers, keystores, tokens) |
| `plugin` | `test -d "$1"` | A zsh plugin folder exists — same logic as `dir`, its own name for readability |
| `config` | `[ -f "$2" ] && grep -qF "$1" "$2"` | A config file exists AND contains a specific marker line (`$1`=pattern, `$2`=file) |

## 4. Module-to-strategy table (`MOD_STRAT`)

`verificar.sh` ships a hardcoded Bash associative array with 30+ entries — format `"id|strategy|target"` (for `config` the target is `"pattern|file"`), checked against what each `modulos/*.sh` actually installs:

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
| `kairos` | dir | `$HOME/kairos` |
| `codegraph` | cmd | `codegraph` |
| `ohmypi` | cmd | `omp` |
| `pi` | cmd | `pi` |
| `mysql` | cmd | `mariadbd` |
| `postgres` | cmd | `postgres` |
| `sqlite` | cmd | `sqlite3` |
| `redis` | cmd | `redis-server` |

Note: no entry currently uses the `pkg`/`file`/`plugin`/`config` strategies — they're implemented and available, but Kairos's current module catalog is verified entirely via `cmd` (binary on PATH) except `kairos` (folder, `dir`). A module from the real `modules.json` catalog that doesn't appear in this table falls into `[SKIP]` — see §5.

## 5. `verify_one()` logic — 3 possible results

For each id being verified:

1. If `$id` has no entry in `MOD_STRAT` → **SKIP**. It can't be verified live because there's no defined strategy for that module — this doesn't mean it's badly installed, just that `verificar.sh` doesn't know how to check it yet.
2. If it has an entry, runs the corresponding function against the target:
   - Returns `0` → **OK**: the registry said installed and the filesystem confirms it.
   - Returns `1` → **WARN**: the registry says `installed=true` but the live check fails — broken install, manually deleted, or a partial package removal.

Each module's result is printed in a different format depending on the active mode (plain text `[OK]`/`[WARN]`/`[SKIP]` in `--silent`, colors in interactive mode, or accumulated as an object into the `JSON_MODULES` array in `--json`).

## 6. Flags and usage modes

| Flag | Effect |
|---|---|
| `--silent` | App mode: no banner or colors, `[OK]`/`[WARN]`/`[SKIP]` lines + a `[STEP] RESUMEN: ...` summary |
| `--force` | Parsed but unused in the current logic (reserved — the script doesn't reinstall anything) |
| `--describe` | Prints the one-line JSON manifest and exits |
| `--all` | Verifies every module with `installed=true` in the registry (fallback: every known id in `MOD_STRAT` if the registry is empty) |
| `--json` | A single JSON object on stdout, no free-text mixed in — implies `--silent` automatically |
| `-h` / `--help` | Parsed but the script doesn't print help or exit — a flag with no real effect today |
| `<id1> <id2> ...` (positional) | Verifies only those ids, ignoring the registry (manual use: `bash verificar.sh ollama n8n`) |
| *(no flags/args)* | Default: every module with `installed=true` in the registry; if the registry is empty, every known id in `MOD_STRAT` |

Positional args take priority over `--all` only if `--all` isn't present; if both are passed, `--all` wins.

## 7. Output — 3 formats

**Interactive text** (no `--silent` or `--json`): ASCII banner, then one colored line per module, a final summary with a separator and green/yellow/gray colors for OK/WARN/SKIP.

**`--silent`**: no banner, one line per module:
```
[OK]   ollama  (cmd: ollama)
[WARN] n8n  registry says installed but fails (cmd: n8n)
[SKIP] pi — no strategy defined
```
plus a final summary `[STEP] RESUMEN: $OK ok, $WARN warn, $SKIP skip ($total total)`.

**`--json`**: a single object on stdout, format
```json
{"total":N,"ok":N,"warn":N,"skip":N,
 "modules":[{"id":"ollama","status":"ok","strategy":"cmd","target":"ollama"}, ...]}
```
`status` ∈ `"ok" | "warn" | "skip"`. For `"skip"`, `strategy`/`target` remain empty strings. String values are escaped (backslash and double quotes) before being inserted into the JSON built by hand with `printf`/string concatenation — there's no JSON library, it's manual construction.

If there's nothing to verify (empty registry and no arguments), it exits early printing the corresponding empty JSON/text without iterating over anything.

## 8. Exit code

- `0` if `$WARN -eq 0` (everything verified is OK or SKIP).
- `1` if `$WARN -gt 0` (at least one module declared installed failed the live check).

This lets `verificar.sh --all --json` be used in a pipeline and its exit code checked as a quick "is anything broken?" signal, without having to parse the JSON.

## 9. Checkpoint and shared library

Sources `lib.sh` (same directory) for the shared logging and color functions. Defines its own checkpoint, but no part of the current script actually calls it — it was declared following the template's convention but has no real use in the verification logic (which has no sequential steps that would need resuming after an interruption).

## 10. App screen (`VerificarFragment.kt`)

Extends `BaseModuleFragment`. Three cards:

**LIVE DIAGNOSTIC** — explanatory text + "Verify all modules" button. Tapping it shows a progress indicator ("Verifying modules… running the 6 strategies live") and runs on a separate thread:
```
bash <TERMUX_HOME>/scripts/install/verificar.sh --all --json
```
with a 40-second timeout. The script's exit code isn't used to decide whether the run succeeded (it can legitimately be `1` if there are WARNs) — it only checks that `stdout` isn't empty and is parseable JSON; if parsing fails or stdout comes back empty, it's treated as a run error.

**RESULTS** — a list of rows, one per module. Fixed order: `warn` first (most useful to see at the top), then `skip`, `ok` last. Each row shows:

| Status | Text |
|---|---|
| `ok` | "Installed and verified" |
| `warn` | "Registry says installed but the real binary isn't there" |
| `skip` | "Installed (no verification strategy defined yet)" |

If `strategy` isn't empty, a monospaced line `strategy: <strategy> → <target>` is added below.

If `modules` comes back empty: a different message depending on whether the run failed entirely or the registry was empty.

**MAINTENANCE** — standard buttons:
- "Update" — reinstalls/updates the module.
- "Uninstall" — returns to the previous screen on success.

If the module isn't installed, the standard "not installed" state is shown with an install button, like any other module.

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

`hasSwitch: false` because there's no persistent process to start/stop — the module only "runs" when the verify button is tapped, it doesn't live in the background. `requiresProot: false` because the script only does checks in the native Termux environment (even though some of the modules it verifies do use proot, `verificar.sh` itself never goes in there).

## 12. Architecture notes

- **`MOD_STRAT` is a static table, not derived from the real catalog** (`modules.json`) — a module newly added to `modules.json` without also updating `MOD_STRAT` in `verificar.sh` falls silently into `[SKIP]` (not a visible bug, but it reduces diagnostic coverage). Keeping both lists in sync is manual.
- **`--force` and `-h`/`--help` are parsed but do nothing** — they're read as valid flags but no branch of the script consults them afterward.
- **The checkpoint is defined but never invoked** — a holdover from the standard `modulos/*.sh` template, with no functional impact since there are no steps that need resuming.
- **The script never writes to the registry in the mode the app uses** (`--all --json`) — the header comment mentions that `--all` mode can "re-endorse" `installed=true`, but reviewing the script's actual logic there's no registry write or edit anywhere — this is a discrepancy between the header comment and the real behavior, documented here so it isn't assumed to exist without checking again if the script is touched in the future.
- **Possible false WARNs with the `cmd` strategy** — if a module installs its binary outside the `PATH` that `verificar.sh` sees (for example, an unactivated Python virtual environment), the check can report WARN even though the module works fine from its own flow — the `cmd` strategy has no awareness of virtual-environment/activation context, only the `PATH` of the process running the script.
