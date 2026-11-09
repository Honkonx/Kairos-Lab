# LIVESRV.md — module `livesrv` (Live Server, command `live-server`)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
Static server with automatic reload (npm package `live-server`). `modules.json`: `livesrv`,
`Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "live-server"`, `port: ""`,
`tmuxSession: ""`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
It only installs the binary. The app does NOT start it or manage its port (no `RunAction` and no
tmux session). UI options: 1 (install/uninstall switch).

## 3. Installation
- `modulos/livesrv.sh:82` → `install_npm_global "livesrv" "live-server" "live-server"`
  (`lib.sh:613`; verification `live-server --version` with `verify_binary_installed`, then `registry_install`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Execution
Manual only, from the terminal (`live-server`). The UI does not launch it. The `PackagesFragment` row
offers neither "Open in terminal" nor "Open project" (`PackagesFragment.kt:105`).

## 5. UI options
Only the switch (tab "Servers and tunnels", `PackagesFragment.kt:204`). Uninstall: `npm uninstall -g live-server`.

## 6. Files and paths
`$PREFIX/bin/live-server`, `$(npm root -g)/live-server`, registry `livesrv.version`.

## 7. Security
A static server started by hand listens on all interfaces by default according to live-server's
behavior (not verified in this repo); the app does not control host/port.

## 8. Problems / gaps
- There is no start/stop action nor folder/port selection from the UI (even though the tool's purpose is to serve a folder).
- `terminalCommand` exists in `modules.json` but the consolidated row does not use it.
- `live-server --version` is used as the post-condition; if that version does not print and return 0, the installation fails with an error even though the binary works (not confirmed).

## 9. Added
`modulos/livesrv.sh`: 2026-08-18.
