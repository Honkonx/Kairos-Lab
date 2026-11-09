# MARKSERV.md — module `markserv` (live Markdown server)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
A server that renders Markdown in the browser with live reload (npm package `markserv`).
`modules.json`: `markserv`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "markserv"`,
`port: ""`, `tmuxSession: ""`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
It only installs the binary; the app does not start it nor know its port. UI options: 1 (switch).

## 3. Installation
`modulos/markserv.sh:81` → `install_npm_global "markserv" "markserv" "markserv"` (`lib.sh:613`; post-condition
`markserv --version`; `registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Execution
Manual only, from the terminal (`markserv`). No UI action.

## 5. UI options
Only the switch (`PackagesFragment.kt:139`, tab "Servers and tunnels"). Uninstall: `npm uninstall -g markserv`.

## 6. Files and paths
`$PREFIX/bin/markserv`, `$(npm root -g)/markserv`, registry `markserv.version`.

## 7. Security
It serves the directory where it is launched; no control from the app (default listening interfaces not audited here). No secrets.

## 8. Problems / gaps
- No start/stop, folder or port selection, nor opening the browser from the UI.
- markserv's `--version` is required as the post-condition; if the package does not implement it, the installation fails by generic design (not confirmed on a device).
- It might overlap functionally with Live Server (`livesrv`); consolidating was not evaluated.

## 9. Added
`modulos/markserv.sh`: 2026-08-18.
