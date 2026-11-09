# REPO.md — Local apt repo + `.deb` packaging (`repo.sh` + `moduledeb.sh`)

## What they are (2 sibling mechanisms, do not confuse them)

Kairos has **two** `.deb` packaging scripts, with different purposes:

| Script | Packages | Typical consumer | UI |
|---|---|---|---|
| `modulos/repo.sh` | **Real apt/pkg packages** already installed on the device (`repo pack <package>`, e.g. `nano`) — uses `dpkg -L` + `dpkg-deb -b`, preserves real maintainer scripts (`postinst`/`prerm`/etc.) read from `$PREFIX/var/lib/dpkg/info/` | Reinstalling the SAME apt package on another device, via `pkg install kairos-<id>` from the own local repo | `RepoFragment.kt` |
| `modulos/moduledeb.sh` | **Kairos's own installers** that do NOT go through `dpkg` (Claude Code, OpenCode, n8n — they download loose binaries, patch them with `patchelf`, write their own scripts in `$HOME/scripts/`) | Repackaging an ALREADY-DONE and patched module installation, to reinstall it on another device without re-downloading/re-patching from scratch | `RepoFragment.kt` (same screen, separate section) |

Both reuse the same real `.deb` format (`debian-binary` + `control.tar.xz` + `data.tar.xz`,
via `dpkg-deb -b` with a manual `ar`+`tar` fallback) but are independent code — `moduledeb.sh`
reimplements `_moduledeb_build_deb` instead of sourcing `repo.sh`, because `repo.sh` parses the `"$@"`
of the process that invokes it for its own subcommand dispatch (see the header comment of
`moduledeb.sh`).

## `repo.sh` — local apt repo

Subcommands: `init` (creates `$PREFIX/../kairos-repo` with the structure `dists/stable/main/
binary-aarch64`), `add <module_id>` (packages a Kairos module's binary+scripts with the
prefix `kairos-<id>`), `pack <package>` (repackages ANY already-installed apt package, without a
prefix — reinstalls the same package), `publish` (regenerates `Packages`/`Packages.gz`/`Release`),
`remove <package>` (removes one or more `.deb` files from the local repo, looking for both `<package>_*.deb` and
`kairos-<package>_*.deb`), `source` (prints the `deb [trusted=yes] file://...` line to add
to `sources.list.d/`), `keys` and `sign <KEYID>`.

Packages packaged with `repo pack` are registered in `$HOME/kairos_local/repo_registry.json`
(upsert per package: `package`, `version`, `date`, `deb_path`, `scripts_preserved`) — separate
from Kairos's global module registry (`$HOME/.android_server_registry`).

It does not use `dpkg-repack` (it is not packaged for Termux) — it manually replicates what
it does internally: `dpkg -L <package>` for the already-installed files + direct reading of
`$PREFIX/var/lib/dpkg/info/<package>.{preinst,postinst,prerm,postrm,conffiles}` for the real
maintainer scripts (`dpkg -e`/`--control` does not work here because it operates on a real `.deb` on
disk, not on a package installed by name — the original `.deb` does not exist, which is exactly the
problem this subcommand solves).

The repo format is real Debian (`Packages`/`Packages.gz`/`Release` with MD5/SHA1/
SHA256 checksums), with opt-in GPG signing (`InRelease` clearsign + detached `Release.gpg`, same mechanism as
`apt-secure(8)`; it never generates a new key).

## `moduledeb.sh` — packaging Kairos's own modules

Subcommands: `pack <id>` (packages according to the module's manifest), `install <path.deb>`
(installs/applies — currently in a single step: `dpkg -i`, or a manual `ar`+`tar` fallback that extracts and runs
the `postinst` in the same pass), `list` (lists available manifests).

The generated `.deb` carries the full manifest embedded as `DEBIAN/manifest.json`, and a generic
`postinst` (the same for any module) that on the target device:

1. Runs each dependency's `dependencies[].check_cmd` and reports what is missing (with `install_hint`)
2. Runs `verify_cmd` — if it already passes, it finishes without touching anything else
3. If there are missing dependencies, it stops there (it does not try to patch without the dependencies)
4. Otherwise, runs `patch_cmd` (if the manifest defines it) and verifies again

There are hand-written manifests in `modulos/manifests/` for three modules:

| Manifest | Variant covered | Real patch | Limitation (`not_covered`) |
|---|---|---|---|
| `claude.json` | `native` (ELF+glibc-runner) | `patchelf --set-interpreter` on glibc-runner | `legacy` variant (npm) not covered |
| `opencode.json` | single (native glibc) | none really, just re-`chmod` | Does not capture new libs/assets if the upstream package grows — only the known binary+runtime |
| `n8n.json` | `udocker` | none really (`setup --execmode=P2` best-effort) | The udocker image (~800MB) and the container are NOT packaged — only control scripts; user data/workflows neither |

Three hand-written manifests do not scale to the whole module catalog, which is why the design of the
generic mechanism exists (section "Design: generic manifest + 2-phase installation").

## UI — `RepoFragment.kt`

A single screen covers both mechanisms: the "Own repository" section (`repo init`/`publish`/
`source`, table of installed packages with a "Package" button per row → `repo pack <package>`) and the
"Create module .deb" section (a "Create .deb of <module>" button for each installed module, which
invokes `moduledeb.sh pack <id> --silent`). The registry of already-generated `.deb` files is read from
`repo_registry.json` to show real size/date.

## Design: generic manifest + 2-phase installation

Documented in detail in **`docs/paquetes_deb/index.md`**:

- Manifests are generated dynamically by calling `modulos/<id>.sh --describe-files` (an extension
  of the existing `--describe` contract) instead of reading hand-written JSON — so the mechanism
  scales to any catalog module without maintaining one file per module.
- `moduledeb install` is split into 3 explicit phases — `extract` (only unpacks, runs
  nothing) → `verify` (compares the manifest against the device's real state, shows a summary)
  → `apply` (only then copies files and runs `patch_cmd` if needed). `install` is kept
  as a shortcut that chains the 3 for terminal use.
- The hand-written manifests remain as an optional fallback (they keep working if a module does not yet
  implement `--describe-files`).

See that document for the full JSON contract.

## References

- `modulos/repo.sh`, `modulos/moduledeb.sh` — real code
- `modulos/manifests/{claude,opencode,n8n}.json` — hand-written manifests
- `app/src/main/java/com/termux/app/ui/RepoFragment.kt` — UI
- `docs/paquetes_deb/index.md` — design of the generic mechanism

## Screen controls (`RepoFragment.kt`)

| Control | What it does | Why |
|---|---|---|
| "LOCAL APT REPO" card — text | Clarifies that this repo (`pkg install kairos-<module>`) is different from "Create .deb" (packages ALREADY-installed packages of any kind) | The 2 flows share a screen but solve different things |
| "⚙ Initialize/repair repo (init)" | `repo.sh init` — creates the `dists/stable/...` structure of the local apt repo | — |
| "📑 Publish index (publish)" | `repo.sh publish` — regenerates `Release`/`Packages` after adding or removing `.deb` files | — |
| "🔗 View sources.list line (source)" | `repo.sh source` — shows the real line to add the local repo to `sources.list.d` | — |
| "GPG SIGNING (optional)" card — "🔏 Sign repo with GPG" | Lists own secret keys (`gpg --list-secret-keys`); if there are none, explains how to create one by hand (it never generates the key for the user); if there are, signs (`repo.sh sign <keyid>`), replacing `[trusted=yes]` with `signed-by=` | Kairos never controls a user's private key — signing is 100% opt-in |
| "INSTALLED PACKAGES" card — "📦 Create .deb" row (per package) | `repo.sh pack <package>` — rebuilds a real `.deb` from an already-installed package (`dpkg -L` + `dpkg-deb -b`, without `dpkg-repack`) | Allows repackaging ANY apt package on the system, not just Kairos modules |
| "CREATE MODULE .DEB" card — "📦 Create .deb of <module>" | `moduledeb.sh pack <id> --silent` — builds a `.deb` with the module's ALREADY-installed/patched content + its manifest, reusable without reinstalling from scratch | — |
| ".DEBS GENERATED" card — "👁 View" row | `dpkg-deb --info`/`--contents` on the real `.deb` — shows the control file + file tree | Read-only, preview before installing |
| ".DEBS GENERATED" card — "🗑 Delete" row | `repo.sh remove <package>` — deletes the `.deb` from the local repo and republishes the index | Does not affect the package already installed on the system — clarified in the confirmation dialog |
| MAINTENANCE card (generic) | `addMaintenanceCard()` — standard Update/Uninstall | — |

**Non-obvious detail**: the registry of generated `.deb` files (`~/kairos_local/repo_registry.json`) is
a plain JSON file — `RepoFragment` reads it directly without going through the script (`readRegistry()`),
because listing is a read-only operation and does not justify an extra `ProcessBuilder`.

---

## Technical sheet

> Based on reading `modulos/repo.sh` (773 lines), `RepoFragment.kt` (703) and the `modules.json` entry.

**1. What it is.** Local apt repo at `$PREFIX/../kairos-repo` (`/data/data/com.termux/files/kairos-repo`)
plus `.deb` packaging: `repo add <module>` (binary + `~/scripts/<id>`), `repo pack <package>`
(any installed apt package, keeping postinst/prerm/conffiles), `publish`, `remove`,
`source`, `keys`, `sign <KEYID>` (`repo.sh:12-60`). Id `repo`, `type: "DevOps"`,
`terminalCommand: "repo"`.

**2. Classification: FUNCTIONAL MODULE (stateful tool, no service).** `RepoFragment`
(`ModuleDetailNavigator.kt:43`) has repo status, 3 fixed buttons (initialize, publish,
view `sources.list` line), GPG signing, a dynamic list of installed packages with "Create .deb",
a list of generated .deb files (view/delete) and a "Create .deb of <module>" button for each installed module
(via `moduledeb.sh pack`, `RepoFragment.kt:163-215`) + maintenance. ~4 fixed buttons + 3 dynamic
lists with per-row actions (~10+ real controls).

**3. Installation.** No binaries or downloads: "install" with no subcommand in `--silent` mode
runs `repo init` (`repo.sh:~733-742`: creates `deb/` and `dists/stable/main/binary-aarch64`,
an empty `Packages` and `Release`, registry `repo.*`). Uses `dpkg-deb` (or `ar`+`tar` as a fallback),
`jq` for the `~/kairos_local/repo_registry.json` JSON, `gpg` only if signing. `--force` is accepted
but does nothing (the script's own comment, and the manifest declares `supports_force:false`).

**4. Execution.** No service/port. Each button launches `bash repo.sh <subcmd> --silent` with
`ProcessBuilder` and shows the last `[OK]`/`[ERROR]` line (`RepoFragment.kt:654-675`).

**5. UI.** See point 2. GPG signing only offers already-existing secret keys
(`repo keys`/`gpg --list-secret-keys`); it never generates keys (decision documented in the script).

**6. Files.** `…/files/kairos-repo/{deb,dists/stable/{Release,InRelease,Release.gpg},repo-key.gpg|.asc}`,
`~/kairos_local/repo_registry.json`, registry `repo.*`, checkpoint `~/.install_repo_checkpoint`.
The `.deb` files carry absolute Termux prefix paths (`./data/data/com.termux/files/usr/...`).

**7. Security.**
- Without signing, `repo source` prints `deb [trusted=yes] file://…` (disables apt verification for
  that repo; it is local and own, the script declares it on purpose).
- The private key is never touched and no passphrase is requested in the UI. Signing with `--batch --yes` requires
  gpg-agent to already have the key unlocked (inference: not tested with a passphrase).
- `repo add/pack` interpolate `<id>`/`<package>` into paths without validating characters (`repo.sh:300+`),
  but the UI only passes catalog ids or `dpkg-query` names.
- `repo pack` packages real installed files: a `.deb` of a module with credentials
  stored in `~/scripts/<id>` would include them (inference, not reviewed per module).

**8. Limitations.**
- `runScript`/`runModuleDebPack` read stdout with `readText()` without a timeout; a hung `dpkg-deb`
  leaves the thread blocked and the Snackbar never appears.
- `repo publish` silently deletes `InRelease`/`Release.gpg` (with a warn) and requires signing
  again by hand; the UI does not chain publish+sign.
- `repo add` labels the version from the registry (`<id>.version`), defaulting to 1.0.0;
  it does not reflect the binary's real version if it is not registered.
- `_repo_binary_name` only translates `remote→ssh`; for the rest it assumes binary = id.
- Inference: the repo is a local `file://`; there is no HTTP server to consume it from another
  device (the `repo source` note asks to copy `repo-key.gpg` by hand).

**9. Added on.** `modulos/repo.sh` added on **2026-08-14**.
