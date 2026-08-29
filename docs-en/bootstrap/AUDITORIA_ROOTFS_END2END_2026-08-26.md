# Real incident history of the embedded rootfs and how it was resolved

This document collects, in technical detail, the real problems found while developing the
embedded rootfs mechanism (see `ROOTFS_EMBEBIDO.md`) and how they were diagnosed and fixed. It's
kept as a reference for anyone debugging a similar problem or trying to understand why the
current design makes the choices it does.

## Final state summary

The full flow — extracting the embedded rootfs without a network connection, installing it for
real via `apt`, and successfully running the post-rootfs setup script — is **confirmed working
end to end on a real device**, after resolving a series of issues found across several rounds of
testing. The section below documents the actual path to get there, because each incident reveals
a real constraint of the environment (Android, Termux, the package index itself) worth knowing
ahead of time.

## Known gap: glibc support packages not covered by the embedded rootfs

Confirmed as a **deliberate design limitation, not an oversight**: five GNU libc support packages
(used by prebuilt binaries that don't run directly on top of Bionic/Termux) live in a completely
separate APT repository from the main index that the rootfs generator queries. Covering them
would require the generator to query a second package index and resolve that dependency
separately — a real extension of the script, not a simple line addition to the package list.

**Practical impact**: the setup wizard leaves the package groups the rootfs does cover resolved
without a network connection, but the glibc-support install step still downloads normally the
first time — it doesn't block startup, it simply doesn't benefit from the "no network"
acceleration the other groups get. It's an expected partial degradation, not a failure.

**Path to closing it in the future**: add a second package-index/package-list pair to the
generator (`build_rootfs.py`), and an additional checkpoint in the installer for that group. Not
urgent, since no critical first-launch component depends on this package group.

## Incident 1 — child-process environment variables built incorrectly

**Observed symptom**: the step after rootfs extraction (the setup script that prepares the full
environment) failed with a "cannot run bash" error, both with and without the embedded rootfs.

**Root cause**: the routine that invokes that script built the child process's environment
variables (`HOME`, `PREFIX`, `PATH`, dynamic linker path) manually instead of using the shared
helper already proven elsewhere in the app — it forgot to set the `SHELL` variable (needed for
subsequent child processes launched by the setup script itself to resolve their command
interpreter correctly), and it used the Android process's own `HOME` instead of the real Termux
`HOME`.

**Fix**: replace the manual environment setup with the shared helper already used elsewhere in
the app to launch processes inside the Termux environment.

## Incident 2 — unreliable relative-name binary resolution right after bootstrap extraction

**Observed symptom**, with incident 1 already fixed: a different error of the same pattern —
"cannot run apt," in the step that installs the rootfs's `.deb` files, a method that was already
correctly using the incident-1 helper.

**Likely root cause**: two different binaries failing with the same kind of error, in two places
that already had the environment correctly configured, pointed to resolving a binary by name
(relying on `PATH` already being fully resolved) not being 100% reliable on at least one real
device immediately after the bootstrap finishes extracting — possibly a real filesystem delay
leaving the freshly extracted binary executable with some lag.

**Fix**: always use absolute paths to the critical binaries (`bash`, `apt`) instead of relying on
`PATH` resolution, with an automatic retry and a short pause as a safeguard against a possible
transient delay.

**Persistent log**: a combined log file instrumenting every phase of the install process
(bootstrap, rootfs, setup script) was added — essential for precisely diagnosing the rest of the
incidents in this list instead of relying solely on error screenshots.

## Incident 3 — initial hypothesis ruled out: "the embedded rootfs wasn't detected"

**Observed symptom**, in a later test with a newer rootfs (a larger package count): the wizard
showed an "extracting rootfs" message and then errored out. The initial hypothesis was that
detection of the embedded artifact had failed and the installer had fallen back to the
network-download path.

**Investigation by code reading**: the text the wizard displays throughout the entire rootfs
install process is computed once, up front, directly from the result of the check for whether the
artifact is embedded. If that check had failed, the text shown would have been "downloading and
installing," not "extracting" — the very text that was observed is evidence that detection
**did** work correctly.

**Actual conclusion**: the failure wasn't in detecting the embedded artifact, but in a later step
(copying the artifact, extracting the `.tar.xz`, or installing the `.deb` files) that shares the
same on-screen status text across all three phases. This correction of the hypothesis was key to
not continuing to investigate in the wrong direction.

**Hygiene fix applied in parallel, without confirming it was the root cause**: the `.xz`
extension was excluded from Android's build system's automatic resource recompression — without
that exclusion, the packager can recompress an already-compressed file with DEFLATE when
including it as a resource, which is both a build-time waste and a runtime performance risk for
large binary assets. It's a low-risk fix, valid regardless of whether it ended up being the root
cause of this specific incident.

## Incident 4 — real root cause: a package conflict within the rootfs itself

**Observed symptom, precisely reproduced on a real device with the diagnostic log active**:

```
[RootfsInstaller] EXCEPTION: IllegalStateException: apt install exited with code 100:
The following packages have unmet dependencies:
 nodejs : Conflicts: nodejs-lts but 24.18.0-1 is to be installed
 nodejs-lts : Conflicts: nodejs but 26.4.0-1 is to be installed
E: Unable to correct problems, you have held broken packages.
```

**Confirmed root cause**: the generated rootfs simultaneously contained two mutually exclusive
Node.js variants (flagged as `Conflicts:` against each other in Termux's own package index). The
explicit root package list only requested one of the two variants, but the transitive dependency
resolver always took the first alternative of any `pkgA | pkgB` dependency, without checking
whether the other alternative was, in fact, the one already requested on purpose — thereby
dragging in the unwanted variant as a transitive dependency of some other package in the closure.

**Fix applied in the rootfs generator**: the resolver now keeps every alternative of each
dependency (not just the first) and, when resolving the closure, prioritizes whichever
alternative is already part of what was explicitly requested, instead of always taking the first
one listed. This fix applies to any conflict of this kind, not just the specific Node.js case —
it's the underlying cause, not a package-specific patch.

## Final end-to-end confirmation

With the package conflict fixed, the full flow was reproduced on a real device from a clean
install: the rootfs extracted, installed, and correctly marked its checkpoints, and the
post-rootfs setup script ran and finished without errors, leaving the environment ready to use.
The package groups covered by the embedded rootfs showed practically instant install times
(already resolved via the pre-marked checkpoints) — the "no network" saving is real and
measurable in practice.

Two non-blocking side findings turned up during this final test:

- The post-rootfs setup script was pointing at the wrong path for its shared function library,
  which silently degraded some secondary steps (without strict error checking, the failure didn't
  interrupt the script while going unnoticed). Fixed by pointing at the real path.
- The first `apt install` pass over all the packages took several minutes and timed out (possibly
  some package's configuration prompt hanging); the existing automatic retry completed the
  install almost immediately, since most of the first pass's work had already been done.
  Non-blocking thanks to the retry, but it remains an open item to identify which specific
  package causes that behavior, in order to avoid the timeout on every real install.
