#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · dsh.sh (silent mode)
#  Instala DeepSeek Harness (`@deepseek-ai/dsh`, MIT) -- agente de IA "everything is
#  a plugin" con Web UI unificada propia, via proot-distro Ubuntu (node-pty necesita
#  glibc real, no Bionic -- ver docs/referencias/REFERENCIA_DEEPSEEK_HARNESS.md,
#  2026-08-18, veredicto ya dado: "vale la pena como módulo nuevo dsh -- vía proot
#  jammy + DSH_PERMISSION_MODE=danger-full-access + WebView/VNC embebido de Kairos").
#  Mismo patrón que modulos/n8n.sh (proot+Node 22 via NodeSource) y modulos/cactus.sh
#  (proot-distro login "ubuntu" idempotente) -- sin reinventar ninguno de los 2.
#
#  USO DESDE APP (KairosApp):
#    bash dsh.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ proot-distro "ubuntu" (si no existe ya)
#    ✅ Node.js 22 LTS + build-essential dentro de la distro (node-pty necesita
#       compilar su addon nativo contra glibc real)
#    ✅ @deepseek-ai/dsh (npm global, dentro de la distro)
#    ✅ Scripts de control: ~/scripts/dsh/start.sh, ~/scripts/dsh/stop.sh
#    ✅ Registry actualizado (puerto 3080, el default real de `dsh web`)
#
#  NO INSTALA:
#    ❌ Sandbox Landlock/bwrap -- no funciona dentro de proot (mismo trade-off que
#       cualquier otro módulo proot de Kairos) -- se fuerza DSH_PERMISSION_MODE=
#       danger-full-access, documentado en REFERENCIA_DEEPSEEK_HARNESS.md.
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | 2026-11-05 (nuevo, primera versión)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
  shift
done

if $DESCRIBE; then
  cat << 'JSON'
{"id":"dsh","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_dsh_checkpoint"
DSH_SCRIPTS="$HOME/scripts/dsh"
DISTRO_NAME="ubuntu"
DSH_PORT=3080

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# No empaqueta el rootfs de la distro (igual que n8n/cactus, GBs, fuera de alcance) --
# solo los scripts de control, que son triviales de regenerar.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$DSH_SCRIPTS/start.sh" \
    --arg p2 "$DSH_SCRIPTS/stop.sh" \
    --arg verify "[ -d \"$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_NAME\" ] || [ -d \"$TERMUX_PREFIX/var/lib/proot-distro/containers/$DISTRO_NAME\" ]" \
    --arg patch "chmod 755 \"$DSH_SCRIPTS/\"*.sh 2>/dev/null || true" \
    '{
      id: "dsh",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-dsh",
      version_registry_key: "dsh.version",
      files: [
        {path: $p1, required: true, note: "Arranca dsh web dentro del proot en tmux"},
        {path: $p2, required: true, note: "Detiene la sesión tmux"}
      ],
      file_globs: [],
      dependencies: [
        {id: "proot-distro-ubuntu", check_cmd: $verify, install_hint: "proot-distro install ubuntu"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "NO empaqueta el rootfs de la distro Ubuntu ni @deepseek-ai/dsh instalado adentro (GBs reales, mismo criterio que n8n/cactus) -- reinstalar este .deb en un device que nunca corrió dsh.sh con éxito solo deja los scripts de control, la distro+dsh real sigue necesitando dsh.sh --silent de cero."
      ]
    }'
  exit 0
fi

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  registry_install dsh "$version" "port=$DSH_PORT" "permission_mode=danger-full-access"
}

# ── Verificar si ya está instalado ────────────────────────────
if check_done "dsh_npm" && ! $FORCE; then
  log "dsh ya instalado [checkpoint]"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo -n "  ¿Instalar DeepSeek Harness (agente de IA con Web UI propia, via Ubuntu/proot)? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=4

# ============================================================
# PASO 1 — Termux
# ============================================================
step "1/$TOTAL_STEPS Preparando Termux"

if check_done "dsh_termux"; then
  log "Termux ya preparado [checkpoint]"
else
  pkg_update_with_fallback
  if ! command -v proot-distro &>/dev/null; then
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
      proot proot-distro tmux curl || error "No se pudo instalar proot-distro"
  fi
  mark_done "dsh_termux"
fi

# ============================================================
# PASO 2 — proot-distro "ubuntu"
# ============================================================
step "2/$TOTAL_STEPS Instalando distro Ubuntu"

# Mismo chequeo de 3 vías que cactus.sh (GLIBC_DISTRO) -- distintas versiones/layouts de
# proot-distro ponen el rootfs en rutas distintas, "list -q" es el fallback fiable en
# cualquiera de ellas.
if [ -d "$TERMUX_PREFIX/var/lib/proot-distro/containers/$DISTRO_NAME" ] || \
   [ -d "$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_NAME" ] || \
   proot-distro list -q 2>/dev/null | grep -qx "$DISTRO_NAME"; then
  log "Distro '$DISTRO_NAME' ya instalada"
else
  info "Instalando Ubuntu (puede tardar varios minutos)..."
  _OUT=$(proot-distro install "$DISTRO_NAME" 2>&1); _RC=$?
  echo "$_OUT" | grep -q "already exists" || { [ $_RC -ne 0 ] && error "Falló instalación de Ubuntu: $_OUT"; }
fi
mark_done "dsh_distro"

# ============================================================
# PASO 3 — Node.js 22 + dsh dentro de Ubuntu
# ============================================================
step "3/$TOTAL_STEPS Instalando Node.js 22 + DeepSeek Harness en Ubuntu"

if check_done "dsh_npm"; then
  log "Node.js + dsh ya instalados [checkpoint]"
else
  info "Esto puede tardar varios minutos (Node.js 22 + compilar node-pty)..."
  proot-distro login "$DISTRO_NAME" -- bash << 'INNER'
set -e
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
DPKG_OPTS='-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold'

# dpkg self-heal -- mismo patrón real ya confirmado en ciberseguridad.sh/entorno.sh/n8n.sh
# (una corrida anterior killeada por Android puede dejar /var/lib/dpkg/lock-frontend tomado).
echo "[0/4] Verificando estado de dpkg..."
dpkg --configure -a 2>&1 | tail -20 || true

echo "[1/4] Actualizando Ubuntu..."
apt-get update
apt-get install -y $DPKG_OPTS \
  curl ca-certificates gnupg build-essential python3 python3-pip git \
  || { echo "[ERROR] apt-get install falló"; exit 1; }

echo "[2/4] Instalando Node.js 22 LTS..."
# Mismo fix real que n8n.sh -- "curl | bash" sin chequear el
# exit code REAL de curl (no el de bash -, siempre 0 con stdin vacío) esconde fallos de
# red/DNS y cae silenciosamente al nodejs viejo de los repos base de Ubuntu.
NODESOURCE_LOG="/tmp/nodesource_setup.log"
curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >"$NODESOURCE_LOG" 2>&1
CURL_RC=${PIPESTATUS[0]}
if [ "$CURL_RC" -ne 0 ]; then
  echo "[ERROR] curl a deb.nodesource.com falló (exit $CURL_RC)"
  tail -20 "$NODESOURCE_LOG"
  exit 1
fi
apt-get install -y $DPKG_OPTS nodejs
NODE_MAJOR=$(node --version | sed 's/v//' | cut -d'.' -f1)
if ! [[ "$NODE_MAJOR" =~ ^[0-9]+$ ]] || [ "$NODE_MAJOR" -lt 22 ]; then
  echo "[ERROR] Node < 22 tras instalar nodesource (dsh requiere Node ^22.19 o >=24) -- $(node --version 2>&1)"
  exit 1
fi
echo "[OK] Node.js $(node --version)"

echo "[3/4] Instalando @deepseek-ai/dsh (compila node-pty, puede tardar)..."
export npm_config_python=$(which python3)
npm install -g @deepseek-ai/dsh || { echo "[ERROR] npm install -g @deepseek-ai/dsh falló"; exit 1; }

echo "[4/4] Verificando..."
command -v dsh &>/dev/null || { echo "[ERROR] dsh no quedó en PATH tras la instalación"; exit 1; }
echo "[OK] dsh instalado: $(dsh --version 2>&1 | head -1)"
INNER
  [ $? -ne 0 ] && error "Falló la instalación de Node.js/dsh dentro de Ubuntu -- revisá el log arriba"
  mark_done "dsh_npm"
fi

# ============================================================
# PASO 4 — Scripts de control + registry
# ============================================================
step "4/$TOTAL_STEPS Creando scripts de control"

mkdir -p "$DSH_SCRIPTS"

cat > "$DSH_SCRIPTS/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="dsh-server"
DISTRO_NAME="$DISTRO_NAME"
PORT="$DSH_PORT"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] dsh ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

echo "[*] Iniciando DeepSeek Harness..."
tmux new-session -d -s "\$SESSION"

# -u ANDROID_ROOT -u ANDROID_DATA (mismo fix real que cactus.sh _needle_importa_ya, causa
# confirmada 2026-10-10): proot hereda esas 2 variables del anfitrión Android y algunas
# libs de Node detectan "estoy en Android" por su presencia, aunque el proceso corra en
# glibc real dentro del proot -- se limpian para que dsh/Node se comporten como en un
# Ubuntu normal.
#
# 2 hallazgos reales de revisión automática (2026-11-05): DSH_PERMISSION_MODE=danger-full-access
# es necesario (Landlock/bwrap no están disponibles dentro de proot -- ver
# REFERENCIA_DEEPSEEK_HARNESS.md), pero eso le da al agente acceso de escritura TOTAL dentro
# del proot -- deliberadamente NO se usan ni "--shared-home" (compartía el $HOME real de Termux,
# con SSH keys/tokens de otros módulos) ni "--shared-tmp" (compartía /tmp, donde vive el socket
# de control de tmux -- un agente con ese acceso podría ver/manipular OTRAS sesiones tmux del
# host, ej. ollama-server/n8n-server, no solo la propia). Sin ninguno de los dos, ese acceso
# total queda CONTENIDO al /root y /tmp internos del proot (aislados, propios de la distro) --
# proot no es un límite de seguridad real (no es una sandbox del kernel), así que esto reduce
# superficie de ataque real, no la elimina. El modo queda visible en el registry
# (dsh.permission_mode=danger-full-access) y en el mensaje de instalación -- no es un default
# silencioso.
DSH_CMD="export HOME=/root && export DSH_PERMISSION_MODE=danger-full-access && dsh web --port \$PORT"
tmux send-keys -t "\$SESSION" \
  "proot-distro login \"\$DISTRO_NAME\" -- env -u ANDROID_ROOT -u ANDROID_DATA bash -c '\${DSH_CMD}'" Enter

echo "[*] Esperando a que dsh responda en :\$PORT..."
DSH_UP=false
for i in \$(seq 1 40); do
  sleep 3
  curl -sf --max-time 2 "http://127.0.0.1:\$PORT/" >/dev/null 2>&1 && { DSH_UP=true; break; }
done

if ! \$DSH_UP; then
  echo "[ERROR] dsh no respondió en :\$PORT tras 120s -- revisá: tmux attach -t \$SESSION"
  exit 1
fi
echo "[OK] DeepSeek Harness activo en http://127.0.0.1:\$PORT"
SCRIPT
chmod +x "$DSH_SCRIPTS/start.sh"

cat > "$DSH_SCRIPTS/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="dsh-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] dsh detenido"
else
  echo "[OK] dsh no estaba corriendo"
fi
SCRIPT
chmod +x "$DSH_SCRIPTS/stop.sh"

DSH_VER=$(proot-distro login "$DISTRO_NAME" -- bash -c 'dsh --version 2>&1 | head -1' 2>/dev/null)
[ -z "$DSH_VER" ] && DSH_VER="unknown"
update_registry "$DSH_VER"

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  DeepSeek Harness instalado ✓${NC}"
  echo ""
  echo "  Puerto:  $DSH_PORT (127.0.0.1, dentro de Ubuntu/proot)"
  echo "  Sandbox: danger-full-access (Landlock/bwrap no disponibles en proot)"
  echo ""
fi

log "Instalación de dsh completada"
