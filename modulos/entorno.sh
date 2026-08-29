#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · entorno.sh (silent mode)
#  Instalador base del módulo Entorno — adaptado de
#  termux-ai-stack-dev/scripts/install_entorno.sh v1.1.0
#
#  Instala dependencias base + mejoras mini-PC (no instala distros ni DEs
#  dentro de distros — esas se eligen con el menú interactivo de
#  termux-ai-stack o con los scripts generados gui_start.sh /
#  distro_setup_gui.sh, ver terminalCommand="menu" en modules.json):
#    - proot-distro + udocker (contenedores)
#    - pulseaudio (audio)
#    - Drivers GPU según hardware (Adreno/Mali/Xclipse/genérico)
#    - Tools de escritorio en el HOST (best-effort): xfce4 xfce4-goodies
#      tigervnc x11vnc pavucontrol — dbus-launch ya viene con el paquete "dbus"
#      en Termux, "dbus-x11" (nombre de Debian/Ubuntu) no existe acá, ver
#      docs/humano216.md (checkpoint entorno_desktop_tools)
#    - Base para CLIs de IA en el HOST (best-effort): python3 nodejs-lts git
#      curl (checkpoint entorno_ai_tools)
#    - Crea ~/scripts/entorno/ (scripts de gestión): tx11_start/stop,
#      vnc_start/stop, pulse_start/stop, gpu_env.sh, x11_setup_env.sh,
#      gui_start.sh, gui_stop.sh, distro_setup_gui.sh, pdrun
#    - Diagnóstico X11 (subcomando `entorno diagnose`): imprime a log y a
#      ~/kairos_logs/x11_diagnose.txt el estado del socket :1, DISPLAY,
#      distros, socket visible dentro de la distro, GPU y pulseaudio — para
#      entender por qué NO se renderiza la GUI dentro de una distro proot
#
#  NOTA X11 (ronda 2026-08-13, ver docs/humano/humano100.md): Kairos ya NO usa la
#  app externa Termux:X11 (com.termux.x11). El servidor X11 va embebido en el APK
#  (Xlorie, proceso ":xserver" vía X11Service, ver docs/x11/X11_EMBEBIDO.md).
#  Este instalador solo registra el modo embebido en el registry; los scripts
#  tx11_start.sh/tx11_stop.sh generados abajo apuntan AL display :1 embebido y al
#  visor com.termux.x11.MainActivity del propio APK — nunca a la app externa.
#
#  MINI-PC (Android TV / AOSP con distro proot) — pantalla negra + cursor X dentro
#  de la distro (ver causas completas en `bash entorno.sh diagnose` y en el propio
#  script de diagnóstico). Causa raíz: el socket X11 no se comparte con la distro
#  (el login debe usar --shared-tmp, pdrun/gui_start ya lo hacen), DISPLAY mal
#  seteado (debe ser :1, el del X11 embebido, nunca :0), sin dbus dentro de la
#  distro (dbus-x11 + dbus-launch --exit-with-session), XDG_RUNTIME_DIR con 0777
#  (crearlo con chmod 700), variables GPU del driver no exportadas (gpu_env.sh) y
#  la distinción DE en el HOST (nativa, más rápida) vs DE dentro de la distro.
#
#  Comandos para mini-PC (tras abrir Más → X11 en Kairos y verificar touch):
#    bash entorno.sh --diagnose                 → reporte en ~/kairos_logs/x11_diagnose.txt
#    source ~/scripts/entorno/x11_setup_env.sh  → entorno GUI correcto (DISPLAY/XDG/PULSE/GPU)
#    ~/scripts/entorno/gui_start.sh             → DE nativa en el HOST (default xfce4)
#    ~/scripts/entorno/gui_start.sh --distro <distro> [<DE>]  → DE dentro de la distro
#    ~/scripts/entorno/gui_stop.sh [--x11]      → matar sesiones de escritorio (+ X11)
#    ~/scripts/entorno/distro_setup_gui.sh <distro>  → instala dbus-x11+xfce4 en la distro
#    ~/scripts/entorno/proot_menu_sync.sh <distro>   → lanzadores .desktop en el host
#    ~/scripts/entorno/webapp_launchers.sh           → lanzadores .desktop para las
#                                                        WebUIs de otros módulos ya
#                                                        instalados (n8n/Ollama/etc.)
#
#  USO DESDE APP (KairosApp):
#    bash entorno.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas (siempre implícito al llamar desde la app)
#    --force    Reinstalar aunque ya esté
#    --describe Manifiesto declarativo
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 1.4.0 | Agosto 2026 (agrega webapp_launchers.sh — lanzadores
#  .desktop para las WebUIs de otros módulos de Kairos ya instalados
#  (n8n/Ollama/OpenClaw/OpenCode/IA Local), patrón url_to_app.sh de RDeX
#  (referencia/termux/RDeX-main), ver docs/humano/humano170.md)
#
#  VERSIÓN ANTERIOR: 1.3.0 (adaptado de install_entorno.sh v1.1.0;
#  añade diagnose, scripts gui_* / x11_setup_env / distro_setup_gui /
#  proot_menu_sync y tools desktop/IA best-effort. v1.3.0: 3 mejoras
#  concretas portadas de sabamdarif/termux-desktop (setup-termux-desktop) —
#  termux-wake-lock/unlock alrededor de la sesión de escritorio (gui_start/
#  gui_stop, evita que Android suspenda/throttlee la DE en background),
#  --nogpu real en gui_start.sh (antes solo se saltaba gpu_env.sh dejando
#  variables GPU stale; ahora fuerza LIBGL_ALWAYS_SOFTWARE=1 +
#  MESA_LOADER_DRIVER_OVERRIDE=llvmpipe + GALLIUM_DRIVER=llvmpipe, en ambos
#  modos nativo y distro), y pulse_start.sh con limpieza de runtime dirs
#  stale (~/.config/pulse/*-runtime, $TMPDIR/pulse) + verificación real de
#  que pulseaudio arrancó (antes reportaba éxito sin confirmar el fork))
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib"
export DEBIAN_FRONTEND=noninteractive

SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
DIAGNOSE=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true  ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    diagnose|--diagnose) DIAGNOSE=true ;;
  esac
done

if $DESCRIBE; then
  cat << 'JSON'
{"id":"entorno","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"instala infraestructura base (proot-distro/udocker/pulseaudio/GPU) + tools desktop/IA best-effort, registra el X11 EMBEBIDO del APK y genera los scripts gui_start/gui_stop/distro_setup_gui/proot_menu_sync para mini-PC — distros y DEs se eligen desde el menú interactivo de termux-ai-stack o con esos scripts; bash entorno.sh --diagnose imprime el estado X11"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. entorno.sh es infraestructura:
# instala paquetes apt (proot-distro/pulseaudio/mesa-*, ver _install_gpu_native)
# + descarga udocker.py por su cuenta (independiente del módulo modulos/
# udocker.sh) + genera TODOS los scripts de gestión en $HOME/scripts/entorno/
# (tx11_start/stop, vnc_start/stop, pulse_start/stop, gpu_env.sh, pdrun,
# x11_setup_env.sh, gui_start/stop.sh, distro_setup_gui.sh, proot_menu_sync.sh,
# webapp_launchers.sh) — eso sí es propio de Kairos y se empaqueta vía
# file_globs, mismo criterio que db.sh/qemu.sh para sus wrappers propios.
if $DESCRIBE_FILES; then
  jq -n \
    --arg glob "$HOME/scripts/entorno/**" \
    --arg verify "command -v proot-distro >/dev/null 2>&1" \
    '{
      id: "entorno",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-entorno",
      version_registry_key: "entorno.version",
      files: [],
      file_globs: [{pattern: $glob, required: true, note: "scripts de gestión generados (tx11_start/stop, vnc_start/stop, pulse_start/stop, gpu_env.sh, pdrun, x11_setup_env.sh, gui_start/stop.sh, distro_setup_gui.sh, proot_menu_sync.sh, webapp_launchers.sh)"}],
      dependencies: [
        {id: "pkg:proot-distro", check_cmd: "command -v proot-distro >/dev/null 2>&1", install_hint: "pkg install -y proot-distro"},
        {id: "pkg:pulseaudio", check_cmd: "command -v pulseaudio >/dev/null 2>&1", install_hint: "pkg install -y pulseaudio"},
        {id: "udocker_bin", check_cmd: "command -v udocker >/dev/null 2>&1", install_hint: "curl -fsSL https://raw.githubusercontent.com/indigo-dc/udocker/main/udocker.py -o $PREFIX/bin/udocker && chmod +x $PREFIX/bin/udocker"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "Los paquetes GPU (mesa-zink/virglrenderer-android/angle-android/mesa) dependen del vendor real detectado en runtime (_check_gpu) — no hay un único set fijo para listar como dependencia; se resuelven en su propio pkg install cuando se corre el script completo",
        "El X11 embebido (Xlorie) va dentro del APK, no de este módulo — acá solo se registra el modo 'embedded' en el registry",
        "No incluye distros proot ni Desktop Environments instalados — esos se eligen aparte (menú interactivo o los scripts gui_start/distro_setup_gui generados)",
        "No reinstala udocker.py si ya lo instaló modulos/udocker.sh (dependencia idempotente, mismo binario)"
      ]
    }'
  exit 0
fi

# ── Archivos de estado ──
REGISTRY="$TERMUX_HOME/.android_server_registry"
CHECKPOINT="$TERMUX_HOME/.install_entorno_checkpoint"
ENTORNO_SCRIPTS="$TERMUX_HOME/scripts/entorno"

# ── Helpers compartidas (log/warn/error/info/step/titulo/check_done/mark_done/
#    notify_event/registry_write/mirrors) ──
# Fuenteado en lugar de copiado: antes (bug humano57, ver docs/humano/humano57.md)
# este script definía log/warn/error/check_done/mark_done y copiaba notify_event()
# inline, con SILENT=0/1 mientras lib.sh espera true/false — causaba
# "notify_event: command not found" al final de TODA instalación. Desde el refactor
# de la ronda 2026-08-13 (humano103) se alinea el flag y se sourcea lib.sh como
# el resto de módulos.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  local _gpu="${GPU_TYPE:-$(_check_gpu)}"
  registry_install entorno "$version" "gpu=$_gpu" "gpu_method=${GPU_METHOD:-auto}" "x11_mode=embedded" "x11_display=:1"
  log "Registry actualizado — $REGISTRY"
}

# ── Detección de GPU (getprop, no dmesg — requiere root en Android 15) ──
_check_gpu() {
  local gpu
  gpu=$(getprop ro.board.platform 2>/dev/null)
  # Codenames Adreno ampliados (2026-08-06, ver docs/humano/humano77.md) — la
  # lista original no cubría "cape" (Snapdragon 7+ Gen 2 / SM7475, chip real
  # del POCO F5 usado para probar esta sesión, confirmado "GPU: unknown" en
  # log real) ni otros codenames Qualcomm recientes.
  case "$gpu" in
    *sm*|*kona*|*lahaina*|*shima*|*cape*|*kalama*|*taro*|*pineapple*|*sun*|*parrot*|*khaje*|*monaco*) echo "adreno" ;;
    *mt*|*t618*|*g610*|*g720*)          echo "mali" ;;
    *s5e*|*exynos*)                     echo "xclipse" ;;
    *)                                   echo "unknown" ;;
  esac
}

_install_proot_distro() {
  titulo "proot-distro"
  command -v proot-distro &>/dev/null && { log "proot-distro ya instalado"; return 0; }
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md: esta función corre
  # después de check_done "entorno_pkg_update" (gate de una sola vez por checkpoint) — en
  # una reinstalación parcial (checkpoint ya marcado en una corrida previa) el mirror puede
  # haberse roto entretanto y este "pkg install" fallaría sin pista útil.
  pkg_update_with_fallback
  pkg install -y proot-distro 2>/dev/null || error "No se pudo instalar proot-distro"
  log "proot-distro instalado"
}

_install_udocker() {
  titulo "udocker"
  command -v udocker &>/dev/null && { log "udocker ya instalado"; return 0; }
  mkdir -p "$TERMUX_HOME/tmp"
  # Bug real confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
  # 2026-08-27): la descarga directa de udocker.py (raw.githubusercontent.com/indigo-dc/
  # udocker/main/udocker.py) está rota — el proyecto upstream reestructuró el repo,
  # "udocker.py" ya no existe en la raíz (404 siempre). Mismo fix que modulos/udocker.sh y
  # modulos/n8n.sh: "pip install udocker" (paquete real en PyPI, vía oficial documentada por
  # el propio proyecto), probado en dispositivo real.
  pip3 install --quiet --upgrade udocker 2>/dev/null || pip install --quiet --upgrade udocker 2>/dev/null || {
    warn "No se pudo instalar udocker (pip install udocker falló) — se puede instalar manualmente después"
    return 0
  }
  log "udocker instalado"
}

_install_x11() {
  # Desde la ronda 2026-08-13 (ver docs/humano/humano100.md) Kairos NO usa la app
  # externa Termux:X11 (com.termux.x11): el servidor X va embebido en el propio APK
  # (X11Service, proceso ":xserver", ver docs/x11/X11_EMBEBIDO.md). Por eso
  # acá no hay APK que descargar — solo se registra el modo embebido en el registry
  # para que scripts/UI/EntornoNative sepan que el display es el embebido (:1).
  titulo "X11 embebido (Xlorie en el APK) — GUI primaria"
  if grep -q "^entorno\.x11_mode=embedded" "$REGISTRY" 2>/dev/null; then
    log "X11 embebido ya registrado (modo embedded)"
    return 0
  fi
  log "X11 embebido registrado (display :1, proceso :xserver del propio APK) — no requiere APK externo"
}

_install_pulseaudio() {
  titulo "PulseAudio"
  if command -v pulseaudio &>/dev/null; then
    log "PulseAudio ya instalado"
  else
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y pulseaudio 2>/dev/null || error "No se pudo instalar pulseaudio"
    log "PulseAudio instalado"
  fi
  local PA_CONF="$TERMUX_PREFIX/etc/pulse/default.pa"
  if [ -f "$PA_CONF" ]; then
    # module-aaudio-sink: backend real de salida en dispositivos donde el sink
    # por defecto (ALSA/OpenSL) no llega al altavoz — sin audible en varios
    # equipos aunque pulseaudio arranque OK. Patrón tomado de
    # referencia/termux/Moded-Debian-main/setup.sh (fix de audio ya validado
    # en la comunidad termux-desktop). Cargar antes que el TCP module.
    grep -q "load-module module-aaudio-sink" "$PA_CONF" 2>/dev/null || {
      echo "load-module module-aaudio-sink" >> "$PA_CONF"
      log "PulseAudio module-aaudio-sink configurado (fix de audio silencioso)"
    }
    grep -q "load-module module-native-protocol-tcp" "$PA_CONF" 2>/dev/null || {
      echo "load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1" >> "$PA_CONF"
      log "PulseAudio TCP configurado (127.0.0.1)"
    }
  fi
}

_install_gpu_native() {
  titulo "GPU nativa — $GPU_TYPE"
  case "$GPU_TYPE" in
    adreno)
      if dpkg -s mesa-zink &>/dev/null && dpkg -s vulkan-loader-generic &>/dev/null; then
        log "GPU Adreno: mesa-zink + vulkan-loader-generic ya instalados"
      else
        # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
        pkg_update_with_fallback
        pkg install -y mesa-zink vulkan-loader-generic 2>/dev/null || \
          warn "GPU Adreno: algunos paquetes fallaron (puede que ya estén)"
        log "GPU Adreno: mesa-zink + vulkan-loader-generic instalados"
      fi
      # Turnip (driver Vulkan nativo Adreno, sin pasar por Zink software-GL-sobre-Vulkan)
      # — paquete real de Termux (no proot), confirmado en
      # referencia/termux/Termux-Desktops-main/Documentation/HardwareAcceleration.md
      # sección "Hardware Acceleration in Native Termux". Best-effort: si el paquete no
      # existe en el mirror del dispositivo (repo x11-packages puede no tenerlo en todas
      # las versiones), el método sigue quedando en "zink" — no rompe la instalación base.
      # No verificado en dispositivo real todavía (empirical-verification-before-fix.md).
      pkg install -y mesa-vulkan-icd-freedreno-dri3 2>/dev/null && \
        log "GPU Adreno: mesa-vulkan-icd-freedreno-dri3 instalado (Turnip disponible como opción)" || \
        warn "GPU Adreno: mesa-vulkan-icd-freedreno-dri3 no disponible en este mirror — Turnip seguirá sin funcionar, Zink sigue como default"
      GPU_METHOD="zink"
      ;;
    mali)
      if dpkg -s mesa &>/dev/null && dpkg -s virglrenderer-android &>/dev/null && dpkg -s angle-android &>/dev/null; then
        log "GPU Mali: mesa + virglrenderer-android + angle-android ya instalados"
      else
        # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
        pkg_update_with_fallback
        pkg install -y mesa virglrenderer-android angle-android 2>/dev/null || \
          warn "GPU Mali: algunos paquetes fallaron (puede que ya estén)"
      fi
      local ANGLE_OPT="$TERMUX_PREFIX/opt/angle-android/vulkan"
      if [ -d "$ANGLE_OPT" ]; then
        [ ! -f "$ANGLE_OPT/libEGL.so.1" ] && \
          ln -s "$ANGLE_OPT/libEGL_angle.so" "$ANGLE_OPT/libEGL.so.1" 2>/dev/null
        [ ! -f "$ANGLE_OPT/libGLESv1_CM.so.1" ] && \
          ln -s "$ANGLE_OPT/libGLESv1_CM_angle.so" "$ANGLE_OPT/libGLESv1_CM.so.1" 2>/dev/null
        [ ! -f "$ANGLE_OPT/libGLESv2.so.2" ] && \
          ln -s "$ANGLE_OPT/libGLESv2_angle.so" "$ANGLE_OPT/libGLESv2.so.2" 2>/dev/null
      fi
      log "GPU Mali: mesa + virglrenderer-android + angle-android instalados"
      GPU_METHOD="virgl_angle"
      ;;
    xclipse)
      if dpkg -s mesa &>/dev/null && dpkg -s virglrenderer-android &>/dev/null && dpkg -s angle-android &>/dev/null; then
        log "GPU Xclipse: mesa + virglrenderer-android ya instalados"
      else
        # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
        pkg_update_with_fallback
        pkg install -y mesa virglrenderer-android angle-android 2>/dev/null || \
          warn "GPU Xclipse: algunos paquetes fallaron"
        log "GPU Xclipse: mesa + virglrenderer-android instalados"
      fi
      GPU_METHOD="virgl"
      ;;
    unknown)
      if dpkg -s mesa &>/dev/null; then
        log "mesa (softGPU llvmpipe) ya instalado"
      else
        warn "GPU no detectada — instalando mesa (softGPU llvmpipe)"
        # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
        pkg_update_with_fallback
        pkg install -y mesa 2>/dev/null || true
      fi
      GPU_METHOD="llvmpipe"
      ;;
  esac
  # mesa-utils (glxinfo/glxgears) — bug real (docs/humano281.md, pedido explícito del
  # usuario): el diagnóstico de GPU (EntornoNative.kt gpuDiagnostic()) sugería "pkg install
  # mesa-utils" en un mensaje de texto pero nunca lo instalaba de verdad, así que el propio
  # comando sugerido nunca funcionaba solo. Se instala acá, una sola vez, para todas las
  # ramas de GPU_TYPE (diagnóstico real, no específico de un driver).
  dpkg -s mesa-utils &>/dev/null || pkg install -y mesa-utils 2>/dev/null || \
    warn "mesa-utils no se pudo instalar (glxinfo/glxgears no van a estar disponibles para diagnóstico)"
}

# ── Lista de distros proot instaladas ──
# Ruta real del rootfs (proot-distro la fija siempre en $PREFIX/var/lib/proot-distro/
# installed-rootfs) con fallback al subcomando list-installed de versiones nuevas.
_proot_distros() {
  local dir="$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs"
  if [ -d "$dir" ]; then
    ls "$dir" 2>/dev/null
  else
    proot-distro list-installed 2>/dev/null | awk '{print $1}'
  fi
}

# ── Diagnóstico X11 (`entorno.sh diagnose` / `--diagnose`) ──
# Reporta el estado del X11 embebido, socket, DISPLAY, distros, GPU y pulseaudio e
# imprime las causas 1-8 de la pantalla negra que apliquen. Salida a stdout/log y a
# ~/kairos_logs/x11_diagnose.txt (mini-PC: pantalla negra + cursor X dentro de la distro).
_diagnose() {
  local OUTFILE="$TERMUX_HOME/kairos_logs/x11_diagnose.txt"
  mkdir -p "$(dirname "$OUTFILE")"
  : > "$OUTFILE"
  local diag
  diag() { echo "$1"; echo "$1" >> "$OUTFILE"; }

  local TMPD="${TMPDIR:-$TERMUX_PREFIX/tmp}"
  local SOCKET="$TMPD/.X11-unix/X1"

  diag "═══════════════════════════════════════════════════════════"
  diag "  Diagnóstico X11 — $(date '+%Y-%m-%d %H:%M:%S')"
  diag "  (X11 EMBEBIDO de Kairos: display :1, proceso :xserver del APK)"
  diag "═══════════════════════════════════════════════════════════"
  diag ""

  diag "── 1. Proceso X11 embebido (:xserver) ──"
  if pgrep -f ":xserver" >/dev/null 2>&1; then
    diag "  [OK] Proceso :xserver vivo"
  else
    diag "  [WARN] Proceso :xserver NO está corriendo — abrí 'Más → X11' en Kairos o ejecutá: bash ~/scripts/entorno/tx11_start.sh"
  fi

  diag ""
  diag "── 2. Socket X11 (causa 1) ──"
  if [ -S "$SOCKET" ]; then
    diag "  [OK] Socket presente: $SOCKET"
  else
    diag "  [WARN] No hay socket en $SOCKET — el X11 embebido no está arriba o usa otro display"
  fi

  diag ""
  diag "── 3. Variables de entorno (causas 2 y 4) ──"
  diag "  DISPLAY=${DISPLAY:-<vacío>}"
  diag "  TMPDIR=$TMPD"
  diag "  XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-<vacío>}"
  diag "  PULSE_SERVER=${PULSE_SERVER:-<vacío>}"

  diag ""
  diag "── 4. Distros proot instaladas + socket visible dentro (causa 1) ──"
  if command -v proot-distro &>/dev/null; then
    local distro found=0
    while IFS= read -r distro; do
      [ -z "$distro" ] && continue
      found=1
      diag "  Distro: $distro"
      if timeout 20 proot-distro login "$distro" --shared-tmp --shared-home -- ls /tmp/.X11-unix/ 2>/dev/null | grep -q "X1"; then
        diag "    [OK] Socket X1 visible dentro de la distro (/tmp/.X11-unix/X1)"
      else
        diag "    [WARN] Socket NO visible dentro de la distro — el login debe usar --shared-tmp (pdrun/gui_start ya lo hacen)"
      fi
    done < <(_proot_distros)
    [ "$found" = "0" ] && diag "  No hay distros instaladas (proot-distro list)"
  else
    diag "  proot-distro NO está instalado (corré el módulo entorno)"
  fi

  diag ""
  diag "── 5. GPU y variables de aceleración (causa 7) ──"
  diag "  GPU detectada: $(_check_gpu)"
  local _method; _method=$(grep '^entorno\.gpu_method=' "$REGISTRY" 2>/dev/null | cut -d= -f2 | tail -1)
  diag "  Método (registry): ${_method:-<vacío>}"
  [ -f "$ENTORNO_SCRIPTS/gpu_env.sh" ] && source "$ENTORNO_SCRIPTS/gpu_env.sh" 2>/dev/null
  diag "  Variables GPU: GALLIUM_DRIVER=${GALLIUM_DRIVER:-<vacío>} MESA_GL_VERSION_OVERRIDE=${MESA_GL_VERSION_OVERRIDE:-<vacío>} MESA_GLES_VERSION_OVERRIDE=${MESA_GLES_VERSION_OVERRIDE:-<vacío>}"

  diag ""
  diag "── 6. PulseAudio ──"
  if pgrep -x pulseaudio >/dev/null 2>&1; then
    diag "  [OK] pulseaudio vivo"
  else
    diag "  [WARN] pulseaudio NO está corriendo — bash ~/scripts/entorno/pulse_start.sh (y dentro de la distro exportá PULSE_SERVER=127.0.0.1)"
  fi

  diag ""
  diag "── 7. Recomendaciones (causas de pantalla negra + cursor X) ──"
  local _r=0
  local _bump
  _bump() { _r=$((_r+1)); diag "  ${_r}. $1"; }
  if ! pgrep -f ":xserver" >/dev/null 2>&1; then
    _bump "Arrancá el X11 embebido ('Más → X11' / tx11_start.sh) y volvé a correr diagnose."
  elif [ ! -S "$SOCKET" ]; then
    _bump "El servidor X11 no expone el socket — reiniciá el servidor desde Kairos (Más → X11 → Cerrar servidor X11, y volvé a entrar)."
  fi
  if [ "${DISPLAY:-}" != ":1" ]; then
    _bump "DISPLAY no es :1 (es '${DISPLAY:-vacío}') — el X11 embebido usa :1. Fuenteá ~/scripts/entorno/x11_setup_env.sh."
  fi
  local _sock_hidden=0 distro2
  while IFS= read -r distro2; do
    [ -z "$distro2" ] && continue
    timeout 20 proot-distro login "$distro2" --shared-tmp --shared-home -- ls /tmp/.X11-unix/ 2>/dev/null | grep -q "X1" || _sock_hidden=1
  done < <(_proot_distros)
  [ "$_sock_hidden" = "1" ] && \
    _bump "El socket X11 no se ve dentro de la distro — usá 'proot-distro login <distro> --shared-tmp --shared-home' (pdrun y gui_start.sh --distro ya lo hacen)."
  if command -v dbus-launch &>/dev/null; then
    diag "  (dbus-launch disponible en el HOST)"
  else
    _bump "dbus-launch no está en el HOST — 'pkg install -y dbus' (en Termux dbus-launch viene con el paquete \"dbus\", no existe \"dbus-x11\" acá — ese es el nombre de Debian/Ubuntu, válido solo DENTRO de una distro con distro_setup_gui.sh) — las DE modernas lo necesitan (causa 3)."
  fi
  if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    _bump "XDG_RUNTIME_DIR vacío — fuentesá ~/scripts/entorno/x11_setup_env.sh (lo crea con chmod 700; /tmp 0777 rompe muchas apps, causa 4)."
  fi
  local _manu; _manu=$(getprop ro.product.manufacturer 2>/dev/null)
  case "$_manu" in
    samsung|*samsung*|*Samsung*)
      _bump "Fabricante $_manu (Samsung/Tizen/One UI): el glib2 de One UI puede romper apps GUI dentro de la distro — probá la DE nativa del HOST o el workaround de la comunidad termux-desktop (causa 5)."
      ;;
  esac
  _bump "(Nota) cap_last_cap: proot-distro ya maneja --bind /dev/null:/proc/sys/kernel/cap_last_cap internamente — no lo agregues a mano en proot (causa 6)."
  if [ -n "${GALLIUM_DRIVER:-}" ] && [ "${GALLIUM_DRIVER:-}" != "llvmpipe" ]; then
    diag "  (GPU acelerada: GALLIUM_DRIVER=$GALLIUM_DRIVER — si apps crashean probá 'gui_start.sh --nogpu' o exportá MESA_LOADER_DRIVER_OVERRIDE, causa 7)"
  fi
  _bump "Si todo lo anterior está OK y la GUI sigue en negro: probá la DE NATIVA del HOST (gui_start.sh sin --distro, más rápida) antes que la de la distro (causa 8)."

  diag ""
  diag "Reporte guardado en: $OUTFILE"
  log "Diagnóstico X11 completo — $OUTFILE"
}

_create_scripts() {
  titulo "Scripts de gestión"
  mkdir -p "$ENTORNO_SCRIPTS"

  cat > "$ENTORNO_SCRIPTS/tx11_start.sh" << 'TX11EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Iniciar X11 EMBEBIDO de Kairos (Xlorie dentro del APK, proceso :xserver) — NO la
# app externa Termux:X11. Replica lo que hace X11Service.start() + MainActivity en
# la app (ver docs/x11/X11_EMBEBIDO.md y X11Fragment.kt).
# Mismo UID que la app (sharedUserId com.termux) → puede startservice a servicios
# exported=false del propio APK.
export DISPLAY=:1
am start-foreground-service -n com.termux/.app.X11Service 2>/dev/null || \
  am startservice -n com.termux/.app.X11Service 2>/dev/null || true
sleep 2
am start --user 0 -n com.termux/com.termux.x11.MainActivity 2>/dev/null || true
echo "X11 embebido iniciado en :1 — abrí el visor en la app para ver el escritorio"
TX11EOF
  chmod +x "$ENTORNO_SCRIPTS/tx11_start.sh"

  cat > "$ENTORNO_SCRIPTS/tx11_stop.sh" << 'TX11STOP'
#!/data/data/com.termux/files/usr/bin/bash
# Detener X11 embebido — mismo contrato que X11Fragment.stopX11():
# broadcast ACTION_STOP (cierra el visor si está abierto) + stop del X11Service (mata :xserver)
am broadcast -a com.termux.x11.ACTION_STOP -p com.termux 2>/dev/null || true
am stopservice -n com.termux/.app.X11Service 2>/dev/null || true
echo "X11 embebido detenido"
TX11STOP
  chmod +x "$ENTORNO_SCRIPTS/tx11_stop.sh"

  cat > "$ENTORNO_SCRIPTS/vnc_start.sh" << 'VNCSTART'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:1
# Fix real (humano181, bug 3 "al darle iniciar da error"): TigerVNC exige contraseña
# interactiva (vncpasswd) en el primer uso — vncInstall() solo deja un aviso pero
# nada la fuerza. Con stdin sin tty (este script corre vía ProcessBuilder desde
# EntornoNative.vncStart(), sin stdin interactivo), vncserver/tigervncserver no
# puede leer el prompt y aborta con rc!=0 → exactamente el error que reporta el
# usuario. Solución: si nunca se corrió vncpasswd (~/.vnc/passwd no existe), se
# arranca sin contraseña (-SecurityTypes None) — aceptable porque VncViewerActivity/
# VncClient.kt solo se conectan a 127.0.0.1:5901 (-localhost ya restringe el bind a
# loopback, mismo criterio que vncStartWithConfig() ya usa para su opción
# "sin contraseña"). Si el usuario configuró una contraseña real, se respeta
# (comportamiento estándar, sin este flag).
VNC_EXTRA_ARGS=""
[ -f "$HOME/.vnc/passwd" ] || VNC_EXTRA_ARGS="-SecurityTypes None"
vncserver :1 -geometry 1920x1080 -depth 24 -localhost $VNC_EXTRA_ARGS 2>/dev/null || \
  tigervncserver :1 -geometry 1920x1080 -depth 24 -localhost $VNC_EXTRA_ARGS 2>/dev/null || {
  echo "VNC no instalado — usa el menú Interfaz para instalarlo"
  exit 1
}
echo "VNC en :5901 — conectar: vncviewer 127.0.0.1:5901"
VNCSTART
  chmod +x "$ENTORNO_SCRIPTS/vnc_start.sh"

  cat > "$ENTORNO_SCRIPTS/vnc_stop.sh" << 'VNCSTOP'
#!/data/data/com.termux/files/usr/bin/bash
vncserver -kill :1 2>/dev/null || tigervncserver -kill :1 2>/dev/null || true
echo "VNC detenido"
# Aviso real a la app (bridge sin polling, ver modulos/lib.sh notify_event() y
# app/src/main/java/com/termux/app/util/ModuleEventBridge.kt, docs/referencias/REFERENCIA_PODROID.md) —
# script standalone, no sourcea lib.sh, así que se escribe inline con la misma lógica
# defensiva (no bloquear si no hay lector del otro lado).
[ -p "$HOME/.kairos_events" ] && timeout 1 bash -c "echo 'entorno:vnc_closed:' > '$HOME/.kairos_events'" 2>/dev/null || true
VNCSTOP
  chmod +x "$ENTORNO_SCRIPTS/vnc_stop.sh"

  # pulse_start.sh — limpia estado stale + VERIFICA que pulseaudio arrancó de
  # verdad antes de reportar éxito (patrón sabamdarif/termux-desktop
  # setup-termux-desktop: mata el proceso viejo, borra los runtime dirs de
  # `~/.config/pulse/*-runtime/` y `$TMPDIR/pulse/` — quedan corruptos tras un
  # crash y rompen el siguiente arranque en silencio — y hace polling con
  # `pulseaudio --check` hasta 5s en vez de asumir que el fork ya está listo).
  # Antes (versión previa) el script terminaba con exit 0 aunque pulseaudio
  # jamás llegara a levantar — mini-PC quedaba mudo sin ningún aviso.
  cat > "$ENTORNO_SCRIPTS/pulse_start.sh" << 'PULSESTART'
#!/data/data/com.termux/files/usr/bin/bash
pulseaudio --kill 2>/dev/null || true
for _i in 1 2 3; do
  pulseaudio --check 2>/dev/null || break
  sleep 0.5
done
pkill -9 pulseaudio 2>/dev/null || true
rm -rf "$HOME/.config/pulse/"*-runtime/ 2>/dev/null
rm -rf "${TMPDIR:-$PREFIX/tmp}/pulse/" 2>/dev/null

pulseaudio --start --exit-idle-time=-1 2>/dev/null

_ready=false
for _i in 1 2 3 4 5; do
  if pulseaudio --check 2>/dev/null; then
    _ready=true
    break
  fi
  sleep 1
done

if $_ready; then
  echo "PulseAudio iniciado"
else
  echo "[WARN] PulseAudio no confirmó estar arriba tras 5s — revisá 'pulseaudio --check' a mano" >&2
  exit 1
fi
PULSESTART
  chmod +x "$ENTORNO_SCRIPTS/pulse_start.sh"

  cat > "$ENTORNO_SCRIPTS/pulse_stop.sh" << 'PULSESTOP'
#!/data/data/com.termux/files/usr/bin/bash
pkill pulseaudio 2>/dev/null
echo "PulseAudio detenido"
PULSESTOP
  chmod +x "$ENTORNO_SCRIPTS/pulse_stop.sh"

  cat > "$ENTORNO_SCRIPTS/gpu_env.sh" << 'GPUENVEOF'
#!/data/data/com.termux/files/usr/bin/bash
# Cargar variables de entorno para aceleración GPU
# USO: source ~/scripts/entorno/gpu_env.sh
export MESA_NO_ERROR=1
export vblank_mode=0
GPU_TYPE=$(grep "^entorno\.gpu=" ~/.android_server_registry 2>/dev/null | cut -d= -f2)
GPU_METHOD=$(grep "^entorno\.gpu_method=" ~/.android_server_registry 2>/dev/null | cut -d= -f2)
case "$GPU_METHOD" in
  zink)
    export GALLIUM_DRIVER=zink
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLES_VERSION_OVERRIDE=3.2 ;;
  virgl_angle|virgl)
    export GALLIUM_DRIVER=virpipe
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLES_VERSION_OVERRIDE=3.2
    export LIBGL_DRI3_DISABLE=1 ;;
  turnip)
    # Fix real (roadmap Mini PC item 1, docs/humano/humano181.md bug 5 "en gpu falta
    # wrapper, zink, turnip o panfrot" — nunca cerrado del todo: setGpuMethod("turnip")
    # ya instalaba paquetes desde 2026-08-xx pero este case nunca existió, así que la
    # variable real que activa el driver freedreno nativo (paquete apt
    # mesa-vulkan-icd-freedreno-dri3, ver referencia/termux/Termux-Desktops-main/
    # Documentation/HardwareAcceleration.md sección "Hardware Acceleration in Native
    # Termux") nunca se exportaba — el método quedaba elegido en el registry pero
    # gpu_env.sh seguía sin tocar ninguna variable, cayendo al comportamiento default
    # de Mesa. MESA_LOADER_DRIVER_OVERRIDE=zink es el valor real documentado ahí para
    # activar Turnip (el ICD freedreno se registra bajo el nombre "zink" del loader,
    # no es un typo) — sin verificar en dispositivo real todavía (regla
    # empirical-verification-before-fix.md), mismo estado "experimental" que ya
    # declara gpuMethodOptions() para este método.
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export TU_DEBUG=noconform ;;
  panfrost)
    # Panfrost (driver Mesa nativo para Mali, sin VirGL de por medio) — mismo gap que
    # turnip arriba: setGpuMethod("panfrost") instala mesa-panfrost pero gpu_env.sh
    # nunca activaba el driver. GALLIUM_DRIVER=panfrost es el nombre real del backend
    # Gallium en Mesa upstream — no verificado en dispositivo real todavía, declarado
    # "experimental" en gpuMethodOptions() a propósito.
    export GALLIUM_DRIVER=panfrost
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLES_VERSION_OVERRIDE=3.2 ;;
  llvmpipe)
    export GALLIUM_DRIVER=llvmpipe ;;
  wrapper)
    # "Wrapper" real (corregido 2026-08-28, docs/humano281.md — el usuario aclaró
    # explícitamente que NO es ANGLE/OpenGL/EGL, es Vulkan puro, una capa ENCIMA del driver
    # Vulkan nativo del propio dispositivo, y SOLO funciona en modo nativo, nunca dentro de
    # proot-distro). Identificado en referencia/termux/termux-desktop-main/docs/
    # hw-acceleration.md + enable-hw-acceleration: paquete real "vulkan-wrapper-android"
    # (github.com/sabamdarif/termux-desktop releases, no está en el repo oficial de Termux) —
    # expone el ICD Vulkan real del fabricante vía VK_ICD_FILENAMES en vez de reemplazarlo por
    # un driver por software. A propósito NO exporta GALLIUM_DRIVER/MESA_GL_VERSION_OVERRIDE/
    # MESA_GLES_VERSION_OVERRIDE/LIBGL_DRI3_DISABLE (las únicas variables que GPU_ENV_ARGS
    # reenvía a "proot-distro login ... --env" más abajo en este mismo script) — así queda
    # automáticamente confinado a modo nativo sin necesitar un guard aparte: si algún día se
    # intenta usar dentro de una distro, VK_ICD_FILENAMES simplemente no llega, cae al Mesa
    # default de la distro. No instala el paquete acá (ver setGpuMethod() en EntornoNative.kt,
    # que sí lo descarga desde el release real) — no verificado en dispositivo real todavía
    # (empirical-verification-before-fix.md).
    export VK_ICD_FILENAMES="$TERMUX_PREFIX/share/vulkan/icd.d/wrapper_icd.aarch64.json"
    export MESA_VK_WSI_PRESENT_MODE=mailbox
    unset GALLIUM_DRIVER ;;
esac
echo "GPU: $GPU_TYPE ($GPU_METHOD) — variables cargadas"
GPUENVEOF
  chmod +x "$ENTORNO_SCRIPTS/gpu_env.sh"

  # pdrun — wrapper para lanzar un binario YA instalado dentro de una distro proot sin
  # loguearse a mano (portado de "pdrun"/packinstall.sh de termux-desktop-main, ver
  # referencia/termux/termux-desktop-main/distro-container-setup). Usado por los
  # lanzadores .desktop que EntornoNative.distroAppInstall()/generateDesktopLaunchers()
  # generan para apps instaladas dentro de una distro (catálogo de apps, ver
  # docs/humano/humano118.md). DISPLAY fijo :1 — el X11 embebido de Kairos (Xlorie,
  # X11Service), NUNCA :0 (Termux:X11 externo, no se usa en Kairos).
  cat > "$ENTORNO_SCRIPTS/pdrun" << 'PDRUNEOF'
#!/data/data/com.termux/files/usr/bin/bash
# pdrun <distro> <comando...> — corre <comando> dentro de <distro> sobre el X11 embebido
# de Kairos (:1), con el mismo storage compartido que EntornoNative.distroLoginArgs()
# (Kotlin) — mantener ambas implementaciones equivalentes si se toca una.
DISTRO="$1"
if [ -z "$DISTRO" ]; then
  echo "uso: pdrun <distro> <comando...>" >&2
  exit 1
fi
shift

usb_bind_args() {
  local args="--bind /storage:/storage --bind /mnt/media_rw:/mnt/media_rw"
  local media uuid
  while IFS= read -r media; do
    [ -n "$media" ] || continue
    uuid="${media##*/}"
    args="$args --bind $media:/mnt/usb/$uuid"
  done < <(awk '($2 ~ "^/mnt/media_rw/[0-9A-Fa-f-]+$") && ($3 ~ /vfat|exfat|ntfs|fuseblk/) {print $2}' /proc/mounts 2>/dev/null | sort -u)
  # ~/scripts y ~/proyectos — mismo bind automático que EntornoNative.projectBindArgs()
  # (Kotlin, 2026-08-18): antes esto era un paso manual que el usuario tenía que pegar a
  # mano en cada login ("🔗 Vincular"); ahora se aplica solo, en TODO login vía pdrun.
  mkdir -p "$HOME/scripts" "$HOME/proyectos" 2>/dev/null
  args="$args --bind $HOME/scripts:/home/builder/scripts --bind $HOME/proyectos:/home/builder/proyectos"
  printf '%s' "$args"
}

# Carga el entorno X11 (x11_setup_env.sh) si existe — setea DISPLAY=:1, XDG_RUNTIME_DIR
# (chmod 700), PULSE_SERVER=127.0.0.1 y las variables GPU. Fuenteada (no ejecutada) para
# que las variables queden en este proceso y se forwardeen a la distro abajo.
if [ -f "$HOME/scripts/entorno/x11_setup_env.sh" ]; then
  source "$HOME/scripts/entorno/x11_setup_env.sh" 2>/dev/null || true
fi

# Forward de variables hacia la distro (proot no comparte env automáticamente).
# XDG_RUNTIME_DIR se traduce a la ruta de la distro: el host lo ve en $PREFIX/tmp/... que
# con --shared-tmp mapea a /tmp/... dentro de la distro.
ENV_PREFIX=""
for _v in DISPLAY PULSE_SERVER GALLIUM_DRIVER MESA_NO_ERROR MESA_GL_VERSION_OVERRIDE \
          MESA_GLES_VERSION_OVERRIDE LIBGL_DRI3_DISABLE vblank_mode; do
  if [ -n "${!_v:-}" ]; then
    ENV_PREFIX="$ENV_PREFIX $_v=${!_v}"
  fi
done
if [ -n "${XDG_RUNTIME_DIR:-}" ]; then
  ENV_PREFIX="$ENV_PREFIX XDG_RUNTIME_DIR=/tmp/${XDG_RUNTIME_DIR##*/}"
fi

exec proot-distro login $(usb_bind_args) "$DISTRO" --shared-tmp --shared-home -- \
  env $ENV_PREFIX sh -lc 'exec "$@"' _ "$@"
PDRUNEOF
  chmod +x "$ENTORNO_SCRIPTS/pdrun"

  # x11_setup_env.sh — entorno correcto para GUI sobre el X11 embebido (:1). Se fuentea
  # (source) antes de lanzar cualquier GUI; ataca las causas 1/2/4/7 de la pantalla negra.
  cat > "$ENTORNO_SCRIPTS/x11_setup_env.sh" << 'X11SETUPEOF'
#!/data/data/com.termux/files/usr/bin/bash
# ~/scripts/entorno/x11_setup_env.sh — prepara el entorno para GUI sobre el X11 embebido
# de Kairos (display :1). Diseñado para source-earse ANTES de lanzar una GUI:
#   source ~/scripts/entorno/x11_setup_env.sh
# Hace:
#   1. export DISPLAY=:1                (display del X11 embebido del APK — causa 2)
#   2. XDG_RUNTIME_DIR con chmod 700    (causa 4 — /tmp con 0777 rompe muchas apps)
#   3. export PULSE_SERVER=127.0.0.1    (pulseaudio TCP del host, mismo namespace net)
#   4. source gpu_env.sh                (variables GPU del driver instalado — causa 7)
#   5. verifica el socket X11           (causa 1 — ¿está arriba el servidor?)
export DISPLAY="${X11_DISPLAY:-:1}"
export XDG_RUNTIME_DIR="${PREFIX:-/data/data/com.termux/files/usr}/tmp/xdg-runtime"
mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null
chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null
export PULSE_SERVER=127.0.0.1
[ -f "$HOME/scripts/entorno/gpu_env.sh" ] && source "$HOME/scripts/entorno/gpu_env.sh" 2>/dev/null
_TMP="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
if [ -S "$_TMP/.X11-unix/X${DISPLAY#:}" ]; then
  echo "[OK] Socket X11 visible: $_TMP/.X11-unix/X${DISPLAY#:}"
else
  echo "[WARN] No hay socket X11 en $_TMP/.X11-unix/X${DISPLAY#:} — ¿arrancaste el X11 embebido (Más → X11 / tx11_start.sh)?"
fi
X11SETUPEOF
  chmod +x "$ENTORNO_SCRIPTS/x11_setup_env.sh"

  # gui_start.sh — lanza el escritorio para mini-PC. Dos rutas: DE nativa en el HOST
  # (más rápida) o DE dentro de la distro proot (--distro). Flags al estilo termux-desktop.
  cat > "$ENTORNO_SCRIPTS/gui_start.sh" << 'GUISTARTEOF'
#!/data/data/com.termux/files/usr/bin/bash
# gui_start.sh — lanza el escritorio para mini-PC sobre el X11 embebido de Kairos (:1).
#
# DOS RUTAS (causa 8 del header de modulos/entorno.sh):
#   1) HOST nativo:  la DE corre en Termux mismo (más rápido, sin proot overhead).
#   2) DENTRO de la distro proot:  la DE corre en la distro (--distro <distro>).
#
# USO:
#   gui_start.sh                                  → DE nativa en el HOST (default xfce4)
#   gui_start.sh <DE>                             → DE nativa con la DE dada (xfce4/lxqt/openbox/i3)
#   gui_start.sh --distro <distro> [<DE>]         → DE dentro de la distro proot
#   gui_start.sh --display <IP>:<PUERTO>          → reenvía X11 a otra pantalla (forward)
#
# FLAGS (patrón termux-desktop):
#   --nogpu   no cargar variables GPU (gpu_env.sh) — troubleshooting
#   --nodbus  no usar dbus-launch — arranca la DE directo
#   --legacy  no cargar x11_setup_env.sh (solo DISPLAY) — troubleshooting
DE="xfce4"
MODE="native"
DISTRO=""
DISPLAY_VAL="${DISPLAY:-:1}"
USE_DBUS=true
USE_GPU=true
USE_ENV_SCRIPT=true

while [ $# -gt 0 ]; do
  case "$1" in
    --distro)  MODE="distro"; DISTRO="$2"; shift 2 ;;
    --display) DISPLAY_VAL="$2"; shift 2 ;;
    --nogpu)   USE_GPU=false ;;
    --nodbus)  USE_DBUS=false ;;
    --legacy)  USE_ENV_SCRIPT=false ;;
    -h|--help) sed -n '1,30p' "$0"; exit 0 ;;
    *)         DE="$1"; shift ;;
  esac
done

session_cmd() {
  case "$1" in
    xfce4|xfce) echo "xfce4-session" ;;
    lxqt)       echo "lxqt-session" ;;
    openbox)    echo "openbox-session" ;;
    i3|i3wm)    echo "i3" ;;
    *)          echo "$1-session" ;;
  esac
}

# Aviso phantom process killer (Android 12+, SDK >= 31) — best-effort, patrón termux-desktop.
SDK=$(getprop ro.build.version.sdk 2>/dev/null)
if [ -n "$SDK" ] && [ "$SDK" -ge 31 ]; then
  echo "[WARN] Android SDK $SDK: si la DE muere sola o la pantalla queda en negro, desactivá 'Phantom process killer' en Opciones de desarrollador."
fi

export DISPLAY="$DISPLAY_VAL"

# ── Modo nativo (HOST) ──
if [ "$MODE" = "native" ]; then
  if $USE_ENV_SCRIPT; then
    if [ -f "$HOME/scripts/entorno/x11_setup_env.sh" ]; then
      source "$HOME/scripts/entorno/x11_setup_env.sh" 2>/dev/null || true
      export DISPLAY="$DISPLAY_VAL"
    else
      mkdir -p "${XDG_RUNTIME_DIR:-$HOME/.xdg-runtime}" 2>/dev/null
      chmod 700 "${XDG_RUNTIME_DIR:-$HOME/.xdg-runtime}" 2>/dev/null
      export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$HOME/.xdg-runtime}"
      export PULSE_SERVER=127.0.0.1
    fi
  fi
  if $USE_GPU; then
    [ -f "$HOME/scripts/entorno/gpu_env.sh" ] && source "$HOME/scripts/entorno/gpu_env.sh" 2>/dev/null || true
  else
    # --nogpu real (patrón sabamdarif/termux-desktop setup-termux-desktop): antes
    # esto solo se saltaba gpu_env.sh, dejando las variables GPU sin setear —
    # si una sesión previa las había exportado quedaban stale y --nogpu no
    # forzaba nada. Fuerza explícitamente software rendering (llvmpipe).
    export LIBGL_ALWAYS_SOFTWARE=1 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe GALLIUM_DRIVER=llvmpipe
  fi
  # Wake lock (patrón sabamdarif/termux-desktop): sin esto Android puede
  # suspender/throttlear la CPU en background durante la sesión de escritorio
  # (congelamientos random de la DE) — gui_stop.sh hace termux-wake-unlock.
  command -v termux-wake-lock &>/dev/null && termux-wake-lock
  SESSION_CMD=$(session_cmd "$DE")
  if $USE_DBUS && command -v dbus-launch &>/dev/null; then
    echo "[OK] Iniciando $DE nativo en $DISPLAY (dbus-launch)..."
    exec dbus-launch --exit-with-session "$SESSION_CMD"
  else
    echo "[OK] Iniciando $DE nativo en $DISPLAY (sin dbus)..."
    exec "$SESSION_CMD"
  fi
fi

# ── Modo distro proot ──
[ -z "$DISTRO" ] && { echo "uso: gui_start.sh --distro <distro> [<DE>]" >&2; exit 1; }
command -v proot-distro &>/dev/null || { echo "[ERROR] proot-distro no está instalado (módulo entorno)" >&2; exit 1; }

# Bug real confirmado (2026-08-19, ver docs/mini-pc/AUDITORIA_ENTORNO_MINIPC_CODIGO_2026-08-19.md,
# reporte de usuario "solo abre las interfaces graficas nativas nunca abre con las distro"): a
# diferencia del modo nativo (arriba en este mismo script no aplica — el nativo real corre desde
# EntornoNative.kt/startDesktop(), que espera 5s fijos + verifica el socket ANTES de lanzar el
# cliente X, fix de 2026-08-14), este modo nunca esperaba a que Xlorie (X11Service, proceso
# ":xserver" separado, arranca async) terminara de publicar el socket — solo hacía un chequeo
# instantáneo DE SOLO DIAGNÓSTICO (WARN, sin bloquear) justo antes del login, y arrancaba la
# sesión igual. Si el usuario tocaba "Iniciar escritorio en distro" apenas arrancaba X11Service
# (patrón real: EntornoFragment.startDistroDesktopOnEmbeddedX11() llama X11Service.start() y en
# el mismo tick dispara este script en background, sin ningún delay previo), el cliente X dentro
# de la distro intentaba conectar antes de que el socket existiera y moría al toque con "Can't
# open display" — exactamente el síntoma reportado. Se agrega un retry loop real (hasta 10s,
# mismo presupuesto de margen que ya usa el camino nativo) que ESPERA el socket antes de hacer
# login, en vez de solo advertir y seguir.
# Presupuesto de espera subido de 10s a 25s (bug real confirmado en dispositivo real,
# 2026-08-29, reproducido dos veces seguidas con el mismo dispositivo): un X11Service recién
# arrancado en frío (CmdEntryPoint.main() cargando libXlorie.so + inicializando Xlorie por
# primera vez en el proceso ":xserver") puede tardar más de 10s en publicar el socket real —
# el primer intento del día falló con "Socket X11 AUSENTE ... tras 10s" seguido de
# "xfce4-session: Cannot open display", mientras que un segundo intento ~2 minutos después,
# con el mismo proceso ":xserver" ya caliente, encontró el socket "presente (esperado 0s)" y
# el escritorio arrancó bien. 25s da margen real para un arranque en frío sin alargar de más
# el caso común (ya caliente sale del loop apenas el socket existe, no espera los 25s enteros).
_TMP_HOST="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
if [[ "$DISPLAY_VAL" == :* ]]; then
  _SOCK="$_TMP_HOST/.X11-unix/X${DISPLAY_VAL#:}"
  _waited=0
  while [ ! -S "$_SOCK" ] && [ "$_waited" -lt 25 ]; do
    sleep 1
    _waited=$((_waited + 1))
  done
  if [ -S "$_SOCK" ]; then
    echo "[OK] Socket X11 presente en $_SOCK (esperado ${_waited}s)"
  else
    echo "[WARN] Socket X11 AUSENTE en $_SOCK tras 25s — ¿arrancaste el X11 embebido (Más → X11)? Se intenta igual, probablemente falle."
  fi
fi

SESSION_CMD=$(session_cmd "$DE")
INNER="mkdir -p /tmp/xdg-runtime; chmod 700 /tmp/xdg-runtime; "
if [[ "$DISPLAY_VAL" == :* ]]; then
  INNER="$INNER [ -S /tmp/.X11-unix/X${DISPLAY_VAL#:} ] || echo '[WARN] Socket X11 no visible DENTRO de la distro pese a existir en el host — el login debe usar --shared-tmp (causa 1)'; "
fi
if ! $USE_GPU; then
  # --nogpu real dentro de la distro también (mismo fix que el modo nativo).
  INNER="export LIBGL_ALWAYS_SOFTWARE=1 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe GALLIUM_DRIVER=llvmpipe; $INNER"
fi

# Fix real (humano181, bug 5 "en gpu falta wrapper, zink, turnip o panfrot"): las variables
# GPU aceleradas (GALLIUM_DRIVER=zink/virpipe/etc., seteadas por gpu_env.sh según lo elegido
# en "⚙ Configurar método GPU" → EntornoNative.setGpuMethod()) se sourceaban en ESTE shell
# (host) más arriba pero nunca se pasaban al `env` explícito de `proot-distro login ... --
# env ...` — a diferencia del modo nativo (arriba en este script), que hereda el entorno del
# propio proceso. proot-distro arma un entorno limpio para el login, así que cualquier export
# del shell host que no esté en la lista explícita de `env` se pierde: la distro SIEMPRE
# corría en software-rendering (llvmpipe por defecto de Mesa), nunca con la aceleración
# elegida — Zink/VirGL ya están soportados por gpu_env.sh (ver arriba), pero jamás llegaban
# a aplicarse dentro de la distro. Turnip (driver nativo Adreno, sin Zink de por medio) y
# Panfrost puro (sin VirGL) siguen sin soporte real en gpu_env.sh — eso sí es feature
# faltante, no se inventa acá.
#
# Bug real confirmado 2026-08-27 (ver docs/humano256.md, reporte de usuario: fondo NEGRO al
# abrir un entorno gráfico en distro, pero el panel/resto sí carga): el fix de arriba (humano181)
# construía GPU_ENV_ARGS leyendo $GALLIUM_DRIVER/$MESA_GL_VERSION_OVERRIDE/etc DIRECTO del shell
# actual — pero este bloque "modo distro" nunca sourcea gpu_env.sh (a diferencia del modo nativo,
# línea ~754 arriba), así que esas variables SIEMPRE estaban unset acá, sin importar qué método
# GPU hubiera elegido el usuario en "⚙ Configurar método GPU". `${VAR:-}` sobre una var unset da
# string vacío, y pasarle a `env` "MESA_GL_VERSION_OVERRIDE=" (vacío, no ausente) es un valor
# INVÁLIDO para Mesa — confirmado en desktop_distro_debian_mate.log real del dispositivo:
# "error: invalid value for MESA_GL_VERSION_OVERRIDE: " (dos veces) y "... MESA_GLES_VERSION_OVERRIDE: "
# apenas arranca mate-session, antes de que el compositor/mate-settings-daemon terminen de armar
# la sesión — consistente con el fondo quedando negro mientras el panel (que no depende de esa
# ruta GL) sí carga. Fix: sourcear gpu_env.sh acá también (mismo patrón que el modo nativo) y
# construir GPU_ENV_ARGS solo con las variables que gpu_env.sh realmente pobló — nunca forzar un
# override vacío para las que no aplican al método elegido (o si no hay método configurado).
if $USE_GPU; then
  [ -f "$HOME/scripts/entorno/gpu_env.sh" ] && source "$HOME/scripts/entorno/gpu_env.sh" 2>/dev/null || true
  GPU_ENV_ARGS="MESA_NO_ERROR=${MESA_NO_ERROR:-1} vblank_mode=${vblank_mode:-0}"
  [ -n "$GALLIUM_DRIVER" ] && GPU_ENV_ARGS="$GPU_ENV_ARGS GALLIUM_DRIVER=$GALLIUM_DRIVER"
  [ -n "$MESA_GL_VERSION_OVERRIDE" ] && GPU_ENV_ARGS="$GPU_ENV_ARGS MESA_GL_VERSION_OVERRIDE=$MESA_GL_VERSION_OVERRIDE"
  [ -n "$MESA_GLES_VERSION_OVERRIDE" ] && GPU_ENV_ARGS="$GPU_ENV_ARGS MESA_GLES_VERSION_OVERRIDE=$MESA_GLES_VERSION_OVERRIDE"
  [ -n "$LIBGL_DRI3_DISABLE" ] && GPU_ENV_ARGS="$GPU_ENV_ARGS LIBGL_DRI3_DISABLE=$LIBGL_DRI3_DISABLE"
else
  GPU_ENV_ARGS=""
fi

if $USE_DBUS && command -v dbus-launch &>/dev/null; then
  INNER="$INNER exec dbus-launch --exit-with-session $SESSION_CMD"
else
  INNER="$INNER exec $SESSION_CMD"
fi

# Wake lock (patrón sabamdarif/termux-desktop) — ver comentario en el modo nativo arriba.
command -v termux-wake-lock &>/dev/null && termux-wake-lock

echo "[OK] Iniciando $DE en la distro $DISTRO (DISPLAY=$DISPLAY_VAL, --shared-tmp --shared-home)..."
exec proot-distro login "$DISTRO" --shared-tmp --shared-home -- \
  env DISPLAY="$DISPLAY_VAL" XDG_RUNTIME_DIR=/tmp/xdg-runtime PULSE_SERVER=127.0.0.1 $GPU_ENV_ARGS \
  bash -c "$INNER"
GUISTARTEOF
  chmod +x "$ENTORNO_SCRIPTS/gui_start.sh"

  # gui_stop.sh — detiene sesiones de escritorio (nativas y dentro de distros) y,
  # con --x11, también el X11 embebido (mismo contrato que tx11_stop.sh).
  cat > "$ENTORNO_SCRIPTS/gui_stop.sh" << 'GUISTOPEOF'
#!/data/data/com.termux/files/usr/bin/bash
# gui_stop.sh — detiene sesiones de escritorio (nativas y dentro de distros).
# USO: gui_stop.sh [--x11]
#   --x11  además detiene el X11 embebido (mismo contrato que tx11_stop.sh)
STOP_X11=false
for arg in "$@"; do
  case "$arg" in
    --x11)       STOP_X11=true ;;
    -h|--help)   sed -n '1,10p' "$0"; exit 0 ;;
  esac
done

# Sesiones de escritorio (por DE y por wrappers usados por gui_start.sh)
pkill -f 'xfce4-session' 2>/dev/null
pkill -f 'startxfce4' 2>/dev/null
pkill -f 'lxqt-session' 2>/dev/null
pkill -f 'openbox-session' 2>/dev/null
pkill -f 'dbus-launch --exit-with-session' 2>/dev/null
pkill -f 'proot-distro login' 2>/dev/null
pkill -f 'tigervncserver' 2>/dev/null

# Fix real (humano181, bug 6 "instalo nativo y luego abrir con distro da error"): pkill
# solo manda SIGTERM y retorna al toque, sin esperar a que el proceso termine de verdad —
# xfce4-session/proot-distro login pueden tardar >0s en apagarse limpio. showConflictDialog()
# (EntornoFragment.kt) reintenta el arranque del OTRO modo apenas stopDesktopSession()
# retorna — si los procesos viejos siguen vivos un instante más, el nuevo DE puede chocar
# contra el X11 :1 todavía ocupado por la sesión anterior (dos gestores de ventana en el
# mismo display). Se agrega una espera acotada (hasta 3s) verificando que los procesos
# realmente murieron antes de reportar éxito, mismo patrón ya usado por pulse_start.sh en
# este mismo archivo.
for _i in 1 2 3; do
  pgrep -f 'xfce4-session|startxfce4|lxqt-session|openbox-session|dbus-launch --exit-with-session|proot-distro login' >/dev/null 2>&1 || break
  sleep 1
done
echo "[OK] Sesiones de escritorio detenidas"

# Libera el wake lock tomado por gui_start.sh (patrón sabamdarif/termux-desktop) —
# best-effort, no falla si no había ninguno tomado.
command -v termux-wake-unlock &>/dev/null && termux-wake-unlock

if $STOP_X11; then
  am broadcast -a com.termux.x11.ACTION_STOP -p com.termux 2>/dev/null || true
  am stopservice -n com.termux/.app.X11Service 2>/dev/null || true
  echo "[OK] X11 embebido detenido"
fi
GUISTOPEOF
  chmod +x "$ENTORNO_SCRIPTS/gui_stop.sh"

  # distro_setup_gui.sh — instala la DE elegida (xfce4/lxqt/mate/kde) + dbus dentro de
  # una distro proot. Antes solo soportaba xfce4 hardcodeado — ampliado (2026-08-18,
  # pedido explícito del usuario: la vía "con distro" debe poder elegir el mismo
  # abanico de DE que la vía nativa, ver EntornoNative.KNOWN_DESKTOPS) a
  # xfce4/lxqt/mate, mismo criterio de nombres de paquete por gestor que
  # KNOWN_DESKTOPS usa para el host (installDesktop() en EntornoNative.kt). "kde"
  # agregada (roadmap Mini PC item 2, MEJORAS_PENDIENTES.md 2026-08-28) SOLO acá, vía
  # distro — KDE Plasma no existe como paquete pkg nativo de Termux (fuera de alcance
  # del repo x11-packages, confirmado bug real docs/humano/humano116.md sobre
  # "plasma"), así que EntornoNative.KNOWN_DESKTOPS (el picker NATIVO, host) sigue sin
  # "kde" a propósito — ver EntornoNative.KNOWN_DESKTOPS_DISTRO para el abanico
  # ampliado que sí usa el picker "con distro".
  cat > "$ENTORNO_SCRIPTS/distro_setup_gui.sh" << 'DISTROGUIEOF'
#!/data/data/com.termux/files/usr/bin/bash
# distro_setup_gui.sh <distro> [<de>] [lite] — instala una DE (xfce4/lxqt/mate, default
# xfce4) + dbus dentro de una distro proot y crea ~/.xsession. Después se lanza con:
#   gui_start.sh --distro <distro> <de>
# (el login usa --shared-tmp --shared-home y DISPLAY=:1 — imprescindible para que la
#  distro vea el socket X11 del X11 embebido; causas 1/2 del header de entorno.sh)
# 3er argumento "lite" (auditoría GUI/distro 2026-08-28, docs/mini-pc/
# INVESTIGACION_REFERENCIAS_GUI_DISTRO_2026-08-26.md): instala el set mínimo de paquetes
# de la DE (sin metapaquetes "-goodies"/"-extra") para dispositivos de gama baja — solo
# verificado para apt (Debian/Ubuntu/Kali, gestor real de KNOWN_DISTROS) y pacman (Arch),
# porque son los únicos casos donde el paquete completo ya traía un metapaquete extra
# CONFIRMADO (xfce4-goodies, mate-extra) que se puede omitir sin inventar nombres nuevos.
# dnf/apk no tienen un caso "lite" ya confirmado en este script — el flag se acepta pero
# no cambia su comportamiento (ver comentario en cada rama).
DISTRO="$1"
DE="${2:-xfce4}"
LITE="${3:-}"
[ -z "$DISTRO" ] && { echo "uso: distro_setup_gui.sh <distro> [xfce4|lxqt|mate|kde] [lite]" >&2; exit 1; }
command -v proot-distro &>/dev/null || { echo "[ERROR] proot-distro no está instalado (módulo entorno)" >&2; exit 1; }
case "$DE" in
  xfce4|lxqt|mate|kde) : ;;
  *) echo "[ERROR] DE desconocida: $DE (soportadas: xfce4, lxqt, mate, kde)" >&2; exit 1 ;;
esac

echo "[STEP] Configurando GUI en la distro: $DISTRO (dbus-x11 + $DE${LITE:+ [lite]} + tigervnc)..."
# Roadmap Mini PC item 3 (MEJORAS_PENDIENTES.md 2026-08-28): mismo patrón de
# "setsid timeout -k 10 900" + limpieza de procesos huérfanos ya probado en
# ciberseguridad.sh PASO 7b (kali-tools-top10) — una DE pesada (KDE ~1.5-2GB,
# xfce4-goodies/mate-extra) en una red lenta puede colgarse indefinidamente sin
# esto, y un intento anterior interrumpido a mitad de un dpkg deja
# /var/lib/dpkg/lock-frontend tomado para siempre (mismo bug real ya confirmado
# ahí). "timeout -k 10 900" acota el intento a 15 minutos + SIGKILL a los 10s si
# el SIGTERM no alcanza (proot arma su propio árbol de procesos, ver comentario
# de ciberseguridad.sh); el self-heal "dpkg --configure -a" YA vivía dentro del
# bash -c de abajo desde antes (humano281) — se agrega acá la limpieza de
# procesos huérfanos de ESTE contenedor específico ANTES de reintentar, mismo
# criterio (no garantiza el 100% de los casos, reduce la chance real).
pkill -9 -f "proot-distro login $DISTRO" 2>/dev/null
pkill -9 -f "proot-distro/containers/$DISTRO/" 2>/dev/null
pkill -9 -f "proot-distro/installed-rootfs/$DISTRO" 2>/dev/null
setsid timeout -k 10 900 proot-distro login "$DISTRO" --shared-tmp --shared-home -- \
  bash -c '
    DE="$1"
    LITE="$2"
    export DEBIAN_FRONTEND=noninteractive
    _DE_OK=0
    _SESSION_BIN=""
    case "$DE" in
      xfce4) _SESSION_BIN=startxfce4 ;;
      lxqt)  _SESSION_BIN=startlxqt ;;
      mate)  _SESSION_BIN=mate-session ;;
      kde)   _SESSION_BIN=startplasma-x11 ;;
    esac
    # Fix real (humano181, bug 1 "da error al instalar entorno gráfico en la distro"):
    # antes toda la salida de apt-get iba a /dev/null — si la instalación fallaba (mirror
    # caído, sin red dentro del proot, paquete no encontrado, disco lleno), el único rastro
    # era el "[ERROR] La instalación de la DE falló..." genérico de más abajo, sin ninguna
    # pista real del motivo. Se captura la salida real en $_APT_LOG y se imprime su cola si
    # la instalación no queda funcional — mismo criterio de diagnosticabilidad que
    # EntornoFragment.errorDetail() ya espera poder mostrar (json.output).
    _APT_LOG=""
    if command -v apt-get >/dev/null 2>&1; then
      # dpkg --configure -a self-heal (mismo patrón ya usado en ciberseguridad.sh PASO 7b,
      # ver comentario ahí): bug real confirmado por ADB (docs/humano269.md) — si un paso
      # apt ANTERIOR dentro de este mismo contenedor (ej. kali-tools-top10 en el flujo de
      # ciberseguridad --variant pro-gui) quedó interrumpido a mitad de un dpkg (kill por
      # falta de memoria durante una instalación masiva de módulos en paralelo, señal 15),
      # dpkg queda en estado "interrupted" y CUALQUIER apt-get install posterior en el mismo
      # contenedor falla de inmediato con "E: dpkg was interrupted, you must manually run
      # dpkg --configure -a" (bug real confirmado: un apostrofe literal en este comentario,
      # dentro del bloque bash -c de comillas simples de mas abajo, cortaba la comilla externa
      # antes de tiempo y truncaba el script real ejecutado dentro del proot (error real:
      # unexpected end of file from if command, ver docs/humano281.md) — sin este self-heal,
      # ese estado roto se propagaba de
      # ciberseguridad.sh (Kali headless) a este script (GUI), haciendo fallar la GUI aunque
      # el motivo real no tuviera nada que ver con dbus-x11/xfce4/tigervnc.
      dpkg --configure -a >/dev/null 2>&1 || true
      _APT_LOG="$(apt-get update -y 2>&1)"; _APT_LOG="$_APT_LOG"$'\n'
      case "$DE" in
        xfce4)
          if [ "$LITE" = "lite" ]; then
            _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 xfce4 tigervnc-standalone-server 2>&1)"
          else
            _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 xfce4 xfce4-goodies tigervnc-standalone-server 2>&1)" || \
                   _APT_LOG="$_APT_LOG"$'\n'"$(apt-get install -y dbus-x11 xfce4 tigervnc-standalone-server 2>&1)"
          fi ;;
        lxqt)  _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 lxqt tigervnc-standalone-server 2>&1)" ;;
        mate)
          if [ "$LITE" = "lite" ]; then
            # mate-desktop-environment-core = variante mínima real de Debian/Ubuntu (sin
            # mate-extra ni las apps adicionales del metapaquete completo).
            _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 mate-desktop-environment-core tigervnc-standalone-server 2>&1)"
          else
            _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 mate-desktop-environment tigervnc-standalone-server 2>&1)" || \
                   _APT_LOG="$_APT_LOG"$'\n'"$(apt-get install -y dbus-x11 task-mate-desktop tigervnc-standalone-server 2>&1)"
          fi ;;
        kde)
          # kde-plasma-desktop = metapaquete mínimo real de Debian/Ubuntu (Plasma sin
          # kde-full/kde-standard, ~1.5-2GB igual — KDE no tiene una variante "core"
          # tan chica como xfce4/mate; "lite" acá solo evita instalar "kde-standard"/
          # "kde-full" si en el futuro se ofrecieran, no reduce el metapaquete mínimo
          # en sí porque no existe uno más chico documentado). Roadmap Mini PC item 2
          # (MEJORAS_PENDIENTES.md), nombre de paquete NO verificado en dispositivo
          # real todavía (regla empirical-verification-before-fix.md) — mismo criterio
          # EXPERIMENTAL que ya usa este proyecto para catálogo sin correr en vivo.
          _APT_LOG="$_APT_LOG$(apt-get install -y dbus-x11 kde-plasma-desktop tigervnc-standalone-server 2>&1)" ;;
      esac && _DE_OK=1
    elif command -v dnf >/dev/null 2>&1; then
      # Sin variante "lite" confirmada en dnf (no hay grupo mínimo ya verificado en este
      # script para xfce/lxqt/mate/kde) — mismo comando sin importar $LITE.
      case "$DE" in
        xfce4) dnf install -y dbus-x11 @xfce tigervnc-server >/dev/null 2>&1 ;;
        lxqt)  dnf install -y dbus-x11 @lxqt-desktop-environment tigervnc-server >/dev/null 2>&1 ;;
        mate)  dnf install -y dbus-x11 @mate-desktop tigervnc-server >/dev/null 2>&1 ;;
        kde)   dnf install -y dbus-x11 @kde-desktop-environment tigervnc-server >/dev/null 2>&1 ;;
      esac && _DE_OK=1
    elif command -v pacman >/dev/null 2>&1; then
      case "$DE" in
        xfce4)
          if [ "$LITE" = "lite" ]; then
            pacman -Sy --noconfirm dbus xfce4 tigervnc >/dev/null 2>&1
          else
            pacman -Sy --noconfirm dbus xfce4 xfce4-goodies tigervnc >/dev/null 2>&1
          fi ;;
        lxqt)  pacman -Sy --noconfirm dbus lxqt tigervnc >/dev/null 2>&1 ;;
        mate)
          if [ "$LITE" = "lite" ]; then
            pacman -Sy --noconfirm dbus mate tigervnc >/dev/null 2>&1
          else
            pacman -Sy --noconfirm dbus mate mate-extra tigervnc >/dev/null 2>&1
          fi ;;
        kde)
          if [ "$LITE" = "lite" ]; then
            # plasma-desktop = paquete mínimo real de Arch (sesión Plasma sin el resto
            # del metapaquete "plasma" completo: apps de KDE, Wayland, etc.).
            pacman -Sy --noconfirm dbus plasma-desktop tigervnc >/dev/null 2>&1
          else
            pacman -Sy --noconfirm dbus plasma tigervnc >/dev/null 2>&1
          fi ;;
      esac && _DE_OK=1
    elif command -v apk >/dev/null 2>&1; then
      # Alpine no tiene un metapaquete "kde" de una sola palabra confirmado (a
      # diferencia de xfce4/lxqt/mate, que sí coinciden con el nombre del paquete
      # apk real) — sin variante verificada, se deja fuera de esta rama a propósito
      # en vez de adivinar un nombre; $DE="kde" cae al warn genérico de abajo.
      if [ "$DE" != "kde" ]; then
        apk add --no-cache dbus-x11 "$DE" tigervnc >/dev/null 2>&1 && _DE_OK=1
      fi
    else
      echo "[WARN] gestor de paquetes desconocido — instalá la DE manualmente dentro de la distro"
    fi
    printf "#!/bin/sh\nexec dbus-launch --exit-with-session %s\n" "$_SESSION_BIN" > "$HOME/.xsession"
    chmod +x "$HOME/.xsession"
    echo "[OK] ~/.xsession creado ($_SESSION_BIN)"
    # Verificación FUNCIONAL real (bug corregido 2026-08-16 — antes un WARN de
    # apt-get/dnf/pacman/apk fallando no impedía el "[OK] Listo" final: la
    # sesión GUI se declaraba lista aunque la DE nunca se haya instalado). Se
    # verifica que el binario de la DE exista de verdad antes de reportar éxito.
    if [ "$_DE_OK" = "1" ] && command -v "$_SESSION_BIN" >/dev/null 2>&1; then
      echo "[OK] Entorno de escritorio ($DE) verificado — instalación funcional"
      exit 0
    else
      echo "[ERROR] La instalación de la DE falló o $_SESSION_BIN no está disponible — revisá red/repos de la distro"
      [ -n "$_APT_LOG" ] && echo "$_APT_LOG" | tail -c 500
      exit 1
    fi
  ' _ "$DE" "$LITE"
_DE_STATUS=$?
echo ""
if [ "$_DE_STATUS" = "0" ]; then
  echo "[OK] Listo. Para entrar al escritorio de la distro:"
  echo "    gui_start.sh --distro $DISTRO $DE"
  echo "    (y gui_stop.sh para terminar la sesión)"
else
  echo "[ERROR] La DE no quedó instalada correctamente en $DISTRO — gui_start.sh probablemente falle"
  exit 1
fi
DISTROGUIEOF
  chmod +x "$ENTORNO_SCRIPTS/distro_setup_gui.sh"

  # proot_menu_sync.sh — lanzadores .desktop en el HOST para apps de la distro
  # (patrón DroidDesk proot-menu-sync.sh). Best-effort.
  cat > "$ENTORNO_SCRIPTS/proot_menu_sync.sh" << 'PROOTMENUEOF'
#!/data/data/com.termux/files/usr/bin/bash
# proot_menu_sync.sh <distro> — genera lanzadores .desktop en el HOST para las apps
# instaladas DENTRO de la distro (patrón DroidDesk proot-menu-sync.sh). Best-effort:
# escanea /usr/share/applications de la distro y crea un .desktop por app cuyo Exec
# corre vía pdrun (mismo X11 embebido :1). No es un error si no hay apps.
DISTRO="$1"
[ -z "$DISTRO" ] && { echo "uso: proot_menu_sync.sh <distro>" >&2; exit 1; }
command -v proot-distro &>/dev/null || { echo "[ERROR] proot-distro no está instalado (módulo entorno)" >&2; exit 1; }
[ -x "$HOME/scripts/entorno/pdrun" ] || { echo "[ERROR] pdrun no existe — reinstalá el módulo entorno (--force)" >&2; exit 1; }

APPS_DIR="$HOME/.local/share/applications"
DESKTOP_DIR="$HOME/Desktop"
mkdir -p "$APPS_DIR" "$DESKTOP_DIR"

FILES=$(proot-distro login "$DISTRO" --shared-tmp --shared-home -- ls /usr/share/applications/ 2>/dev/null | grep '\.desktop$')
if [ -z "$FILES" ]; then
  echo "[INFO] No hay apps .desktop en la distro $DISTRO"
  exit 0
fi

COUNT=0
while IFS= read -r name; do
  APP="/usr/share/applications/$name"
  EXEC=$(proot-distro login "$DISTRO" --shared-tmp --shared-home -- sh -c 'grep -m1 "^Exec=" "$1" 2>/dev/null | cut -d= -f2-' _ "$APP")
  APPNAME=$(proot-distro login "$DISTRO" --shared-tmp --shared-home -- sh -c 'grep -m1 "^Name=" "$1" 2>/dev/null | cut -d= -f2-' _ "$APP")
  [ -z "$EXEC" ] && continue
  EXEC=$(echo "$EXEC" | sed 's/%[fFuUdDnNickvm]//g' | sed 's/^ *//')
  [ -z "$EXEC" ] && continue
  OUT="$APPS_DIR/kairos-$DISTRO-$name"
  cat > "$OUT" <<EOF
[Desktop Entry]
Type=Application
Name=${APPNAME:-$name} ($DISTRO)
Comment=App de la distro $DISTRO vía pdrun (X11 embebido :1)
Exec=$HOME/scripts/entorno/pdrun $DISTRO $EXEC
Icon=utilities-terminal
Categories=Utility;
Terminal=false
EOF
  cp "$OUT" "$DESKTOP_DIR/" 2>/dev/null || true
  COUNT=$((COUNT+1))
done <<< "$FILES"
echo "[OK] $COUNT lanzadores generados en: $APPS_DIR (y copiados a $DESKTOP_DIR)"
PROOTMENUEOF
  chmod +x "$ENTORNO_SCRIPTS/proot_menu_sync.sh"

  # webapp_launchers.sh — lanzadores .desktop para las WebUIs de otros módulos
  # de Kairos (n8n, Ollama, OpenClaw, OpenCode, IA Local). Patrón `url_to_app.sh`
  # de RDeX (referencia/termux/RDeX-main) — genera un .desktop por servicio web
  # ya instalado (según el registry) para que aparezca como ícono en el menú
  # de aplicaciones de la DE en vez de tener que recordar el puerto/URL a mano.
  # Reusa `am start -a android.intent.action.VIEW` (mismo mecanismo que ya usa
  # este script para lanzar X11Service más arriba) — no depende de termux-api.
  cat > "$ENTORNO_SCRIPTS/webapp_launchers.sh" << 'WEBAPPEOF'
#!/data/data/com.termux/files/usr/bin/bash
# webapp_launchers.sh — genera lanzadores .desktop para las WebUIs de módulos
# de Kairos ya instalados (n8n, Ollama, OpenClaw, OpenCode, IA Local/llama.cpp).
# Best-effort: un módulo no instalado simplemente no genera lanzador.
REGISTRY="$HOME/.android_server_registry"
APPS_DIR="$HOME/.local/share/applications"
DESKTOP_DIR="$HOME/Desktop"
mkdir -p "$APPS_DIR" "$DESKTOP_DIR"

_is_installed() { grep -q "^${1}\.installed=true" "$REGISTRY" 2>/dev/null; }

_make_launcher() {
  local id="$1" name="$2" port="$3" icon="${4:-utilities-terminal}"
  local out="$APPS_DIR/kairos-webapp-${id}.desktop"
  cat > "$out" <<EOF
[Desktop Entry]
Type=Application
Name=$name (Kairos)
Comment=Abre la WebUI de $name (http://127.0.0.1:$port)
Exec=am start -a android.intent.action.VIEW -d http://127.0.0.1:$port
Icon=$icon
Categories=Network;Utility;
Terminal=false
EOF
  cp "$out" "$DESKTOP_DIR/" 2>/dev/null || true
  echo "[OK] lanzador: $name → http://127.0.0.1:$port"
}

COUNT=0
_is_installed n8n         && { _make_launcher n8n         "n8n"          5678  network-workgroup; COUNT=$((COUNT+1)); }
_is_installed ollama      && { _make_launcher ollama      "Ollama"       11434 network-server;    COUNT=$((COUNT+1)); }
_is_installed openclaw    && { _make_launcher openclaw    "OpenClaw"     18789 utilities-terminal; COUNT=$((COUNT+1)); }
_is_installed opencode    && { _make_launcher opencode    "OpenCode"     3000  utilities-terminal; COUNT=$((COUNT+1)); }
_is_installed llamaserver && { _make_launcher llamaserver "IA Local"     8085  network-server;     COUNT=$((COUNT+1)); }

[ "$COUNT" -eq 0 ] && echo "[INFO] Ningún módulo con WebUI instalado todavía — sin lanzadores generados"
exit 0
WEBAPPEOF
  chmod +x "$ENTORNO_SCRIPTS/webapp_launchers.sh"

  log "$ENTORNO_SCRIPTS creado con scripts de gestión"
}

# ── Tools de escritorio en el HOST (best-effort) — DE nativa sin distro ──
_install_desktop_tools() {
  titulo "Tools de escritorio en HOST (best-effort)"
  local _sdk; _sdk=$(getprop ro.build.version.sdk 2>/dev/null)
  if [ -n "$_sdk" ] && [ "$_sdk" -ge 31 ]; then
    warn "Android SDK $_sdk: desactivá 'Disable phantom process killer' en Opciones de desarrollador para que la DE no muera sola (patrón termux-desktop)"
  fi
  if command -v pkg &>/dev/null; then
    # Bug #21 arreglado (auditoría ADB, ver docs/humano/humano193.md): faltaba pkg_update_with_fallback()
    # antes de este "pkg install" — el fix del bug de mirror roto (2026-08-12) nunca se propagó
    # a esta función específica, así que "Visor VNC (:5901)" fallaba siempre con
    # "No mirror or mirror group selected" + "curl: command not found" sin ningún indicio real.
    pkg_update_with_fallback
    # Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por
    # ADB): "dbus-x11" NO es un paquete real de Termux (confirmado con `pkg search dbus` — solo
    # existen "dbus"/"dbus-glib"/etc., ninguno "dbus-x11"; ese nombre es la convención de
    # Debian/Ubuntu, que sí separa dbus-launch en un paquete aparte). Con un nombre de paquete
    # inválido en la lista, TODO el "pkg install" fallaba de forma atómica — ni xfce4 ni ningún
    # otro paquete de la lista se instalaba nunca, silenciado por el "|| warn" de abajo (que
    # sigue de largo sin marcar la instalación como realmente fallida). En Termux `dbus-launch`
    # ya viene incluido en el paquete "dbus" (confirmado: `command -v dbus-launch` responde con
    # dbus instalado solo) — no hace falta ningún paquete separado para eso acá.
    pkg install -y xfce4 xfce4-goodies tigervnc x11vnc pavucontrol 2>/dev/null || \
      warn "Algunos paquetes de escritorio fallaron (puede que ya estén o que el repo no los tenga)"
  else
    warn "pkg no disponible — no se instalaron tools de escritorio"
  fi
  log "Tools de escritorio instaladas (best-effort)"
}

# ── Base para CLIs de IA en el HOST (best-effort) ──
_install_ai_tools() {
  titulo "Base CLIs de IA en HOST (best-effort)"
  if command -v pkg &>/dev/null; then
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y python3 nodejs-lts git curl 2>/dev/null || \
      warn "Algunos paquetes de base IA fallaron (puede que ya estén)"
  else
    warn "pkg no disponible — no se instaló la base de IA"
  fi
  log "Base de IA instalada (best-effort)"
}

# ════════════════════════════════════════════════════════════════
#  EJECUCIÓN PRINCIPAL — siempre silenciosa desde la app (SILENT
#  siempre implícito acá, pero se respeta el flag igual por si se
#  invoca standalone para pruebas manuales)
# ════════════════════════════════════════════════════════════════

# Diagnóstico X11 (subcomando `diagnose` / flag `--diagnose`) — funciona sin importar
# si el módulo está instalado o no.
if $DIAGNOSE; then
  _diagnose
  exit 0
fi

command -v getent &>/dev/null && [ "$(getent group aid_inet 2>/dev/null)" ] && log "aid_inet detectado"

if ! $FORCE && [ "$(grep "^entorno\.installed=" "$REGISTRY" 2>/dev/null | cut -d= -f2)" = "true" ]; then
  info "Entorno ya instalado (usa --force para reinstalar)"
  exit 0
fi

GPU_TYPE=$(_check_gpu)
GPU_METHOD="auto"

check_done "entorno_arch" || {
  titulo "Arquitectura"
  ARCH=$(uname -m)
  case "$ARCH" in
    aarch64|arm64) : ;;
    *) error "Solo ARM64 (aarch64/arm64) — detectado: $ARCH" ;;
  esac
  log "Arquitectura: $ARCH"
  mark_done "entorno_arch"
}

check_done "entorno_pkg_update" || {
  titulo "Actualizando índice de paquetes"
  pkg_update_with_fallback
  mark_done "entorno_pkg_update"
}

check_done "entorno_proot_distro" || {
  _install_proot_distro
  mark_done "entorno_proot_distro"
}

check_done "entorno_udocker" || {
  _install_udocker
  mark_done "entorno_udocker"
}

check_done "entorno_x11" || {
  _install_x11
  mark_done "entorno_x11"
}

check_done "entorno_pulse" || {
  _install_pulseaudio
  mark_done "entorno_pulse"
}

check_done "entorno_gpu" || {
  _install_gpu_native
  mark_done "entorno_gpu"
}

check_done "entorno_dirs" || {
  _create_scripts
  mark_done "entorno_dirs"
}

check_done "entorno_desktop_tools" || {
  _install_desktop_tools
  mark_done "entorno_desktop_tools"
}

check_done "entorno_ai_tools" || {
  _install_ai_tools
  mark_done "entorno_ai_tools"
}

check_done "entorno_registry" || {
  update_registry "1.2.0"
  mark_done "entorno_registry"
}

log "Entorno instalado correctamente — GPU: ${GPU_TYPE} (${GPU_METHOD})"
_sdk_final=$(getprop ro.build.version.sdk 2>/dev/null)
[ -n "$_sdk_final" ] && [ "$_sdk_final" -ge 31 ] && \
  warn "Android SDK $_sdk_final: si la GUI no renderiza o muere sola, desactivá 'Disable phantom process killer' en Opciones de desarrollador (mini-PC)"
notify_event "entorno" "install_done" "GPU: ${GPU_TYPE}"
exit 0
