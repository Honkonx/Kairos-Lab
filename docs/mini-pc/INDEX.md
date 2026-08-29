# docs/mini-pc/ — Kairos como "mini PC" (escritorio Linux embebido)

Esta carpeta documenta la funcionalidad de Kairos que convierte el teléfono en un mini PC /
homelab de bolsillo: gestión de distribuciones Linux vía `proot-distro`, un servidor X11
embebido propio, visor VNC, aceleración por GPU para el entorno de escritorio, y expansión
homelab (almacenamiento compartido, catálogo de apps, notificaciones).

- [ARQUITECTURA_ENTORNO_GRAFICO.md](ARQUITECTURA_ENTORNO_GRAFICO.md) — arquitectura real del
  módulo Entorno: distribuciones Linux vía `proot-distro`, selección de método de GPU,
  exclusividad entre escritorio nativo y escritorio dentro de una distro, y los bugs reales de
  esta capa (junto con sus correcciones) documentados con evidencia técnica.
- [ENTORNOS_GRAFICOS_EN_DISTROS.md](ENTORNOS_GRAFICOS_EN_DISTROS.md) — diseño del mecanismo que
  levanta un entorno de escritorio (XFCE4/LXQt/MATE) dentro de una distro proot sobre el mismo
  servidor X11 embebido, mecanismo de wallpaper por entorno de escritorio, catálogo de
  aplicaciones recomendado, y el patrón de instalación por niveles para paquetes pesados.
- [MINIPC_TAB.md](MINIPC_TAB.md) — diseño de la pestaña "Mini PC" de la navegación principal:
  organización en subpestañas (Nativo, X11, Distros, VNC, Sistema), grilla de acciones con
  indicadores de estado en vivo, y las decisiones de UX detrás de la fusión de X11 y VNC dentro
  de un mismo punto de entrada.
- [NAVEGACION_MINIPC.md](NAVEGACION_MINIPC.md) — patrones de navegación evaluados para la
  sección "Más" del menú inferior (bottom sheet, drawer clásico, riel lateral) y la decisión de
  diseño adoptada.
- [PLAN_EXPANSION_HOMELAB.md](PLAN_EXPANSION_HOMELAB.md) — hoja de ruta de Kairos como homelab
  de bolsillo: qué ya cubre, qué falta, y la lista de mejoras propuestas con su prioridad.
