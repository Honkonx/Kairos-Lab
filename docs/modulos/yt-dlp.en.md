# yt-dlp — Kairos Module

**Kairos module** — no Fragment of its own and no switch (`hasSwitch: false`), a pure CLI tool invoked
by the user from the terminal (`terminalCommand: "yt-dlp"` in `modules.json`).

> Checked against the real `termux-packages` index (`packages/python-yt-dlp/build.sh`,
> regular package, no root) before writing the script.

---

**Script:** `modulos/yt-dlp.sh` (v1.0.0) — synchronized copy in
`app/src/main/assets/scripts/yt-dlp.sh`.
**Registry:** prefix `yt-dlp.*`
**Port:** none (pure CLI, no background service).

---

## 1. Overview

Video/audio downloader for YouTube and ~1800 more sites (active fork of youtube-dl). It installs
as a simple CLI with no configuration — the user picks the URL/format/destination folder on each
invocation from the terminal.

## 2. Package name peculiarity

**The real apt package name is `python-yt-dlp`, NOT plain `yt-dlp`** — no package
called `yt-dlp` exists in the `termux-packages` index. The resulting binary is indeed called
`yt-dlp` (so `terminalCommand`/`check_cmd` use that name; only the argument to
`pkg install`/`install_single_pkg()` uses the real package name). It was
confirmed against `packages/python-yt-dlp/build.sh` (`TERMUX_PKG_PROVIDES='yt-dlp'`) before
writing the script, not assumed from the upstream tool's name.

## 3. Installation

Native apt package installed through `install_single_pkg()` — `install_single_pkg "yt-dlp" "yt-dlp" python-yt-dlp`
(id="yt-dlp", check_cmd="yt-dlp", real package="python-yt-dlp").

## 4. Optional dependency: ffmpeg

The real `python-yt-dlp` `build.sh` declares `TERMUX_PKG_RECOMMENDS="ffmpeg, yt-dlp-ejs"` — yt-dlp
works without `ffmpeg` but needs it for remuxing/audio extraction and some formats. Kairos
has its own `ffmpeg` module (category `multimedia`) that covers that optional dependency — the
installation logic is not duplicated here, the relationship is only documented.

## 5. Usage (stays in the terminal, by design of the upstream tool)

The download itself (URL, format, destination folder) is inherently one command per download.
No dedicated UI yet.
