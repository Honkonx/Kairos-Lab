# Diseño de la pestaña "Mini PC"

La funcionalidad de escritorio Linux embebido (proot-distro, servidor X11, VNC) vive en una
pestaña propia de la navegación principal, "Mini PC", en vez de estar enterrada como una
tarjeta más del catálogo de módulos. Este documento describe la organización de esa pantalla y
las decisiones de diseño detrás de su estructura.

## Por qué una pestaña dedicada

La navegación inferior de Kairos tiene un límite práctico de elementos visibles simultáneos. En
vez de agregar un ítem más a un límite ya ajustado, la funcionalidad de escritorio se agrupó de
forma coherente: todo lo relacionado con "entrar a un escritorio Linux" (nativo, dentro de una
distro, o vía VNC) pasó a vivir bajo un mismo punto de entrada — la pestaña "Mini PC" — mientras
que la configuración de bajo nivel del servidor X11 (resolución, escala, densidad, orientación)
quedó como una pantalla secundaria de estado y configuración, accesible desde ahí mismo.

## Organización en subpestañas

La pantalla se organiza en cinco categorías, cada una con una grilla de acciones (ícono + texto)
separadas en dos grupos: **Lanzar** (arrancar algo) y **Mantenimiento** (instalar, configurar,
detener).

- **Nativo** — escritorio XFCE4 corriendo directamente sobre Termux, sin distro intermedia:
  instalación e inicio, configuración de autoinicio, instalación de escritorios adicionales
  (LXQt/MATE), actualización de lanzadores, y detención del escritorio (sin apagar el servidor
  X11 subyacente).
- **X11** — la capa de infraestructura compartida: entrar al visor del servidor X11 embebido,
  configuración del servidor (resolución, escala, teclado), y detener el servidor por completo.
  Esta capa se separó explícitamente de "Nativo" porque el mismo servidor X11 es compartido por
  el modo nativo, el modo distro, y a futuro por capas de compatibilidad adicionales — no es una
  acción exclusiva del escritorio nativo.
- **Distros** — instalación y eliminación de distribuciones Linux, acceso por terminal, instalar
  o eliminar un entorno de escritorio dentro de una distro, arranque de ese escritorio, backup de
  la distro completa, y el puente de carpetas que vincula proyectos entre el host y la distro.
- **VNC** — instalación de TigerVNC, arranque simple o con configuración avanzada, apertura del
  visor incluido en la app, y detención del servidor VNC.
- **Sistema** — control de PulseAudio, diagnóstico de GPU, y selección del método de
  renderizado acelerado.

Cada tile muestra un indicador visual de estado ("corriendo") cuando corresponde, basado
únicamente en señales reales confirmadas por el estado del módulo — nunca un indicador
fabricado sin una fuente de verdad real detrás.

Por encima de las subpestañas, una tarjeta de estado resume el método de GPU activo, si el
servidor X11/VNC/PulseAudio están corriendo, y qué escritorios están instalados; por debajo,
una tarjeta de mantenimiento cubre las acciones sobre el módulo Entorno en sí (actualizar,
desinstalar).

## Fusión de X11 y VNC dentro de Mini PC

Antes de este diseño, el control del servidor X11 y el visor VNC vivían en una pantalla
independiente, separada del resto del flujo de escritorio. Como el camino real de "entrar a un
escritorio" para la mayoría de los usuarios pasa por Entorno (instalar/arrancar) y no por un
control aislado del servidor, esas acciones se fusionaron dentro de la misma pestaña Mini PC,
eliminando una pantalla redundante y unificando el punto de entrada.

## Catálogo de distribuciones soportadas

El catálogo de distribuciones disponibles incluye las bases más comunes (Ubuntu, Debian, Alpine,
Arch Linux, Fedora, Void, Kali) más tres agregadas para ampliar la variedad de gestores de
paquetes y ciclos de vida disponibles:

- **Manjaro** — Arch curado/estable, con ventana de retraso de paquetes y su propio canal de
  pruebas, para quien quiere paquetes recientes sin el riesgo de un rolling release puro.
- **Rocky Linux** — clon de RHEL con ciclo de vida de soporte extendido, útil para contenedores
  que corren un servicio persistente sin cambios disruptivos frecuentes.
- **openSUSE Tumbleweed** — única distribución del catálogo con gestor de paquetes RPM/zypper
  fuera de la familia basada en RHEL, con tooling propio y testing automatizado del rolling
  release.

Quedaron deliberadamente fuera del catálogo distribuciones de nicho regional o de hardware
específico (Raspbian, Pardus), variantes sin `systemd` que agregan fricción dentro de un
contenedor `proot` que ya no usa `systemd` de por sí (Artix), y distribuciones minimalistas sin
uso esperado real dentro de este proyecto (Chimera, CRUX, DietPi).
