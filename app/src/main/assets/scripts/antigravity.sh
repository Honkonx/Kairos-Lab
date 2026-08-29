#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · antigravity.sh (silent mode)
#  Instala Antigravity CLI (agy) en Termux nativo ARM64
#
#  USO DESDE APP (KairosApp):
#    bash antigravity.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_antigravity.sh
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Descarga antigravity-termux-standalone.tar.gz del fork
#    ✅ Instala binarios agy + agy.va39 en $PREFIX/bin/
#    ✅ Verifica dependencias: glibc, curl, ca-certificates
#    ✅ Detecta LSE atomics — fallback QEMU si falta
#    ✅ Registry actualizado
#    ✅ NO usa /tmp/ (noexec Android 15) — rutas en $HOME/
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Autenticación Google Sign-In — pasa la primera vez que
#       el usuario ejecuta 'agy' manualmente, no se puede
#       automatizar desde la instalación
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 1.0.0 | Julio 2026 (adaptado de install_antigravity.sh v1.1.0)
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
{"id":"antigravity","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. 2 binarios ARM64 sobre
# glibc-runner (agy + su segundo binario agy.va39), sin parche conocido.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/agy" \
    --arg p2 "$TERMUX_PREFIX/bin/agy.va39" \
    --arg glibc "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" \
    --arg verify "command -v agy >/dev/null 2>&1 && agy --version >/dev/null 2>&1" \
    '{
      id: "antigravity", supports_describe_files: true, variant: null,
      package_name: "kairos-module-antigravity",
      version_registry_key: "antigravity.version",
      files: [
        {path: $p1, required: true, note: "Binario CLI principal (agy), sobre glibc-runner"},
        {path: $p2, required: true, note: "Segundo binario que agy invoca (agy.va39)"}
      ],
      file_globs: [],
      dependencies: [
        {id: "glibc", check_cmd: ("test -f \"" + $glibc + "\""), install_hint: "pkg install -y glibc-repo && pkg update -y && pkg install -y glibc-runner"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: ["No hay parche real conocido (a diferencia de Claude native) — si el binario no ejecuta tras copiarlo, probablemente falta LSE atomics o glibc-runner, no algo reparable con patch_cmd"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_antigravity_checkpoint"
AGY_WORKDIR="$HOME/.agy_install"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

AGY_INSTALL_OK=0
_agy_cleanup() {
  if [ "$AGY_INSTALL_OK" -eq 0 ] && [ -d "$AGY_WORKDIR" ]; then
    rm -rf "$AGY_WORKDIR"
  fi
}
trap '_agy_cleanup' EXIT

update_registry() {
  local version="$1"
  registry_install antigravity "$version" "location=termux_native" "binary=$TERMUX_PREFIX/bin/agy"
  if [ -n "$version" ]; then
    log "Registry actualizado — antigravity v${version}"
  else
    log "Registry actualizado — antigravity (versión desconocida)"
  fi
}

_check_installed() {
  command -v agy &>/dev/null && \
  [ -f "$TERMUX_PREFIX/bin/agy" ] && \
  [ -f "$TERMUX_PREFIX/bin/agy.va39" ]
}

# ── Ya instalado ────────────────────────────────────────────
if _check_installed && ! $FORCE; then
  _VER_ACTUAL=$(agy --version 2>/dev/null | grep -oE '[0-9]+\.[0-9.]+' | head -1)
  log "Antigravity CLI ya instalado (v${_VER_ACTUAL:-?})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}  ╔══════════════════════════════════════════╗"
  echo    "  ║  ✦ ANTIGRAVITY CLI — Instalador         ║"
  echo -e "  ╚══════════════════════════════════════════╝${NC}"
  echo ""
  echo -n "  ¿Instalar Antigravity CLI? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Dependencias ─────────────────────────────────────
step "PASO 1 — Dependencias del sistema"
if check_done "deps"; then
  log "Dependencias ya instaladas [checkpoint]"
else
  info "Verificando: glibc, curl, ca-certificates, resolv-conf..."
  _GLIBC_MISSING=false
  [ ! -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] && _GLIBC_MISSING=true

  # glibc-runner vive en el repo que agrega glibc-repo — sin un "pkg update -y"
  # en el medio, glibc-runner todavía no existe en los índices de pkg y la
  # instalación falla con "paquete no encontrado". Este script instalaba
  # glibc-repo y glibc-runner en el mismo "pkg install -y" sin el update
  # intermedio, violando la secuencia documentada en
  # termux-ai-stack-dev/doc/ANTIGRAVITY.md §2 ("Secuencia correcta: repo →
  # update → paquete") y ya corregida en el script de referencia
  # termux-ai-stack-dev/scripts/install_antigravity.sh (PASO 1) — ese fix
  # nunca se había portado a esta copia usada por la app.
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
  command -v curl &>/dev/null || _MISSING_DEPS+=("curl")
  [ ! -s "$TERMUX_PREFIX/etc/tls/cert.pem" ] && _MISSING_DEPS+=("ca-certificates")
  [ ! -r "$TERMUX_PREFIX/etc/resolv.conf" ] && _MISSING_DEPS+=("resolv-conf")

  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>/dev/null || \
      error "No se pudieron instalar dependencias: ${_MISSING_DEPS[*]}"
  fi

  if grep -q "atomics" /proc/cpuinfo 2>/dev/null; then
    log "LSE atomics: soportado (nativo)"
  elif command -v qemu-aarch64 &>/dev/null; then
    warn "LSE no soportado — usando QEMU (puede ser lento)"
  else
    error "CPU sin LSE y sin qemu-aarch64 — instala: pkg install qemu-user-aarch64"
  fi

  mark_done "deps"
  log "Dependencias verificadas"
fi

# ── PASO 2 — Descargar e instalar binarios ────────────────────
step "PASO 2 — Descargando Antigravity CLI"
if check_done "binaries"; then
  log "Binarios ya instalados [checkpoint]"
else
  rm -rf "$AGY_WORKDIR"
  mkdir -p "$AGY_WORKDIR"

  _FORK="Honkonx/antigravity-cli-termux"
  _TAR="$AGY_WORKDIR/antigravity-termux-standalone.tar.gz"
  _EXTRACT="$AGY_WORKDIR/extract"
  mkdir -p "$_EXTRACT"

  info "Descargando desde github.com/${_FORK}..."

  if ! curl -fL --progress-bar \
    "https://github.com/${_FORK}/releases/latest/download/antigravity-termux-standalone.tar.gz" \
    -o "$_TAR" 2>/dev/null; then
    rm -rf "$AGY_WORKDIR"
    error "Descarga fallida — verifica conexión"
  fi

  [ -s "$_TAR" ] || error "Archivo descargado vacío"
  log "Descargado: $(du -sh "$_TAR" | cut -f1)"

  info "Extrayendo binarios..."
  tar -xzf "$_TAR" -C "$_EXTRACT" agy agy.va39 2>/dev/null || \
    error "Fallo al extraer — archivo corrupto"

  [ -f "$_EXTRACT/agy" ] && [ -f "$_EXTRACT/agy.va39" ] || \
    error "Binarios no encontrados en el archivo"

  info "Instalando en $TERMUX_PREFIX/bin/..."
  install -m 0755 "$_EXTRACT/agy"      "$TERMUX_PREFIX/bin/agy"      || error "No se pudo instalar agy"
  install -m 0755 "$_EXTRACT/agy.va39" "$TERMUX_PREFIX/bin/agy.va39" || error "No se pudo instalar agy.va39"

  rm -rf "$AGY_WORKDIR"
  mark_done "binaries"
  log "Binarios instalados: agy + agy.va39"
fi

# ── PASO 3 — Verificar + registry ─────────────────────────────
step "PASO 3 — Verificación y registro"
_AGY_VER=$(agy --version 2>/dev/null | grep -oE '[0-9]+\.[0-9.]+' | head -1)
if [ -n "$_AGY_VER" ]; then
  log "Antigravity CLI v${_AGY_VER} funcional"
else
  warn "No se pudo verificar versión — puede requerir autenticación al ejecutar"
  # No escribir un placeholder tipo "installed" acá — el registry solo debe
  # tener una versión real parseada o quedar vacío (ModulesFragment/ModuleListAdapter
  # ya filtran versión vacía con isNotEmpty(); un string como "installed" en cambio
  # se concatena tal cual como "v$version" en la card del módulo → "vinstalled").
  _AGY_VER=""
fi

update_registry "${_AGY_VER}"
rm -f "$CHECKPOINT"
AGY_INSTALL_OK=1

notify_event "antigravity" "install_done" "$_AGY_VER"
log "Antigravity CLI instalado correctamente"
$SILENT || {
  echo ""
  echo -e "  ${DIM}Autenticación: la primera vez que ejecutes 'agy'${NC}"
  echo -e "  ${DIM}se abrirá Google Sign-In en el navegador.${NC}"
}
exit 0
