# Packages

## What it is

`packages` consolidates 10 npm dev tools into a single container module
(`PackagesFragment.kt`) with a switch per tool — each one is enabled or disabled
directly, with no intermediate install dialogs.

## The 10 tools

| Tool | id | Real script | npm package | Version command |
|---|---|---|---|---|
| TypeScript | `typescript` | `modulos/typescript.sh` | `typescript` (`tsc`) | `tsc --version` |
| NestJS CLI | `nestjs` | `modulos/nestjs.sh` | `@nestjs/cli` (`nest`) | `nest --version` |
| Prettier | `prettier` | `modulos/prettier.sh` | `prettier` | `prettier --version` |
| Live Server | `livesrv` | `modulos/livesrv.sh` | `live-server` | `live-server --version` |
| Localtunnel | `localtunnel` | `modulos/localtunnel.sh` | `localtunnel` (`lt`) | `lt --version` |
| Vercel CLI | `vercel` | `modulos/vercel.sh` | `vercel` | `vercel --version` |
| Markserv | `markserv` | `modulos/markserv.sh` | `markserv` | `markserv --version` |
| PSQL Format | `psqlformat` | `modulos/psqlformat.sh` | `psqlformat` (+ Perl via pkg) | `psqlformat --version` |
| NPM Check Updates | `ncu` | `modulos/ncu.sh` | `npm-check-updates` (`ncu`) | `ncu --version` |
| Ngrok | `ngrok` | `modulos/ngrok.sh` | `ngrok` | `ngrok --version` |

The 10 scripts (`modulos/<id>.sh`) use a shared helper in `modulos/lib.sh` that installs
Node.js if it's missing before `npm install -g`. `psqlformat.sh` also installs `perl`
(pkg) as a dependency before `npm install -g` — uninstall only reverts the npm package
`psqlformat`, never `perl` itself (shared runtime, could be in use by another tool).

## Node.js dependency

All 10 tools depend on Node.js. If it's missing, each script installs it on its own —
there's no need to first flip the "Node.js LTS" switch in the Languages module. If the
user prefers to manage it separately, the Node.js switch in Languages covers exactly the
same real package (`nodejs-lts`), without duplicating anything.

## Real uninstall (`ModuleController.deepUninstallPlan`)

10 entries, all `npm uninstall -g <package>`:

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

None of them delete Node.js itself (a runtime shared with other modules).

## Installed detection

`typescript→tsc`, `nestjs→nest`, `prettier→prettier`, `livesrv→live-server`,
`localtunnel→lt`, `vercel→vercel`, `markserv→markserv`, `psqlformat→psqlformat`,
`ncu→ncu`, `ngrok→ngrok`.

## Screen controls (`PackagesFragment.kt`)

A switch per tool plus optional actions, with real flags confirmed against each CLI's
official documentation:

| Tool | Switch | Actions | Non-obvious detail |
|---|---|---|---|
| TypeScript | `tsc --version` | "Create tsconfig.json" (`tsc --init`) | `tsc` refuses to overwrite an existing `tsconfig.json` |
| NestJS CLI | `nest --version` | "Create project" (`nest new %s --package-manager npm --skip-git`, asks for a name) | `--package-manager`/`--skip-git` prevent the CLI from prompting interactively (it would hang waiting for input in a process with no terminal) |
| Prettier | `prettier --version` | "Check formatting" (read-only) / "Apply formatting" (`--write .`, requires confirmation) | — |
| Live Server | `live-server --version` | None | — |
| Localtunnel | `lt --version` | None | — |
| Vercel CLI | `vercel --version` | "View session" (`whoami`) / "Log in" (`login --no-browser`, prints URL+device-flow code) / "Deploy to production" (`--prod --yes`) | Login never asks for a password/token — it only shows the URL/code to complete in any browser |
| Markserv | `markserv --version` | None | — |
| PSQL Format | `psqlformat --version` | See separate card below | Needs an actual `.sql` file |
| NPM Check Updates | `ncu --version` | "Check for updates" (read-only) / "Update package.json" (`ncu -u`, requires confirmation) | — |
| Ngrok | `ngrok --version` | "Configure authtoken" (`config add-authtoken %s`, asks for the token) | The token is never logged/shown in plain text in any message |

**"Format .sql file (psqlformat)" card**: "Choose .sql file" opens the system picker,
copies the chosen file to a temp file inside the Termux sandbox, runs `psqlformat
<file>` (read-only — never writes back) and shows the result in a scrollable dialog. The
temp file is always deleted, whether it succeeds or fails.

## Tabs by category

The 10 tools of the single "Packages" card are grouped into 3 tabs by actual function:

| Tab | Tools | Criterion |
|---|---|---|
| **Formatting and quality** | TypeScript, Prettier, NPM Check Updates, PSQL Format | Operate on existing code/config, with no server process of their own |
| **Servers and tunnels** | Live Server, Markserv, Localtunnel, Ngrok | Long-running processes / network exposure |
| **Deploy and frameworks** | NestJS CLI, Vercel CLI | Scaffolding new projects + publishing |

The intro card and the "Format .sql file" card stay outside the tabs, above them.

This module is scoped to 10 npm development tools — it is not a general catalog of
Termux/apt packages. A catalog of general Termux packages (compression, editors, network
utilities) would be a different module/scope.
