# GitHub CLI (`gh`) — Kairos Module (internal)

> Sources: `modulos/gh.sh`, the `modules.json` entry, `ModuleDetailNavigator.kt`, `GenericModuleFragment.kt`.

## 1. What it is and what it is for
Installs the official GitHub client (`gh`, a Termux apt package) for repos/PRs/issues from the
terminal.

## 2. Classification
**ADD-ON / EXTENSION**. It only installs a tool and offers "open in terminal"
(`terminalCommand: "gh"`). It is also marked `"internal": true` in `modules.json`: it does not appear in
the Store or in the module list (`ModuleInfo.kt:50`). It has no Fragment of its own: it is not listed in
`ModuleDetailNavigator.kt`, so it falls back to `GenericModuleFragment` (status, "Open in terminal",
"Manage projects", maintenance). ~4-5 generic UI options. The real `gh` experience lives in the
`git` module (`GitFragment`, see `GIT.md`), which also installs `gh`.

## 3. Installation
`gh.sh --silent [--force]` → `install_single_pkg "gh" "gh" gh` (`gh.sh:167`): `pkg install -y gh`,
idempotent (`command -v`), `verify_binary_installed gh` (runs `gh --version`) and
`registry_install`. No variants, no dependencies, no direct downloads (official apt, no
sha256 of its own). `--describe-files` with a deliberate `files: []`. It does not log in (`gh auth login`
is left manual, `gh.sh:12-14`).

## 4. Execution
Not a service (no port, tmux or switch). On-demand CLI only.

## 5. UI functions
Those of `GenericModuleFragment`: installed/not installed status, open-terminal button with `gh` (with folder
selection), manage projects, update / diagnose / uninstall.

## 6. Files and paths
`$PREFIX/bin/gh`; registry `gh.*`; checkpoint `~/.install_gh_checkpoint`. Credentials: stored by
`gh` in its own config (not managed by Kairos).

## 7. Security
No secrets handled by Kairos, no `curl|bash`, no downloads outside apt. Minimal risk.

## 8. Known limitations
- Hidden module (`internal`) but with its own script, catalog entry and manifests: it is
  functionally a subset of `git.sh`, which already installs `gh` (deliberate duplication).
- There is no login flow in its own UI (it lives in `GitFragment` → "gh login" via terminal).
- Inference: being `internal`, a user cannot install it alone from the Store; it arrives via `git`.

## 9. Date added
`modulos/gh.sh` added on **2026-08-31**.
