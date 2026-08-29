#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · mimocode.sh (silent mode)
#  Instala MiMo Code (Xiaomi) en Termux ARM64 — binario nativo + glibc
#
#  USO DESDE APP (KairosApp):
#    bash mimocode.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA (2 métodos en cascada):
#    ✅ MÉTODO 1 (preferido): binario nativo del fork propio
#       github.com/Honkonx/MiMoCode-Termux (fork de Hope2333/MiMoCode-Termux)
#       — mismo patrón que freebuff.sh/bun-termux, codebuff.sh y
#       codegraph.sh: se descarga el asset real "*_aarch64.deb" de GitHub
#       Releases (fork primero, upstream Hope2333 como fallback si el fork
#       no tiene el asset todavía adjunto a su release) y se extrae el árbol
#       usr/ COMPLETO a $TERMUX_PREFIX. Confirmado 2026-08-28 descargando y
#       parseando el .deb real (Hope2333/MiMoCode-Termux, tag Push260803):
#       a diferencia de codebuff/codegraph, el binario real
#       (usr/lib/mimocode/runtime/mimocode) es BIONIC NATIVO PURO — ELF
#       interpreter "/system/bin/linker64", SIN dependencia de glibc en
#       absoluto — mejor que el MÉTODO 2 de abajo, que sí necesita la capa
#       glibc + patchelf. No hace falta ni glibc ni patchelf en este método.
#    ✅ MÉTODO 2 (fallback si el 1 falla): el flujo previo — glibc-repo +
#       glibc + patchelf-glibc (capa de compatibilidad, si falta) + binario
#       nativo ARM64 de GitHub Releases (XiaomiMiMo/MiMo-Code,
#       *-linux-arm64.tar.gz) parcheado con patchelf --set-interpreter al
#       loader glibc de Termux — comando: mimo
#    ✅ Registry actualizado
#
#  QUÉ CAMBIÓ (v2.0.0, 2026-08-24, ver docs/humano212.md):
#    v1.0.0 instalaba vía "npm install -g @mimo-ai/cli" — falla SIEMPRE en
#    Android/Termux porque XiaomiMiMo nunca publicó el paquete opcional
#    "@mimo-ai/mimocode-android-arm64" que el postinstall.mjs de ese CLI
#    busca (404 real, confirmado 2026-08-14). Investigación real (pedido
#    explícito del usuario: "busca si alguien lo hizo funcionar en termux
#    android") encontró que `referencia/termux/core-termux-main/core/tools/
#    ai/mimocode/install.sh` SÍ lo resuelve — descarga el binario Linux
#    ARM64 real de GitHub Releases (nada de npm) y lo corre bajo glibc.
#    Ese proyecto ofrece 3 métodos (glibc nativo, glibc+proot, proot-distro
#    Ubuntu completo); acá se porta SOLO el método glibc nativo (pedido
#    explícito: "glibc nada de proot distro") — mismo patrón ya usado y
#    probado en claude.sh (variante native)/freebuff.sh, sin reinventar
#    nada ni agregar la complejidad de un contenedor proot para esto.
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Autenticación (soporta "MiMo Auto" anónimo gratis por tiempo limitado,
#       login OAuth de Xiaomi MiMo Platform, migración desde Claude Code, o
#       cualquier API custom compatible con OpenAI) — el propio TUI de 'mimo'
#       guía la configuración en el primer arranque
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 3.0.0 | Agosto 2026 (fork nativo MiMoCode-Termux — Bionic puro,
#  sin glibc — como método preferido, ver github.com/Honkonx/MiMoCode-Termux;
#  fallback al binario oficial XiaomiMiMo + glibc/patchelf que reemplazó el
#  método npm roto — ver docs/humano212.md)
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
{"id":"mimocode","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v mimo 2>/dev/null || echo "$TERMUX_PREFIX/bin/mimo")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$HOME/.local/share/mimocode/**" \
    --arg verify "command -v mimo >/dev/null 2>&1 && mimo --version >/dev/null 2>&1" \
    '{
      id: "mimocode",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-mimocode",
      version_registry_key: "mimocode.version",
      files: [{path: $path, required: true, note: "Wrapper que ejecuta el binario nativo ARM64 parcheado con patchelf, resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob, required: true, note: "binario real (~/.local/share/mimocode/<binario>) parcheado con patchelf, que el wrapper de arriba exec-a — sin esto el wrapper queda roto en un device nuevo"}
      ],
      dependencies: [
        {id: "glibc_ld", check_cmd: "[ -f \"$PREFIX/glibc/lib/ld-linux-aarch64.so.1\" ]", install_hint: "pkg install -y glibc-repo && pkg install -y glibc"},
        {id: "patchelf", check_cmd: "[ -x \"$PREFIX/glibc/bin/patchelf\" ]", install_hint: "pkg install -y patchelf-glibc"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: []
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_mimocode_checkpoint"
MIMO_DIR="$HOME/.local/share/mimocode"
MIMO_WRAPPER="$TERMUX_PREFIX/bin/mimo"
GLIBC_LD="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PATCHELF="$TERMUX_PREFIX/glibc/bin/patchelf"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^mimocode_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "mimocode_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^mimocode_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  local v=""
  command -v mimo &>/dev/null && v=$(mimo --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$v" ] && [ -x "$MIMO_DIR/mimo" ] && v=$("$MIMO_DIR/mimo" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  echo "$v"
}

# ── Método 1 (preferido) — binario nativo del fork propio (MiMoCode-Termux) ──
# Mismo patrón ya usado en freebuff.sh (bun-termux), codebuff.sh y codegraph.sh:
# se descarga el .deb REAL armado para Termux desde el fork propio del proyecto
# (o su upstream Hope2333 si el fork todavía no tiene el asset adjunto a su
# release) y se copia el árbol usr/ completo. Confirmado 2026-08-28 descargando
# y parseando el .deb real (Hope2333/MiMoCode-Termux, Push260803): el binario
# real (usr/lib/mimocode/runtime/mimocode) es BIONIC NATIVO PURO — ELF
# interpreter "/system/bin/linker64" — a diferencia del MÉTODO 2 (más abajo),
# NO necesita glibc ni patchelf en absoluto.
MIMOCODE_FORK_SOURCE_REPO=""
_mimocode_download_native_fork() {
  command -v dpkg-deb &>/dev/null || { warn "dpkg-deb no disponible — no se puede extraer el .deb del fork nativo de mimocode"; return 1; }
  command -v curl &>/dev/null || return 1

  local _repo _releases_json _asset_url=""
  for _repo in "Honkonx/MiMoCode-Termux" "Hope2333/MiMoCode-Termux"; do
    _releases_json=$(curl -fsSL "https://api.github.com/repos/${_repo}/releases?per_page=5" 2>/dev/null)
    [ -z "$_releases_json" ] && continue
    _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*_aarch64\.deb"' | \
      head -1 | grep -o 'https://[^"]*')
    if [ -n "$_asset_url" ]; then MIMOCODE_FORK_SOURCE_REPO="$_repo"; break; fi
  done
  if [ -z "$_asset_url" ]; then
    warn "No se encontró un asset .deb aarch64 del fork nativo de mimocode (ni en Honkonx/MiMoCode-Termux ni en el upstream Hope2333/MiMoCode-Termux)"
    return 1
  fi

  local _tmp="$HOME/tmp/mimocode_fork_$$"
  local _deb="$_tmp/$(basename "$_asset_url")"
  mkdir -p "$_tmp"
  info "Descargando MiMo Code nativo del fork ($(basename "$_asset_url"), repo $MIMOCODE_FORK_SOURCE_REPO)..."
  curl -fsSL "$_asset_url" -o "$_deb" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  local _extract="$_tmp/extract"
  mkdir -p "$_extract"
  dpkg-deb -x "$_deb" "$_extract" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  # Mismo bug/fix real ya confirmado en freebuff.sh/codebuff.sh/codegraph.sh
  # (docs/humano281.md): dpkg-deb -x deja el árbol bajo la RUTA ABSOLUTA COMPLETA
  # "$_extract/data/data/com.termux/files/usr/..." (así empaqueta Termux sus .deb),
  # no bajo "$_extract/usr/" — copiar el árbol usr/ COMPLETO preserva la relación
  # wrapper→runtime intacta (acá: usr/bin/mimo invoca usr/lib/mimocode/runtime/mimocode).
  local _extract_usr="$_extract/data/data/com.termux/files/usr"
  [ -d "$_extract_usr" ] || _extract_usr="$_extract/usr"
  if [ ! -d "$_extract_usr" ]; then
    warn "El .deb del fork nativo de mimocode no tiene ningún layout usr/ reconocible tras la extracción"
    rm -rf "$_tmp"
    return 1
  fi
  cp -a "$_extract_usr/." "$TERMUX_PREFIX/" || {
    warn "No se pudo copiar el árbol usr/ del .deb del fork nativo de mimocode a $TERMUX_PREFIX"
    rm -rf "$_tmp"
    return 1
  }
  chmod +x "$TERMUX_PREFIX/bin/mimo" 2>/dev/null
  [ -f "$TERMUX_PREFIX/lib/mimocode/runtime/mimocode" ] && chmod +x "$TERMUX_PREFIX/lib/mimocode/runtime/mimocode" 2>/dev/null
  rm -rf "$_tmp"

  # Verificación FUNCIONAL real — mismo criterio ya usado en
  # _mimocode_download_native() (Método 2, más abajo).
  "$TERMUX_PREFIX/bin/mimo" --version >/dev/null 2>&1 || {
    warn "MiMo Code nativo del fork instalado pero no responde a --version"
    return 1
  }
  return 0
}

# Descarga el binario nativo ARM64 + parchea con patchelf (patrón core-termux,
# mismo mecanismo de descubrimiento en vivo que freebuff.sh — no hardcodear
# nombres de tag/asset/binario, ya cambiaron de forma inesperada en otros
# módulos de este mismo patrón). Usa /releases (no /releases/latest) por si
# XiaomiMiMo llegara a marcar una release como prerelease en el futuro.
_mimocode_download_native() {
  info "Consultando releases reales (GitHub API XiaomiMiMo/MiMo-Code)..."
  local releases_json
  releases_json=$(curl -fsSL "https://api.github.com/repos/XiaomiMiMo/MiMo-Code/releases?per_page=5" 2>/dev/null)
  [ -z "$releases_json" ] && return 1

  local latest url
  latest=$(echo "$releases_json" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -z "$latest" ] && return 1
  MIMO_LATEST="$latest"

  url=$(echo "$releases_json" | grep -o '"browser_download_url": *"[^"]*linux-arm64\.tar\.gz"' | \
    head -1 | grep -o 'https://[^"]*')
  [ -z "$url" ] && return 1

  local tarball; tarball=$(basename "$url")
  local tmp="$HOME/tmp/mimocode_${latest//\//_}_$$"
  mkdir -p "$tmp" "$MIMO_DIR"

  info "Descargando binario nativo ARM64 ($tarball, release $latest)..."
  curl -fsSL "$url" -o "$tmp/$tarball" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  tar -zxf "$tmp/$tarball" -C "$MIMO_DIR" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  rm -rf "$tmp"

  # No asumir el nombre del binario extraído — tomar el único archivo
  # ejecutable real del directorio de destino (mismo criterio que freebuff.sh).
  local _real_bin
  _real_bin=$(find "$MIMO_DIR" -maxdepth 1 -type f ! -name "*.tar.gz" | head -1)
  [ -z "$_real_bin" ] && return 1
  chmod +x "$_real_bin"
  MIMO_REAL_BIN="$_real_bin"

  # patchelf: el binario oficial está linkeado contra el loader glibc de
  # Linux — apuntar el ELF interpreter al glibc de Termux (mismo patrón que
  # antigravity/claude/openclaw/cursor/freebuff).
  if [ -f "$GLIBC_LD" ]; then
    info "Aplicando patchelf al binario nativo..."
    if [ -x "$PATCHELF" ]; then
      "$PATCHELF" --set-interpreter "$GLIBC_LD" "$_real_bin" 2>/dev/null || \
        warn "patchelf falló — el binario puede requerir ajuste manual"
    else
      warn "patchelf no encontrado en $PATCHELF — el binario puede requerir ajuste manual"
    fi
  fi

  # Wrapper en $PREFIX/bin (igual que claude/freebuff) — apunta al binario
  # real detectado.
  cat > "$MIMO_WRAPPER" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec "$_real_bin" "\$@"
WRAPPER
  chmod +x "$MIMO_WRAPPER"

  # Verificación FUNCIONAL real, no solo "los pasos no tiraron error".
  "$MIMO_WRAPPER" --version >/dev/null 2>&1 || {
    warn "Binario nativo instalado pero no responde a --version"
    return 1
  }
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ MIMO CODE — Instalador                 ║"
  echo "  ║  Xiaomi · Termux ARM64                    ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "MiMo Code ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar MiMo Code? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Capa de compatibilidad glibc (Método ORIGINAL, primario) ─
# Orden corregido 2026-08-28 (docs/humano281.md, corrección explícita del usuario: "deben
# quedar con el método que teníamos y los repos como el mío o de hoppe son de respaldo, todo
# es respaldo no remplazo") — el fork propio (Honkonx/MiMoCode-Termux, hoy sin releases
# propios, cae a Hope2333/MiMoCode-Termux) queda como RESPALDO real, tentado solo si el
# método original (glibc + patchelf, el que ya se usaba antes de esta ronda) falla — nunca
# al revés.
_MIMOCODE_FORK=false
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

# ── PASO 2 — Binario nativo ARM64 (Método original, primario) ─
step "PASO 2 — Instalando MiMo Code nativo ARM64 (glibc + patchelf)"
if check_done "native_install"; then
  log "MiMo Code nativo ya instalado [checkpoint]"
else
  if _mimocode_download_native; then
    log "MiMo Code nativo ARM64 instalado"
    mark_done "native_install"
  else
    warn "Falló el método original (glibc + patchelf) — probando el fork de respaldo (MiMoCode-Termux)..."
  fi
fi

# ── PASO 3 — Fork nativo (RESPALDO, solo si el método original falló) ─
if ! check_done "native_install"; then
step "PASO 3 — Binario nativo del fork de respaldo (MiMoCode-Termux)"
if check_done "fork_install"; then
  log "MiMo Code (fork de respaldo) ya instalado [checkpoint]"
  _MIMOCODE_FORK=true
else
  _mimocode_download_native_fork || \
    error "No se pudo instalar MiMo Code (ni el método original glibc+patchelf, ni el fork de respaldo ${MIMOCODE_FORK_SOURCE_REPO:-MiMoCode-Termux})"
  log "MiMo Code instalado vía el fork de respaldo (${MIMOCODE_FORK_SOURCE_REPO}) — sin glibc, Bionic puro"
  mark_done "fork_install"
  _MIMOCODE_FORK=true
fi
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_CHANNEL="native_arm64"
$_MIMOCODE_FORK && _CHANNEL="fork_native_bionic"
_DATE=$(date +%Y-%m-%d)
registry_write mimocode \
  "installed=true" \
  "version=${_VER_FINAL:-${MIMO_LATEST:-?}}" \
  "channel=${_CHANNEL}" \
  "install_date=${_DATE}"

notify_event "mimocode" "install_done" "$_VER_FINAL"
log "MiMo Code instalado correctamente (v${_VER_FINAL:-${MIMO_LATEST:-?}}) — ejecutá 'mimo' para configurar autenticación"
rm -f "$CHECKPOINT"
exit 0
