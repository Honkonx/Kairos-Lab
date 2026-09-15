# restic

**Kairos module** — no Fragment of its own, no switch, a pure CLI tool invoked by the user from
the terminal, same pattern as `rclone`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/restic.sh`
**Category:** cloud
**`hasSwitch`:** `false` — no background process, pure CLI

---

## 1. What it is

Encrypted, incremental backups with real versioning. It complements the `rclone` module rather
than overlapping with it: `rclone` syncs or mounts a remote, `restic` backs up WITH a history of
snapshots and real encryption — and it can use a remote already configured with `rclone` as the
destination for its own backup repository, without having to set up access to the provider
again.

## 2. Installation

Native Termux package — a single static Go binary, no extra dependencies.

## 3. Usage

`restic init` (create the repository), `restic backup <path>` (back up), `restic snapshots`
(view history), and `restic restore` (recover) are inherently interactive and specific to each
backup — the user chooses the repository location and the encryption password. By the tool's
own design, this flow stays in Kairos's built-in terminal, with no dedicated screen yet.
