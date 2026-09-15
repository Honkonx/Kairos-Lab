# Codebuff

Codebuff (CodebuffAI) — a coding-agent CLI, compatible with any model via OpenRouter (no
automatable login/API key of its own). Falls into the shared `CliToolFragment.kt` Fragment (see
`docs/modulos/cli-tools.en.md`) — with no documented login/direct prompt/model via command
line, it's left with "Open in terminal" + project management.

## 1. Installation (`modulos/codebuff.sh`) — a 2-stage mechanism

The real npm package `codebuff` isn't the app itself — it's a **thin launcher** (~35KB, no
native addons) that only downloads the real binary the **first time** the `codebuff` command is
run (into the launcher's config directory). The script triggers that first run on purpose
(`timeout 90 codebuff --version`) to force the download, and only then applies `patchelf`
(the same glibc pattern used by other modules that install glibc binaries on top of Bionic).

Real issues resolved in the installation script:
- The symlink left by `npm install -g` has a `#!/usr/bin/env node` shebang that doesn't exist
  as-is in Termux — the wrapper is fixed after installing.
- The launcher computes its download key from the platform/architecture detected at runtime,
  which doesn't match any key in its own supported-targets map on Android/Termux —
  `CODEBUFF_BINARY_TARGET=linux-arm64` is forced (an official override the launcher itself
  exposes via an environment variable, no patching involved), and it's persisted in `~/.bashrc`
  so it also applies to new terminal sessions.
- `codebuff --version` doesn't respond quickly (the launcher draws an animated ASCII banner
  before any output) — the installation check doesn't require a parsed semver, it only rules
  out the known fatal errors ("Unsupported platform"/"ENOENT").

## 2. Screen controls (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["codebuff"]`)

| Control | What it does | Why |
|---|---|---|
| "⌨ Open in terminal" | `unset LD_PRELOAD; codebuff` | With no documented login/prompt/model — model provider configuration (OpenRouter) is left for the user to do manually inside the session |
| "🗂 Manage projects" | Shared project management menu | Same mechanism shared with the rest of the CLIs |
| "＋ Create project from template" | `codebuff --create '<template>' '<name>'` | 2-field dialog (template + name) |
| "🔄 Update" / "🗑 Uninstall" | Standard maintenance | — |
