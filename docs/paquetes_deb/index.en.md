# Kairos module `.deb` packages — index

> Documentation of the module `.deb` system: what it looks like, what it requires and uses, how packages are
> created and how they are installed. It replaces the v1 `moduledeb` design as the current reference.

## Core idea

A Kairos `.deb` is **not an ordinary apt package**: it is an already-installed module packaged together with
the information Kairos needs to decide whether to install it. That information is **two files inside the
package itself**, and they are the **first thing read, before even considering installation**:

1. `DEBIAN/manifest.json` — what the package is, which paths it touches and what it needs to work (`pkg`
   packages, add-ons, other modules).
2. `DEBIAN/SHA256SUMS.txt` — the hash of `manifest.json` and of **every** file it ships.

Without those two files the package **is not installed**, regardless of where it comes from.

## One system for three origins

| Origin | Who triggers it | Code |
|---|---|---|
| **GitHub** (Release of `Honkonx/Kairos-Lab`) | install sheet → "GitHub" origin | `ModuleDebInstaller.installFromGithub` |
| **Local package** (file chosen by the user) | Store → "Local package" | `LocalPluginManager.installDeb` → `ModuleDebInstaller.installFromDeb` |
| **Created from the module in the APK** | Repo → "Create .deb from a module" | `moduledeb pack <id>` (generates the same format) |

All three end up in `ModuleDebInstaller.installFromDeb`, which always runs the same flow
([`flujo-instalacion.en.md`](flujo-instalacion.en.md)). The only difference is **trust**: GitHub provides a separately published hash
([`catalogo-github.en.md`](catalogo-github.en.md)); a local file does not, so it asks for confirmation showing a summary.

## Documents

- [`formato-v2.en.md`](formato-v2.en.md) — what `manifest.json` and `SHA256SUMS.txt` contain (fields, real example).
- [`flujo-instalacion.en.md`](flujo-instalacion.en.md) — steps and checks, from reading the JSON to applying.
- [`crear-paquetes.en.md`](crear-paquetes.en.md) — `moduledeb pack`, `reseal`, `inspect`; how `--describe-files` is written.
- [`catalogo-github.en.md`](catalogo-github.en.md) — the Release's `catalog.json` and `SHA256SUMS.txt`; how they are published.

## Code files

`modulos/moduledeb.sh` (+ copy in `app/src/main/assets/scripts/`), `util/ModuleDebInstaller.kt`,
`util/LocalPluginManager.kt`, `ui/PluginsFragment.kt` (confirmation dialog), `ui/BottomSheetInstalacion.kt`,
`ui/RepoFragment.kt`, `tools/moduledeb-release.sh`. Tests: `ModuleDebInstallerTest`.

## Status

Implemented and tested in WSL with a real v1-format `.deb` of the `claude` module (v1 rejection, `reseal`,
`inspect`, rejection of an extra file, of an undeclared `.bashrc`, of a tampered manifest and of a path outside
`$HOME`/`$PREFIX`).
**Not tested on the device** (the full APK → GitHub/local → install flow).
`.deb` packages in v1 format (without `manifest.json` or `SHA256SUMS.txt`) are not installed: they must be
regenerated with `moduledeb pack` or converted with `moduledeb reseal` (see [`catalogo-github.en.md`](catalogo-github.en.md)).
