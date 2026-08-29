# docs/x11/ — Kairos embedded X11 server

This folder documents Kairos's embedded X11 server (based on Xlorie/termux-x11): its
architecture, the module launcher panel available from inside the graphical desktop, and the
findings from technical audits of that infrastructure.

- [X11_EMBEBIDO.md](X11_EMBEBIDO.md) — how the embedded X11 server (Xlorie/termux-x11) actually
  works inside the Kairos APK: viewer, touch input, desktop startup, and the built-in VNC viewer.
- [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md) — design of the application menu accessible from
  inside the graphical X11 desktop, listing installed Kairos modules.
- [AUDITORIA_X11_CODIGO_2026-08-19.md](AUDITORIA_X11_CODIGO_2026-08-19.md) — history of real bugs
  found and fixed in the embedded X11 code.
- [AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md](AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md) —
  synthesis of known open items and evaluation of alternatives (remote access, additional desktop
  environments).
- [AUDITORIA_INFRAESTRUCTURA_X11_2026-08-26.md](AUDITORIA_INFRAESTRUCTURA_X11_2026-08-26.md) —
  audit of the X11 server infrastructure itself (process lifecycle, socket, GPU, security).
- [AUDITORIA_XLORIE_WAYLAND_2026-08-25.md](AUDITORIA_XLORIE_WAYLAND_2026-08-25.md) — clarifies
  what Xlorie actually is, the state of Wayland support, and an evaluation of VNC/proot-distro
  alternatives.
