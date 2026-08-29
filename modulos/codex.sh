#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · codex.sh (silent mode)
#  Instala OpenAI Codex CLI en Termux ARM64 (vía npm)
#
#  USO DESDE APP (KairosApp):
#    bash codex.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_codex.sh
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo (canal termux por defecto)
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Node.js (nodejs-lts, si falta)
#    ✅ Codex CLI — canal termux (@mmmbuto/codex-cli-termux@latest)
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login interactivo (codex login) — queda para que el usuario lo haga
#       manualmente después, no se puede automatizar sin credenciales
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 1.0.0 | Julio 2026 (adaptado de install_codex.sh v1.0.0)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
VARIANT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --variant)  VARIANT="$2"; shift ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"codex","supports_silent":true,"supports_force":true,"variants":["termux","native"],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Codex tiene 2 variantes reales
# (termux=npm, native=binario prebuilt) — se detecta cuál está activa por
# archivos reales en vez de asumir. La variante native es la más simple de
# empaquetar (un binario + symlink); la variante npm queda con file_globs
# sobre el paquete completo (la ruta exacta del wrapper de npm varía según
# "npm root -g", no es un archivo fijo predecible).
if $DESCRIBE_FILES; then
  _df_native_bin="$TERMUX_PREFIX/opt/codex-native/codex"
  _df_native_link="$TERMUX_PREFIX/bin/codex"
  if [ -f "$_df_native_bin" ]; then
    jq -n \
      --arg p1 "$_df_native_bin" \
      --arg p2 "$_df_native_link" \
      --arg verify "test -x \"$_df_native_bin\" && \"$_df_native_link\" --version >/dev/null 2>&1" \
      '{
        id: "codex", supports_describe_files: true, variant: "native",
        package_name: "kairos-module-codex",
        version_registry_key: "codex.version",
        files: [
          {path: $p1, required: true, note: "Binario ARM64 prebuilt de codex_android (WangChengYeh/codex_android), sin Node.js"},
          {path: $p2, required: true, note: "Symlink a codex-native/codex"}
        ],
        file_globs: [], dependencies: [],
        verify_cmd: $verify,
        patch_cmd: "",
        not_covered: ["No hay parche real conocido para el binario native — si el symlink se rompe, recrearlo con ln -sf es suficiente y no hace falta patch_cmd"]
      }'
  else
    local_npm_root=$(npm root -g 2>/dev/null)
    jq -n \
      --arg glob "${local_npm_root}/@mmmbuto/codex-cli-termux/**" \
      '{
        id: "codex", supports_describe_files: true, variant: "termux",
        package_name: "kairos-module-codex",
        version_registry_key: "codex.version",
        files: [], file_globs: [{pattern: $glob, required: false, note: "paquete npm completo — ruta depende de npm root -g del device que empaqueta"}],
        dependencies: [{id: "node", check_cmd: "command -v node", install_hint: "pkg install -y nodejs-lts"}],
        verify_cmd: "command -v codex >/dev/null 2>&1 && codex --version >/dev/null 2>&1",
        patch_cmd: "",
        not_covered: ["Variante termux (npm): el wrapper bin de npm (symlink en $PREFIX/bin/codex creado por npm) no se captura como archivo discreto — solo el paquete completo vía file_globs. Reinstalar puede requerir \"npm rebuild\" si los symlinks no sobreviven la copia"]
      }'
  fi
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_codex_checkpoint"
CODEX_CHANNEL="termux"
# 2026-07-31: dist-tag corregido de @next a @latest — el README real del repo
# (github.com/DioNanos/codex-termux) documenta "next" como "tested candidate
# versions published after GitHub Actions validation" (release en camino,
# no promovido aún) y dice explícitamente "@latest es el target recomendado
# para usuarios finales". Confirmado además contra el registry npm real:
# dist-tags actuales son stable=0.144.4, next=0.145.0, latest=0.146.0 — es
# decir "next" queda POR DEBAJO de "latest" en este paquete (no es un canal
# "más nuevo/beta" como en otros paquetes), así que instalar @next instalaba
# a propósito una versión más vieja que la recomendada por el propio proyecto.
CODEX_PKG="@mmmbuto/codex-cli-termux@latest"

# 2026-07-28: --variant native — binario ARM64 prebuilt de codex_android, sin
# Node.js en absoluto (ver docs/referencias/REFERENCIA_TERMUX_AI_MASTER.md). Versión
# pinneada a propósito (no "latest") — repo de un solo mantenedor, no oficial
# de OpenAI, riesgo asumido conscientemente. Rama totalmente separada del flujo
# npm de abajo — sale por su propio exit antes de tocar Node.js.
CODEX_NATIVE_VERSION="0.25.0"
CODEX_NATIVE_URL="https://github.com/WangChengYeh/codex_android/releases/download/v${CODEX_NATIVE_VERSION}/android-codex-cli-binaries-${CODEX_NATIVE_VERSION}-aarch64.tar.gz"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# check_done/mark_done: codex namespacea las keys (codex_X=done),
# se sobreescriben acá encima de las genéricas de lib.sh
mark_done()  { grep -q "^codex_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "codex_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^codex_${1}=done" "$CHECKPOINT" 2>/dev/null; }


get_installed_ver() {
  command -v codex &>/dev/null && codex --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ CODEX CLI — Instalador               ║"
  echo "  ║  OpenAI · Termux ARM64                  ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Codex CLI ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Codex CLI? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

if [ "$VARIANT" = "native" ]; then
  step "Instalando binario nativo (sin Node.js) — v${CODEX_NATIVE_VERSION}"
  info "Descargando binario de codex_android..."
  tmp="$HOME/tmp/codex_native_dl"
  mkdir -p "$tmp"
  tarball="$tmp/codex-native.tar.gz"
  curl -fsSL --max-time 60 "$CODEX_NATIVE_URL" -o "$tarball" 2>/dev/null \
    || wget -q --timeout=60 "$CODEX_NATIVE_URL" -O "$tarball" 2>/dev/null \
    || error "Descarga falló"
  [ -s "$tarball" ] || error "Descarga vacía o incompleta"
  tar -xzf "$tarball" -C "$tmp" || error "No se pudo extraer el tarball"
  bin_src=$(find "$tmp" -type f -name "codex-aarch64-linux-android" | head -1)
  [ -z "$bin_src" ] && error "No se encontró el binario en el tarball"
  mkdir -p "$TERMUX_PREFIX/opt/codex-native"
  cp "$bin_src" "$TERMUX_PREFIX/opt/codex-native/codex"
  chmod +x "$TERMUX_PREFIX/opt/codex-native/codex"
  ln -sf "$TERMUX_PREFIX/opt/codex-native/codex" "$TERMUX_PREFIX/bin/codex"
  # Verificación real de ejecución, no solo que el archivo exista (mismo patrón
  # que cloudflared/ssh.sh — un binario ARM64 no siempre corre sobre Bionic).
  if ! codex --version &>/dev/null; then
    rm -f "$TERMUX_PREFIX/bin/codex"
    rm -rf "$TERMUX_PREFIX/opt/codex-native"
    error "Binario descargado pero no ejecuta (incompatible con Bionic)"
  fi
  rm -rf "$tmp"
  mkdir -p "$HOME/.config/codex"
  registry_write codex "installed=true" "version=${CODEX_NATIVE_VERSION}" "channel=native" "install_date=$(date +%Y-%m-%d)"
  log "Codex CLI (native) instalado: $(codex --version 2>/dev/null | head -1)"
  exit 0
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
    # Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): "npm install
    # -g npm" sobreescribe el npm parcheado para Termux (shebang sin /usr/bin/env, que
    # acá no existe) con uno genérico del registry — "bad interpreter" en cualquier
    # npm posterior. El npm que trae nodejs-lts ya alcanza.
    log "Node.js instalado: $(node --version)"
    mark_done "node"
  fi
fi

# ── PASO 2 — npm install ─────────────────────────────────────
step "PASO 2 — Instalando Codex CLI vía npm"
if check_done "npm_install"; then
  log "Codex CLI ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${CODEX_PKG}"
  npm install -g "$CODEX_PKG" 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real confirmado por ADB (docs/humano269.md, auditoría 2026-08-27, mismo patrón ya
  # documentado en lib.sh/expo.sh): el symlink que "npm install -g" genera no ejecuta directo
  # en este dispositivo — sin este fix, verify_binary_installed de acá abajo podía fallar de
  # forma intermitente aunque la instalación en sí haya salido bien.
  fix_npm_shebang_wrapper codex "$CODEX_PKG"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed codex || error "codex no ejecuta tras la instalación (revisá manualmente: codex --version)"
  log "Codex CLI instalado"
  mark_done "npm_install"
fi

# ── PASO 3 — Login (omitido en modo silencioso) ──────────────
step "PASO 3 — Autenticación"
if check_done "login"; then
  log "Login ya completado [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — login omitido, ejecutá 'codex login' manualmente después"
  mark_done "login"
else
  echo -n "  ¿Iniciar login ahora? (s/n): "
  read -r _DO_LOGIN < /dev/tty
  if [ "$_DO_LOGIN" = "s" ] || [ "$_DO_LOGIN" = "S" ]; then
    codex login < /dev/tty
    log "Login completado"
  else
    warn "Login omitido — ejecuta 'codex login' manualmente"
  fi
  mark_done "login"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write codex \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=${CODEX_CHANNEL}" \
  "install_date=${_DATE}"

notify_event "codex" "install_done" "$_VER_FINAL"
log "Codex CLI instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0
