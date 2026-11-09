# Git / GitHub (`git`) — Kairos Module

> Sources: `modulos/git.sh`, `app/src/main/java/com/termux/app/ui/GitFragment.kt` and the entry in
> `app/src/main/assets/modules.json`. Anything marked "inference" has not been verified on a device.

## 1. What it is and what it is for
General-purpose Git + GitHub CLI module: it operates on ANY folder under `~/proyectos`
(status / commit+push / pull / clone) and exposes four basic `gh` actions. It was extracted from
`ExpoFragment` (`git.sh:7-15`, `GitFragment.kt:16-23`). Id `git`, name "Git / GitHub",
script `git.sh`, `category: dev`, `requires: []`, `catalogVersion 1.0.0` (`modules.json`).

## 2. Classification
**FUNCTIONAL MODULE** (own UI with state and real operations), although lightweight: it has no
service/port, but it does have a dedicated screen (`ModuleDetailNavigator.kt:33` → `GitFragment`), a
status card (git version, gh version, active project — `GitFragment.kt:46-50`) and **9 buttons of
its own** (manage projects, clone, status, commit+push, pull, gh auth status, gh login,
gh repo create, gh pr list) + the common maintenance card (`addMaintenanceCard`, line 75).
Approx. 9-12 UI options.

## 3. Installation
- `git.sh --silent [--force]`; `--describe`/`--describe-files` return manifests (`git.sh:53-79`).
- Installs with `install_single_pkg "git" "git" git` and `install_single_pkg "gh" "gh" gh`
  (`git.sh:91-92`): `pkg install -y`, idempotent via `command -v` (unless `--force`), real
  verification with `verify_binary_installed` (runs `--version`) and `registry_install` (`lib.sh:510-528`).
- No direct downloads: everything comes from Termux apt, signed by the official repo (no sha256 of its own).
- Writes the registry keys `git.*` and `gh.*` (the `gh` key is shared with the `gh` module).
- `--describe-files` declares `files: []` on purpose (it is not packaged as its own .deb, `git.sh:61-77`).

## 4. Execution
Not a service: no start/stop, port, tmux or persistent process (`hasSwitch:false`,
`port:""`). Each action runs a one-off `bash -c` through `ManagerNativeUtils.runShell(cmd, timeout)`
(`GitFragment.kt:98,124`) with timeouts of 10-120 s, shown in a "Running…" AlertDialog.

## 5. UI functions (what is behind them)
| Control | Real operation |
|---|---|
| Manage projects | `showProjectsMenu` (symlink/copy/sync/delete) and sets the active project in `~/.git_active_project` |
| Clone | `mkdir -p ~/proyectos && git clone <url>` (120 s) |
| Status / Pull | `cd <project> && git status` / `git pull` |
| Commit + Push | `git add . && git commit -m <msg> ; git push` (the `;` is deliberate, lines 150-153) |
| gh auth status | `gh auth status` |
| gh login | opens `gh auth login` in the terminal (interactive) |
| gh repo create | asks for name and visibility; with an active project it runs `gh repo create … --source=. --remote=origin --push` |
| gh pr list | `gh pr list` in the active project |

## 6. Files and paths
`~/.git_active_project` (active project path), `~/proyectos/` (clone destination),
`$PREFIX/bin/git` and `gh` (apt packages), registry `~/.android_server_registry`
(`git.*`, `gh.*`), checkpoint `~/.install_git_checkpoint`.

## 7. Security
- User inputs (path, URL, commit message, repo name) go through `shellQuote(...)`
  in every command (`GitFragment.kt:142-236`): there is no unescaped concatenation.
- It stores no tokens: `gh auth login` stays in the terminal and gh keeps its credentials in its
  own config directory (upstream behavior, not verified here; inference).
- No `curl | bash` or arbitrary downloads.

## 8. Known limitations
- Neither `git.sh` nor `GitFragment` configures `user.name`/`user.email`; a first `git commit` on a
  clean device fails ("Please tell me who you are") and, because of the `;`, the flow continues to
  `git push` with "nothing to push". An identity step/dialog is missing.
- `git add .` adds everything with no review or assisted `.gitignore`; there is no branch selection nor
  `git diff/log/branch/checkout`.
- `gh` actions are limited to 4 (the comment at `GitFragment.kt:63-67` lists as pending:
  issues, create/merge PR, list own repos).
- Long output is shown only in a text dialog with no dedicated scroll or copy.
- Inference: `git clone` over HTTPS of private repos will require credentials (gh helper) that the
  UI does not configure (`gh auth setup-git` is not invoked).
- `gh` also exists as a separate `internal` module (see `GH.md`) with the same registry key;
  the duplication is deliberate (`git.sh:85-90`).

## 9. Date added
`modulos/git.sh`: **2026-09-08**.
