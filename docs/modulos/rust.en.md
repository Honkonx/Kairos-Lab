# Rust — `rust` module

> Sheet based on reading `modulos/rust.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` and `lib.sh`. Container: see `LENGUAJES.md`.
> Anything marked "inference" has not been tested on a device.

## 1. What it is and what it is for
Rust toolchain (rustc + cargo) from the Termux package. Declared size ~200 MB in modules.json.

## 2. Classification
**ADD-ON / EXTENSION**, not a functional module: no screen of its own (`internal: true` in modules.json; it is a
row of `LanguagesFragment` on top of `ConsolidatedModuleFragment`), no service, no port.
Real UI options: 1 switch (install/uninstall) + 1 action button = 2 controls.
Extra action: "cargo install binaries" (`cargo install --list`, 15 s) — LanguagesFragment.kt:55-57.

## 3. Installation
- Script: `modulos/rust.sh` (71 lines; identical to `app/src/main/assets/scripts/rust.sh`, verified with `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (deliberate `files: []` manifest for `moduledeb`: apt package managed by pkg).
- Termux package: `rust`. Verification binary: `rustc`.
- No dependencies in modules.json (`requires: []`), no variants (`installMethods: ["standard"]`).
- No extra steps: `install_single_pkg "rust" "rustc" rust` (`modulos/rust.sh:68`).
- Verification: `verify_binary_installed` inside `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + running `--version` before `registry_install`. If it is already installed and `--force` is not given, it only writes `installed=true`.
- Registry: `rust.installed` / `rust.version` in `~/.android_server_registry`.

## 4. Execution
No process or service. modules.json declares `terminalCommand: rustc`, but `ConsolidatedModuleFragment` offers no "Open in terminal" button: the field is not used on this screen.

## 5. UI functions
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: confirmation dialog + `deepUninstallModule` (284-322). Status/version: `ModuleInstalled.isInstalledRobust` + registry version or `rustc --version` (kt:251-262). Action: "cargo install binaries" (`cargo install --list`, 15 s) — LanguagesFragment.kt:55-57.

## 6. Files and paths
`$PREFIX/bin/rustc`; registry `~/.android_server_registry`; checkpoint `~/.install_rust_checkpoint` (declared in the script, `install_single_pkg` does not use it).

## 7. Security
No secrets; network only through `pkg`. The action commands are fixed (no user input). Uninstall: `pkg uninstall -y rust; rm -rf ~/.cargo` (ModuleController.kt:966-969). Deleting `~/.cargo` also removes binaries installed with `cargo install`.

## 8. Issues / limitations
The generic confirmation dialog does not warn that `~/.cargo` is deleted (per the uninstall plan). No version selector (pkg offers only one).

## 9. Added on
`modulos/rust.sh`: 2026-08-18.
