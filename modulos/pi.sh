#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · pi.sh (silent mode)
#  Instala Pi Coding Agent en Termux ARM64 (vía npm)
#
#  INVESTIGACIÓN REAL (referencia/termux/core-termux-main/core/tools/ai/pi/,
#  no asumido por el nombre — confirmado leyendo install.sh real):
#    - Pi Coding Agent es un agente de codificación por terminal (npm
#      package oficial "@earendil-works/pi-coding-agent", repo real
#      github.com/earendil-works/pi). Comando final: "pi" (lo publica npm
#      directo en $PREFIX/bin/pi, sin wrapper propio).
#    - El install.sh de core-termux NO usa Bun a pesar de tener una función
#      "_install_pi_bun" — esa función llama a _install_pkg_fallback, que
#      resuelve a npm. Replicado acá tal cual (npm install -g).
#    - Sin flags/opciones propias documentadas en el código fuente
#      disponible (sin --help embebido, sin carpeta bin/ con wrapper) — no
#      se inventa sintaxis sin confirmar (mismo criterio que freebuff.sh/
#      codebuff.sh).
#
#  QUÉ INSTALA:
#    ✅ Node.js (nodejs-lts, si falta) + ripgrep + git + fd (deps reales)
#    ✅ Pi Coding Agent (@earendil-works/pi-coding-agent@latest, npm) —
#       comando: pi
#    ✅ Registry actualizado
#
#  CONFIG PERSISTENTE: $HOME/.pi (creada por el propio binario en su
#  primer uso, no por este instalador)
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Ninguna autenticación — no hay evidencia de mecanismo de login en
#       el código fuente de referencia disponible; si "pi" pide una API
#       key la pide en su propia sesión interactiva (out of scope de este
#       instalador)
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (nuevo módulo, fuente
#  referencia/termux/core-termux-main/core/tools/ai/pi/install.sh)
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

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"pi","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"Pi Coding Agent (npm @earendil-works/pi-coding-agent) — agente de codificacion por terminal, comando 'pi'. Sin flags/login confirmados en el codigo fuente disponible."}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Corregido 2026-08-28: el
# comentario anterior decía "npm publica el ejecutable directo ahí, sin
# wrapper propio", pero PASO 2 (más abajo) llama a fix_npm_shebang_wrapper,
# que SÍ reescribe $PREFIX/bin/pi como un wrapper "exec node <entry>" contra
# el paquete real en node_modules — mismo patrón que el resto de CLIs npm.
if $DESCRIBE_FILES; then
  # Bug real encontrado 2026-08-29 empaquetando en dispositivo real: este
  # bloque hardcodeaba "$TERMUX_PREFIX/bin/pi" sin resolver primero con
  # `command -v` (a diferencia de kimi.sh/minimaxcli.sh, que sí lo hacen) —
  # en un device con npm global redirigido a $HOME/.npm-global (confirmado
  # con `npm config get prefix`), el wrapper real de fix_npm_shebang_wrapper
  # queda en $HOME/.npm-global/bin/pi, no en $TERMUX_PREFIX/bin/pi, y
  # moduledeb.sh pack fallaba con "archivo requerido no encontrado".
  _pi_bin=$(command -v pi 2>/dev/null || echo "$TERMUX_PREFIX/bin/pi")
  jq -n \
    --arg p1 "$_pi_bin" \
    --arg glob "$(npm root -g 2>/dev/null)/@earendil-works/**" \
    --arg verify "command -v pi >/dev/null 2>&1 && pi --version >/dev/null 2>&1" \
    --arg patch "chmod 755 \"$_pi_bin\" 2>/dev/null || true" \
    '{
      id: "pi",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-pi",
      version_registry_key: "pi.version",
      files: [
        {path: $p1, required: true, note: "Wrapper reescrito por fix_npm_shebang_wrapper tras npm install -g — exec node contra el entry real en node_modules"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "árbol npm global real (@earendil-works/pi-coding-agent) que el wrapper de arriba ejecuta — sin esto el wrapper queda roto en un device nuevo"}
      ],
      dependencies: [
        {id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "No empaqueta $HOME/.pi (config/estado creado por el propio binario en su primer uso) — a propósito, es estado de usuario, no parte de la instalación"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_pi_checkpoint"
PI_PKG="@earendil-works/pi-coding-agent@latest"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^pi_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "pi_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^pi_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  command -v pi &>/dev/null && pi --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  π PI CODING AGENT — Instalador           ║"
  echo "  ║  earendil-works · Termux ARM64             ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Pi Coding Agent ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Pi Coding Agent? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Dependencias (Node.js, ripgrep, git, fd) ─────────
step "PASO 1 — Verificando dependencias"
if check_done "deps"; then
  log "Dependencias ya verificadas [checkpoint]"
else
  if command -v node &>/dev/null && command -v npm &>/dev/null; then
    log "Node.js detectado: $(node --version 2>/dev/null)"
  else
    info "Instalando nodejs-lts..."
    pkg_update_with_fallback
    pkg install nodejs-lts -y 2>/dev/null || error "No se pudo instalar Node.js"
    command -v node &>/dev/null || error "Node.js no disponible tras instalación"
    log "Node.js instalado: $(node --version)"
  fi

  _MISSING_DEPS=()
  command -v rg &>/dev/null || _MISSING_DEPS+=("ripgrep")
  command -v git &>/dev/null || _MISSING_DEPS+=("git")
  command -v fd &>/dev/null || _MISSING_DEPS+=("fd")
  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" 2>/dev/null || error "No se pudieron instalar dependencias: ${_MISSING_DEPS[*]}"
  fi
  mark_done "deps"
  log "Dependencias verificadas"
fi

# ── PASO 2 — npm install ─────────────────────────────────────
step "PASO 2 — Instalando Pi Coding Agent vía npm"
if check_done "npm_install"; then
  log "Pi Coding Agent ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${PI_PKG} --ignore-scripts"
  npm install -g "$PI_PKG" --ignore-scripts 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real encontrado 2026-08-24 (ver docs/humano212.md): faltaba este
  # wrapper — mismo bug de shebang "#!/usr/bin/env node" (no existe en
  # Termux) ya documentado y arreglado en install_npm_global()/codebuff.sh/
  # OpenClaw/Cursor CLI. Debe ir ANTES de verify_binary_installed (el
  # symlink roto no ejecuta "--version" todavía).
  fix_npm_shebang_wrapper pi "${PI_PKG%@latest}"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed pi || error "pi no ejecuta tras la instalación (revisá manualmente: pi --version)"
  log "Pi Coding Agent instalado"
  mark_done "npm_install"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write pi \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=npm" \
  "install_date=${_DATE}"

notify_event "pi" "install_done" "$_VER_FINAL"
log "Pi Coding Agent instalado correctamente (v${_VER_FINAL:-?}) — ejecutá 'pi' en la terminal"
rm -f "$CHECKPOINT"
exit 0
