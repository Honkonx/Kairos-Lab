#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · qwencode.sh (silent mode)
#  Instala Qwen Code (Alibaba, fork de Gemini CLI) en Termux ARM64 (vía npm)
#
#  USO DESDE APP (KairosApp):
#    bash qwencode.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Node.js (nodejs-lts, si falta)
#    ✅ Qwen Code CLI (@qwen-code/qwen-code@latest, npm) — comando: qwen
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login interactivo / API key de Qwen3-Coder (bailian.console.aliyun.com
#       o modelstudio.console.alibabacloud.com según región) — queda para que
#       el usuario lo configure manualmente después
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (nuevo módulo, candidato de i-Haklab, ver
#  docs/humano/humano98.md — comando CLI confirmado "qwen", no "qwen-code",
#  vía docs oficiales de QwenLM/qwen-code)
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

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  _bin=$(command -v qwen 2>/dev/null || echo "$TERMUX_PREFIX/bin/qwen")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$(npm root -g 2>/dev/null)/@qwen-code/**" \
    --arg verify "command -v qwen >/dev/null 2>&1 && qwen --version >/dev/null 2>&1" \
    '{
      id: "qwencode",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-qwencode",
      version_registry_key: "qwencode.version",
      files: [{path: $path, required: true, note: "Wrapper/binario real de qwen (@qwen-code/qwen-code), resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob, required: true, note: "árbol npm global real que el wrapper/symlink de arriba ejecuta — sin esto el wrapper queda roto en un device nuevo"}
      ],
      dependencies: [{id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: []
    }'
  exit 0
fi

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"qwencode","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_qwencode_checkpoint"
QWENCODE_PKG="@qwen-code/qwen-code@latest"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^qwencode_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "qwencode_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^qwencode_${1}=done" "$CHECKPOINT" 2>/dev/null; }


get_installed_ver() {
  command -v qwen &>/dev/null && qwen --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◇ QWEN CODE — Instalador                ║"
  echo "  ║  Alibaba · Termux ARM64                  ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Qwen Code ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Qwen Code? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Node.js ─────────────────────────────────────────
step "PASO 1 — Verificando Node.js"
if check_done "node"; then
  log "Node.js ya verificado [checkpoint]"
else
  if command -v node &>/dev/null && command -v npm &>/dev/null; then
    log "Node.js detectado: $(node --version 2>/dev/null)"
    mark_done "node"
  else
    info "Instalando nodejs-lts..."
    pkg_update_with_fallback
    pkg install nodejs-lts -y 2>/dev/null || error "No se pudo instalar Node.js"
    command -v node &>/dev/null || error "Node.js no disponible tras instalación"
    log "Node.js instalado: $(node --version)"
    mark_done "node"
  fi
fi

# ── PASO 2 — npm install ─────────────────────────────────────
step "PASO 2 — Instalando Qwen Code vía npm"
if check_done "npm_install"; then
  log "Qwen Code ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${QWENCODE_PKG}"
  npm install -g "$QWENCODE_PKG" 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano184.md): el symlink npm no
  # ejecuta directo en este dispositivo — mismo patrón que explica el "version=?" ya visto acá.
  fix_npm_shebang_wrapper "qwen" "${QWENCODE_PKG%@latest}"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed qwen || error "qwen no ejecuta tras la instalación (revisá manualmente: qwen --version)"
  log "Qwen Code instalado"
  mark_done "npm_install"
fi

# ── PASO 3 — API key (omitido en modo silencioso) ─────────────
step "PASO 3 — Configuración de API key"
if check_done "apikey"; then
  log "Configuración ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — configurá tu API key de Qwen3-Coder manualmente después (ver bailian.console.aliyun.com o modelstudio.console.alibabacloud.com)"
  mark_done "apikey"
else
  warn "Ejecutá 'qwen' y seguí el asistente para configurar tu API key"
  mark_done "apikey"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write qwencode \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=npm" \
  "install_date=${_DATE}"

notify_event "qwencode" "install_done" "$_VER_FINAL"
log "Qwen Code instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0
