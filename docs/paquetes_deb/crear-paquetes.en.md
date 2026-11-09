# Creating module `.deb` packages

## From the APK

**Repo** module screen → "Create .deb from a module" (`RepoFragment.runModuleDebPack`) → runs
`moduledeb pack <id>`. The `.deb` ends up in `/storage/emulated/0/Download/kairos/` (public folder, so it can be
shared) named `<package_name>[-variant]_<version>_aarch64.deb`. The module's **already-done** installation on that
device is packaged; nothing is reinstalled.

## `moduledeb.sh`

| Subcommand | What it does |
|---|---|
| `pack <id>` | Resolves the manifest (`<id>.sh --describe-files`, falling back to `modulos/manifests/<id>.json`), copies the real files in bulk (`tar \| tar`), writes `control`, `manifest.json` (v2), `postinst` and `SHA256SUMS.txt`, and builds the `.deb` |
| `inspect <deb>` | Reads only `manifest.json` + `SHA256SUMS.txt` and returns the verdict as JSON (rc 2 = rejected) |
| `reseal <deb> [output]` | Converts a v1 `.deb` to v2 format: rewrites the manifest and recomputes the hashes **of the current content**. For old own packages; it does not validate authenticity |
| `extract <deb>` / `verify <dir>` / `apply <dir>` | Installation phases (see [`flujo-instalacion.en.md`](flujo-instalacion.en.md)) |
| `install <deb>` | Chains extract → verify → apply from the terminal |

`moduledeb` never runs the module's full installer (`claude.sh`, `opencode.sh`...): the `postinst` only runs the
checks and the lightweight patch from the manifest.

## How a module declares what gets packaged (`--describe-files`)

Each module prints its manifest with `bash <id>.sh --describe-files`: `id`, `package_name`, `variant`,
`version_registry_key`, `files[]`, `file_globs[]`, `dependencies[]`, `verify_cmd`, `patch_cmd`, `not_covered[]`.
`moduledeb pack` adds `kairos_package`, `schema`, `pack_date`, `checksums_file` and, on each dependency, `kind` and
`packages[]`. For a requirement to resolve by itself (without free-form commands), its `install_hint` must be
exactly `pkg install -y <packages>` (they can be chained with `&&`).

The manifests of existing modules were not modified for format v2: they remain as before and the v2 format is
derived when packing.

## Quick tests in WSL

WSL Debian has `dpkg-deb`, `jq` and `sha256sum`, enough for `inspect` and `reseal` (not for `apply`, which writes
to `/data/data/com.termux`). Example with a v1 `.deb` of the `claude` module:

```
moduledeb inspect kairos-module-claude_2.1.251_aarch64.deb   # v1 → rc 2, "falta SHA256SUMS.txt"
moduledeb reseal  kairos-module-claude_2.1.251_aarch64.deb /tmp/claude_v2.deb
moduledeb inspect /tmp/claude_v2.deb                          # rc 0, requirements: glibc-repo, glibc-runner, patchelf-glibc
```
