# Expo — Kairos Module

**Kairos Module** — managed through the Kairos UI (Modules tab). Installation is handled by the app via `ProcessBuilder` → `modulos/expo.sh` (438 lines); runtime interaction (build, login, git push) runs directly from `ExpoFragment.kt` (467 lines).

---

**Project:** Kairos
**Script:** `modulos/expo.sh` (v2.0.0) — mirror at `app/src/main/assets/scripts/expo.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ExpoFragment.kt` (`ModuleDetailNavigator.kt:32`)
**`modules.json`:** `id: "expo"`, `type: "Nativo"`, `requires: ["nodejs"]`, `size ~350MB`, `hasSwitch: false` (CLI tool, no process of its own, no port)

---

## 1. Overview

Expo (via **EAS CLI**, Expo Application Services, npm package `eas-cli`) lets you build React Native/Expo apps **in the cloud** from the phone itself — the real build runs on Expo's servers, not on the device, so it does not compete for CPU/RAM with the rest of the stack. It is useful for developing/maintaining React Native apps directly from Termux without needing a PC.

**Classification: FUNCTIONAL MODULE.** Status card (EAS version, Node, Expo user, active project) and ~13 buttons (build preview, build production, view builds, build profiles, doctor, publish OTA, submit, login, logout, info, choose project, git push, update CLI) + a maintenance card. No service; it operates on a chosen project. It has no persistent process — it is a CLI invoked on demand for build/status/submit/push.

## 2. Permissions

- **Android**: none specific — it needs no extra storage beyond the wizard, opens no ports, needs no overlay/notifications.
- **Termux internals**: nothing special — it uses `npm`/`node`/`git` already installed by the script itself.
- **External account**: requires login at `expo.dev` (`eas login`, interactive — opened in the terminal; the app does not automate credentials).

## 3. Full installation logic (`modulos/expo.sh`)

A 5-step script with checkpoints (`$HOME/.install_expo_checkpoint`):

| Step | What it does | Checkpoint |
|---|---|---|
| 1 | `pkg update` with its own mirror fallback — rewrites `$PREFIX/etc/apt/sources.list` with a fixed mirror (`expo.sh:~145-160`) instead of using `pkg_update_with_fallback`; skipped if `instalar.sh`/`kairos.sh` already did it (`$ANDROID_SERVER_READY`) | `termux_update` |
| 2 | Node.js (pkg `nodejs`; installs if missing, or upgrades if `< 18`) + `git` | `nodejs_git` |
| 3 | `npm install -g eas-cli` (no pinned version, no sha256), retries `command -v eas` with `hash -r` and applies `fix_npm_shebang_wrapper eas eas-cli` | `eas_install` |
| 4 | Generates 5 control scripts in `~/scripts/expo/`: `eas_build.sh` (build with preview/production profile, validates login and `package.json` before starting, runs `eas build:configure` if `eas.json` is missing), `eas_status.sh` (`eas build:list`), `eas_submit.sh`, `git_push.sh` (add+commit+push of the active project), `expo_info.sh` (JSON with node/npm/eas/git/user) | `expo_scripts` |
| 5 | Aliases in `.bashrc` (`expo-build`, `expo-status`, `expo-submit`, `expo-push`, `expo-login`, `expo-info`) + registry | `expo_aliases` |

Closing: `verify_binary_installed eas` (runs `--version`) and `registry_install expo`.

**Supported flags**: `--silent`, `--force`, `--describe` (`{"id":"expo",...,"variants":[],"variant_required":false}`).

**What it deliberately does NOT do**: it does not ask for `expo.dev` login during installation (left for later, from the app), and it does not ask for storage permission (handled by the Kairos wizard).

## 4. State detection

- **Installation**: `command -v eas`. It exits early if `eas` already exists and `--force` is not given, **without repairing the registry** (if `eas` exists but `expo.installed` is missing, the app shows it as "not installed" until a forced reinstall).
- **"Running"**: not applicable — `hasSwitch: false`.

## 5. App screen (`ExpoFragment.kt`)

"STATUS" card (EAS CLI version, Node version, expo.dev user, active project — loaded on open with `buildInfoJson()`) + buttons:

| Button | Real action |
|---|---|
| 🔨 Build APK preview | `eas build --platform android --profile preview --non-interactive` (env `EAS_SKIP_AUTO_FINGERPRINT=1`) on the active project; runs `eas build:configure -p android` (60 s) first if `eas.json` is missing |
| 📦 Production build (AAB) | Same, `production` profile |
| 🌐 View active builds | `eas build:list --platform android --limit 5 --json` (falls back to plain text if the JSON fails), dialog with the real list |
| 🗂 Build profiles (eas.json) | Reads the real `eas.json["build"]` of the active project (map of named profiles, `docs.expo.dev/eas/json/`) and lists them in a dialog — tapping a profile triggers the same `runExpoAction("build", <profile>)` as the fixed buttons |
| 🩺 Diagnostics (expo-doctor) | `npx expo-doctor` (90 s) on the active project (`docs.expo.dev/develop/expo-doctor/`), result in a dialog (`showDoctorDialog()`) |
| 🚀 Publish OTA update | `eas update --branch <branch> --message <message> --non-interactive` — publishes JS/assets changes without a new native build or store review. Dialog with 2 fields (branch, default `production`; message) via `showEasUpdateDialog()` |
| 📮 EAS Submit (send to store) | `launchTerminalCommand("eas submit --platform android")` — goes to the terminal (not `--non-interactive`) because in most cases it needs more interactive context (build/credential selection) |
| 👤 Log in to expo.dev | `launchTerminalCommand("eas login")` — interactive, in the adapted terminal |
| 🔓 Log out of expo.dev | `launchTerminalCommand("eas logout")` |
| ℹ Info / general status | `eas --version`, `node --version`, `eas whoami`, plus the stored active project |
| 📁 Set active project | Lists folders of `~/proyectos` (`File.listFiles()` directly, no subprocess) and saves the chosen one to `~/.eas_active_project` |
| ⬆ Git push (active project) | `git add .` + `git status --short` + `git commit -m <message>` + `git push` on the active project |
| ⬆ Update EAS CLI | `launchTerminalCommand("npm install -g eas-cli@latest")` — a standalone npm command with no interaction |
| MAINTENANCE card | Full `addMaintenanceCard()`: Update (via `expo.sh --silent`) + Uninstall |

The "active project" is stored in a flat file (`~/.eas_active_project`, path only) — there is no symlink registry like the one used by Claude Code/OpenCode/Antigravity (`ProjectsManager.kt`); Expo uses real folders under `~/proyectos` directly.

**Execution**: no service. The app runs one-off commands with its own `runCommand` (ProcessBuilder with `LD_PRELOAD=libtermux-exec-ld-preload.so` to resolve the npm shebang, with per-action timeouts, `ExpoFragment.kt:423-460`): `eas --version`, `eas whoami`, `eas build:configure -p android`, `eas build …`, `eas build:list --json`, `npx expo-doctor`, `eas update`. Login/logout/submit/update CLI open the terminal (`launchTerminalCommand`). Each argument goes as a separate `ProcessBuilder` element (no shell in between), so values such as a commit message with an apostrophe need no escaping.

## 6. Registry (`~/.android_server_registry`)

```
expo.installed=true
expo.version=<eas-cli version>
expo.install_date=<YYYY-MM-DD>
expo.commands=expo-build,expo-status,expo-submit,expo-push,expo-login,expo-info
expo.port=none
expo.location=termux_native
```

## 7. Files

`~/scripts/expo/*.sh`, `~/.bashrc` (aliases), `~/.eas_active_project` (active project), global npm package `eas-cli`, registry `expo.*`, checkpoint `~/.install_expo_checkpoint`.

## 8. Technical notes

- **`ACTION_VIEW` with a "command" extra does not work**: `TermuxActivity` never reads that extra, so the real mechanism for opening commands in the terminal is `launchTerminalCommand()` (`BaseModuleFragment`), not an Intent with extras (comment in `openLoginSession()`).
- **Shebang of `eas` after `npm install -g`**: `expo.sh` does its own `npm install -g eas-cli` by hand (it does not go through the generic `install_npm_global()` helper of `lib.sh`, because it needs its own retry logic + full log), so it must explicitly call `fix_npm_shebang_wrapper eas eas-cli` after the install. Without it, the symlink npm creates for the `eas` binary may not execute directly on this device (the `#!/usr/bin/env node` shebang does not run at runtime; the same underlying bug documented in `lib.sh` for OpenClaw/Cursor): `command -v eas` passes (the file exists and is executable) but `verify_binary_installed eas`, which actually invokes the binary, fails. The problem can be intermittent depending on how Android rewrites the symlink in that particular install.

## 9. Security

No `curl|bash`. Expo credentials (`eas login`) are stored by eas-cli in its own config; Kairos neither touches nor shows them. User parameters (branch, message, path) go through `ProcessBuilder` as an argument list (no shell). `git_push.sh` does a blind `git add .`. Rewriting `sources.list` with a fixed mirror is a persistent change to the package system.

## 10. Known limitations

- `EXPO_SCRIPTS` (the 5 scripts in `~/scripts/expo/`) and the `expo-*` aliases are only used by the terminal; the current UI does not call them (it uses `runCommand` directly). Duplicated/legacy code.
- `git_push.sh` keeps a push of its own that has already been extracted into the `git` module (`GIT.md`).
- The fixed mirror in STEP 1 (`sources.list`) contradicts the centralization in `lib.sh`; risk of leaving outdated mirrors.
- `eas submit` (interactive flow) and `eas login` are terminal-only: there is no UI of its own for credentials (a decision consistent with not handling secrets, but it leaves those actions outside the UI).
- Inconsistent metadata: `catalogVersion 1.0.0` / header `2.0.0` / banner `v2.0.0` and repo `termux-ai-stack` in the header.
- Inference: cloud `eas build` was not tested (it requires an Expo account and network).

## 11. Date added

`modulos/expo.sh`: **2026-07-19**.
