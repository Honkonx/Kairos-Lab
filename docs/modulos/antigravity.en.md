# Antigravity CLI

**Kairos module** — installation managed by the app via `ModuleController.installModule()` →
`ProcessBuilder` → `modulos/antigravity.sh`. No on/off switch (pure CLI, no persistent process).

---

**Script:** `modulos/antigravity.sh`
**Fragment:** `AntigravityFragment.kt`
**`modules.json`:** `id: "antigravity"`, `hasSwitch: false`, icon `✦`
**Registry prefix:** `antigravity.*`

---

## 1. Overview

Google Antigravity's CLI (`agy`), a native binary. The only Kairos module with a real "Sign in
with Google" login implemented inside the app itself (not delegated to the terminal) — see
section 6.

## 2. Permissions

None from Android beyond the general first-run wizard permissions. OAuth login opens the
system browser (`Intent.ACTION_VIEW`) for the Google sign-in screen — it requires no special
permission declared in the manifest beyond being able to launch external Activities (standard).

## 3. Variants

None — a single installation channel (`--describe` reports `"variants":[]`).

## 4. Installation logic (step by step)

1. **STEP 1 — System dependencies** (checkpoint `deps`):
   - Detects whether `glibc-runner` is missing (`$PREFIX/glibc/lib/ld-linux-aarch64.so.1`) — if
     missing, installs `glibc-repo` and **then** runs `pkg update` before installing
     `glibc-runner` itself (without this intermediate step, the package doesn't show up yet in
     the just-added indexes and the installation fails with "package not found").
   - Also installs `curl`, `ca-certificates`, `resolv-conf` if missing.
   - **LSE atomics detection** (`grep atomics /proc/cpuinfo`) — if the CPU doesn't support it,
     it requires `qemu-user-aarch64` as a fallback (aborts with instructions if it's not
     available, instead of failing silently later on).
2. **STEP 2 — Binary download and installation** (checkpoint `binaries`):
   - Downloads `antigravity-termux-standalone.tar.gz` from the `Honkonx/antigravity-cli-termux`
     fork (`latest` release), into a temporary workdir at `$HOME/.agy_install` (never `/tmp/`).
   - Extracts specifically `agy` and `agy.va39` from the tarball.
   - Installs both into `$PREFIX/bin/` with `install -m 0755`.
   - `trap '_agy_cleanup' EXIT` — if the script ends without having marked
     `AGY_INSTALL_OK=1`, it deletes the working workdir (no half-installed leftovers).
3. **STEP 3 — Verification and registration**: runs `agy --version`; if it doesn't respond, it
   doesn't write a placeholder like `"installed"` into the registry's version field — it leaves
   it empty on purpose (the UI already filters an empty version with `isNotEmpty()`, but a
   string like `"installed"` would get concatenated as-is into `"vinstalled"` on the module
   card).

## 5. Status detection

`_check_installed()`: `command -v agy` and both files `$PREFIX/bin/agy` +
`$PREFIX/bin/agy.va39` exist. If it's already installed and there's no `--force`, it exits with
`exit 0` without re-syncing the registry.

On the app side, this is a module **with no switch** — `isModuleInstalled()` gates the UI.

## 6. "Sign in with Google" login

Unlike Claude/Codex, Antigravity's login **isn't done from the terminal** — it's implemented as
native OAuth inside the app itself (`com.termux.app.oauth.AntigravityOAuth`).

- The refresh token is saved in plain text at `~/.config/agy/oauth_refresh_token`.
- **Risk accepted on purpose, documented in the code itself**: impersonating Antigravity's
  OAuth client violates Google's Terms of Service.
- `AntigravitySecrets.kt` has no real credentials loaded by default — login fails at the token
  exchange step (`invalid_client`) until those values are filled in by hand.
- Flow: `startGoogleSignIn()` → `AntigravityOAuth().signIn { url -> opens the browser }` → on
  return, saves the refresh token → `refreshUi()` recomposes the screen showing "connected".
- The login flow opens the external browser (`ACTION_VIEW`) and the user genuinely leaves the
  app for a while; `loginRunning` is always released (doesn't depend on the Fragment still
  being attached) so the sign-in doesn't stay "stuck" if the user comes back later.

## 7. App screen (`AntigravityFragment.kt`)

- **STATUS card**: method (`native·binary`), version (`antigravity.version` from the registry),
  status pill, "Google Account" pill (connected/not connected depending on whether the token
  file exists), terminal pill.
- **"⌨ Open in terminal (agy)"** — `launchTerminalCommand("agy")`.
- **"💬 Direct prompt (agy -p)"** — free-text dialog → `launchTerminalCommand("agy -p
  '<prompt>'")`. The `-p`/`--print`/`--prompt` flag is confirmed in Antigravity's official
  documentation.
- **"📁 Open in project"** — same shared `~/proyectos` as Claude/Codex/OpenCode.
- **"🗂 Manage projects"** — symlink/import/delete/sync.
- **Connect Engram memory** — `engramSetupButton("antigravity-cli")`.
- **"🔑 Sign in with Google" / "🚪 Sign out of Google"** — conditional on `loggedIn` (whether the
  token file exists).
- **"↻ Update (agy update)"** — reinstalls via `ModuleController.installModule()`. The CLI
  itself has no real `update` subcommand — the installer re-runs the full installation with
  `AGY_MODE=update`.
- **"↻ Resume last session"** — `agy --continue`, picks up the last conversation.
- **"MAINTENANCE" card — "🗑 Uninstall"** — `confirmUninstallModule()`.

## 8. Registry

```
antigravity.installed=true
antigravity.version=<x.y[.z]>       # empty if it couldn't be verified, never a placeholder like "installed"
antigravity.install_date=<YYYY-MM-DD>
antigravity.location=termux_native
antigravity.binary=<path to the agy binary>
```

## 9. Models and MCP

`agy --model "<name>"` — 8 real models supported (Gemini 3.x family, Claude Sonnet 4.6, Claude
Opus 4.6, GPT-OSS 120B). No real subcommand like "agy models list" to populate a closed
selector — it's offered as an optional free-text field inside the "Direct prompt" dialog.

MCP: Antigravity reuses the MCP servers already imported from Gemini CLI (same `~/.gemini/`
directory, since Antigravity is Gemini CLI's successor) — no MCP management subcommand of its
own beyond that.

## 10. Known gotchas

- **Explicit OAuth risk** — see section 6, a conscious design decision.
- **The "Update" button fully reinstalls** every time it's tapped — there's no way to force a
  version-only check without reinstalling.
