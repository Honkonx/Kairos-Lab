# Xlorie, Wayland, VNC, proot-distro — clarifications and evaluation

## Xlorie is not a separate project

There is no independent repository called "xlorie". **Xlorie is the name of the DDX layer
(Device Dependent X, the platform-specific layer) written for Android, which lives inside the
real `termux/termux-x11` repository** — it's not a separate project. Kairos's `x11-server` module
is already a fork of that real tree (via an intermediate fork, see
[X11_EMBEBIDO.md](X11_EMBEBIDO.md)), so "the version of Xlorie Kairos uses" and "the version of
termux-x11 Kairos uses" are the same question. Updating the vendored module to the latest version
remains blocked by the major build tool version jump, the need to compile Xorg from the NDK, and
the rewrite of Kairos's own subclasses — not justified today.

## Wayland — no news

No real Wayland/XWayland support exists in any relevant reference project available — it's the
same termux-x11/Xlorie server Kairos already uses. The only door left open is a minimal Wayland
compositor built from scratch (external reference candidates: a lightweight compositor like
`labwc` or a trimmed-down `sway`) — a months-long project, not an alternative available today.

## VNC — TigerVNC confirmed as the right choice

A search for lightweight VNC alternatives for Android/Termux: **TigerVNC remains the standard
community recommendation for this exact scenario** (Termux + XFCE). Other remote-desktop
alternatives (RustDesk, AnyDesk, Chrome Remote Desktop) use completely different protocols from
VNC/RFB — they are full, heavier remote-desktop suites (requiring their own
server+relay+proprietary client), which don't fit the lightweight pattern Kairos already uses
(pure Kotlin RFB client + TigerVNC server-side). There's no real better alternative.

## proot-distro — version and a note to watch

Kairos installs `proot-distro` without pinning any specific version — it always uses whatever
version is currently available in Termux's package repository.

**Real finding**: the official proot-distro project is working on a **complete Python rewrite**
of its current major version — several alpha releases published on consecutive days on PyPI,
a sign of active development. It's still in alpha, not yet in Termux's stable repository — it
poses no immediate risk or opportunity, but it's worth noting as something to watch: if that
rewrite reaches stable and changes the install/list/login mechanism that Kairos's scripts already
parse from proot-distro's text output, it could break those scripts without warning the day the
user updates the package.

## Conclusion

Of the four points evaluated in this round, three were already correctly closed by prior
investigation (Xlorie/re-vendoring, Wayland, and largely VNC) — this investigation confirms them
with external sources without finding anything to change those conclusions. The only genuinely
new finding is proot-distro's Python rewrite (alpha stage, to watch, no action needed today).
