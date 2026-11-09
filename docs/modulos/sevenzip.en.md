# 7-Zip (sevenzip) — Kairos Module

> **Obsolete as a module.** The 7z operation now belongs to the File Manager through
> `ArchiveManager`; the standalone sheet/installer is no longer distributed. This document is
> kept as a technical reference, not as reinstall instructions.

**Kairos module** — no Fragment of its own and no switch (`hasSwitch: false`), a pure CLI tool invoked
by the user from the terminal (`terminalCommand: "7zz"` in `modules.json`).

> The script was written after checking the real `termux-packages` index
> (`packages/7zip/build.sh`, `TERMUX_PKG_VERSION="26.03"`, regular package, no root).

---

**Script:** `modulos/sevenzip.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/sevenzip.sh`.
**Registry:** prefix `sevenzip.*`
**Port:** none (pure CLI, no background service).

---

## 1. Overview

High-compression archiver (`.7z` format) — it also reads/writes `.zip`/`.tar`/`.gz`/`.xz` and
reads (does not write) `.rar`. It fills the "universal archive format" gap that neither `zstd` (single-stream
compression, no multi-file container) nor `restic` (backup, not one-off archiving) covers.

## 2. Package name peculiarity (same pattern as `yt-dlp`)

This module's id is **`sevenzip`**, not `7zip` — the rest of the Kairos catalog uses
ids that start with a letter, not a digit. The real apt package installed is still
literally `7zip` (confirmed against `packages/7zip/build.sh` in `termux-packages`): the
name `p7zip` is deprecated in the real index and is now just an alias
(`TERMUX_PKG_REPLACES`/`TERMUX_PKG_PROVIDES`) that resolves to the `7zip` package — `pkg
install p7zip` works through that alias, but the real `build.sh` lives in `packages/7zip/`.

## 3. Installation

Native apt package — installed directly with `pkg install -y 7zip` (not through the generic
`install_single_pkg()` because the post-install verification needs its own check, see section 4).

## 4. Verification peculiarity — the CLI supports neither `--version` nor `--help`

According to the 7-Zip documentation (command-line manual), the `7zz` binary
does **not** support `--version` or `--help` (both return `Command Line Error:`, exit code
7) — the default flag of `verify_binary_installed()` (`--version`) would have marked a successful
installation as failed. Instead, `7zz` always prints its version banner
(`"7-Zip ..."`) to stdout **before** the command-line error, regardless of the command's
result — the script checks that banner (`7zz 2>&1 | grep 7-Zip`) rather than relying on the exit
code or a standard flag.

## 5. Usage (stays in the terminal, by design of the upstream tool)

Installed binaries: `7zz` (recommended, all features), `7z`/`7za`/`7zr` (lighter/legacy
variants). `7zz a archive.7z folder/` (compress), `7zz x archive.7z` (extract with
paths), `7zz l archive.7z` (list contents) — one-off use by the user from the terminal.
