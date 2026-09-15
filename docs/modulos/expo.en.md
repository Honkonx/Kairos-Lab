# Expo

**Kairos module** — managed via the Kairos UI (Modules tab). Installation handled by the app via `ProcessBuilder` → `modulos/expo.sh`; runtime interaction (build, login, git push) runs directly from `ExpoFragment.kt`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/expo.sh` — mirrored at `app/src/main/assets/scripts/expo.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ExpoFragment.kt`
**`id` in `modules.json`:** `expo` — `hasSwitch: false` (CLI tool, no process of its own)

---

## 1. General Description

Expo (via the **EAS CLI**, Expo Application Services) lets you build React Native/Expo apps **in the cloud** directly from the phone — the actual build runs on Expo's servers, not on the device, so it doesn't compete for CPU/RAM with the rest of the stack. It's useful for developing/maintaining React Native apps directly from Termux without needing a PC.

No persistent process (`hasSwitch: false`) — it's a CLI invoked on demand for build/status/submit/push.

## 2. Permissions

- **Android**: none specific — requires no extra storage beyond the wizard, doesn't open ports, doesn't need overlay/notifications.
- **Internal Termux**: none special — uses `npm`/`node`/`git` already installed by the script itself.
- **External account**: requires login at `expo.dev` (`eas login`, interactive — opened in the terminal, the app doesn't automate credentials).

## 3. Complete installation logic (`modulos/expo.sh`)

5-step script with checkpoints (`$HOME/.install_expo_checkpoint`):

| Step | What it does | Checkpoint |
|---|---|---|
| 1 | `pkg update` (with fallback to 2 mirrors) — skipped if the global installer already did it (`$ANDROID_SERVER_READY`) | `termux_update` |
| 2 | Node.js (installs if missing, or upgrades if `< 18`) + `git` | `nodejs_git` |
| 3 | `npm install -g eas-cli` | `eas_install` |
| 4 | Generates 5 control scripts under `~/scripts/expo/`: `eas_build.sh` (build with a preview/production profile, validates login and `package.json` before starting, runs `eas build:configure` if `eas.json` is missing), `eas_status.sh` (`eas build:list`), `eas_submit.sh`, `git_push.sh` (add+commit+push of the active project), `expo_info.sh` (JSON with node/npm/eas/git/user) | `expo_scripts` |
| 5 | Aliases (`expo-build`, `expo-status`, `expo-submit`, `expo-push`, `expo-login`, `expo-info`) + registry | `expo_aliases` |

**Supported flags**: `--silent`, `--force`, `--describe` (`{"id":"expo",...,"variants":[],"variant_required":false}`).

**What it deliberately does NOT do**: doesn't ask for `expo.dev` login during installation (left for later, from the app), doesn't ask for storage permission (handled by the Kairos wizard).

## 4. State detection

- **Installation**: `command -v eas` (the script doesn't mark any special state beyond this — exits early if `eas` already exists and there's no `--force`).
- **"Running"**: not applicable — `hasSwitch: false`.

## 5. Real app screen (`ExpoFragment.kt`)

"STATUS" card (EAS CLI version, Node version, expo.dev user, active project) + buttons:

| Button | Real action |
|---|---|
| 🔨 Build preview APK | `eas build --platform android --profile preview --non-interactive` (env `EAS_SKIP_AUTO_FINGERPRINT=1`) on the active project |
| 📦 Build production (AAB) | Same, `production` profile |
| 🌐 View active builds | `eas build:list --platform android --limit 5 --json` (falls back to plain text if JSON fails) |
| 🗂 Build profiles (eas.json) | Reads the active project's real `eas.json["build"]` (a map of named profiles, `docs.expo.dev/eas/json/`) and lists them in a dialog — tapping a profile triggers the same `runExpoAction("build", <profile>)` as the fixed buttons above, covering any custom profile beyond preview/production |
| 🩺 Diagnostics (expo-doctor) | `npx expo-doctor` on the active project, result in a dialog (`showDoctorDialog()`) |
| 🚀 Publish OTA update | `eas update --branch <branch> --message <message> --non-interactive` on the active project — publishes JS/asset changes without a new native build or store review. Dialog with 2 fields (branch, default `production`; message) via `showEasUpdateDialog()` |
| 📮 EAS Submit (send to store) | `launchTerminalCommand("eas submit --platform android")` — goes to the terminal (not `--non-interactive` like Build/Update) because it usually needs more interactive context (build/credential selection) |
| 👤 Log in to expo.dev | `launchTerminalCommand("eas login")` — interactive, in the adapted terminal |
| 🔓 Log out of expo.dev | `launchTerminalCommand("eas logout")` |
| ℹ Info / general status | `eas --version`, `node --version`, `eas whoami`, plus the saved active project |
| 📁 Configure active project | Lists folders under `~/proyectos` (direct `File.listFiles()`, no subprocess) and saves the chosen one to `~/.eas_active_project` |
| ⬆ Git push (active project) | `git add .` + `git status --short` + `git commit -m <message>` + `git push` on the active project |
| ⬆ Update EAS CLI | `launchTerminalCommand("npm install -g eas-cli@latest")` |

**MAINTENANCE card**: full `addMaintenanceCard()` (Update via `expo.sh --silent` + Uninstall).

The "active project" is stored in a plain-text file (`~/.eas_active_project`, path only) — there's no symlink registry like the one Claude Code/OpenCode/Antigravity use (`ProjectsManager.kt`), Expo uses real folders under `~/proyectos` directly.

All runtime logic lives in Kotlin (`ExpoFragment.kt`) — without going through any intermediate Python script, without needing manual shell escaping (each argument goes as a separate `ProcessBuilder` element, no shell in between that could reinterpret special characters).

## 6. Registry (`~/.android_server_registry`)

```
expo.installed=true
expo.version=<eas-cli version>
expo.install_date=<YYYY-MM-DD>
expo.commands=expo-build,expo-status,expo-submit,expo-push,expo-login,expo-info
expo.port=none
expo.location=termux_native
```

## 7. Implementation notes

- **`ACTION_VIEW` with a "command" extra doesn't work**: `TermuxActivity` never reads that extra — the real mechanism is `launchTerminalCommand()` (`BaseModuleFragment`), not an Intent with extras.
- Kotlin invokes binaries directly via `ProcessBuilder` with each argument as a list element, with no shell in between, avoiding any escaping issues in commit messages or other values with special characters.
- **`eas-cli` can end up installed but not runnable**: on Termux/Android, the symlink `npm` generates for a Node binary (shebang `#!/usr/bin/env node`) sometimes isn't directly executable even though the file exists with execute permission — installation therefore verifies that `eas` actually responds (`eas --version`) instead of just confirming the file is present, and rewrites the wrapper if needed.

## Screen controls

| Control | What it does | Why |
|---|---|---|
| STATUS card (EAS CLI/Node/User/Project) | Read-only, loaded on open | — |
| "🔨 Build preview APK" | `eas build --profile preview --non-interactive` | Runs `eas build:configure` first if `eas.json` is missing |
| "📦 Build production (AAB)" | Same path, `production` profile | — |
| "🌐 View active builds" | `eas build:list --json`, dialog with the real list | — |
| "🗂 Build profiles (eas.json)" | Reads the project's real profiles, tapping one triggers a build with that profile | Covers any custom profile, not just the 2 hardcoded ones |
| "🩺 Diagnostics (expo-doctor)" | `npx expo-doctor`, dialog with the report | — |
| "🚀 Publish OTA update" | Dialog (branch + message) → `eas update --non-interactive` | Full EAS flow (build + update) |
| "📮 EAS Submit (send to store)" | Terminal — `eas submit --platform android` | Needs more interactive context (build/credential selection) than a simple dialog |
| "👤 Log in to expo.dev" / "🔓 Log out" | `eas login` / `eas logout` in the terminal | — |
| "ℹ Info / general status" | Re-runs `buildInfoJson()` | — |
| "📁 Configure active project" | Lists folders under `~/proyectos`, choosing one writes `~/.eas_active_project` | — |
| "⬆ Git push (active project)" | `git add . && git commit && git push` on the active project | — |
| "⬆ Update EAS CLI" | Terminal — `npm install -g eas-cli@latest` | A one-off npm command with no interaction, doesn't warrant a dedicated action |
| Full MAINTENANCE card (not just Uninstall) | Update + Uninstall | — |
