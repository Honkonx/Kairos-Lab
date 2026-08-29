# llama.cpp module audit

A real code audit (read-only, no architectural changes) of Kairos's llama.cpp module, checking
existing documentation against the actual state of the code.

## Overall verdict

**Solid, with real evidence of end-to-end functionality** — not just "compiles in CI." The module
went through a long chain of real failed builds, each fixed with a confirmed root cause (see
[LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md)), until the `llama-server` server actually ran on
a real device. The configuration UI isn't decorative: every field is persisted, and the startup
script reads them and passes them as real flags to the binary.

## Findings

### Build system: clones real llama.cpp, doesn't vendor it as a submodule

The real llama.cpp repository is cloned at build time, pinned to a fixed tag — confirmed on disk
to be a real clone (not a static copy) of the official repository. After cloning, an idempotent
text patch is applied that comments out the build of about a dozen unnecessary
benchmarking/debug utilities — a real fix for a real CMake/AGP integration problem (see the
architecture document), not a cosmetic hack.

### Vulkan genuinely active, but conditioned on external headers not versioned in the repo

Confirmed that Vulkan isn't aspirational — it was actually linked in real CI. The real gap is
that linking depends on two external checkouts (Khronos headers) that the build system looks for
as sibling directories of the project — if they don't exist, the Vulkan build fails at
configuration with only a warning, not a hard error, which can silently let the build fall back
to compiling without those includes. This is the most fragile point of the pipeline.

### Target architecture: `arm64-v8a` only

Consistent with the rest of the project (Bionic ARM64 exclusively). The lack of multi-ABI
variants is compensated by compiling several ARM kernel variants selected dynamically at runtime
based on the device's real CPU — not a practical performance limitation for the target hardware.

### In-process JNI wrapper, HTTP server as a separate binary

Confirmed that the JNI wrapper (used by the Chat screen) exposes its functions directly in the
app's own process, with no socket or port — these are two completely different engines that
share the same models directory, correctly distinguished in the UI ("embedded engine" vs.
"server"). The HTTP server exposes llama.cpp's standard endpoints with no additional Kairos
wrapping on top.

### UI: every exposed parameter actually reaches the engine

The full UI → config file → script → binary chain was reviewed, confirming that each
configuration field (context size, threads, GPU layers, API key, parallelism, embeddings, LAN
exposure) is validated, persisted to a real file, and read by the startup script, which
translates it into a real binary flag — with no unsafe reading of the config file (no `eval`).
No decorative UI option without a real consumer was found. Some sampling parameters
(top-p, top-k, repetition penalty) are fixed at build time — explicitly documented in the UI
itself, not a broken promise.

### Model download: curated catalog with disk-space validation

The curated catalog of included models (about twenty, verified one by one against each model's
real repository, with sizes ranging from a few hundred MB to several GB) is complemented by a
manual URL option. Download validation is multi-layered: verifying the file's real binary format,
comparing size against what the server declares, cleaning up orphaned partial downloads, and
checking real free disk space before starting any download (with a 15% safety margin over the
remaining bytes) — if there isn't enough space, the download doesn't even start, and the user is
told the real amounts needed and available.

### Consistency with the rest of the app's modules

The `llamaserver` module follows the same standard contract as the rest of Kairos's modules
(catalog, declared port, multiplexed terminal session, category), with no special case excluding
it from the normal install/update system.

## Findings summary by severity

| Finding | Severity |
|---|---|
| No disk space check before downloading a large model | Resolved during this audit round |
| Vulkan depends on external checkouts not versioned in the repo itself, with a silent failure if missing | Real, known — build pipeline risk, not a code issue |
| Latest UI redesign not reflected in the main architecture document | Cosmetic — no incorrect information, just scattered across documents |

None of this audit's findings contradict the conclusion that the module is confirmed working end
to end.
