# Docker — Kairos Reference
## Full technical documentation

**Reference document** — the `docker` module (`id: "docker"` in `modules.json`). Unlike the rest of
the Kairos modules, **this module installs nothing**. It is an "honest" module: it explains why real
Docker cannot work on the device and points the user to the `udocker` module, which is a real
alternative available in Kairos.

**App:** Kairos (termux-app fork) · ARM64 · Android
**Script:** `modulos/docker.sh` (133 lines, v1.0.0)
**Fragment:** `app/src/main/java/com/termux/app/ui/DockerFragment.kt` (57 lines)

---

## 1. Why it exists

The header of `modulos/docker.sh` (lines 6-27) documents the reasoning, with the same honesty
criterion as `modulos/mimocode.sh` and `modulos/qemu.sh` (do not pretend something works when it does not):

A real Docker daemon (`dockerd`) needs direct Linux kernel access to:
- **namespaces** — process, network and mount isolation
- **cgroups** — resource limits
- **overlayfs** — for image layers

For **non-rooted** apps, the Android kernel has those features disabled/inaccessible by design of the
platform sandbox. This is **not a Termux limitation** — it is a barrier of the Android operating
system itself. There is no way to "enable" them from userspace without rooting the device.

With root it would technically be possible (real namespaces/cgroups become accessible), but that
requires rooting the phone — something Kairos is explicitly designed **not** to require, and which this
module will not push the user to do. Moreover, many manufacturers' stock kernels do not compile
namespaces/cgroups, so root alone is not enough.

---

## 2. What the script does exactly (`modulos/docker.sh`)

The script **does not try to install or simulate Docker in any way**. Its full behavior:

1. Parses flags (`docker.sh:52-62`): `--silent`, `--describe`, `--describe-files` (with `files: []`, `verify_cmd: "false"` and a `not_covered` explaining there is nothing to package), `--force` (accepted for consistency with the rest of `modulos/`, but with no effect).
2. If `--describe`: prints the declarative JSON manifest and exits (see section 4).
3. Loads `lib.sh` (shared helpers: `registry_write`, `notify_event`, `log`, colors) with `source ... || exit 1`, so a missing `lib.sh` breaks the script.
4. Does a *best-effort* detection of whether the device looks rooted, **only for the message** — it does not
   change the script's behavior in any case (it may trigger a root prompt on rooted devices):
   ```bash
   if command -v su &>/dev/null && su -c "id" &>/dev/null; then
     _IS_ROOTED=true
   fi
   ```
5. Prints the explanation (banner if not `--silent`, always the `[INFO]` block):
   - If the device looks rooted: clarifies that this module still does not install Docker (out of
     scope for Kairos), and that with root the user could install it manually *outside* Kairos.
   - If it does not look rooted: directly recommends udocker as the real route.
6. Writes to the registry `~/.android_server_registry`:
   ```bash
   registry_write docker "installed=false" "reason=no_namespaces_cgroups_unrooted_android" \
     "alternative=udocker" "rooted_device=$_IS_ROOTED"
   ```
   **`installed` is always `false`, without exception** — there is no path in the script that sets it
   to `true`. This is deliberate: the UI must never show "Docker installed".
7. `notify_event "docker" "explained" "not_supported_use_udocker"` + `log "..."`.
8. `exit 0` — **it is not an error**. The expected behavior of the module is to explain and exit
   successfully, not to fail.

There is no code branch that downloads, compiles, or runs anything related to real Docker.

---

## 3. App screen — `DockerFragment.kt`

`DockerFragment.kt` (extends `BaseModuleFragment`) replaces the generic "Open in terminal" of
`GenericModuleFragment` (useless here because the script only prints text and exits), and is registered
in `ModuleDetailNavigator.kt`:

```kotlin
"docker" -> DockerFragment()
```

The screen is 100% text + navigation (it does not run the script or touch terminal sessions):

1. **Warning card** — "⚠ Real Docker does not work on this device", with the explanation in plain
   language (no namespaces/cgroups jargon): Docker needs special permissions that Android does not give
   any non-rooted app, it is not a Kairos or Termux problem, and rooting is outside what the app aims to
   do (`strings_docker.xml:4-5`).
2. **"REAL ALTERNATIVE" card** — explains what udocker is in one sentence: it runs Docker Hub images
   (alpine, ubuntu, debian, etc.) without root, with weaker but functional isolation, "it is the same
   technology the n8n module already uses internally" (`strings_docker.xml:6-7`).
3. **Primary action button** — "→ Go to udocker (real alternative)", which navigates straight to
   `UdockerFragment()` (`navigateTo(UdockerFragment())`, `DockerFragment.kt:53-55`) instead of opening
   a terminal or running anything.

The Fragment never invokes `docker.sh`; all the script's logic (root detection, registry) only runs if
something triggers script execution outside this screen (e.g. the generic install flow).

| Control | What it does | Why |
|---|---|---|
| Text "⚠ Real Docker does not work on this device" + explanation | Read-only | Real Docker (`dockerd`) needs kernel namespaces + cgroups accessed directly by a privileged daemon — Android blocks that for non-rooted apps by sandbox design |
| "→ Go to udocker (real alternative)" | `navigateTo(UdockerFragment())` | udocker runs real Docker Hub images in userspace via PRoot, without real namespaces/cgroups but without needing root |

---

## 4. Declarative manifest (`--describe`)

```json
{"id":"docker","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"experimental":true,"note":"Docker real (dockerd) NO es posible sin root en Android/Termux — el kernel no expone namespaces/cgroups a apps sin privilegios. Este script no instala nada, explica el motivo y dirige a modulos/udocker.sh como alternativa real ya disponible en Kairos"}
```

## 5. `modules.json` entry

Relevant fields (`modules.json:733-755`): `id: "docker"`, `name: "Docker"`, `repo: "Honkonx/kairos-lab"`,
`script: "docker.sh"`, `icon: "🐳"`, `port: ""`, `size: "0MB (no instala nada)"`, `type: "Nativo"`,
`requiresProot: false`, `estimate: "instantáneo"`, `hasSwitch: false`, `experimental: true`,
`arch: "bionic"`, `category: "dev"`, `installMethods: []`, `requires: []`, `catalogVersion: "1.0.0"`,
`recommended: false`, `downloads: 0`, plus a full `description`.

Notable points:
- `hasSwitch: false` — there is no ON/OFF toggle (it makes no sense to enable/disable something that is
  never installed).
- `size: "0MB (no instala nada)"` and `estimate: "instantáneo"` — they reflect that the script downloads
  and installs nothing, it only prints and exits.
- `installMethods: []` and `requires: []` — empty, consistent with there being no real installation.
- `experimental: true` — marked the same as other "explanatory" modules (`qemu`, `mimocode`).

---

## 6. Registry — why `installed` is never `true`

`docker.sh` always writes:

```
docker.installed=false
docker.reason=no_namespaces_cgroups_unrooted_android
docker.alternative=udocker
docker.rooted_device=<true|false>
```

This is intentional and safe against accidental changes: any part of the UI that queries the registry to
decide whether to show the module as "installed" (e.g. a green check, a status badge) will never see
it that way for `docker`, regardless of whether the device has root. It is the module's only write: it
creates no directories or binaries.

---

## 7. Relationship with `udocker` — how they differ and why they are two separate modules

`docker` and `udocker` (see `UDOCKER.md`) touch the same problem — running Docker-style containers on
Android without root — but they are different things:

| | `docker` (this module) | `udocker` |
|---|---|---|
| **What it is** | An explanatory module — installs nothing, only redirects | A real tool that **does** run container images |
| **Engine** | N/A (runs no containers) | PRoot (syscall interception via `ptrace`) |
| **Real Docker (dockerd)?** | No, does not even try | Neither — it is a userspace container *emulator*, not real Docker |
| **Kernel namespaces/cgroups** | Not applicable | Does not use or need them — that is why it works without root |
| **Root required** | No (and the script never asks for it) | No |
| **Own `modulos/*.sh`** | `modulos/docker.sh` | `modulos/udocker.sh` |
| **`modules.json` entry** | `id: "docker"`, `hasSwitch: false` | `id: "udocker"` (`modules.json:655`) |
| **Fragment** | `DockerFragment.kt` (informational + button to udocker) | `UdockerFragment.kt` (functional) |
| **`installed` in registry** | Always `false` | Depends on whether `command -v udocker` finds the binary |
| **Network isolation** | N/A | None really — it shares the host network stack (the container's `localhost` = Android's `localhost`) |

**Why the two exist separately:** `docker` plays a **UX/honesty** role — it is the module a user reaches
when searching for "Docker" by name (because it is the term they know), finds in the Store/module list
by its familiar 🐳 icon and name, and instead of failing silently or installing something that is not
Docker and passing it off as such, the real operating-system limitation is explained and the user is
sent to the right place.

In other words: **`docker.sh` is not a reduced version of `udocker`, nor a step before installing it**
— it is a semantic landing screen for the term "Docker" that redirects to the right tool, with zero
functional overlap.

---

## 8. Use from the app

```bash
bash docker.sh --silent
```

Supported flags:
- `--silent` — no questions (it still prints the entire explanation via `[INFO]`, it only skips the
  interactive banner).
- `--describe` — prints the declarative manifest (section 4) and exits.
- `--force` — accepted for consistency with the rest of `modulos/`, with no effect in this script.

## 9. Security

No secrets, no `curl | bash`, no interpolation of user input. The only minor risk: it invokes
`su -c "id"` if `su` exists, which may trigger a root prompt on rooted devices (from reading the code;
real effect not tested).

## 10. Known limitations

- The `description` in `modules.json` is very long for a catalog card.
- Inference: the module appears in the catalog with a 🐳 icon even though it installs nothing, which may
  confuse a user looking for real Docker; it is the UX decision described in section 7.

## 11. Date added

2026-08-16 (`modulos/docker.sh`).
