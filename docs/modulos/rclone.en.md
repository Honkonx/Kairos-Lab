# rclone

**Kairos module** — no Fragment of its own, no switch, a pure CLI tool invoked by the user from
the terminal, same pattern as `ffmpeg`/`restic`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/rclone.sh`
**Category:** cloud
**`hasSwitch`:** `false` — no ON/OFF, no persistent background process

---

## 1. What it is

Known as "rsync for the cloud": syncs or mounts 70+ storage providers (Google Drive, Dropbox,
S3, WebDAV, SFTP, and many more) directly from the device itself, without leaving Kairos to get
the binary available. It complements the `restic` module (which backs up WITH version history
and encryption) and `syncthing` (which syncs between the user's own devices, without going
through any external provider).

## 2. Installation

Native Termux package (`pkg install rclone`) — a single static Go binary, no extra
dependencies.

## 3. Configuration and usage

Setting up a remote (`rclone config`) is interactive by the tool's own design — it asks for the
provider name, credentials, and several service-specific options, a flow that doesn't make
sense to reduce to a generic form without losing flexibility. Because of that, both the setup
and day-to-day use (`rclone sync`, `rclone mount`, `rclone copy`, etc.) stay in Kairos's
built-in terminal, using rclone's standard syntax.

A remote already configured with rclone can be reused as a destination for `restic` (see the
`restic` module), without having to set up access to the provider twice.
