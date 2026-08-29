#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · mistralvibe.sh (silent mode)
#  Instala Mistral Vibe en Termux ARM64 (vía pip, Python real)
#
#  USO DESDE APP (KairosApp):
#    bash mistralvibe.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Python 3 (ya viene del módulo Python, verificado igual por si no está)
#    ✅ Mistral Vibe (pip install mistral-vibe, paquete oficial) — comando: vibe
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Configuración de modelo/proveedor (config.toml) — queda para que el
#       usuario la haga manualmente al correr 'vibe' por primera vez
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.2.1 | Agosto 2026 — fix real: el manifiesto --describe-files
#  (usado por moduledeb.sh pack) listaba "node" como dependencia (copiado de
#  un módulo npm como copilotcli.sh/qwencode.sh) — este módulo usa Python/pip
#  + toolchain rust/clang, nunca Node.js. Corregido a python+rust reales
#  (auditoría de código 2026-08-27, ver docs/humano266.md).
#  VERSIÓN: 1.2.0 | Agosto 2026 — fix real: "pip install mistral-vibe" fallaba
#  con "Failed to build rpds-py" (dependencia transitiva en Rust, sin wheel
#  ARM64 en PyPI) por faltar el toolchain de compilación (rust/clang/make) —
#  ver PASO 1.5, docs/humano215.md.
#  VERSIÓN: 1.1.0 | Agosto 2026 — fix crash real en dispositivo: "pip install"
#  terminaba con exit 0 pero 'vibe' crasheaba al ejecutar por un binario-
#  incompatible de charset_normalizer (dependencia transitiva de mistral-vibe
#  sin pin de versión — pip elegía la última, que queda con .so/.py de
#  distintas versiones mezclados en este Python/arch). El chequeo anterior
#  solo hacía "command -v vibe" (existe en PATH), nunca ejecutaba el binario
#  real, por eso la instalación "silenciosa" nunca reportó el fallo. Ahora
#  PASO 2 corre "vibe --version" de verdad y, si crashea, se auto-repara
#  reinstalando charset-normalizer limpio (y si persiste, fijo a una versión
#  conocida-buena) antes de dar la instalación por exitosa.
#  (v1.0.0, Agosto 2026: nuevo módulo, candidato de i-Haklab, ver
#  docs/humano/humano99.md — repo oficial github.com/mistralai/mistral-vibe;
#  i-Haklab lo instala solo vía su propio apt repo, acá se usa el pip
#  oficial en su lugar, mismo criterio ya usado con freebuff/qwen-code)
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
# Corregido 2026-08-28: antes solo listaba el wrapper y declaraba el
# site-packages de pip como no_covered — mismo bug class que kilo/mimocode,
# solo que acá el payload es pip en vez de un binario nativo. site-packages
# es un directorio COMPARTIDO entre módulos pip (python.sh instala ahí
# numpy/scipy/etc también) — empaquetar todo el dir sería incorrecto
# (incluiría paquetes ajenos). En vez de un glob amplio, se usa `pip show -f`
# para listar exactamente los archivos que pertenecen a mistral-vibe (el
# paquete en sí, sin dependencias transitivas — charset-normalizer y demás
# siguen sin empaquetarse, ver not_covered).
if $DESCRIBE_FILES; then
  _bin=$(command -v vibe 2>/dev/null || echo "$TERMUX_PREFIX/bin/vibe")
  _pip_python=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  _vibe_files_json="[]"
  if [ -n "$_pip_python" ]; then
    _pip_show=$("$_pip_python" -m pip show -f mistral-vibe 2>/dev/null)
    _pip_location=$(echo "$_pip_show" | grep -m1 "^Location:" | sed 's/^Location: *//')
    if [ -n "$_pip_location" ]; then
      _vibe_files_json=$(echo "$_pip_show" | sed -n '/^Files:/,$p' | tail -n +2 | \
        sed 's/^ *//' | grep -v '^$' | \
        jq -R --arg loc "$_pip_location" '{path: ($loc + "/" + .), required: false}' | jq -s '.')
    fi
  fi
  jq -n \
    --arg path "$_bin" \
    --argjson vibe_files "$_vibe_files_json" \
    --arg verify "command -v vibe >/dev/null 2>&1 && vibe --version >/dev/null 2>&1" \
    '{
      id: "mistralvibe",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-mistralvibe",
      version_registry_key: "mistralvibe.version",
      files: ([{path: $path, required: true, note: "Wrapper/binario real de vibe (mistral-vibe), resuelto por PATH al momento de empaquetar"}] + $vibe_files),
      file_globs: [],
      dependencies: [
        {id: "python", check_cmd: "command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1", install_hint: "pkg install -y python"},
        {id: "rust", check_cmd: "command -v cargo >/dev/null 2>&1", install_hint: "pkg install -y rust clang make libffi openssl pkg-config"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "Las dependencias transitivas de pip (charset-normalizer y demás) no se empaquetan — solo los archivos propios de mistral-vibe (via pip show -f) más el wrapper. Si el device destino no tiene esas dependencias ya resueltas, reinstalar vía pip normal.",
        "Si pip show -f no devuelve Location:/Files: (formato distinto de pip, o mistral-vibe no está instalado al momento de empaquetar), $vibe_files queda vacío — el .deb solo trae el wrapper, igual que antes de este fix"
      ]
    }'
  exit 0
fi

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"mistralvibe","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_mistralvibe_checkpoint"
MISTRALVIBE_PKG="mistral-vibe"
# Versión conocida-buena de charset_normalizer usada como fallback si la
# última versión (la que pip elige por defecto, sin pin) queda con binarios
# incompatibles en este Python/arch — ver nota de versión arriba.
CHARSET_NORMALIZER_PIN="3.3.2"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^mistralvibe_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "mistralvibe_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^mistralvibe_${1}=done" "$CHECKPOINT" 2>/dev/null; }


get_installed_ver() {
  command -v vibe &>/dev/null && vibe --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

# ── Verificación funcional real (no solo "existe en PATH") ───
# Corre 'vibe --version' de verdad: un pip install con exit 0 no garantiza
# que el paquete importe bien en runtime (caso real: charset_normalizer con
# binarios mezclados de 2 versiones distintas → ImportError al arrancar).
_vibe_runtime_check() {
  local _out _rc
  _out=$(timeout 15 vibe --version 2>&1); _rc=$?
  if [ $_rc -eq 0 ] && ! echo "$_out" | grep -qi "Traceback\|ImportError\|ModuleNotFoundError"; then
    return 0
  fi
  echo "$_out" >&2
  return 1
}

# Reinstala charset_normalizer desde cero (sin caché, pisando cualquier .so/.py
# viejo que haya quedado mezclado). Con $2 vacío reinstala la misma versión
# "limpia"; con $2 seteado fija esa versión exacta (fallback si la última
# versión disponible no tiene wheel sano para este Python/arch).
_vibe_repair_charset_normalizer() {
  local _python="$1" _pin="${2:-}"
  local _pkg="charset-normalizer${_pin:+==$_pin}"
  info "Reinstalando ${_pkg} limpio (posible conflicto binario)..."
  pip_install "$_python" --force-reinstall --no-cache-dir "$_pkg" >/dev/null 2>&1
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ MISTRAL VIBE — Instalador              ║"
  echo "  ║  Mistral AI · Termux ARM64                ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Mistral Vibe ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Mistral Vibe? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Python ───────────────────────────────────────────
step "PASO 1 — Verificando Python"
if check_done "python"; then
  log "Python ya verificado [checkpoint]"
else
  PYTHON_PATH=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  if [ -n "$PYTHON_PATH" ]; then
    log "Python detectado: $($PYTHON_PATH --version 2>/dev/null)"
    mark_done "python"
  else
    info "Instalando python..."
    pkg_update_with_fallback
    pkg install python -y 2>/dev/null || error "No se pudo instalar Python"
    command -v python3 &>/dev/null || error "Python no disponible tras instalación"
    log "Python instalado: $(python3 --version)"
    mark_done "python"
  fi
fi

# ── PASO 1.5 — Toolchain de compilación (rust + clang + make) ─
# Bug real encontrado 2026-08-24 (ver docs/humano215.md): "pip install
# mistral-vibe" fallaba con "Failed to build rpds-py" — rpds-py es una
# dependencia transitiva de mistral-vibe escrita en Rust, sin wheel ARM64
# publicado en PyPI para Termux, así que pip intenta compilarla desde
# fuente y necesita el toolchain de Rust (cargo/rustc) + un compilador C
# (clang, para las bindings de PyO3) + headers de libffi/openssl. Sin esto
# el error aparece recién en PASO 2, tarde y sin pista de la causa real.
# Mismas dependencias que ya instala referencia/termux/core-termux-main/
# core/tools/ai/mistral-vibe/install.sh para la misma herramienta.
step "PASO 1.5 — Toolchain de compilación (rust, clang, make, libffi, openssl, pkg-config)"
if check_done "build_toolchain"; then
  log "Toolchain de compilación ya verificado [checkpoint]"
else
  info "Instalando: rust clang make libffi openssl pkg-config"
  pkg_update_with_fallback
  pkg install -y rust clang make libffi openssl pkg-config \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>/dev/null || \
    error "No se pudo instalar el toolchain de compilación (rust/clang/make)"
  command -v cargo &>/dev/null || error "cargo no disponible tras instalar rust"
  mark_done "build_toolchain"
  log "Toolchain de compilación listo"
fi

# ── PASO 2 — pip install ─────────────────────────────────────
step "PASO 2 — Instalando Mistral Vibe vía pip"
if check_done "pip_install"; then
  log "Mistral Vibe ya instalado [checkpoint]"
else
  PIP_PYTHON=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  info "Ejecutando: $PIP_PYTHON -m pip install ${MISTRALVIBE_PKG}"
  # pip_install() en vez de "$PIP_PYTHON" -m pip install directo (lib.sh, 2026-08-28, ver
  # docs/humano278.md): serializa con flock contra cualquier OTRO módulo instalando por pip
  # al mismo tiempo — confirmado en dispositivo que esta instalación falló mientras
  # ciberseguridad.sh corría pip install en paralelo.
  pip_install "$PIP_PYTHON" "$MISTRALVIBE_PKG" 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "pip install falló"
  # verify_binary_installed() en vez de command -v a secas (2026-08-22, ver docs/humano/humano201.md).
  verify_binary_installed vibe || error "vibe no ejecuta tras la instalación (revisá manualmente: vibe --version)"

  info "Verificando que 'vibe' ejecute de verdad (no solo que exista en PATH)..."
  if ! _vibe_runtime_check; then
    warn "vibe se instaló pero crashea al ejecutar — probable conflicto binario de charset_normalizer (dependencia transitiva sin pin). Reinstalando limpio..."
    _vibe_repair_charset_normalizer "$PIP_PYTHON"
    if ! _vibe_runtime_check; then
      warn "Seguía fallando — reintentando con charset-normalizer==${CHARSET_NORMALIZER_PIN} (versión conocida-buena)..."
      _vibe_repair_charset_normalizer "$PIP_PYTHON" "$CHARSET_NORMALIZER_PIN"
      _vibe_runtime_check || error "vibe sigue fallando tras 2 intentos de reparar charset-normalizer — revisá manualmente ejecutando 'vibe --version'"
    fi
  fi
  log "Mistral Vibe instalado y verificado (vibe --version ejecuta OK)"
  mark_done "pip_install"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write mistralvibe \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=pip" \
  "install_date=${_DATE}"

notify_event "mistralvibe" "install_done" "$_VER_FINAL"
log "Mistral Vibe instalado correctamente (v${_VER_FINAL:-?}) — ejecutá 'vibe' para configurar tu modelo"
rm -f "$CHECKPOINT"
exit 0
