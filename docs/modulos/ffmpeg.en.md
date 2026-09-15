# FFmpeg

**Kairos module** — no Fragment of its own, no switch, a pure CLI tool invoked by the user from
the terminal, same pattern as `rclone`/`restic`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/ffmpeg.sh`
**Category:** multimedia
**`hasSwitch`:** `false` — no ON/OFF, no persistent background process

---

## 1. What it is

FFmpeg is the reference command-line tool for audio/video conversion and editing: trimming,
compressing, extracting audio, generating thumbnails, changing formats — on any file recorded
or downloaded on the device itself.

## 2. Installation

Native Termux package installed directly (`pkg install ffmpeg`), no compilation or extra steps
— a binary already packaged for ARM64 by the Termux project itself.

## 3. Usage

FFmpeg is a command-line tool by design — it has no dedicated screen inside Kairos. It's used
from the built-in terminal, with FFmpeg's own standard syntax (`ffmpeg -i input.mp4
output.mp3`, etc.).
