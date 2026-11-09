# restic

> **Add-on (no longer a catalog module).** It lives in **Homelab → Services → Add-ons**
> (`util/HomelabAddons.kt`). This document describes the design and behavior of the tool.

A pure CLI tool with no Fragment of its own and no switch (`hasSwitch: false`), invoked by the user from the terminal
(`terminalCommand: "restic"` in `modules.json`).

---

**Script:** `modulos/restic.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/restic.sh`.
**Registry:** prefix `restic.*`
**Port:** none (pure CLI, no background service).

---

## 1. Overview

Encrypted, incremental backups with real versioning: it backs up WITH a history of snapshots and
encryption.

## 2. Installation

Native apt package installed through `install_single_pkg()` (same helper as `golang.sh`) — a single
static Go binary, no extra dependencies. Checked against the real `termux-packages` index
(`packages/restic/build.sh`, regular package, no root).

## 3. Usage (stays in the terminal, by design of the upstream tool)

`restic init`/`restic backup <path>`/`restic snapshots`/`restic restore` are inherently
interactive and specific to each backup (repository location and encryption password
chosen by the user). No dedicated screen yet.
