# Investigación: homelab, proot-distro/escritorios y Shizuku (2026-10-09)

> Basada en búsquedas web (fuentes abajo) y en lo ya verificado en Kairos.
> Las fuentes de blog son secundarias: lo que no es oficial se marca. Nada de esto se probó en el dispositivo.

## 1. Qué es un homelab

Un homelab es un servidor casero (un mini PC, un NAS, una Raspberry Pi…) donde se alojan servicios propios.
Según los tutoriales consultados, la forma típica es: una máquina con un hipervisor (**Proxmox VE**) que corre
máquinas virtuales o contenedores LXC, **Docker** encima, **Portainer** para gestionar contenedores y un
panel tipo **Homepage** (puerto 3000 por defecto) como página de inicio, con autenticación y descubrimiento
automático de servicios. Referencias de recursos: unos 512 MB de RAM mínimo y ~2 GB recomendados para
Portainer + contenedores; una estimación propia razonable de partida es 8 GB de RAM y SSD (no sale de las
fuentes).

Fuentes: [Portainer en Proxmox LXC](https://oneuptime.com/blog/post/2026-03-20-portainer-proxmox-lxc/markdown),
[Proxmox + Docker en casa](https://dev.to/devopsbg/home-devops-solution-proxmox-and-docker-for-enterprise-home-lab-212i),
[guía de Homepage 2.2](https://aicybr.com/blog/homepage-2-2-self-hosting-dashboard-guide) (fuente única de
terceros: contrastar con la oficial).

### Qué significa para Kairos (criterio propio, no verificado en uso)

- Un teléfono **no** es un servidor con hipervisor: sin root no hay Docker real ni systemd (ver §2). Kairos
  hace de homelab con **procesos de Termux + proot + un panel**; el equivalente a "contenedores" son los
  módulos y las distros proot.
- Piezas de panel que ya existen: Homelab (anillos, servicios, automatizaciones, SSH) y la web de control.
  Las ideas de otros paneles (manifiesto de apps, widgets JSON, SSE, categorías) son una línea de trabajo posible.
- Docker es **un módulo que requiere root**, no un complemento (decisión del proyecto).

## 2. proot-distro, entornos gráficos nativos y dentro de distro

Hallazgos de la búsqueda (comunidad; contrastar con la documentación oficial de Termux):

- proot intercepta cada llamada al sistema con **ptrace**; un desarrollador midió `fstatat` de 2,75 µs a
  304 µs bajo proot (110×). Por eso los escritorios pesados van lentos en proot.
- El filtro **seccomp** de Android bloquea llamadas al sistema; Wine en Termux choca con él de inmediato.
- Limitaciones habituales de una distro proot sin root: **sin systemd, sin GPU real (sin virglrenderer), sin
  Docker**.
- **Phantom process killer** (Android 12+): puede matar procesos hijos ("signal 9"); se recomienda
  desactivarlo o activar "Disable child process restrictions" (Android 14+).
- Un escritorio **nativo de Termux con Termux:X11** es más rápido que uno dentro de proot; la combinación
  habitual es escritorio en proot mostrado por X11 o VNC.
- Existe un advisory sobre `proot-distro` 5.0.2 (validación de hardlinks al restaurar backups): solo restaurar
  archivos de confianza y mantener el paquete actualizado.

Fuentes: [proot-distro issue 526](https://github.com/termux/proot-distro/issues/526),
[FEX-Emu en Termux](https://wiki.fex-emu.com/index.php/Termux),
[proroot (sin ptrace)](https://news.hada.io/topic?id=28342),
[termux-desktop](https://github.com/sabamdarif/termux-desktop),
[Debian+XFCE con proot-distro](https://gituser.codeberg.page/posts/2024/10/install-debian-with-xfce-on-termux-using-proot-distro/).

Lo verificado **en el dispositivo de Kairos** incluye, por ejemplo, que `gtk-update-icon-cache` tarda más de
9 minutos bajo proot (con solución conocida).

## 3. Shizuku

Qué es (README oficial de Shizuku y manual, vía búsqueda): un servidor Java privilegiado que arranca con la
identidad del **shell de ADB** (uid 2000) y expone APIs del sistema por Binder a otras apps. **No es root**:
tiene los permisos limitados del shell. Se inicia por **depuración inalámbrica** (Android 11+, sin PC) o por
ADB desde un PC (antes de Android 11); en dispositivos sin root hay que repetir el arranque tras cada reinicio.
`rish` es un script que Shizuku coloca para ejecutar comandos en Termux a través de ese servidor
(`rish -c "comando"` ⇒ uid 2000). Fuentes: [Shizuku (README)](https://github.com/RikkaApps/Shizuku/blob/master/README.md),
[manual](https://shizuku.rikka.app/guide/setup/), [termux-shizuku-tools](https://github.com/jecis-repos/termux-shizuku-tools)
(comunitario). Licencia de Shizuku: **no verificada** aquí.

### Cómo encajaría en Kairos (propuesta, nada implementado)

Se planteó "controlar Android por completo por SSH" con librerías del SDK. Camino realista:

1. **SSH a Termux ya existe** (Homelab → "Acceso SSH a Termux", puerto 8022, `RemoteManager`).
2. **Shizuku/rish** daría comandos con identidad de shell (`pm`, `settings`, `input`, `am`, `cmd`) desde ese
   SSH — sin root, con las limitaciones del shell de ADB y dependiente de que el usuario lo inicie.
3. **Kairos como cliente**: integrar la API de Shizuku (dependencia Android) para pedir el permiso y
   ejecutar acciones desde la app, sin pasar por la terminal.
4. **Seguridad:** exponer "todo Android" por SSH amplía mucho el riesgo; hace falta lista blanca de acciones,
   confirmaciones y un interruptor visible. Esto es diseño pendiente de aprobación.

Pendiente de verificar: compatibilidad con `targetSdk 28` de Kairos, el paquete real de `rish` en Termux y su
licencia, y si el dispositivo de pruebas tiene depuración inalámbrica.
