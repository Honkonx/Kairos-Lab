#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · qemu.sh (silent mode)
#  Módulo QEMU — emulación de CPU/binarios en Termux
#
#  INVESTIGACIÓN REAL (antes de escribir esto — mismo criterio de
#  honestidad que modulos/mimocode.sh):
#    - Termux SÍ tiene paquetes reales de QEMU en su repo (algunos vía
#      x11-repo): qemu-system-x86-64(-headless), qemu-system-aarch64,
#      qemu-system-i386, qemu-system-arm, qemu-utils, y los paquetes
#      qemu-user-<arch> (modo usuario: qemu-x86_64, qemu-arm, etc.)
#    - Android NO expone /dev/kvm a apps sin root — no hay forma de dar
#      aceleración por hardware a qemu-system sin rootear el dispositivo.
#      Sin KVM, qemu-system corre en TCG (traducción de instrucciones por
#      software) — funciona, pero MUY por debajo de nativo: útil para
#      probar un binario o bootear una distro headless liviana, NO para
#      un uso "de escritorio" fluido (una VM gráfica pesada va a ser
#      lenta, a veces frustrante).
#    - qemu-user (qemu-x86_64, qemu-aarch64, qemu-arm, etc.) SÍ es
#      genuinamente útil sin root: corre un binario estático de otra
#      arquitectura directo (ej. un binario x86_64 en un teléfono ARM64),
#      sin necesitar una VM completa ni binfmt_misc (que sí requiere
#      root) — se invoca explícito: "qemu-x86_64 ./mi_binario_x86_64".
#      Este es el caso de uso más sólido de QEMU en Termux sin root.
#    - qemu-system-aarch64-headless TAMBIÉN es un paquete real de Termux
#      (confirmado 2026-09-08 bajando e inspeccionando el .deb real de
#      packages.termux.dev, no solo leyendo el nombre en un README de
#      terceros — ver empirical-verification-before-fix.md): mismo
#      arquitectura que el host (ARM64), sin traducción de ISA cruzada
#      (a diferencia de qemu-system-x86_64, que SIEMPRE traduce x86_64→
#      TCG IR en un host ARM64) — sigue siendo TCG software puro (Android
#      no da /dev/kvm sin root, esto NO cambia), pero es el camino
#      genuinamente más liviano/recomendado para bootear un guest Linux
#      completo en este entorno. Requiere firmware UEFI real (el guest
#      ISO/qcow2 lo espera): confirmado que el paquete `qemu-common`
#      (dependencia real de qemu-system-aarch64-headless, se instala
#      solo) trae `edk2-aarch64-code.fd` en
#      $PREFIX/share/qemu/edk2-aarch64-code.fd — sin `-bios` apuntando
#      ahí, un guest UEFI (Alpine virt, Debian/Ubuntu cloud image) NO
#      bootea. La máquina `virt` (aarch64) no tiene bus IDE como `q35`
#      (x86_64) — el disco/ISO se conecta como virtio-blk-pci genérico
#      (`-drive if=none,... -device virtio-blk-pci,drive=...`), no como
#      `-cdrom`/IDE — ver run_vm.sh para el detalle real de ambas ramas.
#
#  QUÉ EXPONE ESTE MÓDULO (honesto, sin prometer de más):
#    ✅ qemu-user-x86-64 + qemu-user-arm — correr binarios estáticos de
#       otra arquitectura (rápido, uso real, sin root)
#    ✅ qemu-system-aarch64-headless (RECOMENDADO, mismo arch que el
#       host) + qemu-system-x86-64-headless (cross-arch, más lento) +
#       qemu-utils — bootear una VM headless SIN gráficos por defecto
#       (consola serie/-nographic) o con VNC (framebuffer real vía el
#       servidor VNC propio de QEMU). SIN aceleración KVM → software
#       puro en AMBAS arquitecturas de guest (esperá minutos de boot,
#       no segundos) — aarch64 evita la traducción cruzada de ISA pero
#       sigue sin ser instantáneo.
#    ❌ NO incluye ninguna imagen/ISO de sistema operativo propia — el
#       catálogo curado que ofrece la UI de Kairos (QemuFragment.kt)
#       descarga imágenes oficiales reales de Alpine/Debian/Ubuntu, este
#       script en sí no trae ninguna
#    ❌ NO es una alternativa a una VM de escritorio con GPU — sin KVM
#       una VM gráfica pesada (ej. Windows) va a ser prácticamente
#       inusable en este entorno; no se recomienda para eso
#
#  USO DESDE APP (KairosApp):
#    bash qemu.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#    --describe Manifiesto declarativo
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.4.0 | Septiembre 2026 (agrega un socket QMP real —
#  `-qmp unix:<vmdir>/qmpsocket,server,nowait`, ver
#  docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md hallazgo #2 — en
#  AMBOS modos de boot (console y vnc), y cambia el VNC de QEMU de TCP
#  loopback (`-vnc 127.0.0.1:2`) a socket unix (`-vnc unix:<vmdir>/vncsocket`,
#  hallazgo #3 de la misma auditoría — "Allow connections only from
#  localhost using localsocket without a password", patrón real de
#  Vectras-VM-Emu-Android `StartVM.getDisplayParams()`). `<vmdir>` es
#  siempre `$QEMU_SCRIPTS` ($HOME/scripts/qemu) — este módulo modela UNA
#  sola VM corriendo a la vez (sesión tmux `kairos_qemu_vnc` única, ver
#  más abajo), así que ambos sockets pueden vivir en una ruta fija sin
#  colisionar. v1.3.0 había agregado qemu-system-aarch64-headless — mismo
#  arch que el host, ver investigación arriba — y un 6to argumento a
#  run_vm.sh: guest_arch (x86_64|aarch64, default x86_64 para retrocompat
#  con cualquier caller viejo de 5 argumentos). v1.2.0 (Agosto 2026) había
#  agregado -machine q35 + hostfwd SSH al puerto 2222 del host (patrón
#  tomado de referencia/emuladores/docker-in-termux-main/README.md) y un
#  4to argumento de modo — console|vnc — ver docs/arquitectura/
#  PROPUESTA_QEMU_DISPLAY_2026-08-26.md)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ───────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ─────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"qemu","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"qemu-user (correr binarios de otra arquitectura, sin root, uso real) + qemu-system headless aarch64 (recomendado, mismo arch que el host) y x86_64 (cross-arch, mas lento) SIN aceleracion KVM (Android no expone /dev/kvm sin root) — software puro (TCG); no incluye ninguna imagen de SO"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Los paquetes qemu-user*/
# qemu-system* son paquetes apt completos (mismo criterio que clang.sh/
# python.sh: no se empaquetan). Lo propio de Kairos son los wrappers en
# $HOME/scripts/qemu/ (run_user.sh, run_vm.sh) — esos sí se empaquetan.
if $DESCRIBE_FILES; then
  jq -n \
    --arg glob "$HOME/scripts/qemu/**" \
    --arg verify "command -v qemu-x86_64 >/dev/null 2>&1" \
    '{
      id: "qemu",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-qemu",
      version_registry_key: "qemu.version",
      files: [],
      file_globs: [{pattern: $glob, required: true, note: "wrappers generados (run_user.sh, run_vm.sh)"}],
      dependencies: [
        {id: "pkg:qemu-user", check_cmd: "command -v qemu-x86_64 >/dev/null 2>&1", install_hint: "pkg install -y qemu-user-x86-64 qemu-user-arm"},
        {id: "pkg:qemu-system-aarch64", check_cmd: "command -v qemu-system-aarch64 >/dev/null 2>&1", install_hint: "pkg install -y qemu-system-aarch64-headless qemu-utils"},
        {id: "pkg:qemu-system-x86_64", check_cmd: "command -v qemu-system-x86_64 >/dev/null 2>&1", install_hint: "pkg install -y qemu-system-x86-64-headless qemu-utils"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "Los paquetes qemu-user-*/qemu-system-* son paquetes apt completos — no se snapshotean, reinstalar vía pkg es más simple",
        "No incluye ninguna imagen/ISO de sistema operativo — el usuario las trae por su cuenta"
      ]
    }'
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_qemu_checkpoint"
QEMU_SCRIPTS="$HOME/scripts/qemu"

# ── log/warn/error/info/step + check_done/mark_done/registry_write compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh" 2>/dev/null || {
  echo "Error: lib.sh no encontrado"
  exit 1
}

# ── Ya instalado ────────────────────────────────────────────
# Gate real de "ya instalado" — requiere qemu-user Y AL MENOS uno de los dos
# qemu-system (aarch64 recomendado, x86_64 cross-arch). No exige AMBOS
# system-modes porque un mirror caído puede tumbar uno solo sin impedir que
# el otro (y qemu-user) hayan quedado perfectamente funcionales — PASO 3 más
# abajo reintenta cualquiera que falte igual, sea cual sea la razón de entrar
# a este bloque.
if command -v qemu-x86_64 &>/dev/null && { command -v qemu-system-aarch64 &>/dev/null || command -v qemu-system-x86_64 &>/dev/null; } && ! $FORCE; then
  log "QEMU ya instalado — $(command -v qemu-system-aarch64 &>/dev/null && qemu-system-aarch64 --version 2>/dev/null | head -1 || qemu-system-x86_64 --version 2>/dev/null | head -1)"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   kairos-app · QEMU Installer                ║
  ║   Emulación CPU/binarios · v1.0.0            ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  IMPORTANTE (léelo antes de instalar):"
  echo "  Android NO da acceso a /dev/kvm sin root — sin aceleración"
  echo "  por hardware, qemu-system corre TODO por software (TCG):"
  echo "  útil para una VM headless liviana, pero LENTO (minutos de"
  echo "  boot). qemu-user (correr un binario suelto de otra arch,"
  echo "  ej. x86_64 en un teléfono ARM64) sí es rápido y confiable."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ============================================================
# PASO 1 — x11-repo (algunos paquetes qemu-system viven ahí)
# ============================================================
step "1/3 Habilitando x11-repo"

if check_done "qemu_x11repo"; then
  log "x11-repo ya habilitado [checkpoint]"
else
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md. Sin "2>/dev/null"
  # — mismo motivo que PASO 2/PASO 3 abajo: para que el error real de apt/dpkg llegue al log
  # en vez de descartarse.
  pkg_update_with_fallback
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    x11-repo || warn "x11-repo no se pudo instalar — algunos paquetes qemu-system pueden faltar"
  pkg_update_with_fallback
  mark_done "qemu_x11repo"
fi

# ============================================================
# PASO 2 — qemu-user (modo usuario — el caso de uso sólido sin root)
# ============================================================
step "2/3 Instalando qemu-user (x86_64 + arm)"

if check_done "qemu_user"; then
  log "qemu-user ya instalado [checkpoint]"
else
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  # Sin "2>/dev/null" — el usuario reportó (2026-08-25, ver docs/adb/AUDITORIA_MODULO_POR_MODULO_
  # 2026-08-24.md) que ni qemu-user ni qemu-system quedaron instalados y el log de instalación
  # (~/kairos_logs/install_qemu.log, capturado por ModuleController.kt desde stdout+stderr
  # combinados) no traía ninguna pista real de por qué — el "2>/dev/null" de antes descartaba el
  # error real de apt/dpkg (mirror caído, paquete no disponible, lo que sea) antes de que
  # pudiera llegar al log.
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    qemu-user-x86-64 qemu-user-arm || warn "Algunos paquetes qemu-user fallaron"
  command -v qemu-x86_64 &>/dev/null && log "qemu-x86_64 (modo usuario) OK" || warn "qemu-x86_64 no quedó disponible"
  mark_done "qemu_user"
fi

# ============================================================
# PASO 3 — qemu-system headless (sin KVM — TCG software), AMBAS
# arquitecturas de guest: aarch64 (RECOMENDADO, mismo arch que el host,
# sin traducción cruzada de ISA) + x86_64 (cross-arch, más lento). Ver
# investigación real en la cabecera del archivo — ambos paquetes
# confirmados reales en el repo de Termux (qemu-system-aarch64-headless
# incluido), no asumidos por analogía con el nombre de x86_64.
# ============================================================
step "3/3 Instalando qemu-system-aarch64-headless + qemu-system-x86-64-headless + qemu-utils"

if check_done "qemu_system"; then
  log "qemu-system ya instalado [checkpoint]"
else
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  # Mismo motivo que PASO 2 — sin "2>/dev/null", para que el error real de apt/dpkg llegue al
  # log en vez de descartarse. Un solo comando `pkg install` con ambos paquetes: si uno de los
  # dos falla (ej. mirror caído para ese .deb puntual) apt igual intenta instalar el resto —
  # cada `command -v` de abajo confirma el resultado real de CADA uno por separado, no asume
  # éxito conjunto del exit code combinado (empirical-verification-before-fix.md).
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    qemu-system-aarch64-headless qemu-system-x86-64-headless qemu-utils || \
    warn "Alguno de los paquetes qemu-system falló al instalar — revisando cuál quedó real abajo"
  command -v qemu-system-aarch64 &>/dev/null && log "qemu-system-aarch64 OK (recomendado, mismo arch que el host — sin KVM, TCG software)" || \
    warn "qemu-system-aarch64 no quedó disponible"
  command -v qemu-system-x86_64 &>/dev/null && log "qemu-system-x86_64 OK (cross-arch — sin KVM, TCG software, más lento)" || \
    warn "qemu-system-x86_64 no quedó disponible"
  mark_done "qemu_system"
fi

# ── Scripts wrapper ──────────────────────────────────────────
mkdir -p "$QEMU_SCRIPTS"

cat > "$QEMU_SCRIPTS/run_user.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: run_user.sh <arch> <binario> [args...]
# <arch>: x86_64 | arm   (agregá el paquete qemu-user-<arch> si falta otra)
ARCH="$1"; BIN="$2"; shift 2 2>/dev/null
[ -z "$ARCH" ] || [ -z "$BIN" ] && { echo "uso: run_user.sh <x86_64|arm> <binario> [args...]" >&2; exit 1; }
command -v "qemu-$ARCH" &>/dev/null || { echo "[ERROR] qemu-$ARCH no instalado" >&2; exit 1; }
exec "qemu-$ARCH" "$BIN" "$@"
SCRIPT
chmod +x "$QEMU_SCRIPTS/run_user.sh"

cat > "$QEMU_SCRIPTS/run_vm.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: run_vm.sh <imagen.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc] [resolucion_WxH] [x86_64|aarch64]
# Bootea SIN KVM (TCG software, lento: esperá minutos, no segundos). Este
# módulo NO trae ninguna imagen: conseguí un .qcow2/.iso liviano vos mismo
# (ej. Alpine Linux) y pasá la ruta.
#
# Modo de salida (4to argumento, default "console" — retrocompatible con
# cualquier caller viejo que solo pasaba 3 argumentos, ver
# docs/arquitectura/PROPUESTA_QEMU_DISPLAY_2026-08-26.md sección 5 opción 1/2):
#   console (default) — -nographic, consola serie redirigida a este stdio.
#   vnc                — -vnc unix:<vmdir>/vncsocket (v1.4.0 — socket unix,
#                         no TCP, ver comentario real más abajo junto a
#                         QMP_SOCK/VNC_SOCK; antes era 127.0.0.1:2/puerto 5902,
#                         el visor VNC nativo de Kairos ya usa 5901 para Mini
#                         PC vía EntornoNative.vncStart()/VncViewerActivity.kt).
#                         Sin -nographic acá: la consola serie del guest deja
#                         de imprimirse en este stdio, la salida real ahora es
#                         el framebuffer gráfico servido por VNC.
#
# Resolución (5to argumento, opcional, solo aplica en modo vnc — pedido
# explícito del usuario 2026-09-03: "mejoras el modulo qemu para poder
# configurar su vnc desde el propio modulo"): "WxH" (ej. "1280x720"). SOLO
# tiene efecto real con guest_arch=x86_64 — se pasa como `-g WxHx32` (flag
# real de qemu-system, ver qemu.org/docs/master/system/invocation.html), que
# fija el modo gráfico inicial del framebuffer VGA estándar que solo existe
# en la máquina `q35`. La máquina `virt` (aarch64) usa virtio-gpu-pci en vez
# de una VGA BIOS clásica — `-g` no aplica ahí (honesto: se ignora con un
# aviso en vez de fingir que funciona), la resolución la negocia el driver
# virtio-gpu del guest. Vacío (default, retrocompat con cualquier caller que
# solo pase 4 argumentos) = sin -g, comportamiento de siempre.
#
# Arquitectura del guest (6to argumento, opcional, default "x86_64" —
# retrocompat con cualquier caller viejo de 5 argumentos, que SIEMPRE
# arrancaba x86_64): "aarch64" (RECOMENDADO — mismo arch que el host, sin
# traducción cruzada de ISA, ver investigación real en la cabecera de
# modulos/qemu.sh) o "x86_64" (cross-arch, más lento, pero útil para probar
# binarios/imágenes específicamente x86_64). Cada arch usa una máquina QEMU
# distinta — NO son intercambiables:
#   x86_64  → -machine q35 (chipset PC moderno, bus IDE/AHCI real, VGA BIOS
#             clásica — por eso -cdrom/-g funcionan acá)
#   aarch64 → -machine virt -cpu max -bios <edk2-aarch64-code.fd real, del
#             paquete qemu-common, confirmado con `tar tf` sobre el .deb
#             real de packages.termux.dev el 2026-09-08 — sin esto un guest
#             UEFI como Alpine/Debian/Ubuntu NO bootea, se queda en un
#             prompt EFI vacío). La máquina `virt` NO tiene bus IDE — el
#             disco se conecta como virtio-blk-pci genérico (-drive
#             if=none,... + -device virtio-blk-pci), nunca como -cdrom.
#
# Red: -netdev user,id=net0 + -device virtio-net-pci,netdev=net0 — SLIRP
# (modo usuario), 100% userspace, sin root, sin /dev/net/tun, sin binfmt_misc.
# Gap real confirmado en docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md
# ("qemu: Flags/opciones oficiales NO expuestas") — antes la VM headless
# arrancaba sin ninguna interfaz de red, sin salida a internet. Sintaxis
# verificada contra la doc oficial (qemu.org/docs/master/system/invocation.html):
# NO usar -net tap/bridge (requiere root/kernel tun que Android no expone).
# virtio-net-pci funciona igual en ambas máquinas (q35 Y virt traen un root
# complex PCIe real).
#
# hostfwd + -machine q35 (x86_64): patrón tomado de
# referencia/emuladores/docker-in-termux-main/README.md (guía real de correr
# Alpine x86_64 headless en Termux vía QEMU) — sin hostfwd la VM tenía salida
# a internet pero era imposible entrar por SSH desde el host (Termux) sin
# pasar por la consola serie; q35 es el chipset recomendado por esa guía
# (más moderno que el "pc" por defecto, mejor soporte virtio).
IMG="$1"; RAM="${2:-512}"; SSH_PORT="${3:-2222}"; MODE="${4:-console}"; RESOLUTION="${5:-}"; GUEST_ARCH="${6:-x86_64}"
USAGE="uso: run_vm.sh <imagen.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc] [resolucion_WxH] [x86_64|aarch64]"
[ -z "$IMG" ] && { echo "$USAGE" >&2; exit 1; }
[ -f "$IMG" ] || { echo "[ERROR] No existe: $IMG" >&2; exit 1; }
case "$GUEST_ARCH" in
  x86_64|aarch64) ;;
  *) echo "[WARN] guest_arch '$GUEST_ARCH' desconocido — usando x86_64" >&2; GUEST_ARCH="x86_64" ;;
esac
QEMU_BIN="qemu-system-$GUEST_ARCH"
command -v "$QEMU_BIN" &>/dev/null || { echo "[ERROR] $QEMU_BIN no instalado" >&2; exit 1; }
echo "[INFO] Guest: $GUEST_ARCH $([ "$GUEST_ARCH" = "aarch64" ] && echo "(mismo arch que el host — sin traducción cruzada de ISA)" || echo "(cross-arch — más lento que aarch64)")"
echo "[INFO] Sin KVM (Android no da /dev/kvm sin root) — TCG software, el boot va a tardar."
echo "[INFO] Red: modo usuario (SLIRP) vía -netdev user — sin root, sin TUN/TAP."
echo "[INFO] SSH host→VM: si la VM corre sshd en el puerto 22, conectate con 'ssh -p $SSH_PORT root@localhost'."

# Socket QMP (QEMU Machine Protocol, control real de la VM en caliente — apagado graceful,
# pausar/reanudar, screendump, hot-swap de medios) — hallazgo real de auditoría, ver
# docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md hallazgo #2. Socket unix (no TCP): un
# cliente Kotlin (com.termux.app.qemu.QmpClient) conecta via android.net.LocalSocket, mismo
# criterio de seguridad que VNC_SOCK más abajo. "$0" es siempre la ruta real de ESTE script
# (run_vm.sh, que vive en $QEMU_SCRIPTS) tanto si lo invoca qemu.sh como si el usuario lo corre
# a mano — dirname da la carpeta de estado real de la VM sin depender de una variable heredada
# del proceso padre. Una sola VM corre a la vez (sesión tmux kairos_qemu_vnc única, ver más
# abajo) así que un socket de ruta FIJA (no por-VM) es suficiente — no hay dos VMs concurrentes
# que puedan colisionar en el mismo archivo.
VM_STATE_DIR="$(cd "$(dirname "$0")" && pwd)"
QMP_SOCK="$VM_STATE_DIR/qmpsocket"
VNC_SOCK="$VM_STATE_DIR/vncsocket"
QMP_FLAG=(-qmp "unix:$QMP_SOCK,server,nowait")
# Socket stale de una sesión anterior (crash, kill -9, tmux kill-session sin que QEMU alcanzara
# a limpiar su propio bind) — QEMU con "server,nowait" falla al bindear si el archivo ya existe.
# Inofensivo si no existían (rm -f no falla).
rm -f "$QMP_SOCK" "$VNC_SOCK"

# Bug real confirmado (2026-08-27, ver docs/humano256.md): el código viejo SIEMPRE
# forzaba "-drive file=$IMG,format=qcow2", incluso para un .iso (ej. el catálogo
# de descarga ofrece alpine-virt-*.iso). Un ISO9660 no es qcow2 — qemu detecta el
# formato inválido y sale con error DESPUÉS de haber reemplazado el proceso bash
# (por el "exec" de la línea vieja), así que el "|| exec ...-cdrom" de fallback
# nunca se ejecutaba (exec ya reemplazó el proceso, no hay bash vivo para evaluar
# el "||"). Resultado real observado: la terminal volvía al prompt al instante,
# sin ningún log de qemu (el "2>/dev/null" viejo silenciaba el error), y en modo
# vnc el server nunca llegaba a levantar → ECONNREFUSED en VncViewerActivity.
# Fix: decidir el FORMATO (no todavía el flag final de disco) por extensión real
# del archivo — .iso siempre es un filesystem ISO9660 (format=raw para qemu-img/
# -drive, no existe "format=iso"), .qcow2 es qcow2, cualquier otra cosa (.img/
# .raw/sin extensión, ej. las cloud images de Debian/Ubuntu del catálogo) es raw.
case "${IMG##*.}" in
  iso|ISO) IMG_FORMAT="raw" ;;
  qcow2|QCOW2) IMG_FORMAT="qcow2" ;;
  *) IMG_FORMAT="raw" ;;
esac

# El FLAG final de disco sí depende de la máquina — q35 (x86_64) tiene bus IDE
# real y el atajo -cdrom es válido ahí para .iso; virt (aarch64) NO tiene bus
# IDE, cualquier medio (disco o ISO) se conecta igual como virtio-blk-pci
# genérico (confirmado: los guests UEFI que ofrece el catálogo — Alpine virt,
# cloud images de Debian/Ubuntu — bootean su ISO9660/qcow2 desde un bloque
# virtio sin necesitar semántica real de "cdrom").
if [ "$GUEST_ARCH" = "aarch64" ]; then
  DISK_FLAG=(-drive "if=none,format=$IMG_FORMAT,file=$IMG,id=hd0" -device virtio-blk-pci,drive=hd0)
elif [ "${IMG##*.}" = "iso" ] || [ "${IMG##*.}" = "ISO" ]; then
  DISK_FLAG=(-cdrom "$IMG")
else
  DISK_FLAG=(-drive "file=$IMG,format=$IMG_FORMAT")
fi

# Flags de máquina/CPU/firmware — la parte que de verdad distingue ambas
# arquitecturas de guest (ver comentario largo de guest_arch más arriba).
if [ "$GUEST_ARCH" = "aarch64" ]; then
  EDK2_BIOS="${PREFIX:-/data/data/com.termux/files/usr}/share/qemu/edk2-aarch64-code.fd"
  [ -f "$EDK2_BIOS" ] || echo "[WARN] No se encontró el firmware UEFI ($EDK2_BIOS) — ¿quedó bien instalado qemu-common? La VM probablemente no bootee." >&2
  MACHINE_FLAGS=(-machine virt -cpu max -bios "$EDK2_BIOS")
else
  MACHINE_FLAGS=(-machine q35)
fi

if [ "$MODE" = "vnc" ]; then
  # Socket unix en vez de TCP 127.0.0.1:5902 (v1.4.0, hallazgo #3 de la auditoría de
  # comunicación 2026-09-08 — "Allow connections only from localhost using localsocket without
  # a password", patrón real de Vectras-VM-Emu-Android StartVM.getDisplayParams()): mismo
  # comportamiento visible para el usuario (el visor VNC nativo de Kairos sigue conectando
  # automático), pero el socket queda restringido por permisos de archivo del propio $HOME de
  # la app en vez de expuesto en un puerto loopback conectable por cualquier proceso del mismo
  # UID. VncClient.kt/VncViewerActivity.kt ya saben conectar sobre android.net.LocalSocket.
  echo "[INFO] Modo VNC — servidor QEMU en socket unix $VNC_SOCK. El visor VNC nativo de Kairos conecta ahí directo (ya no es un puerto TCP)."
  DISPLAY_FLAG="-vnc unix:$VNC_SOCK"
  VGA_FLAG=""
  if [ "$GUEST_ARCH" = "aarch64" ]; then
    # virt no trae GPU por defecto (a diferencia de q35, que sí trae VGA
    # implícita) — sin esto, VNC levantaría el puerto pero sin ningún
    # framebuffer real que mostrar.
    VGA_FLAG="-device virtio-gpu-pci"
    [ -n "$RESOLUTION" ] && echo "[INFO] Resolución '$RESOLUTION' ignorada — virtio-gpu (aarch64) no soporta -g, la negocia el driver del guest."
  elif [ -n "$RESOLUTION" ]; then
    VGA_FLAG="-g ${RESOLUTION}x32"
    echo "[INFO] Resolución solicitada: ${RESOLUTION} (flag -g, framebuffer VGA estándar)."
  fi
else
  echo "[INFO] Modo consola — salida serie redirigida a esta terminal (-nographic)."
  DISPLAY_FLAG="-nographic"
  VGA_FLAG=""
fi

if [ "$MODE" = "vnc" ]; then
  # Modo VNC = uso silencioso desde la UI (QemuFragment.bootVmVnc()), sin terminal
  # visible — igual que los módulos con servicio de fondo (ollama/n8n/openclaw),
  # arranca en una sesión tmux detached (ver modulos/ollama.sh) en vez de "exec" en
  # foreground: así este script vuelve al instante y Kairos puede sondear la
  # existencia real de $VNC_SOCK sin bloquear ningún hilo ni necesitar la terminal
  # adaptada.
  # QEMU_VM_LOG: log real para diagnosticar si el server nunca llega a levantar
  # (ej. ISO corrupta, RAM insuficiente) — VncViewerActivity solo ve "conexión
  # rechazada", no el motivo real; este log sí lo tiene.
  #
  # Se arma un script wrapper temporal (en vez de interpolar el comando a mano
  # dentro de un string para "tmux new-session") para que rutas con espacios/
  # comillas en $IMG no rompan el quoting — printf %q escapa cada argumento de
  # forma segura, un array interpolado a mano ("${DISK_FLAG[*]}") no lo hace.
  command -v tmux &>/dev/null || { echo "[ERROR] tmux no instalado — necesario para el modo VNC en segundo plano" >&2; exit 1; }
  QEMU_VM_LOG="$HOME/kairos_logs/qemu_vm_vnc.log"
  QEMU_VM_WRAPPER="$HOME/scripts/qemu/.vm_vnc_cmd.sh"
  mkdir -p "$(dirname "$QEMU_VM_LOG")"
  {
    printf '#!/data/data/com.termux/files/usr/bin/bash\n'
    printf 'exec '
    printf '%q ' "$QEMU_BIN" "${MACHINE_FLAGS[@]}" -m "$RAM" $DISPLAY_FLAG $VGA_FLAG \
      -netdev "user,id=net0,hostfwd=tcp::${SSH_PORT}-:22" -device virtio-net-pci,netdev=net0 \
      "${DISK_FLAG[@]}" "${QMP_FLAG[@]}"
    printf '\n'
  } > "$QEMU_VM_WRAPPER"
  chmod +x "$QEMU_VM_WRAPPER"
  tmux kill-session -t kairos_qemu_vnc 2>/dev/null
  tmux new-session -d -s kairos_qemu_vnc "bash '$QEMU_VM_WRAPPER' > '$QEMU_VM_LOG' 2>&1"
  echo "[INFO] QEMU arrancando en segundo plano (sesión tmux kairos_qemu_vnc) — log: $QEMU_VM_LOG"
  exit 0
fi

exec "$QEMU_BIN" "${MACHINE_FLAGS[@]}" -m "$RAM" $DISPLAY_FLAG $VGA_FLAG -netdev "user,id=net0,hostfwd=tcp::${SSH_PORT}-:22" -device virtio-net-pci,netdev=net0 "${DISK_FLAG[@]}" "${QMP_FLAG[@]}"
SCRIPT
chmod +x "$QEMU_SCRIPTS/run_vm.sh"

log "Scripts wrapper creados en $QEMU_SCRIPTS (run_user.sh, run_vm.sh)"

# ── Registry ─────────────────────────────────────────────────
# system_mode_aarch64/system_mode_x86_64 reemplazan al viejo "system_mode" único
# (v1.3.0) — QemuFragment.kt necesita saber CADA arch por separado (el usuario
# puede elegir cuál guest arrancar, y una puede haber quedado instalada sin la
# otra si un mirror falló para un solo paquete).
registry_install qemu "1.4.0" "kvm=false" "mode=tcg_software" \
  "user_mode=$(command -v qemu-x86_64 &>/dev/null && echo true || echo false)" \
  "system_mode_aarch64=$(command -v qemu-system-aarch64 &>/dev/null && echo true || echo false)" \
  "system_mode_x86_64=$(command -v qemu-system-x86_64 &>/dev/null && echo true || echo false)"

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  QEMU instalado ✓${NC}"
  echo ""
  echo "  Correr binario de otra arch: bash ~/scripts/qemu/run_user.sh x86_64 ./mi_binario"
  echo "  Bootear una VM headless:     bash ~/scripts/qemu/run_vm.sh mi_imagen.qcow2 [ram_MB] [ssh_port] [console|vnc] [resolucion] [x86_64|aarch64]"
  echo "  SSH host→VM (si corre sshd): ssh -p 2222 root@localhost (puerto configurable, 3er argumento de run_vm.sh)"
  echo "  Modo VNC (4to argumento):    bash ~/scripts/qemu/run_vm.sh mi_imagen.qcow2 512 2222 vnc  (servidor VNC en socket unix ~/scripts/qemu/vncsocket)"
  echo "  Control QMP (apagar/pausar/reanudar/screenshot/cambiar medio): socket unix ~/scripts/qemu/qmpsocket, desde la pestaña 'Máquina Virtual' de Kairos"
  echo "  Arch del guest (6to arg.):   aarch64 recomendado (mismo arch que el host) — default x86_64 por retrocompat"
  echo "  (recordá: sin KVM, la VM va a ser lenta — no es un reemplazo de VirtualBox)"
  echo ""
fi

notify_event "qemu" "install_done" "no_kvm"
log "Instalación de QEMU completada"
exit 0
