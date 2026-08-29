#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ssh.sh (silent mode)
#  Módulo SSH: SSH + Cloudflared (sin dashboard)
#
#  USO DESDE APP (KairosApp):
#    bash ssh.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_remote.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté configurado
#
#  QUÉ INSTALA:
#    ✅ OpenSSH (puerto 8022) — configurado
#    ✅ Cloudflared ARM64 nativo (tunnel SSH)
#    ✅ mosh-server (best-effort, no fatal si falla) — permite a un cliente
#       Mosh (`mosh-server new` ejecutado sobre esta misma sesión SSH) dar
#       sesiones resilientes a cambios de red (wifi↔datos, roaming) — el caso
#       de uso típico de "acceder remoto a este teléfono" sufre justo ese
#       problema y plain SSH corta la sesión en cada cambio de red. Ver
#       referencia/interfaz/Haven-main/core/mosh/MOSH.md — Haven bootstrapea
#       Mosh vía `exec "mosh-server new"` sobre una conexión SSH normal, así
#       que basta con tener el binario instalado del lado servidor (acá) para
#       que cualquier cliente Mosh (Haven u otro) ya pueda usarlo.
#    ✅ Scripts: ssh_start.sh, ssh_stop.sh
#    ✅ Registry actualizado
#    ✅ Aliases en .bashrc
#
#  OUTPUT (modo --silent):
#    [STEP] N/6 Descripción     ← para barra de progreso
#    [OK] mensaje                ← paso completado
#    [ERROR] mensaje             ← fallo (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 2.2.0 | Agosto 2026 (agrega mosh-server best-effort — auditoría de
#  referencia/interfaz/Haven-main/, ver docs/humano/humano170.md)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

CLOUDFLARED_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"

# ── Parsear flags ─────────────────────────────────────────────
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

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"remote","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. "remote"/ssh.sh es sobre todo
# CONFIGURACIÓN (sshd_config + scripts de control) sobre un paquete apt real
# (openssh) — el binario sshd en sí NO se empaqueta (es apt, se reinstala
# como dependencia, no como archivo copiado 1:1). cloudflared sí se incluye
# como archivo porque se descarga directo, no vía pkg.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$HOME/scripts/remote/ssh_start.sh" \
    --arg p2 "$HOME/scripts/remote/ssh_stop.sh" \
    --arg p3 "$TERMUX_PREFIX/etc/ssh/sshd_config" \
    --arg p4 "$TERMUX_PREFIX/bin/cloudflared" \
    --arg verify "command -v sshd >/dev/null 2>&1 && test -f \"$TERMUX_PREFIX/etc/ssh/sshd_config\"" \
    '{
      id: "remote", supports_describe_files: true, variant: null,
      package_name: "kairos-module-remote",
      version_registry_key: "remote.version",
      files: [
        {path: $p1, required: true, note: "Arranca sshd en :8022 (ssh_start.sh)"},
        {path: $p2, required: true, note: "Detiene sshd (ssh_stop.sh)"},
        {path: $p3, required: true, note: "sshd_config con Puerto 8022 configurado"},
        {path: $p4, required: false, note: "Binario cloudflared descargado directo (no vía pkg) — opcional, para el túnel"}
      ],
      file_globs: [],
      dependencies: [
        {id: "openssh", check_cmd: "command -v sshd", install_hint: "pkg install -y openssh"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: ["No se empaquetan las claves SSH del host ($PREFIX/etc/ssh/ssh_host_*) ni el authorized_keys del usuario — son credenciales/identidad, no configuración reubicable"]
    }'
  exit 0
fi

# ── Utilidades ────────────────────────────────────────────────
get_local_ip() {
  local ip
  ip=$(ifconfig 2>/dev/null | grep -A1 "netmask 255\.255\." | \
       grep "inet " | grep -v "127\." | awk '{print $2}' | head -1)
  [ -z "$ip" ] && ip=$(ifconfig 2>/dev/null | grep "inet " | \
       grep -v "127\." | awk '{print $2}' | head -1)
  [ -z "$ip" ] && ip=$(ip addr show 2>/dev/null | grep "inet " | \
       grep -v "127\." | awk '{print $2}' | cut -d'/' -f1 | head -1)
  echo "${ip:-localhost}"
}

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_remote_checkpoint"
REMOTE_SCRIPTS="$HOME/scripts/remote"
SSHD_CONFIG="$TERMUX_PREFIX/etc/ssh/sshd_config"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry_ssh() {
  local version="$1"
  local mosh="false"
  command -v mosh-server &>/dev/null && mosh="true"
  # Se escriben ambos prefijos: "ssh.*" (compat con auto-chequeo de este script y
  # kairos_manager.py legacy) y "remote.*" (lo que la app Kotlin realmente lee — el
  # moduleId en modules.json es "remote"). Ver fix 2026-08-01.
  registry_write ssh "installed=true" "version=${version}" "install_date=$(date +%Y-%m-%d)" "port=8022" "location=termux_native" "auth=password+pubkey" "mosh=${mosh}"
  registry_write remote "installed=true" "version=${version}" "install_date=$(date +%Y-%m-%d)" "port=8022" "location=termux_native" "mosh=${mosh}"
}

# ── Verificar si ya está instalado ────────────────────────────
SSH_CONFIGURED=false
CF_INSTALLED=false

{ [ "$(grep -c 'Port 8022' "$SSHD_CONFIG" 2>/dev/null)" -gt 0 ] || \
  [ "$(grep '^ssh.installed' "$REGISTRY" 2>/dev/null | cut -d= -f2)" = "true" ]; } && \
  SSH_CONFIGURED=true

command -v cloudflared &>/dev/null && CF_INSTALLED=true

if $SSH_CONFIGURED && $CF_INSTALLED && ! $FORCE; then
  log "Remote ya instalado completamente (SSH + Cloudflared)"
  # Bug real (fix 2026-08-01): esta salida temprana nunca llamaba a
  # update_registry_ssh() (eso solo pasaba en el PASO 6, más abajo) — un
  # dispositivo que ya corrió este script ANTES del fix de arriba (con
  # "ssh.installed=true" pero sin "remote.installed=true" en el registry)
  # se quedaba viendo "No instalado" para siempre: cada vez que la app
  # reintentaba instalar/togglear el módulo, caía derecho acá y salía sin
  # jamás escribir la clave "remote.*" que la UI necesita. Se repara el
  # registry en cada corrida (aunque ya esté todo instalado) para que un
  # simple reintento del usuario (sin --force) alcance para arreglarlo.
  REPAIR_VER=$(ssh -V 2>&1 | grep -oE 'OpenSSH_[0-9]+\.[0-9p]+' | head -1)
  [ -z "$REPAIR_VER" ] && REPAIR_VER="unknown"
  update_registry_ssh "$REPAIR_VER"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ──────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Remote Installer        ║
  ║   SSH + Cloudflared · v2.1.0               ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instalará:"
  echo "  ▸ OpenSSH configurado en puerto 8022"
  echo "  ▸ Cloudflared ARM64 nativo (tunnel SSH remoto)"
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=6

# ============================================================
# PASO 1 — Termux update (solo standalone)
# ============================================================
step "1/$TOTAL_STEPS Verificando Termux"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado por instalar.sh [skip]"
elif check_done "termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  # Auditoría de módulos 2026-08-27 (docs/arquitectura/AUDITORIA_MODULOS_2026-08-27.md):
  # este PASO 1 se había quedado con la lógica vieja de "2 mirrors fijos en orden" que
  # lib.sh ya centralizó (ver pkg_update_with_fallback(), comentario "Quick win de la
  # auditoría de referencia/ 2026-08-05") para ollama.sh/n8n.sh/kairos.sh/entorno.sh —
  # ssh.sh quedó afuera de esa migración pese a que sus otros 2 pasos (línea ~228 y
  # ~395 de este mismo archivo) sí llaman a la función centralizada. Se corrige acá
  # para no tener 2 implementaciones divergentes del mismo fallback en el mismo script
  # (duplicación real, no solo cosmética: la vieja probaba 2 mirrors fijos sin medir
  # velocidad, la centralizada mide 5 candidatos y usa el más rápido).
  pkg_update_with_fallback
  log "Termux actualizado"
  mark_done "termux_update"
fi

# ============================================================
# PASO 2 — Dependencias
# ============================================================
step "2/$TOTAL_STEPS Instalando dependencias"

if check_done "remote_deps"; then
  log "Dependencias ya instaladas [checkpoint]"
else
  DEPS_TO_INSTALL=()
  command -v sshd &>/dev/null || DEPS_TO_INSTALL+=("openssh")
  command -v tmux &>/dev/null || DEPS_TO_INSTALL+=("tmux")

  if [ ${#DEPS_TO_INSTALL[@]} -gt 0 ]; then
    info "Instalando: ${DEPS_TO_INSTALL[*]}..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" \
      "${DEPS_TO_INSTALL[@]}" || \
      warn "Algunos paquetes tuvieron advertencias"
  fi

  command -v sshd &>/dev/null && log "OpenSSH ✓" || error "openssh no se instaló"
  command -v tmux &>/dev/null && log "tmux ✓" || warn "tmux no se instaló"

  mark_done "remote_deps"
fi

# ============================================================
# PASO 3 — Configurar SSH
# ============================================================
step "3/$TOTAL_STEPS Configurando SSH"

if check_done "ssh_config"; then
  log "SSH ya configurado [checkpoint]"
else
  [ -f "$SSHD_CONFIG" ] && cp "$SSHD_CONFIG" "${SSHD_CONFIG}.bak" 2>/dev/null

  cat > "$SSHD_CONFIG" << 'SSHCONF'
# termux-ai-stack · sshd_config v2.1.0
Port 8022
ListenAddress 0.0.0.0

# Autenticación
PasswordAuthentication yes
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys

# Seguridad
PermitRootLogin no
MaxAuthTries 6
MaxSessions 5

# Keepalive
ClientAliveInterval 60
ClientAliveCountMax 3

# Sin X11
X11Forwarding no

# SFTP
Subsystem sftp /data/data/com.termux/files/usr/libexec/sftp-server
SSHCONF

  log "sshd_config configurado (Puerto 8022)"

  info "Generando claves del servidor SSH..."
  ssh-keygen -A 2>/dev/null || warn "ssh-keygen -A: puede ser normal si ya existen"
  ls "$TERMUX_PREFIX/etc/ssh/ssh_host_"*"_key" &>/dev/null && \
    log "Claves del servidor generadas" || \
    warn "Claves no encontradas — se generarán al primer inicio de sshd"

  mkdir -p "$HOME/.ssh"
  chmod 700 "$HOME/.ssh"
  touch "$HOME/.ssh/authorized_keys"
  chmod 600 "$HOME/.ssh/authorized_keys"
  log "~/.ssh/authorized_keys listo"

  mark_done "ssh_config"
fi

# ============================================================
# PASO 4 — Scripts SSH
# ============================================================
step "4/$TOTAL_STEPS Creando scripts SSH"

if check_done "ssh_scripts"; then
  log "Scripts SSH ya creados [checkpoint]"
else
  mkdir -p "$REMOTE_SCRIPTS"

  cat > "$REMOTE_SCRIPTS/ssh_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
_get_ip() {
  local ip
  ip=$(ifconfig 2>/dev/null | grep -A1 "netmask 255\.255\." | grep "inet " | grep -v "127\." | awk '{print $2}' | head -1)
  [ -z "$ip" ] && ip=$(ifconfig 2>/dev/null | grep "inet " | grep -v "127\." | awk '{print $2}' | head -1)
  echo "${ip:-localhost}"
}
if pgrep -x sshd &>/dev/null; then
  echo "[OK] SSH ya corriendo → ssh -p 8022 $(whoami)@$(_get_ip)"
  exit 0
fi
sshd 2>/dev/null
sleep 1
if pgrep -x sshd &>/dev/null; then
  echo "[OK] SSH iniciado → ssh -p 8022 $(whoami)@$(_get_ip)"
else
  echo "[ERROR] No se pudo iniciar SSH"
  exit 1
fi
SCRIPT
  chmod +x "$REMOTE_SCRIPTS/ssh_start.sh"

  cat > "$REMOTE_SCRIPTS/ssh_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
if pgrep -x sshd &>/dev/null; then
  pkill sshd 2>/dev/null; sleep 1
  pgrep -x sshd &>/dev/null && echo "[ERROR] No se pudo detener" || echo "[OK] SSH detenido"
else
  echo "[OK] SSH no estaba corriendo"
fi
SCRIPT
  chmod +x "$REMOTE_SCRIPTS/ssh_stop.sh"

  log "Scripts SSH creados"
  mark_done "ssh_scripts"
fi

# ============================================================
# PASO 5 — Cloudflared ARM64 nativo
# ============================================================
step "5/$TOTAL_STEPS Instalando Cloudflared"

if check_done "cloudflared_install"; then
  log "Cloudflared ya instalado [checkpoint]"
else
  CF_DEST="$TERMUX_PREFIX/bin/cloudflared"

  if command -v cloudflared &>/dev/null && ! $FORCE; then
    log "Cloudflared ya disponible"
  else
    info "Descargando cloudflared linux/arm64..."
    curl -fL "$CLOUDFLARED_URL" -o "$CF_DEST" 2>/dev/null || \
      timeout 30 wget -q -O "$CF_DEST" "$CLOUDFLARED_URL" 2>/dev/null

    if [ -f "$CF_DEST" ] && [ -s "$CF_DEST" ]; then
      chmod +x "$CF_DEST"
      CF_VER=$(cloudflared --version 2>/dev/null | head -1)
      if [ -n "$CF_VER" ]; then
        log "Cloudflared instalado — $CF_VER"
      else
        warn "Cloudflared descargado pero no ejecuta (incompatible con Bionic)"
        rm -f "$CF_DEST"
      fi
    else
      warn "Descarga de cloudflared falló — se puede instalar después"
    fi
  fi

  # Bug real (fix 2026-07-31): mark_done corría siempre, incluso cuando la
  # descarga fallaba o el binario resultaba no ejecutable — el checkpoint
  # quedaba "hecho" para siempre y ningún reintento de instalación volvía a
  # bajar cloudflared (este paso se salta directo con el log de [checkpoint]
  # de arriba). SSH en sí sigue funcionando sin cloudflared (solo se pierde el
  # túnel remoto), así que no se aborta con error() — pero si no quedó
  # instalado, el checkpoint se deja sin marcar para que la próxima
  # instalación (o un reintento manual) lo vuelva a intentar.
  command -v cloudflared &>/dev/null && mark_done "cloudflared_install"
fi

# ============================================================
# PASO 5b — mosh-server (best-effort, no fatal)
# ============================================================
if ! check_done "mosh_install"; then
  if command -v mosh-server &>/dev/null; then
    log "mosh-server ya disponible"
    mark_done "mosh_install"
  else
    info "Instalando mosh (sesiones resilientes a cambios de red)..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y mosh 2>/dev/null && command -v mosh-server &>/dev/null && {
      log "mosh-server instalado"
      mark_done "mosh_install"
    } || warn "mosh no se pudo instalar — SSH sigue funcionando igual, solo se pierde la resiliencia a cambios de red"
  fi
fi

# ============================================================
# PASO 6 — Aliases + Registry
# ============================================================
step "6/$TOTAL_STEPS Finalizando"

if ! check_done "remote_aliases"; then
  BASHRC="$HOME/.bashrc"
  [ -f "$BASHRC" ] && grep -v "ssh-start\|ssh-stop\|ssh-status\|# Remote" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  Remote (SSH + Cloudflared) · aliases
# ════════════════════════════════
alias ssh-start='bash ~/scripts/remote/ssh_start.sh'
alias ssh-stop='bash ~/scripts/remote/ssh_stop.sh'
alias ssh-status='pgrep -x sshd &>/dev/null && echo "SSH: ● :8022" || echo "SSH: ○ detenido"'
ALIASES

  log "Aliases agregados"
  mark_done "remote_aliases"
fi

# Registry
SSH_VER=$(ssh -V 2>&1 | grep -oE 'OpenSSH_[0-9]+\.[0-9p]+' | head -1)
[ -z "$SSH_VER" ] && SSH_VER="unknown"
update_registry_ssh "$SSH_VER"

# ── Limpieza ──────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  IP=$(get_local_ip)
  echo ""
  echo -e "${GREEN}${BOLD}  Remote instalado ✓${NC}"
  echo ""
  echo -e "  SSH:         ssh -p 8022 $(whoami)@${IP}"
  command -v cloudflared &>/dev/null && \
    echo -e "  Cloudflared: ✓ instalado" || \
    echo -e "  Cloudflared: no instalado"
  command -v mosh-server &>/dev/null && \
    echo -e "  Mosh:        ✓ instalado (mosh $(whoami)@${IP} --ssh=\"ssh -p 8022\")" || \
    echo -e "  Mosh:        no instalado"
  echo ""
fi

notify_event "remote" "install_done" "$SSH_VER"
log "Instalación de Remote completada"
exit 0
