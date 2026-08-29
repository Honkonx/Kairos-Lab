#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · kimi.sh (silent mode)
#  Instala Kimi Code CLI (@moonshot-ai/kimi-code) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash kimi.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Node.js (nodejs-lts, si falta)
#    ✅ Kimi Code (@moonshot-ai/kimi-code, npm oficial de Moonshot AI) — comando: kimi
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ API key de Kimi (configuración inicial) — queda para que el usuario
#       la configure manualmente ejecutando 'kimi' la primera vez
#
#  NOTA DE VERSIÓN:
#    Kimi Code requiere Node.js 22.19+. El package real es @moonshot-ai/kimi-code
#    (el "kimi-cli" de nombre corto en npm es un paquete distinto/legacy — ver
#    docs/humano/humano123.md, módulos candidatos core-termux v4.25.0).
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (nuevo módulo, candidato core-termux v4.25.0,
#  ver docs/humano/humano123.md)
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
{"id":"kimi","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. CLI npm global simple —
# wrapper resuelto en PATH + el paquete real en npm root -g (fix_npm_shebang_wrapper
# ya lo reemplaza por un wrapper bash real, ver lib.sh).
if $DESCRIBE_FILES; then
  _kimi_bin=$(command -v kimi 2>/dev/null || echo "$TERMUX_PREFIX/bin/kimi")
  _kimi_pkg_dir="$(npm root -g 2>/dev/null)/@moonshot-ai/kimi-code"
  jq -n \
    --arg p1 "$_kimi_bin" \
    --arg glob "${_kimi_pkg_dir}/**" \
    --arg dep1_check "command -v node >/dev/null 2>&1" \
    --arg verify "command -v kimi >/dev/null 2>&1" \
    '{
      id: "kimi",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-kimi",
      version_registry_key: "kimi.version",
      files: [
        {path: $p1, required: true, note: "wrapper bash real (fix_npm_shebang_wrapper) en PATH"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "paquete npm real @moonshot-ai/kimi-code en npm root -g — el wrapper lo referencia directo"}
      ],
      dependencies: [
        {id: "nodejs", check_cmd: $dep1_check, install_hint: "pkg install -y nodejs-lts"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No incluye la API key configurada por el usuario — hay que correr 'kimi' y reconfigurarla tras reinstalar"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_kimi_checkpoint"
KIMI_PKG="@moonshot-ai/kimi-code@latest"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^kimi_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "kimi_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^kimi_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  command -v kimi &>/dev/null && kimi --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

node_major() {
  command -v node &>/dev/null && node --version 2>/dev/null | sed 's/^v//' | cut -d. -f1 || echo "0"
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ⌘ KIMI CODE — Instalador                ║"
  echo "  ║  Moonshot AI · Termux ARM64              ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Kimi Code ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Kimi Code? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Node.js 22.19+ ──────────────────────────────────
step "PASO 1 — Verificando Node.js (requiere 22.19+)"
if check_done "node"; then
  log "Node.js ya verificado [checkpoint]"
else
  if command -v node &>/dev/null && command -v npm &>/dev/null && [ "$(node_major)" -ge 22 ]; then
    log "Node.js detectado: $(node --version 2>/dev/null)"
    mark_done "node"
  else
    info "Instalando nodejs-lts..."
    pkg_update_with_fallback
    pkg install nodejs-lts -y 2>/dev/null || error "No se pudo instalar Node.js"
    command -v node &>/dev/null || error "Node.js no disponible tras instalación"
    [ "$(node_major)" -ge 22 ] || warn "Node $(node --version 2>/dev/null) — Kimi Code pide 22.19+; si falla, actualizá nodejs-lts manualmente"
    log "Node.js instalado: $(node --version)"
    mark_done "node"
  fi
fi

# ── PASO 2 — npm install ─────────────────────────────────────
step "PASO 2 — Instalando Kimi Code vía npm"
if check_done "npm_install"; then
  log "Kimi Code ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${KIMI_PKG}"
  npm install -g "$KIMI_PKG" 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano184.md): el symlink npm no
  # ejecuta directo en este dispositivo — mismo patrón que explica el "version=?" ya visto acá.
  fix_npm_shebang_wrapper "kimi" "${KIMI_PKG%@latest}"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed kimi || error "kimi no ejecuta tras la instalación (revisá manualmente: kimi --version)"
  log "Kimi Code instalado"
  mark_done "npm_install"
fi

# ── PASO 3 — API key (omitido en modo silencioso) ─────────────
step "PASO 3 — Configuración de API key"
if check_done "apikey"; then
  log "Configuración ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — configurá tu API key de Kimi manualmente después, ejecutá 'kimi'"
  mark_done "apikey"
else
  warn "Ejecutá 'kimi' y seguí el asistente para configurar tu API key"
  mark_done "apikey"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write kimi \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=npm" \
  "install_date=${_DATE}"

notify_event "kimi" "install_done" "$_VER_FINAL"
log "Kimi Code instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0