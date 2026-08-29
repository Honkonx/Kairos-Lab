# Native Cloudflare tunnel (embedded cloudflared)

## The problem

The tunneling module exposes local services to the internet via `cloudflared tunnel --url ...`
or `ngrok`. The Cloudflare button consistently failed on real Android devices with a DNS
resolution error:

```
lookup api.trycloudflare.com on [::1]:53: connection refused
```

### Root cause

The official `cloudflared` binary is a Go binary built with `CGO_ENABLED=0` (standard practice
for cross-platform Go binaries), meaning its DNS resolution relies entirely on Go's pure
resolver. That resolver looks for `/etc/resolv.conf` at the operating system's standard path —
on Android that path does not exist (it's read-only and can't be created without root
privileges).

Termux maintains its own working `resolv.conf` inside its prefix, but Go's resolver never looks
there, and there's no environment variable to redirect it. The `GODEBUG=netdns=go`/`netdns=cgo`
variable, commonly cited as a fix for this kind of issue, has no effect here: the official
binary was never built with cgo support, so there's no alternate resolver to switch to. The
device's normal DNS resolution (e.g. via `curl`) works fine — the failure is specific to how
pure-Go binaries resolve names in a sandboxed Android environment.

## The fix: compile cloudflared from source with real cgo support

Instead of shipping the official (pure-Go) binary, the build pipeline compiles `cloudflared`
from its own source with `CGO_ENABLED=1`, using the Android NDK's `clang` toolchain targeting
`android/arm64`. A cgo-enabled binary on Android uses Bionic's real `getaddrinfo()` (Android's
libc) to resolve names — the same mechanism any normal Android app uses, with no dependency on
`/etc/resolv.conf` whatsoever.

### CI build step

```bash
NDK_CLANG="$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
git clone --depth 1 --branch <tag> https://github.com/cloudflare/cloudflared.git
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$NDK_CLANG" \
  go build -trimpath -ldflags "-s -w" \
  -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so \
  ./cmd/cloudflared
```

Relevant details:

- The API level in the clang binary name (`aarch64-linux-android26-clang`) matches the project's
  `minSdkVersion`.
- The binary is not checked into the repository — it's compiled fresh on every CI run, like
  other native dependencies of the project.
- The output is placed in `app/src/main/jniLibs/arm64-v8a/`, the standard location the Android
  Gradle Plugin automatically packages inside the APK with execute permissions. This avoids the
  W^X restrictions Android 10+ imposes on binaries written at runtime (which, if executed
  directly from the app's private storage, may lack execute permission).

### App integration

The tunnel manager resolves the native binary's path at runtime from the app's native library
directory (never hardcoded, since Android assigns a different path per install):

```kotlin
private fun nativeCloudflaredPath(nativeLibDir: String?): String? {
    if (nativeLibDir.isNullOrEmpty()) return null
    val f = File(nativeLibDir, "libcloudflared.so")
    return if (f.exists() && f.canExecute()) f.absolutePath else null
}
```

When the native binary is available, it's used directly and `GODEBUG=netdns=go` is **not**
applied to it (applying it would revert the binary to Go's pure resolver even though it was
built with cgo, defeating the purpose of the native build). `ngrok` still uses the binary
distributed through Termux with that variable, since it doesn't yet have an equivalent native
alternative (see below).

### Fallback behavior

If the native `.so` isn't present in an install (e.g. an APK built before this step was added),
the tunnel manager automatically falls back to the previous path — `cloudflared` installed
inside the Termux environment via the package manager — without breaking the build or the app.
In that scenario the DNS resolution issue persists, but in isolation, without affecting the rest
of the system.

## ngrok: a viable native alternative, not yet implemented

Whether an equivalent path existed for `ngrok` was investigated. The ngrok CLI is closed source,
but ngrok publishes **`ngrok-go`** (`golang.ngrok.com/ngrok/v2`, MIT licensed, genuinely open
source), a Go SDK that establishes ngrok tunnels programmatically without needing the CLI at
all. Being a standard Go module, it's cross-compilable using the same CGO + NDK pattern already
implemented for `cloudflared`.

The difference from `cloudflared` is that `ngrok-go` is a library, not a ready-made executable —
it would require writing a small custom `main.go` that imports it and calls the SDK's functions
with the corresponding auth token. The cross-compilation effort is already solved and reusable;
only the cloned/compiled repository would change. This is documented as a viable future
improvement, pending implementation.

## Validation status

This mechanism has been implemented and compiles successfully in the CI pipeline. End-to-end
validation on a real device (confirming that the public Cloudflare Quick Tunnel URL appears and
resolves correctly) remains the next step before considering the fix fully closed.
