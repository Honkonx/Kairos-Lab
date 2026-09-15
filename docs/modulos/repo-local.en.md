# Local repo (.deb) — module packaging (`repo`)

**Kairos module** — managed from the UI (Modules tab, `RepoFragment.kt`). Installation handled
by the app via `ProcessBuilder` → `modulos/repo.sh`; the same screen also exposes a sibling
mechanism, `modulos/moduledeb.sh`, for packaging Kairos's own modules that don't go through
`dpkg`.

**What it solves**: reinstalling on another device (or another Kairos install) without
downloading or re-patching anything from scratch — an already-installed `apt` package, or an
already-working install of a Kairos module, gets repackaged into a real `.deb` that can later be
installed with `pkg install`.

---

**Script:** `modulos/repo.sh` (+ `modulos/moduledeb.sh`) — mirrored at
`app/src/main/assets/scripts/`
**Fragment:** `app/src/main/java/com/termux/app/ui/RepoFragment.kt`
**`id` in `modules.json`:** `repo` — no switch (a packaging tool, not a service with a persistent
process), development category, terminal command `repo`

---

## 1. Overview

Kairos has two sibling `.deb` packaging mechanisms, with different purposes but sharing one
screen:

| Mechanism | What it packages | What it's for |
|---|---|---|
| `repo.sh` | Real **apt/pkg packages** already installed on the device (e.g. `nano`, `nmap`, any Termux package) | Reinstalling the same apt package on another device, via `pkg install kairos-<package>` from the local repo |
| `moduledeb.sh` | **Kairos's own installers** that don't go through `dpkg` (Claude Code, OpenCode, n8n — modules that download standalone binaries and patch them, instead of installing via `pkg`) | Repackaging an already-done, already-patched module install, so it can be reinstalled on another device without re-downloading/re-patching from scratch |

Both produce the same real `.deb` format (`debian-binary` + `control.tar.xz` + `data.tar.xz`,
the standard Debian/Ubuntu/Termux format) — they're independent of each other, each with its own
build logic.

## 2. Permissions

- **Android**: none specific — runs in the same Termux process as the setup wizard, no extra
  permissions.
- **Internal Termux**: reads files already installed on Termux's own filesystem (`dpkg -L`,
  `dpkg` metadata) and writes `.deb` files/repo indexes under `$HOME` — no root required, no
  ports opened, no network dependency unless the user wants to export/share the result.

## 3. `repo.sh` — local apt repo on the device

Builds a real apt repository right on the device itself (standard
`dists/stable/main/binary-aarch64` layout), with these subcommands:

| Subcommand | What it does |
|---|---|
| `init` | Creates the local repo's structure |
| `add <module>` | Packages a Kairos module's binary+scripts with the `kairos-<id>` prefix |
| `pack <package>` | Repackages **any** apt package already installed on the device, with no prefix — lets you reinstall that exact same package elsewhere |
| `publish` | Regenerates the repo's indexes (`Packages`/`Packages.gz`/`Release`, with MD5/SHA1/SHA256 checksums) after adding or removing a `.deb` |
| `remove <package>` | Removes one or more `.deb` files from the local repo and republishes the index |
| `source` | Prints the line ready to add the local repo to the device's apt configuration |

`pack` rebuilds the `.deb` without relying on external repackaging tools (not available in this
environment): it reads the package's real installed file list and the real maintenance scripts
already shipped by Debian/Termux (the ones that run before/after installing or removing a
package), and preserves them in the rebuilt `.deb` — the resulting package behaves just like one
installed from the official repo.

**GPG signing (optional)**: the local repo can be signed with the user's own GPG key — Kairos
never generates or controls that key, it's entirely the user's decision and responsibility.
Unsigned, the repo is added as explicitly "trusted" (standard behavior for a personal local
repo); signed, it uses the same verification mechanism as any official apt repo.

Every package packaged with `pack` gets logged (package, version, date, `.deb` path) in its own
local index, separate from Kairos's general module registry.

## 4. `moduledeb.sh` — packaging Kairos's own modules

Covers the case `repo.sh` can't: a Kairos module that installed its binary by downloading it
directly (not via `pkg`) and applying its own patches so it runs on Termux — with no source
`dpkg` package, there's nothing `dpkg -L` can read.

| Subcommand | What it does |
|---|---|
| `pack <id>` | Packages an already-done, already-working module install into a `.deb` |
| `install <path.deb>` | Installs/applies that `.deb` on the target device |
| `list` | Lists which modules have packaging support available |

The generated `.deb` includes what dependencies the module needs and how to verify it ended up
working — installing it on another device first confirms the dependencies are present (warning
about anything missing), then checks whether the module already works as-is, and only patches it
if needed before re-verifying.

Current coverage: an initial set of modules with packaging support (includes variants of Claude
Code, OpenCode, and n8n) — each one honestly documents its own limitations (for example, which
install variant it covers, or which files it can't capture if the upstream project grew past what
was known when its packaging support was written). The mechanism is designed to scale to any
module in the catalog over time, not just the ones that already have it today.

## 5. State detection

- **Installation of the `repo` module itself**: registry (`repo.installed=true`).
- There's no "running"/"stopped" concept — it's a packaging tool invoked on demand, with no
  background process of its own.

## 6. Real app screen (`RepoFragment.kt`)

One screen covers both mechanisms, with two clearly separated sections so the user doesn't
confuse which of the two flows they're using:

**"Own repository" section**:
- Initialize/repair the local repo.
- Publish the index (after adding or removing packages).
- View the line to add the local repo to the device's apt configuration.
- Sign the repo with the user's own GPG key (optional; if the user doesn't have one, it explains
  how to create it — Kairos never generates it on their behalf).
- A table of apt packages installed on the device, with a "Package" button per row that runs
  `repo pack <package>`.

**"Create module .deb" section**: buttons for the modules with packaging support available
today, which invoke `moduledeb pack <id>`.

**"Generated .deb" section**: a list of already-packaged files (real size and date), with
actions to view the `.deb`'s contents (metadata + file tree, before installing) or remove it
from the local repo (doesn't affect the package already installed on the device, only removes
the `.deb` from the repo).

## 7. Technical notes

- The two mechanisms (`repo.sh`/`moduledeb.sh`) are independent of each other — they don't share
  `.deb`-building code, even though both produce the same real format.
- The list of generated `.deb` files is read directly from the local index (plain JSON) without
  invoking any script — it's a read-only operation.
- `moduledeb.sh`'s coverage (which modules have packaging support) is a set that grows over
  time — a catalog module that doesn't have support yet simply won't appear as an option in the
  "Create module .deb" section until it's added.
