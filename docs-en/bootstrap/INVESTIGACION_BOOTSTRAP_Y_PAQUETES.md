# Investigation: custom bootstrap, package mirroring, and file isolation

This document gathers the findings from an investigation into three ideas related to the Termux
bootstrap used by the application: customizing the bootstrap itself, hosting a self-owned package
mirror, and whether it makes sense to "hide" operational files from a user without root
privileges.

## 1. How the bootstrap works today

The bootstrap (the minimal set of `bash`, `dpkg`, `apt`, and coreutils needed for the install
prefix to exist and for `apt` to work) is **not downloaded from any URL at runtime**. It's
downloaded at **build time**, directly from the official, public `termux-packages` repository,
with a per-architecture SHA-256 checksum verified byte-for-byte — if the checksum doesn't match,
the build fails hard, rather than continuing with a potentially corrupt bootstrap.

The downloaded zip is compiled into a native library that ships as part of the APK. The native
code wrapping it is a trivial wrapper: it only exposes the embedded zip's contents to the
Java/Kotlin layer of the app — there's no transformation, no custom `sources.list`, nothing
Kairos-specific in that step. What ends up compiled inside the APK is, byte for byte, what the
official Termux project publishes, unmodified.

APT's `sources.list` file (the one that tells the package manager where to look for packages)
also comes as-is from the official bootstrap, pointing at Termux's standard public repository —
it isn't something this project configures or rewrites in the build pipeline. If a self-owned
mirror were ever wanted, the correct point of intervention would be rewriting that file **after**
`apt` is already working on the device (a trivial runtime operation), not touching the bootstrap
build pipeline.

## 2. Package identity: the app shares the same identifier as Termux

The application deliberately shares the same package identifier and the same shared user ID
(`sharedUserId`) as the official Termux app. This isn't "sharing a UID with another app" in the
sense of coexisting — it's the same package identity, which is why the application and official
Termux **cannot be installed at the same time** on the same device. The decision is intentional:
it preserves compatibility with official Termux ecosystem add-ons (for example, the ones that
grant access to the system API or that trigger tasks on device boot), which look specifically for
that package identifier. The practical consequence is that, on the device, the app replaces
Termux rather than coexisting with it.

## 3. File isolation: the app's private directory and Termux's `$HOME` are the same permission tree

The hypothesis investigated was whether the application's private files directory (the standard
sandbox of any Android app) offered any additional isolation over Termux's `$HOME`, in terms of
"hiding" operational files from a user without root privileges.

**Conclusion: there's no permission difference between the two.** The app's root files directory
and Termux's `$HOME` come from the same tree — `$HOME` is simply a subdirectory within that same
tree. Any shell session launched from the app itself is a child process of the app's own process,
automatically inherits the same operating-system user ID, and therefore already has full access
to that entire tree, not just to `$HOME`.

So there's no subfolder that's "more hidden" within the app's own sandbox: everything under that
tree is equally invisible to other apps without root privileges, and equally accessible to the
app's own shell session, whether it's `$HOME` or any other path. The only real difference is a
convention of where the user expects to find things when exploring their own session, not an
operating-system permission difference.

## 4. Self-owned package mirror: viable, with a much narrower scope than it first appears

- The official Termux project's policy for forks with their own distribution is explicit: it's
  recommended to set up your own repository rather than depend on the official host for a
  large-scale fork's traffic.
- No recompilation is needed: the recommended way to mirror the repository is a periodic sync
  (`rsync`) that copies the already-compiled binaries, without going through the full
  cross-compilation pipeline of the Termux packages project.
- Since the application preserves the same shared package identifier and install prefix as
  official Termux, official packages are already binary-compatible — there's no need to "fork"
  packages in the sense of recompiling them.
- The real scope of packages the application uses is narrow: a few dozen packages on a single
  architecture (ARM64). A partial mirror of just those packages is a trivially small problem
  (tens of megabytes), which fits comfortably on any free static hosting.
- The real risk of maintaining a self-owned mirror isn't falling behind on security updates (a
  periodic sync solves that) — it only shows up if you ever want to diverge from the official
  packages with your own patches, which would require the full compilation pipeline and ongoing
  real maintenance overhead.

## 5. Two bootstrap layers, independent by design

It's worth spelling this out explicitly, since it's easy to confuse: there are two separate
mechanisms that both get colloquially called "bootstrap."

| Layer | What it installs | Where it comes from | Source repository |
|---|---|---|---|
| Minimal bootstrap | `bash`, `dpkg`, `apt`, coreutils | Official `termux-packages` zip, compiled at build time | Official, public Termux repository |
| Embedded rootfs | Additional base packages (Git, Python, Node.js, build tooling) | A self-owned artifact generated from the public Termux package index, installed via `apt` at runtime | The application's own distribution repository |

Layer 1 never depends on the visibility of the self-owned repository — it's always public and
official. Layer 2 does depend on how the self-owned rootfs artifact is distributed (see
`ROOTFS_EMBEBIDO.md`). Any future "self-owned mirror" plan should decide explicitly whether it
targets replacing layer 1, layer 2, or both — today they're independent and share no download
code.

## 6. Recovery mechanism for a corrupted install prefix

Neither the official Termux codebase nor any of the reference forks reviewed has a granular
repair mechanism for a corrupted install environment. The universal pattern — in both the
official project and every fork reviewed — is "wipe everything and re-extract from scratch,"
whether done manually or via a retry button. The known recommendation in the Termux community for
a broken bootstrap is, literally, to uninstall and reinstall the app.

This means a smarter diagnosis (categorizing the type of corruption and applying a selective
repair instead of a full re-extraction) would be a genuine improvement over the state of the art
of the Termux ecosystem itself, not just over this particular application — there's no existing
precedent to adopt; it would need to be designed from scratch.

## Conclusions

| Idea | Viability | Effort | Recommendation |
|---|---|---|---|
| Custom bootstrap | Medium — requires touching the protected native build pipeline | Medium-high | Not needed today — no concrete need identified beyond the abstract idea of "customizing it" |
| Partial self-owned mirror (syncing a few dozen packages) | High — this is what the Termux project itself recommends for forks | Low | Viable in the future for real independence from the official host, though not urgent while the official repository stays available |
| Recompiling/forking packages with custom patches | Low for this project's scale | High and ongoing | Not recommended unless a concrete need for a patch the official one doesn't offer shows up |
| Isolating operational files in the private sandbox instead of `$HOME` | No real benefit | N/A | Discarded — same permission tree, would only change the location convention |
| Granular repair of a corrupted environment | High — well-defined concrete pattern, no existing precedent to copy | Low-medium | Real future improvement, low risk |
