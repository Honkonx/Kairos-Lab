# Xlorie, Wayland, VNC, proot-distro — aclaraciones y evaluación

## Xlorie no es un proyecto separado

No existe un repositorio independiente llamado "xlorie". **Xlorie es el nombre de la capa DDX
(Device Dependent X, la capa específica de plataforma) escrita para Android, que vive dentro del
repositorio real `termux/termux-x11`** — no es un proyecto aparte. El módulo `x11-server` de
Kairos ya es un fork de ese árbol real (vía un fork intermedio, ver
[X11_EMBEBIDO.md](X11_EMBEBIDO.md)), así que "la versión de Xlorie que usa Kairos" y "la versión
de termux-x11 que usa Kairos" son la misma pregunta. Actualizar el módulo vendorizado a la
versión más reciente sigue bloqueado por el salto de versión mayor de la herramienta de build, la
necesidad de compilar Xorg desde el NDK, y la reescritura de las subclases propias de Kairos — no
se justifica hoy.

## Wayland — sin novedad

No existe soporte real de Wayland/XWayland en ningún proyecto de referencia relevante disponible
— es el mismo servidor termux-x11/Xlorie que Kairos ya usa. La única puerta que queda abierta es
un compositor Wayland minimalista construido desde cero (candidatos de referencia externa: un
compositor liviano tipo `labwc` o una versión recortada de `sway`) — un proyecto de meses, no una
alternativa disponible hoy.

## VNC — TigerVNC confirmado como la elección correcta

Búsqueda de alternativas de VNC livianas para Android/Termux: **TigerVNC sigue siendo la
recomendación estándar de la comunidad para este escenario exacto** (Termux + XFCE). Otras
alternativas de escritorio remoto (RustDesk, AnyDesk, Chrome Remote Desktop) usan protocolos
completamente distintos a VNC/RFB — son suites de escritorio remoto completas y más pesadas
(requieren su propio servidor+relay+cliente propietario), que no encajan con el patrón liviano
que ya usa Kairos (cliente RFB puro en Kotlin + TigerVNC del lado servidor). No hay una
alternativa real mejor.

## proot-distro — versión y hallazgo de vigilancia

Kairos instala `proot-distro` sin fijar ninguna versión específica — siempre usa la versión más
reciente disponible en el repositorio de paquetes de Termux en ese momento.

**Hallazgo real**: el proyecto oficial de proot-distro está trabajando en una **reescritura
completa en Python** de su versión mayor actual — varias versiones alpha publicadas en días
consecutivos en PyPI, señal de desarrollo activo. Está en fase alpha, todavía no en el
repositorio estable de Termux — no representa un riesgo ni una oportunidad inmediata, pero vale
la pena tenerlo anotado como algo a vigilar: si esa reescritura llega a estable y cambia el
mecanismo de instalación/listado/login que los scripts de Kairos ya interpretan analizando la
salida de texto de proot-distro, podría romper esos scripts sin aviso el día que el usuario
actualice el paquete.

## Conclusión

De los cuatro puntos evaluados en esta ronda, tres ya estaban correctamente cerrados por
investigación previa (Xlorie/re-vendorización, Wayland, y en gran parte VNC) — esta investigación
los confirma con fuentes externas sin encontrar nada que cambie esas conclusiones. El único
hallazgo genuinamente nuevo es la reescritura en Python de proot-distro (en fase alpha, a
vigilar, sin acción requerida hoy).
