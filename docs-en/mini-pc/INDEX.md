# docs/mini-pc/ — Kairos as a "mini PC" (embedded Linux desktop)

This folder documents the Kairos feature that turns the phone into a pocket mini PC / homelab:
Linux distribution management via `proot-distro`, a self-contained embedded X11 server, a VNC
viewer, GPU acceleration for the desktop environment, and homelab expansion (shared storage,
app catalog, notifications).

- [ARQUITECTURA_ENTORNO_GRAFICO.md](ARQUITECTURA_ENTORNO_GRAFICO.md) — real architecture of the
  Entorno module: Linux distributions via `proot-distro`, GPU method selection, exclusivity
  between the native desktop and a desktop running inside a distro, and the real bugs found in
  this layer (with their fixes), documented with technical evidence.
- [ENTORNOS_GRAFICOS_EN_DISTROS.md](ENTORNOS_GRAFICOS_EN_DISTROS.md) — design of the mechanism
  that boots a desktop environment (XFCE4/LXQt/MATE) inside a proot distro on top of the same
  embedded X11 server, the wallpaper mechanism per desktop environment, the recommended app
  catalog, and the staged-install pattern for heavy packages.
- [MINIPC_TAB.md](MINIPC_TAB.md) — design of the "Mini PC" tab in the main navigation:
  organization into sub-tabs (Native, X11, Distros, VNC, System), the action grid with live
  status indicators, and the UX decisions behind merging X11 and VNC into a single entry point.
- [NAVEGACION_MINIPC.md](NAVEGACION_MINIPC.md) — navigation patterns evaluated for the "More"
  section of the bottom menu (bottom sheet, classic drawer, side rail) and the design decision
  taken.
- [PLAN_EXPANSION_HOMELAB.md](PLAN_EXPANSION_HOMELAB.md) — roadmap for Kairos as a pocket
  homelab: what it already covers, what's missing, and the list of proposed improvements with
  their priority.
