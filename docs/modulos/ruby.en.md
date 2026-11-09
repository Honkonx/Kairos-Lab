# Ruby — `ruby` module

> Sheet based on reading `modulos/ruby.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `LENGUAJES.md`.
> Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Native Ruby interpreter + `gem` from Termux.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "Gems" (`gem list`, 15 s) — LanguagesFragment.kt:72-73.

## 3. Installation
- Script: `modulos/ruby.sh` (70 lines; identical to `app/src/main/assets/scripts/ruby.sh`, verified with `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `ruby`. Verification binary: `ruby`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: `install_single_pkg "ruby" "ruby" ruby` (`modulos/ruby.sh:67`). The modules.json entry does not declare `iconAsset` (uses an emoji).
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If it is already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `ruby.installed` / `ruby.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: ruby`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `ruby --version` (kt:251-262). Action: "Gems" (`gem list`, 15 s) — LanguagesFragment.kt:72-73.

## 6. Files and paths
`$PREFIX/bin/ruby`; registry `~/.android_server_registry`; checkpoint `~/.install_ruby_checkpoint` (declared in the script, `install_single_pkg` does not use it).

## 7. Security
No secrets; network only through `pkg`. The action commands are fixed (no user input). Uninstall: **there is no `DeepUninstallPlan` for `ruby`** (no matches in ModuleController.kt). The OFF switch runs `deepUninstallModule` and, with `plan == null`, only resets the state (ModuleController.kt:1066-1068, message "real uninstall not supported") — the `ruby` package stays installed in Termux.

## 8. Issues / limitations
Real limitation (read in code): the OFF switch does not uninstall the package; on refresh, `isInstalledRobust` will still see `ruby` and the switch goes back to ON (inferred behavior, not tested on a device). LENGUAJES.md does not mention it.

## 9. Added on
`modulos/ruby.sh`: 2026-08-25.
