# Building the rootfs and the APK locally, without relying on GitHub Releases

The continuous-integration pipeline for the embedded rootfs (generation → publish as a Release →
download at APK build time) exists because a CI flow needs an intermediate place to fetch the
artifact from between two separate jobs running on ephemeral machines. **For building on a local
development machine, that intermediate step isn't needed at all** — you can generate the rootfs
and embed it directly, without publishing anything or depending on any authentication token.

## Important concept: what the rootfs does and doesn't embed

The embedded rootfs does **not** include the application's modules (AI agent CLIs, services,
utilities) — those keep being downloaded on demand when the user activates them. What it embeds
is only the base system packages (Git, Python, Node.js, build tooling, system utilities) that the
first-run setup wizard needs to install before leaving the environment ready to use.

## Why a remote repository isn't needed for a local build

- The rootfs generator is pure Python 3 (standard library only — no `dpkg-deb`, no compilation
  dependencies) — it runs identically in any Linux/WSL environment and in a CI runner.
- The build task that downloads the rootfs from a remote repository only activates if a specific
  environment variable is present; if it isn't, the build system never touches the rootfs
  artifact, neither to download it nor to modify it (with one exception: a full build clean does
  delete it if present — see below).
- The rootfs installer, at runtime on the device, decides whether to use it solely by checking
  whether the `.tar.xz` file exists among the APK's bundled resources — it doesn't depend on any
  build flag. **If the file is there, the app uses it, no matter how it got there.**

Conclusion: it's enough to generate the `.tar.xz` by hand and copy it directly into the app's
resource folder before building — no publishing, no access token, no touching releases.

## Step 1 — Generate the rootfs

Requirements: Python 3 (typically already present on any modern Linux/WSL distribution), `tar`,
and `xz-utils` (install it if the `xz` binary is missing).

```bash
# From the repository root
mkdir -p /tmp/rootfs_staging /tmp/rootfs_cache
python3 tools/rootfs/build_rootfs.py /tmp/rootfs_staging /tmp/rootfs_cache

# Package it (same commands the CI pipeline uses, minus the publish step)
tar -cJf /tmp/kairos-rootfs-aarch64.tar.xz -C /tmp/rootfs_staging .
sha256sum /tmp/kairos-rootfs-aarch64.tar.xz
ls -lh /tmp/kairos-rootfs-aarch64.tar.xz
```

The script should list the number of declared root packages, resolve the transitive dependency
closure (several hundred packages, depending on the current list), download each `.deb` with
checksum verification, and finish without errors. If a root package isn't found in the package
index, that's a typo in the package list — a real finding to fix before continuing.

## Step 2 — Build the APK without the embedded rootfs (lightweight variant)

First confirm that the rootfs artifact does **not** already exist in the app's resource folder
(remove it if left over from a previous build). Then build normally, using the project's usual
build environment variables.

This is the reference APK — no embedded rootfs, useful as a size baseline and to confirm that
generating the rootfs in Step 1 didn't break anything in the normal build.

## Step 3 — Build the APK with the embedded rootfs

Copy the `.tar.xz` generated in Step 1 to the exact location the installer expects inside the
app's resource folder, then build with the same command as always — no additional environment
variable or access token is needed: those variables only control **automatic download** from a
remote repository, not whether a file already present in the resource folder gets packaged (the
build system always does that, like any other resource of the app module).

It's worth measuring the actual size of the resulting APK at this step and comparing it against
Step 2's, to get a concrete figure of how much the embedded rootfs adds to the final APK (the
`.tar.xz` itself plus packaging overhead).

## Step 4 — Verification on a real device

Install the compiled APK on a physical device and open the first-run setup wizard (or do a clean
reinstall if a previous installation already exists) to confirm the embedded rootfs extracts and
installs without needing a network connection. A successful compile isn't enough to consider this
mechanism closed — real device verification is what confirms the full flow works end to end.

## About APK size and architecture variants

A "universal" build (packaging native binaries for all four supported Android architectures —
ARM64, ARM, x86, x86_64) is noticeably heavier than one restricted to a single architecture,
because it includes the minimal Termux bootstrap and the project's native libraries for each of
the four. The embedded rootfs itself is architecture-exclusive (ARM64, the one used by the real
supported Android devices), so it isn't the rootfs that grows with a universal variant — it's the
rest of the APK's native content. Restricting the build to a single architecture should produce a
real, measurable saving compared to the universal build.

## What you don't need for this flow

- **No container runtime needed** — the rootfs generator doesn't compile anything, it only
  downloads already-compiled binary packages from the official Termux repository.
- **No authentication token needed**, nor publishing any remote artifact — that's only necessary
  so a CI pipeline (which runs on an ephemeral machine without the repository already checked
  out) can pass the artifact between two separate jobs.
- **No need to recompile Termux's packages from source** — that would only be necessary for a
  much deeper scenario (changing the application's package identity), out of scope for this
  mechanism.
