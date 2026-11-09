# Plan: Kairos "add-ons" — what they are, where they live and how they are installed (2026-10-08)

> This document is the **plan**; items marked IMPLEMENTED already exist in code, the rest is a proposal pending approval.

## 1. Definition

- **Module**: has an entry in `modules.json`, appears in the Store/module list and has its
  own screen with status, service or options (e.g. Ollama, n8n, Remote).
- **Add-on**: a capability that **another screen invokes**. It does not appear as a module and has no
  catalog entry; it lives inside its **host** (the screen that uses it) and the user
  sees it as an option, tab or button of that host. If the host does not need it, it does not exist.
  Examples: in the file manager, 7z, zip and tar.xz add-ons can be used; in Cybersecurity, Tor can be included.
- **Extension**: the same, but adding options to an existing module (e.g. the local AI provider
  inside a CLI). For code purposes it is treated like an add-on.

## 2. Add-on map (2026-10-08)

| Add-on | Host | Status |
|---|---|---|
| Tor (SOCKS proxy 9050) | Cybersecurity ("Tor" tab) | **IMPLEMENTED** (`util/TorManager.kt`, `CiberseguridadFragment.kt`) |
| Syncthing | Homelab (Services → Add-ons) | in progress (see §5) |
| Restic | Homelab (Services → Add-ons) | in progress (see §5) |
| 7z / zip / tar.zst | File manager | **IMPLEMENTED** (`util/ArchiveManager.kt`, own engine without binaries) |
| yt-dlp + ffmpeg | Unified downloader (`media-downloader`) | **IMPLEMENTED** as a single module (`media_downloader.sh`) |
| rclone | — | removed entirely (no module or UI) |
| Wine/Hangover | — | removed from the catalog; left as future work |

## 3. Proposed contract for an add-on (data-driven, adaptable)

So the `TorManager` pattern is not repeated once per tool, an add-on is described with
data and a common engine (design decision: do not close doors for the future):

```
Addon(
  id, name, descriptionRes,       // identity and text (es/en)
  host,                           // "ciberseguridad" | "homelab" | "archivos" | ...
  pkg, bin,                       // pkg package and the real executable to verify with --version
  service?: (tmuxSession, port, serveArgs, guiUrl?),   // only if it is a daemon
  actions: [...]                  // real UI actions (each one = one operation of the program)
)
```
Common engine: `install()` (pkg + real check that the binary runs), `status()`, `start()/stop()`
(tmux + port), `uninstall()`. The host only draws the cards. State registry:
`addon.<id>.*` keys in `~/.android_server_registry` (same mechanism as modules).

Rules all of them inherit: no background process without a visible switch; secrets
(repository passwords, tokens) are never stored or shown again; each button maps to a real,
verified operation of the program.

## 4. How they reach the device ("rootfs or wizard")

1. **On demand from their host** (today): the "Install" button runs `pkg install` and verifies the
   binary. It works without touching anything else.
2. **Inside the embedded rootfs**: add the package to `tools/rootfs/package_list.txt`
   (consumed by `tools/rootfs/build_rootfs.py`, which downloads the Termux `.deb` files without compiling; see
   [`../bootstrap/rootfs-embebido.en.md`](../bootstrap/rootfs-embebido.en.md)). Advantage:
   they are installable without network. Cost: APK size with rootfs (today ~533 MB). **Still to be measured**:
   the real weight of tor/syncthing/restic before deciding.
3. **Wizard**: `WizardActivity` would offer an "Optional add-ons" screen with checkboxes that
   queue the `pkg install` commands. **The wizard code has not been read yet**; the exact design is left
   until it is confirmed which add-ons go there.

## 5. Concrete next step (in progress)

Syncthing and Restic move to the Homelab **Services** tab as an "Add-ons" section, using the
common engine from §3. Syncthing is a daemon with a web interface (`syncthing serve --no-browser
--no-restart --gui-address=127.0.0.1:8384`, per the former `modulos/syncthing.sh`); Restic is a
CLI without a service (`restic -r <repo> init|backup|snapshots`, password via the
`RESTIC_PASSWORD` variable, never stored).

## 6. Integrations "that others invoke" — ideas, NOT implemented

- Cybersecurity tools over Tor: `sqlmap` has its own proxy/Tor options (verify in
  its real help before exposing them); `nmap` would need `proxychains` (not confirmed on Termux).
- Homelab automations: "start Syncthing on boot" reusing the existing trigger
  mechanism.
- Cloud/Restic: use a restic repository as a backup destination for the Cloud folder (design pending).

## 7. Open questions

1. Which add-ons go inside the rootfs and which are only downloadable in the wizard?
2. Are add-ons listed in a central place (an "Add-ons" screen) or only
   inside each host, as now?
3. Of the modules with ≤3 options (clang, kotlin, livesrv, markserv, localtunnel): which
   become add-ons and under which host? (Still pending confirmation.)


## Clarification 2026-10-08

- **Docker is not an add-on**: it is a module that also requires root. It was left out of any merge of small modules.
- **Add-ons in rootfs vs. wizard**: the rootfs only serves to shorten downloads; the wizard runs *after* extracting it, so whatever goes in the rootfs saves the wizard time. Some things cannot go in the rootfs. Both the rootfs code and the wizard code must **detect which packages/add-ons are already installed** and continue with the rest. Research on which packages fit: pending.
