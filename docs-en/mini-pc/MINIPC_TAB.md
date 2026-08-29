# "Mini PC" tab design

The embedded Linux desktop functionality (proot-distro, X11 server, VNC) lives in its own tab in
the main navigation, "Mini PC", instead of being buried as just another card in the module
catalog. This document describes how that screen is organized and the design decisions behind
its structure.

## Why a dedicated tab

Kairos's bottom navigation has a practical limit on the number of items that can be shown at
once. Rather than adding one more item to an already-tight limit, the desktop functionality was
grouped coherently: everything related to "entering a Linux desktop" (native, inside a distro,
or via VNC) now lives under one entry point — the "Mini PC" tab — while low-level X11 server
configuration (resolution, scale, density, orientation) became a secondary status/settings
screen, reachable from there.

## Sub-tab organization

The screen is organized into five categories, each with a grid of icon+text actions split into
two groups: **Launch** (start something) and **Maintenance** (install, configure, stop).

- **Native** — XFCE4 desktop running directly on top of Termux, without an intermediate distro:
  install and start, autostart configuration, installing additional desktop environments
  (LXQt/MATE), refreshing launchers, and stopping the desktop (without shutting down the
  underlying X11 server).
- **X11** — the shared infrastructure layer: entering the embedded X11 server's viewer, server
  configuration (resolution, scale, keyboard), and stopping the server entirely. This layer was
  explicitly split out from "Native" because the same X11 server is shared by native mode,
  distro mode, and future compatibility layers — it isn't an action exclusive to the native
  desktop.
- **Distros** — installing and removing Linux distributions, terminal access, installing or
  removing a desktop environment inside a distro, starting that desktop, backing up the full
  distro, and the folder bridge that links projects between the host and the distro.
- **VNC** — installing TigerVNC, starting it simply or with advanced configuration, opening the
  viewer bundled with the app, and stopping the VNC server.
- **System** — PulseAudio control, GPU diagnostics, and selecting the accelerated rendering
  method.

Each tile shows a visual "running" indicator when relevant, based solely on real signals
confirmed by the module's status — never a fabricated indicator without a real source of truth
behind it.

Above the sub-tabs, a status card summarizes the active GPU method, whether the X11
server/VNC/PulseAudio are running, and which desktops are installed; below, a maintenance card
covers actions on the Entorno module itself (update, uninstall).

## Merging X11 and VNC into Mini PC

Before this design, X11 server control and the VNC viewer lived on a separate screen, apart from
the rest of the desktop flow. Since the real path most users take to "enter a desktop" goes
through Entorno (install/start) rather than a standalone server control screen, those actions
were merged into the same Mini PC tab, removing a redundant screen and unifying the entry point.

## Catalog of supported distributions

The catalog of available distributions includes the most common bases (Ubuntu, Debian, Alpine,
Arch Linux, Fedora, Void, Kali) plus three added to broaden the variety of package managers and
support lifecycles available:

- **Manjaro** — curated/stable Arch, with a package delay window and its own testing channel,
  for users who want recent packages without the risk of a pure rolling release.
- **Rocky Linux** — an RHEL clone with an extended support lifecycle, useful for containers
  running a persistent service without frequent breaking changes.
- **openSUSE Tumbleweed** — the only distribution in the catalog with an RPM/zypper package
  manager outside the RHEL family, with its own tooling and automated testing (openQA) of the
  rolling release.

Deliberately left out of the catalog: distributions with a regional or hardware-specific niche
(Raspbian, Pardus), systemd-less variants that add friction inside a `proot` container that
already doesn't use `systemd` (Artix), and minimalist distributions with no realistic expected
use in this project (Chimera, CRUX, DietPi).
