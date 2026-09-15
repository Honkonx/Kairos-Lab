# Entorno

**Módulo Kairos** — Gestionado vía la UI de Kairos (tab Módulos). Instalación de la infraestructura base vía `ProcessBuilder` → `modulos/entorno.sh`; toda la operación posterior (distros, escritorios, VNC, GPU) corre **100% nativa en Kotlin**, sin pasar por scripts bash ni terminal (salvo "Login a distro", que abre una consola real a propósito).

---

**App:** Kairos (fork termux-app)
**Script de instalación:** `modulos/entorno.sh`
**Puerto nativo Kotlin:** `app/src/main/java/com/termux/app/util/EntornoNative.kt`
**Fragment:** `EntornoFragment.kt`
**`hasSwitch`:** `false` — sin ON/OFF único (Entorno instala infraestructura; cada componente —X11, VNC, PulseAudio, distro— se enciende/apaga por separado)
**`requiresProot`:** `true`
**Tamaño estimado:** ~200MB · **Tiempo estimado:** ~3 min (solo infraestructura base — instalar una distro/escritorio después toma más)

---

## 1. Qué es

Entorno convierte el teléfono en una "mini PC portátil": permite instalar distros Linux completas (vía `proot-distro`) con escritorio gráfico real (XFCE4/LXQt/MATE/Plasma) mostrado a través del servidor **X11 embebido en el propio APK** (Xlorie, proceso `:xserver` — ver `docs/x11/x11-embedded-architecture.md`), con aceleración GPU detectada automáticamente según el hardware del dispositivo, más VNC como alternativa/respaldo y PulseAudio para audio.

El servidor X va embebido directamente en el APK de Kairos — no depende de ninguna app externa para mostrar el escritorio.

`modulos/entorno.sh` **solo instala la infraestructura base** (proot-distro, udocker, registro del modo X11 embebido, PulseAudio, drivers GPU, y 6 scripts de gestión) — la instalación de una distro específica o un escritorio específico se hace DESPUÉS, desde la propia UI de la app (`EntornoFragment`), no como parte del install inicial.

## 2. Permisos

- Permisos genéricos del wizard de Kairos (almacenamiento).
- No instala ningún APK externo — el servidor X11 (Xlorie) va embebido en el propio Kairos, arranca como proceso Android `:xserver` (`X11Service.kt`) cuando se pide desde la app.
- No requiere root en ningún punto — todo corre vía `proot`/`proot-distro` (namespace de usuario).

## 3. Lógica de instalación (`modulos/entorno.sh`)

Acepta `--silent` (implícito desde la app), `--force`, `--describe`. 8 checkpoints, todos verificados con `command -v`/`dpkg -s` real antes de marcarse (no checkpoints ciegos):

| Paso (checkpoint) | Qué hace |
|---|---|
| `entorno_arch` | Verifica `uname -m` sea `aarch64`/`arm64` — aborta si no (Entorno es ARM64-only) |
| `entorno_pkg_update` | `pkg update` con **fallback de 5 mirrors** (`packages.termux.dev`, `grimler.se`, `mirror.accum.se`, 2 mirrors chinos) — mide la velocidad REAL de cada uno con `curl -w '%{time_total}'` contra `.../dists/stable/InRelease` y usa el más rápido, en vez de probar en orden fijo |
| `entorno_proot_distro` | `pkg install proot-distro` |
| `entorno_udocker` | Descarga `udocker.py` directo del repo oficial (`indigo-dc/udocker`) a `$PREFIX/bin/udocker` — si falla la descarga, solo `warn` y sigue (udocker no es estrictamente necesario para Entorno en sí) |
| `entorno_x11` | Registra el modo "X11 embebido" en el registry (`entorno.x11_mode=embedded`) — no hay APK que descargar, el servidor (Xlorie) ya viene dentro del propio Kairos |
| `entorno_pulse` | `pkg install pulseaudio` + habilita `module-native-protocol-tcp` con ACL `127.0.0.1` en `default.pa` (solo si no está ya) |
| `entorno_gpu` | Ver sección 4 — detección + instalación de driver según SoC |
| `entorno_dirs` | Genera 6 scripts de gestión en `~/scripts/entorno/` (ver sección 6) |
| `entorno_desktop_tools` | Instala tools de escritorio en el HOST (fuera de cualquier distro proot): `xfce4 xfce4-goodies dbus-x11 tigervnc x11vnc pavucontrol`, best-effort (`warn`, no aborta si algún paquete falla). Si el SDK de Android detectado es ≥31, avisa sobre desactivar "Disable phantom process killer" |
| `entorno_ai_tools` | Instala la base para CLIs de IA en el HOST: `python3 nodejs-lts git curl`, también best-effort. Es la base que módulos como OpenCode/Claude Code asumen disponible en el HOST |
| `entorno_registry` | Escribe `entorno.installed=true`, `entorno.gpu`, `entorno.gpu_method`, `entorno.version` |

## 4. Detección de GPU y opciones de driver

`_check_gpu()` lee `getprop ro.board.platform` (no `dmesg`, que requiere root en Android) y clasifica por patrones conocidos:

| SoC detectado | Tipo | Paquetes instalados | Método por defecto |
|---|---|---|---|
| `sm*`/`kona*`/`lahaina*`/`shima*` | Adreno (Qualcomm) | `mesa` + `vulkan-loader-generic` (Zink —driver Gallium software-GL-sobre-Vulkan— viene incluido en el paquete `mesa` normal, no es un paquete separado); además se intenta instalar `mesa-vulkan-icd-freedreno` (ICD Vulkan nativo de Turnip) como opción adicional, si el mirror del dispositivo lo tiene disponible | `zink` (Turnip queda disponible como alternativa configurable manualmente si `mesa-vulkan-icd-freedreno` se instaló bien) |
| `mt*`/`t618*`/`g610*`/`g720*` | Mali (MediaTek) | `mesa` + `virglrenderer-android` + `angle-android` (con symlinks de compatibilidad de `libEGL`/`libGLESv1_CM`/`libGLESv2` si hace falta) | `virgl_angle` |
| `s5e*`/`exynos*` | Xclipse (Samsung Exynos) | `mesa` + `virglrenderer-android` + `angle-android` | `virgl` |
| Cualquier otro (`unknown`) | Genérico | `mesa` (renderizado por software, llvmpipe) | `llvmpipe` |

También se instala `mesa-demos` (`glxinfo`/`glxgears`), usado por el diagnóstico GPU real de la app (`EntornoNative.gpuDiagnostic()`) para reportar el renderer OpenGL efectivo, no solo el método configurado.

El usuario puede cambiar el método manualmente después desde la app (`EntornoFragment` → "⚙ Configurar método GPU" → `EntornoNative.setGpuMethod()`/`gpuMethodOptions()`), sin reinstalar nada.

## 5. Pantalla real de la app (`EntornoFragment.kt`)

**Card ESTADO** (se refresca en cada acción vía `EntornoNative.status()`): GPU, Método, X11 embebido (● Corriendo / ○ Detenido), VNC, PulseAudio, Escritorios instalados.

**Sección CONTENEDORES — proot-distro**:
- 📦 Instalar distro → grid de tiles 2 columnas — `ubuntu`/`debian`/`alpine` confirmadas, `archlinux`/`fedora`/`void` marcadas "experimental" con confirmación extra antes de instalar.
- 🔑 Login a distro → abre una **consola real** dentro del proot elegido vía el overlay de terminal (`proot-distro login <nombre>`).
- 💾 Backup de distro (tar.gz), 🗑 Eliminar distro, 🔗 Vincular `~/scripts` y `~/proyectos` con la distro.
- 📲 Instalar app en distro / 🗑 Eliminar app de distro.

**Sección ESCRITORIO**:
- 🖥 XFCE4 nativo (sin distro) — instalar + iniciar — escritorio XFCE4 directo en Termux, sin pasar por una distro proot.
- ▶ Iniciar escritorio (X11 + DE) — si hay un solo DE instalado lo arranca directo, si hay varios pregunta cuál.
- 🖥 Instalar otro escritorio nativo (LXQt/MATE).
- 🖥 Actualizar lanzadores del escritorio — regenera los `.desktop`.
- 🚀 Configurar autoinicio del escritorio.
- ⏹ Detener escritorio actual (mantiene X11 arriba) — distinto de detener el servidor X11 completo.
- ⏹ Detener servidor X11.

**Sección VNC** (secundario/opcional frente a X11): instalar TigerVNC, iniciar (`:5901`), configurar e iniciar VNC (resolución/calidad/contraseña), detener. El usuario puede conectarse con un cliente VNC externo a `127.0.0.1:5901`, **o con el visor VNC embebido de la propia app** — `VncViewerActivity`/`VncClient.kt` (cliente RFB 3.8 escrito desde cero, ver `docs/x11/x11-embedded-architecture.md`).

**Lanzador "Cerrar sesión"**: `generateDesktopLaunchers()` (`EntornoNative.kt`) crea, junto a los íconos de CLIs y apps de distro, un `.desktop` extra `kairos-cerrar-sesion.desktop` dentro del escritorio que corre `gui_stop.sh` sin `--x11` (mata las sesiones de DE conocidas — xfce4/lxqt/openbox/proot-distro login — pero deja el servidor X11 embebido arriba, para poder reabrir un escritorio sin reiniciar el servidor).

**Sección AUDIO + GPU**: PulseAudio on/off, diagnóstico GPU (renderer OpenGL real + dispositivo Vulkan + drivers instalados, vía `EntornoNative.gpuDiagnostic()`), configurar método GPU manualmente.

Todas las acciones (salvo "Login a distro") van directo por `EntornoNative.kt` — **no hay ProcessBuilder + parseo de stdout desde el Fragment**, es Kotlin nativo llamando a los binarios (`proot-distro`, `pm`, `am`, etc.) directamente y devolviendo `JSONObject`.

## 6. Scripts de gestión generados (`~/scripts/entorno/`)

`entorno.sh` los genera como heredocs durante la instalación — son los que invoca `EntornoNative.kt` en runtime:

| Script | Qué hace |
|---|---|
| `tx11_start.sh` | `am start --user 0 -n com.termux/com.termux.x11.MainActivity` (la Activity visor DENTRO del propio APK Kairos, `sharedUserId=com.termux`) |
| `tx11_stop.sh` | `am broadcast -a com.termux.x11.ACTION_STOP -p com.termux` — cierra el visor embebido |
| `vnc_start.sh` | `vncserver`/`tigervncserver :1 -geometry 1920x1080 -depth 24 -localhost` |
| `vnc_stop.sh` | Mata el servidor VNC + notifica el bridge de eventos (`~/.kairos_events`) |
| `pulse_start.sh` / `pulse_stop.sh` | `pulseaudio --start --exit-idle-time=-1` / `pkill pulseaudio` |
| `gpu_env.sh` | Exporta `GALLIUM_DRIVER`/`MESA_GL_VERSION_OVERRIDE`/etc. según `entorno.gpu_method` leído del registry — se sourcea antes de arrancar cualquier app gráfica |

## 7. Registry

Prefijo `entorno.*`: `installed`, `version`, `install_date`, `gpu`, `gpu_method`.

## 8. Alcance

`EntornoNative.kt` es un puerto de las opciones de mayor valor de un menú TUI bash más amplio de referencia — no una reimplementación 1:1 de cada opción de nicho existente en esa herramienta original.

## Controles de la pantalla

Es el módulo con más controles del proyecto — organizado en secciones por `sectionLabel()`, sin cards separadas para todo.

### Card "ESTADO" (solo lectura)

GPU, método GPU, X11 (corriendo/detenido), VNC (corriendo/detenido/no instalado), PulseAudio, escritorios instalados — refrescado por `refreshStatus()` vía `EntornoNative.status()`, con color verde/gris según esté corriendo o no.

### Card "📋 INSTALADO" (solo lectura)

Inventario de distros `proot-distro` instaladas (`refreshInventory()`). Los contenedores udocker tienen su propia pantalla completa en `UdockerFragment.kt`.

### Sección "NATIVO — X11 + escritorio directo sobre Termux (recomendado)"

| Control | Qué hace | Por qué |
|---|---|---|
| "🖥 XFCE4 nativo (sin distro) — instalar + iniciar" | `promptXfceNative()` — instala xfce4+xfce4-terminal nativos (paquetes `pkg`, sin proot) si faltan, arranca sobre el X11 embebido | Camino de una sola pulsación para la interfaz gráfica nativa |
| "▶ Iniciar escritorio (X11 + DE)" | `promptStartDesktop()` | Arranca el escritorio nativo ya instalado |
| "🖥 Instalar otro escritorio nativo (LXQt/MATE)" | `promptInstallDesktop()` | Para quien no quiere XFCE4 |
| "🖥 Actualizar lanzadores del escritorio" | `runEntornoAction("desktop-launchers")` | `startDesktop()` ya regenera los lanzadores automáticamente al iniciar — este botón es solo para agregar un CLI nuevo sin reiniciar el DE completo |
| "🚀 Configurar autoinicio del escritorio" | `promptAutostart()` — multi-selección de qué CLIs se abren solos al entrar al escritorio | Evaluado vía `EntornoNative.autostartOptions()`/`setAutostart()` |
| "⏹ Detener escritorio actual (mantiene X11 arriba)" | `stopDesktopSessionAction()` | Distinto de "Detener servidor X11" — permite cambiar de camino (nativo↔distro) sin perder el servidor X11 |
| "⏹ Detener servidor X11" | `stopEmbeddedX11()` | Apagado completo de todo (servidor + sesión) |

### Sección "CON DISTRO (proot-distro) — sistema Linux completo, opcional"

| Control | Qué hace | Por qué |
|---|---|---|
| "📦 Instalar distro" | `promptDistroInstall()` | Camino secundario/opcional — para cuando el usuario necesita un sistema aislado real |
| "🖥 Instalar escritorio en distro" | `promptDistroInstallDesktop()` — mismo patrón picker (distro → DE) que el camino nativo | Sin necesidad de correr `distro_setup_gui.sh` a mano por terminal |
| "▶ Iniciar escritorio dentro de la distro" | `promptDistroDesktopStart()` | Comparte el mismo X11 embebido que el camino nativo — solo uno puede estar activo a la vez, enforzado en `EntornoNative` (`desktopModeConflict()`) |
| "🔑 Login a distro (terminal)" | `promptDistroLogin()` | Consola interactiva real dentro del proot |
| "💾 Backup de distro (tar.gz)" | `promptDistroAction("distro-backup", ...)` | — |
| "🗑 Eliminar distro" | `promptDistroRemove()` | — |
| "🔗 Vincular ~/scripts y ~/proyectos" | `promptDistroAction("bridge-mount", ...)` | Comparte carpetas del host dentro de la distro |

### Sección "CON DISTRO — catálogo de apps (apt, opcional)"

| Control | Qué hace | Por qué |
|---|---|---|
| "📲 Instalar app en distro" | `promptDistroAppInstall()` — catálogo curado (`curatedDistroApps`: Firefox, Chromium, GIMP, Inkscape, VLC, LibreOffice, Thunderbird, Blender, OBS Studio, FileZilla) + opción "Otro" texto libre | Sugerencias en vez de exigir el nombre exacto del paquete apt de memoria |
| "🗑 Eliminar app de distro" | `promptDistroAppRemove()` | — |

### Sección "VNC — secundario/opcional"

| Control | Qué hace | Por qué |
|---|---|---|
| "📥 Instalar TigerVNC" | `vncInstallWithProgress()` | — |
| "▶ Iniciar VNC (:5901)" | `runEntornoAction("vnc-start")` | Arranque rápido sin configurar nada |
| "⚙ Configurar e iniciar VNC" | `promptVncConfig()` — resolución, calidad (16/24 bit), checkbox "Pedir contraseña" + campo real de contraseña | Resolución/calidad/contraseña son los 3 parámetros reales que soporta `vncStartWithConfig()` sin romper la alineación con el display embebido `:1` |
| "⏹ Detener VNC" | `runEntornoAction("vnc-stop")` | — |

### Sección "AUDIO + GPU"

| Control | Qué hace | Por qué |
|---|---|---|
| "🔊 PulseAudio — encender/apagar" | `runEntornoAction("pulse-toggle")` | — |
| "🩺 Diagnóstico GPU" | `showGpuDiagnostic()` | — |
| "⚙ Configurar método GPU" | `promptGpuMethod()` — lista de métodos reales según el tipo de GPU detectado (`EntornoNative.gpuMethodOptions()`) | El método correcto depende del hardware real del dispositivo, no es una opción universal |

**Detalle no obvio**: `errorDetail()` (helper compartido por varios flujos de este Fragment) muestra el mensaje corto de error JUNTO CON los últimos 300 caracteres de `output` (salida real de apt-get/pkg dentro del proot), en vez de solo un mensaje corto sin ninguna pista de la causa.
