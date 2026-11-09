# C/C++ (Clang) — `clang` module

> Container: see `LENGUAJES.md`. Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Clang compiler (C/C++) and LLVM tools from the Termux package; ~150 MB declared.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) = 1 control.
Extra action: none, on purpose (comment in LanguagesFragment.kt:58-62).

## 3. Installation
- Script: `modulos/clang.sh` (72 lines; identical to `app/src/main/assets/scripts/clang.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `clang`. Verification binary: `clang`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: `install_single_pkg "clang" "clang" clang` (`modulos/clang.sh:68`). It is the language with the least UI: only a switch and the version.
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `clang.installed` / `clang.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: clang`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `clang --version` (kt:251-262). Action: none, on purpose (comment in LanguagesFragment.kt:58-62).

## 6. Files and paths
`$PREFIX/bin/clang`; registry `~/.android_server_registry`; checkpoint `~/.install_clang_checkpoint` (declared in the script, not used by `install_single_pkg`).

## 7. Security
No secrets; network only through `pkg`. Action commands are fixed (no user input). Uninstall: `pkg uninstall -y clang` (ModuleController.kt:970). No cleanup of its own.

## 8. Known limitations
No useful UI option (no "compile file"/project). `python.sh` also installs `clang` (STEP 4, python.sh:237-250), so turning it off here may leave Python without a compiler for native pips (inference, not tested on a device).

## 9. Date added
`modulos/clang.sh`: 2026-08-18.
