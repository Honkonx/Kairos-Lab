# Embedded rootfs — how it actually works

Kairos can embed a set of base Termux packages (the "rootfs" used by the first-run setup wizard)
directly inside the APK, so the initial setup wizard doesn't depend on mobile data or a slow
Wi-Fi connection the first time the app is opened. This document describes the full mechanism:
where the packages come from, how they're packaged, how they get installed on the device, and
the design decisions that led to the current scheme.

## What the embedded rootfs is NOT

It's important to separate two layers that are easy to confuse:

| Layer | What it installs | Where it comes from | When it runs |
|---|---|---|---|
| **Minimal bootstrap** | `bash`, `dpkg`, `apt`, coreutils — the bare minimum needed for the install prefix to exist and for `apt` to work | Official `termux-packages` zip, downloaded and checksum-verified at build time, compiled into a native library inside the APK | First launch, always — there is no Kairos install without this |
| **Embedded rootfs** | ~190 additional packages (`git`, `python`, `nodejs-lts`, build tooling, multimedia utilities) that the first-run setup wizard needs | A Kairos-specific artifact, generated from the public `packages.termux.dev` index | Setup wizard, after the minimal bootstrap |

The embedded rootfs does **not** include the application's modules (Ollama, n8n, AI agent CLIs,
etc.) — those are still downloaded on demand when the user activates them, identically whether or
not the embedded rootfs is present.

## Why not just "extract and copy" the packages

An earlier version of this mechanism extracted the `.deb` files during the build pipeline and
copied the already-decompressed files straight into the install prefix on the device. It's the
fastest approach, but it has a real problem: `dpkg`/`apt` on the device never learn that those
packages are "installed." Any update check (`apt list --upgradable`) or already-installed check
stops working for everything that arrived this way, and any script relying on `dpkg -l`/`dpkg -L`
to know what's installed becomes blind to those packages.

**Design adopted**: the generation pipeline downloads the real `.deb` files as-is (without
extracting them) and packages them into a single `.tar.xz`. On the device, that `.tar.xz` is
extracted to a temporary folder and installed with a real `apt install -y <list of .deb files>` —
the same mechanism a manual install would use, just without needing a network connection because
the `.deb` files are already on the device. The result: `dpkg`/`apt` end up with the correct
records, and update checks work with Termux's normal tooling, without inventing a parallel
bookkeeping system.

A hybrid approach was also evaluated — pre-generating, during the build pipeline, both the
extracted files and the corresponding fragments of the `dpkg` database (`/var/lib/dpkg/status`,
file lists) to copy directly without invoking `apt` on the device at all. It's a real and valid
future optimization, but it wasn't implemented: before investing in it, it was necessary to
confirm in practice whether `apt install` time over ~190 local packages was actually a noticeable
problem, and it turned out not to be.

## Generating the rootfs (`build_rootfs.py`)

A pure Python 3 script (standard library only — `urllib`, `gzip`, `hashlib`), with no external
dependencies and no need for `dpkg-deb`. Flow:

1. **Downloads the package index** (`Packages.gz`) from the public repository at
   `packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/`.
2. **Parses apt's control-file format** (blocks separated by a blank line, `Key: value` fields)
   with a custom parser. For the `Depends:` field, when a dependency offers several alternatives
   (`pkgA | pkgB`), the resolver prioritizes whichever alternative is already in the list of
   explicitly requested packages — this prevents an ambiguous transitive dependency from dragging
   in a package that conflicts with something already requested on purpose (see the known-issues
   section below).
3. **Resolves the transitive dependency closure** with a breadth-first search starting from a
   canonical list of root packages. A root package missing from the index is a hard error
   (normally indicates a typo); a missing transitive dependency is only a warning, since it might
   be a virtual package or something already provided by the base system.
4. **Downloads each `.deb`**, verifying its SHA-256 against the one published by the apt index
   itself (never a hand-written hash) — if it doesn't match, the build fails. Files already
   downloaded with the correct hash aren't re-downloaded between runs.
5. **Copies the `.deb` files without extracting them** to the output folder and writes a
   `manifest.json` (package → version) for traceability.

The pipeline itself (not the script) packages the resulting folder into a `.tar.xz` and computes
the checksum published alongside the artifact.

### Package coverage and its real scope

The list of root packages mirrors the base install stages of the first-run setup wizard, grouped
by stage: a "core" group (Python, Node.js LTS, Git, networking tools, SSH, `proot`/
`proot-distro`, compression utilities, SQLite, etc.), a build-tooling group (`build-essential`,
`clang`, `make`, Rust, `pkg-config`, OpenSSL), and a multimedia/GPU/utility group (FFmpeg, image
libraries, generic Vulkan drivers, system utilities).

**Deliberate limitation, documented since the original design**: glibc support packages (used by
prebuilt binaries that don't run directly on top of Bionic) live in a completely separate APT
repository from the main index (`termux-glibc`, not `termux-main`), and the current generator only
queries a single index. Covering those packages would require a second index fetch and a second
dependency resolution pass — a real extension of the script, not a simple line addition to the
package list, and it isn't implemented. The practical impact is limited: that install step still
works via a normal network download the first time, simply without the "no network" benefit that
the other package groups get.

## Installing on the device

At runtime, the installer decides whether to use the embedded rootfs solely by checking whether
the `.tar.xz` artifact is present among the APK's bundled resources — it doesn't depend on any
build flag. If it's present, it extracts it with a real Java decompression/unarchiving library
(without invoking external `tar` binaries), installs the `.deb` files with `apt install -y`, and,
if everything succeeds, pre-marks the checkpoints corresponding to each package group in the
setup wizard's progress file — so the wizard detects them as already resolved and skips them
through its own existing mechanism, with no logic changes to it.

If installing the embedded rootfs fails for any reason (missing artifact, corrupted `.deb`, `apt`
error), the failure is silent and non-blocking: no checkpoint gets marked, and the setup wizard
simply continues with the normal package-by-package download over the network.

## Two APK variants

- **APK without embedded rootfs** (lightweight variant): the setup wizard downloads packages on
  demand, over the network, like a regular Termux install.
- **APK with embedded rootfs**: includes the `.tar.xz` as an APK resource; the setup wizard
  detects and uses it automatically, without a network connection, for the package groups it
  covers.

Both variants share exactly the same installation code — the only difference is whether the
artifact is present in the app's package or not.

## Real issues resolved during development

Documented here because they're useful for anyone adapting or debugging this mechanism:

- **Child-process environment variables built by hand**: an early version of the routine that
  invokes the post-rootfs setup script built the process environment variables (`HOME`, `PREFIX`,
  `PATH`, etc.) manually instead of using the shared helper already used elsewhere in the app, and
  ended up using the Android process's own `HOME` instead of the real Termux `HOME` — this caused
  a "cannot run bash" error. Fixed by reusing the existing shared helper.
- **Unreliable relative-name binary resolution right after the bootstrap finishes extracting**:
  invoking `apt`/`bash` by name (relying on `PATH` already being fully resolved) intermittently
  failed immediately after the bootstrap finished extracting, on at least one real device. Fixed
  by always using absolute paths to the binaries, with a retry and a short pause as a safeguard
  against a possible transient filesystem delay.
- **Real conflict between two packages within the Termux index itself**: during an end-to-end
  test, `apt install` of the ~190 packages failed with a declared conflict between two Node.js
  variants (an LTS one and a non-LTS one) — both ended up in the dependency closure because the
  resolver blindly took the first alternative of any `pkgA | pkgB` dependency, without checking
  whether the other alternative was, in fact, the one already requested on purpose in the root
  package list. Root cause fixed in the dependency resolver (see point 2 above): it now
  prioritizes the alternative that's already part of what was explicitly requested.
- **Unnecessary recompression of the artifact by Android's resource packager**: without an
  explicit exclusion, Android's build tooling can recompress an already-compressed `.tar.xz` file
  with DEFLATE when including it as a resource — a build-time waste and a runtime performance
  risk for large compressed binary assets. The `.xz` extension was excluded from resource
  recompression as a hygiene measure, regardless of whether it was the cause of any specific
  incident.
- **Post-rootfs setup script pointing at the wrong shared-function library path**: without strict
  error checking, some steps of the script failed silently (utility commands not found) without
  the failure being obvious at a glance. Fixed by pointing at the real path of the shared function
  file.
- **Timeout on the first `apt install` pass**: in at least one attempt, the first install pass of
  the ~190 packages took several minutes and timed out (possibly some package's configuration
  prompt hanging); the existing automatic retry completed the install almost immediately because
  the first pass had already left nearly everything installed. Non-blocking thanks to the retry,
  but it remains a follow-up item to identify which specific package produces the hanging prompt.

With all of these fixes in place, the complete flow — extracting the embedded rootfs without a
network connection, installing it for real via `apt`, and successfully running the post-rootfs
setup script — was confirmed working end-to-end on a real device.

## Why this design (decision summary)

- **No custom `.deb`/`ar` parser at any critical point**: generation only resolves dependencies
  and downloads; the actual installation is done by `apt` on the device. The only binary-archive
  extraction done by custom code (the `.tar.xz` container itself) uses standard, well-tested
  decompression libraries, not a hand-written parser.
- **Checksum published alongside the artifact**, not embedded in source code — avoids having to
  manually update a hash every time the rootfs is regenerated.
- **The post-rootfs setup script needs no code changes at all** — the embedded rootfs mechanism
  only pre-marks the checkpoints that script already knows how to read.
- **Silent, non-blocking fallback** on any embedded-rootfs failure — the setup wizard is never
  blocked by this mechanism; in the worst case, it simply loses the "no network" speedup.
