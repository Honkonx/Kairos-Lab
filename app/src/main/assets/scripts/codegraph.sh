#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · codegraph.sh (silent mode)
#  Instala CodeGraph en Termux ARM64 (binario nativo de GitHub Releases)
#
#  INVESTIGACIÓN REAL (referencia/termux/core-termux-main/core/tools/ai/
#  codegraph/, no asumido por el nombre — confirmado leyendo install.sh
#  real + bin/codegraph):
#    - CodeGraph NO es un agente de IA — es una herramienta de análisis
#      estático de código: genera un grafo de relaciones entre
#      archivos/funciones/clases/módulos de un proyecto, para navegación y
#      refactor. Repo real: github.com/colbymchenry/codegraph.
#    - Binario Node.js precompilado (dist/bin/codegraph.js), no un
#      paquete npm — se descarga el tarball de la última release
#      (codegraph-linux-arm64.tar.gz) y se invoca vía un wrapper de una
#      línea: "exec node <extraído>/lib/dist/bin/codegraph.js "$@"".
#    - Sin flags/subcomandos propios documentados en el código fuente
#      disponible (el wrapper reenvía "$@" tal cual, sin --help embebido
#      en el repo) — no se inventa sintaxis sin confirmar.
#
#  QUÉ INSTALA (2 métodos en cascada):
#    ✅ MÉTODO 1 (preferido): binario nativo del fork propio
#       github.com/Honkonx/codegraph-termux (fork de Hope2333/codegraph-termux,
#       fork del proyecto real colbymchenry/codegraph) — mismo patrón que
#       freebuff.sh/bun-termux y codebuff.sh: se descarga el asset real
#       "*_aarch64.deb" de GitHub Releases (fork primero, upstream Hope2333
#       como fallback si el fork no tiene el asset todavía adjunto a su
#       release) y se extrae el árbol usr/ COMPLETO a $TERMUX_PREFIX.
#       Confirmado 2026-08-28 descargando y parseando el .deb real
#       (Hope2333/codegraph-termux, tag Push260803): el package trae su
#       PROPIO runtime Node.js embebido (usr/lib/codegraph-termux/<ver>/node,
#       ELF interpreter YA PRE-PARCHEADO al loader glibc de Termux) — no
#       depende del "node" del sistema ni de ninguno de los paquetes pkg del
#       MÉTODO 2. El shim de entrada (usr/bin/codegraph) sí es Bionic nativo
#       puro (interpreter /system/bin/linker64), pero el runtime Node que
#       invoca requiere la misma capa glibc que codebuff.sh/freebuff.sh — no
#       hace falta patchelf en este método, solo tener esa capa instalada.
#    ✅ MÉTODO 2 (fallback si el 1 falla): el flujo previo — Node.js
#       (nodejs-lts) + ripgrep + sqlite + git + clang + make + curl (deps
#       reales del install.sh de referencia) + binario CodeGraph ARM64
#       (última release de GitHub del proyecto original colbymchenry/
#       codegraph) extraído en $HOME/.local/share/kairos-data/
#       codegraph-linux-arm64/ + wrapper en $PREFIX/bin/codegraph.
#
#  CONFIG PERSISTENTE: $HOME/.codegraph (creada por el propio binario)
#
#  USO: correr "codegraph" dentro de un proyecto para analizarlo (sin
#  flags confirmados adicionales — el binario expone su propio --help
#  en tiempo de ejecución).
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 2.0.0 | Agosto 2026 (fork nativo codegraph-termux como método
#  preferido, ver github.com/Honkonx/codegraph-termux — fallback al binario
#  original de colbymchenry/codegraph que ya existía, fuente
#  referencia/termux/core-termux-main/core/tools/ai/codegraph/install.sh)
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
{"id":"codegraph","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"CodeGraph (colbymchenry/codegraph) — analisis estatico/grafo de relaciones de un proyecto de codigo, NO es un agente de IA. Binario ARM64 de GitHub Releases + wrapper Node."}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_codegraph_checkpoint"
CODEGRAPH_DATA="$HOME/.local/share/kairos-data/codegraph-linux-arm64"
CODEGRAPH_REPO="colbymchenry/codegraph"
# Método 1 (fork nativo codegraph-termux) — install.sh del propio .deb deja el
# runtime versionado bajo este directorio (ver usr/lib/codegraph-termux/<ver>/).
CODEGRAPH_FORK_DIR="$TERMUX_PREFIX/lib/codegraph-termux"

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. El tarball extraído es un runtime
# Node.js completo (lib/dist/...) — se usa file_globs (§2.2) en vez de listar cada
# archivo, igual criterio que opencode.sh para su árbol usr/*.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/codegraph" \
    --arg glob "$CODEGRAPH_DATA/**" \
    --arg verify "\"$TERMUX_PREFIX/bin/codegraph\" --help >/dev/null 2>&1" \
    '{
      id: "codegraph",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-codegraph",
      version_registry_key: "codegraph.version",
      files: [
        {path: $p1, required: true, note: "Wrapper: exec node <CODEGRAPH_DATA>/lib/dist/bin/codegraph.js — ruta absoluta hardcodeada al crearse (PASO 3), regenerable"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "runtime Node.js completo extraído del tarball de GitHub Releases (lib/dist/..., version)"}
      ],
      dependencies: [
        {id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}
      ],
      verify_cmd: $verify,
      patch_cmd: "true",
      not_covered: [
        "No empaqueta $HOME/.codegraph (config/estado creado por el propio binario) — es estado de usuario",
        "El wrapper embebe una ruta absoluta a CODEGRAPH_DATA — si el device destino usa un HOME distinto (no debería en Termux/Kairos, pero por las dudas) el wrapper quedaría apuntando mal; no hay patch_cmd real para esto todavía"
      ]
    }'
  exit 0
fi

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^codegraph_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "codegraph_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^codegraph_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  local v=""
  [ -f "$CODEGRAPH_DATA/version" ] && v=$(cat "$CODEGRAPH_DATA/version")
  # Canal fork: no deja un archivo "version" — el propio .deb instala bajo un
  # directorio versionado (usr/lib/codegraph-termux/<ver>/), tomar el más nuevo.
  if [ -z "$v" ] && [ -d "$CODEGRAPH_FORK_DIR" ]; then
    v=$(ls "$CODEGRAPH_FORK_DIR" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
  fi
  echo "$v"
}

# ── Método 1 (preferido) — binario nativo del fork propio (codegraph-termux) ──
# Mismo patrón ya usado en freebuff.sh (bun-termux) y codebuff.sh (codebuff-termux):
# se descarga el .deb REAL armado para Termux desde el fork propio del proyecto (o
# su upstream Hope2333 si el fork todavía no tiene el asset adjunto a su release) y
# se copia el árbol usr/ completo. Confirmado 2026-08-28 descargando y parseando el
# .deb real (Hope2333/codegraph-termux, Push260803): trae su PROPIO runtime Node.js
# embebido (usr/lib/codegraph-termux/<ver>/node) con el ELF interpreter YA
# PRE-PARCHEADO al loader glibc de Termux — no hace falta patchelf, solo tener esa
# capa glibc instalada (PASO 1, de la que este método también depende). El shim de
# entrada (usr/bin/codegraph) es Bionic nativo puro, pero exec-ea a ese runtime Node
# embebido para correr el JS real (usr/lib/codegraph-termux/<ver>/lib/dist/bin/
# codegraph.js) — la ruta se resuelve relativa al propio $0, no hardcodeada, así que
# sigue funcionando igual tras el cp -a a $TERMUX_PREFIX.
CODEGRAPH_FORK_SOURCE_REPO=""
_codegraph_download_native_fork() {
  command -v dpkg-deb &>/dev/null || { warn "dpkg-deb no disponible — no se puede extraer el .deb del fork nativo de codegraph"; return 1; }
  command -v curl &>/dev/null || return 1

  local _repo _releases_json _asset_url=""
  for _repo in "Honkonx/codegraph-termux" "Hope2333/codegraph-termux"; do
    _releases_json=$(curl -fsSL "https://api.github.com/repos/${_repo}/releases?per_page=5" 2>/dev/null)
    [ -z "$_releases_json" ] && continue
    _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*_aarch64\.deb"' | \
      head -1 | grep -o 'https://[^"]*')
    if [ -n "$_asset_url" ]; then CODEGRAPH_FORK_SOURCE_REPO="$_repo"; break; fi
  done
  if [ -z "$_asset_url" ]; then
    warn "No se encontró un asset .deb aarch64 del fork nativo de codegraph (ni en Honkonx/codegraph-termux ni en el upstream Hope2333/codegraph-termux)"
    return 1
  fi

  local _tmp="$HOME/tmp/codegraph_fork_$$"
  local _deb="$_tmp/$(basename "$_asset_url")"
  mkdir -p "$_tmp"
  info "Descargando CodeGraph nativo del fork ($(basename "$_asset_url"), repo $CODEGRAPH_FORK_SOURCE_REPO)..."
  curl -fsSL "$_asset_url" -o "$_deb" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  local _extract="$_tmp/extract"
  mkdir -p "$_extract"
  dpkg-deb -x "$_deb" "$_extract" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  # Mismo bug/fix real ya confirmado en freebuff.sh/codebuff.sh (docs/humano281.md):
  # dpkg-deb -x deja el árbol bajo la RUTA ABSOLUTA COMPLETA "$_extract/data/data/
  # com.termux/files/usr/..." (así empaqueta Termux sus .deb), no bajo
  # "$_extract/usr/" — copiar el árbol usr/ COMPLETO preserva la relación
  # shim→runtime Node→JS intacta.
  local _extract_usr="$_extract/data/data/com.termux/files/usr"
  [ -d "$_extract_usr" ] || _extract_usr="$_extract/usr"
  if [ ! -d "$_extract_usr" ]; then
    warn "El .deb del fork nativo de codegraph no tiene ningún layout usr/ reconocible tras la extracción"
    rm -rf "$_tmp"
    return 1
  fi
  cp -a "$_extract_usr/." "$TERMUX_PREFIX/" || {
    warn "No se pudo copiar el árbol usr/ del .deb del fork nativo de codegraph a $TERMUX_PREFIX"
    rm -rf "$_tmp"
    return 1
  }
  chmod +x "$TERMUX_PREFIX/bin/codegraph" 2>/dev/null
  rm -rf "$_tmp"

  # Verificación FUNCIONAL real (mismo criterio que la post-condición del wrapper del
  # Método 2 más abajo) — --help es el flag real que codegraph expone.
  verify_binary_installed codegraph --help || {
    warn "CodeGraph nativo del fork instalado pero no responde a --help"
    return 1
  }
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◈ CODEGRAPH — Instalador                 ║"
  echo "  ║  Análisis de código · Termux ARM64        ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v codegraph &>/dev/null && { [ -d "$CODEGRAPH_DATA" ] || [ -d "$CODEGRAPH_FORK_DIR" ]; } && ! $FORCE; then
  log "CodeGraph ya instalado ($(get_installed_ver))"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar CodeGraph? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# Termux es exclusivamente ARM64 (ver CLAUDE.md) — la rama no-aarch64 es solo
# defensiva, no se espera ejercitarla; el fork nativo (Método 1) y su capa
# glibc solo se intentan en aarch64.
_ARCH=$(uname -m 2>/dev/null || echo "unknown")
_NATIVE=false
[ "$_ARCH" = "aarch64" ] && _NATIVE=true

# ── PASO 1 — Capa de compatibilidad glibc ────────────────────
# Requisito del runtime Node embebido en el fork de respaldo (más abajo, PASO 5b).
# Orden corregido 2026-08-28 (docs/humano281.md, corrección explícita del usuario: "deben
# quedar con el método que teníamos y los repos como el mío o de hoppe son de respaldo, todo
# es respaldo no remplazo") — el método ORIGINAL (GitHub Releases del binario oficial +
# wrapper node) corre primero (PASO 3-5); el fork propio (Honkonx/codegraph-termux, hoy sin
# releases propios, cae a Hope2333/codegraph-termux) queda como RESPALDO real (PASO 5b),
# tentado solo si el método original falla — nunca al revés.
_CODEGRAPH_FORK=false
if $_NATIVE; then
  step "PASO 1 — Capa de compatibilidad glibc (runtime del fork de respaldo)"
  if check_done "glibc"; then
    log "glibc ya verificado [checkpoint]"
  else
    _MISSING_DEPS=()
    [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || _MISSING_DEPS+=("glibc-repo" "glibc")
    command -v dpkg-deb &>/dev/null || _MISSING_DEPS+=("dpkg")
    command -v curl &>/dev/null || _MISSING_DEPS+=("curl")

    if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
      info "Instalando: ${_MISSING_DEPS[*]}"
      pkg_update_with_fallback
      pkg install -y "${_MISSING_DEPS[@]}" \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" 2>/dev/null || \
        warn "No se pudieron instalar las dependencias glibc — el fork de respaldo puede no funcionar"
    fi
    mark_done "glibc"
    log "Capa glibc verificada"
  fi
fi

# PASO 3-5 (método original) corren dentro de un subshell: cualquier error() (que hace
# "exit 1") dentro de este bloque solo termina el subshell, no el script completo — así
# PASO 5b (fork de respaldo) siempre llega a intentarse si el método original falla en
# cualquier punto, en vez de matar la instalación entera de una.
if ! (
step "PASO 3 — Verificando dependencias"
if check_done "deps"; then
  log "Dependencias ya verificadas [checkpoint]"
else
  _MISSING_DEPS=()
  command -v node &>/dev/null || _MISSING_DEPS+=("nodejs-lts")
  command -v rg &>/dev/null || _MISSING_DEPS+=("ripgrep")
  command -v sqlite3 &>/dev/null || _MISSING_DEPS+=("sqlite")
  command -v git &>/dev/null || _MISSING_DEPS+=("git")
  command -v clang &>/dev/null || _MISSING_DEPS+=("clang")
  command -v make &>/dev/null || _MISSING_DEPS+=("make")
  command -v curl &>/dev/null || _MISSING_DEPS+=("curl")
  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" 2>/dev/null || error "No se pudieron instalar dependencias: ${_MISSING_DEPS[*]}"
  fi
  command -v node &>/dev/null || error "Node.js no disponible tras instalación"
  mark_done "deps"
  log "Dependencias verificadas"
fi

# ── PASO 4 — Descargar binario de la última release (Método 2) ─
step "PASO 4 — Descargando CodeGraph (GitHub Releases)"
if check_done "download"; then
  log "CodeGraph ya descargado [checkpoint]"
else
  _LATEST_VERSION=$(curl -sI "https://github.com/${CODEGRAPH_REPO}/releases/latest" 2>/dev/null | grep -i location | sed -E 's#.*/tag/([^[:space:]]+).*#\1#' | tr -d '\r')
  [ -z "$_LATEST_VERSION" ] && error "No se pudo obtener la última versión de CodeGraph (GitHub)"
  info "Última versión: $_LATEST_VERSION"

  _TAR="$TERMUX_PREFIX/tmp/codegraph-linux-arm64.tar.gz"
  rm -f "$_TAR"
  if ! curl -fL --progress-bar \
    "https://github.com/${CODEGRAPH_REPO}/releases/download/${_LATEST_VERSION}/codegraph-linux-arm64.tar.gz" \
    -o "$_TAR" 2>/dev/null; then
    error "Descarga fallida — verificá conexión"
  fi
  [ -s "$_TAR" ] || error "Archivo descargado vacío"

  rm -rf "$CODEGRAPH_DATA"
  mkdir -p "$CODEGRAPH_DATA"
  # Bug real confirmado (auditoría ADB 2026-08-22, ver docs/humano/humano193.md, bug #28): el tarball
  # de GitHub Releases trae un directorio raíz con el mismo nombre que $CODEGRAPH_DATA
  # ("codegraph-linux-arm64/") envolviendo lib/dist/bin/codegraph.js — sin --strip-components=1
  # quedaba doble-anidado ($CODEGRAPH_DATA/codegraph-linux-arm64/lib/...) y el wrapper de
  # PASO 3 (que asume $CODEGRAPH_DATA/lib/dist/bin/codegraph.js directo) fallaba con
  # MODULE_NOT_FOUND en cada ejecución, pese a marcarse installed=true.
  tar -xzf "$_TAR" -C "$CODEGRAPH_DATA" --strip-components=1 || error "No se pudo extraer el binario descargado"
  rm -f "$_TAR"
  echo "${_LATEST_VERSION#v}" > "$CODEGRAPH_DATA/version"
  mark_done "download"
  log "CodeGraph descargado ($_LATEST_VERSION)"
fi

# ── PASO 5 — Wrapper (Método 2) ────────────────────────────────
step "PASO 5 — Creando wrapper"
if check_done "wrapper"; then
  log "Wrapper ya creado [checkpoint]"
else
  cat > "$TERMUX_PREFIX/bin/codegraph" << WRAPPER
#!$TERMUX_PREFIX/bin/bash
exec node "$CODEGRAPH_DATA/lib/dist/bin/codegraph.js" "\$@"
WRAPPER
  chmod +x "$TERMUX_PREFIX/bin/codegraph"
  # Post-condición real, no solo "existe el archivo" (bug #28 real: MODULE_NOT_FOUND pese a
  # que el wrapper existía y era ejecutable — ver docs/humano/humano194.md, verify_binary_installed()
  # en lib.sh). --help es el flag real que codegraph expone (ver cabecera del script).
  verify_binary_installed codegraph --help || error "codegraph no responde tras crear el wrapper (revisar extracción del tarball)"
  mark_done "wrapper"
  log "Wrapper creado en $TERMUX_PREFIX/bin/codegraph"
fi
); then
  warn "El método original (GitHub Releases + wrapper node) falló en algún paso — se intenta el fork de respaldo a continuación"
fi

# ── PASO 5b — Fork nativo (RESPALDO, solo si el método original no dejó el wrapper
#              funcionando) ────────────────────────────────────
if $_NATIVE && ! verify_binary_installed codegraph --help; then
  step "PASO 5b — Binario nativo del fork de respaldo (codegraph-termux)"
  if check_done "fork_install"; then
    log "CodeGraph (fork de respaldo) ya instalado [checkpoint]"
    _CODEGRAPH_FORK=true
  else
    warn "El método original no dejó codegraph funcional — probando el fork de respaldo..."
    if _codegraph_download_native_fork; then
      log "CodeGraph instalado vía el fork de respaldo (${CODEGRAPH_FORK_SOURCE_REPO})"
      mark_done "fork_install"
      _CODEGRAPH_FORK=true
    else
      warn "Falló también el fork de respaldo de codegraph"
    fi
  fi
fi

verify_binary_installed codegraph --help || error "codegraph no quedó funcional (ni el método original, ni el fork de respaldo)"

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
_CHANNEL="github_release"
$_CODEGRAPH_FORK && _CHANNEL="fork_native_arm64"
registry_write codegraph \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=${_CHANNEL}" \
  "install_date=${_DATE}"

notify_event "codegraph" "install_done" "$_VER_FINAL"
log "CodeGraph instalado correctamente (v${_VER_FINAL:-?}) — ejecutá 'codegraph' dentro de un proyecto"
rm -f "$CHECKPOINT"
exit 0
