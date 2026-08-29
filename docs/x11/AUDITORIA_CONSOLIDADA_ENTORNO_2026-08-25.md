# Estado consolidado del módulo Entorno (escritorio gráfico X11/VNC)

Este documento resume el estado real, las limitaciones conocidas, y las alternativas evaluadas
para el módulo de escritorio gráfico de Kairos (X11 embebido + VNC + proot-distro), a partir de
varias rondas de auditoría técnica.

## Lo que ya funciona

- Servidor X11 embebido (Xlorie/termux-x11) con entrada táctil y renderizado reales, visor VNC
  propio (protocolo RFB puro en Kotlin, con sincronización de portapapeles bidireccional), tres
  escritorios nativos soportados (XFCE4/MATE/LXQt), soporte de escritorio dentro de una distro
  Linux vía proot, selección de método de GPU (software/Zink/VirGL) propagada correctamente tanto
  al modo nativo como al modo distro, diagnóstico de GPU, arranque automático de escritorio, y
  generación de lanzadores de aplicaciones para los módulos instalados (ver
  [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md)).
- Investigaciones ya cerradas con conclusión clara: no existe soporte real de Wayland/XWayland en
  ningún proyecto de referencia disponible — es el mismo servidor termux-x11/Xlorie que Kairos ya
  usa. Actualizar el módulo vendorizado a la versión más reciente del proyecto upstream está
  bloqueado por un salto de versión mayor de la herramienta de build (Gradle/AGP), por tener que
  compilar Xorg desde NDK (hoy se usa un binario precompilado), y por la reescritura de las
  subclases propias de Kairos — no se justifica el costo.

## Acceso remoto: RDP evaluado y descartado

Se evaluó agregar soporte RDP (xrdp) como alternativa a X11/VNC. Reportes reales de la comunidad
corriendo exactamente ese escenario (Termux + proot + xrdp en Android) documentan latencia peor
que VNC, con el touchpad virtual tardando varios segundos en responder. Kairos ya cubre los dos
mejores caminos reales para este caso de uso (X11 embebido nativo para mejor rendimiento, VNC
como alternativa liviana) — agregar RDP sería estrictamente peor en la práctica según evidencia
de terceros en el mismo escenario. No se recomienda implementarlo.

## Escritorios adicionales

Se evaluó si tendría sentido agregar KDE Plasma Mobile o GNOME como escritorios adicionales. Dado
que el fallback de renderizado por software (llvmpipe) sigue siendo el método activo en
dispositivos sin aceleración GPU dedicada confirmada, y que KDE/GNOME son notoriamente más
pesados en RAM/GPU que los tres escritorios livianos que Kairos ya ofrece (XFCE4/MATE/LXQt,
elegidos precisamente por ser livianos), no hay evidencia de que sumar un cuarto escritorio más
pesado aporte valor real. Si en el futuro se busca una alternativa "moderna" pero liviana, un
compositor Wayland minimalista (ej. `labwc`, una versión recortada de `sway`) sería el candidato
más razonable — pero esto es un proyecto de meses, dado que no existe ninguna base real de
Wayland en el ecosistema actual de referencias del proyecto.

## GPU nativa (Turnip/Panfrost)

Zink (OpenGL sobre Vulkan) y VirGL ya están soportados y correctamente propagados tanto al modo
nativo como al modo distro. El driver Vulkan nativo real para GPUs Adreno (Turnip) o Mali
(Panfrost), sin pasar por la capa de traducción Zink, no está implementado — es trabajo de
integración de drivers Mesa específicos por fabricante de SoC, de esfuerzo alto, sin una solución
rápida disponible.

## Pendientes menores

- Portar las tablas de mapeo de teclado (keycode Android → keysym X11/Unicode) de un proyecto de
  referencia con licencia GPL-3.0 al teclado del visor VNC propio — mejora de bajo riesgo técnico
  pero de tamaño considerable (miles de líneas de tablas), pendiente de decisión sobre
  compatibilidad de licencia.
- Explorar el cliente VNC (RFB 3.8/VeNCrypt) de otro proyecto de referencia — evaluado como
  potencialmente relevante, no explorado en profundidad todavía.
- Cerrar el gap de actualización automática del menú de aplicaciones (regenerar los lanzadores al
  instalar un módulo nuevo, no solo al abrir el escritorio).
- Panel/launcher nativo propio en C/Xlib — descartado como primera opción (ver
  [PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md)), queda en espera de que el menú nativo del
  escritorio resulte insuficiente en la práctica.
