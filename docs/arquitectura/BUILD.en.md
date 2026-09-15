# BUILD.md — Building

Builds are done **via CI** (recommended, reproducible) or **locally on Windows** (see below,
requires Android Studio + SDK/NDK installed) — GitHub Actions is the primary CI path, GitLab
CI (`.gitlab-ci.yml`, repo root) is the alternative. `origin` can be configured to push to
both remotes with a single `git push`, so both pipelines stay available from the same
code.

## Local build (Windows, `tools/build-local.ps1`)

Replicates the exact steps of `build-app.yml`/`.gitlab-ci.yml` (same Gradle command, same
`llama-engine` prerequisites), without WSL — every step is plain Gradle/CMake/git, nothing
Linux-exclusive.

```powershell
.\tools\build-local.ps1              # full lightweight build (clones Vulkan/SPIRV-Headers if missing)
.\tools\build-local.ps1 -SkipVulkan  # if Vulkan-Headers/SPIRV-Headers are already cloned/installed
```

What the script does:
1. `JAVA_HOME` → uses Android Studio's embedded JBR if nothing better is already set (CI uses JDK
   17 "temurin"; if the build fails on Java version, install a separate JDK 17 and set
   `JAVA_HOME` manually before running the script).
2. `ANDROID_HOME`/`ANDROID_SDK_ROOT` → auto-detects `%LOCALAPPDATA%\Android\Sdk` if not set.
3. Rewrites `local.properties` (`sdk.dir`) to point at the machine's real SDK.
4. Verifies NDK `27.2.12479018` (used by `llama-engine`/Vulkan, different from the `29.x` NDK used
   by the rest); installs it via `sdkmanager` if missing.
5. Clones `Vulkan-Headers`/`SPIRV-Headers` as siblings of the checkout (`../Vulkan-Headers`,
   `../SPIRV-Headers` — exactly what `llama-engine/build.gradle` expects via
   `rootProject.file("../...")`) and builds/installs `SPIRV-Headers` with CMake, if they don't
   already exist.
6. `./gradlew downloadBootstraps assembleDebug --no-daemon` with
   `TERMUX_PACKAGE_VARIANT=apt-android-7`/`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.

Resulting APK at `app\build\outputs\apk\debug\*.apk`, same as the CI artifact.

### Building `llama-engine` on Windows

`llama-engine` builds llama.cpp (with Vulkan support) from scratch, which requires a host
toolchain in addition to the Android NDK. Things to watch for when building on Windows:

- The host tool `llama-ui-embed` (part of llama.cpp's `tools/ui/CMakeLists.txt`) needs a real
  HOST C++ compiler — not the Android NDK one (which only compiles for Android). If there's no
  host compiler on the PATH, CMake can end up mistakenly using the NDK's `clang++.exe` and fail
  with errors like `'inttypes.h' file not found`.
- Generating the host toolchain for the Vulkan shader compiler needs valid compiler paths on
  Windows (`gcc.exe`/`g++.exe`), not hardcoded Linux paths.
- The `glslc` shader compiler is provided by the NDK in different subdirectories per host
  platform (`windows-x86_64/`, `linux-x86_64/`, etc.) — you need to point at the correct binary
  for the current host.

**Requirement:** `w64devkit` (portable MinGW g++, no installer — github.com/skeeto/w64devkit)
extracted into `.build-tools/w64devkit/` (repo root, gitignored). Only needed to
build `llama-engine` on Windows — without it, `build-local.ps1` warns with instructions and the
rest of the project (`app`, `terminal-emulator`, `terminal-view`, `x11-server`, `termux-shared`)
builds fine anyway.

## The 4 real workflows (`.github/workflows/`)

| Workflow | Real name | Trigger | What it does |
|---|---|---|---|
| `build-app.yml` | "Build Kairos APK" | `workflow_dispatch` (manual, not automatic on push/PR) | Lightweight build — no embedded rootfs, the wizard downloads it at runtime |
| `build-app-rootfs.yml` | "Build Kairos APK (with embedded rootfs)" | `workflow_dispatch`, requires `KAIROS_ROOTFS_RELEASE_TAG` | Build with the rootfs already packaged inside the APK |
| `build-app-rootfsv1.yml` | "Build Kairos APK (with embedded rootfs) v1" | `workflow_dispatch`, optional `rootfs_release_tag` | Improved variant of `build-app-rootfs.yml`: auto-detects the most recent release with the `rootfs-` prefix (pre-release or release) if no tag is passed manually; works with private repos via Actions' automatic `GITHUB_TOKEN` |
| `build-rootfs.yml` | "Build Kairos rootfs" | `workflow_dispatch` | Assembles the rootfs (`tools/rootfs/build_rootfs.py`) and publishes it as a GitHub Release — see `docs/bootstrap/rootfs-embebido.md` |

## Additional workflows: prebuilt Python wheels for specific modules

Besides the 4 workflows above (which build the APK/rootfs), the repo has manually-triggered
workflows that cross-compile Python extensions with native Rust components
(`aarch64-linux-android`, via `maturin`) for packages PyPI doesn't publish in a format compatible
with Android/Bionic — only `manylinux`/macOS/Windows. The resulting wheel is published as a
Release asset, and the corresponding module's script looks for it there before attempting to
compile Rust on the device itself (much slower and with more failure points than in CI). This
mechanism is verified working end to end for `hf_xet` (a Hugging Face Hub dependency); the
equivalent workflow for `firecrawl-anydoc` (a Hermes dependency) uses the same mechanism but
hasn't been confirmed with a real run yet.

## GitLab CI (`.gitlab-ci.yml`, repo root) — alternative to GitHub Actions

A single job (`build-apk`), equivalent to the lightweight `build-app.yml` above — same
`downloadBootstraps assembleDebug`, same `llama-engine` prerequisites (NDK 27.2.12479018 +
cloning/installing `Vulkan-Headers`/`SPIRV-Headers` as siblings of the checkout). `when: manual`
(doesn't run automatically on every push, has to be triggered manually from
gitlab.com → CI/CD → Pipelines). No branch restriction. The APK is kept as a job artifact,
30-day retention (same as GitHub).

### Real differences between GitLab CI and GitHub Actions

They're not "the same Ubuntu with a different name":

| Difference | GitHub Actions (`ubuntu-latest`) | GitLab CI (`eclipse-temurin:17-jdk` + shared runner) |
|---|---|---|
| C/C++ toolchain | `build-essential` (gcc+g++) preinstalled | JDK-only image, no compiler at all — `g++` has to be requested manually |
| Android SDK preinstalled | Yes — common NDKs and `cmake` versions (including 3.22.1) already on disk out of the box | No — generic image, absolutely nothing Android-related preinstalled, everything gets assembled via `sdkmanager` inside the job itself |
| Install order of the `cmake;3.22.1` SDK component | Irrelevant — already on disk before any Gradle task runs | AGP installs it lazily, only mid-build — if any of your own code needs it BEFORE that, on an SDK built from scratch it isn't there yet, has to be preinstalled manually |
| Runner size | 4 vCPU / 16GB RAM (standard hosted) | `saas-linux-small-amd64` — noticeably smaller |

## How to launch the lightweight build (the most common one)

1. GitHub → Actions → "Build Kairos APK"
2. "Run workflow" (branch `main`)
3. Real command it runs: `./gradlew downloadBootstraps assembleDebug --no-daemon`
4. Download the APK from the artifact once it finishes

## Real environment variables

- `TERMUX_PACKAGE_VARIANT=apt-android-7`
- `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`
- `KAIROS_EMBED_ROOTFS` — only in `build-app-rootfs.yml`, gates the `downloadRootfsAsset` task in `app/build.gradle`
- `GITHUB_TOKEN` — needed in `build-app-rootfs.yml` to download the rootfs asset from the private repo (see `docs/bootstrap/rootfs-embebido.md`, "Private repo and GITHUB_TOKEN" section)

## Real pinned versions (`gradle.properties`/`app/build.gradle`)

- NDK `29.0.14206865` (`ndkVersion`, overridable via `JITPACK_NDK_VERSION`)
- compileSdk/targetSdk/minSdk — see `app/build.gradle` directly, these change more often than this doc
- Kotlin, AGP, Gradle — see `build.gradle` (root) and `gradle/wrapper/gradle-wrapper.properties`

## Real Gradle structure

```
kairos/                        ← repo root (NOT "app/" — the termux-app fork lives directly here)
├── build.gradle                ← root build script
├── settings.gradle             ← includes modules: app, terminal-emulator, terminal-view, termux-shared, llama-engine
├── gradle.properties           ← SDK/NDK versions
├── app/build.gradle             ← app module — native UI, no React Native
├── terminal-emulator/build.gradle
├── terminal-view/build.gradle
├── termux-shared/build.gradle
└── llama-engine/build.gradle    ← llama.cpp NDK module (see llama-cpp-local-engine.md)
```

## Troubleshooting

### NDK error
The main NDK (r29.0.14206865) is installed by the CI workflow itself; `llama-engine` additionally
needs the separate NDK 27.2.12479018 (see "Local build" above, which installs it automatically via
`sdkmanager`). If a local build fails on the NDK, confirm which of the two is needed depending on
which module is being compiled.

### Rootfs 404 on the lightweight build
If the rootfs's source repo is private, the lightweight build (`build-app.yml`) will get a 404
when trying to download the rootfs at runtime unless the end user has access/a token. See
`docs/bootstrap/rootfs-embebido.md`.

### llama-engine won't build / build gets heavier
`llama-engine` builds llama.cpp+Vulkan from scratch on EVERY build (including `build-app.yml`,
the "normal" one) — it's a considerably heavier build than without this module. See
`docs/ia-local/llama-cpp-local-engine.md`.
