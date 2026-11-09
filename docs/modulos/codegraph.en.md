# CodeGraph — `codegraph` module (static code analysis)

> Summary of the CLI set in `CLI_TOOLS.md`. Not tested on a device.

## 1. What it is
CodeGraph (`colbymchenry/codegraph`): a static analysis tool that builds a graph of relationships between the
files/functions/classes of a project. It is NOT an AI agent (`modules.json`: `codegraph`, `type: Nativo`,
`category: dev`, `installMethods: ["github_release"]`, `requires: []`, `terminalCommand: "codegraph"`, ~60MB, ~2 min).
Visible in the Store (not `internal`).

## 2. Classification: FUNCTIONAL MODULE (lightweight)
It has its own UI with actions that run real subcommands on a chosen project (it does not just install and open a terminal).
It shares the `CliToolFragment` fragment with 13 other CLIs (`ModuleDetailNavigator.kt:61`), configured in `CliToolFragment.kt:377-390`.
UI options: ~10 (see §5). No background service and no port.

## 3. Installation
- `modulos/codegraph.sh` (397 lines; version 2.0.0 per its header). Flags: `--silent`, `--force`, `--describe`, `--describe-files`
  (`moduledeb` manifest, `:101-135`). In non-silent mode it asks for confirmation via `/dev/tty` (`:237`); the app uses `--silent`.
- Two methods in cascade. Note: the script header (lines ~31-48) describes the native fork as "METHOD 1, preferred", but the code
  does the opposite: the original method goes first and the fork is the fallback (`:253-256`, `:361-378`).
  1. STEP 1 (`:258`): if `uname -m` = aarch64, installs any missing `glibc-repo glibc dpkg curl` (failure = `warn` only).
  2. STEPS 3-5 (subshell, `:285-358`): dependencies (`nodejs-lts ripgrep sqlite git clang make curl`), downloads
     `codegraph-linux-arm64.tar.gz` from the latest `colbymchenry/codegraph` release into `~/.local/share/kairos-data/codegraph-linux-arm64`,
     and creates the wrapper `$PREFIX/bin/codegraph` (`exec node .../lib/dist/bin/codegraph.js "$@"`).
  3. STEP 5b (`:361-378`): if `verify_binary_installed codegraph --help` fails, `_codegraph_download_native_fork` (`:161`) downloads the aarch64 `.deb`
     from `Honkonx/codegraph-termux` (or `Hope2333/codegraph-termux`), extracts it with `dpkg-deb -x` and copies `usr/` into `$PREFIX`; it ships its own glibc Node.
- Verification: `verify_binary_installed codegraph --help` (`:353`, `:380`; uses `--help`, not `--version`).
- Registry (`:388`): `installed=true`, `version`, `channel=github_release|fork_native_arm64`, `install_date`. Uses checkpoints (`~/.install_codegraph_checkpoint`), deleted at the end.
- Already installed and no `--force`: `exit 0` without touching the registry (`:~243`).

## 4. Execution
There is no resident process. Each action opens the integrated terminal with the command (`launchCliTerminalCommand`), doing `cd` into the chosen project folder.

## 5. UI options (`CliToolFragment.kt:466-620`)
Cards: Status (id/version/command) and Use. Buttons: 1 "Open in terminal" (default or chosen folder), 2 "Manage projects",
3 "Analyze project" (`codegraph init '<path>' && codegraph status '<path>'`), 4 search symbol (`codegraph query '<symbol>'`),
5-7 "callers", "callees", "impact" (`codegraph callers|callees|impact '<symbol>'`), 8 "affected files" (`codegraph affected '<path>'`),
9 "Update", 10 "Uninstall" (with a deep-uninstall option). No login and no local provider (`hasAuth=false`, `localProviderCapable=false`).

## 6. Files and paths
`$PREFIX/bin/codegraph`; method 2 data `~/.local/share/kairos-data/codegraph-linux-arm64/` (+ `version` file); fork: `$PREFIX/lib/codegraph-termux/<ver>/`;
the binary's own config `~/.codegraph`; registry `~/.android_server_registry`.

## 7. Security
Downloads binaries from GitHub Releases without hash verification (`verify_sha256` is not used in this script). Method 2 takes the "latest release"
via the `releases/latest` redirect (no pin). The fallback fork copies a whole `usr/` tree over `$PREFIX` (it may overwrite files). It handles no secrets.

## 8. Known limitations
- The script header is out of step with the real order of methods (§3).
- No integrity (hash) verification of what is downloaded; no version pin.
- Actions are only launched in the terminal (there is no native panel with the graph/results); the analysis is not shown in a UI of its own.
- Inference: the STEP 1 glibc dependency is always installed on aarch64 even if the original (Node) method does not use it; the cost was not measured.

## 9. Date added
`modulos/codegraph.sh`: 2026-08-18.
