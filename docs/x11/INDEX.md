# docs/x11/ — Servidor X11 embebido de Kairos

Esta carpeta documenta el servidor X11 embebido de Kairos (basado en Xlorie/termux-x11): su
arquitectura, el panel de módulos accesible desde el escritorio gráfico, y los hallazgos de
auditorías técnicas sobre esa infraestructura.

- [X11_EMBEBIDO.md](X11_EMBEBIDO.md) — mecanismo real del servidor X11 embebido (Xlorie/
  termux-x11) dentro del APK de Kairos: visor, entrada táctil, arranque del escritorio, visor VNC
  propio.
- [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md) — diseño del menú de aplicaciones accesible desde
  dentro del escritorio gráfico X11, con los módulos de Kairos instalados.
- [AUDITORIA_X11_CODIGO_2026-08-19.md](AUDITORIA_X11_CODIGO_2026-08-19.md) — historial de bugs
  reales encontrados y corregidos en el código del X11 embebido.
- [AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md](AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md) —
  síntesis de pendientes conocidos y evaluación de alternativas (acceso remoto, escritorios
  adicionales).
- [AUDITORIA_INFRAESTRUCTURA_X11_2026-08-26.md](AUDITORIA_INFRAESTRUCTURA_X11_2026-08-26.md) —
  auditoría de la infraestructura del servidor X11 en sí (ciclo de vida del proceso, socket, GPU,
  seguridad).
- [AUDITORIA_XLORIE_WAYLAND_2026-08-25.md](AUDITORIA_XLORIE_WAYLAND_2026-08-25.md) — aclaración
  sobre qué es Xlorie realmente, estado de Wayland, y evaluación de alternativas de VNC/
  proot-distro.
