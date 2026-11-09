# TYPESCRIPT.md — `typescript` module (TypeScript / `tsc`)

> Based on reading the code. Short sheet; the summary of the set of 10 npm tools
> is in `PAQUETES.md`. Not tested on a device: everything marked "Verified in code" comes from reading code.

## 1. What it is
The TypeScript `tsc` compiler installed as a global npm package (`modules.json`: `typescript`,
`type: Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "tsc"`, ~15MB).

## 2. Classification: ADD-ON/EXTENSION
It only installs the binary and exposes 1 one-off action (`tsc --init`). It has no service, state or
screen of its own: it lives as a row with a switch inside `PackagesFragment`. UI options: 2
(install/uninstall switch + «Create tsconfig.json»).

## 3. Installation
- `modulos/typescript.sh` (146 lines). Flags: `--silent`, `--force`, `--describe`,
  `--describe-files` (manifest for `moduledeb`, lines 43-80).
- Unlike the other 9, it does NOT use `install_npm_global()`: it installs by hand
  (`ensure_node_installed` → `npm install -g typescript` → `fix_npm_shebang_wrapper` → platform
  patch → `verify_binary_installed "tsc"` → `registry_install`), `typescript.sh:~95-141`.
  Reason documented in the script itself: `install_npm_global` checks `tsc --version` before
  the patch gets to run.
- Platform patch: TypeScript 7.x ships a native `tsc` written in Go; the package
  `@typescript/typescript-android-arm64` does not exist, so the script installs `@typescript/typescript-linux-arm64@<same version>`,
  copies it to `typescript-android-arm64`, fixes the `name` in `package.json` and runs `chmod +x lib/tsc`.
- Verification: `verify_binary_installed "tsc"` (`lib.sh:501`) + `registry_install typescript <version>`.
  The «already installed» branch (`command -v tsc`, without `--force`) only does `registry_write typescript installed=true` (no version).

## 4. Execution
It is not a service. The UI action runs `bash -c 'cd "$HOME" && tsc --init'` (30 s by default,
`ConsolidatedModuleFragment.runItemAction`).

## 5. UI options (`PackagesFragment.kt:56-69`, «Format and quality» tab, `:203`)
1. Install/uninstall switch (uninstall: `npm uninstall -g typescript`, `ModuleController.kt:981`).
2. «Create tsconfig.json» → `tsc --init` in `$HOME` (not in a chosen project).

## 6. Files and paths
Binary `$PREFIX/bin/tsc` (bash wrapper), `$(npm root -g)/typescript`, `$(npm root -g)/@typescript/typescript-android-arm64`,
registry `~/.android_server_registry` (`typescript.version`), checkpoint `~/.install_typescript_checkpoint` (declared, unused in the manual flow).

## 7. Security
No secrets. `npm install -g` with no pinned version or integrity check of its own
(standard npm supply chain). The patch copies a package from another platform (linux-arm64) with `--force`.

## 8. Issues / limitations
- `tsc --init` always runs in `$HOME`; there is no project picker or compile/`--noEmit` action.
- The patch depends on `@typescript/typescript-linux-arm64@<version>` existing; if `node -e` cannot obtain the version, the `npm install` is skipped and it only fails at the later check.
- Inference: the patch is validated according to the script's comments, not re-tested.

## 9. Added on
`modulos/typescript.sh`: 2026-08-18.
