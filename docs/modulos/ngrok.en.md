# NGROK.md — module `ngrok` (tunnels via npm)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
`ngrok` client (npm package `ngrok`, which downloads the native binary in its postinstall) to expose local ports.
`modules.json`: `ngrok`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "ngrok"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION (but consumed by an unrelated functional screen)
This module itself only installs the CLI and offers 1 action (authtoken). The real tunnel functionality lives in
`TunnelManager.kt`/`TunnelFragment.kt` ("ngrok" provider next to Cloudflare: `TunnelFragment.kt:138`), which does NOT depend on this
script. Module UI options: 2 (switch + "Configure authtoken"); the tunnel has its own options on another screen.

## 3. Installation
- `modulos/ngrok.sh:81` → `install_npm_global "ngrok" "ngrok" "ngrok"` (`lib.sh:613`; post-condition `ngrok --version`;
  `registry_install`). Flags: `--silent` (parsed; not read again in this script), `--force`, `--describe`, `--describe-files`
  (glob `$(npm root -g)/ngrok/**`, includes the native binary downloaded by the postinstall).
- Duplicated installation: `TunnelManager.ensureNgrok()` (`TunnelManager.kt:142-157`) does its own `npm install -g ngrok`
  (with `nodejs-lts` if missing) and verifies `ngrok version`, without going through `ngrok.sh` or the registry.

## 4. Execution
UI action: `cd "$HOME" && ngrok config add-authtoken '<token>'`. The real tunnel is started by `TunnelManager`
(`ngrok http|tcp <port> --log stdout` in tmux, with `GODEBUG=netdns=go`, `TunnelManager.kt:258-276,320`).

## 5. UI options (`PackagesFragment.kt:167-181`, tab "Servers and tunnels")
1. Install/uninstall switch (`npm uninstall -g ngrok`, `ModuleController.kt:990`).
2. "Configure authtoken" with a text dialog (the value is quoted with single quotes; it is neither logged nor shown).

## 6. Files and paths
`$PREFIX/bin/ngrok`, `$(npm root -g)/ngrok`, ngrok's own config (token in its configuration file), registry `ngrok.version`;
`TunnelFragment`'s token/domain is stored separately (`tunnel.ngrok.*`, `TunnelManager.kt:28-30`).

## 7. Security
- The token travels as a `bash -c` argument (briefly visible in the device's own process list) and ngrok stores it in its
  config; the app never shows it again.
- Two places store the token (ngrok's config via this module and `TunnelManager`'s `tunnel.ngrok.token`): there is no synchronization between them.

## 8. Problems / gaps
- Duplicated installation (script vs `ensureNgrok`), with a different post-condition (`ngrok version` vs `--version`) and no registry on the `TunnelManager` path.
- ngrok is a closed-source binary; DNS depends on the `GODEBUG=netdns=go` workaround (documented in `TunnelManager.kt:306-313`).
- The `TunnelManager` comment says the official `ngrok` fails DNS on `[::1]:53`; not re-tested.

## 9. Added
`modulos/ngrok.sh`: 2026-08-18.
