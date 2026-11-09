# Cactus Needle — Kairos Module

**Kairos Module** — managed through the Kairos UI (`CactusFragment.kt`, 1063 lines). Installation, state and use are handled by the app via `ProcessBuilder` → `modulos/cactus.sh`.

---

**Script:** `modulos/cactus.sh` (v1.5.1, 1358 lines; includes `cactus_engine.py` embedded in a heredoc) — synced copy at `app/src/main/assets/scripts/cactus.sh` (same SHA256).
**Registry:** prefix `cactus.*`
**Resulting CLI:** `cactus`
**`modules.json`:** `hasSwitch: false` on purpose (`:792`), `terminalCommand: "cactus"` (`:793`)

---

## 1. What it is and what it is for

Cactus Needle is a **local tool-calling** engine (`cactus-needle`, a 45M-parameter Needle model, ~28MB of RAM): it turns a natural-language request into a call to a tool from a fixed catalog and runs it, without needing a large AI to decide *what* to run. Modes (CLI parser at `cactus.sh:1159-1205`):

- **`cactus run "request"`** — needle decides the tool call directly and runs it, with NO AI reasoner. Fast, deterministic, runs on the device without network.
- **`cactus ai "request"`** — a reasoner (Ollama `:11434` or llama-server `:8085`, via an OpenAI-compatible endpoint) first interprets the request into a short instruction, and then needle translates it into the tool call. If no reasoner is available, it automatically falls back to direct mode.
- **`cactus extract <schema> "text"`** — structured extraction (needle treats the schema as a single declared tool; its "arguments" are the extracted fields). 8 schemas (see §6).
- **`cactus serve`** — opt-in HTTP server (see §8).
- **`cactus schemas`** / **`cactus tools`** / **`cactus status`** — introspection.

**Real tool catalog** (`~/scripts/cactus/cactus_engine.py`, `cactus.sh:1348`): `run_bash`, `run_python`, `read_file`, `write_file`, `list_dir`, `system_info`, `engram_remember`, `engram_recall` (the last two delegate to the Engram module if installed).

**Classification: FUNCTIONAL MODULE.** Own screen (`ModuleDetailNavigator.kt:42`, does not fall into `GenericModuleFragment`), 4 tabs, backend status, execution with/without AI, extraction, persisted tasks, HTTP server with switch and token. ~17 controls. No Update/Uninstall/Diagnose on this screen (it is uninstalled from Settings).

## 2. Architecture — why it runs via proot-distro/glibc

`cactus-needle` 2.x no longer depends on jax/jaxlib; the cause of the failure on Bionic is that Needle downloads its native library from HuggingFace when instantiating `Needle()` and its platform detection does not recognize Android → it downloads a manylinux/glibc wheel, which Termux (Bionic libc) cannot satisfy. That is why `_needle_importa_ya()` **instantiates** `Needle(...)` instead of just `import needle` (`cactus.sh:289-312`): verifying that it "imports" is not enough, you have to prove it really works.

**The script self-repairs**: if native `pip` fails, `cactus.sh` automatically runs a fallback to **proot-distro with a real glibc distro** (`ubuntu` by default; `_install_cactus_glibc_fallback`, `:325-370`) — it installs `proot-distro` and the distro if needed, and installs `cactus-needle` INSIDE that glibc container. The chosen runtime (native `pip` vs `proot`) is persisted in the registry (`cactus.runtime`) so as not to retry the path already known to fail. The `cactus` wrapper in `$PREFIX/bin` delegates to the right interpreter according to that runtime (native Python or `proot-distro login ubuntu -- python3 ...`; `:1235-1274`). On a real device it was confirmed that `cactus.installed=true`, `cactus.runtime=proot`, `cactus.glibc_distro=ubuntu`. The native pip path (without proot) still does not work on Bionic; everything depends on proot-distro (large weight and installation time).

## 3. Installation (`modulos/cactus.sh`)

- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`:214-217`).
- Steps (`:406-1334`): 1) Python (`pkg install python`) 2) native `python-numpy` via `pkg` (not pip; on Termux with a very recent Python, pip usually has no wheel and fails compiling from source) 3) `python3 -m pip install --break-system-packages cactus-needle` + real test; on failure → proot-distro/glibc fallback (§2) 4) writes `~/scripts/cactus/cactus_engine.py` + wrapper `$PREFIX/bin/cactus` 5) the HTTP server's `start.sh`/`stop.sh` (`:1287-1331`) and registry.
- Verification: real post-condition (`_needle_importa_ya`, `timeout 30`). That same test is used in the "already installed" check (`:392`, `if command -v cactus … && _needle_importa_ya`) and may need network (HuggingFace) to pass (inference, not verified).
- Registry (`registry_write cactus …`, `:1343-1355`): `installed`, `version`, `arch`, `engine`, `tools`, `modes`, `extract_schemas`, `http_server_port=8977`, `runtime` (pip|proot), `glibc_distro`, `install_date`. "Installed" detection in the app: `command -v cactus` (`ModuleInstalled.kt:90,240`, `LiveCheck.PATH_BINARY`) — that only confirms the wrapper exists, not that Needle works.

## 4. Execution

- On-demand CLI: the UI invokes `TERMUX_CACTUS_PATH` (`$PREFIX/bin/cactus`) by argv with `ManagerNativeUtils.runExec(..., 60 s)` (`CactusFragment.kt:272,281-283`), with no intermediate shell.
- Opt-in HTTP server: tmux session `cactus-server` (`ModuleController.kt:1204`), port 8977 (`:1246`), `start.sh` = `cactus serve --port ${CACTUS_SERVE_PORT:-8977}` with log `~/kairos_logs/cactus_serve.log` (`cactus.sh:1296-1313`), listening **only on 127.0.0.1** (`:1132`). `stopAllModules` does NOT include `cactus` (`ModuleController.kt:322`), so "stop all" leaves the server running.

## 5. UI (`CactusFragment.kt`) — 4 tabs

(`CactusFragment.kt:46-58,144-164`.)

| Control | What it does | Why |
|---|---|---|
| **Status** — Ollama (:11434)/llama-server (:8085) rows | Read-only, `checkPort()` on each load (`:173-213`) | Backend of the `cactus ai` reasoner — if none responds, the "With AI" section is disabled |
| **Run** — "▶ Run without AI" (field + button) | `cactus run --json-only "<request>"` — needle decides the tool directly, no reasoner | Direct argv (`ManagerNativeUtils.runExec`, no shell) |
| **Run** — "🧠 Run with AI" (field + button) | `cactus ai --json-only "<request>"` — the reasoner interprets first, needle translates and runs | Disabled (field + button dimmed, visible note) if `mAiBackendAvailable == false` |
| **Run** — "📋 Choose schema (by category)" + field + "🔎 Extract" | `cactus extract <schema> "<text>" --json-only` | 8 schemas grouped by category in a dialog; `EXTRACT_SCHEMA_CATALOG` is maintained by hand in parallel with the engine (`:357-368`) |
| **Tasks** — "☰ Templates" / "＋ New task" / ▶ and 🗑 per row | Tasks persisted in `~/.cactus_tasks.json`, manual on-demand execution (**no scheduler**) | Explicit MVP |
| "☰ Templates" | 5 fixed templates tied to the engine's real catalog (system_info, list_dir, engram_recall, engram_remember, read_file) — they pre-fill "New task", editable before saving | The user does not have to write the request from scratch |
| **Advanced** — "🧰 View catalog" | `cactus tools` — lists the functions needle can run | — |
| **Advanced** — "HTTP SERVER" switch | Starts/stops `cactus serve` via tmux (`:836-850`) | See §8 |
| **Advanced** — "🔑 View access token" | Shows the token as it is on disk (`:870-890`) | See §10 |
| **Advanced** — "⬇ Export scripts" / "⬆ Import scripts" | Exports/imports `cactus_engine.py` + `.cactus_tasks.json` as JSON to `/storage/emulated/0/Download/KairosCactus/` (`:923-1031`) | Back up or migrate between installations |
| **Advanced** — "💻 Open in terminal" | `launchTerminalCommand("cactus status")` | Escape hatch to a real terminal |

## 6. Extraction templates (8 schemas)

`EXTRACT_SCHEMAS` in `cactus_engine.py` (embedded in `modulos/cactus.sh`). Needle has no closed list of schemas supported by the engine ("a byte-level grammar compiled from your schemas" — any valid JSON schema works), so those in this table are Kairos use cases, not fixed engine capabilities.

| Category | Schema (`id`) | Fields |
|---|---|---|
| Business documents | `invoice` | vendor, total, due_date *(README example, "Quickstart")* |
| Business documents | `receipt` | merchant, total, currency, line_items *(README example, "Extraction")* |
| Business documents | `purchase_order` | vendor, po_number, items, total |
| Business documents | `quote` | client, items, total, valid_until |
| Identification and contact | `business_card` | name, company, title, email, phone |
| Identification and contact | `contact` | name, email, phone, company |
| Productivity | `meeting_notes` | topic, date, attendees, action_items |
| Productivity | `event` | title, date, location, organizer |

`CactusFragment.kt` (`showExtractSchemaDialog()`) maintains a parallel catalog in Kotlin (`EXTRACT_SCHEMA_CATALOG`) — there is no way to read the Python dictionary from Kotlin at build time, so when adding a new schema to `cactus_engine.py` it must also be added there by hand (risk of going out of sync).

## 7. Time-based scheduling — no, by design

Neither the needle engine nor Kairos' "TASKS" card has a scheduler: the tool catalog (`run_bash,run_python,read_file,write_file,list_dir,system_info,engram_remember,engram_recall`) includes no timer/cron tool, and saved tasks run on demand (the ▶ button), never on their own.

For something like "at 2pm run n8n", the right path is not to add scheduling to Cactus but to use the real Linux/Termux cron from outside (`pkg install cronie` + `crond`, or `termux-job-scheduler` from the `termux-api` package) to trigger a script that calls `cactus run`/`cactus extract` or directly the command of the module you want to run. (Kairos also has an Automations screen to trigger actions on modules by schedule/boot/battery; see `AUTOMATIZACIONES.md`.)

## 8. Opt-in HTTP server (`cactus serve`)

`cactus serve [--port 8977]` (a command in `cactus_engine.py`) exposes a very lightweight HTTP server using only Python's stdlib `http.server`/`BaseHTTPRequestHandler` (no new dependencies) — the real engine (`needle`) does not change, it is just a transport layer over the same logic the CLI uses (`_needle_run_core()`/`_extract_core()`, extracted from `run_needle()`/`run_extract()` to reuse them from both sides without duplicating code). It lets n8n or other local clients trigger Cactus.

**Off by default (opt-in)** — enabled from the "HTTP SERVER" switch in `CactusFragment.kt`, the same mechanism as `LlamaServerFragment` (`startModuleService`/`stopModuleService`/`isModuleRunning()` of `BaseModuleFragment`, which delegate to `ModuleController.kt` → tmux + `~/scripts/cactus/start.sh`/`stop.sh`, generated by `cactus.sh` STEP 5). `modules.json` keeps `hasSwitch: false` for Cactus on purpose — the global switch in the Modules list implies the module's *main* function is a background service (the case of Ollama/n8n/llama-server), and Cactus' main function is CLI/tool-calling on demand.

**Single endpoint**: `POST http://127.0.0.1:<port>/run` (default port `8977`, no collision with ollama 11434, n8n 5678, openclaw 18789, opencode 3000, remote 8022, llamaserver 8085, Cloud 8971). It listens only on `127.0.0.1` (not LAN) — reachable from n8n anyway, because n8n runs in udocker/PRoot and shares the host's network namespace.

**Expected JSON body** — the same 3 modes as the CLI:
```json
{"mode": "directo", "query": "list the files in ~/proyectos"}
{"mode": "ia", "query": "...", "model": "qwen2.5:1.5b (optional)"}
{"mode": "extract", "schema": "invoice", "text": "..."}
```
Response: the same JSON that `cactus run/ai/extract --json-only` returns from the CLI.

**Auth**: header `X-Cactus-Token: <token>`, mandatory on every request. The token is generated once (`secrets.token_hex(24)`) the first time `cactus serve` runs and persisted in `~/.cactus_http_token` (same pattern as `NubeServer.kt`/`.nube_token`). Compared with `hmac.compare_digest` (`:1082-1083`), the same anti-timing-attack criterion as `NubeServer.kt`. No token → 403.

**Example n8n node** (`HTTP Request`):
```
POST http://127.0.0.1:8977/run
Header: X-Cactus-Token: <value of ~/.cactus_http_token>
Body:   {"mode": "directo", "query": "{{$json.pedido}}"}
```

The full end-to-end flow (switch → tmux → real `cactus serve` → HTTP request from n8n) is not verified on a device; the Python engine was verified with `py_compile`.

## 9. Files and paths it creates

`$PREFIX/bin/cactus`, `~/scripts/cactus/cactus_engine.py`, `~/scripts/cactus/tools.idx` (tool embeddings index, next to the engine), `~/scripts/cactus/start.sh|stop.sh`, `~/.cactus_http_token`, `~/.cactus_tasks.json`, `~/kairos_logs/cactus_serve.log`, `~/.install_cactus_checkpoint` (deleted at the end), registry, and — if runtime=proot — the full proot-distro `ubuntu` distro (hundreds of MB) with `python3-pip` + `cactus-needle` inside.

## 10. Security

- **High functional risk by design**: `--json-only` implies auto-execution without confirmation (`cactus.sh:907`, `effective_auto = auto or json_only`), and the UI always uses `--json-only` (`CactusFragment.kt:281-283`). A request like "delete…" is interpreted by a 45M-parameter model and, if it picks `run_bash`/`run_python`/`write_file`, it runs **without a confirmation dialog** (`exec_tool`, `cactus.sh:805-`; `_run` = `bash -c`/`python3 -c`). The low-confidence threshold (0.45) only brakes in interactive CLI mode.
- HTTP server: loopback only; a 24-byte token compared with `hmac.compare_digest`. The endpoint runs bash/python through needle (acknowledged in comments, `:1029-1031`). The token file is created without an explicit `chmod 600` (`:1039-1043`) and `showServerTokenDialog` **shows it in clear** as many times as requested (`CactusFragment.kt:870-890`), which contradicts the principle of never re-displaying a stored secret; it is mitigated with `filterTouchesWhenObscured` (`:109-114`) but not with `FLAG_SECURE` (inference: no `setScreenSecure` was seen in this fragment).
- Any process/app on the same local network namespace that knows the token can trigger execution.
- Script export: writes the engine and tasks to `Download/` (public); if the user stored sensitive data in a task it remains in clear.
- Import: `importScripts` rewrites `cactus_engine.py` with the content of an imported JSON (`CactusFragment.kt:1005-1031`; whether it validates origin was not reviewed in depth) — a malicious JSON would replace the engine that is then executed (there is a `confirmImportScripts` dialog).

## 11. Known limitations

| Item | Type |
|---|---|
| Execution without confirmation from the UI (45M model picks `run_bash`) | Confirmed in code |
| `stopAllModules` does not stop `cactus-server` | Confirmed in code |
| HTTP token visible in clear + file without explicit chmod 600 | Confirmed in code |
| `EXTRACT_SCHEMA_CATALOG` (Kotlin) and `EXTRACT_SCHEMAS` (Python) are maintained by hand in parallel | Confirmed — risk of going out of sync |
| "Installed" = `command -v cactus` in the app, it does not validate Needle | Confirmed (code); impact inferred |
| The native pip path (without proot) still does not work on Bionic; everything depends on proot-distro | Per the script header; not re-tested |
| No Update/Uninstall/Diagnose on the screen | Confirmed in code |
| End-to-end `serve` flow not verified on a device | Pending |

## 12. Date added

**2026-08-13** (first commit of `modulos/cactus.sh`).
