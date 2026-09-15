# Bootstrap architecture

How Kairos's minimal bootstrap works, what it means for the package identity to be
`com.termux`, and how viable a custom package mirror would be. This document covers the real
mechanism, confirmed by reading the source code (`TermuxInstaller.java`, `app/build.gradle`,
`app/src/main/cpp/`) — not assumptions.

## 1. How the bootstrap works today

`app/build.gradle` (the `downloadBootstrap()` / `downloadBootstraps()` task) downloads, at
**build time** (Gradle, not runtime), the four architecture zips
(`bootstrap-aarch64.zip`, `-arm`, `-i686`, `-x86_64`) directly from:

```
https://github.com/termux/termux-packages/releases/download/bootstrap-<version>/bootstrap-<arch>.zip
```

the official, public `termux-packages` repo, with no authentication. The version is pinned in
`app/build.gradle` (`apt-android-7` variant, the one Kairos uses), with a hardcoded SHA-256
checksum per architecture, verified byte for byte before accepting the file — if the checksum
doesn't match, the build fails hard instead of continuing with a corrupted bootstrap.

The four `.zip` files are not versioned in git (the `*.zip` pattern is in `.gitignore`); they
stay cached at `app/src/main/cpp/bootstrap-<arch>.zip` on the local filesystem after the first
download. Every clean build (CI included) downloads them again from the official repo.

`app/src/main/cpp/termux-bootstrap.c` is a trivial JNI wrapper: it exposes
`Java_com_termux_app_TermuxInstaller_getZip()`, which copies a `blob[]` + `blob_size` array
(defined in `termux-bootstrap-zip.S`, which embeds the entire `.zip` as raw binary data inside
the compiled native library) into a `jbyteArray`. There is no `sources.list` logic of its own,
no transformation, nothing Kairos-specific at this step: the zip that ends up compiled into
`libtermux-bootstrap.so` is byte-for-byte the one `termux-packages` publishes.

`TermuxInstaller.setupBootstrapIfNeeded()` doesn't download anything from a URL at runtime — the
bootstrap zip comes from `loadZipBytes()`, which loads `libtermux-bootstrap.so`
(`System.loadLibrary`) and calls the native `getZip()` method described above. The entire
bootstrap (bash, coreutils, apt, dpkg, precompiled ARM64 binaries) is compiled directly into the
APK as a native library.

The actual `sources.list` that `apt` uses on the device comes straight from Termux's official
bootstrap, pointing at `packages.termux.dev` — standard Termux behavior, not something Kairos
configures. If a custom mirror were ever wanted, the real point of intervention would be
*after* `apt` is already working (rewriting `$PREFIX/etc/apt/sources.list` at runtime, trivial)
— there's no need to touch `app/src/main/cpp/` or recompile anything for that.

## 2. Two distinct "bootstrap" layers, by design

| Layer | What it installs | Where it comes from | When it runs | Source repo |
|---|---|---|---|---|
| **Minimal bootstrap** (`TermuxInstaller.java`) | `bash`, `dpkg`, `apt`, coreutils — the minimum needed for `$PREFIX` to exist and `apt` to work | `libtermux-bootstrap.so` (compiled at build time from the official zip) | First launch, always | `github.com/termux/termux-packages` (official, public) |
| **Embedded rootfs** (`RootfsInstaller.kt`) | ~189 additional packages (`glibc`, `nodejs-lts`, `python`, `git`, etc. — see `rootfs-embebido.en.md`) | Kairos's own artifact, via real `apt install` | Wizard, install screen, after the minimal bootstrap | Kairos's own |

Layer 1 never depends on any infrastructure of its own — it's public, from termux-packages.
Layer 2 does depend on where the rootfs artifact is hosted. They are independent today and
share no download code.

## 3. Package identity — Kairos is `com.termux`, not a separate app

Confirmed in `AndroidManifest.xml` (`android:sharedUserId="${TERMUX_PACKAGE_NAME}"`) and
`app/build.gradle` (`namespace "com.termux"`, with no `applicationId` of its own): Kairos
literally uses the same package as official Termux. It isn't "sharing a UID with another app" —
it's the same package identity.

Practical consequence: Kairos and official Termux cannot be installed at the same time on the
same device. This is intentional (compatibility with official addons like Termux:API and
Termux:Boot, which specifically look for `com.termux`), but it means that, technically, Kairos
already replaces Termux on the device rather than coexisting with it.

## 4. `context.filesDir` vs Termux's `$HOME`

`TERMUX_FILES_DIR_PATH = "/data/data/com.termux/files"` and `context.filesDir` for this package
resolve to the same root directory — `$HOME` (`TERMUX_HOME_DIR_PATH`) is simply the `/home`
subdirectory within that same tree. A bash session launched by `TermuxService`/`ProcessBuilder`
from the app itself is a child process of the app's own process — it automatically inherits the
same UID, and therefore already has full access to all of `context.filesDir`, not just `/home`.

There is no "more private" folder within the app's own sandbox: everything under
`/data/data/com.termux/` is equally invisible to other apps (without root) and equally
accessible from the app's own bash session, whether it's `/home` or any other subfolder. The
real difference is one of convention (which folder the user expects to see with a file explorer
or from their own Termux session), not of OS permissions — any new code that needs to read or
write to the Termux tree should point at `$HOME`/`TERMUX_HOME_DIR_PATH`, not `context.filesDir`,
to keep the convention scripts and the user themselves expect.

## 5. Custom package mirror — viability

Official Termux policy for forks: *"Please don't use the official host in termux forks. Set
up your own repository."* — for a fork with its own distribution, Termux expects it to have
its own host.

Key points:

- No recompilation is needed. The recommended way to mirror is a simple periodic
  `rsync -a --delete rsync://packages.termux.dev/termux termux` — it copies the already-compiled
  binaries, without going through the `termux-packages` build pipeline (Docker + NDK
  cross-compile).
- Since Kairos preserves the same `sharedUserId` and the same prefix
  (`/data/data/com.termux/files/usr`), the official packages are already binary-compatible —
  there's no need to "fork" the packages in the sense of recompiling them.
- Real scope for Kairos: it only uses between 20 and 30 packages on a single architecture
  (aarch64): `glibc`, `glibc-runner`, `openssl-glibc`, `nodejs-lts`, `tmux`, `git`, `python`,
  `proot-distro`, `curl`, `wget`, `tar`, `xz-utils`, `binutils`, `ca-certificates`,
  `resolv-conf`, `qemu-user-aarch64`. A partial mirror of just those packages is a trivial size
  problem (tens of MB), viable to host on any free static CDN.
- The real risk of a periodic rsync mirror isn't falling behind on security (rsync solves that
  on its own) — it only shows up if the project ever wants to **diverge** from the official
  packages (custom versions, patches). That's when the full `termux-packages` build pipeline is
  needed, which implies ongoing maintenance overhead.

### Real precedent for a custom mirror + patched bootstrap

There are published termux-app forks that have already implemented this pattern end to end,
including:

- A custom bootstrap rebuild pipeline (custom build + dedicated CI workflow).
- A diff tool between two bootstrap builds (which package changed, package by package) — useful
  for auditing, something neither Kairos nor official termux-app has today.
- An actual custom mirror (`repo/aarch64`, `arm`, `x86_64`, `i686`, `all`, plus a `Release`
  file), with the same structure as a standard Debian APT repo — confirming in practice that a
  partial mirror of 20-30 packages fits on free static hosting, not just in theory.
- Documentation of the real workflow for when a package with hardcoded paths needs patching
  (using `strings` on the binary to find embedded paths like `/data/data/com.termux`,
  rebuilding with `termux-packages`'s official Docker builder, and reintegrating into the zip
  and the custom mirror).

Two technical warnings documented there, relevant to any future work patching Termux binaries:

- **Hardcoded paths in a binary can't be overridden by an environment variable** — if a binary
  has `/data/data/com.termux/...` embedded at compile time, it has to be recompiled from
  source; there's no environment-variable shortcut.
- **`LD_LIBRARY_PATH` takes priority over `RUNPATH`** — this explains why most Termux libraries
  work despite having an "incorrect" `RUNPATH`, until one shows up that doesn't respect
  `LD_LIBRARY_PATH` and fails in a non-obvious way.

## 6. External reference precedent: alternative bootstrap via a full VM

A radically different approach to avoiding a Termux-style bootstrap is running a full Linux VM
(custom kernel + QEMU + an Alpine-style squashfs rootfs) instead of extracting precompiled
binaries — an entirely different scale of effort (a pipeline that compiles its own kernel and
cross-compiles QEMU for NDK). It's not a pattern that transfers to "modifying Kairos's
bootstrap" — it's building a hypervisor, not adapting an installer. The only genuinely reusable
idea from that approach is its **versioned migrations** system: a script that runs
`/etc/<app>/migrations/<versionCode>.sh` in order between the last applied version and the
current one, with a separately persisted version marker, idempotently. If Kairos ever needs a
versioned bootstrap/setup process that runs incrementally on every app update (today
`KairosBootstrap.kt` only re-extracts everything if the version changed, with no incremental
steps), that pattern applies directly.

## 7. Bootstrap recovery — current state

If `$PREFIX` already exists and isn't empty, the install code doesn't verify anything — it
continues straight through, without checking `bash --version`, `pkg --version`, or any other
integrity check.

The only existing recovery mechanism is the "Try Again" button in the error dialog: it deletes
`$PREFIX` entirely and reruns the installation from scratch, with no attempt at partial repair.
That button only appears if extraction actively fails (an exception during zip/symlink
extraction) — if `$PREFIX` ends up silently corrupted (extraction succeeds but some binary ends
up broken, or the user deletes a file by hand afterward), there's no path in the app to detect
or repair it.

There's a concurrency guard that prevents two extractions from running in parallel — that
specific problem is already solved; the real remaining work is integrity verification,
non-blocking progress, and selective recovery (for example `apt --fix-broken install` or
rebuilding `/var/lib/dpkg/status` instead of re-extracting everything). No granular repair
mechanism was found already implemented anywhere to copy from — neither in official Termux nor
in any fork reviewed — the universal pattern across the whole ecosystem is "delete and
re-extract everything." This confirms that selective/smart recovery would be a real improvement
over the state of the art of the ecosystem, not just over this project — it would have to be
designed from scratch.
