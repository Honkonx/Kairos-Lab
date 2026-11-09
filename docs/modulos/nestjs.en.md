# NESTJS.md — module `nestjs` (NestJS CLI, command `nest`)

> Sheet based on reading the code. Group summary in `paquetes.en.md`. Not tested on a device.

## 1. What it is
The official NestJS CLI (`@nestjs/cli`) for creating Node/NestJS projects. `modules.json`: `nestjs`,
`Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "nest"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION
It installs the CLI and offers 1 action (scaffolding). No service or state. UI options: 2
(switch + "Create Nest project").

## 3. Installation
- `modulos/nestjs.sh:81` → `install_npm_global "nestjs" "@nestjs/cli" "nest"` (`lib.sh:613`):
  skips if `nest` exists and there is no `--force` (`registry_write installed=true`); otherwise,
  `ensure_node_installed` → `npm install -g @nestjs/cli` → `command -v nest` →
  `fix_npm_shebang_wrapper` (`lib.sh:563`) → `verify_binary_installed nest` (`--version`, `lib.sh:501`) → `registry_install`.
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/@nestjs/**`).

## 4. Execution
It is not a service. The action runs `cd "$HOME" && nest new '<name>' --package-manager npm --skip-git`
(180 s timeout). The output is shown when it finishes, last 15 lines (`formatNestNew`).

## 5. UI options (`PackagesFragment.kt:70-85`, tab "Deploy and frameworks")
1. Install/uninstall switch (`npm uninstall -g @nestjs/cli`).
2. "Create Nest project" with a name dialog (the value is quoted with single quotes, `promptTextInputAction`).

## 6. Files and paths
`$PREFIX/bin/nest`, `$(npm root -g)/@nestjs/cli`, new projects in `$HOME/<name>`, registry `nestjs.version`.

## 7. Security
Project name shell-escaped (not injectable). `nest new` runs `npm install` of
templates from npm (unpinned). No secrets.

## 8. Problems / gaps
- The project is always created in `$HOME`; there is no folder selector or name validation.
- Output only at the end (no live progress); 180 s may not be enough for `npm install` on a slow network (the process is killed and the project is left half-done).
- There are no `nest start/build/generate` actions; the terminal is needed for the rest.

## 9. Added
`modulos/nestjs.sh`: 2026-08-18.
