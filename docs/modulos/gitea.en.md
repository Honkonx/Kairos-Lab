# Gitea — Kairos Module

**Kairos Module** — no Fragment of its own, managed by `GenericModuleFragment.kt` (the
`hasSwitch`+`tmuxSession`+`webviewUrl` contract of `modules.json` already covers install/start/
stop/open the web UI without needing a dedicated screen), same pattern as `syncthing`.

---

**Script:** `modulos/gitea.sh` (v1.0.0, 194 lines) — synced copy at
`app/src/main/assets/scripts/gitea.sh`. Package verified against the `termux-packages` index
(`packages/gitea/build.sh`, `TERMUX_PKG_VERSION="1.27.3"`, regular package, no root).
**Registry:** prefix `gitea.*`
**Port:** `3001` (HTTP web UI, opened embedded by the app via `webviewUrl http://localhost:3001`).

---

## 1. Overview

A self-hosted Git server on the device (Gitea — "Git with a cup of tea"). It fits the
"home-lab in your pocket" philosophy (`docs/mini-pc/`) and the `ide`/Studio module: it lets you
push the integrated IDE's own code to a remote that lives on the same phone, without
depending on GitHub/GitLab.

**Classification: lightweight FUNCTIONAL MODULE (no Fragment of its own).** It has a real service
(`hasSwitch:true`, tmux `gitea-server`, port 3001), but the whole UI is `GenericModuleFragment` (it is not
listed in `ModuleDetailNavigator.kt`): status, start/stop switch (`GenericModuleFragment.kt:147-160`),
open webview (164-169), maintenance (update/diagnose/uninstall). It has no `terminalCommand`, so there is
no terminal button. ~6 generic UI options; all real configuration (admin user, repos, tokens) happens in
Gitea's web UI.

## 2. Port conflict — resolved at install time

The `app.ini` packaged by `termux-packages` (`packages/gitea/app.ini`) **has no `[server]`
section**, so Gitea would run on its default HTTP port: **3000**. That port is already used by the
`opencode` module in Kairos (`"port": "3000"` in `modules.json` and `getModulePort()` in
`ModuleController.kt`) — that is why `gitea.sh` STEP 2 patches `$PREFIX/etc/gitea/app.ini` adding:

```ini
[server]
HTTP_PORT = 3001
DISABLE_SSH = true
```

`DISABLE_SSH = true` also prevents Gitea from trying to start its internal SSH server (default port
22, which Android/Termux without root cannot bind — ports <1024 require `CAP_NET_BIND_SERVICE`) —
Kairos already has its own `sshd` via the `remote` module (port 8022). Git access to this server is over
HTTP (`clone`/`push http://127.0.0.1:3001/user/repo.git`), not SSH.

## 3. Installation

`gitea.sh --silent [--force]`.
- STEP 1: `install_single_pkg "gitea" "gitea" gitea` (apt, verifies `gitea --version`).
- STEP 2: idempotent patching of `$PREFIX/etc/gitea/app.ini` with checkpoint `app_ini_patch` — only if
  the file exists and does not already contain its own `[server]` section (in case the user customized it
  by hand after a previous install; `gitea.sh:116-137`; if the file is missing, `error`).
- STEP 3: writes `~/scripts/gitea/start.sh|stop.sh` (checkpoint `serve_scripts`); `registry_install gitea … http_port=3001`.
- No direct downloads or sha256 (apt).

## 4. Start/stop (`hasSwitch: true`)

`~/scripts/gitea/start.sh`/`stop.sh` (same tmux + fixed-port pattern as `syncthing.sh`/`cactus.sh` STEP 5):

```
tmux new-session -d -s gitea-server \
  "gitea web --config $PREFIX/etc/gitea/app.ini > ~/kairos_logs/gitea_serve.log 2>&1"
```

`start.sh` does `sleep 3` and validates that the tmux session exists (not that the port responds); `stop.sh`
kills the session. `ModuleController.kt` knows `gitea` in its tables (`getModuleStartScript`/`getModuleStopInfo`/
`getTmuxSession`/`getModulePort` → 3001; `:1179,1192,1203,1245`) — same pattern as `syncthing`, verified by
`ModuleControllerTest.kt` (cross-consistency between `modules.json` and these tables).

## 5. UI functions and configuration

Start/Stop switch, "Open web interface", Update, Diagnose, Uninstall. Nothing specific to Gitea.

Creating the first admin user and repos is Gitea's own installation wizard, the first time the UI is
opened — inherently interactive by design of the upstream tool (same criterion as `rclone config`/
Syncthing pairing) — the user completes it from `http://localhost:3001`, which `GenericModuleFragment`
opens embedded when the switch is ON.

## 6. Files and paths

`$PREFIX/etc/gitea/app.ini` (modified), `~/scripts/gitea/{start,stop}.sh`, `~/kairos_logs/gitea_serve.log`,
Gitea data (repos/DB) in its default working path (exact path not verified on a device), registry `gitea.*`.

## 7. Security

The patched `app.ini` does not set `HTTP_ADDR`: by default upstream Gitea listens on all interfaces
(inference, not verified on a device), while the script says "127.0.0.1" (`gitea.sh:165`). If so, the UI
and the first-user wizard would be exposed to the LAN before the admin is created. No secrets in the
script. No `curl|bash`.

## 8. Known limitations

- Neither the script nor the UI sets `HTTP_ADDR=127.0.0.1` or `INSTALL_LOCK`; the start message claims
  "127.0.0.1:3001" without guaranteeing it.
- The `app.ini` patch is skipped if any `[server]` already exists (it is not verified that the port is
  3001); a previous config with another port would break the switch (the app expects 3001).
- `start.sh` only validates that the tmux session exists, not that the port responds.
- No dedicated screen: there is no user/repo management; the "Studio → local remote" integration suggested
  by the script header does not exist in the code read.

## 9. Date added

`modulos/gitea.sh`: **2026-09-15**.
