# Consolidated status of the graphical desktop module (X11/VNC)

This document summarizes the real status, known limitations, and evaluated alternatives for
Kairos's graphical desktop module (embedded X11 + VNC + proot-distro), based on several rounds of
technical audit.

## What already works

- Embedded X11 server (Xlorie/termux-x11) with real touch input and rendering, a custom VNC
  viewer (pure Kotlin RFB protocol, with bidirectional clipboard sync), three supported native
  desktop environments (XFCE4/MATE/LXQt), desktop support inside a Linux distro via proot, GPU
  method selection (software/Zink/VirGL) correctly propagated to both native and distro modes,
  GPU diagnostics, automatic desktop startup, and generation of application launchers for
  installed modules (see [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md)).
- Investigations already closed with a clear conclusion: no real Wayland/XWayland support exists
  in any available reference project — it's the same termux-x11/Xlorie server Kairos already
  uses. Updating the vendored module to the latest version of the upstream project is blocked by
  a major build-tool version jump (Gradle/AGP), by having to compile Xorg from the NDK (a
  precompiled binary is used today), and by having to rewrite Kairos's own subclasses — the cost
  isn't justified.

## Remote access: RDP evaluated and dropped

Adding RDP (xrdp) support as an alternative to X11/VNC was evaluated. Real community reports
running exactly this scenario (Termux + proot + xrdp on Android) document worse latency than VNC,
with the virtual touchpad taking several seconds to respond. Kairos already covers the two best
real paths for this use case (native embedded X11 for best performance, VNC as a lightweight
alternative) — adding RDP would be strictly worse in practice based on third-party evidence in
the same scenario. It is not recommended.

## Additional desktop environments

Whether it would make sense to add KDE Plasma Mobile or GNOME as additional desktops was
evaluated. Given that the software rendering fallback (llvmpipe) remains the active method on
devices without confirmed dedicated GPU acceleration, and that KDE/GNOME are notoriously heavier
on RAM/GPU than the three lightweight desktops Kairos already offers (XFCE4/MATE/LXQt, chosen
precisely for being lightweight), there's no evidence that adding a fourth, heavier desktop would
provide real value. If a "modern" but lightweight alternative is wanted in the future, a minimal
Wayland compositor (e.g. `labwc`, a trimmed-down `sway`) would be the most reasonable candidate —
but this is a months-long project, since no real Wayland foundation exists in the project's
current reference ecosystem.

## Native GPU (Turnip/Panfrost)

Zink (OpenGL over Vulkan) and VirGL are already supported and correctly propagated to both native
and distro modes. The real native Vulkan driver for Adreno GPUs (Turnip) or Mali GPUs (Panfrost),
bypassing the Zink translation layer, is not implemented — it's SoC-vendor-specific Mesa driver
integration work, high effort, with no quick win available.

## Minor open items

- Porting keyboard mapping tables (Android keycode → X11/Unicode keysym) from a GPL-3.0-licensed
  reference project to the custom VNC viewer's keyboard — a technically low-risk but sizeable
  improvement (thousands of lines of tables), pending a decision on license compatibility.
- Exploring the VNC client (RFB 3.8/VeNCrypt) from another reference project — flagged as
  potentially relevant, not explored in depth yet.
- Closing the gap in automatic application-menu refresh (regenerating launchers when a new module
  is installed, not only when the desktop opens).
- A custom native C/Xlib panel/launcher — dropped as a first option (see
  [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md)), kept on hold pending evidence that the desktop's
  native menu is insufficient in practice.
