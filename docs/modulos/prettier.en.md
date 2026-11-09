# PRETTIER — `prettier` module (code formatter)

> Based on reading the code. Summary of the whole set in `PAQUETES.md`. Not tested on a device.

## 1. What it is
Prettier (npm package `prettier`), a code formatter. `modules.json`: `prettier`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]`, `terminalCommand: "prettier"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
Installs the binary and exposes 2 actions over `$HOME`. No service. UI options: 3 (switch + 2 actions).

## 3. Installation
- `modulos/prettier.sh:81` → `install_npm_global "prettier" "prettier" "prettier"` (flow in `lib.sh:613`:
  skip if it exists without `--force` → `ensure_node_installed` → `npm install -g` → `fix_npm_shebang_wrapper` →
  `verify_binary_installed` (`prettier --version`) → `registry_install`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Execution
No resident process; the actions run `cd "$HOME" && <command>` and show the result in a dialog.

## 5. UI options (`PackagesFragment.kt:86-104`, «Format and quality» tab)
1. Install/uninstall switch (`npm uninstall -g prettier`).
2. «Check format» → `prettier --check .` (read-only; lists files with problems, `formatPrettierCheck`).
3. «Apply format» → `prettier --write .` (60 s), with prior confirmation (`requiresConfirmation`), because it rewrites files.

## 6. Files and paths
`$PREFIX/bin/prettier`, `$(npm root -g)/prettier`, registry `prettier.version`. Operates recursively on `$HOME`.

## 7. Security
`--write .` over `$HOME` modifies real files (mitigated with a confirmation dialog; there is no backup or dry-run). No secrets.

## 8. Issues / limitations
- Both actions run in `$HOME`, not in a chosen project; `prettier --write .` over the whole home may touch files that are not the user's own (dotfiles of other modules with supported extensions, although `.prettierignore`/node_modules are respected by Prettier's defaults).
- No folder picker or specific-file picker.
- Inference: the real behavior on a large `$HOME` (60 s limit) was not verified.

## 9. Added on
`modulos/prettier.sh`: 2026-08-18.
