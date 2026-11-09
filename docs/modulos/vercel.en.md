# VERCEL.md — `vercel` module (Vercel CLI)

> Based on reading the code. Summary of the whole set in `PAQUETES.md`. Not tested on a device.

## 1. What it is
Vercel CLI (`vercel`, npm package) for deploying projects. `modules.json`: `vercel`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]`, `terminalCommand: "vercel"`, ~15MB.

## 2. Classification: ADD-ON/EXTENSION (the most "usable" of the batch)
Installs the CLI and exposes 3 native actions (session, login, deploy). No service or app-owned state.
UI options: 4 (switch + 3 actions).

## 3. Installation
`modulos/vercel.sh:81` → `install_npm_global "vercel" "vercel" "vercel"` (flow in `lib.sh:613`; post-condition
`vercel --version`; `registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Execution
Each action runs `cd "$HOME" && <command>` and shows the output at the end in a dialog.

## 5. UI options (`PackagesFragment.kt:107-138`, «Deploy and frameworks» tab)
1. Install/uninstall switch (`npm uninstall -g vercel`).
2. «View session» → `vercel whoami` (`formatVercelWhoami`).
3. «Login» → `vercel login --no-browser` (20 s, partial read, `readPartialOutput`): shows URL + verification code.
4. «Production deploy» → `vercel --prod --yes` (150 s), shows the deployment URL or the error.

## 6. Files and paths
`$PREFIX/bin/vercel`, `$(npm root -g)/vercel`, registry `vercel.version`; the credential is stored by the CLI itself (outside Kairos,
typically in `~/.local/share/com.vercel.cli`; inference: path not verified).

## 7. Security
The app handles no password/token (login via device flow). `deploy --prod --yes` publishes the content of the
project linked in `$HOME` with no additional confirmation (unlike `prettier --write`/`ncu -u`, this action does NOT have
`requiresConfirmation`, `PackagesFragment.kt:133-136`).

## 8. Issues / limitations
- The deploy publishes to production with a single tap and no confirmation dialog.
- The deploy only works if `$HOME` is already a linked project (limitation declared in the code, `:130-132`); there is no `vercel link` or folder selection.
- Inference (risk): login is cut off at 20 s by killing the process; if the CLI needs to stay alive polling in order to save the token, logging in from the app might not complete. Not verified on a device.

## 9. Added on
`modulos/vercel.sh`: 2026-08-18.
