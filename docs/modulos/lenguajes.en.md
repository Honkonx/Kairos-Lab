# Languages — module `languages`

## What it is

`languages` is a **container** module — it has no installation script of its own
(`modules.json`: `"script": ""`, `type: Contenedor`, `hasSwitch: false`). It is mapped in
`ModuleDetailNavigator.kt:48` to `LanguagesFragment` (extends `ConsolidatedModuleFragment`), which shows
one row per language with a real `SwitchCompat`:

- **ON** → `ModuleController.installModule(id, null, force=false, ...)` — the same real
  mechanism used by the rest of the app (runs `bash <id>.sh --silent`, log in
  `~/kairos_logs/install_<id>.log`).
- **OFF** → prior confirmation (simple AlertDialog, without a "deep uninstall" checkbox —
  here it is always deep) + `ModuleController.deepUninstallModule(id, ...)`, which runs
  `deepUninstallPlan(id)` (real package uninstall: `pkg uninstall -y <package>`).

Each row shows the real state: "Not installed" / "Installed — `<real version>`" (read from the
registry `<id>.version`, written by `install_single_pkg()` in `modulos/lib.sh` on install; if
the registry has no value —binary installed by hand in the terminal, bypassing the app— the
`<command> --version` is run live as a fallback).

It is a functional UI module (a catalog with real state), but each language is an add-on.
UI options: 8 switches + 5-6 action buttons (see the controls table) + 1 access row to `PythonFragment` = ~15 controls.

## The languages

| Language | id | Real script | pkg package | Version command | Sheet |
|---|---|---|---|---|---|
| Node.js LTS | `nodejs` | `modulos/nodejs.sh` | `nodejs-lts` (+ Corepack) | `node --version` | `nodejs.en.md` |
| Perl | `perl` | `modulos/perl.sh` | `perl` | `perl --version` | — |
| PHP | `php` | `modulos/php.sh` | `php` | `php --version` | — |
| Rust | `rust` | `modulos/rust.sh` | `rust` (rustc + cargo) | `rustc --version` | — |
| C/C++ (Clang) | `clang` | `modulos/clang.sh` | `clang` | `clang --version` | — |
| Go | `golang` | `modulos/golang.sh` | `golang` | `go version` | — |
| Ruby | `ruby` | `modulos/ruby.sh` | `ruby` | `ruby --version` | — |
| Kotlin | `kotlin` | `modulos/kotlin.sh` | `kotlin` (apt dependency `openjdk-17`) | `kotlinc -version` | `kotlin.en.md` |

The scripts (`modulos/<id>.sh`) are independent of each other — they all use `install_single_pkg()`
from `modulos/lib.sh` (except Kotlin, which verifies with `kotlinc -version` because `kotlinc` does not
accept `--version`). What unifies their presentation is this Fragment: they used to be separate
cards in the Store, now they are rows inside "Languages". Python is linked, not duplicated
(`LanguagesFragment.kt:81-104`).

## Python — not duplicated, on purpose

Python is already a top-level module (`PythonFragment.kt`, with per-project virtual environment
management — see `docs/modulos/python.en.md`). It was not added as a switch here to avoid having 2
installation paths that could get out of sync; instead, "Languages" shows an informational row
"🐍 Python — own management" that navigates straight to `PythonFragment` with one tap,
so the catalog feels centralized without duplicating real logic.

## Where each one runs

All are **native to Termux** (bionic, `pkg install`, no proot) — they work directly in
any Kairos terminal session. **All of them are also available, separately, inside a full
Linux distro** (Debian/Ubuntu via the Entorno module, proot) with their own
`apt install <package>` — a glibc filesystem entirely separate from native bionic. That second path
**is not managed by this module**; if a language is needed inside a proot distro, it is installed
by hand there, like any other package of that distro.

## `internal` field in modules.json

The corresponding objects in `app/src/main/assets/modules.json` carry `"internal": true` —
a field of `ModuleInfo.kt`/`ModuleCatalog.kt` that hides the module from the Store
(`PluginsFragment`) and the Modules screen (`ModulesFragment`) as its own entry, **without**
deleting or modifying the JSON object itself. Different from the runtime `hidden` field
(`ProjectsManager.hiddenModuleIds()`, the user hiding an ALREADY installed module from home)
— `internal` is a catalog decision, not a user one.

## Real uninstall (`ModuleController.deepUninstallPlan`)

```kotlin
"nodejs" -> DeepUninstallPlan("pkg uninstall -y nodejs-lts", ...)  // real package is nodejs-lts, not nodejs
"perl"   -> DeepUninstallPlan("pkg uninstall -y perl", ...)
"php"    -> DeepUninstallPlan("pkg uninstall -y php", ...)
"rust"   -> DeepUninstallPlan("pkg uninstall -y rust", ...)
"clang"  -> DeepUninstallPlan("pkg uninstall -y clang", ...)
"golang" -> DeepUninstallPlan("pkg uninstall -y golang", ...)
```

(`ModuleController.kt:959-974`.) `ruby` and `kotlin` have **no** entry in `deepUninstallPlan`;
with `plan == null`, `deepUninstallModule` only deletes state/registry and returns "real uninstall
not supported" (`ModuleController.kt:1067-1068`) — the package stays installed in Termux.

## Installed detection (`ModuleInstalled.BINARY_FALLBACK`)

`nodejs→node`, `perl→perl`, `php→php`, `rust→rustc`, `clang→clang`, `golang→go` — the same binary
they already had as `terminalCommand` in the catalog before becoming `internal`. This makes
the switch reflect a language installed BY HAND in the terminal (bypassing the app), not only what
the app itself installed.

## Shared architecture

- `app/src/main/java/com/termux/app/ui/ConsolidatedModuleFragment.kt` — base shared with
  `PackagesFragment` ("name + state/version + switch" row).
- `app/src/main/java/com/termux/app/ui/LanguagesFragment.kt`.
- `app/src/main/java/com/termux/app/ui/ModuleDetailNavigator.kt` — `"languages" ->
  LanguagesFragment()`.
- `app/src/main/java/com/termux/app/model/ModuleInfo.kt` — `internal` field.
- `app/src/main/java/com/termux/app/data/ModuleCatalog.kt` — parsing/serialization of `internal`.
- `app/src/main/java/com/termux/app/data/ModuleInstalled.kt` — sentinel `"languages"→"bash"`
  (the container module always appears "installed", never triggers the install sheet) +
  the languages' `BINARY_FALLBACK`.
- `app/src/main/java/com/termux/app/ModuleController.kt` — `deepUninstallPlan` per language.
- `app/src/main/java/com/termux/app/ui/ModulesFragment.kt` / `PluginsFragment.kt` — filter
  `internal` out of the displayed catalog.
- `app/src/main/assets/modules.json` — `internal: true` on each language + the `"languages"`
  entry.

See also `docs/modulos/paquetes.en.md` (same pattern, for npm tools).

## Screen controls (`LanguagesFragment.kt`)

One `switchRow()` per language (ON installs, OFF uninstalls) + optional `RunAction` buttons per
row (single pass, no prior dialog unless they mutate something — see `RunAction.
requiresConfirmation`/`requiresTextInput`).

| Language | Switch (version) | RunAction(s) | Why (if applicable) |
|---|---|---|---|
| Node.js LTS | `node --version` | "npm -g packages" (`npm list -g --depth=0`, 20 s) | — |
| Perl | `perl --version` | "Config" (`perl -V`) | Dependency of PSQL Format (Packages module) |
| PHP | `php --version` | "Loaded modules" (`php -m`) | — |
| Rust | `rustc --version` | "cargo install binaries" (`cargo install --list`) | — |
| C/C++ (Clang) | `clang --version` | None | No package manager of its own nor a single-pass flag with real value |
| Go | `go version` | "Environment" (`go env`) | — |
| Ruby | `ruby --version` | — | — |
| Kotlin | `kotlinc -version` | — | The real apt package declares `openjdk-17` as a dependency, no need to install it separately |

**"ALREADY INSTALLED SEPARATELY" card**: a shortcut row to `PythonFragment` (its own venv/pip
management) — the installer is not duplicated here to avoid 2 paths that could get out of sync,
but it stays visible so the catalog feels centralized.

**Non-obvious detail**: these languages run NATIVELY in Termux (bionic, no proot) — each one
also works separately inside a full Linux distro (Debian/Ubuntu via Entorno,
proot) with its own `apt install`, but that second path is NOT managed by this module.

## Known limitations

- `ruby` and `kotlin` have no real uninstall (see above).
- modules.json's `terminalCommand` is not used on the screen (no "Open in terminal").
- No language warns about cross-dependencies on uninstall (node, perl, clang and rust are
  used by other modules).
- A comment in `ModuleInstalled.kt:161` about kotlin/`install_single_pkg` is now obsolete.

`LanguagesFragment.kt` added on 2026-08-18.
