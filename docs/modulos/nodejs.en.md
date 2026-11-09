# Node.js LTS — module `nodejs`

> Sheet based on reading `modulos/nodejs.sh`, `modules.json`, `LanguagesFragment.kt`, `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `lenguajes.en.md`. Anything marked "inference" was not tested on a device.

## 1. What it is and what it is for
Native Termux JavaScript runtime (Node.js LTS with npm). It is also a shared dependency of ~10 npm modules (`ensure_node_installed()` in `modulos/lib.sh:533`) and of the Packages module tools.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "npm -g packages" (`npm list -g --depth=0`, 20 s) — LanguagesFragment.kt:47-49.

## 3. Installation
- Script: `modulos/nodejs.sh` (76 lines; identical to `app/src/main/assets/scripts/nodejs.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `nodejs-lts`. Verification binary: `node`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- After installing, it enables Corepack if present (`modulos/nodejs.sh:71-73`, `corepack enable`) to provide pnpm/yarn. The only real difference from the other languages. The step is best-effort: its failure neither aborts nor is reported.
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If it is already installed and there is no `--force`, it only writes `installed=true`.
- Registry: `nodejs.installed` / `nodejs.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: node`, but `ConsolidatedModuleFragment` does not offer an "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `node --version` (kt:251-262). Action: "npm -g packages" (`npm list -g --depth=0`, 20 s) — LanguagesFragment.kt:47-49.

## 6. Files and paths
`$PREFIX/bin/node`; registry `~/.android_server_registry`; checkpoint `~/.install_nodejs_checkpoint` (declared in the script).

## 7. Security
No secrets; network only via `pkg`. The action commands are fixed (no user input). Uninstall: `pkg uninstall -y nodejs-lts; rm -rf ~/.npm ~/.npmrc ~/.node_repl_history ~/.config/yarn ~/.cache/yarn` (ModuleController.kt:959-963). Real risk: uninstalling Node leaves the npm modules that depend on it without a runtime (native n8n, AI CLIs, Packages); the UI only asks for a generic confirmation dialog, without warning about that dependency (ConsolidatedModuleFragment.kt:284-306).

## 8. Problems / gaps
Uninstall does not warn that other modules depend on Node (inferred impact; the code does not check it). `~/.npm` is deleted along with the cache, with no option to keep global packages.

## 9. Added
`modulos/nodejs.sh`: 2026-08-18.
