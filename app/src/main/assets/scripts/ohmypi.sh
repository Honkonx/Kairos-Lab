#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ohmypi.sh (silent mode)
#  Instala Oh-My-Pi (omp) en Termux ARM64 — binario glibc nativo
#
#  INVESTIGACIÓN REAL (referencia/termux/core-termux-main/core/tools/ai/
#  oh-my-pi/, no asumido por el nombre — confirmado leyendo install.sh +
#  README.md + helper/omp_helper.c reales):
#    - Oh-My-Pi es la versión mejorada/standalone de Pi Coding Agent —
#      agente de codificación por terminal compilado con "bun build
#      --compile" contra glibc, con addons nativos en Rust (AST grep,
#      diff, syntax highlighting, fuzzy find, shell exec), gestión de
#      sesiones y soporte MCP. Repo real: github.com/can1357/oh-my-pi
#      (autor Can Boluk). Comando final: "omp".
#    - El README documenta a Pi como dependencia ("installed automatically
#      as dependency") pero el install.sh de referencia NO la fuerza en
#      código — mismo criterio acá: se documenta la relación (ver módulo
#      "pi" en Kairos) sin forzar una instalación en cascada no pedida
#      por el usuario (mismo patrón que cactus.sh "requires" python).
#    - core-termux ofrece 3 métodos de instalación (selector interactivo):
#      glibc nativo (recomendado), glibc+proot, proot-distro (Ubuntu). En
#      modo --silent de Kairos no hay selector interactivo posible — se
#      usa SIEMPRE el método "glibc nativo (recomendado)", el mismo que
#      core-termux marca como default. Los otros 2 métodos no se
#      implementan acá (agregan proot sin necesidad real: glibc nativo ya
#      corre a velocidad nativa, sin overhead).
#    - Flags/opciones reales confirmadas en el README oficial: "omp"
#      (sesión interactiva), "omp -p '<prompt>'" (prompt one-shot),
#      "omp -c" (continuar la última sesión), "omp --version".
#
#  QUÉ INSTALA:
#    ✅ glibc-repo + glibc (paquetes reales de Termux — provee
#       ld-linux-aarch64.so.1 y las libs glibc bajo $PREFIX/glibc/)
#    ✅ jq + curl + tar + clang (deps reales del install.sh de referencia)
#    ✅ Binario omp-linux-arm64 (última release de GitHub) en
#       $HOME/.local/share/kairos-data/oh-my-pi/omp
#    ✅ Helper nativo compilado con clang ($PREFIX/bin/omp) que invoca el
#       binario a través del loader glibc (ld-linux-aarch64.so.1) — mismo
#       código fuente que usa core-termux (omp_helper.c), sin reescribir
#       la lógica del loader a mano
#    ✅ Registry actualizado
#
#  CONFIG PERSISTENTE: $HOME/.omp (creada por el propio binario)
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Ninguna configuración de proveedor/API key — "Multi-model
#       support" es responsabilidad del propio binario "omp" en tiempo de
#       ejecución (fuera del alcance de este instalador, sin evidencia de
#       subcomando de shell de una sola línea en el código fuente
#       disponible)
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (nuevo módulo, fuente
#  referencia/termux/core-termux-main/core/tools/ai/oh-my-pi/install.sh +
#  helper/omp_helper.c)
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
{"id":"ohmypi","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"Oh-My-Pi (can1357/oh-my-pi) — version mejorada/standalone de Pi Coding Agent, comando 'omp'. Instala SIEMPRE via glibc nativo (metodo recomendado de core-termux) — sin proot."}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ohmypi_checkpoint"
OMP_DATA="$HOME/.local/share/kairos-data/oh-my-pi"
OMP_REPO="can1357/oh-my-pi"

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. NOTA REAL (2026-08-23, ver
# docs/humano206.md/docs/estructura/ESTRUCTURA_MODULOS.md): modules.json marcaba
# ohmypi con arch:"bionic", pero la cabecera de este script (líneas 4/9-11) confirma
# que es un binario GLIBC (omp-linux-arm64, corrido vía loader glibc — mismo patrón
# que claude native, arch:"glibc"). Corregido 2026-08-25 en modules.json
# (arch:"bionic" → "glibc", mismo valor que usan claude/opencode/otros módulos
# glibc-runner) — ver MEJORAS_PENDIENTES.md.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/omp" \
    --arg p2 "$OMP_DATA/omp" \
    --arg p3 "$OMP_DATA/version" \
    --arg dep_check "test -f \"$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1\"" \
    --arg verify "\"$TERMUX_PREFIX/bin/omp\" --version >/dev/null 2>&1" \
    --arg patch "chmod +x \"$TERMUX_PREFIX/bin/omp\" \"$OMP_DATA/omp\" 2>/dev/null || true" \
    '{
      id: "ohmypi",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-ohmypi",
      version_registry_key: "ohmypi.version",
      files: [
        {path: $p1, required: true, note: "Helper compilado con clang (omp_helper.c) — ejecuta $p2 a través del loader glibc, mismo binario ARM64 sirve en cualquier device con el mismo glibc de Termux"},
        {path: $p2, required: true, note: "Binario omp-linux-arm64 descargado de GitHub Releases, linkeado contra glibc"},
        {path: $p3, required: false, note: "Versión instalada"}
      ],
      file_globs: [],
      dependencies: [
        {id: "glibc_ld", check_cmd: $dep_check, install_hint: "pkg install -y glibc-repo && pkg install -y glibc"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "No empaqueta $HOME/.omp (config/estado creado por el propio binario)",
        "El helper (omp_helper.c) tiene las rutas de HOME/PREFIX embebidas como literales C compilados — funciona porque Termux siempre usa las mismas rutas absolutas, pero no es un binario genérico reubicable a otro HOME"
      ]
    }'
  exit 0
fi

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^ohmypi_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "ohmypi_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^ohmypi_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  [ -f "$OMP_DATA/version" ] && cat "$OMP_DATA/version" || echo ""
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  Ω OH-MY-PI (omp) — Instalador            ║"
  echo "  ║  can1357 · glibc nativo · Termux ARM64    ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v omp &>/dev/null && [ -f "$OMP_DATA/omp" ] && ! $FORCE; then
  log "Oh-My-Pi ya instalado ($(get_installed_ver))"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo "  Se instala vía glibc nativo (método recomendado — sin proot,"
  echo "  velocidad nativa)."
  echo -n "  ¿Instalar Oh-My-Pi? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — glibc + dependencias ──────────────────────────────
step "PASO 1 — glibc y dependencias"
if check_done "deps"; then
  log "Dependencias ya verificadas [checkpoint]"
else
  if [ ! -f "$TERMUX_PREFIX/etc/apt/sources.list.d/glibc.list" ]; then
    info "Instalando glibc-repo..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc-repo 2>/dev/null || error "No se pudo instalar glibc-repo"
    info "Actualizando índices de paquetes (repo glibc recién agregado)..."
    pkg update -y 2>/dev/null || error "pkg update falló tras agregar glibc-repo"
  fi

  if [ ! -f "$TERMUX_PREFIX/glibc/lib/libc.so.6" ]; then
    info "Instalando glibc..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc 2>/dev/null || error "No se pudo instalar glibc"
  fi
  [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || error "glibc instalado pero falta el loader ld-linux-aarch64.so.1 — dispositivo no soportado (solo aarch64/arm64)"

  _MISSING_DEPS=()
  command -v jq &>/dev/null || _MISSING_DEPS+=("jq")
  command -v curl &>/dev/null || _MISSING_DEPS+=("curl")
  command -v tar &>/dev/null || _MISSING_DEPS+=("tar")
  command -v clang &>/dev/null || _MISSING_DEPS+=("clang")
  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" 2>/dev/null || error "No se pudieron instalar dependencias: ${_MISSING_DEPS[*]}"
  fi
  mark_done "deps"
  log "glibc y dependencias verificadas"
fi

# ── PASO 2 — Descargar binario omp-linux-arm64 ─────────────────
step "PASO 2 — Descargando Oh-My-Pi (GitHub Releases)"
if check_done "download"; then
  log "Binario ya descargado [checkpoint]"
else
  _LATEST_VERSION=$(curl -fsSL "https://api.github.com/repos/${OMP_REPO}/releases?per_page=5" 2>/dev/null | \
    jq -r '[.[] | select(.tag_name | startswith("v"))][0].tag_name' 2>/dev/null)
  [ -z "$_LATEST_VERSION" ] || [ "$_LATEST_VERSION" = "null" ] && error "No se pudo obtener la última versión de Oh-My-Pi (GitHub)"
  info "Última versión: $_LATEST_VERSION"

  mkdir -p "$OMP_DATA"
  if ! curl -fsSL "https://github.com/${OMP_REPO}/releases/download/${_LATEST_VERSION}/omp-linux-arm64" \
    -o "$OMP_DATA/omp" 2>/dev/null; then
    error "Descarga fallida — verificá conexión"
  fi
  [ -s "$OMP_DATA/omp" ] || error "Archivo descargado vacío"
  chmod +x "$OMP_DATA/omp"
  echo "$_LATEST_VERSION" > "$OMP_DATA/version"
  mark_done "download"
  log "Oh-My-Pi descargado ($_LATEST_VERSION)"
fi

# ── PASO 3 — Compilar helper glibc (mismo código que core-termux) ──
step "PASO 3 — Compilando helper nativo (clang)"
if check_done "helper"; then
  log "Helper ya compilado [checkpoint]"
else
  _HELPER_SRC="$TERMUX_PREFIX/tmp/omp_helper.c"
  cat > "$_HELPER_SRC" << 'CSRC'
/**
 * omp_helper.c — glibc loader helper for Oh-My-Pi standalone binary
 * (idéntico al helper de referencia/termux/core-termux-main, ver cabecera
 * de ohmypi.sh — el binario omp-linux-arm64 está linkeado contra glibc,
 * Termux usa bionic; este helper lo ejecuta a través del loader glibc).
 */
#include <stdlib.h>
#include <unistd.h>
#include <stdio.h>

int main(int argc, char** argv) {
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");

    setenv("SSL_CERT_FILE", "/data/data/com.termux/files/usr/etc/tls/cert.pem", 1);
    setenv("TMPDIR", "/data/data/com.termux/files/usr/tmp", 1);

    char* loader = "/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1";
    char real_bin[] = "/data/data/com.termux/files/home/.local/share/kairos-data/oh-my-pi/omp";
    char lib_path[] = "/data/data/com.termux/files/usr/glibc/lib";

    char** new_argv = malloc((argc + 4) * sizeof(char*));
    if (!new_argv) {
        return 1;
    }

    new_argv[0] = loader;
    new_argv[1] = "--library-path";
    new_argv[2] = lib_path;
    new_argv[3] = real_bin;

    for (int i = 1; i < argc; i++) {
        new_argv[i + 3] = argv[i];
    }
    new_argv[argc + 3] = NULL;

    execv(loader, new_argv);

    perror("execv");
    free(new_argv);
    return 1;
}
CSRC

  if ! clang -O2 -o "$TERMUX_PREFIX/bin/omp" "$_HELPER_SRC" 2>&1 | tail -10; then
    rm -f "$_HELPER_SRC"
    error "No se pudo compilar el helper de Oh-My-Pi (clang)"
  fi
  chmod +x "$TERMUX_PREFIX/bin/omp"
  rm -f "$_HELPER_SRC"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed omp || error "omp no ejecuta tras compilar el helper (revisá manualmente: omp --version)"
  mark_done "helper"
  log "Helper compilado en $TERMUX_PREFIX/bin/omp"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write ohmypi \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=github_release" \
  "install_method=glibc_native" \
  "install_date=${_DATE}"

notify_event "ohmypi" "install_done" "$_VER_FINAL"
log "Oh-My-Pi instalado correctamente (${_VER_FINAL:-?}) — ejecutá 'omp' en la terminal"
rm -f "$CHECKPOINT"
exit 0
