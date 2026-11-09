# Packages — module `packages`

> Same pattern as `docs/modulos/lenguajes.en.md` — read that file first for the full context (`internal` field, switch mechanism ON=install/OFF=real uninstall, shared `ConsolidatedModuleFragment.kt`). This document only covers what is specific to "Packages".

## What it is

`packages` consolidates 10 npm development tools (`typescript`, `nestjs`, `prettier`,
`livesrv`, `localtunnel`, `vercel`, `markserv`, `psqlformat`, `ncu`, `ngrok`) into a single
container module (`PackagesFragment.kt`) with one switch per tool — ready-made modules that are
only turned on or off, with no intermediate installation dialogs.

Classification: **container without a script** (`script: ""`, `type: Contenedor`, `category: tools`),
mapped in `ModuleDetailNavigator.kt:49` to `PackagesFragment`. Functional as a catalog; each
tool is an npm add-on.

## The 10 tools

| Tool | id | Real script | npm package | Version command | Sheet |
|---|---|---|---|---|---|
| TypeScript | `typescript` | `modulos/typescript.sh` | `typescript` (`tsc`) | `tsc --version` | — |
| NestJS CLI | `nestjs` | `modulos/nestjs.sh` | `@nestjs/cli` (`nest`) | `nest --version` | `nestjs.en.md` |
| Prettier | `prettier` | `modulos/prettier.sh` | `prettier` | `prettier --version` | — |
| Live Server | `livesrv` | `modulos/livesrv.sh` | `live-server` | `live-server --version` | `livesrv.en.md` |
| Localtunnel | `localtunnel` | `modulos/localtunnel.sh` | `localtunnel` (`lt`) | `lt --version` | `localtunnel.en.md` |
| Vercel CLI | `vercel` | `modulos/vercel.sh` | `vercel` | `vercel --version` | — |
| Markserv | `markserv` | `modulos/markserv.sh` | `markserv` | `markserv --version` | `markserv.en.md` |
| PSQL Format | `psqlformat` | `modulos/psqlformat.sh` | `psqlformat` (+ Perl via pkg) | `psqlformat --version` | — |
| NPM Check Updates | `ncu` | `modulos/ncu.sh` | `npm-check-updates` (`ncu`) | `ncu --version` | `ncu.en.md` |
| Ngrok | `ngrok` | `modulos/ngrok.sh` | `ngrok` | `ngrok --version` | `ngrok.en.md` |

The 10 scripts (`modulos/<id>.sh`) use `install_npm_global()` from `modulos/lib.sh` (which in turn
calls `ensure_node_installed()` — installs `nodejs-lts` only if missing). `psqlformat.sh` also
installs `perl` (pkg) as a dependency before the `npm install -g` — the uninstall (see below)
only reverts the `psqlformat` npm package, never `perl` itself (shared runtime, it might be in use
by the Languages module or another tool).

## Node.js dependency

All 10 tools depend on Node.js. If it is missing, each script installs it on its own
(`ensure_node_installed()`) — there is no need to turn on the "Node.js LTS" switch of the
Languages module first. If the user prefers to manage it separately, the Node.js switch in Languages covers
exactly the same real package (`nodejs-lts`), without duplicating anything.

## Real uninstall (`ModuleController.deepUninstallPlan`)

10 entries, all `npm uninstall -g <package>` (`ModuleController.kt:976-990`):

```kotlin
"typescript"  -> DeepUninstallPlan("npm uninstall -g typescript", ...)
"nestjs"      -> DeepUninstallPlan("npm uninstall -g @nestjs/cli", ...)
"prettier"    -> DeepUninstallPlan("npm uninstall -g prettier", ...)
"livesrv"     -> DeepUninstallPlan("npm uninstall -g live-server", ...)
"localtunnel" -> DeepUninstallPlan("npm uninstall -g localtunnel", ...)
"vercel"      -> DeepUninstallPlan("npm uninstall -g vercel", ...)
"markserv"    -> DeepUninstallPlan("npm uninstall -g markserv", ...)
"psqlformat"  -> DeepUninstallPlan("npm uninstall -g psqlformat", ...)
"ncu"         -> DeepUninstallPlan("npm uninstall -g npm-check-updates", ...)
"ngrok"       -> DeepUninstallPlan("npm uninstall -g ngrok", ...)
```

None of them deletes Node.js itself (a runtime shared with other modules) — same criterion as the rest
of `ModuleController.kt` (e.g. `ollama`/`claude` never uninstall Python/Node as a side
effect).

## Installed detection (`ModuleInstalled.BINARY_FALLBACK`)

`typescript→tsc`, `nestjs→nest`, `prettier→prettier`, `livesrv→live-server`,
`localtunnel→lt`, `vercel→vercel`, `markserv→markserv`, `psqlformat→psqlformat`, `ncu→ncu`,
`ngrok→ngrok` — the same binary they already had as `terminalCommand` before becoming
`internal`.

## Shared architecture

The same files as `docs/modulos/lenguajes.en.md` (`internal` field in `ModuleInfo.kt`/
`ModuleCatalog.kt`, sentinel + `BINARY_FALLBACK` in `ModuleInstalled.kt`, filters in
`ModulesFragment.kt`/`PluginsFragment.kt`), plus:

- `app/src/main/java/com/termux/app/ui/PackagesFragment.kt` (433 lines) — reuses
  `ConsolidatedModuleFragment.kt`, same mechanism as Languages.
- `app/src/main/java/com/termux/app/ui/ModuleDetailNavigator.kt` — `"packages" ->
  PackagesFragment()`.
- `app/src/main/assets/modules.json` — `internal: true` on all 10 + the `"packages"` entry.

## Screen controls (`PackagesFragment.kt`)

Same pattern as `LanguagesFragment` (one switch per tool, optional `RunAction`s) — here with
more variety of real flags confirmed against each CLI's official documentation. UI options:
10 switches + 10 `RunAction`s (typescript 1, nestjs 1, prettier 2, vercel 3, ncu 2,
ngrok 1; `PackagesFragment.kt:56-180`) + the "Format .sql" card with a SAF picker
(`registerForActivityResult(GetContent)`, kt:48-50) = ~21 controls, grouped in 3 tabs
(`itemGroups()` kt:199).

| Tool | Switch | RunAction(s) | Non-obvious detail |
|---|---|---|---|
| TypeScript | `tsc --version` | "Create tsconfig.json" (`tsc --init`) | Safe by design: `tsc` refuses to overwrite an existing `tsconfig.json` |
| NestJS CLI | `nest --version` | "Create project" (`nest new %s --package-manager npm --skip-git`, asks for a name) | `--package-manager`/`--skip-git` prevent the CLI from prompting interactively (it would hang waiting for stdin in a `ProcessBuilder` without a TTY) |
| Prettier | `prettier --version` | "Check format" (read-only) / "Apply format" (`--write .`, **requires confirmation** — rewrites files in `$HOME`) | — |
| Live Server | `live-server --version` | None | — |
| Localtunnel | `lt --version` | None | — |
| Vercel CLI | `vercel --version` | "View session" (`whoami`) / "Log in" (`login --no-browser`, prints device-flow URL+code) / "Deploy to production" (`--prod --yes`) | Login never asks for a password/token — it only shows the URL/code to complete in any browser |
| Markserv | `markserv --version` | None | — |
| PSQL Format | `psqlformat --version` | None (see separate card below) | Needs a concrete `.sql` file — it does not fit as a one-line RunAction over `$HOME` |
| NPM Check Updates | `ncu --version` | "Check for updates" (read-only) / "Update package.json" (`ncu -u`, **requires confirmation**) | — |
| Ngrok | `ngrok --version` | "Configure authtoken" (`config add-authtoken %s`, asks for the token) | The token is never logged/shown in plain text in any toast |

This module is scoped to **10 npm development tools** (it is not a general catalog of
Termux/apt packages) — each `RunAction` is confirmed against its CLI's official documentation.

**"FORMAT .SQL FILE (psqlformat)" card** — "📄 Pick .sql file": opens the SAF picker,
copies the chosen file to a real temporary file inside Termux's sandbox (psqlformat cannot
read a `content://` Uri), runs `psqlformat <file>` (read-only — it never writes back,
`--write` is not exposed) and shows the result in a scrollable dialog. The temporary file is deleted
always, on success or error.

## Tabs by category

Using the `ConsolidatedModuleFragment.itemGroups()` hook on top of the
`BaseModuleFragment.setupTabs()` helper, the 10 tools are grouped into 3 tabs by real function
(`LanguagesFragment` does not override this hook and keeps a single card):

| Tab | Tools | Criterion |
|---|---|---|
| **Format and quality** | TypeScript, Prettier, NPM Check Updates, PSQL Format | Operate on existing code/config, with no server process of their own |
| **Servers and tunnels** | Live Server, Markserv, Localtunnel, Ngrok | Long-running processes / network exposure |
| **Deploy and frameworks** | NestJS CLI, Vercel CLI | Scaffolding new projects + publishing |

The "WHAT IT IS" (intro) card and the "FORMAT .SQL FILE (psqlformat)" card (`addExtraRows()`)
stay OUTSIDE the tabs, above them — the order of `ConsolidatedModuleFragment.
buildContent()`: intro → extra rows → tool list.

## Known limitations

- `prettier --check/--write .` and `vercel --prod --yes` act on `$HOME` (the whole home folder,
  not a chosen project); a destructive action only mitigated by the confirmation dialog.
- `vercel --prod` requires `$HOME` to already be linked to a project (comment in the code
  itself, kt:~127-131).
- `ngrok config add-authtoken %s` receives the token through a text field without logging it
  (`RunAction.requiresTextInput`); ngrok persists it in its own config file (path and
  encryption not verified).
- `ncu -u` rewrites `$HOME`'s `package.json` (with confirmation).
- All tools need Node.js (`ensure_node_installed`, `lib.sh:533`).

`PackagesFragment.kt` added on 2026-08-18.
