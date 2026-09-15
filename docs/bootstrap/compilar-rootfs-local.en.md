# Building the rootfs and the APK locally

A guide for assembling the three build artifacts without depending on GitHub Actions: the
embedded rootfs, the APK without the rootfs (lightweight variant), and the APK with the rootfs
embedded. The whole process can run on a WSL Debian instance (or any Linux with Python 3) plus a
normal Gradle build on the host — no Docker, nothing published, no CI tokens.

## A note on concept

The rootfs does **not** embed Kairos's modules (`modulos/*.sh`). It embeds the **base packages
the wizard needs on first launch** (`tools/rootfs/package_list.txt`, ~46 root packages →
~189 with transitive dependencies: git, python, nodejs, etc.), so the wizard becomes just
extraction + installation + configuration, with no network required. Modules are still
downloaded separately, on demand, when the user activates them — that doesn't change.

## Why GitHub Releases aren't needed for a local build

The CI pipeline (`build-rootfs.yml` → Release → `build-app-rootfs.yml` downloads that Release)
exists because GitHub Actions needs somewhere to pull the artifact from between two separate
workflows running on different ephemeral machines. For a fully local build, that intermediate
step isn't necessary:

- `tools/rootfs/build_rootfs.py` is pure Python 3 (`urllib`, `gzip`, `hashlib` — all stdlib, no
  `dpkg-deb` or build dependencies) — it runs the same on a WSL Debian box as it does in CI.
- `app/build.gradle` (the `downloadRootfsAsset` task) only activates if `KAIROS_EMBED_ROOTFS=true`
  is set. If it isn't set, Gradle never touches `app/src/main/assets/kairos_rootfs.tar.xz`,
  neither to download it nor to delete it (exception: `gradlew clean` does delete it if it
  exists — see below).
- `RootfsInstaller.kt` decides at **runtime** whether to use the embedded rootfs simply by
  checking whether `app/src/main/assets/kairos_rootfs.tar.xz` exists — it doesn't depend on any
  build flag. If the file is there, the app uses it, regardless of how it got there.

Conclusion: it's enough to generate the `.tar.xz` by hand and copy it directly to
`app/src/main/assets/kairos_rootfs.tar.xz` before building — nothing to publish, no
`GITHUB_TOKEN`, no touching Releases.

## Step 1 — Build the rootfs (WSL Debian)

Requirements: `python3` (already included in Debian), `tar`, `xz-utils` (`sudo apt install
xz-utils` if `xz` is missing).

```bash
cd /path/to/repo/kairos

mkdir -p /tmp/rootfs_staging /tmp/rootfs_cache
python3 tools/rootfs/build_rootfs.py /tmp/rootfs_staging /tmp/rootfs_cache

# Package it (same command as build-rootfs.yml, minus the Release step)
tar -cJf /tmp/kairos-rootfs-aarch64.tar.xz -C /tmp/rootfs_staging .
sha256sum /tmp/kairos-rootfs-aarch64.tar.xz
ls -lh /tmp/kairos-rootfs-aarch64.tar.xz
```

**Expected output**: the script lists the root packages from `package_list.txt`, resolves the
transitive dependency closure, downloads each `.deb` while verifying its SHA-256 checksum
against the real index from `packages.termux.dev`, and finishes with "Listo." (Done). If any
root package produces `ERROR: paquetes de package_list.txt no encontrados en el índice`
(packages from package_list.txt not found in the index), it's a real typo in
`package_list.txt` that needs fixing before continuing.

## Step 2 — Build the APK without the rootfs (lightweight variant)

First confirm that `app/src/main/assets/kairos_rootfs.tar.xz` **does not exist** (delete it by
hand if it's left over from a previous build). Then do a normal build:

```powershell
$env:TERMUX_PACKAGE_VARIANT = "apt-android-7"
$env:TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0"
.\gradlew.bat :app:assembleDebug
```

This is the reference APK — useful as a size baseline and to confirm that building the rootfs
(Step 1) didn't break anything in the normal build.

## Step 3 — Build the APK with the rootfs embedded

```bash
# Copy the rootfs built in Step 1 to the exact location RootfsInstaller.kt expects
cp /tmp/kairos-rootfs-aarch64.tar.xz app/src/main/assets/kairos_rootfs.tar.xz
```

```powershell
# Same build as always — there is NO need to set KAIROS_EMBED_ROOTFS or GITHUB_TOKEN;
# those variables only control the automatic DOWNLOAD from a Release, not whether the
# file already present in assets/ gets packaged (Gradle always does that, it's just a
# normal asset of the app module).
.\gradlew.bat :app:assembleDebug
```

Compare the resulting size against Step 2 to see how much the rootfs adds to the final APK (the
`.tar.xz` itself plus Android's packaging overhead).

## Step 4 — Verification on a real device

With a device connected via USB:

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

Open the first-launch wizard (or do a clean reinstall if there was an old `$HOME` around) and
confirm the embedded rootfs extracts and installs without network access. This end-to-end
verification is the step that closes the loop — building without testing on a real device isn't
enough.

## About universal APK size

`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0` (the value used in this project) produces a single
universal APK with the bootstrap for all 4 architectures (`aarch64`/`arm`/`i686`/`x86_64`,
~25MB each) plus native binaries for all 4 ABIs in `terminal-emulator`/`x11-server`. The rootfs
itself is aarch64-only (`build_rootfs.py` only requests `binary-aarch64/Packages.gz`), so it
isn't the rootfs that grows with the universal variant — it's the bootstrap plus the native
binaries. Trying `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=1` (or
`-Pandroid.injected.build.abi=arm64-v8a`) should measurably reduce size if a lighter,
single-architecture APK is needed.

## What this plan doesn't need

- **No Docker needed** — `build_rootfs.py` doesn't compile anything, it only downloads `.deb`
  files already compiled from the official Termux repo.
- **No `GITHUB_TOKEN` needed**, nor publishing any Release — that's only necessary so that CI
  (which runs on an ephemeral machine without the repo on disk between workflows) can pass the
  artifact from one workflow to another.
- **No need to recompile `termux-packages` from source** — that would only be necessary for a
  full package-identity rebrand (see `arquitectura-bootstrap.en.md`), a completely different
  topic.

## Gotcha: `gradlew clean` deletes the embedded rootfs

The block `clean { doLast { if (rootfsAssetFile.exists()) rootfsAssetFile.delete() } }` in
`app/build.gradle` deletes `app/src/main/assets/kairos_rootfs.tar.xz` if `gradlew clean` is run.
To rebuild the APK with the rootfs after a `clean`, the `.tar.xz` needs to be copied back in
(Step 3) before building again.

To have both APKs (with and without the rootfs) from the same local build, build first without
the file in `assets/` (Step 2) and only afterward copy the `.tar.xz` and rebuild (Step 3) — not
the other way around, and confirm the file wasn't left over from a previous run before the first
lightweight build.

## Publishing the rootfs as a Release is still needed for CI/production

This guide covers only local test/development builds. If the CI pipeline needs to go back to
automatically producing an APK with the rootfs embedded, `build-rootfs.yml` has to actually be
run and the Release actually published — the local shortcut described here doesn't replace
that, it only allows iterating without depending on CI.
