# Remote (SSH + Cloudflared)

**Kairos module** — managed from the UI (Modules tab). Installation handled by the app via `ProcessBuilder` → `modulos/ssh.sh`; runtime (start/stop, keys, tunnel) runs via `RemoteManager.kt`, invoked from `RemoteFragment.kt`.

---

**Script:** `modulos/ssh.sh` — mirrored at `app/src/main/assets/scripts/ssh.sh`. The file is called `ssh.sh`, but the actual module (`id` in `modules.json`) is `remote` — a deliberate name mismatch, see section 7.
**Fragment:** `app/src/main/java/com/termux/app/ui/RemoteFragment.kt`
**Manager:** `app/src/main/java/com/termux/app/util/RemoteManager.kt` (all the real runtime logic)
**`id` in `modules.json`:** `remote` — with a switch (the only module in this batch with a persistent process, `sshd`)

---

## 1. Overview

Remote gives SSH access to the device (port **8022**, not the standard 22 — avoids conflicting with other uses of the privileged port and doesn't require root) plus an optional **Cloudflare Tunnel** (`cloudflared`) to connect from outside the local network without opening ports on the router.

## 2. Permissions

- **Android**: nothing special beyond the base network permission the app already has.
- **Internal Termux**: generates its own SSH server keys (`ssh-keygen -A`), doesn't reuse any system keys.
- **Network**: port 8022 open on `0.0.0.0` (all interfaces) — reachable from any device on the same network without a tunnel; with Cloudflare Tunnel, also reachable from the internet via the configured token.

## 3. Install logic (`modulos/ssh.sh`)

A 6-step script with checkpoints:

| Step | What it does |
|---|---|
| 1 | System update (with fallback to mirrors), skippable if already run before |
| 2 | `openssh` + `tmux` (only whatever is missing) |
| 3 | Writes the real `sshd_config` (port 8022, password + public-key authentication, no root login, no X11Forwarding, with SFTP) — backs up the previous config if it existed; generates server keys; creates `~/.ssh/authorized_keys` |
| 4 | Generates `~/scripts/remote/ssh_start.sh` (starts `sshd`, prints `ssh -p 8022 user@IP` with the real detected IP) and `ssh_stop.sh` |
| 5 | Downloads native ARM64 `cloudflared` from GitHub Releases (real binary, not a `pkg` package) — if it fails or the binary turns out not to be executable, it's removed and a warning is shown, but installation doesn't abort (SSH works without the tunnel) |
| 6 | Aliases (`ssh-start`, `ssh-stop`, `ssh-status`) + registry |

**Supported flags**: `--silent`, `--force`, `--describe`.

**Reinstall shortcut**: if SSH is already configured and `cloudflared` is available and `--force` wasn't passed, the script exits early — but it always repairs the registry before exiting.

## 4. State detection

- **Installation**: registry (`remote.installed`).
- **"Running"**: checked by process name (`sshd`), not by a tmux session — unlike n8n/Ollama/OpenClaw/OpenCode. `RemoteFragment` uses the same mechanism as `ModuleController`, so the two can't diverge.
- **Cloudflare Tunnel**: checks the tunnel's tmux session or the running `cloudflared` process.

## 5. App screen (`RemoteFragment.kt`)

SSH and Cloudflare Tunnel are 2 real switches, kept in sync with the real state via polling.

- **"SSH" switch**: starts/stops via the standard module lifecycle (`ModuleController`), with real confirmation that the port is open before reporting success.
- **"Cloudflare Tunnel" switch**: starts/stops Remote's specific tunnel.

"INFO" card (SSH running/stopped + port, IP, user, active connections — auto-polled every 5s while the screen is open), then a "SERVER ON THE NETWORK" card (IP field + "Search server on the network" button that scans the LAN), the SSH switch, and the rest of the buttons:

| Button / control | Real action |
|---|---|
| **SSH** switch | Starts/stops the `sshd` process |
| Search server on the network | Scans the LAN looking for the service port, lists results, pastes the chosen IP into the field |
| Connection info | Builds IP/user/connections/status |
| Add public key | Free-text prompt, appends to `~/.ssh/authorized_keys` |
| Change password | Text prompt |
| Active SSH connections | Lists sessions + daemon PID |
| Copy SSH command | Copies the real `ssh -p 8022 user@IP` (already built with the detected IP) to the clipboard |
| Server fingerprint | Runs `ssh-keygen -lf` on each already-generated host key file (one fingerprint per type: RSA/ECDSA/ED25519) and shows them, so the user can verify them against what their SSH client shows on first connect (protection against man-in-the-middle attacks) |
| **Cloudflare Tunnel** switch | Starts/stops the tunnel |
| Configure CF token | Saves the Cloudflare token (text prompt) |
| How to connect via CF-SSH | Real instructions |

**"SSH SECURITY" card**: panel exposing live `sshd_config` controls (never cached, re-read after every action):

| Control | Real action |
|---|---|
| "Always require SSH key" switch | Enables/disables `PasswordAuthentication`; disabled in the UI if there's no key in `authorized_keys` yet (guardrail against losing access) |
| Change SSH port | Ports 1024-65535 (sshd runs without root) |
| Toggle root login | Explicit warning before allowing it |
| Generate own key | So the device can connect as a client to other servers |
| Copy my own public key | — |

**Polling**: `RemoteFragment` keeps its own refresh handler which is explicitly cancelled when leaving the screen, preventing it from continuing to reschedule itself in the background.

All runtime logic lives in Kotlin (`RemoteManager.kt`).

## 6. Registry (`~/.android_server_registry`)

```
ssh.installed=true
ssh.version=<OpenSSH version>
ssh.install_date=<YYYY-MM-DD>
ssh.port=8022
ssh.location=termux_native
ssh.auth=password+pubkey
remote.installed=true
remote.version=<same version>
remote.install_date=<YYYY-MM-DD>
remote.port=8022
remote.location=termux_native
```

## 7. Design notes

- **Double `ssh.*`/`remote.*` prefix — deliberate**: the module's real `id` in `modules.json` is `remote`, and all the Kotlin UI reads `remote.installed`. The script, on the other hand, is called `ssh.sh` and originally only wrote `ssh.*` keys — to keep backward compatibility, the registry is written with both prefixes: `ssh.*` is kept for the script's own self-check compatibility; `remote.*` is what the app actually reads.
- The script's early exit (when everything is already installed) repairs the registry on every run, so a plain retry without `--force` is enough to fix any inconsistency.
- The `cloudflared` checkpoint is only marked if the binary ended up actually executable after the download.
- **`ssh.sh` is not the name the user sees in the app** — the screen says "Remote", the internal file is called `ssh.sh` by historical project convention (named after the protocol, not the module).

## Actual coverage

`RemoteFragment.kt` fully covers what OpenSSH offers from a user-facing standpoint: adding a public key, changing the password, viewing active connections, server fingerprint, changing the port, toggling root login, generating/copying your own key, and Cloudflare token+guide. Port forwarding (a more advanced/risky use case) is deliberately left out of the UI.
