# DESCARGA_E_INSTALACION.md — Kairos's 3 download/install mechanisms

> Kairos has 3 completely independent download/install systems, each with its own logic and
> its own guarantees. This doc compares them side by side — for the full technical detail
> of each one, see the dedicated doc cited in each section.

## 1. Rootfs — Termux base packages

See `docs/bootstrap/rootfs-embebido.md` for the full mechanism (extraction, real
`apt install`, update checks). Summary of the 2 build variants:

| Variant | Workflow | How the rootfs arrives | Requires network in the wizard |
|---|---|---|---|
| Lightweight | `build-app.yml` | `RootfsInstaller.kt` downloads it at runtime from a GitHub Release | Yes |
| With embedded rootfs | `build-app-rootfs.yml` | Already inside the APK as an asset, `RootfsInstaller.kt` just extracts it | No |

**Why the lightweight variant can fail for an end user**: if the rootfs's source repository is
private, GitHub responds with a 404 to any unauthenticated Release asset download. The build
with the embedded rootfs (`build-app-rootfs.yml`, runs in CI) does have a token available during
the build and uses it to authenticate the download — but `RootfsInstaller.kt` runs on a real
user's device, with no token available at all (embedding one in the APK would be
extractable/abusable, deliberately ruled out). As long as the rootfs's source repository stays
private, the lightweight variant will fail with a 404 at runtime — this isn't an intermittent
bug, it's a known architectural limit (see `RootfsInstaller.kt` and
`rootfs-embebido.md`, "Private repo and access token" section).

**Wizard flow** (`WizardInstallFragment.kt`): runs `kairos.sh` (base bootstrap), attempts
the rootfs (embedded or downloaded, depending on the build variant), and if it fails, falls back
to the usual behavior — a normal `pkg install`, package by package, without blocking the rest of
the installation.

## 2. Module installation — checkpoints and the real `--force` gap

`ModuleController.installModule(moduleId, variant, onProgress, onComplete)` — runs
`bash $HOME/scripts/install/<module>.sh --silent [--variant <variant>]` via `ProcessBuilder`,
logging everything to `~/kairos_logs/install_<module>.log` (never shows raw output on
screen, only a spinner — see `BottomSheetInstalacion.kt`). If the process exits with a signal
exit code (segfault, killed), it's decoded into a readable name (`decodeExitSignal()`)
instead of showing just the raw number.

**Checkpoint** logic (which steps already ran, so they're not repeated on every
reinstall) lives INSIDE each `modulos/<module>.sh` — `ModuleController` knows nothing about
that, it just runs the script and waits for the result.

**Known gap**: `installModule()` has no parameter for passing `--force`, even though
several scripts in `modulos/` already support that flag internally to decide between "skip the
existing checkpoint" or "reinstall from scratch". Without that parameter, tapping
"Update/Reinstall" on an already-installed module can have no real effect — the script sees the
checkpoint, skips everything, and still reports success. There's also no "update without
reinstalling everything" path yet for most modules — fixing `--force` without that incremental
update path would just swap "does nothing" for "reinstalls everything from scratch every time".

## 3. AI model downloads — 2 separate systems, 2 different engines

Kairos has TWO completely independent inference engines, each with its own model download
mechanism — they never share code or UI:

### 3a. Ollama — curated catalog + real streaming

`ModelsFragment.kt` shows a curated catalog of verified models plus a free-text "(advanced)"
field for any other model name. The actual download is done by
`OllamaApiClient.pullModel(name, onProgress)` — it sends `POST /api/pull` with `"stream": true`
against the local Ollama server (`127.0.0.1:11434`), and parses each line of the response
(NDJSON streaming) to extract `completed`/`total` and compute real speed/ETA. The model
ends up managed by Ollama itself (in its own internal directory, not in the app's storage).

### 3b. llama.cpp — manual GGUF, the app's own private storage

`LocalAIFragment.kt` (local engine, `llama-engine/`, see `llama-cpp-local-engine.md`) has no
closed catalog — the user can also paste a direct URL to a `.gguf` file
(typically from Hugging Face). `LocalModelManager.downloadModel(context, url, fileName,
onProgress)` downloads to `context.filesDir/models/` (the app's PRIVATE storage — not Termux's
`$HOME`, independent of the rootfs) with:
- Real progress with speed/ETA.
- Multi-layer validation before accepting the download: minimum size vs. declared
  `Content-Length` (ratio ≥95%, doesn't require an exact match since not every server sends
  the correct header), and verification of the **real GGUF magic header** (first 4 bytes =
  the literal `"GGUF"`) — this prevents accepting a download that got cut off midway, or an
  HTML error page returned with HTTP 200 as if it were a valid model.
- Automatic cleanup of orphaned `.part` files (canceled/interrupted downloads).

**Why these are 2 separate systems instead of one**: Ollama manages its own models
internally (its own format, its own HTTP server) — Kairos just asks it to download. The
embedded llama.cpp has no server or manager of its own — the `.gguf` file is loaded
directly into the app's process via JNI, so Kairos has to handle the download and
storage itself. Sharing code between the two wouldn't make real sense, beyond the
similar UX patterns (spinner, speed/ETA) that were deliberately replicated.
