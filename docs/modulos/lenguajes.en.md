# Languages — `languages` module

## What it is

`languages` is a **container** module — it has no install script of its own
(`modules.json` field `"script": ""`). Its Fragment (`LanguagesFragment.kt`) shows one
row per language with a real `SwitchCompat`:

- **ON** → `ModuleController.installModule(id, null, force=false, ...)` — the same real
  mechanism the rest of the app uses (runs `bash <id>.sh --silent`, log at
  `~/kairos_logs/install_<id>.log`).
- **OFF** → `ModuleController.deepUninstallModule(id, ...)` — REAL package uninstall
  (`pkg uninstall -y <package>`), with a prior confirmation (a simple AlertDialog, no
  "deep uninstall" checkbox — here it's always deep, it's the only thing that makes
  sense for a native pkg package).

Each row shows the real state: "Not installed" / "Installed — `<real version>`" (read from
the registry `<id>.version`, written by `install_single_pkg()` in `modulos/lib.sh` on
install; if the registry doesn't have the value — binary installed by hand in the
terminal, bypassing the app — `<command> --version` is run live as a fallback).

## The languages

| Language | id | Real script | pkg package | Version command |
|---|---|---|---|---|
| Node.js LTS | `nodejs` | `modulos/nodejs.sh` | `nodejs-lts` (+ Corepack) | `node --version` |
| Perl | `perl` | `modulos/perl.sh` | `perl` | `perl --version` |
| PHP | `php` | `modulos/php.sh` | `php` | `php --version` |
| Rust | `rust` | `modulos/rust.sh` | `rust` (rustc + cargo) | `rustc --version` |
| C/C++ (Clang) | `clang` | `modulos/clang.sh` | `clang` | `clang --version` |
| Go | `golang` | `modulos/golang.sh` | `golang` | `go version` |
| Ruby | `ruby` | `modulos/ruby.sh` | `ruby` | `ruby --version` |
| Kotlin | `kotlin` | `modulos/kotlin.sh` | `kotlin` (apt dependency `openjdk-17`) | `kotlinc -version` |

The scripts (`modulos/<id>.sh`) are independent of each other — they all use
`install_single_pkg()` from `modulos/lib.sh`. What unifies their presentation is this
Fragment: they used to be separate cards in the Store, now they're rows inside
"Languages".

## Python — not duplicated, on purpose

Python is already a top-level module (`PythonFragment.kt`, with per-project virtual
environment management — see `docs/modulos/python.md`). It wasn't added as a switch here
to avoid having 2 install paths that could get out of sync; instead, "Languages" shows an
informational row "🐍 Python — own management" that navigates directly to
`PythonFragment` with a tap, so the catalog feels centralized without duplicating real
logic.

## Where each one runs

All of them are **native to Termux** (bionic, `pkg install`, no proot) — they work
directly in any Kairos terminal session. **All of them are also available, separately,
inside a full Linux distro** (Debian/Ubuntu via the Environment module, proot) with its
own `apt install <package>` — it's a completely separate glibc filesystem from the
native bionic one. That second path **is not managed by this module**; if a language is
needed inside a proot distro, it's installed by hand there, just like any other package
of that distro.

## `internal` field in modules.json

The corresponding objects in `app/src/main/assets/modules.json` carry `"internal": true`
— a field in `ModuleInfo.kt`/`ModuleCatalog.kt` that hides the module from the Store
(`PluginsFragment`) and from the Modules screen (`ModulesFragment`) as a standalone
entry, **without** deleting or modifying the JSON object itself. Different from the
runtime `hidden` field (`ProjectsManager.hiddenModuleIds()`, the user hiding an ALREADY
installed module from the home screen) — `internal` is a catalog decision, not a user
one.

## Real uninstall (`ModuleController.deepUninstallPlan`)

```kotlin
"nodejs" -> DeepUninstallPlan("pkg uninstall -y nodejs-lts", ...)  // real package is nodejs-lts, not nodejs
"perl"   -> DeepUninstallPlan("pkg uninstall -y perl", ...)
"php"    -> DeepUninstallPlan("pkg uninstall -y php", ...)
"rust"   -> DeepUninstallPlan("pkg uninstall -y rust", ...)
"clang"  -> DeepUninstallPlan("pkg uninstall -y clang", ...)
"golang" -> DeepUninstallPlan("pkg uninstall -y golang", ...)
```

## Installed detection (`ModuleInstalled.BINARY_FALLBACK`)

`nodejs→node`, `perl→perl`, `php→php`, `rust→rustc`, `clang→clang`, `golang→go` — the same
binary they already had as `terminalCommand` in the catalog before becoming `internal`.
This makes the switch reflect a language installed BY HAND in the terminal (bypassing the
app), not just what the app itself installed.

## Shared architecture

- `app/src/main/java/com/termux/app/ui/ConsolidatedModuleFragment.kt` — shared base with
  `PackagesFragment` (row "name + state/version + switch").
- `app/src/main/java/com/termux/app/ui/LanguagesFragment.kt`.
- `app/src/main/java/com/termux/app/ui/ModuleDetailNavigator.kt` — `"languages" ->
  LanguagesFragment()`.
- `app/src/main/java/com/termux/app/model/ModuleInfo.kt` — `internal` field.
- `app/src/main/java/com/termux/app/data/ModuleCatalog.kt` — parsing/serialization of
  `internal`.
- `app/src/main/java/com/termux/app/data/ModuleInstalled.kt` — sentinel
  `"languages"→"bash"` (the container module always shows as "installed", never triggers
  the install sheet) + `BINARY_FALLBACK` for the languages.
- `app/src/main/java/com/termux/app/ModuleController.kt` — `deepUninstallPlan` per
  language.
- `app/src/main/java/com/termux/app/ui/ModulesFragment.kt` / `PluginsFragment.kt` —
  filter `internal` out of the displayed catalog.
- `app/src/main/assets/modules.json` — `internal: true` on each language + the
  `"languages"` entry.

See also `docs/modulos/paquetes.en.md` (same pattern, for npm tools).

## Screen controls (`LanguagesFragment.kt`)

One `switchRow()` per language (ON installs, OFF uninstalls) + optional `RunAction`
buttons per row (a single one-shot pass, no dialog beforehand unless it mutates something
— see `RunAction.requiresConfirmation`/`requiresTextInput`).

| Language | Switch (version) | RunAction(s) | Why (if applicable) |
|---|---|---|---|
| Node.js LTS | `node --version` | "npm -g packages" (`npm list -g --depth=0`) | — |
| Perl | `perl --version` | "Config" (`perl -V`) | Dependency of PSQL Format (Packages module) |
| PHP | `php --version` | "Loaded modules" (`php -m`) | — |
| Rust | `rustc --version` | "cargo install binaries" (`cargo install --list`) | — |
| C/C++ (Clang) | `clang --version` | None | No package manager of its own, nor a one-shot flag with real value |
| Go | `go version` | "Environment" (`go env`) | — |
| Ruby | `ruby --version` | — | — |
| Kotlin | `kotlinc -version` | — | Real apt package declares `openjdk-17` as a dependency, no need to install it separately |

**"ALREADY INSTALLED SEPARATELY" card**: shortcut row to `PythonFragment` (own venv/pip
management) — the installer isn't duplicated here to avoid having 2 paths that could get
out of sync, but it stays visible so the catalog feels centralized.

**Non-obvious detail**: these languages run NATIVE in Termux (bionic, no proot) — each
one also works separately inside a full Linux distro (Debian/Ubuntu via Environment,
proot) with its own `apt install`, but that second path is NOT managed by this module.
