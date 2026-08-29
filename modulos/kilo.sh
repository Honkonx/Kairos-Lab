#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · kilo.sh (silent mode)
#  Instala Kilo Code CLI en Termux ARM64 — binario nativo + glibc
#
#  USO DESDE APP (KairosApp):
#    bash kilo.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ glibc-repo + glibc + patchelf-glibc (capa de compatibilidad, si falta)
#    ✅ Binario nativo ARM64 de GitHub Releases (Kilo-Org/kilocode,
#       kilo-linux-arm64.tar.gz) parcheado con patchelf --set-interpreter al
#       loader glibc de Termux — comando: kilo
#    ✅ Registry actualizado
#
#  QUÉ CAMBIÓ (v2.0.0, 2026-08-24, ver docs/humano215.md):
#    v1.0.0 instalaba vía "npm install -g @kilocode/cli" — el package.json
#    de ese paquete no lista "android" en su campo "os" (--force lo saltea,
#    ver v1.1.0/humano212), pero incluso saltando eso, su postinstall.mjs
#    busca un paquete opcional "@kilocode/cli-android-arm64" que
#    Kilo-Org nunca publicó en npm (404 real, confirmado 2026-08-24) — el
#    mismo patrón de raíz que mimocode v1 (paquete de plataforma inexistente
#    en npm). Investigación real (mismo criterio que mimocode/codebuff)
#    encontró la solución en `referencia/termux/core-termux-main/core/tools/
#    ai/kilocode-cli/install.sh`: descarga el binario Linux ARM64 real de
#    GitHub Releases (nada de npm) y lo corre bajo glibc — mismo mecanismo
#    exacto que ya se portó para mimocode.sh v2.0.0. Se porta SOLO el método
#    glibc nativo (mismo criterio ya establecido: "glibc nada de proot
#    distro"), no los otros 2 métodos que core-termux ofrece.
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ API key / proveedor — queda para que el usuario configure
#       manualmente ejecutando 'kilo' la primera vez
#
#  NOTA:
#    Kilo Code es un fork del motor OpenCode (el mismo que Kairos ya
#    instala como módulo opencode) con su propio ecosistema/proveedores.
#    Se mantiene como módulo separado porque es un binario/paquete distinto
#    y el usuario puede querer ambos (ver docs/humano/humano123.md).
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 2.0.0 | Agosto 2026 (binario nativo ARM64 + glibc, reemplaza el
#  método npm roto — ver docs/humano215.md)
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
{"id":"kilo","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v kilo 2>/dev/null || echo "$TERMUX_PREFIX/bin/kilo")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$HOME/.local/share/kilo/**" \
    --arg verify "command -v kilo >/dev/null 2>&1 && kilo --version >/dev/null 2>&1" \
    '{
      id: "kilo",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-kilo",
      version_registry_key: "kilo.version",
      files: [{path: $path, required: true, note: "Wrapper que ejecuta el binario nativo ARM64 parcheado con patchelf, resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob, required: true, note: "binario real (~/.local/share/kilo/kilo) parcheado con patchelf, que el wrapper de arriba exec-a — bug real confirmado en dispositivo (docs/humano281.md): sin esto el wrapper queda roto en un device nuevo"}
      ],
      dependencies: [
        {id: "glibc_ld", check_cmd: "[ -f \"$PREFIX/glibc/lib/ld-linux-aarch64.so.1\" ]", install_hint: "pkg install -y glibc-repo && pkg install -y glibc"},
        {id: "patchelf", check_cmd: "[ -x \"$PREFIX/glibc/bin/patchelf\" ]", install_hint: "pkg install -y patchelf-glibc"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No incluye credenciales/configuración del usuario — reconfigurar tras reinstalar"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_kilo_checkpoint"
KILO_DIR="$HOME/.local/share/kilo"
KILO_WRAPPER="$TERMUX_PREFIX/bin/kilo"
GLIBC_LD="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PATCHELF="$TERMUX_PREFIX/glibc/bin/patchelf"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^kilo_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "kilo_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^kilo_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  local v=""
  command -v kilo &>/dev/null && v=$(kilo --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$v" ] && [ -x "$KILO_DIR/kilo" ] && v=$("$KILO_DIR/kilo" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  echo "$v"
}

# Descarga el binario nativo ARM64 + parchea con patchelf (patrón core-termux,
# mismo mecanismo de descubrimiento en vivo que freebuff.sh/mimocode.sh — no
# hardcodear nombres de tag/asset/binario).
#
# Bug real confirmado 2026-08-27 (docs/humano269.md, auditoría ADB): Kilo-Org/kilocode
# publica DOS streams de releases bajo el mismo repo — la CLI nativa (assets
# "*-linux-arm64.tar.gz") y el plugin de JetBrains (tags "jetbrains/vX.Y.Z", assets .zip,
# SIN ningún linux-arm64.tar.gz). /releases/latest de GitHub devuelve la release MÁS
# RECIENTE por fecha sin importar el stream — cuando el plugin de JetBrains publica
# después que la CLI (pasó exactamente eso el 2026-08-27), /releases/latest devuelve esa
# release de JetBrains, que SÍ trae "tag_name" (no dispara el fallback de abajo) pero NO
# trae ningún asset linux-arm64.tar.gz — la descarga fallaba SIEMPRE en ese escenario,
# no de forma intermitente. Fix: no usar /releases/latest en absoluto — pedir la lista
# completa (/releases?per_page=20, más releases que antes para no quedarse corto si hay
# varias del stream JetBrains seguidas) y quedarse con el PRIMER asset linux-arm64.tar.gz
# que aparezca en todo el JSON (la lista ya viene ordenada más-nueva-primero, así que el
# primer match es la release de CLI más reciente, sin importar cuántas de JetBrains la
# precedan). El tag real (KILO_LATEST) se extrae de la URL de descarga misma
# (.../releases/download/<tag>/<archivo>), no de un "tag_name" grepeado aparte — así
# tag y asset SIEMPRE pertenecen a la misma release, sin posibilidad de desincronizarse.
_kilo_download_native() {
  info "Consultando releases reales (GitHub API Kilo-Org/kilocode)..."
  local releases_json
  releases_json=$(curl -fsSL "https://api.github.com/repos/Kilo-Org/kilocode/releases?per_page=20" 2>/dev/null)
  [ -z "$releases_json" ] && return 1

  local url latest
  url=$(echo "$releases_json" | grep -o '"browser_download_url": *"[^"]*linux-arm64\.tar\.gz"' | \
    head -1 | grep -o 'https://[^"]*')
  [ -z "$url" ] && return 1
  latest=$(echo "$url" | sed -E 's#.*/releases/download/([^/]+)/.*#\1#')
  [ -z "$latest" ] && return 1
  KILO_LATEST="$latest"

  local tarball; tarball=$(basename "$url")
  local tmp="$HOME/tmp/kilo_${latest//\//_}_$$"
  mkdir -p "$tmp" "$KILO_DIR"

  info "Descargando binario nativo ARM64 ($tarball, release $latest)..."
  curl -fsSL "$url" -o "$tmp/$tarball" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  tar -zxf "$tmp/$tarball" -C "$KILO_DIR" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  rm -rf "$tmp"

  # Bug real encontrado 2026-08-24 (ver docs/humano216.md): el tarball trae
  # VARIOS archivos ejecutables además del binario real (bwrap, kilo-sandbox-
  # seccomp, kilo-sandbox-*.js) — a diferencia de freebuff/mimocode que
  # extraen un único binario limpio. "find | head -1" agarraba el primero
  # en orden de directorio (no alfabético garantizado), no necesariamente
  # "kilo", y patchelf fallaba por parchear el archivo equivocado
  # (bwrap/seccomp, binarios reales pero distintos). Fix: preferir el
  # archivo literalmente llamado "kilo" si existe, con el heurístico viejo
  # (único ejecutable) solo como fallback si ese nombre no aparece.
  local _real_bin
  if [ -f "$KILO_DIR/kilo" ]; then
    _real_bin="$KILO_DIR/kilo"
  else
    _real_bin=$(find "$KILO_DIR" -maxdepth 1 -type f ! -name "*.tar.gz" ! -name "*.js" | head -1)
  fi
  [ -z "$_real_bin" ] && return 1
  chmod +x "$_real_bin"
  KILO_REAL_BIN="$_real_bin"

  if [ -f "$GLIBC_LD" ]; then
    info "Aplicando patchelf al binario nativo..."
    if [ -x "$PATCHELF" ]; then
      "$PATCHELF" --set-interpreter "$GLIBC_LD" "$_real_bin" 2>/dev/null || \
        warn "patchelf falló — el binario puede requerir ajuste manual"
    else
      warn "patchelf no encontrado en $PATCHELF — el binario puede requerir ajuste manual"
    fi
  fi

  # Bug real encontrado 2026-08-24 (probado en dispositivo real, ADB): el binario
  # (compilado con Bun) intenta crear /tmp/kilo al arrancar — "/tmp" real del
  # sistema Android no es escribible para la app (EACCES: permission denied,
  # mkdir '/tmp/kilo'), pese a que el binario corre y patchelf funcionó bien.
  # Bun respeta TMPDIR si está seteado — $PREFIX/tmp ya existe por defecto en
  # Termux, no hace falta crearlo. Confirmado con TMPDIR override: "kilo --version"
  # responde limpio (7.4.23) en vez de crashear.
  cat > "$KILO_WRAPPER" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
export TMPDIR="$TERMUX_PREFIX/tmp"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec "$_real_bin" "\$@"
WRAPPER
  chmod +x "$KILO_WRAPPER"

  "$KILO_WRAPPER" --version >/dev/null 2>&1 || {
    warn "Binario nativo instalado pero no responde a --version"
    return 1
  }
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ⚡ KILO CODE — Instalador                ║"
  echo "  ║  Kilo Code · Termux ARM64                ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Kilo Code ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Kilo Code? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Capa de compatibilidad glibc ────────────────────
step "PASO 1 — Capa de compatibilidad glibc (binario nativo ARM64)"
if check_done "glibc"; then
  log "glibc ya verificado [checkpoint]"
else
  _MISSING_DEPS=()
  [ -f "$GLIBC_LD" ] || _MISSING_DEPS+=("glibc-repo" "glibc")
  [ -x "$PATCHELF" ] || _MISSING_DEPS+=("patchelf-glibc")
  command -v curl &>/dev/null || _MISSING_DEPS+=("curl")

  if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
    info "Instalando: ${_MISSING_DEPS[*]}"
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>/dev/null || \
      error "No se pudieron instalar las dependencias glibc"
    [ -f "$GLIBC_LD" ] || error "glibc ld.so no encontrado tras la instalación"
  fi

  mark_done "glibc"
  log "Capa glibc verificada"
fi

# ── PASO 2 — Binario nativo ARM64 ────────────────────────────
step "PASO 2 — Instalando Kilo Code nativo ARM64 (glibc + patchelf)"
if check_done "native_install"; then
  log "Kilo Code nativo ya instalado [checkpoint]"
else
  _kilo_download_native || \
    error "No se pudo instalar el binario nativo de Kilo Code (descarga o verificación funcional falló — revisá conectividad a github.com/Kilo-Org/kilocode/releases)"
  log "Kilo Code nativo ARM64 instalado"
  mark_done "native_install"
fi

# ── PASO 3 — Configuración (omitido en modo silencioso) ───────
step "PASO 3 — Configuración de proveedor/API key"
if check_done "apikey"; then
  log "Configuración ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — configurá tu proveedor/API key manualmente después, ejecutá 'kilo'"
  mark_done "apikey"
else
  warn "Ejecutá 'kilo' y seguí el asistente para configurar tu proveedor/API key"
  mark_done "apikey"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write kilo \
  "installed=true" \
  "version=${_VER_FINAL:-${KILO_LATEST:-?}}" \
  "channel=native_arm64" \
  "install_date=${_DATE}"

notify_event "kilo" "install_done" "$_VER_FINAL"
log "Kilo Code instalado correctamente (v${_VER_FINAL:-${KILO_LATEST:-?}})"
rm -f "$CHECKPOINT"
exit 0
