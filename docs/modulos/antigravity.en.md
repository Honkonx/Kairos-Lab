# Antigravity CLI — Kairos Module

**Kairos Module** — installation managed by the app via `ModuleController.installModule()` → `ProcessBuilder` → `modulos/antigravity.sh`. No on/off switch (pure CLI, no persistent process).

---

**Script:** `modulos/antigravity.sh` (275 lines; v1.0.0, adapted from the interactive script `install_antigravity.sh` v1.1.0)
**Fragment:** `AntigravityFragment.kt` (343 lines)
**`modules.json`:** `id: "antigravity"`, `hasSwitch: false`, icon `✦`
**Registry prefix:** `antigravity.*`

---

## 1. Overview

Google Antigravity CLI (`agy`), a native ARM64 binary packaged for Termux by the `Honkonx/antigravity-cli-termux` fork. It runs on top of `glibc-runner`. It is the only Kairos module with a "Sign in with Google" screen implemented inside the app itself (not delegated to the terminal) — see section 6.

**Classification: FUNCTIONAL MODULE** (own screen, `ModuleDetailNavigator.kt:25`): its own OAuth login (loopback server + browser), direct prompt with model, continue session, projects. ~11 controls. No service or persistent port (the OAuth loopback is temporary).

## 2. Permissions

No Android permissions beyond the wizard's general ones. The OAuth login opens the system browser (`Intent.ACTION_VIEW`) for the Google screen — it needs no special permission declared in the manifest beyond being able to launch external Activities (standard).

## 3. Variants

None — a single installation channel (`--describe` reports `"variants":[]`).

## 4. Installation logic (step by step)

Flags (`antigravity.sh:48-51`): `--silent`, `--force`, `--describe`, `--describe-files`.

1. **STEP 1 — System dependencies** (checkpoint `deps`):
   - Detects whether `glibc-runner` is missing (`$PREFIX/glibc/lib/ld-linux-aarch64.so.1`) — if so, installs `glibc-repo` and **then** runs `pkg update` BEFORE installing `glibc-runner` itself (without the intermediate `pkg update`, `glibc-runner` does not yet appear in the newly added indexes and the install fails with "package not found"; the sequence is repo → update → package).
   - Also installs `curl`, `ca-certificates`, `resolv-conf` if missing.
   - **LSE atomics detection** (`grep atomics /proc/cpuinfo`) — if the CPU does not support it, requires `qemu-user-aarch64` as a fallback (aborts with instructions if absent, instead of failing silently later).
2. **STEP 2 — Binary download and installation** (checkpoint `binaries`):
   - Downloads `antigravity-termux-standalone.tar.gz` from the `Honkonx/antigravity-cli-termux` fork (`latest` release, `curl -fL https://github.com/Honkonx/antigravity-cli-termux/releases/latest/download/antigravity-termux-standalone.tar.gz`), to a temporary workdir in `$HOME/.agy_install` (never `/tmp/`).
   - Extracts specifically `agy` and `agy.va39` from the tarball.
   - Installs both into `$PREFIX/bin/` with `install -m 0755`.
   - `trap '_agy_cleanup' EXIT` — if the script ends without having set `AGY_INSTALL_OK=1`, it deletes the working directory (no half-installed leftovers).
   - No sha256/checksum and no pinned version (`latest`).
3. **STEP 3 — Verification and registration**: `verify_binary_installed agy` (runs `agy --version`); on failure it writes `installed=false reason=binary_no_ejecuta`. If `agy --version` does not respond, **no** placeholder such as `"installed"` is written to the registry version field — it is left empty on purpose (a string like `"installed"` would be concatenated as-is into `"vinstalled"` on the module card; the UI already filters empty versions with `isNotEmpty()`).

## 5. State detection

"Already installed" = `command -v agy` AND both files `$PREFIX/bin/agy` + `$PREFIX/bin/agy.va39` exist. If already installed and `--force` is not given, it exits with `exit 0` without re-syncing the registry.

On the app side, the module has **no switch** — `isModuleInstalled()` gates the UI.

## 6. "Sign in with Google" login — a Kairos piece, not part of the script

Antigravity login is **not done from the terminal** — it is implemented as native OAuth inside the app itself (`com.termux.app.oauth.AntigravityOAuth`, ported from an open-source GPLv3 project, a license compatible with Kairos).

- The refresh token is stored in plain text at `~/.config/agy/oauth_refresh_token` — a path defined by Kairos (there is no prior convention). It is written with `File.writeText`, with no explicit chmod (`AntigravityFragment.kt:262-263`); the Termux home is private to the app. The UI never displays it.
- **Risk accepted on purpose, documented in the code itself** (`AntigravityFragment.kt`, full comment in `AntigravityOAuth.kt`): impersonating the Antigravity OAuth client may violate Google's Terms of Service.
- `AntigravitySecrets.kt` **has no real credentials loaded by default** (`CLIENT_ID`/`CLIENT_SECRET` empty) — login fails at the token exchange (`invalid_client`) until those values are filled in by hand.
- Flow: `startGoogleSignIn()` → `AntigravityOAuth().signIn { url -> opens the browser }` → on return, saves the refresh token → `refreshUi()` redraws the screen showing "connected".
- The stored token is read only by `AntigravityFragment`; no script or the `agy` binary consumes it (inference: `agy` does its own login on first launch; `antigravity.sh` prints "Authentication: the first time you run 'agy'").

## 7. App screen (`AntigravityFragment.kt`)

| Control | What it does | Notes |
|---|---|---|
| STATUS card (Method/Version/Status/Google account/Terminal) | Read-only: method (`native·binary`), version (`antigravity.version` from the registry), status pill, "Google account" pill (depending on whether `~/.config/agy/oauth_refresh_token` exists), terminal pill + close | — |
| "⌨ Open in terminal (agy)" | Where to open? → `launchTerminalCommand("agy")` | — |
| "💬 Direct prompt (agy -p)" | Dialog with prompt + optional free-text model → `agy [--model 'x'] -p '<prompt>'` in the terminal (`runDirectPrompt()`) | The `-p`/`--print`/`--prompt` flag exists in the CLI. Prompt and model use `shellEscape` |
| "↻ Continue last session" | `agy --continue` (with folder dialog) | — |
| "📁 Open in project" | Lists real projects from `~/proyectos` (folder shared with Claude/Codex/OpenCode) and opens `cd ... && agy` | — |
| "🗂 Manage projects" | Shared symlink/import/copy/sync/delete menu | — |
| Connect Engram memory | `engramSetupButton("antigravity-cli")` (see `ENGRAM.md`) | Runs `engram setup antigravity-cli \|\| engram setup`: if the slug is rejected, it falls back to Engram's interactive selector in the same terminal session |
| "🔑 Sign in with Google" / "🚪 Sign out of Google" | Conditional on `loggedIn` (existence of the token file). Login: `AntigravityOAuth` OAuth (thread + loopback `ServerSocket` + `ACTION_VIEW`); sign out deletes the token file | See risks and credential status in section 6 |
| "↻ Update (agy update)" | Reinstalls via `ModuleController.installModule()` / `reinstallModuleService()` | The CLI has no `update` subcommand of its own; updating re-runs the full installer. See gotchas |
| "🗑 Uninstall" | `confirmUninstallModule()` (a standalone button, not the full maintenance card, to avoid duplicating "Update") | — |

**Models**: `agy --model "<name>"` accepts 8 models (Gemini 3.x family, Claude Sonnet 4.6, Claude Opus 4.6, GPT-OSS 120B). There is no "agy models list"-style subcommand to populate a closed selector, so the model is an optional free-text field (the exact name may vary between versions, e.g. "Gemini 3.1 Pro (High)").

**MCP**: Antigravity reuses the MCP servers already imported from Gemini CLI (same `~/.gemini/` as its real home); there is no documented MCP management subcommand of its own, so Kairos adds nothing.

**Non-obvious detail**: the login flow opens the external browser (`ACTION_VIEW`) and the user leaves the app for a while; it is the flow most prone to the Fragment detaching midway, which is why `loginRunning` is ALWAYS released (it does not depend on the Fragment still being attached) so the sign-in is not left "stuck" if the user comes back later.

## 8. Registry

```
antigravity.installed=true
antigravity.version=<x.y[.z]>       # empty if it could not be verified, never a placeholder like "installed"
antigravity.install_date=<YYYY-MM-DD>
antigravity.location=termux_native
antigravity.binary=<path to the agy binary>
```

## 9. Files and paths

`$PREFIX/bin/agy`, `$PREFIX/bin/agy.va39`, `~/.config/agy/oauth_refresh_token`, `~/.agy_install` (temporary), checkpoint `~/.install_antigravity_checkpoint`.

## 10. Security

- Google refresh token stored in plain text without explicit restrictive permissions (depends on umask and the Android sandbox; moderate risk). The UI does not display it.
- The login impersonates the Antigravity IDE OAuth client (ToS risk declared in the code itself).
- Download without checksum from a fork of our own (trust placed in `Honkonx/antigravity-cli-termux`).

## 11. Gotchas and known limitations

- **Google login is inoperative as the repo stands**: `AntigravitySecrets.CLIENT_ID/CLIENT_SECRET` are empty, so the visible button always fails at the token exchange. It is a conscious, documented decision, worth remembering if Google blocks the client.
- **"Update" is effectively a no-op if already installed**: it reinstalls without `--force` and the script exits early if already installed (`:124`). Same gap as Claude/Codex/OpenCode.
- No checksum or pinned version.
- Inference: other glibc modules in Kairos require `unset LD_PRELOAD` at launch; here the UI calls `agy` directly (`AntigravityFragment.kt:117`). If the binary failed for that reason it would be a bug; not verified on a device.

## 12. Date added

`modulos/antigravity.sh`: **2026-07-24**.
