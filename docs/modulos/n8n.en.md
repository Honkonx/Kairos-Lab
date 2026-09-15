# n8n

**Kairos module** — managed from the UI (Modules tab). Installation, start/stop and
status are handled by the app via `ProcessBuilder` → `modulos/n8n.sh`.

---

**Script:** `modulos/n8n.sh` — copy synced to `app/src/main/assets/scripts/n8n.sh`.
**Port:** `:5678`
**Registry:** prefix `n8n.*`

---

## 1. Overview

n8n is a workflow automation platform (visual node editor, self-hosted). In Kairos it
runs inside a full Linux environment (Debian, via `proot-distro`) or via `udocker` (a
rootless container, no proot) — it's the only module in the stack that still needs a
full Linux environment underneath (unlike OpenClaw/OpenCode, which run in native glibc
mode).

## 2. Permissions

None of its own beyond `INTERNET`. The proot variant doesn't need root — `proot-distro`
emulates the environment without elevated privileges.

## 3. Installation — `modulos/n8n.sh`

Accepts `--silent --variant <proot|udocker> [--source <clean|github|rootfs-github|rootfs-clean>] [--force]`.
`--describe` → `{"id":"n8n","supports_silent":true,"supports_force":true,"variants":["udocker","proot"],"variant_required":false,"variant_default":"udocker","extra_flags":[{"name":"source","applies_to_variant":"proot","values":["clean","github","rootfs-github","rootfs-clean"],"default":"clean"}]}` — **`udocker` is the default variant** when no explicit `--variant` is passed.

### Variants

| Variant | Environment | When to use it |
|---|---|---|
| `udocker` (default) | `n8nio/n8n` container (official image) via `udocker`, no proot | Real default — lighter alternative, no full distro |
| `proot` | Debian Bookworm ARM64 via `proot-distro` | Full environment, heavier — use when the complete underlying Linux system is needed |

`--source` (applies only to `proot`) controls where the Debian rootfs and n8n itself come
from: `clean` (fresh `proot-distro` + `npm install`, default), `github` (everything from
GitHub Releases), `rootfs-github`/`rootfs-clean` (mixed).

### Steps — `proot` variant (8 steps, checkpoint per step)

```
STEP 1/8  Termux update
STEP 2/8  proot-distro install debian (Bookworm ARM64)
STEP 3/8  Inside the proot: Node.js 22 LTS (setup_22.x) + npm install -g n8n
            + cloudflared (native ARM64 binary inside the proot, for the tunnel)
          Real verification: Node major >= 20 (aborts if nodesource failed
          silently), real exit code of "npm install -g n8n" captured
          with PIPESTATUS (doesn't trust the pipe with "tail"), "n8n --version"
          confirmed before continuing (aborts if the binary doesn't respond).
STEP 4/8  Control scripts → ~/scripts/n8n/:
            start_servidor.sh, stop_servidor.sh, url.sh, status.sh,
            backup.sh, cf_token.sh
STEP 5/8  Aliases in .bashrc (n8n-start/-stop/-url/-status/-backup, cf-token)
STEP 6/8  Auto-start on boot (Termux:Boot)
STEP 7/8  Registry
STEP 8/8  Cleanup
```

### Steps — `udocker` variant (8 steps, separate checkpoint `~/.install_n8n_udocker_checkpoint`)

```
STEP 0/7  udocker (with 2 fixed udockertools mirrors as fallback — the
          default dynamic source can fail on mobile networks/CGNAT)
STEP 1/7  Create n8nio/n8n container (udocker create)
STEP 2/7  Native Termux cloudflared (for the tunnel, outside the container)
STEP 3/7  Control scripts → ~/scripts/n8n-udocker/ (start.sh with a real
          health check via /healthz)
STEP 4/7  Aliases
STEP 5/7  Registry
STEP 6-7  Auto-start on boot + cleanup
```

### Real bugs already fixed

- **Container detection** — the `udocker` variant checks whether the container exists with `udocker inspect` instead of parsing `udocker ps` output, which doesn't list the name in a predictable column.
- **Real udockertools verification** — instead of trusting the exit code of a `grep` over the installer's output, it verifies that the `VERSION` file `udockertools` leaves on disk actually exists.
- **2 fixed udockertools mirrors** as a fallback — the default dynamic source can fail on mobile networks/CGNAT.
- **Node 22 LTS** — upgraded from Node 20 (end of support), n8n requires `>=20.19`.
- **Real error capture in pipes** — the Node.js install and `npm install -g n8n` check the real exit code of the command (not the last stage of the pipe, typically `tail`), so a failure isn't silently ignored.
- **Real health check against `/healthz`** in both variants before considering the start successful, instead of a fixed `sleep` with no confirmation that n8n is actually responding.
- **Version parsing in the udocker variant** — `udocker images` returns `REPO:TAG` in a single column; the script extracts the real tag instead of assuming 2 separate columns.
- **`udocker pull` always failed due to platform detection** — root cause: the Python bundled with Termux reports `platform.system() == "Android"` instead of `"Linux"`, and `udocker` uses that value to build the platform selector for the Docker Hub manifest — since no manifest exists for `android/arm64` (it isn't a real Docker platform), pulling the `n8nio/n8n` image failed 100% of the time, regardless of network. The fix passes `--platform=linux/arm64` explicitly to `udocker pull` to bypass the broken autodetection.

## 4. Status detection — `ModuleController.kt`

- `getTmuxSession("n8n")` → `"n8n-server"`.
- `getModulePort("n8n")` → `5678` — verified with `waitForPortOpen()` after a successful
  start.
- `getModuleStartScript("n8n")` → `$HOME/scripts/n8n/start_servidor.sh` (proot variant;
  the udocker variant uses its own `start.sh` under `~/scripts/n8n-udocker/`).

## 5. App screen — `N8nFragment.kt`

- STATUS card: environment (`proot`/`udocker`), network mode (local / Cloudflare
  tunnel), version, tunnel URL, status pill (running/stopped).
- Dropdown + switch for network mode and start/stop: the dropdown picks local/Cloudflare
  tunnel (`~/.n8n_local_only`), the switch controls start/stop. Changing mode requires
  stopping first. It's read by `start.sh` (udocker) and `start_servidor.sh` (proot)
  before bringing up the tunnel — applies only on the next start.
- "Open web interface (local)" — internal WebView pointing to `http://localhost:5678`
  (starts n8n if it isn't running).
- "View tunnel URL" — `cat ~/.last_cf_url`.
- **"Workflows (API)"** — lists the instance's real workflows via n8n's public REST API
  (`GET /api/v1/workflows`), with an active/inactive indicator. Tapping one opens a
  dialog with the "Activate"/"Deactivate" action (`POST
  /workflows/{id}/activate|deactivate`). If no API Key is configured, it warns and opens
  the configuration dialog instead of failing.
- **"n8n API Key"** — saves/deletes the API Key at `~/.n8n_api_key` (text prompt; the
  key is generated manually in the n8n UI, Settings → n8n API → Create an API key —
  there's no way to issue it via CLI without a logged-in session).
- "View logs" — real script depending on variant (`n8n_log.sh` proot / `log.sh`
  udocker).
- "Backup workflows" → runs `~/scripts/n8n/n8n_backup.sh` (proot) or
  `~/scripts/n8n-udocker/backup.sh` (udocker), depending on variant.
- "Manage projects" — same shared menu (symlink/copy/sync) used by
  Claude/Codex/OpenCode/Antigravity/OpenClaw/Hermes, with no project launcher of its own
  (n8n is a server, not a CLI that opens a folder).
- "Update n8n" → runs the real script for the given variant (`n8n_update.sh` proot /
  `update.sh` udocker).
- **"Cloudflare Token (fixed URL)"** — lets you set a real Cloudflare Tunnel token to
  get a permanent public URL instead of the temporary one that changes on every restart.
  The token is saved via `TunnelManager` (the same backend as the Tunnel tab) with id
  `"n8n"`, an internal bridge with the rest of the tunnel config.
- **"Configure webhook domain"** — sets `N8N_WEBHOOK_URL` in `~/.env_n8n` so webhooks
  are built with a custom domain instead of the temporary `*.trycloudflare.com`
  subdomain. The (bare, schemeless) domain is saved via `TunnelManager` with id `"n8n"` —
  `~/.env_n8n` still exists as a real mirror (the n8n process still reads that file to
  start).
- **"Switch HTTP/HTTPS protocol"** — toggles `~/.n8n_protocol` (applies only to proot
  mode; writing the file is harmless if the user is on udocker).
- **"Repair control scripts"** — regenerates ONLY start/stop/log/status/update/backup
  (depending on the installed variant) via `modulos/n8n.sh --repair-scripts --silent`,
  without touching the container/rootfs or the user's workflows/credentials.
- **"View recent executions"** — `GET /api/v1/executions` to see whether the last run
  failed without opening the full WebView. The `status` field is sometimes missing from
  the listing (a known quirk of the n8n API) — it falls back to `finished` as a default.

Silent background install if the module isn't installed: a dialog lets you pick the
variant (`udocker`/`proot-distro`), installs without blocking navigation.

## 5b. Workflows API (`N8nApiClient.kt`)

`app/src/main/java/com/termux/app/util/N8nApiClient.kt` — same HTTP pattern as
`OllamaApiClient` (`HttpURLConnection` + `org.json`, no external library). Talks to
`http://127.0.0.1:5678/api/v1` using the `X-N8N-API-KEY` header. Functions:
`hasApiKey()`/`readApiKey()`/`writeApiKey()` (persisted at `~/.n8n_api_key`),
`listWorkflows()` (`GET /workflows?limit=100`) and `setActive(id, active)` (`POST
/workflows/{id}/activate` or `.../deactivate`). All functions are blocking — the caller
is responsible for running them on its own `Thread`.

## 6. Cloudflare Tunnel

Without a token: **temporary** tunnel (`*.trycloudflare.com`, changes on every restart)
— extracted from `cf_url.log`. With a token: **fixed** tunnel, same URL always.

**Token/domain storage architecture**: n8n keeps its own "Cloudflare Token (fixed URL)"
button on its screen, but the real value is stored in `TunnelManager`
(`app/src/main/java/com/termux/app/util/TunnelManager.kt`) with `moduleId = "n8n"` — the
same registry keys used by the Tunnel tab, prefixed with the module id
(`tunnel.n8n.cloudflare.token`/`.domain`, instead of the global `tunnel.cloudflare.*`
keys used by the generic quick buttons). Remote/SSH follows the same pattern with
`moduleId = "remote"` (token only, SSH is raw TCP — no real domain field).

`~/.env_n8n` (with `N8N_WEBHOOK_URL=...`) still exists and is still needed — the n8n
process itself (not the Kairos UI) reads that file to start with the configured domain —
but it has become a mirror that gets rewritten every time the domain is saved, not the
source of truth the UI re-reads to show the current state (that's covered by
`TunnelManager.getConfig("cloudflared", "n8n")`).

On the Tunnel tab, n8n's own tunnel (started by `modulos/n8n.sh` alone, not by
`TunnelManager`) is shown with its real status (active/inactive, URL if any) but without
start/stop buttons from there — the real control for that tunnel lives on the n8n
screen; Tunnel is just a visibility panel for this case.

## 7. Registry (`~/.android_server_registry`)

```
n8n.installed=true
n8n.version=<real version>
n8n.install_date=YYYY-MM-DD
n8n.mode=proot|udocker
n8n.port=5678
```

## 8. Reference commands

```bash
n8n-start     # alias -> start_servidor.sh (proot) or start.sh (udocker)
n8n-stop
n8n-url       # real URL of the active tunnel
n8n-status
n8n-backup    # workflow backup
cf-token <token>   # set the Cloudflare Tunnel token
```
