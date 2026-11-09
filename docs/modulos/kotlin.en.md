# Kotlin — module `kotlin`

> Sheet based on reading `modulos/kotlin.sh`, `modules.json`, `LanguagesFragment.kt`, `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `lenguajes.en.md`. Anything marked "inference" was not tested on a device.

## 1. What it is and what it is for
Native Kotlin compiler (`kotlinc`). According to the comment in `modulos/kotlin.sh:7-10`, the Termux package declares OpenJDK 17 as an apt dependency (not checked against apt).

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) = 1 control.
Extra action: none (no RunAction) — LanguagesFragment.kt:74.

## 3. Installation
- Script: `modulos/kotlin.sh` (87 lines; identical to `app/src/main/assets/scripts/kotlin.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `kotlin`. Verification binary: `kotlinc`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- It does NOT use `install_single_pkg()`: it installs with `pkg install -y kotlin` and verifies with `verify_binary_installed kotlinc "-version"` (`modulos/kotlin.sh:76-82`) because `kotlinc` does not accept `--version` (documented in the script itself). It records the version with `kotlinc -version` + grep (`kotlin.sh:83`). A comment in ModuleInstalled.kt:161 still says it uses `install_single_pkg`.
- Verification: `verify_binary_installed` (`modulos/lib.sh:510-528`): `command -v` + running the version flag before `registry_install`. If it is already installed and there is no `--force`, it only writes `installed=true`.
- Registry: `kotlin.installed` / `kotlin.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: kotlinc`, but `ConsolidatedModuleFragment` does not offer an "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `kotlinc -version` (kt:251-262). Action: none (no RunAction) — LanguagesFragment.kt:74.

## 6. Files and paths
`$PREFIX/bin/kotlinc`; registry `~/.android_server_registry`; checkpoint `~/.install_kotlin_checkpoint` (declared in the script).

## 7. Security
No secrets; network only via `pkg`. The action commands are fixed (no user input). Uninstall: **there is no `DeepUninstallPlan` for `kotlin`** — the OFF switch only resets state; the package and OpenJDK remain installed.

## 8. Problems / gaps
Real gap: no real uninstall, same as Ruby. The version is read with `redirectErrorStream(true)` (ConsolidatedModuleFragment.kt:257), so `kotlinc -version`'s stderr output is captured. No extra UI action.

## 9. Added
`modulos/kotlin.sh`: 2026-08-25.
