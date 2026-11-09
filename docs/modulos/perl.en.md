# Perl — `perl` module

> Sheet based on reading `modulos/perl.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `LENGUAJES.md`.
> Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Native Perl interpreter. `modules.json` describes it as a dependency of tools (e.g. PSQL Format in the Packages module).

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "Config" (`perl -V`, 15 s) — LanguagesFragment.kt:50-52.

## 3. Installation
- Script: `modulos/perl.sh` (72 lines; identical to `app/src/main/assets/scripts/perl.sh`, verified with `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `perl`. Verification binary: `perl`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: just `install_single_pkg "perl" "perl" perl` (`modulos/perl.sh:68`).
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If it is already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `perl.installed` / `perl.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: perl`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `perl --version` (kt:251-262). Action: "Config" (`perl -V`, 15 s) — LanguagesFragment.kt:50-52.

## 6. Files and paths
`$PREFIX/bin/perl`; registry `~/.android_server_registry`; checkpoint `~/.install_perl_checkpoint` (declared in the script, `install_single_pkg` does not use it).

## 7. Security
No secrets; network only through `pkg`. The action commands are fixed (no user input). Uninstall: `pkg uninstall -y perl` (ModuleController.kt:964). No folder cleanup (the comment in ModuleController.kt:956-958 states it has no configuration of its own).

## 8. Issues / limitations
Uninstalling it breaks `psqlformat` without warning (psqlformat.sh installs perl as an extra pkg, according to the modules.json description and the comments in ModuleController.kt:976-980).

## 9. Added on
`modulos/perl.sh`: 2026-08-18.
