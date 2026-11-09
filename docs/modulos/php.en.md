# PHP — `php` module

> Sheet based on reading `modulos/php.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `LENGUAJES.md`.
> Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Native PHP CLI (interpreter + extensions from the Termux package). It does not include a web server or Composer.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "Loaded modules" (`php -m`, 15 s) — LanguagesFragment.kt:53-54.

## 3. Installation
- Script: `modulos/php.sh` (71 lines; identical to `app/src/main/assets/scripts/php.sh`, verified with `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `php`. Verification binary: `php`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: `install_single_pkg "php" "php" php` (`modulos/php.sh:67`).
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If it is already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `php.installed` / `php.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: php`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `php --version` (kt:251-262). Action: "Loaded modules" (`php -m`, 15 s) — LanguagesFragment.kt:53-54.

## 6. Files and paths
`$PREFIX/bin/php`; registry `~/.android_server_registry`; checkpoint `~/.install_php_checkpoint` (declared in the script, `install_single_pkg` does not use it).

## 7. Security
No secrets; network only through `pkg`. The action commands are fixed (no user input). Uninstall: `pkg uninstall -y php` (ModuleController.kt:965).

## 8. Issues / limitations
The UI cannot launch `php -S` (built-in server) or manage extensions; it only shows `php -m`. Composer is not covered (no reference in php.sh).

## 9. Added on
`modulos/php.sh`: 2026-08-18.
