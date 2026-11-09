# Go — `golang` module

> Container: see `LENGUAJES.md`. Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Go toolchain (compiler + stdlib) native to Termux; ~120 MB declared.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "Environment" (`go env`, 15 s) — LanguagesFragment.kt:64-65.

## 3. Installation
- Script: `modulos/golang.sh` (71 lines; identical to `app/src/main/assets/scripts/golang.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `golang`. Verification binary: `go`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: `install_single_pkg "golang" "go" golang` (`modulos/golang.sh:67`). The id is `golang` but the binary is `go` (mapped in ModuleInstalled.kt:132).
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `golang.installed` / `golang.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: go`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `go version` (kt:251-262). Action: "Environment" (`go env`, 15 s) — LanguagesFragment.kt:64-65.

## 6. Files and paths
`$PREFIX/bin/go`; registry `~/.android_server_registry`; checkpoint `~/.install_golang_checkpoint` (declared in the script, not used by `install_single_pkg`).

## 7. Security
No secrets; network only through `pkg`. Action commands are fixed (no user input). Uninstall: `pkg uninstall -y golang; rm -rf ~/.cache/go ~/.cache/go-build` (ModuleController.kt:971-974). It does not delete `~/go` (GOPATH).

## 8. Known limitations
`~/go` is left orphaned on uninstall (from reading the uninstall plan; not tested).

## 9. Date added
`modulos/golang.sh`: 2026-08-18.
