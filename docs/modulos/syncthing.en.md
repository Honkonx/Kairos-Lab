# Syncthing

> **Add-on (no longer a catalog module).** It lives in **Homelab → Services → Add-ons**
> (`util/HomelabAddons.kt`). This document describes the design and behavior of the tool.

No Fragment of its own, managed by `GenericModuleFragment.kt` (the
`hasSwitch`+`tmuxSession`+`webviewUrl` contract in `modules.json` already covers installing/starting/stopping/opening
the web UI without needing a dedicated screen).

---

**Script:** `modulos/syncthing.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/syncthing.sh`.
**Registry:** prefix `syncthing.*`
**Port:** `8384` (web UI, `127.0.0.1` only — the app opens it embedded via `webviewUrl`)

---

## 1. Overview

P2P file synchronization between devices, with no dependency on any cloud provider.

## 2. Installation

Native apt package installed through `install_single_pkg()` (see `modulos/lib.sh`), checked against
the real `termux-packages` index (`packages/syncthing/build.sh`, `TERMUX_PKG_VERSION="2.1.5"`,
regular package, no root). Syncthing 2.x requires the `serve` subcommand (1.x versions ran
directly with just flags).

## 3. Start/stop (`hasSwitch: true`)

The module adds `~/scripts/syncthing/start.sh`/`stop.sh` (same tmux + fixed-port pattern as
`cactus.sh` STEP 5 / `llamaserver.sh`):

```
tmux new-session -d -s syncthing-server \
  "syncthing serve --no-browser --no-restart --gui-address=127.0.0.1:8384 > ~/kairos_logs/syncthing_serve.log 2>&1"
```

`ModuleController.kt` registers `syncthing` in its tables (`getModuleStartScript`/
`getModuleStopInfo`/`getTmuxSession`/`getModulePort` → 8384) — same pattern as `cactus`,
verified by `ModuleControllerTest.kt` (cross-consistency between `modules.json` and these
tables).

## 4. Configuration (stays in its own web UI)

Device pairing (Device ID) and the folders to sync are inherently interactive by the upstream
tool's design — the user configures them from `http://localhost:8384`, which
`GenericModuleFragment` opens embedded when the switch is ON (`webviewUrl` field in `modules.json`).
