# n8n

**Kairos module** — managed through the Kairos UI (Modules tab). Installation, start/stop and state are handled by the app via `ProcessBuilder` → `modulos/n8n.sh`.

---

**Script:** `modulos/n8n.sh` (v4.0.0, 1452 lines) — synchronized copy in `app/src/main/assets/scripts/n8n.sh`.
**Port:** `:5678`
**Registry:** prefix `n8n.*`

> Convention: **confirmed** = verified by reading the code; **unverified** = inference without a runtime test.

---

## 1. Overview

n8n is a workflow automation platform (visual nodes, self-hosted) served on `:5678`. In Kairos it runs inside a full Linux environment (Debian, via `proot-distro`) or via `udocker` (a rootless container, no proot) — it is the only module in the stack that still needs a full Linux environment underneath (unlike OpenClaw/OpenCode, migrated to native glibc). Kairos starts/stops it from the app and manages it (Cloudflare tunnel, webhook, workflows via REST API). Module added: 2026-07-19.

Classification: **functional module** — own UI (`N8nFragment`, mapped in `ModuleDetailNavigator.kt:30`), a service with state (start/stop/tmux/port in `ModuleController.kt:1163,1185,1198,1236`), its own registry and real options (~15 controls).

## 2. Permissions

None of its own on Android beyond `INTERNET`. The proot variant does not need root — `proot-distro` emulates the environment without elevated privileges.

## 3. Installation — `modulos/n8n.sh`

Flags (`n8n.sh:65-71`): `--silent`, `--force`, `--describe`, `--describe-files`, `--repair-scripts`, `--source <clean|github|rootfs-github|rootfs-clean>`, `--variant <udocker|proot>`. `--describe` → `{"id":"n8n","supports_silent":true,"supports_force":true,"variants":["udocker","proot"],"variant_required":false,"variant_default":"udocker","extra_flags":[{"name":"source","applies_to_variant":"proot","values":["clean","github","rootfs-github","rootfs-clean"],"default":"clean"}]}`.

**`udocker` is the default variant** (`VARIANT="udocker"`, `n8n.sh:59`; `proot*|debian` → proot, any other value falls back to udocker, `:206-210`) if the caller does not pass `--variant`; the UI (`N8nFragment.kt:231`) lists udocker first.

### Variants

| Variant | Environment | When to use it |
|---|---|---|
| `udocker` (default) | `n8nio/n8n` container (official image) via `udocker`, no proot | Lighter, no full distro |
| `proot` | Debian Bookworm ARM64 via `proot-distro` | Full environment, heavier |

`--source` (only applies to `proot`) controls where the Debian rootfs and n8n come from: `clean` (clean proot-distro + `npm install`, default), `github` (everything from GitHub Releases), `rootfs-github`/`rootfs-clean` (mixed). The UI does not pass it through the CLI: `showInstallSourceDialog()` writes `~/.n8n_install_source` (`N8nFragment.kt:270-296`) and the script only reads it if `--source` was not given explicitly (`n8n.sh:88-90`).

`--describe-files` (`:117-`) describes only the udocker path (control scripts + dependencies `udocker`, `udockertools`, the `n8n` container, `cloudflared`); the proot path has no describe-files (`not_covered`, declared in the script itself).

### Steps — `proot` variant (8 steps, checkpoint per step; `:1040-1450`)

```
STEP 1/8  Termux update
STEP 2/8  proot-distro install debian (Bookworm ARM64)
STEP 3/8  Inside the proot: Node.js 22 LTS (setup_22.x) + npm install -g n8n --unsafe-perm
            + cloudflared (native ARM64 binary inside the proot, for the tunnel)
          Real verification: Node major >= 20 (aborts if nodesource failed
          silently), real exit code of "npm install -g n8n" captured
          with PIPESTATUS (do not trust the pipe with "tail"), "n8n --version"
          confirmed before continuing (aborts if the binary does not respond).
STEP 4/8  Control scripts → ~/scripts/n8n/:
            start_servidor.sh, stop_servidor.sh, url.sh, status.sh,
            backup.sh, cf_token.sh
STEP 5/8  Aliases in .bashrc (n8n-start/-stop/-url/-status/-backup, cf-token)
STEP 6/8  Auto-start (Termux:Boot)
STEP 7/8  Registry (aborts if n8n --version does not respond, :1414-1421)
STEP 8/8  Cleanup
```

### Steps — `udocker` variant (separate checkpoint `~/.install_n8n_udocker_checkpoint`; `:691-1034`)

```
STEP 0/7  udocker (with 2 fixed udockertools mirrors as fallback — the
          default dynamic origin fails intermittently on mobile networks/CGNAT)
STEP 1/7  Create the n8nio/n8n container (udocker create --name=n8n)
STEP 2/7  Native Termux cloudflared (for the tunnel, outside the container)
STEP 3/7  Control scripts → ~/scripts/n8n-udocker/ (start.sh with a
          real health-check via /healthz)
STEP 4/7  Aliases (n8n-ud-*)
STEP 5/7  Registry
STEP 6-7  Auto-start + cleanup
```

The udocker variant exports `TMPDIR` and `UDOCKER_USE_CURL_EXECUTABLE`, creates `~/n8n-udocker` (chmod 777, `:860`), installs `cloudflared` (pkg or download, checking that it runs, `:865-895`), does `udocker pull --platform=linux/arm64` with a fallback to `:stable` (`:897-936`), and really starts n8n before writing the registry (`:1007-1030`; if it fails, `invalidate_registry` + error).

Post-install verification: udocker = real health-check via `start.sh`; proot = `n8n --version` inside the proot (in proot the server is not started during installation). The script does not use `verify_binary_installed()` from `lib.sh`.

Registry (`update_registry`, `:196-199` → `registry_install n8n <ver> mode=… port=5678`): `n8n.installed/version/install_date/mode/port`. `invalidate_registry` (`:209`) writes `n8n.installed=false` on failures.

`modules.json`: `requires: [nodejs, git]`, `installMethods: [udocker, proot-distro]`, `arch: distro`, `catalogVersion 3.0.0` (the script titles itself v4.0.0, `n8n.sh:44`; version mismatch).

### Resolved behaviors

- **`udocker inspect` instead of `udocker ps | grep`** — `ps`/`ps -a` list the container UUID first and the name in a later column, so `grep -q "^n8n"` never matched; `udocker inspect` is used.
- **Real udockertools verification** — `$HOME/.udocker/lib/VERSION` on disk (what udockertools leaves when it extracts the tarball correctly) is checked instead of `grep`'s exit code.
- **2 fixed udockertools mirrors** — the default dynamic origin fails intermittently on mobile networks/CGNAT. 2 known-good mirrors are tried in a loop before giving up.
- **Node 22 LTS** — n8n requires ≥20.19.
- **Real error capture in 2 pipes** — the NodeSource install (`curl|bash`) and `npm install -g n8n` capture `PIPESTATUS[0]` and abort showing the real log if the command on the left of the pipe failed silently.
- **Real verification before writing the registry** — if `n8n --version` does not respond, it aborts with a real error instead of writing `n8n.version="unknown"`.
- **Real health-check in both variants** — both poll `http://localhost:5678/healthz` and `exit 1` if it does not respond in time.
- **`udocker images` parsing** — the installed version (1.3.17) returns `REPO:TAG` in a single column (`n8nio/n8n:latest`); the tag after the `:` in the first column is extracted (`:1020`).
- **`udocker pull` and the detected platform** — udocker builds Docker Hub's manifest platform selector with `platform.system().lower()` (`udocker/helper/hostinfo.py::HostInfo.osversion()`), and Termux's Python returns `"Android"` (not `"Linux"`); Docker Hub has no manifest for `android/arm64`, so the pull always failed with `no image found in manifest for platform (android/arm64)`. An explicit `--platform=linux/arm64` (a documented udocker flag) is passed in the initial installation and in `update.sh`.

## 4. Execution and state detection — `ModuleController.kt`

- Start: `getModuleStartScript("n8n")` → `~/scripts/n8n-udocker/start.sh` if `n8n.mode=udocker`, otherwise `~/scripts/n8n/start_servidor.sh` (`ModuleController.kt:1163`; `readN8nMode()` falls back to `"proot"` if there is no registry, `:1159`).
- tmux session: `getTmuxSession("n8n")` → `n8n-udocker` or `n8n-server` depending on mode (`:1198`); the tunnel runs in a `tunnel` window of the same session.
- Port 5678 (`getModulePort("n8n")`, `ModuleController.kt:1236`, `modules.json` `port:"5678"`, they match), verified with `waitForPortOpen()` after a successful start. `isRunning` = live tmux, with a fallback to open port (`:370-390`).
- Timeouts (all three differ): Kotlin waits 60 s for the port (`startTimeoutMsFor`, `ModuleController.kt:113-115`); udocker `start.sh` waits up to 180 s (`seq 1 90` x 2 s) and proot up to 240 s (`seq 1 80` x 3 s), plus a 15 s margin. A cold start between 60 s and 180 s could be flagged as a failure in the UI even if n8n ends up coming up (untested).
- Stop: udocker `stop.sh` does `pkill -f "cloudflared tunnel"` + `tmux kill-session` (`n8n.sh:373-376`: it kills any of the user's cloudflared, not only n8n's); proot `stop_servidor.sh` does `pkill -f "n8n start"` and `cloudflared` inside the proot.
- Startup env: both variants set `N8N_HOST=0.0.0.0`, `N8N_PORT`, `N8N_SECURE_COOKIE=false`, `N8N_RUNNERS_ENABLED=true`, `NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os`, `NODE_FUNCTION_ALLOW_EXTERNAL=*`; `WEBHOOK_URL` from `~/.env_n8n`. `N8N_PROXY_HOPS=1` and `N8N_RUNNERS_HEARTBEAT_INTERVAL=300` only in proot (`:468-469`); udocker does not carry them.
- Local mode: with `~/.n8n_local_only` cloudflared is not started; without it, with `~/.cf_token` there is a fixed URL, with no token there is a temporary `trycloudflare.com` one.

## 5. App screen — `N8nFragment.kt`

Fragment: `N8nFragment` (inherits `BaseModuleFragment`, 828 lines). Not installed: `showNotInstalled` → variant dialog (`udocker`/`proot-distro`, `:47-50`), installs via `installModuleInBackground()` without blocking navigation. Installed: STATUS card (environment from `n8n.mode`, registry version, tunnel URL from `~/.last_cf_url`, status pill, `:53-93`) and the controls in the table below. Real operation: `startModuleService/stopModuleService` (switch), `ModuleWebViewFragment` at `http://localhost:5678` (`:807`), `N8nApiClient` (`GET /workflows`, `POST /workflows/{id}/activate|deactivate`, `GET /executions`), `ProcessBuilder(TERMUX_BASH_PATH, script)` for backup/update/repair, `launchTerminalCommand` for viewing logs (`tmux attach`) and the tunnel URL. There is no generic "Open in terminal" button.

### 5b. Workflows API (`N8nApiClient.kt`)

`app/src/main/java/com/termux/app/util/N8nApiClient.kt` — same HTTP pattern as `OllamaApiClient` (`HttpURLConnection` + `org.json`, no external library). It talks to `http://127.0.0.1:5678/api/v1` using the `X-N8N-API-KEY` header. Functions: `hasApiKey()`/`readApiKey()`/`writeApiKey()` (persisted in `~/.n8n_api_key`), `listWorkflows()` (`GET /workflows?limit=100`), `setActive(id, active)` (`POST /workflows/{id}/activate` or `.../deactivate`) and `listExecutions()` (`GET /executions`). All functions are blocking — the caller is responsible for running them on its own `Thread`.

## 6. Cloudflare Tunnel

No token: **temporary** tunnel (`*.trycloudflare.com`, changes on every restart) — extracted from `cf_url.log` with `grep -o`. With a token: **fixed** tunnel, same URL every time.

**Token/domain storage**: n8n has its own "Cloudflare token (fixed URL)" button on this screen, but the real value is stored in `TunnelManager` (`app/src/main/java/com/termux/app/util/TunnelManager.kt`) with `moduleId = "n8n"` — the same registry keys used by the Tunnel tab, with the module id as prefix (`tunnel.n8n.cloudflare.token`/`.domain`, instead of the global `tunnel.cloudflare.*` keys used by the generic quick buttons). Remote/SSH follows the same pattern with `moduleId = "remote"` (token only — SSH is raw TCP, with no real domain field). If the user already had a token in the legacy file (`~/.cf_token` for n8n, `~/.cf_ssh_token` for Remote), it is moved once to `TunnelManager` the first time the corresponding dialog is read/opened. `TunnelManager.mirrorBespokeFiles()` writes `~/.cf_token` as a mirror (`TunnelManager.kt:531-540`).

`~/.env_n8n` (with `N8N_WEBHOOK_URL=...`) remains necessary — n8n the PROCESS (not Kairos's UI) reads that file to start with the configured domain — but it is a mirror rewritten every time the domain is saved, not the source of truth the UI rereads (that is `TunnelManager.getConfig("cloudflared", "n8n")`).

In the **Tunnel** tab, n8n's own tunnel (started by `modulos/n8n.sh`, not by `TunnelManager`) is shown with its real state (active/inactive, URL if any) but **without start/stop buttons** there: the real control of that tunnel lives on this screen (n8n's switch), and Tunnel is only a visibility panel for this case (see `TunnelFragment.applyState()`, `ownTunnel` parameter).

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

## 9. Screen controls (`N8nFragment.kt`)

| Control | What it does | Notes |
|---|---|---|
| ESTADO card (Environment) | Read-only — `n8n.mode` from the registry (proot/udocker) | Shows the real installed variant |
| "n8n" dropdown+switch (network mode + startup) | The dropdown picks local/Cloudflare tunnel (`.n8n_local_only`), the switch controls start/stop (`N8nFragment.kt:64-72`) | Changing mode requires turning it off first (switch locked while running). Only applies on the next start; read by `start.sh` (udocker) and `start_servidor.sh` (proot) before bringing up the tunnel |
| "🌐 Open web interface (local)" | Starts n8n if needed and navigates to the WebView (`localhost:5678`) | — |
| "🌐 View tunnel URL" | `cat ~/.last_cf_url` in the terminal | — |
| "📋 Workflows (API)" | Lists the instance's real workflows via n8n's REST API (`N8nApiClient`), with ⚪/✅ for inactive/active. Tapping one opens a dialog with the "Activate"/"Deactivate" action. With no API Key, it warns and opens the configuration dialog | — |
| "🔑 n8n API Key" | Saves/deletes the key in `~/.n8n_api_key` (text prompt) | The key is generated by hand in n8n's web UI (Settings → n8n API → Create an API key); there is no way to issue it via CLI without a signed-in session. The dialog does not preload the stored value; the key is shown only as configured/not configured (`N8nFragment.kt:687`) |
| "📋 View logs" | Runs the real log script according to the variant (`n8n_log.sh` proot / `n8n-udocker/log.sh`) | — |
| "☁ Workflow backup" | Runs `~/scripts/n8n/n8n_backup.sh` (proot) or `~/scripts/n8n-udocker/backup.sh` (udocker) | — |
| "🗂 Manage projects" | Shared symlink/copy/sync menu (`showProjectsMenu`) | The same menu as Claude/Codex/OpenCode/Antigravity/OpenClaw/Hermes, without `onLaunchInProject` (n8n is a server, not a CLI that opens a folder) |
| "↑ Update n8n" | Runs the real update script according to the variant (`n8n_update.sh` proot / `update.sh` udocker) | — |
| "🔑 Cloudflare token (fixed URL)" | Saves/deletes the token via `TunnelManager.saveConfig("cloudflared", token, domain, "n8n")` (`N8nFragment.kt:488-491`) | Lets you set a real Cloudflare Tunnel token to get a permanent public URL instead of the temporary one. It uses `ProcessBuilder` with `TERMUX_BASH_PATH` (absolute path): a relative `"bash"` always fails in `ProcessBuilder` |
| "🌐 Configure webhook domain" | Sets `N8N_WEBHOOK_URL` in `~/.env_n8n` | The dialog asks only for the bare domain and `writeWebhookDomain()` adds `https://` if the user did not include a scheme — `N8N_WEBHOOK_URL` needs the full URL with scheme (`modulos/n8n.sh` line ~256). Without this, webhooks use the temporary `*.trycloudflare.com` subdomain |
| "🔄 Switch HTTP/HTTPS protocol" | Writes `~/.n8n_protocol` | Only applies to proot mode; writing it in udocker is harmless. `writeProtocol` passes the value as positional argument `$1`, not interpolated (`N8nFragment.kt:519`) |
| "🔧 Repair control scripts" | Runs `modulos/n8n.sh --repair-scripts --silent` | Regenerates ONLY start/stop/log/status/update/backup (depending on the installed variant), without touching the container/rootfs or the user's workflows/credentials |
| "⏱ View recent executions" | `GET /api/v1/executions` (`N8nApiClient.listExecutions()`) | Lets you see whether the last run failed without opening the full webview. `status` is sometimes missing from the listing (known upstream bug, `n8n-io/n8n#20706`) — it falls back to `finished` |

## 10. Files and paths

`~/scripts/n8n/` (proot: `start_servidor.sh, stop_servidor.sh, ver_url.sh, n8n_status.sh, n8n_log.sh, n8n_update.sh, n8n_backup.sh, cf_token.sh`), `~/scripts/n8n-udocker/` (`start/stop/status/log/update/backup.sh`), `~/debian.sh`, `~/n8n-udocker/` (udocker data), `/root/.n8n` inside the proot, `~/.udocker/`, `~/.install_n8n_*checkpoint`, `~/.termux/boot/start_n8n[_udocker].sh`, `~/.last_cf_url`, `~/.cf_ud_url.log`, `~/.cf_token`, `~/.env_n8n`, `~/.n8n_local_only`, `~/.n8n_protocol`, `~/.n8n_api_key`, `~/.n8n_install_source`, `~/.udocker_force_p2`, aliases in `~/.bashrc`, backups in `/sdcard/Download/n8n_*.tar.gz`.

## 11. Security

- `~/.n8n_api_key` and `~/.cf_token` are written without `chmod 600` (`N8nApiClient.kt:44-49`, `TunnelManager.kt:535`, `n8n.sh:625`); they depend on `$HOME` isolation.
- The Cloudflare token travels on the command line `cloudflared tunnel run --token …` inside `tmux send-keys` (`n8n.sh:343-345`, `:523`): visible in the process list and in the tmux buffer.
- The API key and token dialogs do not preload the stored value; only save/delete.
- `NODE_FUNCTION_ALLOW_BUILTIN=child_process,…` and `NODE_FUNCTION_ALLOW_EXTERNAL=*` let Code nodes run processes and load any module. `N8N_SECURE_COOKIE=false` and `N8N_HOST=0.0.0.0` expose n8n to the LAN without TLS; with a tunnel, to the Internet. Kairos adds no authentication of its own (n8n asks to create the owner on first access; unverified).
- `~/.env_n8n` is read with `grep|cut` and concatenated unquoted into `UDOCKER_ENV_ARGS` (`n8n.sh:285-286`); the UI strips the domain's scheme but does not validate characters (`N8nFragment.kt:438`). Low risk (the user's own file), but unvalidated.

## 12. Known limitations

- `update.sh` (udocker) extracts the version with `awk '{print $2}'` (`n8n.sh:413`), the parsing that was already fixed in the installation (`:1020`, uses `$1` + `cut`); after "Update n8n" the registry may end up with a wrong version.
- `catalogVersion` 3.0.0 in `modules.json` vs script v4.0.0.
- `describe-files`/moduledeb does not cover proot nor the image.
- udocker `stop.sh` kills all `cloudflared tunnel` processes.
- `~/.n8n_protocol` is only written from the UI; in `modulos/n8n.sh` there is no read of the file, so the "Switch protocol" button might have no real effect (unverified on a device).
- `n8nio/n8n:latest` has been broken on Docker Hub; the fallback to `:stable` mitigates it (unverified).
