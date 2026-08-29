#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · cursor.sh (silent mode)
#  Instala Cursor CLI (cursor-agent) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash cursor.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ glibc-runner + patchelf-glibc (el binario oficial de Cursor está
#       linkeado contra glibc, igual que antigravity/claude/openclaw)
#    ✅ Cursor CLI vía instalador oficial (curl https://cursor.com/install
#       -fsSL | bash) → binario ~/.local/bin/cursor-agent
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login de Cursor (cuenta) — queda para que el usuario lo configure
#       manualmente ejecutando 'cursor-agent' la primera vez
#
#  NOTA TÉCNICA:
#    El instalador oficial de Cursor descarga el binario empaquetado
#    (trae su propio Node embebido) a ~/.local/bin/cursor-agent + su
#    runtime en ~/.local/share/cursor-agent/. En Termux se requiere la
#    capa de compatibilidad glibc (mismo patrón que antigravity.sh —
#    ver docs/referencias/REFERENCIA_ANTIGRAVITY.md). Si el
#    binario falla al ejecutar por el ELF interpreter, aplicar patchelf:
#      patchelf-glibc --set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1 <binario>
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
{"id":"cursor","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Mismo patrón que claude.sh
# (binario glibc + patchelf) — instalador oficial deja cursor-agent en
# $HOME/.local/bin, parcheado con patchelf-glibc para correr sobre el loader
# glibc de Termux en vez del interpreter original del binario.
if $DESCRIBE_FILES; then
  _cursor_loader="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
  _cursor_bin="$HOME/.local/bin/cursor-agent"
  _cursor_patchelf="$TERMUX_PREFIX/glibc/bin/patchelf"
  jq -n \
    --arg p1 "$_cursor_bin" \
    --arg glob "$HOME/.local/share/cursor-agent/**" \
    --arg dep1_check "test -f \"$_cursor_loader\"" \
    --arg dep1_hint "pkg install -y glibc-repo && pkg install -y glibc-runner" \
    --arg dep2_check "test -x \"$_cursor_patchelf\"" \
    --arg dep2_hint "pkg install -y patchelf-glibc" \
    --arg verify "\"$_cursor_bin\" --version >/dev/null 2>&1" \
    --arg patch "\"$_cursor_patchelf\" --set-interpreter \"$_cursor_loader\" \"$_cursor_bin\" 2>/dev/null; chmod +x \"$_cursor_bin\"" \
    '{
      id: "cursor",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-cursor",
      version_registry_key: "cursor.version",
      files: [
        {path: $p1, required: true, note: "binario cursor-agent, instalador oficial cursor.com/install, ya parcheado con patchelf --set-interpreter"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "payload real: $HOME/.local/bin/cursor-agent es un symlink a un script bash dentro de este dir, que a su vez exec-a un Node.js glibc embebido (node) contra index.js — confirmado en dispositivo (docs/humano212.md); sin este glob el wrapper de arriba queda roto tras reinstalar"}
      ],
      dependencies: [
        {id: "glibc_loader", check_cmd: $dep1_check, install_hint: $dep1_hint},
        {id: "patchelf", check_cmd: $dep2_check, install_hint: $dep2_hint}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "No incluye la autenticación de cuenta Cursor del usuario — reconfigurar con 'cursor-agent' tras reinstalar"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_cursor_checkpoint"
CURSOR_WORKDIR="$HOME/.cursor_install"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^cursor_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "cursor_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^cursor_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  local bin="$HOME/.local/bin/cursor-agent"
  if [ -x "$bin" ]; then
    "$bin" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
  else
    echo ""
  fi
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ▸ CURSOR CLI — Instalador               ║"
  echo "  ║  Cursor · Termux ARM64 (glibc)           ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Cursor CLI ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Cursor CLI? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — glibc + deps ────────────────────────────────────
step "PASO 1 — Capa de compatibilidad glibc"
if check_done "glibc"; then
  log "glibc ya verificado [checkpoint]"
else
  _GLIBC_MISSING=false
  [ ! -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] && _GLIBC_MISSING=true

  if $_GLIBC_MISSING; then
    info "glibc no detectado — instalando glibc-repo..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc-repo \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>/dev/null || \
      error "No se pudo instalar glibc-repo"
    info "Actualizando índices de paquetes (repo glibc recién agregado)..."
    pkg update -y 2>/dev/null || error "pkg update falló tras agregar glibc-repo"
  fi

  _MISSING_DEPS=()
  $_GLIBC_MISSING && _MISSING_DEPS+=("glibc-runner")
  [ -x "$TERMUX_PREFIX/glibc/bin/patchelf" ] || _MISSING_DEPS+=("patchelf-glibc")
  command -v curl &>/dev/null || _MISSING_DEPS+=("curl")
  [ ! -s "$TERMUX_PREFIX/etc/tls/cert.pem" ] && _MISSING_DEPS+=("ca-certificates")

  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>/dev/null || \
      error "No se pudieron instalar dependencias: ${_MISSING_DEPS[*]}"
  fi

  mark_done "glibc"
  log "Capa glibc verificada"
fi

# ── PASO 2 — Instalador oficial de Cursor ────────────────────
step "PASO 2 — Instalando Cursor CLI (instalador oficial)"
if check_done "cursor_install"; then
  log "Cursor CLI ya instalado [checkpoint]"
else
  info "Ejecutando: curl https://cursor.com/install -fsSL | bash"
  curl https://cursor.com/install -fsSL | bash 2>&1 | tail -15
  [ ${PIPESTATUS[0]} -eq 0 ] || error "Instalador de Cursor falló"

  export PATH="$HOME/.local/bin:$PATH"

  # Bug real encontrado 2026-08-24 (ver docs/humano212.md), reemplaza el
  # bloque anterior de acá que asumía que "$HOME/.local/bin/cursor-agent" era
  # el binario glibc a parchear con patchelf. Confirmado en dispositivo:
  #  1. "cursor-agent" NO es un ELF — es un script bash de ~1KB
  #     (shebang "#!/usr/bin/env bash") que resuelve su propio directorio y
  #     hace exec de "$SCRIPT_DIR/node" (un Node.js real embebido, ESE sí
  #     glibc ELF) contra "$SCRIPT_DIR/index.js". patchelf rechazaba
  #     "cursor-agent" con "not an ELF executable" — el objetivo real del
  #     patchelf es el "node" embebido en el mismo directorio, no el wrapper.
  #  2. El wrapper bash en sí tampoco ejecuta directo: mismo bug de shebang
  #     "#!/usr/bin/env bash" (no existe /usr/bin/env en Termux) que
  #     fix_npm_shebang_wrapper() ya resuelve para binarios npm — acá se
  #     corrige igual, pero a mano (no es un paquete npm, no hay
  #     package.json con campo "bin" que fix_npm_shebang_wrapper pueda leer):
  #     reescribe la primera línea del script real a la ruta absoluta del
  #     bash de Termux.
  _CURSOR_LOADER="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
  _CURSOR_BIN="$HOME/.local/bin/cursor-agent"
  _CURSOR_PATCHELF_BIN="$TERMUX_PREFIX/glibc/bin/patchelf"
  _CURSOR_REAL_SCRIPT=$(readlink -f "$_CURSOR_BIN" 2>/dev/null)
  if [ -n "$_CURSOR_REAL_SCRIPT" ] && [ -f "$_CURSOR_REAL_SCRIPT" ]; then
    if head -1 "$_CURSOR_REAL_SCRIPT" | grep -q "^#!/usr/bin/env bash"; then
      info "Corrigiendo shebang del wrapper cursor-agent..."
      sed -i "1s|^#!/usr/bin/env bash|#!$TERMUX_PREFIX/bin/bash|" "$_CURSOR_REAL_SCRIPT"
    fi
    _CURSOR_NODE_BIN="$(dirname "$_CURSOR_REAL_SCRIPT")/node"
    if [ -f "$_CURSOR_LOADER" ] && [ -f "$_CURSOR_NODE_BIN" ]; then
      if [ -x "$_CURSOR_PATCHELF_BIN" ]; then
        info "Aplicando patchelf al Node.js embebido de cursor-agent..."
        chmod +x "$_CURSOR_NODE_BIN"
        "$_CURSOR_PATCHELF_BIN" --set-interpreter "$_CURSOR_LOADER" "$_CURSOR_NODE_BIN" 2>/dev/null || \
          warn "patchelf falló sobre el node embebido — el binario puede requerir ajuste manual"
      else
        warn "patchelf no encontrado en $_CURSOR_PATCHELF_BIN — el binario puede requerir ajuste manual"
      fi
    fi
  fi
  # verify_binary_installed() en vez de command -v a secas (2026-08-22, ver docs/humano/humano201.md)
  # — corre DESPUÉS del fix de shebang/patchelf de arriba (antes de eso, "cursor-agent --version"
  # está garantizado a fallar, sería un falso negativo).
  verify_binary_installed cursor-agent || error "cursor-agent no ejecuta tras la instalación (ni con el fix de shebang/patchelf aplicado) — revisá manualmente: $_CURSOR_BIN --version"

  log "Cursor CLI instalado: $(get_installed_ver || true)"
  mark_done "cursor_install"
fi

# ── PASO 3 — Login (omitido en modo silencioso) ──────────────
step "PASO 3 — Autenticación"
if check_done "auth"; then
  log "Autenticación ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — configurá tu cuenta de Cursor manualmente, ejecutá 'cursor-agent'"
  mark_done "auth"
else
  warn "Ejecutá 'cursor-agent' y seguí el asistente para configurar tu cuenta"
  mark_done "auth"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write cursor \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=official_installer" \
  "glibc=true" \
  "install_date=${_DATE}"

notify_event "cursor" "install_done" "$_VER_FINAL"
log "Cursor CLI instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0