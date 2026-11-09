# LOCALTUNNEL.md — module `localtunnel` (command `lt`)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
`localtunnel` HTTP tunnel client (`lt` binary) with a patch so that opening the URL works on
Android. `modules.json`: `localtunnel`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`,
`terminalCommand: "lt"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
It installs the binary; no usage UI. It is NOT integrated into `TunnelManager`/`TunnelFragment` (those only
support `cloudflared` and `ngrok`: `TunnelManager.kt:228`). UI options: 1 (switch).

## 3. Installation
- `modulos/localtunnel.sh` (128 lines). It does not use `install_npm_global()` end to end: it installs by hand
  (`localtunnel.sh:~103-121`) because `lt --version` fails with "Unsupported platform: android" (`openurl` dependency)
  before the patch gets applied.
- Actual order: `ensure_node_installed` → `npm install -g localtunnel` → `command -v lt` → `fix_npm_shebang_wrapper` →
  `_localtunnel_fix_openurl` (`sed` inserts `case 'android': command='termux-open-url'` into `openurl.js`, `localtunnel.sh:~83-95`) →
  `verify_binary_installed "lt"` → `registry_install`.
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/localtunnel/**`).

## 4. Execution
Manual only (`lt --port <n>`). The app does not start it.

## 5. UI options
Only the switch (`PackagesFragment.kt:106`, tab "Servers and tunnels"). Uninstall: `npm uninstall -g localtunnel`
(`ModuleController.kt:985`).

## 6. Files and paths
`$PREFIX/bin/lt`, `$(npm root -g)/localtunnel` (includes the patched `node_modules/openurl/openurl.js`), registry `localtunnel.version`.

## 7. Security
It exposes a local port at a public `loca.lt` URL without authentication of its own (service behavior, not audited
here). The patch edits a file inside `node_modules` with `sed -i` (lost on an npm update; only re-applied by reinstalling).

## 8. Problems / gaps
- No "start tunnel" action; the app does not connect it to `TunnelManager` (which uses cloudflared/ngrok).
- The `sed` patch depends on the `default:` line existing in `openurl.js`; if upstream changes, it is not applied and only `verify_binary_installed` fails.
- The "already installed" branch only does `registry_write installed=true` with no version.

## 9. Added
`modulos/localtunnel.sh`: 2026-08-18.
