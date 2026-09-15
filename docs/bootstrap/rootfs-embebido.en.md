# Embedded rootfs

The mechanism that lets Kairos's first launch **extract and install** a base set of packages
instead of downloading them one by one via `pkg install`, with the goal of making the
first-launch wizard work with no network access (or very little).

## Design: real `.deb` packages, installed via `apt`, not a file dump

The embedded rootfs is **not** a backup of already-decompressed files that get copied straight
into `$PREFIX`. It's a set of real `.deb` files (unextracted), packaged into a `.tar.xz`, which
on the device get extracted to a temporary folder and installed with a real `apt install -y`.

This design decision isn't incidental: a dump of already-decompressed files would install
faster, but would leave `dpkg`/`apt` with no record that those packages are installed —
`pkg list --upgradable` would never see them, `dpkg -L <package>` wouldn't know which files
belong to it, and any script that depends on querying the real package state (for example to
detect whether `glibc` is already installed) would stop working. Going through a real
`apt install`, even over local `.deb` files, keeps dpkg/apt as the single source of truth about
what's installed — without inventing a parallel bookkeeping system.

## Pipeline overview

```
                      ┌─────────────────────────┐
                      │  Rootfs build (CI)       │
                      │  tools/rootfs/*.py        │
                      │  only downloads real .deb │
                      │  files (no extraction)     │
                      └───────────┬──────────────┘
                                  │ publishes
                                  ▼
                     "rootfs-<tag>" artifact
        kairos-rootfs-aarch64.tar.xz (loose .deb files) + .sha256 + manifest.json
                     │                              │
        (build time) │                              │ (runtime)
                      ▼                              ▼
         "With embedded rootfs" build          "Without rootfs" build
         downloads+embeds as asset             lightweight APK, no asset
                      │                              │
                      ▼                              ▼
         "With rootfs" APK (no network         "Without rootfs" APK
         in the wizard)                        (downloads at runtime,
                      │                          needs network)
                      └──────────────┬───────────────┘
                                     ▼
                     RootfsInstaller.kt (same extraction/
                     install logic for both cases — only
                     the source of the tar.xz changes)
                                     │
                                     ▼
              100% JVM extraction of the .tar.xz into a
              temporary folder (NOT directly into $PREFIX)
                                     │
                                     ▼
              apt install -y <all the .deb files> — a REAL
              install, dpkg/apt end up with correct records
                                     │
                                     ▼
              setup-script checkpoints get pre-marked
              (core_pkgs/build_pkgs/media_util_pkgs) — the
              setup script skips them via its own mechanism
                                     │
                                     ▼
        Wizard: "Check packages" screen (optional, with a
        spinner) → verifies installed packages, installs
        whatever is missing, checks for and applies updates
        (the same action is available afterward under
        Settings → "Check system packages")
```

## Artifact generation (`tools/rootfs/build_rootfs.py`)

A pure Python script (no `dpkg-deb`, no external dependencies, runs the same in CI as on any
machine with Python 3 and network access), invoked as
`build_rootfs.py <staging_dir> <cache_dir>`. Flow:

1. **`fetch_packages_index()`** — downloads `Packages.gz` from the real
   `packages.termux.dev` index (`termux-main` repo, `binary-aarch64`).
2. **`parse_packages_index(raw)`** — a custom parser for apt's control file format (blocks
   separated by a blank line, `Key: value` fields, indented continuation lines). For each
   package it extracts `version`/`filename`/`sha256`/`depends` — the `Depends:` field only takes
   the first alternative of each `|` and drops the version range.
3. **`resolve_closure(roots, index)`** — a BFS over transitive dependencies starting from
   `package_list.txt`. A root package missing from the index is a hard error (typically a real
   typo in `package_list.txt`); a missing transitive dependency only produces a warning and
   doesn't block the build (it might be a virtual package or something already provided by
   Termux's base system). When a dependency offers several mutually-exclusive alternatives (for
   example `nodejs | nodejs-lts`, packages Termux declares as conflicting with each other),
   resolution prefers whichever alternative is already listed explicitly in `package_list.txt`
   instead of always taking the first one — this keeps two conflicting packages from ending up
   in the same rootfs, which would make `apt install` fail with exit code 100 over the whole
   set.
4. **`download_deb(name, info, cache_dir)`** — downloads each `.deb` with `urllib.request`,
   verifying the real SHA256 against the one published by apt's own index (not a hand-hardcoded
   hash). If the file already exists in `cache_dir` with the correct hash, it isn't downloaded
   again.
5. **`main()`** — orchestrates all of the above, copies (without extracting) each resolved
   `.deb` into `staging_dir`, and writes `manifest.json` (package → version) for traceability.

The script itself never extracts the `.deb` files — the reason is exactly the one described
above: if it did, dpkg/apt on the device would have no record of what's installed.

The final packaging step (`tar -cJf` of the `.tar.xz` and computing the `.sha256`) is done by
the CI workflow, not the Python script — `build_rootfs.py` only leaves the loose `.deb` files
plus `manifest.json` in `staging_dir`. This build is heavy (dozens of downloads), so it's
triggered manually, not on every push — only when `package_list.txt` changes or versions need
refreshing.

## Extraction and installation on the device (`RootfsInstaller.kt`)

The `.tar.xz` extraction is 100% Java/Kotlin, without invoking `tar` via `ProcessBuilder`: it
uses Apache Commons Compress (`TarArchiveInputStream`) and XZ for Java
(`XZCompressorInputStream`), with real progress tracking based on bytes read from the
compressed `.xz` against the total file size.

Installing the extracted `.deb` files is still a real `apt install -y`, via `ProcessBuilder`,
using absolute paths to the binaries (`bash`, `apt`) instead of relative names resolved via
`PATH` — using relative names right after the bootstrap finishes extracting turned out not to
be reliably consistent across all devices, so the current mechanism uses absolute paths with a
retry and a brief wait if the first attempt fails.

After installation, `RootfsInstaller` pre-writes the corresponding checkpoints into the progress
file used by the main setup script (`kairos.sh`) — the script skips them via its own existing
checkpoint mechanism, with no changes needed to that script.

**Silent, non-blocking fallback**: if the rootfs installation fails for any reason (artifact
unavailable, no network, a corrupted `.deb`, `apt install` failing), no checkpoints are marked
and the main setup script continues with a normal `pkg install`, package by package — the
embedded rootfs is a speed optimization, not a hard dependency of the first-launch flow.

## Package verification and updates (`RootfsPackageChecker.kt`)

All verification/install/update logic is pure Kotlin — it doesn't invoke any external
interpreter for this. It reads the package list bundled in the APK itself
(`assets/scripts/rootfs_package_list.txt`) and parses `/data/data/com.termux/files/usr/var/lib/
dpkg/status` directly (the real file dpkg/apt maintains) to know what's installed.

| Function | What it does |
|---|---|
| `verify()` | Reads `/var/lib/dpkg/status`, compares it against the package list → `{installed, missing}` |
| `installMissing()` | `apt install -y` on whatever is missing |
| `checkUpdates()` | `apt update` + `apt list --upgradable`, parsed in Kotlin → a list of packages with new versions |
| `update()` | `apt install -y --only-upgrade` on whatever list is passed in |
| `sync()` | The four above combined — designed for a single button with a spinner, without showing raw log output on screen |

This action is available in two places in the app, both using `RootfsPackageChecker.sync()`:

1. **First-launch wizard** — an optional screen at the end of the process, with a "Check and
   update" / "Skip" button.
2. **Settings** — a "Check system packages" row in the general section.

## Other design decisions

- **No custom `.deb`/`ar` parser at any critical point.** Dependency resolution and downloads
  are handled by Python against the real apt index; the actual install is done by `apt` on the
  device; `.tar.xz` extraction uses a real, battle-tested library (Apache Commons Compress + XZ
  for Java), not a hand-written parser.
- **Plain `tar.xz`**, with no exotic packaging tricks — simplicity and maintainability were
  prioritized over zero-time extraction.
- **Checksum published alongside the artifact** (`.sha256`), not hardcoded in the source code —
  this avoids having to update a hash by hand every time the rootfs is rebuilt.
- **The main setup script (`kairos.sh`) needs no changes** — the checkpoint pre-marking
  mechanism from `RootfsInstaller` is compatible with its existing "skip steps already done"
  logic.
- A subset of packages (the `glibc` ones) is left out of the embedded rootfs for now, because
  they come from a different APT repository than Termux's main index.
- The artifact is packaged into the APK with `androidResources { noCompress += [".xz"] }`, so
  the packaging tool doesn't re-compress an already-compressed `.tar.xz` — this avoids both
  wasted build time and an extra decompression pass at runtime for a large binary.

## Known limitations

- The embedded rootfs artifact weighs several hundred MB (the full `.tar.xz` runs around 300MB
  for the base package set) — it's regenerated only when `package_list.txt` changes
  (adding/removing/updating packages), not on every build.
- The runtime download path (for the lightweight variant, without an embedded rootfs) depends
  on the artifact being hosted somewhere accessible without authentication from the end user's
  device — an authentication token embedded in the APK isn't a viable solution, because any APK
  can be extracted/decompiled and a token would end up exposed. For the lightweight variant to
  work end to end for real users, the rootfs artifact needs to be hosted somewhere with public,
  unauthenticated access.
- The full flow (no-network extraction + `apt install` of every package + pre-marked checkpoints
  + the main setup script continuing on from there) is confirmed working end to end on a real
  device. The first `apt install` attempt over the full package set can take several minutes; if
  that first attempt times out, the mechanism retries automatically once more, which normally
  completes quickly because most of the install work is already done from the first attempt.

## Files involved

| File | Role |
|---|---|
| `tools/rootfs/package_list.txt` | Canonical package list (single source of truth) |
| `tools/rootfs/build_rootfs.py` | Resolves dependencies and downloads the `.deb` files (without extracting) |
| `modulos/rootfs_package_list.txt` (+ a copy in assets) | On-device copy of the list, read by `RootfsPackageChecker.kt` |
| `app/build.gradle` (`downloadRootfsAsset` task, `commons-compress`/`xz`/`viewpager2` deps) | Downloads the artifact at build time when applicable, plus extraction/wizard libraries |
| `app/src/main/java/com/termux/app/util/RootfsInstaller.kt` | 100% JVM extraction (Commons Compress + XZ) plus the real `apt install` |
| `app/src/main/java/com/termux/app/util/RootfsPackageChecker.kt` | verify/installMissing/checkUpdates/update/sync — 100% Kotlin |
| `app/src/main/java/com/termux/app/KairosBootstrap.kt` | Extracts the package list to the device |
| `app/src/main/java/com/termux/app/wizard/WizardActivity.java` | Host of the `ViewPager2` holding the wizard screens |
| `app/src/main/java/com/termux/app/wizard/Wizard{Welcome,Permissions,PhantomProcess,Battery,Install,Check}Fragment.kt` | Wizard screens, each owning its own UI and logic |
| `app/src/main/java/com/termux/app/wizard/WizardPagerAdapter.kt` | `FragmentStateAdapter` for the wizard screens |
| `app/src/main/java/com/termux/app/ui/ConfigFragment.kt` | "Check system packages" button in Settings |
