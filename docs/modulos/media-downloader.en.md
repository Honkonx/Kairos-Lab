# MEDIA_DOWNLOADER.md — module `media-downloader` (video/audio downloader)

> Sheet based on reading the code (`modulos/media_downloader.sh`, `MediaDownloaderFragment.kt`, `ArchiveManager.kt`, `fragment_media_downloader.xml`, `ArchiveManagerTest.kt`). Not tested on a device.

## 1. What it is
A single downloader: paste an http(s) URL and download video with sound (MP4), video without sound, or audio only (MP3), with a
maximum resolution of 480p/720p/1080p/1440p and a selectable destination folder. Engine: `yt-dlp` + `ffmpeg`.
`modules.json`: `media-downloader`, `type: Nativo`, `category: media`, `installMethods: ["pkg"]`, `requires: ["python"]`,
`recommended: true`, `script: media_downloader.sh`, no `terminalCommand`, ~150MB.

## 2. Classification: FUNCTIONAL MODULE
Own native UI with real options and operation state (format, quality, persistent destination, progress, result).
UI options: ~6 (URL field, 3 formats, resolution selector, change folder, Download). It has no persistent service.

## 3. What it consolidates
This module absorbs the `ffmpeg` and `yt-dlp` tools, which are no longer offered as separate modules. The compressed-archive
function (zstd, 7z) moves to the file manager's `ArchiveManager` (see §8).

## 4. Installation
- `modulos/media_downloader.sh` (59 lines). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.
- `command -v yt-dlp`/`ffmpeg` missing or `--force` → `pkg_update_with_fallback` + `pkg install -y python-yt-dlp ffmpeg` (`:46-49`).
- Verification (`:50-53`): `command -v` and real execution (`yt-dlp --version`, `ffmpeg -version`) of both; it does NOT use `verify_binary_installed` but performs the
  same post-condition check. `registry_install "media-downloader" "yt-dlp <ver> + ffmpeg"`.
- `--describe-files` (`:30-43`): no files of its own, dependencies `pkg:python-yt-dlp` and `pkg:ffmpeg`, a `verify_cmd` that runs both.
- Minor gap: `requires: ["python"]` in the JSON points to Kairos's Python module, but the script resolves Python through Termux's `python-yt-dlp` package (misaligned).

## 5. Execution (`MediaDownloaderFragment.kt`)
- Validates that the URL starts with `http://` or `https://` (`:81-85`).
- Creates a temporary folder `cacheDir/media-download-<ts>` and runs `yt-dlp --newline --no-playlist --no-progress <format> -o '<tmp>/%(title).180B.%(ext)s' <url>`
  with `ManagerNativeUtils.runExec` (argument list, no shell; timeout 3600 s, `:110-116`).
- Formats: with sound `bestvideo[height<=H][ext=mp4]+bestaudio[ext=m4a]/…` + `--merge-output-format mp4`; without sound `bestvideo[height<=H]…`; audio `-x --audio-format mp3`.
- When done it copies each file to the destination (`File` in `/storage/emulated/0/Movies` by default, or `DocumentFile` if a SAF tree with a persistent permission was chosen,
  stored in the `media_downloader` SharedPreferences), renaming to `name (2).ext` if it already exists, and deletes the temporary folder (`:117-129`, `:145-193`).
- Indeterminate progress (no percentage: `runExec` returns the log when it finishes).

## 6. UI functions/options
URL; `RadioGroup` (video with audio / without audio / audio only); resolution `Spinner` (hidden in audio-only, `:65-67`); "Change" folder
(`OpenDocumentTree`); "Download"; indeterminate bar; success/error dialog (last 1600 characters of the log on error). ES/EN strings (`strings_generic.xml:227-253`, `values-en`).

## 7. Files and paths
`modulos/media_downloader.sh` (copy in `app/src/main/assets/scripts/media_downloader.sh`), `MediaDownloaderFragment.kt`
(routed in `ModuleDetailNavigator.kt:50`), `res/layout/fragment_media_downloader.xml`, destination `/storage/emulated/0/Movies` or SAF tree, temporary files in `cacheDir`.

## 8. ArchiveManager (file manager, related)
`app/src/main/java/com/termux/app/util/ArchiveManager.kt`, used only by `FileManagerFragment.kt` (`:277,480,522,544,618,645`), not by the downloader.
- Coverage: ZIP (Zip64 AsNeeded), 7z (LZMA2), tar.zst/.tzst (Commons Compress 1.27.1 + `zstd-jni` 1.5.6-4 in `app/build.gradle:279-282`). Functions: `kindFor`, `list`, `compress`, `extractAll`.
- Existing protections: rejection of absolute paths/`..`/NUL (`safeRelativePath`, `:53`), canonicalization and prefix check (`:111-112,311`), symbolic links and special files
  rejected (`:151,172,291`), duplicate names and file/folder conflicts (`validateEntries`, `:181`), no overwriting, extraction into a
  temporary folder `.kairos-extraer-<uuid>` + move with rollback, compression to `.partial` + `renameTo`, a cap of 100,000 entries (`MAX_ENTRIES`, `:39`).

## 9. Security: missing limits
- There is no limit on total uncompressed size, per-entry size or compression ratio → vulnerable to zip bombs (the only cap is the number of entries).
  `entry.size` is only used for listing and can be declared falsely; streaming copy is not cut off.
- Free space is not checked before extracting.
- `tar.zst` is fully decompressed twice (list and extract); there is no memory/time bound on the zstd window.
- No cancellation or progress for compression/extraction; no password/encryption support.
- Downloader: it does not restrict hosts, nor use cookies; it runs `yt-dlp` with the user's URL as an argument (no shell, no injection). The file name is set by `-o`; it is not
  sanitized beyond `yt-dlp`. No secrets.

## 10. Existing tests (`app/src/test/java/com/termux/app/util/ArchiveManagerTest.kt`, 70 lines, 3 JUnit tests)
1. `safeRelativePathRejectsAbsoluteAndTraversalEntries` (`../`, `/abs`, `C:\`, backslash paths).
2. `zipAndSevenZCompressionCanBeListedAndExtracted` (ZIP and 7z round trip).
3. `zipSlipArchiveIsRejectedWithoutWritingOutsideDestination`.
No tests for tar.zst, symbolic links, duplicates, rollback, zip bombs, or for `MediaDownloaderFragment`.

## 11. Problems / gaps
- No download cancellation, no real progress, no way to update `yt-dlp` from the UI; `--no-playlist` is fixed (it does not download playlists).
- Audio is MP3 only; `ext=mp4`/`m4a` in the selector may degrade quality on sites without those containers (falls back to `best`).
- Unverified on a device: write permissions on `/storage/emulated/0/Movies` (Android 11+, targetSdk 28).
