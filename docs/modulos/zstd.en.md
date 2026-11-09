# Zstandard (zstd) — Kairos Module

> **Obsolete as a module.** TAR+Zstandard compression/decompression is now part of the
> File Manager. This document is kept as a technical reference, not as reinstall instructions
> for a standalone module.

**Kairos module** — no Fragment of its own and no switch (`hasSwitch: false`), a pure CLI tool invoked
by the user from the terminal (`terminalCommand: "zstd"` in `modules.json`).

> The script was written after checking the real `termux-packages` index
> (`packages/zstd/build.sh`, `TERMUX_PKG_VERSION="1.5.7"`, regular package, no root).

---

**Script:** `modulos/zstd.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/zstd.sh`.
**Registry:** prefix `zstd.*`
**Port:** none (pure CLI, no background service).

---

## 1. Overview

Modern compression (Zstandard algorithm, from Facebook/Meta) — much faster than gzip/xz at
comparable or better ratios, with multi-threading support (`-T0` uses all available
cores). Useful for compressing large backups before uploading them to a remote destination, or as a manual
step before/after a `restic backup` of files that do not benefit from restic's own
deduplication (e.g. a large database dump).

## 2. Installation

Native apt package installed through `install_single_pkg()` (same helper as `golang.sh`) — it depends
on `liblzma`/`zlib`, resolved automatically by `pkg install`.

## 3. Usage (stays in the terminal, by design of the upstream tool)

`zstd <file>` / `unzstd <file>.zst` / `zstd -T0 -19 <file>` (maximum compression,
multi-threaded) are one-off user commands — there is nothing to configure
persistently.
