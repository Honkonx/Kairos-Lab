# PSQLFORMAT — `psqlformat` module (SQL formatter)

> Based on reading the code. Summary of the whole set in `PAQUETES.md`. Not tested on a device.

## 1. What it is
SQL formatter (`psqlformat`, npm package; requires Perl). `modules.json`: `psqlformat`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]` (Perl is NOT listed in `requires`), `terminalCommand: "psqlformat"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION (with one native action of its own)
Installs the binary and offers a real file flow (SAF picker + view of the formatted SQL), with no service.
UI options: 2 (switch + «Format .sql file» card).

## 3. Installation
- `modulos/psqlformat.sh:85` → `install_npm_global "psqlformat" "psqlformat" "psqlformat" perl`: the 4th argument runs
  `pkg_update_with_fallback` + `pkg install -y perl` BEFORE `npm install -g` (`lib.sh:613-625`); then `fix_npm_shebang_wrapper`,
  `verify_binary_installed psqlformat` (`--version`) and `registry_install`.
- `--describe-files` declares dependencies `node` and `pkg:perl` (`psqlformat.sh:~58-61`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Execution
When a file is chosen, `PackagesFragment.formatSqlFile` copies the SAF Uri to a temporary file in `cacheDir` (`psqlformat_input_<ts>.sql`),
runs `psqlformat '<path>'` (20 s), deletes the temporary file and shows the SQL in a selectable monospaced dialog (`PackagesFragment.kt:240-294`).
Output goes to stdout; the original file is not modified.

## 5. UI options (`PackagesFragment.kt:143-146`, `:217-233`)
1. Install/uninstall switch (`npm uninstall -g psqlformat`; does NOT uninstall Perl, `ModuleController.kt:988`).
2. «Format .sql file» card: «Choose file» button (`GetContent("*/*")`).

## 6. Files and paths
`$PREFIX/bin/psqlformat`, `$(npm root -g)/psqlformat`, temporary `cacheDir/psqlformat_input_*.sql`, registry `psqlformat.version`.

## 7. Security
The chosen file is copied to `cacheDir` and deleted on both success and error. The path is quoted with single quotes in `bash -c` (the path comes from
`System.currentTimeMillis`, not from the user). No secrets.

## 8. Issues / limitations
- The result is only shown on screen; there is no explicit «save» or «copy» (the text is selectable) and no write-back to the file.
- `modules.json` does not declare Perl in `requires`; the dependency is only resolved inside the script.
- The picker accepts `*/*` (any file), without validating the `.sql` extension.

## 9. Added on
`modulos/psqlformat.sh`: 2026-08-18.
