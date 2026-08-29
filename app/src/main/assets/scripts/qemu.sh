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
#
#  QUÉ EXPONE ESTE MÓDULO (honesto, sin prometer de más):
#    ✅ qemu-user-x86-64 + qemu-user-arm — correr binarios estáticos de
#       otra arquitectura (rápido, uso real, sin root)
#    ✅ qemu-system-x86-64-headless + qemu-utils — bootear una VM x86_64
#       SIN gráficos (consola serie/-nographic), útil para probar un
#       kernel/ISO/imagen liviana. SIN aceleración KVM → software puro,
#       lento (esperá minutos de boot, no segundos)
#    ❌ NO incluye ninguna imagen/ISO de sistema operativo — eso lo trae
#       el usuario (este módulo no descarga SO de terceros)
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
#  VERSIÓN: 1.2.0 | Agosto 2026 (run_vm.sh agrega -machine q35 + hostfwd SSH
#  al puerto 2222 del host, patrón tomado de
#  referencia/emuladores/docker-in-termux-main/README.md; v1.2.0 agrega un
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
{"id":"qemu","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"qemu-user (correr binarios de otra arquitectura, sin root, uso real) + qemu-system headless SIN aceleracion KVM (Android no expone /dev/kvm sin root) — software puro (TCG), lento; no incluye ninguna imagen de SO"}
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
        {id: "pkg:qemu-system", check_cmd: "command -v qemu-system-x86_64 >/dev/null 2>&1", install_hint: "pkg install -y qemu-system-x86-64-headless qemu-utils"}
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
if command -v qemu-x86_64 &>/dev/null && command -v qemu-system-x86_64 &>/dev/null && ! $FORCE; then
  log "QEMU ya instalado — $(qemu-system-x86_64 --version 2>/dev/null | head -1)"
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
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    x11-repo 2>/dev/null || warn "x11-repo no se pudo instalar — algunos paquetes qemu-system pueden faltar"
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
# PASO 3 — qemu-system headless (sin KVM — TCG software, lento)
# ============================================================
step "3/3 Instalando qemu-system-x86-64-headless + qemu-utils"

if check_done "qemu_system"; then
  log "qemu-system ya instalado [checkpoint]"
else
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  # Mismo motivo que PASO 2 — sin "2>/dev/null", para que el error real de apt/dpkg llegue al
  # log en vez de descartarse.
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    qemu-system-x86-64-headless qemu-utils || \
    warn "qemu-system-x86-64-headless no se pudo instalar (puede no estar disponible para esta arch de Termux)"
  command -v qemu-system-x86_64 &>/dev/null && log "qemu-system-x86_64 OK (sin KVM — TCG software)" || \
    warn "qemu-system-x86_64 no quedó disponible — qemu-user (PASO 2) sigue funcionando igual"
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
# USO: run_vm.sh <imagen.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc]
# Bootea SIN KVM (TCG software, lento: esperá minutos, no segundos). Este
# módulo NO trae ninguna imagen: conseguí un .qcow2/.iso liviano vos mismo
# (ej. Alpine Linux) y pasá la ruta.
#
# Modo de salida (4to argumento, default "console" — retrocompatible con
# cualquier caller viejo que solo pasaba 3 argumentos, ver
# docs/arquitectura/PROPUESTA_QEMU_DISPLAY_2026-08-26.md sección 5 opción 1/2):
#   console (default) — -nographic, consola serie redirigida a este stdio.
#   vnc                — -vnc 127.0.0.1:2 (puerto 5902 — el visor VNC nativo
#                         de Kairos ya usa 5901 para Mini PC, ver
#                         EntornoNative.vncStart()/VncViewerActivity.kt, así
#                         que QEMU usa el display :2 siguiente para no pisarlo).
#                         Sin -nographic acá: la consola serie del guest deja
#                         de imprimirse en este stdio, la salida real ahora es
#                         el framebuffer gráfico servido por VNC.
#
# Red: -netdev user,id=net0 + -device virtio-net-pci,netdev=net0 — SLIRP
# (modo usuario), 100% userspace, sin root, sin /dev/net/tun, sin binfmt_misc.
# Gap real confirmado en docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md
# ("qemu: Flags/opciones oficiales NO expuestas") — antes la VM headless
# arrancaba sin ninguna interfaz de red, sin salida a internet. Sintaxis
# verificada contra la doc oficial (qemu.org/docs/master/system/invocation.html):
# NO usar -net tap/bridge (requiere root/kernel tun que Android no expone).
#
# hostfwd + -machine q35: patrón tomado de
# referencia/emuladores/docker-in-termux-main/README.md (guía real de correr
# Alpine x86_64 headless en Termux vía QEMU) — sin hostfwd la VM tenía salida
# a internet pero era imposible entrar por SSH desde el host (Termux) sin
# pasar por la consola serie; q35 es el chipset recomendado por esa guía
# (más moderno que el "pc" por defecto, mejor soporte virtio).
IMG="$1"; RAM="${2:-512}"; SSH_PORT="${3:-2222}"; MODE="${4:-console}"
[ -z "$IMG" ] && { echo "uso: run_vm.sh <imagen.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc]" >&2; exit 1; }
[ -f "$IMG" ] || { echo "[ERROR] No existe: $IMG" >&2; exit 1; }
command -v qemu-system-x86_64 &>/dev/null || { echo "[ERROR] qemu-system-x86_64 no instalado" >&2; exit 1; }
echo "[INFO] Sin KVM (Android no da /dev/kvm sin root) — TCG software, el boot va a tardar."
echo "[INFO] Red: modo usuario (SLIRP) vía -netdev user — sin root, sin TUN/TAP."
echo "[INFO] SSH host→VM: si la VM corre sshd en el puerto 22, conectate con 'ssh -p $SSH_PORT root@localhost'."

# Bug real confirmado (2026-08-27, ver docs/humano256.md): el código viejo SIEMPRE
# forzaba "-drive file=$IMG,format=qcow2", incluso para un .iso (ej. el catálogo
# de descarga ofrece alpine-virt-*.iso). Un ISO9660 no es qcow2 — qemu detecta el
# formato inválido y sale con error DESPUÉS de haber reemplazado el proceso bash
# (por el "exec" de la línea vieja), así que el "|| exec ...-cdrom" de fallback
# nunca se ejecutaba (exec ya reemplazó el proceso, no hay bash vivo para evaluar
# el "||"). Resultado real observado: la terminal volvía al prompt al instante,
# sin ningún log de qemu (el "2>/dev/null" viejo silenciaba el error), y en modo
# vnc el server nunca llegaba a levantar → ECONNREFUSED en VncViewerActivity.
# Fix: decidir el flag de disco ANTES de arrancar qemu, por extensión real del
# archivo — .iso siempre es -cdrom (medio óptico), .qcow2 es -drive format=qcow2,
# cualquier otra cosa (.img/.raw/sin extensión, ej. las cloud images de
# Debian/Ubuntu del catálogo) es -drive format=raw.
case "${IMG##*.}" in
  iso|ISO) DISK_FLAG=(-cdrom "$IMG") ;;
  qcow2|QCOW2) DISK_FLAG=(-drive "file=$IMG,format=qcow2") ;;
  *) DISK_FLAG=(-drive "file=$IMG,format=raw") ;;
esac

if [ "$MODE" = "vnc" ]; then
  echo "[INFO] Modo VNC — servidor QEMU en 127.0.0.1:5902 (display :2). Abrí el visor VNC de Kairos apuntando a ese puerto."
  DISPLAY_FLAG="-vnc 127.0.0.1:2"
else
  echo "[INFO] Modo consola — salida serie redirigida a esta terminal (-nographic)."
  DISPLAY_FLAG="-nographic"
fi

if [ "$MODE" = "vnc" ]; then
  # Modo VNC = uso silencioso desde la UI (QemuFragment.bootVmVnc()), sin terminal
  # visible — igual que los módulos con servicio de fondo (ollama/n8n/openclaw),
  # arranca en una sesión tmux detached (ver modulos/ollama.sh) en vez de "exec" en
  # foreground: así este script vuelve al instante y Kairos puede sondear el
  # puerto 5902 sin bloquear ningún hilo ni necesitar la terminal adaptada.
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
    printf '%q ' qemu-system-x86_64 -machine q35 -m "$RAM" $DISPLAY_FLAG \
      -netdev "user,id=net0,hostfwd=tcp::${SSH_PORT}-:22" -device virtio-net-pci,netdev=net0 "${DISK_FLAG[@]}"
    printf '\n'
  } > "$QEMU_VM_WRAPPER"
  chmod +x "$QEMU_VM_WRAPPER"
  tmux kill-session -t kairos_qemu_vnc 2>/dev/null
  tmux new-session -d -s kairos_qemu_vnc "bash '$QEMU_VM_WRAPPER' > '$QEMU_VM_LOG' 2>&1"
  echo "[INFO] QEMU arrancando en segundo plano (sesión tmux kairos_qemu_vnc) — log: $QEMU_VM_LOG"
  exit 0
fi

exec qemu-system-x86_64 -machine q35 -m "$RAM" $DISPLAY_FLAG -netdev "user,id=net0,hostfwd=tcp::${SSH_PORT}-:22" -device virtio-net-pci,netdev=net0 "${DISK_FLAG[@]}"
SCRIPT
chmod +x "$QEMU_SCRIPTS/run_vm.sh"

log "Scripts wrapper creados en $QEMU_SCRIPTS (run_user.sh, run_vm.sh)"

# ── Registry ─────────────────────────────────────────────────
registry_install qemu "1.0.0" "kvm=false" "mode=tcg_software" "user_mode=$(command -v qemu-x86_64 &>/dev/null && echo true || echo false)" "system_mode=$(command -v qemu-system-x86_64 &>/dev/null && echo true || echo false)"

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  QEMU instalado ✓${NC}"
  echo ""
  echo "  Correr binario de otra arch: bash ~/scripts/qemu/run_user.sh x86_64 ./mi_binario"
  echo "  Bootear una VM headless:     bash ~/scripts/qemu/run_vm.sh mi_imagen.qcow2 [ram_MB] [ssh_port] [console|vnc]"
  echo "  SSH host→VM (si corre sshd): ssh -p 2222 root@localhost (puerto configurable, 3er argumento de run_vm.sh)"
  echo "  Modo VNC (4to argumento):    bash ~/scripts/qemu/run_vm.sh mi_imagen.qcow2 512 2222 vnc  (servidor VNC en 127.0.0.1:5902)"
  echo "  (recordá: sin KVM, la VM va a ser lenta — no es un reemplazo de VirtualBox)"
  echo ""
fi

notify_event "qemu" "install_done" "no_kvm"
log "Instalación de QEMU completada"
exit 0
