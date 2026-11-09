# Tor

> **Add-on (no longer a catalog module).** It is the "Tor" tab of **Cybersecurity**
> (`util/TorManager.kt`). This document describes the design and behavior of the tool.

No Fragment of its own, managed by `GenericModuleFragment.kt` (the `hasSwitch`+`tmuxSession` contract
in `modules.json` already covers installing/starting/stopping without needing a dedicated screen — with no
`webviewUrl`, since the SOCKS proxy is not browsable HTTP).

---

**Script:** `modulos/tor.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/tor.sh`.
**Registry:** prefix `tor.*`
**Port:** `9050` (SOCKS5 proxy, `127.0.0.1` — no web UI; point any SOCKS5-compatible
app/CLI at it once the switch is ON).

---

## 1. Overview

Anonymity network / onion routing (The Onion Router) — exposes a local SOCKS5 proxy.
Checked against the real `termux-packages` index (`packages/tor/build.sh`,
`TERMUX_PKG_VERSION="0.4.9.12"`, regular package, no root).

## 2. SOCKS port

Upstream Tor itself documents in its `torrc.sample` (the one `termux_step_post_make_install`
copies to `$PREFIX/etc/tor/torrc` after installing) that it opens a SOCKS proxy on port **9050** by
default even without an explicit `SocksPort` line in the config — behavior of the `tor` binary
itself (not specific to Android/Termux), with no patches in the `termux-packages` `build.sh`
altering it. To avoid relying on that implicit default, `tor.sh` STEP 2 explicitly adds
`SocksPort 9050` to `torrc` if it does not already detect an active `SocksPort` line
(idempotent, same spirit as the port patching in `gitea.sh`).

## 3. Installation

Native apt package (`install_single_pkg "tor" "tor" tor`) + idempotent `torrc` patching
(STEP 2, checkpoint `torrc_patch`).

## 4. Start/stop (`hasSwitch: true`)

The module adds `~/scripts/tor/start.sh`/`stop.sh` (same tmux + fixed-port pattern as
`syncthing.sh`/`gitea.sh`):

```
tmux new-session -d -s tor-server \
  "tor -f $PREFIX/etc/tor/torrc > ~/kairos_logs/tor_serve.log 2>&1"
```

`ModuleController.kt` registers `tor` in its tables (`getModuleStartScript`/`getModuleStopInfo`/
`getTmuxSession`/`getModulePort` → 9050) — same pattern as `syncthing`/`gitea`, verified by
`ModuleControllerTest.kt` (cross-consistency between `modules.json` and these tables).

**Note:** the real `termux-packages` `build.sh` uses `TERMUX_PKG_SERVICE_SCRIPT` (the
`termux-services` framework, `sv-enable tor`) — Kairos does not use that framework in any module, so
`tor.sh` follows the same tmux pattern as the rest of the modules with a switch, not the native
`termux-services` mechanism.

## 5. Usage

Once the switch is ON, any SOCKS5-compatible app/tool
(curl, browsers, other CLIs) can be pointed at `127.0.0.1:9050` to route its traffic through
Tor.
