# Docker

**Technical reference** — `docker` module (`id: "docker"` in `modules.json`). Unlike the rest of
Kairos's modules, **this module doesn't install anything**. It's an "honest" module: it explains
why real Docker can't work on the device and points the user to the `udocker` module, which
*is* a real alternative already available in Kairos.

**App:** Kairos (termux-app fork) · ARM64 · Android
**Script:** `modulos/docker.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DockerFragment.kt`

---

## 1. Why it exists

A real Docker daemon (`dockerd`) needs direct kernel access to:
- **namespaces** — process, network, and mount isolation
- **cgroups** — resource limits
- **overlayfs** — for image layers

Android's kernel, for apps **without root**, has those features disabled/inaccessible by
platform sandbox design. This is **not a Termux limitation** — it's a barrier from the Android
operating system itself. There's no way to "enable" them from userspace without rooting the
device.

With root it would technically be possible (real namespaces/cgroups become accessible), but
that requires rooting the phone — something Kairos is explicitly designed **not** to require,
and something this module doesn't push the user toward.

---

## 2. What the script exactly does (`modulos/docker.sh`)

The script **doesn't attempt to install or simulate Docker in any way**. Its complete behavior:

1. Parses flags: `--silent`, `--describe`, `--force` (accepted for consistency with the rest of
   `modulos/`, but has no effect — there's nothing to force).
2. If `--describe`: prints the declarative JSON manifest and exits (see section 4).
3. Loads `lib.sh` (shared helpers: `registry_write`, `notify_event`, `log`, colors).
4. Does a *best-effort* detection of whether the device appears rooted, **only for the
   message** — it never changes the script's behavior:
   ```bash
   if command -v su &>/dev/null && su -c "id" &>/dev/null; then
     _IS_ROOTED=true
   fi
   ```
5. Prints the explanation (banner if not `--silent`, always the `[INFO]` block):
   - If the device appears rooted: clarifies that this module still doesn't install Docker
     (out of Kairos's scope), and that with root the user could install it manually *outside*
     of Kairos if they wanted to.
   - If it doesn't appear rooted: recommends udocker directly as the real path forward.
6. Writes to the registry `~/.android_server_registry`:
   ```bash
   registry_write docker "installed=false" "reason=no_namespaces_cgroups_unrooted_android" \
     "alternative=udocker" "rooted_device=$_IS_ROOTED"
   ```
   **`installed` is always `false`, without exception** — there's no code path in the script
   that sets it to `true`. This is deliberate: the UI should never show "Docker installed".
7. `notify_event "docker" "explained" "not_supported_use_udocker"` + `log "..."`.
8. `exit 0` — **not an error**. The correct, expected behavior of the module is to explain and
   exit successfully, not to fail.

There's no code branch that downloads, compiles, or runs anything related to real Docker.

---

## 3. Real app screen — `DockerFragment.kt`

`DockerFragment.kt` (`app/src/main/java/com/termux/app/ui/DockerFragment.kt`) is a dedicated
screen, registered in `ModuleDetailNavigator.kt`:

```kotlin
"docker" -> DockerFragment()
```

Screen content (extends `BaseModuleFragment`, without running the script or touching terminal
sessions — it's 100% text + navigation):

1. **Warning card** — "⚠ Real Docker doesn't work on this device", with a plain-language
   explanation (no namespaces/cgroups jargon): Docker needs special permissions that Android
   doesn't grant to any app without rooting, it's not a Kairos or Termux problem, and rooting is
   outside of what the app is trying to do.
2. **"REAL ALTERNATIVE" card** — explains what udocker is in one sentence: it runs Docker Hub
   images (alpine, ubuntu, debian, etc.) without root, with weaker but functional isolation, the
   same technology the n8n module already uses internally.
3. **Primary action button** — "→ Go to udocker (real alternative)", which navigates directly to
   `UdockerFragment()` (`navigateTo(UdockerFragment())`) instead of opening a terminal or running
   anything.

The Fragment never invokes `docker.sh` — all of the script's logic (root detection, registry)
only runs if something else triggers the script's execution outside this screen (e.g., a
generic installation flow); the screen itself is purely informational/navigational.

---

## 4. Declarative manifest (`--describe`)

```json
{"id":"docker","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"experimental":true,"note":"Real Docker (dockerd) is NOT possible without root on Android/Termux — the kernel doesn't expose namespaces/cgroups to unprivileged apps. This script doesn't install anything, explains why, and points to modulos/udocker.sh as the real alternative already available in Kairos"}
```

## 5. Entry in `modules.json`

```json
{
  "id": "docker",
  "name": "Docker",
  "repo": "Honkonx/kairos-lab",
  "script": "docker.sh",
  "icon": "🐳",
  "port": "",
  "size": "0MB (installs nothing)",
  "type": "Native",
  "requiresProot": false,
  "estimate": "instant",
  "hasSwitch": false,
  "experimental": true,
  "arch": "bionic",
  "category": "dev",
  "installMethods": [],
  "requires": []
}
```

Notable points:
- `hasSwitch: false` — there's no ON/OFF toggle (no point in enabling/disabling something that
  never gets installed).
- `size: "0MB (installs nothing)"` and `estimate: "instant"` — literally reflect that the
  script doesn't download or install anything, it just prints and exits.
- `installMethods: []` and `requires: []` — empty, consistent with there being no real
  installation.
- `experimental: true` — flagged the same way as other "explanatory" modules (`qemu`, `mimocode`).

---

## 6. Registry — why `installed` is never `true`

`docker.sh` always writes:

```
docker.installed=false
docker.reason=no_namespaces_cgroups_unrooted_android
docker.alternative=udocker
docker.rooted_device=<true|false>
```

This is intentional and future-proof against accidental changes: any part of the UI that
checks the registry to decide whether to show the module as "installed" (e.g., a green
checkmark, a status badge) will never see it that way for `docker`, regardless of whether the
device has root or not.

---

## 7. Real relationship with `udocker` — how they differ and why they're two separate modules

`docker` and `udocker` (see `docs/modulos/udocker.md`) tackle the same problem — running
Docker-style containers on Android without root — but they're fundamentally different things,
not two names for the same thing:

| | `docker` (this module) | `udocker` |
|---|---|---|
| **What it is** | An explanatory module — installs nothing, just redirects | A real tool that **does** run container images |
| **Engine** | N/A (doesn't run containers) | PRoot (syscall interception via `ptrace`) |
| **Real Docker (dockerd)?** | No, doesn't even attempt it | Also no — it's a userspace container *emulator*, not real Docker |
| **Kernel namespaces/cgroups** | Not applicable | Doesn't use or need them — that's exactly why it works without root |
| **Root required** | No (and the script never asks for it) | No |
| **Its own `modulos/*.sh`** | Yes — `modulos/docker.sh` | **No** — `modulos/udocker.sh` doesn't exist; it's installed inline from `modulos/n8n.sh` (udocker variant) and `modulos/entorno.sh` (`_install_udocker()`) |
| **`modules.json` entry** | Yes (`id: "docker"`, `hasSwitch: false`) | Has no entry of its own — it's not a module with a switch |
| **Fragment** | `DockerFragment.kt` (informational + button to udocker) | `UdockerFragment.kt` (functional) |
| **`installed` in registry** | Always `false` | Has no entry of its own in the registry — its status depends on whether `command -v udocker` finds the binary left by n8n or Entorno |
| **Network isolation** | N/A | None real — shares the host's network stack (the container's `localhost` = Android's `localhost`) |

**Why the two exist separately, instead of merging `docker.sh` into `udocker`:**
`docker` serves a **UX/honesty** role — it's the module a user reaches when looking for "Docker"
by name (because that's the term they know), finds it in the Store/module list by its 🐳 icon
and familiar name, and instead of silently failing or installing something that isn't Docker
while pretending it is, gets the real operating-system limitation explained and gets routed to
the right place. udocker, on the other hand, doesn't need its own entry point in the Store
because it's not something a user installs on its own from scratch — it's infrastructure that
other modules (n8n, Entorno) bring along when they need it, and that `DockerFragment` points to
as a navigation destination once it has already explained the difference.

In other words: **`docker.sh` isn't a stripped-down version of `udocker`, nor a step before
installing it** — it's a semantic landing screen for the term "Docker" that redirects to the
correct tool, with zero functional overlap.

---

## 8. Usage from the app

```bash
bash docker.sh --silent
```

Supported flags:
- `--silent` — no prompts (still prints the whole explanation via `[INFO]`, only skips the
  interactive banner).
- `--describe` — prints the declarative manifest (section 4) and exits.
- `--force` — accepted for consistency with the rest of `modulos/`, with no effect in this
  script.

## Screen controls

Deliberately simple screen — no install switch, no real action buttons.

| Control | What it does | Why |
|---|---|---|
| "⚠ Real Docker doesn't work on this device" text + explanation | Read-only | Real Docker (`dockerd`) needs a privileged daemon with direct access to kernel namespaces + cgroups — Android blocks that from unrooted apps by sandbox design, not a limitation of Termux or Kairos |
| "→ Go to udocker (real alternative)" | `navigateTo(UdockerFragment())` | udocker runs real Docker Hub images in userspace via PRoot, without real namespaces/cgroups but without needing root — the same technology the n8n module already uses internally |
