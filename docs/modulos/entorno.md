# Entorno (Mini PC) — Módulo de Kairos

**Módulo de Kairos** — Gestionado vía la UI de Kairos. Instalación de la infraestructura base vía `ProcessBuilder` → `modulos/entorno.sh`; toda la operación posterior (distros, escritorios, VNC, GPU) corre **100% nativa en Kotlin**, sin pasar por scripts bash ni terminal (salvo "Login a distro", que abre una consola real a propósito).

---

**Proyecto:** Kairos
**Basado en:** el menú TUI bash de entorno del proyecto de scripts original (~25 opciones) — Kairos reimplementa de forma nativa las de mayor valor, no todas
**Script de instalación:** `modulos/entorno.sh` (idéntico a `app/src/main/assets/scripts/entorno.sh`; la cabecera cita v1.4.0 pero `update_registry "1.2.0"` en el paso final escribe 1.2.0 en el registry — inconsistencia de versión)
**Puerto nativo Kotlin:** `app/src/main/java/com/termux/app/util/EntornoNative.kt` (~2451 líneas, ~90 funciones; JSON in/out)
**Fragment:** `EntornoFragment.kt` + 4 tabs (`EntornoNativoTab`, `EntornoDistrosTab`, `EntornoX11Tab`/`EntornoVncTab` agrupados en la pestaña Pantalla, `EntornoSistemaTab`) + `EntornoTabUi.kt` (componentes `rowList/actionRow/switchRow`); `ModuleDetailNavigator.kt:34`
**`modules.json`:** `hasSwitch: false`, `port: ""`, `requiresProot: false`, `arch: "distro"`, `requires: ["proot-distro"]`, `repo: Honkonx/kairos-lab`, `hideFromCatalog: true` (`:601`; no aparece en el catálogo normal, se accede por la pantalla Mini PC / navegación directa)
**Tamaño estimado:** ~200MB · **Tiempo estimado:** ~3 min (solo infraestructura base — instalar una distro/escritorio después toma más)

---

## 1. Qué es y para qué sirve

Entorno convierte el teléfono en una "mini PC portátil": instala la infraestructura base (proot-distro, udocker, PulseAudio, drivers Mesa/GPU, tools de escritorio) y ofrece desde la UI escritorios nativos (XFCE4/LXQt/MATE sobre el servidor **X11 embebido en el propio APK**, Xlorie, proceso `:xserver` — ver `docs/x11/x11-embedded-architecture.md`), distros proot-distro con escritorio (XFCE4/LXQt/MATE/Plasma), VNC (TigerVNC) como alternativa/respaldo, PulseAudio y diagnóstico GPU/audio, con aceleración GPU detectada según el hardware.

`modulos/entorno.sh` **solo instala la infraestructura base** — la instalación de una distro o un escritorio específico se hace DESPUÉS, desde la propia UI.

**Clasificación: MÓDULO FUNCIONAL.** Pantalla propia, estado vivo (card ESTADO, poll cada 3 s, `EntornoFragment.kt:269-280`), servicios que se encienden/apagan por separado (X11, VNC, PulseAudio, sesión de escritorio) y un backend Kotlin grande. Dentro de los tiles hay sub-diálogos (config VNC con resolución/orientación/calidad/contraseña, selector de GPU, autoinicio multi-selección, accesos directos webapp/app/comando, cambio de fondo, editor de scripts).

## 2. Permisos

- Permisos genéricos del wizard de Kairos (almacenamiento).
- No instala ningún APK externo — el servidor X11 (Xlorie) va embebido en el propio Kairos, arranca como proceso Android `:xserver` (`X11Service.kt`) cuando se pide desde la app.
- No requiere root — todo corre vía `proot`/`proot-distro` (namespace de usuario).

## 3. Lógica de instalación (`modulos/entorno.sh`)

Flags (`entorno.sh:93-103`): `--silent` (implícito desde la app), `--force`, `--describe`, `--describe-files`, `diagnose|--diagnose`. Sin variantes (`variants: []`). Solo ARM64.

Checkpoints (`check_done`/`mark_done`), verificados con `command -v`/`dpkg -s` antes de marcarse:

| Paso (checkpoint) | Qué hace |
|---|---|
| `entorno_arch` | Verifica `uname -m` sea `aarch64`/`arm64` — aborta si no |
| `entorno_pkg_update` | `pkg update` con **fallback de 5 mirrors** (`packages.termux.dev`, `grimler.se`, `mirror.accum.se`, 2 mirrors chinos) — mide la velocidad real de cada uno con `curl -w '%{time_total}'` contra `.../dists/stable/InRelease` y usa el más rápido |
| `entorno_proot_distro` | `pkg install proot-distro` |
| `entorno_udocker` | `pip3 install --upgrade udocker` (`entorno.sh:210-224`; sin pin de versión). Si falla, solo `warn` y sigue (udocker no es estrictamente necesario). `--describe-files` conserva un `install_hint` informativo con una URL vieja de `udocker.py` (`:136`, no se ejecuta) |
| `entorno_x11` | Registra el modo "X11 embebido" en el registry (`entorno.x11_mode=embedded`, `x11_display=:1`) — no hay APK que descargar |
| `entorno_pulse` | `pkg install pulseaudio` + modifica `$PREFIX/etc/pulse/default.pa` (`module-aaudio-sink`, `module-native-protocol-tcp auth-ip-acl=127.0.0.1`, `module-sles-source`; `:241-282`) solo si no está ya |
| `entorno_gpu` | Ver sección 4 |
| `entorno_dirs` | Genera los scripts de gestión en `~/scripts/entorno/` (ver sección 7) |
| `entorno_desktop_tools` | Instala tools de escritorio en el HOST (fuera de cualquier distro): habilita `x11-repo` y luego `xfce4 xfce4-goodies tigervnc x11vnc pavucontrol` (sin `dbus-x11`, que no existe en Termux), best-effort (`warn`). Si el SDK de Android es ≥31, avisa sobre desactivar "Disable phantom process killer" (`entorno.sh:1626-1680`) |
| `entorno_ai_tools` | Instala la base para CLIs de IA en el HOST: `python`, `nodejs-lts`, `git`, `curl`, best-effort |
| `entorno_registry` | `registry_install entorno`: `installed=true`, `gpu`, `gpu_method`, `x11_mode=embedded`, `x11_display=:1` |

Al final llama `notify_event "entorno" "install_done" "GPU: ${GPU_TYPE}"` (FIFO `~/.kairos_events`, `ModuleEventBridge.kt`). Instalación desde UI: `installEntornoSilently()` (`EntornoFragment.kt:341`).

- NO usa `verify_binary_installed()` en el flujo principal; los `pkg install ... || warn` de desktop/ai/gpu no abortan: el módulo se marca instalado aunque falten paquetes.
- Sin sha256 en `entorno.sh`. En Kotlin, `tryKaliPrebuilt()` descarga un tar.gz de kairos-lab (`EntornoNative.kt:505-545`) y lo extrae con `tar` sin checksum.

## 4. Detección de GPU y opciones de driver

`_check_gpu()` lee `getprop ro.board.platform` (no `dmesg`, que requiere root en Android 15), con más codenames y fallback a sysfs kgsl (`entorno.sh:173-196`), y clasifica:

| SoC detectado | Tipo | Driver instalado | `GPU_METHOD` |
|---|---|---|---|
| `sm*`/`kona*`/`lahaina*`/`shima*` y similares | Adreno (Qualcomm) | `mesa` + `vulkan-loader-generic` (+ `mesa-vulkan-icd-freedreno` best-effort) (`entorno.sh:283-310`) | `zink` |
| `mt*`/`t618*`/`g610*`/`g720*` | Mali (MediaTek) | `mesa` + `virglrenderer-android` + `angle-android` (con symlinks manuales de `libEGL`/`libGLESv1_CM`/`libGLESv2` si `angle-android` no los deja listos) | `virgl_angle` |
| `s5e*`/`exynos*` | Xclipse (Samsung Exynos) | `mesa` + `virglrenderer-android` + `angle-android` | `virgl` |
| Cualquier otro (`unknown`) | Genérico | `mesa` (softGPU) | `llvmpipe` |

Además instala `mesa-demos` al final. El usuario puede cambiar el método manualmente desde la app ("⚙ Configurar método GPU" → `EntornoNative.setGpuMethod()`/`gpuMethodOptions()`), sin reinstalar nada.

## 5. Ejecución

- No hay proceso único ni puerto propio; `ModuleController.kt` no tiene entrada específica de `entorno`. La operación va por `EntornoNative.kt`.
- Componentes que se arrancan/paran por separado: X11 embebido (`X11Service`, proceso `:xserver`, display `:1`; `EntornoX11Tab.kt:57-66`), escritorio nativo/distro (`startDesktop`/`startDistroDesktop`, `EntornoNative.kt:848/924`; exclusión mutua nativo↔distro vía `desktopModeConflict`, `:185`), VNC (`vncStart/vncStartWithConfig`, `:1538/1607`, display `:5901`), PulseAudio (`pulseToggle`, `:1654`). "Login a distro" abre terminal real (`launchTerminal`). Las sesiones de distro se monitorean con el poll de sesiones tmux/terminal de TermuxService (`EntornoFragment.kt:449-520`).
- Todas las acciones (salvo "Login a distro") van directo por `EntornoNative.kt` — Kotlin nativo llamando a los binarios (`proot-distro`, `pm`, `am`, etc.) y devolviendo `JSONObject`; no hay `ProcessBuilder` + parseo de stdout desde el Fragment.

## 6. Pantalla de la app (4 pestañas: Nativo · Distros · Pantalla · Sistema)

Cards superiores: ESTADO (GPU, método GPU, X11 ● Corriendo/○ Detenido — no hay estado "APK no instalada", el servidor va dentro del propio Kairos —, VNC, PulseAudio, escritorios instalados; se refresca con `EntornoNative.status()`), INSTALADO (inventario de distros `proot-distro`, `refreshInventory()`), SESIONES DE DISTRO, y mantenimiento (actualizar/desinstalar vía `addMaintenanceCard()`).

**Nativo** (`EntornoNativoTab.kt`):

| Control | Qué hace | Por qué |
|---|---|---|
| "🖥 XFCE4 nativo (sin distro) — instalar + iniciar" | `promptXfceNative()` — instala xfce4+xfce4-terminal nativos (paquetes `pkg`, sin proot) si faltan, arranca sobre el X11 embebido | La "interfaz gráfica bionic" recomendada, camino de una sola pulsación |
| "▶ Iniciar escritorio (X11 + DE)" | `promptStartDesktop()` — si hay un solo DE instalado lo arranca directo, si hay varios pregunta cuál | — |
| "🖥 Instalar otro escritorio nativo (LXQt/MATE)" | `promptInstallDesktop()` (única acción con `ProgressDialogController` para la instalación) | Para quien no quiere XFCE4 |
| "🖥 Actualizar lanzadores del escritorio" | `runEntornoAction("desktop-launchers")` — regenera los `.desktop` | `startDesktop()` ya los regenera al iniciar; este botón es para agregar un CLI nuevo sin reiniciar el DE |
| "🚀 Configurar autoinicio del escritorio" | `promptAutostart()` — multi-selección de qué CLIs se abren solos al entrar al escritorio (`EntornoNative.autostartOptions()`/`setAutostart()`) | Concepto "mini PC completa" |
| "⏹ Detener escritorio actual (mantiene X11 arriba)" | `stopDesktopSessionAction()` | Distinto de detener el servidor X11 — permite cambiar de camino (nativo↔distro) sin perder el servidor |
| Cambiar fondo / crear acceso directo (webapp/app Android/comando) | Ver `webapp_launchers.sh` en la sección 7 | — |

**Distros** (`EntornoDistrosTab.kt`): una tarjeta por distro instalada (Login, Iniciar/Instalar escritorio, Cerrar sesión, menú ⋮ de mantenimiento) más perfil recomendado (`recommendedProfile()`):

| Control | Qué hace | Por qué |
|---|---|---|
| "📦 Instalar distro" | `promptDistroInstall()` — grid de tiles de 2 columnas: `ubuntu`/`debian`/`alpine` confirmadas; `archlinux`/`fedora`/`void` marcadas "experimental" con confirmación extra | Camino secundario/opcional para un sistema aislado real |
| "🖥 Instalar escritorio en distro" | `promptDistroInstallDesktop()` — picker distro → DE | — |
| "▶ Iniciar escritorio dentro de la distro" | `promptDistroDesktopStart()` | Comparte el mismo X11 embebido que el camino nativo — solo uno puede estar activo a la vez (`desktopModeConflict()`, `showConflictDialog()`) |
| "🔑 Login a distro (terminal)" | `promptDistroLogin()` — consola real vía `proot-distro login <nombre>` | — |
| Mantenimiento (⋮): backup (tar.gz), restaurar, reiniciar (`distroRestore/Reset`), eliminar, vincular `~/scripts` y `~/proyectos` (`bridge-mount`), cambiar fondo | `promptDistroAction(...)`, `promptDistroRemove()` | Comparte carpetas del host dentro de la distro |
| "📲 Instalar app en distro" | `promptDistroAppInstall()` — catálogo curado (`curatedDistroApps`: Firefox, Chromium, GIMP, Inkscape, VLC, LibreOffice, Thunderbird, Blender, OBS Studio, FileZilla) + opción "Otro" texto libre | Evita que el usuario tenga que escribir el paquete apt de memoria |
| "🗑 Eliminar app de distro" | `promptDistroAppRemove()` | — |

**Pantalla** (X11 y VNC juntos, en filas con descripción):

| Control | Qué hace | Por qué |
|---|---|---|
| Entrar a X11 / Configuración X11 (`KairosX11PreferencesActivity`) / "⏹ Detener servidor X11" | `EntornoX11Tab.kt:29-37`, `stopEmbeddedX11()` | Apagado completo (servidor + sesión) |
| "📥 Instalar TigerVNC" | `vncInstallWithProgress()` | VNC es secundario/opcional frente a X11 |
| "▶ Iniciar VNC (:5901)" | `runEntornoAction("vnc-start")` | Arranque rápido sin configurar nada |
| "⚙ Configurar e iniciar VNC" | `promptVncConfig()` — resolución, orientación, calidad (16/24 bit), checkbox "Pedir contraseña" + campo real de contraseña (arranca vacío; `vncSetPassword()`, `EntornoNative.kt:1593`) | Resolución/calidad/contraseña son los parámetros que soporta `vncStartWithConfig()` sin romper la alineación con el display embebido |
| Abrir visor VNC embebido | `VncViewerActivity`/`VncClient.kt` (cliente RFB 3.8 escrito desde cero; ver `docs/x11/x11-embedded-architecture.md`). También se puede conectar un cliente VNC externo a `127.0.0.1:5901` | — |
| "⏹ Detener VNC" | `runEntornoAction("vnc-stop")` | — |

**Sistema** (`EntornoSistemaTab.kt`): PulseAudio on/off (interruptor), "🩺 Diagnóstico GPU" (renderer OpenGL real + dispositivo Vulkan + drivers instalados, `EntornoNative.gpuDiagnostic()`), benchmark GPU (`gpuBenchmark`), "⚙ Configurar método GPU" (`promptGpuMethod()` — lista de métodos reales según el tipo de GPU detectado), recursos de sesión (`sessionResourceUsage`), editar script (`gui_start.sh`, `gui_stop.sh`, `distro_setup_gui.sh`).

`errorDetail()` (helper compartido por varios flujos) muestra el mensaje corto de error junto con los últimos 300 caracteres de `output` (salida real de apt-get/pkg dentro del proot) en vez de solo un mensaje genérico.

**Lanzador "Cerrar sesión"**: `generateDesktopLaunchers()` (`EntornoNative.kt`) crea, junto a los íconos de CLIs y apps de distro, un `.desktop` extra `kairos-cerrar-sesion.desktop` dentro del escritorio que corre `gui_stop.sh` sin `--x11` (mata las sesiones de DE conocidas — xfce4/lxqt/openbox/proot-distro login — pero deja el servidor X11 embebido arriba). Resuelve el caso de estar DENTRO de XFCE4 con el overlay de terminal escondido sin forma de cerrar la sesión (que de otro modo queda corriendo y drena batería).

## 7. Scripts de gestión generados (`~/scripts/entorno/`)

`entorno.sh` los genera como heredocs (`_create_scripts`, `entorno.sh:522-1585`); son los que invoca `EntornoNative.kt` en runtime: `tx11_start.sh`, `tx11_stop.sh`, `vnc_start.sh`, `vnc_stop.sh`, `pulse_start.sh`, `pulse_stop.sh`, `gpu_env.sh`, `pdrun`, `x11_setup_env.sh`, `gui_start.sh`, `gui_stop.sh`, `distro_setup_gui.sh`, `proot_menu_sync.sh`, `webapp_launchers.sh`.

| Script | Qué hace |
|---|---|
| `tx11_start.sh` | Nombre heredado — hoy hace `am start --user 0 -n com.termux/com.termux.x11.MainActivity` (la Activity visor DENTRO del propio APK, `sharedUserId=com.termux`), no una app externa |
| `tx11_stop.sh` | `am broadcast -a com.termux.x11.ACTION_STOP -p com.termux` — cierra el visor embebido |
| `vnc_start.sh` | `vncserver`/`tigervncserver :1 -geometry 1920x1080 -depth 24 -localhost` (VNC arranca con `-localhost`; se puede iniciar sin contraseña tras confirmar un diálogo, `EntornoVncTab.kt:173-176`) |
| `vnc_stop.sh` | Mata el servidor VNC + notifica el bridge de eventos (`~/.kairos_events`, inline, standalone) |
| `pulse_start.sh` / `pulse_stop.sh` | `pulseaudio --start --exit-idle-time=-1` / `pkill pulseaudio` |
| `gpu_env.sh` | Exporta `GALLIUM_DRIVER`/`MESA_GL_VERSION_OVERRIDE`/etc. según `entorno.gpu_method` leído del registry — se sourcea antes de arrancar cualquier app gráfica |

## 8. Archivos, registry y otros

`~/scripts/entorno/`, `~/.android_server_registry` (claves `entorno.*`: `installed`, `version`, `install_date`, `gpu`, `gpu_method`, `x11_mode`, `x11_display`, `desktop_mode`), `~/.install_entorno_checkpoint`, `~/kairos_logs/x11_diagnose.txt` (diagnóstico), `$PREFIX/etc/pulse/default.pa`, `~/.local/share/applications/kairos-webapp-*.desktop` y `~/Desktop/`. Desde Kotlin: `.kali_prebuilt_download.tar.gz` temporal, backups de distro, lanzadores `.desktop`.

## 9. Notas técnicas

- **`TMPDIR` sin exportar rompe X11 embebido**: libX11 resuelve el socket vía `$TMPDIR/.X11-unix/X<display>`; si `TMPDIR` no está seteado antes de que el código nativo lo lea, el cliente X falla a conectar aunque el socket exista en `$PREFIX/tmp/.X11-unix`. `X11Service.onCreate()` fuerza `TMPDIR` vía `Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)`; también está en `applyTermuxEnv()` (`ProcessBuilderExt.kt`) para el resto de los módulos.
- **`LD_PRELOAD` ausente rompe clientes X11 lanzados desde `EntornoNative.startDesktop()`**: `ManagerNativeUtils.runShell()` invoca `bash -c` (ni login ni interactivo), así que el `LD_PRELOAD` que Termux configura en `/etc/profile`/`bashrc` (`libtermux-exec-ld-preload.so`, remapea `/tmp` → `$PREFIX/tmp`) no se aplica, y libX11 (que resuelve el socket con la ruta hardcodeada `/tmp/.X11-unix/X<n>`) puede fallar. `startDesktop()` exporta `LD_PRELOAD` explícito, scopeado a ese comando (mismo precedente que `modulos/claude.sh`), hace diagnóstico del socket (`[diag] socket X11 presente/ausente`) antes de conectar, y espera hasta 5 s al servidor X11 (proceso Android separado) por fallos intermitentes en dispositivos lentos.
- **Cerrar el servidor X11 mata el proceso `:xserver`**: `CmdEntryPoint.main()` (código nativo de `x11-server/`) corre `Looper.prepare()+Looper.loop()` en un `Thread` separado que `stopService()`/`onDestroy()` no interrumpe. Como `X11Service` corre aislado en su propio proceso (`android:process=":xserver"`), el mecanismo real es matar el proceso completo: `android.os.Process.killProcess(Process.myPid())` al final de `onDestroy()`.
- `infoRow()` devuelve un `LinearLayout` de 2 `TextView` (clave/valor); la card de ESTADO guarda la referencia al 2º hijo (`valueTextView()`) para poder actualizarla.

## 10. Seguridad

- Sin secretos persistentes propios salvo la contraseña VNC: el campo de contraseña de la UI arranca vacío (no se muestra la guardada). Inferencia: los permisos del archivo `~/.vnc/passwd` no están verificados.
- PulseAudio TCP limitado a 127.0.0.1 (`auth-ip-acl`). VNC con `-localhost`.
- Sin `curl | bash` en `entorno.sh`. Sí hay descarga sin checksum del tar de Kali y `pip install udocker` sin pin de versión.
- Inyección: los nombres de distro/paquete pasan a comandos de shell desde diálogos; existe `sanitizeId()` para ids de lanzadores (`EntornoNative.kt:2136`), pero la entrada libre "Otro paquete" de apps de distro no se auditó línea a línea.

## 11. Limitaciones conocidas

- Versión inconsistente: cabecera v1.4.0 vs `update_registry "1.2.0"`.
- Nada de `verify_binary_installed()`; los fallos de `pkg install` se degradan a `warn` y el registry igual queda `installed=true`.
- Tar de Kali descargado/extraído sin checksum.
- Inferencia: no se ha confirmado en dispositivo el estado funcional real de escritorio-en-distro / VNC / GPU en la última revisión del código.

## 12. Fecha de alta

`modulos/entorno.sh`: 2026-07-28.
