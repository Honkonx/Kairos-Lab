# Cactus Needle

**Kairos module** — managed through the Kairos UI (`CactusFragment.kt`). Installation, status
and usage are handled by the app via `ProcessBuilder` → `modulos/cactus.sh`.

---

**Script:** `modulos/cactus.sh` — copy synced to `app/src/main/assets/scripts/cactus.sh`.
**Registry:** prefix `cactus.*`
**Resulting CLI:** `cactus`

---

## 1. Overview

Cactus Needle is a **local tool-calling engine** — a small model (45M parameters, ~28MB of
RAM) that translates a natural-language request into a function call from a declared catalog
(bash/python/file read-write/Engram memory), without needing a large AI model to decide *what*
to execute. It has 2 modes:

- **`cactus run "request"`** — needle decides the tool call directly and executes it, with no
  AI reasoner. Fast, deterministic, runs on-device with no network.
- **`cactus ai "request"`** — a reasoner (Ollama `:11434` or llama-server `:8085`, via an
  OpenAI-compatible endpoint) first interprets the request into a short instruction, and needle
  then translates it into the tool call. If no reasoner is available, it automatically falls
  back to direct mode.
- **`cactus extract <schema> "text"`** — structured extraction (needle treats the schema as a
  single declared tool; its "arguments" are the extracted fields).
- **`cactus schemas`** / **`cactus tools`** / **`cactus status`** — introspection.

**Real tool catalog** (`~/scripts/cactus/cactus_engine.py`): `run_bash`, `run_python`,
`read_file`, `write_file`, `list_dir`, `system_info`, `engram_remember`, `engram_recall` (these
last 2 delegate to the Engram module if it's installed).

## 2. Architecture — jaxlib and Bionic libc

`cactus-needle` (the PyPI package) isn't pure Python: it depends on
`jax`+`jaxlib`+`flax`+`optax`. `jaxlib` is a compiled library (XLA in C++) that on PyPI only
publishes wheels tagged `manylinux_*_aarch64` (glibc) — Termux runs on **Bionic libc**
(Android), which those wheels don't satisfy. A native `pip install cactus-needle` on Termux
always fails when resolving `jaxlib` (ends in `ResolutionImpossible`, not a plain "no matching
distribution").

**The script self-heals**: if the native `pip` fails, `cactus.sh` automatically attempts a
fallback to **proot-distro with a real glibc distro** (`ubuntu` by default) — it installs
`proot-distro` and the distro if needed, and installs `cactus-needle` inside that glibc
container, where `jaxlib`'s wheels do install. The chosen runtime (native `pip` vs `proot`) is
persisted in the registry (`cactus.runtime`) so future runs don't retry the path already known
to fail. The `cactus` wrapper in `$PREFIX/bin` delegates to the correct interpreter based on
that runtime (native Python or `proot-distro login ubuntu -- python3 ...`).

## 3. How it's installed (real steps in `cactus.sh`)

1. **Python 3** (if missing) — `pkg install python`.
2. **Native `python-numpy`** (via `pkg`, not pip) — cactus-needle depends on numpy; on Termux
   with a very recent Python, pip often doesn't have a published wheel yet and fails compiling
   from source. Installing Termux's native package avoids that problem.
3. **`cactus-needle` via pip** (`python3 -m pip install --break-system-packages
   cactus-needle`) — if `import needle` fails afterward, it triggers the automatic fallback to
   proot-distro/glibc described above.
4. **Engine + wrapper**: writes `~/scripts/cactus/cactus_engine.py` (the real engine, tool
   catalog + extraction logic) and the executable wrapper `cactus` in `$PREFIX/bin`.

Supports `--silent`/`--force`/`--describe` (standard contract of `modulos/*.sh`).

## 4. UI in Kairos (`CactusFragment.kt`)

Its own dedicated screen (not falling back to a generic Fragment), with 6 real cards.

### Screen controls

| Control | What it does | Why |
|---|---|---|
| STATUS card — Ollama (:11434)/llama-server (:8085) rows | Read-only, `checkPort()` on each load | The reasoner backend for `cactus ai` — if neither responds, the "With AI" section is disabled |
| "RUN WITHOUT AI" card — field + "▶ Run without AI" | `cactus run --json-only "<request>"` — needle decides the tool directly, no reasoner | — |
| "RUN WITH AI" card — field + "🧠 Run with AI" | `cactus ai --json-only "<request>"` — the reasoner (Ollama/llama-server) interprets first, needle translates and executes | Disabled (dimmed field + button, visible note) if no reasoner backend is available |
| "EXTRACT DATA" card — "📋 Choose schema (by category)" button + field + "🔎 Extract" | `cactus extract <schema> "<text>" --json-only` — structured extraction | 8 real schemas, grouped by category in a dialog |
| TASKS card — "☰ Templates" / "＋ New task" / ▶ and 🗑 per row | Tasks persisted in `~/.cactus_tasks.json`, manual on-demand execution | Explicit MVP: no real scheduler, by design |
| "☰ Templates" | 5 fixed templates tied to `cactus_engine.py`'s real catalog (system_info, list_dir, engram_recall, engram_remember, read_file) — pre-fill "New task", editable before saving | The user doesn't have to write the request from scratch every time |
| "TOOL CATALOG" card — "🧰 View catalog" | `cactus tools` — lists the functions needle can execute | — |
| SCRIPTS card — "⬇ Export scripts" / "⬆ Import scripts" | Exports/imports `cactus_engine.py` + `.cactus_tasks.json` as JSON to `Download/KairosCactus/` | Backing up or migrating between installs |
| "💻 Open in terminal" | `launchTerminalCommand("cactus status")` | Escape hatch to the real terminal for what the UI doesn't cover |

## 5. Extraction templates (8 schemas)

`EXTRACT_SCHEMAS` in `cactus_engine.py` (embedded inside `modulos/cactus.sh`) — needle doesn't
have a closed list of schemas supported by the real engine: any valid JSON schema works the
same, so these are curated use cases from Kairos, not new capabilities of the engine.

| Category | Schema (`id`) | Fields |
|---|---|---|
| Business documents | `invoice` | vendor, total, due_date |
| Business documents | `receipt` | merchant, total, currency, line_items |
| Business documents | `purchase_order` | vendor, po_number, items, total |
| Business documents | `quote` | client, items, total, valid_until |
| Identification and contact | `business_card` | name, company, title, email, phone |
| Identification and contact | `contact` | name, email, phone, company |
| Productivity | `meeting_notes` | topic, date, attendees, action_items |
| Productivity | `event` | title, date, location, organizer |

`CactusFragment.kt` (`showExtractSchemaDialog()`) keeps a parallel Kotlin catalog
(`EXTRACT_SCHEMA_CATALOG`) — there's no way to read the real Python dictionary from Kotlin at
build time, so if a new schema is added to `cactus_engine.py`, it also has to be added there by
hand.

## 6. No scheduler by design

Neither needle's real engine nor Kairos's "TASKS" card have a scheduler. Needle's real tool
catalog (`run_bash`,`run_python`,`read_file`,`write_file`,`list_dir`,`system_info`,
`engram_remember`,`engram_recall`) includes no timer/cron tool — tasks saved on the "TASKS"
card run on demand (▶ button), never on their own.

If real scheduled execution is needed, the correct path isn't adding scheduling to Cactus —
it's using Linux/Termux's real cron mechanism externally (`pkg install cronie` + `crond`, or
`termux-job-scheduler` from the `termux-api` package for tasks tied to Android's lifecycle) to
trigger a script that in turn calls `cactus run`/`cactus extract` or directly the command of
whichever module you want to run.

## 7. Opt-in HTTP server (`cactus serve`) — orchestration with n8n

Cactus used to be a pure CLI with no API — n8n had no way to trigger it. `cactus serve [--port
8977]` (a command in `cactus_engine.py`) exposes a very lightweight HTTP server using only
Python's stdlib `http.server`/`BaseHTTPRequestHandler` (no new dependencies) — the real engine
(`needle`) doesn't change, it's just a transport layer over the same logic the CLI already
uses.

**Off by default (opt-in)** — the user enables it from the "HTTP SERVER" switch in
`CactusFragment.kt` (same mechanism as other server modules: start/stop via tmux +
`~/scripts/cactus/start.sh`/`stop.sh`). `modules.json` deliberately keeps `hasSwitch: false`
for Cactus — the module's *primary* function is still CLI/tool-calling on demand; the HTTP
server switch lives only inside Cactus's own screen.

**Single endpoint**: `POST http://127.0.0.1:<port>/run` (default port `8977`). It only listens
on `127.0.0.1` (not LAN) — still reachable from n8n, because n8n shares the host's network
namespace.

**Expected JSON body** — same 3 modes the CLI already supports:
```json
{"mode": "directo", "query": "list the files in ~/proyectos"}
{"mode": "ia", "query": "...", "model": "qwen2.5:1.5b (optional)"}
{"mode": "extract", "schema": "invoice", "text": "..."}
```
Response: the same JSON already returned by `cactus run/ai/extract --json-only` via the CLI.

**Auth**: `X-Cactus-Token: <token>` header, required on every request. The token is generated
once (`secrets.token_hex(24)`) and persisted at `~/.cactus_http_token`. Compared with
`hmac.compare_digest` (not `==`), as a timing-attack protection. The "🔑 View access token"
button in `CactusFragment.kt` shows it exactly as it is on disk (it doesn't generate it — it's
generated the first time `cactus serve` runs).

**n8n node example**:
```
POST http://127.0.0.1:8977/run
Header: X-Cactus-Token: <value from ~/.cactus_http_token>
Body:   {"mode": "directo", "query": "{{$json.pedido}}"}
```
