# Native embedded Cloudflare tunnel

Kairos embeds a native `cloudflared` binary compiled specifically for Android, instead of
relying on the official generic Linux binary. This decision fixes a real DNS resolution problem
that affects any pure-Go binary running in a sandboxed, non-root Android environment.

## The problem: DNS resolution of a pure-Go binary on Android

The Tunnel tab exposes a module to the internet via a Cloudflare or ngrok tunnel. The official
`cloudflared` binary, when trying to open a tunnel, consistently failed with:

```
lookup api.trycloudflare.com on [::1]:53: connection refused
```

**Root cause:**

1. `cloudflared` is an official Go binary compiled without cgo support (standard practice for
   cross-platform Go binaries) — its DNS resolver is 100% pure Go.
2. Go's pure resolver looks for `/etc/resolv.conf` at the OS's standard path. On Android that
   path doesn't exist and is read-only without root privileges — it can't be "fixed" by placing
   the file there.
3. The device's environment does have its own functional `resolv.conf` at a different path, but
   Go's resolver never looks there — there's no environment variable that redirects it.
4. Go's debug environment variables for forcing an alternative resolver have no effect, because
   the official binary never had cgo support compiled in — there's no alternative resolver to
   switch to.
5. The device's normal DNS resolution works fine — the problem is specific to how pure-Go
   binaries do their own resolution in this environment.

## The solution: compile cloudflared from source with real cgo for Android

Instead of using the pure-Go binary, the build pipeline compiles `cloudflared` from its own
source with cgo enabled, targeting the Android NDK compiler for the `android/arm64` target. A
binary with cgo enabled on Android uses Bionic's (Android's libc) real `getaddrinfo()` function
to resolve DNS — the same mechanism any normal Android app uses, correct by design, with no
dependency on `/etc/resolv.conf` at all.

### Build (CI)

```bash
NDK_CLANG="$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
git clone --depth 1 --branch <tag> https://github.com/cloudflare/cloudflared.git
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$NDK_CLANG" \
  go build -trimpath -ldflags "-s -w" \
  -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so \
  ./cmd/cloudflared
```

- The API level in the compiler name (`android26`) matches the project's `minSdkVersion`.
- The binary isn't versioned in the repository — it's compiled fresh on every CI run, just like
  the rest of the project's native dependencies.
- It's placed in Android's standard native library location (`jniLibs/arm64-v8a/`), which makes
  the system extract it with execute permission when the APK is installed — sidestepping
  Android 10+'s security restrictions on binaries written and executed at runtime from app
  storage.

### Integration into the app

The app resolves the native binary's path at runtime from the installation's own native library
directory (never hardcoded, since Android randomizes that directory per install). If the native
binary is available, it's used directly and any environment variable that forces Go's pure DNS
resolver is no longer applied — applying it would revert the binary to the problematic behavior
even though it was compiled with cgo.

### Fallback behavior

If an APK was installed from a build prior to this integration, or the native binary's build
step fails for any reason, the app detects that the native binary doesn't exist and falls back
to the previous path (cloudflared installed as a package within the environment) — with the DNS
problem unresolved in that case, but without breaking the rest of the app.

### Verification status

The native binary compiles successfully in the CI pipeline. End-to-end confirmation on a real
device (the tunnel actually coming up and the public URL resolving successfully) is still
pending as of this writing — "it compiles in CI" should not be assumed to mean "it works on
device" without that verification.

## ngrok — same approach, pending implementation

ngrok's official CLI is closed source, but ngrok also publishes an open-source (MIT-licensed) Go
SDK that lets you establish tunnels programmatically without needing the CLI at all. It's a
standard Go module, cross-compilable with the same cgo + NDK pattern already used for
Cloudflare.

The real difference is that this SDK is a library, not a program — a small standalone program
would need to be written that imports it and establishes the tunnel using the auth token the app
already manages. The cross-compilation piece is already solved and reusable as-is; only which
repository gets compiled changes.
