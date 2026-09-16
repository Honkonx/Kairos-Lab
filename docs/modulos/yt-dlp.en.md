# yt-dlp

**Kairos module** — no Fragment of its own, no switch, a pure CLI invoked by the user via the
terminal, same pattern as `rclone`/`restic`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/yt-dlp.sh`
**Category:** multimedia
**`hasSwitch`:** `false` — no ON/OFF, no persistent background process

---

## 1. What it is

A video/audio downloader for YouTube and around 1800 other sites (an active fork of the classic
youtube-dl). It installs as a simple CLI with no prior configuration — the user picks URL,
format and destination folder on each download from the terminal.

## 2. Installation

Native Termux package, installed directly — a single ready-to-use binary.

## 3. Optional dependency: FFmpeg

yt-dlp works without FFmpeg, but needs it for some conversions (extracting audio only, remuxing
to another container, certain combined formats). Kairos already ships `ffmpeg` as its own
module — installing it covers this optional dependency with nothing extra to configure.

## 4. Usage

The actual download (URL, format, destination folder) stays in the integrated terminal, with
yt-dlp's standard syntax — it doesn't have its own screen inside Kairos yet.
