<p align="center">
  <img src="./art/ic_launcher2.png" width="120" alt="Logo de Kairos">
</p>

<h1 align="center">Kairos</h1>

<p align="center">
  <a href="./LICENSE.md"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="#requisitos-mínimos"><img src="https://img.shields.io/badge/platform-Android%20ARM64-3ddc84.svg" alt="Platform"></a>
  <a href="#estado-del-proyecto"><img src="https://img.shields.io/badge/status-beta%20temprana-orange.svg" alt="Status"></a>
  <a href="#créditos-y-agradecimientos-a-terceros"><img src="https://img.shields.io/badge/hecho%20con-Termux%20%2B%20Kotlin-informational.svg" alt="Hecho con"></a>
  <img src="https://img.shields.io/badge/hobby-🔥%20activo-red.svg" alt="Hobby activo">
</p>

<p align="center"><strong>🚀 Un solo APK para tener IA local y un stack completo de desarrollo en tu Android, sin root y sin salir de la app.</strong></p>

Fork de [termux-app](https://github.com/termux/termux-app) con una interfaz nativa completa — nada de escribir comandos a mano salvo que quieras. Todo se instala, se enciende y se apaga tocando la pantalla.

## Estado del proyecto 🚧

> **⚠️ Proyecto en desarrollo activo.** Kairos todavía está en fase de pruebas — puede tener
> errores, comportamiento inconsistente entre dispositivos, y módulos que funcionan mejor que
> otros. No lo trates como software terminado ni "listo para producción". Es un proyecto
> personal que se desarrolla como hobby, en el tiempo libre del autor — no hay un cronograma
> fijo ni un equipo detrás. Si algo falla, [abrí un issue](../../issues); los reportes reales
> son la forma más útil de ayudar en esta etapa.

Kairos es el sucesor de [**termux-ai-stack**](https://github.com/Honkonx/termux-ai-stack) — el
mismo stack de IA local + herramientas de desarrollo, pero empezó como una colección de scripts
bash para Termux normal. Kairos lo lleva un paso más allá: motor + interfaz nativa en un solo
APK, sin tener que memorizar comandos ni copiar instrucciones desde un README.

## Sobre este proyecto

Kairos se desarrolla como un proyecto de código abierto asistido por IA — el trabajo de
programación se hace en pareo con [Claude Code](https://claude.com/claude-code) y
[OpenCode](https://opencode.ai/), usados en distintas sesiones/máquinas sobre el mismo
repositorio. El diseño y las decisiones de producto son del autor; los asistentes de IA
implementan, investigan y documentan bajo su dirección.

## Qué puede hacer 🔥

🧠 **IA local, sin nube.** llama.cpp viene compilado nativo (NDK, con aceleración Vulkan)
dentro del propio APK — no hay que instalar nada aparte. Se expone como servidor HTTP en el
puerto **8085**, compatible con cualquier cliente que hable la API de OpenAI/llama.cpp.
Ollama también está disponible como alternativa (puerto 11434), con su propio catálogo de
modelos para descargar desde la app.

🤖 **Agentes de IA de línea de comandos, con interfaz propia.** Claude Code, Codex, OpenCode,
Antigravity, y una decena más de CLIs de IA — cada uno con login, prompt directo y gestión de
proyectos desde botones, no desde la terminal.

⚡ **Herramientas sin necesidad de un contenedor Linux completo.** Varios módulos (compiladores
de agentes, herramientas de IA) vienen parcheados para correr directo sobre el propio Termux
usando sus binarios glibc reales — sin tener que instalar y arrancar una distro Linux entera
(proot-distro) solo para ejecutarlos. Más liviano y más rápido de abrir.

🔄 **n8n, a tu elección.** Automatización de workflows con dos formas de correrlo: dentro de una
distro Linux completa (proot-distro) o en un contenedor rootless más liviano (udocker) — vos
elegís según lo que necesites.

🔐 **Acceso remoto real — el teléfono como servidor.** SSH con panel de seguridad propio (clave
pública obligatoria opcional, igual que un VPS), soporte Mosh para que la sesión sobreviva
cambios de red, y exposición pública vía túnel (Cloudflare/ngrok) sin necesitar IP fija.

🛡️ **Ciberseguridad.** Kit de herramientas de red y auditoría (nmap, nikto, dirb, sqlmap,
theHarvester) corriendo nativo con salida parseada en la interfaz, más un nivel Pro con
**Kali Linux completo** instalado como distro, con su catálogo real de herramientas.

🏪 **Tienda de módulos.** Catálogo de todo lo instalable — se busca, se instala y se activa con
un toque. Nada de copiar comandos de instalación desde un manual.

🖥️ **Escritorio Linux completo, embebido.** Servidor X11 propio corriendo dentro del teléfono
(sin depender de ninguna app externa) — XFCE4/MATE con aceleración por GPU, tanto en modo
nativo como dentro de una distro completa, o un visor VNC como alternativa si preferís esa vía.
Es una computadora Linux de verdad, dentro del celular.

💻 **El teléfono como mini-PC.** Distros Linux completas con su propio escritorio gráfico,
gestión de proyectos reales, servidores corriendo en segundo plano — pensado para que el
teléfono reemplace a una PC chica para desarrollo, no solo para probar cosas.

🧪 **Entornos de prueba automáticos.** Le apuntás a la carpeta de un proyecto (Node, Python,
PHP...) y la app detecta sola qué stack usa, instala lo que hace falta y lo corre — con la
opción de exponerlo a internet con un túnel, sin salir de la app.

🍷 **A futuro: Windows en el teléfono.** Está planeado sumar Wine con FEXCore y DXVK para poder
correr programas de Windows directamente en Android.

## Estructura del repo

| Carpeta | Qué encontrás ahí |
|---|---|
| [`app/`](./app/) | Código fuente de la app — engine Termux (Java) + UI nativa (Kotlin) |
| [`modulos/`](./modulos/) | Scripts de instalación de cada módulo (Ollama, Claude Code, n8n, Ciberseguridad, etc.) — uno por módulo, sin prefijo |
| [`x11-server/`](./x11-server/) | Servidor X11 embebido (fork de Xlorie/termux-x11) — el escritorio Linux del punto anterior |
| [`llama-engine/`](./llama-engine/) | Módulo NDK de llama.cpp — el motor de IA local |
| [`terminal-emulator/`](./terminal-emulator/) / [`terminal-view/`](./terminal-view/) | Motor de terminal (heredado de termux-app) |
| [`tools/`](./tools/) | Scripts de build y empaquetado del rootfs embebido |
| [`.github/workflows/`](./.github/workflows/) | Pipelines de CI — compilan el APK en GitHub Actions |

## Requisitos mínimos 📋

- 📱 Android 8.0 o superior (ARM64)
- 🧠 **RAM:** 4GB como mínimo aceptable, 8GB o más recomendado — especialmente en Android, donde
  el propio sistema ya consume una parte importante antes de que Kairos arranque nada
- 💾 **Almacenamiento:** 4GB libres como mínimo para empezar; con todos los módulos instalados
  el uso puede llegar a ~30GB, más lo que sumen los modelos LLM que descargues aparte

## Capturas

<p align="center">
  <img src="./art/screenshots/modulos.jpg" width="260" alt="Pantalla de módulos">
  <img src="./art/screenshots/chat-ia.jpg" width="260" alt="Chat IA - elegir motor">
  <img src="./art/screenshots/mas.jpg" width="260" alt="Menú Más">
</p>

## ¿Sigue siendo Termux? 🖥️

Sí — Kairos es un fork directo de [termux-app](https://github.com/termux/termux-app), no una
capa aparte sobre él. Mantiene el mismo paquete de Android (`com.termux`) y el mismo motor de
terminal, así que la terminal completa sigue ahí (FAB flotante para mostrarla/ocultarla) para
quien quiera usar comandos a mano. Podés instalar paquetes con `pkg`, correr scripts, usar `ssh`,
todo lo que ya conocés de Termux normal — la interfaz nativa (módulos, botones, pantallas) es la
forma recomendada de usar Kairos día a día, pero la terminal nunca se esconde ni se reemplaza,
queda como vía de acceso directo para quien la prefiera.

⚠️ Por compartir el mismo paquete `com.termux`, Kairos y Termux original **no pueden coexistir**
instalados en el mismo dispositivo — instalar uno reemplaza al otro.

## Créditos y agradecimientos a terceros 🙏

Kairos es un fork de [termux-app](https://github.com/termux/termux-app) — ver
[`LICENSE.md`](./LICENSE.md) para los términos exactos que aplican a ese código.

El desarrollo de módulos y features se apoya en el análisis constante de proyectos externos
open source (nunca vendorizados, solo consultados para investigación/patrones). Gracias a
todos ellos — estos son los que más aportaron:

| Proyecto | Aporte a Kairos |
|---|---|
| [termux/termux-app](https://github.com/termux/termux-app) | Motor base — sesiones, terminal, utilidades compartidas |
| [termux/termux-x11](https://github.com/termux/termux-x11) | Servidor X11 embebido |
| [afeimod/linbox](https://github.com/afeimod/linbox) | Fuente real del módulo `x11-server` (fork de Xlorie/termux-x11) — árbol `com.termux.x11.*` y `libXlorie.so` precompilado |
| [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop) | Storage compartido, catálogo de apps por distro, aceleración GPU |
| [LinuxDroidMaster/Termux-Desktops](https://github.com/LinuxDroidMaster/Termux-Desktops) | Cierre de sesión del escritorio, arranque por distro+entorno gráfico |
| [DevCoreXOfficial/core-termux](https://github.com/DevCoreXOfficial/core-termux) | Mapeo real de instaladores/desinstaladores para decenas de módulos (lenguajes, CLIs de IA, herramientas) |
| [cactus-compute/needle](https://github.com/cactus-compute/needle) | Modelo de tool-calling ultra-liviano (motor de `cactus`) |
| [GlassHaven/Haven](https://github.com/GlassHaven/Haven) | Patrón de sesiones SSH resilientes a cambios de red (Mosh) |
| [mithun50/openclaw-termux](https://github.com/mithun50/openclaw-termux) | Apagado con gracia (SIGTERM antes de SIGKILL) de procesos en background |
| [Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) | Memoria persistente entre agentes de IA |
| [ivam3/i-Haklab](https://github.com/ivam3/i-Haklab) | Base de varios módulos de agentes de IA y herramientas de ciberseguridad |
| [Hope2333](https://github.com/Hope2333) | Fork de OpenCode para Termux/Android usado como base del módulo `opencode` de Kairos |

## Licencia

GPLv3 — heredada de termux-app, ver [`LICENSE.md`](./LICENSE.md). Excepción: el código del
terminal (basado en [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)) está bajo Apache 2.0.
