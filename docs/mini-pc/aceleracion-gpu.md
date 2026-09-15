# Aceleración de GPU en Mini PC

La aceleración de GPU es lo que hace que un escritorio gráfico dentro de Kairos (nativo o con
distro) se sienta usable en vez de lento — sin ella, todo el renderizado cae a software puro.
Este documento resume cómo Kairos detecta y configura la GPU para Mini PC; el detalle completo
de checkpoints de instalación vive en `../modulos/entorno.md` (sección "Detección de GPU y
opciones de driver") — acá se cubre el nivel arquitectónico: por qué existe un driver distinto
por fabricante y cómo se aplica al escritorio.

## 1. Detección automática por SoC

Kairos identifica el fabricante del chip gráfico del dispositivo (Adreno de Qualcomm, Mali de
MediaTek, Xclipse de Samsung Exynos, o genérico si no reconoce el modelo) y instala el driver
Mesa correspondiente automáticamente, sin que el usuario tenga que saber qué GPU tiene su
teléfono. Tabla completa de SoCs, paquetes y método por defecto: `../modulos/entorno.md` sección
4.

| Fabricante | Método de aceleración por defecto |
|---|---|
| Adreno (Qualcomm) | Zink (driver Gallium software-GL-sobre-Vulkan) — Turnip (Vulkan nativo) disponible como alternativa configurable |
| Mali (MediaTek) | VirGL + ANGLE |
| Xclipse (Samsung Exynos) | VirGL |
| Genérico / no reconocido | Renderizado por software (llvmpipe) |

## 2. Cómo se aplica al escritorio

El método elegido (automático o manual) se traduce en variables de entorno de Mesa
(`GALLIUM_DRIVER`, `MESA_GL_VERSION_OVERRIDE`, y otras específicas por método) que se exportan
antes de arrancar cualquier aplicación gráfica — tanto en el camino nativo como dentro de una
distro (`proot-distro`), donde las mismas variables se reenvían al entorno del login de la
distro.

## 3. Configuración manual y diagnóstico

El usuario puede cambiar el método de GPU manualmente en cualquier momento desde la app, sin
reinstalar nada — útil si el método automático no da el mejor resultado en un dispositivo
particular. La app también incluye un diagnóstico de GPU real (no solo el método configurado):
reporta el renderer OpenGL efectivo y el dispositivo Vulkan detectado, usando herramientas reales
(`glxinfo`/`glxgears`) instaladas junto con el resto de los paquetes de GPU.

## Ver también

- `../modulos/entorno.md` — tabla completa de detección de GPU, paquetes instalados por
  fabricante y controles de la pantalla.
- `arquitectura-mini-pc.md` — cómo la aceleración de GPU se coordina con el servidor X11 y con
  proot-distro dentro de la arquitectura general de Mini PC.
