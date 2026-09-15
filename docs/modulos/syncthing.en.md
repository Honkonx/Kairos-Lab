# Syncthing

**Kairos module** — no Fragment of its own, managed by the app's generic module screen (Kairos
already has everything needed to install/start/stop/open the web UI without a dedicated screen).

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/syncthing.sh`
**Port:** `8384` (web UI, only reachable from the device itself — Kairos opens it embedded
inside the app)

---

## 1. What it is

P2P file synchronization between devices, with no dependency on any cloud provider — unlike
`rclone`, which syncs AGAINST an external provider (Drive, S3, Dropbox, etc.). It complements
the `rclone` module rather than replacing it.

## 2. Installation

Native Termux package, installed directly with the same mechanism as `rclone`/`ffmpeg`.

## 3. Start and stop

The module has a real ON/OFF switch: turning it on starts the Syncthing server in the
background with its web UI listening only on the device itself (`127.0.0.1:8384`). Turning it
off stops the process.

## 4. Configuration

Pairing devices (sharing the device ID with another Syncthing instance) and choosing which
folders to sync are inherently interactive steps, by the tool's own design — they're configured
from Syncthing's web UI, which Kairos opens embedded automatically inside the app as soon as
the switch is on, no separate browser needed.
