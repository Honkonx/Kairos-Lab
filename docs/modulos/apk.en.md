# APK Compiler (`apk`)

**Kairos module** — managed through the Kairos UI (Modules / Store tab). Installation handled
by the app via `ProcessBuilder` → `modulos/apk.sh`; the dedicated screen (`ApkFragment.kt`)
exposes the real compilation pipeline (aapt2 → javac + kotlinc → d8 → zipalign → apksigner)
with step-by-step progress, instead of a raw terminal text box.

**Real scope:** compiles **Java + Kotlin** (pure JVM, no Gradle). It does NOT compile React
Native/JS (needs a real Gradle with autolinking) nor native C/C++ via CMake/NDK (the real
Android NDK, not Termux's native toolchain, is a separate project).

---

**Script:** `modulos/apk.sh` — mirrored at `app/src/main/assets/scripts/apk.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ApkFragment.kt`
**`id` in `modules.json`:** `apk` — `hasSwitch: false` (not a server with a persistent process;
it's a command-line tool invoked per build)

---

## 1. Overview

Module that installs a full APK compiler **on the device itself**, without depending on
Android Studio or a PC — intended for compiling simple Android projects (manifest +
`.java`/`.kt` + optional resources) directly from Termux/Kairos. User entry point: the
`compil-apk-termux` command (or the "🔨 Compile APK" button in `ApkFragment`).

The script isn't a server: it installs build tools (aapt2, JDK, d8, zipalign, apksigner) and
leaves a wrapper (`compil-apk-termux`) in `$PREFIX/bin`. Each build is a one-off invocation of
the wrapper, not a background process — hence `hasSwitch: false` in `modules.json`.

The keystore: Kairos generates its own locally with `keytool` on the first build (default
password `"password"`, generated on the device itself, not distributed as a public file).

## 2. Permissions

- **Android**: none specific for installing/compiling. To **share/install** the resulting APK
  it uses a dedicated `FileProvider` (`${TERMUX_PACKAGE_NAME}.apkbuilder`, declared in
  `AndroidManifest.xml` with `android:grantUriPermissions="true"` and
  `res/xml/apk_builder_paths.xml` as its scope) — it doesn't reuse the existing `.files`
  provider, because that one requires the `allow-external-apps` policy meant for third-party
  plugins calling the Termux API, not for an artifact the app itself generates and wants to
  hand to the system's package installer.
- **Internal Termux**: `aapt2`, `openjdk-17`, `d8`, `zipalign`, `apksigner` are installed via
  `pkg` (native aarch64 Termux packages) — no proot, no network beyond the initial package
  download and downloading `android.jar`.

## 3. Installation logic (`modulos/apk.sh`)

Script with the standard contract (SILENT/CHECKPOINT/REGISTRY) and the standard flags:

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts, same `[OK]`/`[ERROR]` output |
| `--force` | Reinstalls even if already present (deletes the checkpoint) |
| `--describe` | JSON manifest: `{"id":"apk","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |

**STEP 1 — Build tools**: installs `aapt2 openjdk-17 d8 zipalign apksigner unzip zip wget` via
`pkg install`. Verifies each binary with `command -v` before marking the checkpoint.

**STEP 2 — `android.jar`**: downloads `android-30/android.jar` from the `Sable/android-platforms`
mirror on GitHub to `$PREFIX/share/android-platform/android.jar` — saved once, reused by every
future project. Verifies the downloaded file isn't empty.

**STEP 3 — `compil-apk-termux` wrapper + keystore**: writes the full wrapper script (see §4) to
`$PREFIX/bin/compil-apk-termux` and prepares the keystore folder
(`$HOME/.local/share/kairos-apk/`) — the keystore itself (`key.keystore`, password `"password"`)
is generated only on the first real build (`keytool -genkey`), not at install time.

**FINISHING**: writes to the registry (`apk.installed=true`, `apk.command=compil-apk-termux`,
`apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner`, `apk.android_jar=API30`,
`apk.install_date`).

## 4. The `compil-apk-termux` wrapper (subcommands)

`apk.sh` doesn't compile anything itself — it generates a standalone wrapper of ~250 lines
that does. Subcommands:

| Subcommand | Usage | What it does |
|---|---|---|
| `build <project>` | `compil-apk-termux build ~/proyectos/mi_app` | Full 7-step pipeline (8 if the project has Kotlin, see §5). It's the **default** if invoked without a recognized subcommand (`compil-apk-termux <project>` == `build <project>`) |
| `info <apk>` | `compil-apk-termux info final.apk` | `aapt2 dump badging` (package/label/sdkVersion/permissions) + `apksigner verify --print-certs` + content summary (`unzip -l`) |
| `merge <base.apk> <parts...>` | `compil-apk-termux merge base.apk config.arm64-v8a.apk` | Combines a base APK with splits, no Java/Gradle: unpacks base + splits into a temp dir, repacks, `zipalign`, re-signs with the local keystore. Splits carry a duplicate `AndroidManifest.xml` — the base's is kept, there's no semantic de-dup |
| `decode <apk> <dir>` | `compil-apk-termux decode app.apk output/` | Extracts the APK and leaves a readable `AndroidManifest.xml` via `aapt2 dump xmltree`, and lists the `.dex` files. Doesn't do smali/decompile — that requires installing `apktool` separately (not installed by `apk.sh`) |

Optional environment variables that override the wrapper's defaults: `ANDROID_JAR`,
`APK_ALIAS` (default `kairos`), `APK_PASS` (default `password`), `APK_KEYSTORE`.

## 5. The `build` pipeline (7 steps, 8 with Kotlin)

All-or-nothing — the wrapper runs with `set -e`, stopping at the first error in any step, there
is no way to "just compile without signing" as a separately invocable intermediate step. The
`kotlinc` step is **conditional**: it only runs if `find "$PROJECT/src" -name '*.kt'` finds
something — a pure-Java project still sees exactly 7 steps, with no overhead:

| Step (pure Java / with Kotlin) | Tool | What it produces |
|---|---|---|
| 1/7 · 1/8 | `aapt2 compile --dir res/` | `resources.zip` (only if the project has a `res/` folder) |
| 2/7 · 2/8 | `aapt2 link -I android.jar --manifest ...` | `base.apk` (compiled resources) + `R.java` generated into `gen/` |
| — · 3/8 (only if there's `.kt`) | `kotlinc -classpath android.jar` | `.class` from `src/*.kt` → `build/kotlin-classes/` (its own directory, separate from `build/classes/`) |
| 3/7 · 4/8 | `javac -source 11 -target 11 -classpath android.jar[:kotlin-classes]` | `.class` from `src/*.java` + from the generated `R.java` — the classpath includes `kotlin-classes/` when the Kotlin step ran |
| 4/7 · 5/8 | `d8 --release --lib android.jar` | `classes.dex` — collects `.class` files from `build/classes/` and `build/kotlin-classes/` |
| 5/7 · 6/8 | `zip` | Packs `classes.dex` + `assets/` (optional) + `lib/` (optional, native `.so`) into `base.apk` |
| 6/7 · 7/8 | `zipalign -p 4` | `aligned.apk` (alignment required by ART on Android 11+) |
| 7/7 · 8/8 | `apksigner sign --v1/v2/v3-signing-enabled true` | `final.apk` — generates the keystore with `keytool` if it doesn't exist yet |

**Kotlin is a soft dependency, not a hard one**: if the project has `.kt` files but the Kotlin
module didn't install `kotlinc` (not on the PATH), `cmd_build()` stops with `[ERROR] Project
has .kt files but kotlinc is not installed — install the Kotlin module first` before touching
any file — `apk.sh` doesn't force Kotlin to be installed as a hard dependency, it only detects
and warns at build time.

**Minimum project structure**:
```
project/
├── AndroidManifest.xml   (required)
├── src/                  (.java and/or .kt, can have nested packages; optional if the project is resources-only)
├── res/                  (optional)
├── assets/               (optional)
└── lib/                  (optional — native .so files)
```

**Real output**: `<project>/build/apk/final.apk` (`BUILD_DIR="$PROJECT/build"`);
`ApkFragment.scanGeneratedApks()` scans that path.

**Compiler output parser**: `_parse_compiler_output()` recognizes the standard GNU format used
by `javac`/`kotlinc`/`aapt2` (`file:line[:col]: error|warning: message`) and shows
`[ERROR]`/`[WARN]` with a final count instead of a raw dump. `ApkFragment` translates the
`=== [n/N] ... ===` lines emitted by the wrapper into step-by-step `ProgressDialogController`
updates (`N` is 7 or 8 depending on whether there's Kotlin).

## 6. Status detection

- **Installation**: registry (`apk.installed=true`) — installation is considered done if
  `compil-apk-termux` and `aapt2` also exist on the PATH (`command -v`, without `--force`).
- **`hasSwitch: false`**: there's no "running"/"stopped" concept — the module doesn't show a
  toggle in the Store, only an install/open button.
- **`ApkFragment.isModuleInstalled()`**: if not installed, shows the standard "not installed"
  screen with a button that calls `installModuleInBackground`.

## 7. Real app screen (`ApkFragment.kt`)

`getModuleId()` → `"apk"`, `getModuleName()` → `"APK Compiler"`.

**PROJECT TO COMPILE** — row with the selected project (`— none —` by default). "📂 Choose
project to compile" button lists `~/proyectos` via `ProjectsManager.projectsList()` in a
dialog. "🗂 Manage projects (symlink / copy)" button opens the shared project management menu.

**COMPILE** — shows the pipeline (`aapt2 → javac + kotlinc → d8 → zipalign → apksigner`) and a
"Languages" row (Java and Kotlin — NOT React Native/JS or native C/C++), and the "🔨 Compile
APK" button, which:
1. Validates that a project is selected and its folder still exists.
2. Opens `ProgressDialogController` ("Compiling APK").
3. Runs `compil-apk-termux build <path>` in a `Thread` via `ProcessBuilder`, reading line by
   line with `redirectErrorStream(true)`.
4. Translates each `=== ... ===` line (new step) or `  [ERROR]` line into a live progress
   update.
5. On completion: `progress.success(...)` or `progress.failure(...)` with the last 4000 chars
   of the full log, and refreshes the list of generated APKs.

**GENERATED APKs** — `scanGeneratedApks()` walks `~/proyectos/*/build/apk/final.apk`, sorted by
descending modification date, showing project name + human-readable size. Each row is
clickable → `shareOrInstallApk()`:
- **📤 Share**: `Intent.ACTION_SEND` with the `content://` from the `apkbuilder` FileProvider
  (mime `application/vnd.android.package-archive`).
- **📲 Install**: `Intent.ACTION_VIEW` with the same URI + `FLAG_GRANT_READ_URI_PERMISSION` +
  `FLAG_ACTIVITY_NEW_TASK`, delegates to Android's real package installer.

**MAINTENANCE** — shows the source script (`modulos/apk.sh`), a "🔄 Update tools" button and "🗑
Uninstall" (with prior confirmation, explicitly stating it doesn't delete `~/proyectos` or
already-built APKs, only the build tools and the wrapper).

## 8. Registry

```
apk.installed=true
apk.command=compil-apk-termux
apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner
apk.android_jar=API30
apk.install_date=<YYYY-MM-DD>
```

## 9. Technical notes

- **`hasSwitch: false` but has a process during the build**: unlike server modules, `apk` has
  no start/stop — each compilation is a one-off run that finishes on its own.
- **Dedicated FileProvider**: any future change to the generic `.files` provider shouldn't be
  assumed compatible with sharing/installing APKs generated by this module.
- **`merge`/`info`/`decode` have no dedicated UI**: only `build` is exposed in `ApkFragment` —
  the other 3 wrapper subcommands remain available by hand from the terminal.
- **`decode` doesn't decompile smali/Java** — it only leaves the manifest readable and extracts
  the raw `.dex` files; real decompilation requires `apktool` installed separately.
- **Tool choices**: `d8` is the DEX tool available as a Termux package (R8, the successor with
  shrinking/obfuscation from the modern Android Gradle Plugin, isn't packaged there) — it's the
  best real option available without compiling custom tools. `API30` as a target is reasonable
  for the use case (compiling simple APKs from the device, not an Android Studio replacement).
