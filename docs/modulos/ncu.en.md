# NCU.md — module `ncu` (npm-check-updates)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
`npm-check-updates` (command `ncu`): checks and updates dependency versions of a `package.json`.
`modules.json`: `ncu`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "ncu"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
It installs the binary and exposes 2 actions on `$HOME/package.json`. No service. UI options: 3 (switch + 2 actions).

## 3. Installation
`modulos/ncu.sh:81` → `install_npm_global "ncu" "npm-check-updates" "ncu"` (`lib.sh:613`; post-condition `ncu --version`;
`registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/npm-check-updates/**`).
Note: the module id (`ncu`) differs from the npm package (`npm-check-updates`); uninstall uses the package name
(`ModuleController.kt:989`).

## 4. Execution
Actions run `cd "$HOME" && <command>`, with the output shown in a dialog at the end.

## 5. UI options (`PackagesFragment.kt:147-166`, tab "Format and quality")
1. Install/uninstall switch.
2. "Check for updates" → `ncu` (30 s, read-only; lists `name ^current → ^new`, `formatNcuOutput`).
3. "Update package.json" → `ncu -u` (30 s) with prior confirmation; rewrites `package.json`.

## 6. Files and paths
`$PREFIX/bin/ncu`, `$(npm root -g)/npm-check-updates`, `$HOME/package.json` (target), registry `ncu.version`.

## 7. Security
`ncu -u` mutates `package.json` (with confirmation; it makes no backup and does not run `npm install`). No secrets.

## 8. Problems / gaps
- It only operates on `$HOME/package.json`; there is usually none there and the result is "No package.json found" (the formatter itself admits it, `PackagesFragment.kt:298`). There is no project selector.
- It offers no `ncu -i`, `--target`, or filters.
- The 30 s timeout may fall short on a slow network (it queries the npm registry).

## 9. Added
`modulos/ncu.sh`: 2026-08-18.
