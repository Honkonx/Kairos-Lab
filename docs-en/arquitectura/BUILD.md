# BUILD.md — Building

Builds are done **via CI** (recommended, reproducible) or **locally** (see below, requires
Android Studio + SDK/NDK installed). GitHub Actions is the primary CI path; GitLab CI
(`.gitlab-ci.yml`, repo root) is an equivalent alternative when needed.

## Local build (Windows, `tools/build-local.ps1`)

Replicates the steps of `build-app.yml`/`.gitlab-ci.yml` (same Gradle command, same
`llama-engine` prerequisites) without relying on WSL — every step is plain Gradle/CMake/git.

```powershell
.\tools\build-local.ps1              # full lightweight build (clones Vulkan/SPIRV-Headers if missing)
.\tools\build-local.ps1 -SkipVulkan  # if Vulkan-Headers/SPIRV-Headers are already cloned/installed
```

What the script does:
1. Sets `JAVA_HOME` to a JDK 17 (or Android Studio's embedded JBR if nothing better is set).
   CI uses JDK 17 "temurin" — if the local build fails on a Java version mismatch, install a
   separate JDK 17 and set `JAVA_HOME` manually before running the script.
2. Auto-detects `ANDROID_HOME`/`ANDROID_SDK_ROOT` (`%LOCALAPPDATA%\Android\Sdk` if unset).
3. Rewrites `local.properties` (`sdk.dir`) to point at the current machine's real SDK —
   avoids builds breaking because of a path inherited from a different machine/user.
4. Verifies NDK `27.2.12479018` (used by `llama-engine`/Vulkan, distinct from the `29.x` NDK
   used by the rest of the project); installs it via `sdkmanager` if missing.
5. Clones `Vulkan-Headers`/`SPIRV-Headers` as siblings of the checkout (`../Vulkan-Headers`,
   `../SPIRV-Headers` — exactly what `llama-engine/build.gradle` expects via
   `rootProject.file("../...")`) and builds/installs `SPIRV-Headers` with CMake if they don't
   already exist.
6. `./gradlew downloadBootstraps assembleDebug --no-daemon` with
   `TERMUX_PACKAGE_VARIANT=apt-android-7`/`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.

Resulting APK at `app\build\outputs\apk\debug\*.apk`, same as the CI artifact.

### Building `llama-engine` on Windows

Confirmed working end-to-end (a full build, including `llama-engine` with Vulkan, universal
APK). Compiling `llama.cpp`/Vulkan on Windows has a few non-obvious wrinkles compared to a
Linux build (which is what CI runs):

- **A real host compiler is required**: llama.cpp's `llama-ui-embed` host tool looks for a
  host compiler (`g++`/`clang++`) via `find_program`, and on Windows it can mistakenly find
  the Android NDK's `clang++.exe` (which only targets Android, never Windows) if there's no
  real host compiler on the PATH. CMake also caches the detected compiler in
  `CMakeCache.txt`, so re-running the build over the same build directory keeps using the
  stale value even after a real compiler is installed afterward. The fix is passing the host
  compiler explicitly as a CMake argument instead of relying on autodetection.
- **The Vulkan toolchain can be generated with hardcoded Linux paths**: host-toolchain
  generation for `vulkan-shaders-gen` can assume compiler paths like `/usr/bin/gcc` — invalid
  on Windows. These need to be swapped for the real MinGW/w64devkit compiler when the host is
  Windows.
- **`glslc` is platform-specific**: the NDK ships per-host `glslc` binaries
  (`shader-tools/<host>/`) — the correct subdirectory/binary has to be selected based on the
  OS instead of always assuming the Linux variant.

**Requirement**: `w64devkit` (a portable, installer-free MinGW g++ —
github.com/skeeto/w64devkit) extracted into `.build-tools/w64devkit/` (repo root,
gitignored). Only needed to build `llama-engine` on Windows — without it, `build-local.ps1`
warns with instructions and the rest of the project (`app`, `terminal-emulator`,
`terminal-view`, `x11-server`, `termux-shared`) still builds fine.

## The CI workflows (`.github/workflows/`)

| Workflow | Trigger | What it does |
|---|---|---|
| `build-app.yml` ("Build Kairos APK") | manual (`workflow_dispatch`) | Lightweight build — no embedded rootfs, the wizard downloads it at runtime |
| `build-app-rootfs.yml` ("Build Kairos APK (with embedded rootfs)") | manual, requires a prior rootfs Release tag | Build with the rootfs already packaged inside the APK |
| `build-app-rootfsv1.yml` ("Build Kairos APK (with embedded rootfs) v1") | manual, optional tag | Improved variant: auto-detects the most recent release with the `rootfs-` prefix if no tag is passed manually |
| `build-rootfs.yml` ("Build Kairos rootfs") | manual | Assembles the rootfs (`tools/rootfs/build_rootfs.py`) and publishes it as a GitHub Release |

## GitLab CI (`.gitlab-ci.yml`, repo root)

A single job (`build-apk`), equivalent to the lightweight `build-app.yml` above — same
`downloadBootstraps assembleDebug`, same `llama-engine` prerequisites (NDK 27.2.12479018 +
cloning/installing `Vulkan-Headers`/`SPIRV-Headers` as siblings of the checkout).
`when: manual` — has to be triggered by hand from CI/CD → Pipelines. No branch restriction.
The APK is kept as a job artifact with 30-day retention (same as GitHub).

### Real differences: GitLab CI vs GitHub Actions

They're not "the same Ubuntu with a different name" — each real gap cost a failed run before
being found:

| Difference | GitHub Actions (`ubuntu-latest`) | GitLab CI (`eclipse-temurin:17-jdk` + shared runner) |
|---|---|---|
| C/C++ toolchain | `build-essential` (gcc+g++) preinstalled | JDK-only image, no compiler at all — `g++` has to be requested manually |
| Preinstalled Android SDK | Yes — common NDKs and `cmake` versions (including 3.22.1) already on disk out of the box | No — generic image, absolutely no Android tooling preinstalled, everything is assembled via `sdkmanager` in the job itself |
| Install order of the SDK `cmake;3.22.1` component | Irrelevant — already on disk before any Gradle task ran | AGP installs it **lazily, only inside the build** (the first time a `configureCMake*` task needs it) — if any custom code needs it BEFORE that, on a from-scratch SDK it isn't there yet and has to be pre-installed manually |
| Runner size | 4 vCPU / 16GB RAM (standard hosted) | `saas-linux-small-amd64` — noticeably smaller |

## How to trigger the lightweight build (the most common one)

1. GitHub → Actions → "Build Kairos APK"
2. "Run workflow" (main branch)
3. The actual command it runs: `./gradlew downloadBootstraps assembleDebug --no-daemon`
4. Download the APK from the artifact once it finishes

## Real environment variables

- `TERMUX_PACKAGE_VARIANT=apt-android-7`
- `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`
- `KAIROS_EMBED_ROOTFS` — only in `build-app-rootfs.yml`, gates the `downloadRootfsAsset` task in `app/build.gradle`
- `GITHUB_TOKEN` — needed in `build-app-rootfs.yml` to download the rootfs asset from a Release (see `docs/bootstrap/ROOTFS_EMBEBIDO.md`)

## Real pinned versions (`gradle.properties`/`app/build.gradle`)

- NDK `29.0.14206865` (`ndkVersion`, overridable via `JITPACK_NDK_VERSION`)
- compileSdk/targetSdk/minSdk — see `app/build.gradle` directly
- Kotlin, AGP, Gradle — see the root `build.gradle` and `gradle/wrapper/gradle-wrapper.properties`

## Real Gradle structure

```
kairos/                        ← repo root (the termux-app fork lives here directly)
├── build.gradle                ← root build script
├── settings.gradle             ← includes modules: app, terminal-emulator, terminal-view, termux-shared, llama-engine
├── gradle.properties           ← SDK/NDK versions
├── app/build.gradle             ← app module — native UI
├── terminal-emulator/build.gradle
├── terminal-view/build.gradle
├── termux-shared/build.gradle
└── llama-engine/build.gradle    ← llama.cpp NDK module (see LLAMA_CPP_EMBEBIDO.md)
```

## Troubleshooting

### NDK error
The main NDK (r29.0.14206865) is installed by the CI workflow itself; `llama-engine` also
needs the separate NDK 27.2.12479018 (see "Local build" above, which installs it on its own
via `sdkmanager`). If a local build fails on NDK, check which of the two is missing based on
which module is being compiled.

### Rootfs 404 in the lightweight build
If the rootfs is hosted as an asset of a private or restricted Release, the lightweight build
(`build-app.yml`) can get a 404 when trying to download it at runtime without credentials.
See `docs/bootstrap/ROOTFS_EMBEBIDO.md` — the wizard automatically falls back to the classic
package-by-package install if this happens.

### llama-engine doesn't compile / heavier build
`llama-engine` compiles llama.cpp+Vulkan from scratch on every build (including
`build-app.yml`), so it's expected for the build to be heavier than one without the embedded
inference engine. See `docs/ia-local/LLAMA_CPP_EMBEBIDO.md`.
