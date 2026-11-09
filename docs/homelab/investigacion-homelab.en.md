# Research: homelab, proot-distro/desktops and Shizuku (2026-10-09)

> Based on web searches (sources below) and on what is already verified in Kairos.
> Blog sources are secondary: whatever is not official is flagged. None of this was tested on the device.

## 1. What a homelab is

A homelab is a home server (a mini PC, a NAS, a Raspberry Pi…) that hosts your own services.
According to the tutorials consulted, the typical shape is: a machine with a hypervisor (**Proxmox VE**) running
virtual machines or LXC containers, **Docker** on top, **Portainer** to manage containers and a
**Homepage**-style dashboard (port 3000 by default) as the start page, with authentication and automatic service
discovery. Resource references: about 512 MB of RAM minimum and ~2 GB recommended for
Portainer + containers; a reasonable own estimate as a starting point is 8 GB of RAM and an SSD (it does not come
from the sources).

Sources: [Portainer on Proxmox LXC](https://oneuptime.com/blog/post/2026-03-20-portainer-proxmox-lxc/markdown),
[Proxmox + Docker at home](https://dev.to/devopsbg/home-devops-solution-proxmox-and-docker-for-enterprise-home-lab-212i),
[Homepage 2.2 guide](https://aicybr.com/blog/homepage-2-2-self-hosting-dashboard-guide) (single third-party
source: cross-check with the official one).

### What it means for Kairos (own judgement, not verified in use)

- A phone is **not** a server with a hypervisor: without root there is no real Docker or systemd (see §2). Kairos
  acts as a homelab with **Termux processes + proot + a dashboard**; the equivalent of "containers" are the
  modules and the proot distros.
- Dashboard pieces that already exist: Homelab (rings, services, automations, SSH) and the control web UI.
  Ideas from other dashboards (app manifest, JSON widgets, SSE, categories) are a possible line of work.
- Docker is **a module that requires root**, not an add-on (project decision).

## 2. proot-distro, native desktops and desktops inside a distro

Findings from the search (community; cross-check with the official Termux documentation):

- proot intercepts every system call with **ptrace**; a developer measured `fstatat` going from 2.75 µs to
  304 µs under proot (110×). That is why heavy desktops run slowly in proot.
- Android's **seccomp** filter blocks system calls; Wine on Termux runs into it immediately.
- Usual limitations of a rootless proot distro: **no systemd, no real GPU (no virglrenderer), no
  Docker**.
- **Phantom process killer** (Android 12+): it can kill child processes ("signal 9"); it is recommended to
  disable it or enable "Disable child process restrictions" (Android 14+).
- A **native Termux desktop with Termux:X11** is faster than one inside proot; the usual combination is a
  desktop in proot displayed through X11 or VNC.
- There is an advisory about `proot-distro` 5.0.2 (hardlink validation when restoring backups): only restore
  trusted files and keep the package up to date.

Sources: [proot-distro issue 526](https://github.com/termux/proot-distro/issues/526),
[FEX-Emu on Termux](https://wiki.fex-emu.com/index.php/Termux),
[proroot (no ptrace)](https://news.hada.io/topic?id=28342),
[termux-desktop](https://github.com/sabamdarif/termux-desktop),
[Debian+XFCE with proot-distro](https://gituser.codeberg.page/posts/2024/10/install-debian-with-xfce-on-termux-using-proot-distro/).

What has been verified **on the Kairos device** includes, for example, that `gtk-update-icon-cache` takes more
than 9 minutes under proot (with a known workaround).

## 3. Shizuku

What it is (official Shizuku README and manual, via search): a privileged Java server that starts with the
identity of the **ADB shell** (uid 2000) and exposes system APIs over Binder to other apps. It is **not root**:
it has the shell's limited permissions. It is started via **wireless debugging** (Android 11+, no PC) or via
ADB from a PC (before Android 11); on non-rooted devices the start has to be repeated after every reboot.
`rish` is a script that Shizuku provides to run commands in Termux through that server
(`rish -c "command"` ⇒ uid 2000). Sources: [Shizuku (README)](https://github.com/RikkaApps/Shizuku/blob/master/README.md),
[manual](https://shizuku.rikka.app/guide/setup/), [termux-shizuku-tools](https://github.com/jecis-repos/termux-shizuku-tools)
(community). Shizuku license: **not verified** here.

### How it would fit into Kairos (proposal, nothing implemented)

"Controlling Android completely over SSH" with SDK libraries was raised. A realistic path:

1. **SSH to Termux already exists** (Homelab → "SSH access to Termux", port 8022, `RemoteManager`).
2. **Shizuku/rish** would provide commands with shell identity (`pm`, `settings`, `input`, `am`, `cmd`) from that
   SSH — without root, with the limitations of the ADB shell and depending on the user starting it.
3. **Kairos as a client**: integrate the Shizuku API (Android dependency) to request the permission and
   run actions from the app, without going through the terminal.
4. **Security:** exposing "all of Android" over SSH greatly widens the risk; an action allow-list,
   confirmations and a visible switch are needed. This is a design pending approval.

To be verified: compatibility with Kairos's `targetSdk 28`, the real `rish` package in Termux and its
license, and whether the test device has wireless debugging.
