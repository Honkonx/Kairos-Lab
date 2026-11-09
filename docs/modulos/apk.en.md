# APK Compiler (`apk`) — Kairos Module

**Kairos Module** — managed through the Kairos UI (Modules tab / Store). Installation is handled by the app via `ProcessBuilder` → `modulos/apk.sh`; the dedicated screen (`ApkFragment.kt`) exposes the real build pipeline (aapt2 → javac + kotlinc → d8 → zipalign → apksigner) with step-by-step progress, instead of a raw terminal text box.

**Scope:** it compiles **Java + Kotlin** (pure JVM, no Gradle). It does NOT compile React Native/JS (it needs real Gradle with autolinking) or native C/C++ via CMake/NDK (the real Android NDK, not Termux's native toolchain, is a separate project).

---

**Project:** Kairos
**Script:** `modulos/apk.sh` (v2.1.0, 818 lines) — mirror at `app/src/main/assets/scripts/apk.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ApkFragment.kt` (905 lines; `ModuleDetailNavigator.kt:47`) — dedicated, because `apk` has no `terminalCommand` compatible with the generic flow
**`id` in `modules.json`:** `apk` — `hasSwitch: false` (not a server with a persistent process; it is a command-line tool invoked per build)
**Attribution:** adapted from i-Haklab / `termux-apk-make`. The `termux-apk-make` repo ships no real script — only a `README.md` describing the pipeline and delegating installation to i-Haklab's external apt repo; `apk.sh` is a reimplementation of our own that follows the same pipeline order, with two decisions of its own: the `Sable/android-platforms` mirror for `android.jar` (API 30), and a keystore generated locally with `keytool` on the first build (instead of distributed as a public file).

---

## 1. Overview

A module that installs a complete APK compiler **on the device**, without depending on Android Studio or a PC — meant to compile simple Android projects (manifest + `.java`/`.kt` + optional resources) directly from Termux/Kairos. Entry point: the `compil-apk-termux` command (or the "🔨 Compile APK" button in `ApkFragment`). Besides building, it offers APK inspection/merge/decoding and a "rootless instrumentation" system (decompile with apktool, inject calls to a remote logger into a Smali method, recompile and sign).

The script is not a server: it installs build tools (aapt2, JDK, d8, zipalign, apksigner) and leaves a wrapper (`compil-apk-termux`) in `$PREFIX/bin`. Each compilation is a one-off invocation of the wrapper, not a background process — hence `hasSwitch: false`.

**Classification: FUNCTIONAL MODULE.** A dedicated screen with project selection, compilation with progress, a list of generated APKs (share/install), info/decode tools over any APK, an instrumentation flow (decode, find class, choose method, inject), log-server control (start/stop/view) and maintenance. ~15 fixed buttons + dynamic rows.

## 2. Permissions

- **Android**: none specific for installing/compiling. To **share/install** the resulting APK it uses a dedicated `FileProvider` (`${TERMUX_PACKAGE_NAME}.apkbuilder`, declared in `AndroidManifest.xml` with `android:grantUriPermissions="true"` and `res/xml/apk_builder_paths.xml` as scope) — it does **not** reuse the existing `.files` provider (`TermuxOpenReceiver.ContentProvider`), because that one requires the `allow-external-apps` policy intended for third-party plugins calling the Termux API, not for an artifact the app itself generates and wants to hand to the system package installer.
- **Termux internals**: `aapt2`, `openjdk-17`, `d8`, `zipalign`, `apksigner` are installed through `pkg` (native aarch64 Termux packages) — no proot, no network besides the initial download of packages and `android.jar`.

## 3. Installation logic (`modulos/apk.sh`)

A script with the standard `lib.sh` contract (SILENT/CHECKPOINT/REGISTRY) and the standard flags:

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts, same `[OK]`/`[ERROR]` output |
| `--force` | Reinstalls even if already installed (deletes the checkpoint) |
| `--describe` | JSON manifest: `{"id":"apk","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |

"Already installed" requires the wrapper + `aapt2`, `d8`, `zipalign`, `apksigner` on the PATH (`apk.sh:185-198`).

**STEP 1 — Build tools**: `pkg install aapt2 openjdk-17 d8 aapt apksigner unzip zip wget` (`zipalign` ships in the `aapt` package, there is no package of its own). It captures the full stdout+stderr of `pkg install` and only dumps it to the log if something fails. It verifies each binary with `command -v` before marking the checkpoint.

**STEP 2 — `android.jar`**: downloads `android-30/android.jar` from `raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar` (a mirror also used by BuildAPKs) to `$PREFIX/share/android-platform/android.jar` (30 s timeout; **no sha256 or commit version**, `master` branch) — saved once, reused by all future projects. It verifies that the downloaded file is not empty.

**STEP 3 — `compil-apk-termux` wrapper + keystore**: writes the complete wrapper script (see §4) to `$PREFIX/bin/compil-apk-termux` via heredoc, **only if it does not exist or with `--force`**, and prepares the keystore folder (`$HOME/.local/share/kairos-apk/`) — the keystore itself (`key.keystore`) is only generated on the first real build (`keytool -genkey`), not at install time.

**FINISHING**: writes to the registry (`apk.installed=true`, `apk.command=compil-apk-termux`, `apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner`, `apk.android_jar=API30`, `apk.install_date`) and fires `notify_event apk install_done`.

`apktool` and `kotlinc` are not hard dependencies: apktool is installed on first use of `instrument-decode`; Kotlin requires the separate `kotlin` module.

## 4. The `compil-apk-termux` wrapper (subcommands)

`apk.sh` compiles nothing by itself — it generates a standalone wrapper of ~250 lines that does. Subcommands:

| Subcommand | Usage | What it does |
|---|---|---|
| `build <project>` | `compil-apk-termux build ~/proyectos/mi_app` | Full 7-step pipeline (8 if the project has Kotlin, see §5). It is the **default** if invoked without a recognized subcommand (`compil-apk-termux <project>` == `build <project>`). |
| `info <apk>` | `compil-apk-termux info final.apk` | `aapt2 dump badging` (package/label/sdkVersion/permissions) + `apksigner verify --print-certs` + a content summary (`unzip -l`). |
| `merge <base.apk> <parts...>` | `compil-apk-termux merge base.apk config.arm64-v8a.apk` | Functional equivalent of APKEditor's merge without Java/Gradle: unpacks base + splits into a temp dir, repackages, `zipalign`, re-signs with the local keystore. Splits carry a duplicate `AndroidManifest.xml` — the base's is kept, with no semantic de-dupe. |
| `decode <apk> <dir>` | `compil-apk-termux decode app.apk out/` | Extracts the APK + leaves a readable `AndroidManifest.xml` via `aapt2 dump xmltree` and lists the `.dex` files. It does **not** do smali/decompile — that requires `pkg install apktool` separately. |

Optional environment variables overriding the wrapper's defaults: `ANDROID_JAR`, `APK_ALIAS` (default `kairos`), `APK_PASS` (default `password`), `APK_KEYSTORE`.

## 5. The `build` pipeline (7 steps, 8 with Kotlin)

All-or-nothing — the wrapper runs with `set -e` and stops at the first error in any step; there is no way to "just compile without signing" as a separately invocable intermediate step. The `kotlinc` step is **conditional**: it only runs if `find "$PROJECT/src" -name '*.kt'` finds something — a pure Java project sees exactly 7 steps, with no overhead:

| Step (pure Java / with Kotlin) | Tool | What it produces |
|---|---|---|
| 1/7 · 1/8 | `aapt2 compile --dir res/` | `resources.zip` (only if the project has a `res/` folder) |
| 2/7 · 2/8 | `aapt2 link -I android.jar --manifest ...` | `base.apk` (compiled resources) + generated `R.java` in `gen/` |
| — · 3/8 (only if `.kt` exists) | `kotlinc -classpath android.jar` | `.class` files from `src/*.kt` → `build/kotlin-classes/` (its own directory, separate from `build/classes/`) |
| 3/7 · 4/8 | `javac -source 11 -target 11 -classpath android.jar[:kotlin-classes]` | `.class` files from `src/*.java` + the generated `R.java` — the classpath includes `kotlin-classes/` when there was a Kotlin step, so Java code can reference Kotlin classes |
| 4/7 · 5/8 | `d8 --release --lib android.jar` | `classes.dex` — collects `.class` files from `build/classes/` **and** `build/kotlin-classes/` |
| 5/7 · 6/8 | `zip` | Packs `classes.dex` + `assets/` (optional) + `lib/` (optional, native `.so`) into `base.apk` |
| 6/7 · 7/8 | `zipalign -p 4` | `aligned.apk` (alignment required by ART on Android 11+) |
| 7/7 · 8/8 | `apksigner sign --v1/v2/v3-signing-enabled true` | `final.apk` — generates the keystore with `keytool` if it does not exist yet (10000 days) |

`d8` is the DEX tool available as a Termux package (R8 is not packaged there); `API30` as the target is reasonable for compiling simple APKs from the device.

**Kotlin is a soft dependency, not a hard one**: if the project has `.kt` files but the `kotlin.sh` module did not install `kotlinc` (it is not on the PATH), `cmd_build()` stops with `[ERROR] Proyecto tiene archivos .kt pero kotlinc no está instalado — instalá el módulo Kotlin primero` **before** touching any file — `apk.sh` does not force the installation of `kotlin.sh` (they are independent modules), it only detects and warns at build time.

**Minimum project structure** (confirmed in `cmd_build()`):
```
project/
├── AndroidManifest.xml   (required)
├── src/                  (.java and/or .kt, may have nested packages; optional if the project is resources only)
├── res/                  (optional)
├── assets/               (optional)
└── lib/                  (optional — native .so)
```

**Real output**: `<project>/build/apk/final.apk` (the variable used in `cmd_build()` is `$BUILD_DIR/apk/final.apk` with `BUILD_DIR="$PROJECT/build"`; the script header comment says `build/final.apk`, which is out of date). `ApkFragment.scanGeneratedApks()` scans the real path.

**Compiler output parser**: `_parse_compiler_output()` (a bash port of a compiler-output parser from a reference IDE project) recognizes the standard GNU format of `javac`/`kotlinc`/`aapt2` (`file:line[:col]: error|warning: message`) and shows `[ERROR]`/`[WARN]` with a final count instead of a raw dump. `ApkFragment` translates the `=== [n/N] ... ===` lines emitted by the wrapper into step-by-step `ProgressDialogController` updates (`N` is 7 or 8 depending on Kotlin).

## 6. State detection

- **Installation**: registry (`apk.installed=true`) + `compil-apk-termux` and `aapt2` exist on the PATH (`command -v`, without `--force`).
- **`hasSwitch: false`**: there is no "running"/"stopped" concept — the module does not appear with a toggle in the Store, only with an install/open button.
- **`ApkFragment.isModuleInstalled()`**: if not installed, it shows the standard "not installed" screen with a button calling `installModuleInBackground`.

## 7. App screen (`ApkFragment.kt`)

`getModuleId()` → `"apk"`, `getModuleName()` → `"Compilador de APK"`.

| Control | What it does | Why |
|---|---|---|
| "PROJECT TO COMPILE" card — "Selected" | Read-only, shows the chosen project (`— none —` by default) | — |
| "📂 Choose project to compile" | Lists `~/proyectos` via `ProjectsManager.projectsList()`; picking one stores it as `selectedProjectPath` | Reuses the same source as `ProjectActions.pickProjectToOpen` instead of duplicating the listing logic |
| "🗂 Manage projects (symlink / copy)" | `showProjectsMenu()` — import/create/delete projects | — |
| "COMPILE" card (pipeline `aapt2 → javac + kotlinc → d8 → zipalign → apksigner`, "Languages" row: Java and Kotlin — NOT React Native/JS or native C/C++) + "🔨 Compile APK" | Validates that a project is selected and still exists; opens `ProgressDialogController` ("Compiling APK"); runs `compil-apk-termux build <path>` in a `Thread` via `ProcessBuilder` (bash + `applyTermuxEnv()`), reading line by line with `redirectErrorStream(true)` (allows background + notification, `ApkFragment.kt:214-280`); translates each `=== ... ===` line (new step) or `  [ERROR]` into a live progress update (`isAdded` guard before touching the UI); on finish, `progress.success(...)` or `progress.failure(...)` with the last 4000 chars of the log, and refreshes the APK list | Without this parsing the user would only see a generic spinner during a 7-8 step pipeline that can take minutes |
| "GENERATED APKS" card — APK row (tap) | `scanGeneratedApks()` walks `~/proyectos/*/build/apk/final.apk` by descending date (name + human-readable size). Tapping opens `shareOrInstallApk()`: **📤 Share** (`Intent.ACTION_SEND` with the `content://` of the `apkbuilder` FileProvider, mime `application/vnd.android.package-archive`) or **📲 Install** (`Intent.ACTION_VIEW` with the same URI + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`, delegating to Android's package installer) | The generic `.files` provider is blocked by its `allow-external-apps` policy |
| Choose APK (SAF) + Info + Decode | The wrapper's `info`/`decode` over any APK (with an offer to open the folder) | — |
| Instrumentation | decode, choose Smali class, choose method and action (`enter\|exit\|both\|d`), inject and offer to install | Reverse-engineering tool; only under explicit user action |
| Log | Start / stop / view the log server (`log_server.py`, `127.0.0.1:9999`, tmux session with `logstart/logstop/logstatus/logtail`) | — |
| MAINTENANCE — "🔄 Update tools" / Diagnose / "🗑 Uninstall" | `updateModuleService`; uninstall asks for confirmation and removes the build tools (aapt2/d8/zipalign/apksigner/android.jar) + wrapper — it does **not** delete `~/proyectos` or already compiled APKs (stated in the dialog) | — |

## 8. Registry (`~/.android_server_registry`)

```
apk.installed=true
apk.command=compil-apk-termux
apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner
apk.android_jar=API30
apk.install_date=<YYYY-MM-DD>
```

## 9. Files and paths

`$PREFIX/bin/compil-apk-termux`, `$PREFIX/share/android-platform/android.jar`, `~/.local/share/kairos-apk/{key.keystore,instrument/…,logger/}`, `~/.kairos_apk_logger/` (`RemoteLogger.java`, `native_logger.c`, `log_server.py`, `smali_hook.py`, copied by `KairosBootstrap`), `<project>/build/{gen,classes,kotlin-classes,dex,apk}/`, registry `apk.*`.

## 10. Security

- The default keystore uses alias `kairos` and password `password` (`apk.sh:367-368`, overridable via `APK_ALIAS/APK_PASS`); it is generated automatically on the first signing. The resulting APKs share a known weak debug signature; it is not a production secret, but must not be used for publishing.
- The password travels as an argument (`pass:$KEY_PASS`) visible in `ps` while signing.
- `android.jar` is downloaded from a third-party repo without an integrity check (supply chain) and used as the compile classpath.
- The project/APK is passed to `bash -c` escaped with `shellQuote`.
- `instrument-*` modifies third-party APKs and adds a logger that sends data to `127.0.0.1:9999`: a reverse-engineering tool that runs only under explicit user action.

## 11. Notes and known limitations

- **`hasSwitch: false` but there is a process during the build**: each compilation is a one-off execution that ends by itself; there is no `start.sh`/`stop.sh` as in other modules.
- **Dedicated FileProvider, not the generic `.files`**: any future change to `TermuxOpenReceiver.ContentProvider` must not be assumed compatible with sharing/installing APKs generated by this module.
- The wrapper's `merge`/`info`/`decode` are also available by hand from the terminal (`compil-apk-termux info/merge/decode ...`).
- `decode` does not decompile smali/Java — it only leaves the manifest readable and extracts the raw `.dex` files; real decompilation requires `apktool`.
- The embedded wrapper is only rewritten with `--force`: updating `apk.sh` does not update `compil-apk-termux` if it already exists, so wrapper fixes do not reach already-installed devices without a forced reinstall.
- `zip … 2>/dev/null || true` (packaging step) hides failures when adding `classes.dex`, `assets` or `lib`; `zipalign`/`apksigner` then sign an APK without code.
- `javac -source 11 -target 11` fixed; `android.jar` API 30 fixed (no platform selection). Out of scope: React Native/NDK/CMake.
- The `d8` step passes `$(find … -name '*.class')` unquoted (breaks with paths containing spaces).
- Inference: `openjdk-17` and `apktool` (which requires `openjdk-21` per its own message) may clash in version on the same device.
- **Sync**: `modulos/apk.sh` ↔ `app/src/main/assets/scripts/apk.sh` must be kept identical.

## 12. Date added

`modulos/apk.sh`: **2026-08-13**.
