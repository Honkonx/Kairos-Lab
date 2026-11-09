# Remote (SSH + Cloudflared) — Kairos Module

**Kairos module** — managed through the Kairos UI (Modules tab). Installation is handled by the app through `ProcessBuilder` → `modulos/ssh.sh`; runtime (start/stop, keys, tunnel) runs through `RemoteManager.kt`, invoked from `RemoteFragment.kt`.

---

**Project:** Kairos
**Script:** `modulos/ssh.sh` — mirror in `app/src/main/assets/scripts/ssh.sh`. **Mind the name**: the file is called `ssh.sh`, but the real module (`id` in `modules.json`) is `remote` — a deliberate name asymmetry, see section 8.
**Fragment:** `app/src/main/java/com/termux/app/ui/RemoteFragment.kt`
**Manager:** `app/src/main/java/com/termux/app/util/RemoteManager.kt` (runtime logic)
**`id` in `modules.json`:** `remote` — `hasSwitch: true` (it has a persistent process, `sshd`), port 8022, `requires: ["openssh"]`, `catalogVersion 1.1.0`

> Based on reading `modulos/ssh.sh` (451 lines), `RemoteFragment.kt` (1374), `util/RemoteManager.kt` (1168), `ModuleController.kt` and the `modules.json` entry.

---

## 1. What it is

Two SSH directions: (a) Kairos as a **server** (OpenSSH on `:8022`, Cloudflare tunnel, mosh-server) to control it from outside; (b) Kairos as a **client** ("Receiver": saved connections to other servers, imported keys).

SSH access uses port **8022**, not the standard 22 — it avoids conflicts with other uses of the privileged port and needs no root. An optional **Cloudflare tunnel** (`cloudflared`) allows connecting from outside the local network without opening ports on the router. It is a module with a real persistent process: `sshd` runs in the background just like n8n/Ollama/OpenClaw/OpenCode.

## 2. Classification: FUNCTIONAL MODULE

Own UI with live state, service, switches and real options. 5 tabs: **Sender** (4 info rows + SSH switch + 8 buttons + a USB/ADB card with 2 buttons), **Security** ("require key" switch, 4 buttons, 4 rows), **Cloudflare** (tunnel switch + 2 buttons), **Network** (IP field + LAN scan), **Receiver** (own key, imported keys, saved connections, ephemeral connection). Approx. 25-30 real controls.

## 3. Permissions

- **Android**: nothing special beyond the base network permission the app already has.
- **Termux internal**: generates its own SSH server keys (`ssh-keygen -A`), reusing none from the system.
- **Network**: port 8022 open on `0.0.0.0` (all interfaces) — reachable from any device on the same network without a tunnel; with Cloudflare Tunnel, also reachable from the internet through the configured token.

## 4. Installation (`ssh.sh --silent [--force]`)

A 6-step script with checkpoints (`$HOME/.install_remote_checkpoint`):

| Step | What it does | Checkpoint |
|---|---|---|
| 1 | `pkg_update_with_fallback`, skippable if `instalar.sh`/`kairos.sh` already did it | `termux_update` |
| 2 | `openssh` + `tmux` (only what is missing) | `remote_deps` |
| 3 | **Overwrites** `$PREFIX/etc/ssh/sshd_config` (single `.bak` backup) with `Port 8022`, `ListenAddress 0.0.0.0`, `PasswordAuthentication yes`, `PubkeyAuthentication yes`, `PermitRootLogin no`, `MaxAuthTries 6`, `MaxSessions 5`, keepalive 60s/3, no X11Forwarding, with SFTP; generates server keys (`ssh-keygen -A`); creates `~/.ssh/authorized_keys` (chmod 600) | `ssh_config` |
| 4 | Generates `~/scripts/remote/ssh_start.sh` (starts `sshd`, prints `ssh -p 8022 user@IP` with the real IP detected via `ifconfig`/`ip addr`) and `ssh_stop.sh` (`pkill sshd`) | `ssh_scripts` |
| 5 | Downloads `cloudflared-linux-arm64` from `releases/latest/download` to `$PREFIX/bin/cloudflared` (a real binary, no `pkg` package; **no sha256, no pinned version**) and `mosh` best-effort — if `cloudflared` fails or turns out not to be executable (incompatible with Bionic), it deletes it and warns, but does NOT abort the installation (SSH works without the tunnel, only remote access is lost) | `cloudflared_install` — **only marked if `cloudflared` is really executable** |
| 6 | Aliases (`ssh-start`, `ssh-stop`, `ssh-status`) in `.bashrc` + registry | `remote_aliases` |

**Supported flags**: `--silent`, `--force`, `--describe` (`{"id":"remote",...,"variants":[],"variant_required":false}`).

**Reinstall shortcut**: if SSH is already configured (`sshd_config` has `Port 8022` or the registry says `ssh.installed=true`) AND `cloudflared` is available AND there is no `--force`, the script exits early — but it ALWAYS repairs the registry before exiting (see section 8). It checks `command -v sshd`; it does not run the binary.

## 5. State detection and execution

- **Installation**: registry (`remote.installed`).
- **"Running"**: `ModuleController.isRunning("remote")` uses `pgrep -x sshd` (process-by-name, not tmux — unlike n8n/Ollama/OpenClaw/OpenCode, which run in tmux sessions). `RemoteFragment.updateInfoRows()` uses this same mechanism (not a boolean computed separately in `RemoteManager`) so the two cannot diverge.
- **Cloudflare Tunnel**: `RemoteManager.info()` checks `tmux has-session -t <CF session>` OR `pgrep -f "cloudflared.*tunnel"`.
- **SSH start/stop**: `ssh_start.sh` → `sshd` and `pgrep -x sshd`; stop: `pkill sshd`. The app uses `startModuleService`/`stopModuleService` (`ModuleController`: `remote` → `ssh_start.sh`/`sshd`/8022).
- **Tunnel**: tmux session `cf-ssh-tunnel` with `cloudflared tunnel run --token <token> | tee log` (`RemoteManager.cfStart()`/`cfStop()`).
- `sshd_config` changes restart sshd with `pkill sshd; sshd`.

## 6. Real app screen (`RemoteFragment.kt`)

SSH and Cloudflare Tunnel are 2 real `SwitchRow`s (`sshSwitch`, `tunnelSwitch`), synced in `updateInfoRows()` with the real polling state (every 5 s while the screen is open; the `Handler`/`Runnable` is cancelled in `onDestroyView()`).

- **"SSH" switch**: `on=true` → `startModuleService { ... }` (inherited from `BaseModuleFragment`, via `ModuleController`); `on=false` → `stopModuleService { ... }`. This gives a single lifecycle path, with `waitForPortOpen()` and `ModuleEventBridge.notifySessionEvent()`.
- **"Cloudflare Tunnel" switch**: `runRemoteAction("cf-start"/"cf-stop")` → `RemoteManager.cfStart()`/`cfStop()`.

| Button / control | Real action |
|---|---|
| INFO card (SSH/IP/User/Connections) | Read-only, polled every 5 s |
| IP field + 📡 Find server on the network | `scanSubnet()` — scans the LAN looking for port 4096 (OpenCode servers), lists results in a dialog, pastes the chosen IP into the field |
| ℹ Connection info | `info()` — builds IP/user/connections/state |
| 🔑 Add public key | `sshAddKey(key)` — text prompt, appends to `~/.ssh/authorized_keys` |
| 🔒 Change password | `sshSetPassword(pass)` — `passwd` through stdin, without interpolating into the shell |
| 🔌 Active SSH connections | `sshConnections()` — lists sessions + daemon PID, in an `AlertDialog` |
| 📋 Copy SSH command | `copyConnectCommand()` — copies `info.connectCmd` (the real `ssh -p 8022 user@IP`) to the clipboard |
| 🔐 Server fingerprint | `showHostKeyFingerprints()` — runs `ssh-keygen -lf` on each `*_key.pub` (RSA/ECDSA/ED25519) and shows them so the user can check them against what the SSH client shows on first connection (MITM protection) |
| 🔑 Configure CF token | `cfSetToken(token)` — stores the Cloudflare token (via `TunnelManager`) |
| 📖 How to connect via CF-SSH | `cfInfo()` — instructions |
| 🗑 Uninstall (MAINTENANCE card) | `confirmUninstallModule()` |

**SSH Security** (reads `sshd_config` live, never cached, via `refreshSecurityPanel()`):

| Control | Real action (`RemoteManager`) |
|---|---|
| "Always require SSH key" switch | `sshSetPasswordAuth(false/true)` — disables/enables `PasswordAuthentication`; it refuses to disable it if there is no key in `authorized_keys` (anti-lockout guardrail, in the UI and server-side) |
| 🔌 Change SSH port | `sshSetPort(port)` — ports 1024-65535 (sshd runs without root) |
| ⛔ Toggle root login | `sshSetPermitRootLogin(true/false)` — DANGER button, explicit warning before allowing |
| 🔑 Generate own key | `sshGenerateOwnKeypair()` — ed25519, so the device connects AS A CLIENT to other servers |
| 📋 Copy my own public key | copies `securityConfig.ownPublicKey` |

**Receiver** (Kairos as a client): import a private key (warning: it is never shown again), connect with a session key (ephemeral file deleted after 30 s), saved connections with a TCP probe and host-key-change check.

**Anti-tapjacking** on the screen. `RemoteFragment.kt` already broadly covers what OpenSSH offers; port forwarding is a more advanced use case and is deliberately left out.

## 7. Registry (`~/.android_server_registry`)

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

There are also `ssh_client.*` keys for Receiver mode.

## 8. Design notes

- **Double prefix `ssh.*`/`remote.*` — deliberate**: the module's real `id` in `modules.json` is `remote`, and the whole Kotlin UI (`ModuleRegistry`, `BaseModuleFragment.isModuleInstalled()`, `ModulesFragment.pollStatus()`) reads `remote.installed`. The script is called `ssh.sh` and writes BOTH prefixes: `ssh.*` for compatibility with the script's own self-check, `remote.*` is what the app actually reads.
- **The early exit repairs the registry**: the script repairs the registry on EVERY run (even when everything is already installed), so a simple retry without `--force` is enough to leave `remote.installed=true`.
- **`cloudflared` checkpoint verified**: `mark_done "cloudflared_install"` only runs if `command -v cloudflared` confirms it is really executable; otherwise a retry attempts the download again.
- **`ssh.sh` is NOT the name the user sees in the app** — the screen says "Remote", the internal file is called `ssh.sh` by the project's historical convention (named after the protocol, not the module).

## 9. Files and paths

`$PREFIX/etc/ssh/sshd_config(.bak)`, `~/.ssh/{authorized_keys,id_ed25519*}`, `~/.ssh/imported/<id>` (600), `~/.ssh/.ephemeral/`, `~/scripts/remote/`, `$PREFIX/bin/cloudflared`, `~/.bashrc` (aliases `ssh-start/stop/status`), registry (`remote.*`, `ssh.*`, `ssh_client.*`), checkpoint `~/.install_remote_checkpoint`, tunnel log (`CF_SSH_LOG_FILE`).

## 10. Security

- `ListenAddress 0.0.0.0` + `PasswordAuthentication yes` by default: SSH is on every interface with password login enabled. Existing mitigation: the "Require key" switch and the anti-lockout block (`RemoteManager.kt:405-424`).
- The Cloudflare token is passed on the command line (`--token`, visible in `ps`/tmux) and stored in plain text in the registry (`TunnelManager.saveConfig`), not in a protected store. It is escaped with `shellQuote`.
- A stored secret is never shown again: no function returns the content of a saved imported key (only alias + fingerprint, replace, delete); `chmod 700/600` on keys.
- `cloudflared` is downloaded from `latest` without an integrity check (supply chain).
- Inference: the downloaded binary may suffer the Go-on-Android DNS problem documented in `docs/ssh/tunel-cloudflared-nativo.md` (it uses `/etc/resolv.conf`); `RemoteManager.cfStart()` invokes `cloudflared` from the PATH, not the native `libcloudflared.so` (`nativeLibraryDir`) that `TunnelManager` uses for other tunnels. The `--token` mode was not tested on a device.

## 11. Issues / limitations

- `ssh.sh` does not set a password or key; until the user creates one (UI), password login is not usable.
- `verificar.sh` checks `remote` against `cloudflared`, not `sshd` (see `VERIFICAR.md`).
- `ssh_stop.sh` uses `pkill sshd` (it kills all sshd processes, including active sessions).
- The `ssh.sh` header cites the `termux-ai-stack` repo and `install_remote.sh`, and the banner says v2.1.0 while the header says v2.2.0 (cosmetic).
- `passwd` is written to a process without a timeout when reading output before `waitFor(10s)` (`readText()` before the timeout); a hung `passwd` would block the thread.
- Inference: mosh-server is installed but the UI offers nothing mosh-specific.

## 12. Added on

`modulos/ssh.sh` added on **2026-07-19**.
