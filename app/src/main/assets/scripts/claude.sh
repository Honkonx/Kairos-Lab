#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · claude.sh (silent mode)
#  Instala Claude Code en Termux ARM64 (sin root)
#
#  USO DESDE APP (KairosApp):
#    bash claude.sh --silent
#    bash claude.sh --silent --variant native --source github
#    bash claude.sh --silent --variant native --version latest
#
#  USO MANUAL (standalone):
#    bash install_claude.sh
#
#  FLAGS:
#    --silent              Sin preguntas, instala todo directo
#    --force               Reinstala aunque ya esté
#    --variant <tipo>      native (ELF glibc, default y única recomendada) |
#                           legacy (npm — SOLO para desinstalar una instalación
#                           vieja ya existente, ver comentario en
#                           _install_legacy_clean(): npm no puede instalar
#                           nada más nuevo que @2.1.111 en Termux desde que
#                           Anthropic pasó a distribuir binarios glibc sin
#                           parchear vía npm, docs/humano274.md)
#    --source <fuente>     clean (default) | github (restore backup)
#    --version latest      Usar versión más reciente (solo native, default en
#                           modo silencioso — ver "$SILENT && USE_LATEST=true")
#
#  VERSIÓN: 4.2.0 | Agosto 2026 — deprecado "legacy" como opción real de
#  instalación (docs/humano274.md), modules.json ya no la ofrece. Fallback
#  real: si la descarga/verificación de la versión "latest" falla, reintenta
#  con el pin viejo conocido-bueno (variante native, no legacy) en vez de
#  abortar (docs/humano275.md).
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$HOME/.local/bin:$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
INSTALL_MODE=""
INSTALL_SOURCE="clean"
USE_LATEST=false

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --variant)  shift; INSTALL_MODE="$1" ;;
    --source)   shift; INSTALL_SOURCE="$1" ;;
    --version)  shift; [ "$1" = "latest" ] && USE_LATEST=true ;;
  esac
  shift
done

# En modo silent (uso desde la app) nadie pasa --version latest explícitamente,
# así que sin esto siempre cae al pin viejo (CLAUDE_VERSION_NATIVE) aunque exista
# una versión más reciente ya verificada por SHA256 contra el manifest real —
# ver auditoría docs/referencias/AUDITORIA_CATEGORIA_AGENTES_IA.md.
# Solo aplica a variant=native (_install_native_clean es el único lugar que lee
# USE_LATEST); legacy sigue con su pin de npm por las 4 estrategias de fallback.
$SILENT && USE_LATEST=true

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"claude","supports_silent":true,"supports_force":true,"variants":["native"],"variant_aliases":{"native":["elf","glibc"]},"variant_required":false,"variant_default":"native"}
JSON
  exit 0
fi

case "$INSTALL_MODE" in
  native|elf|glibc)  INSTALL_MODE="native" ;;
  legacy|npm|node)   INSTALL_MODE="legacy" ;;
  "")
    # Bug real evitado (2026-08-27, docs/humano274.md): modules.json ya no
    # ofrece "legacy" como variante elegible (hasVariants:false, un solo
    # installMethod) — BottomSheetInstalacion.kt manda variant=null en ese
    # caso, así que "--variant" nunca llega desde la app en modo silencioso.
    # Antes esto abortaba SIEMPRE con "Falta --variant" — ahora, sin variante
    # explícita, se asume "native" (la única real y recomendada) en vez de
    # exigirle al caller un flag que la propia app ya no puede mandar.
    if [ -z "$INSTALL_MODE" ]; then
      INSTALL_MODE="native"
    fi
    ;;
esac

# ── Constantes ────────────────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_claude_checkpoint"
CLAUDE_VERSION_LEGACY="2.1.111"

# Pin de respaldo, SOLO usado si la consulta real a "latest" falla (ver
# _install_native_clean() más abajo) — 2.1.152, pedido explícito del usuario
# (docs/humano274.md, 2026-08-27): versión confirmada estable en uso real,
# preferida sobre refrescar el pin a ciegas a cada rato con la última que
# devuelva la API (que puede no estar tan probada todavía).
CLAUDE_VERSION_NATIVE="2.1.152"
NATIVE_BINARY="$HOME/.local/share/claude-code/claude"
NATIVE_WRAPPER="$HOME/.local/bin/claude"
LEGACY_WRAPPER="$TERMUX_PREFIX/bin/claude"
GLIBC_LD="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PATCHELF="$TERMUX_PREFIX/glibc/bin/patchelf"
RELEASE_API="https://api.github.com/repos/Honkonx/termux-ai-stack/releases/latest"
BASHRC="$HOME/.bashrc"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# ── Detección ─────────────────────────────────────────────────
_detect_method() {
  if [ -f "$NATIVE_BINARY" ] && [ -x "$NATIVE_BINARY" ]; then
    echo "native"; return
  fi
  local npm_root; npm_root=$(npm root -g 2>/dev/null)
  if [ -f "${npm_root}/@anthropic-ai/claude-code/cli.js" ]; then
    echo "legacy"; return
  fi
  if [ -f "$LEGACY_WRAPPER" ]; then
    echo "broken"; return
  fi
  echo "none"
}

_detect_version() {
  local method="$1"
  case "$method" in
    native) "$NATIVE_WRAPPER" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 ;;
    legacy)
      local npm_root; npm_root=$(npm root -g 2>/dev/null)
      node "${npm_root}/@anthropic-ai/claude-code/cli.js" --version 2>/dev/null \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 ;;
  esac
}

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Reemplaza el manifest a mano modulos/manifests/claude.json (borrado como
# código muerto en humano165 — nunca escalaba a los ~55 módulos, ver diseño
# completo en docs/arquitectura/MODULEDEB_GENERICO.md). Contenido migrado
# 1:1 del manifest piloto original (git show 838544d^:modulos/manifests/claude.json)
# — solo cambia DÓNDE vive el JSON, de un archivo estático a la salida de
# este flag. Solo cubre la variante "native" (ver not_covered) — legacy
# (npm) no tiene manifest todavía.
if $DESCRIBE_FILES; then
  _df_variant=$(_detect_method)
  [ "$_df_variant" = "broken" ] || [ "$_df_variant" = "none" ] && _df_variant="null_placeholder"
  jq -n \
    --arg id "claude" \
    --arg pkgname "kairos-module-claude" \
    --arg variant "$_df_variant" \
    --arg p1 "$NATIVE_BINARY" \
    --arg n1 "Binario ELF descargado de downloads.claude.ai, parcheado con patchelf --set-interpreter para correr sobre glibc-runner en vez del linker Bionic nativo (_install_native_clean)" \
    --arg p2 "$NATIVE_WRAPPER" \
    --arg n2 "Wrapper bash: la ruta del binario queda hardcodeada al momento de crearse — por eso el postinst la regenera en vez de asumir que el archivo copiado sigue siendo válido en el device destino" \
    --arg p3 "\$HOME/.claude/settings.json" \
    --arg n3 "env.DISABLE_AUTOUPDATER + env.DISABLE_UPDATES (evita que el binario native se autoactualice y se sobreescriba con un ELF sin parchear) + env.LD_PRELOAD" \
    --arg dep1_check "test -f \"$GLIBC_LD\"" \
    --arg dep1_hint "pkg install -y glibc-repo && pkg install -y glibc-runner patchelf-glibc" \
    --arg dep2_check "test -f \"$PATCHELF\"" \
    --arg dep2_hint "pkg install -y glibc-runner patchelf-glibc" \
    --arg verify "BIN=\"$NATIVE_BINARY\"; [ -x \"\$BIN\" ] && INTERP=\$(\"$PATCHELF\" --print-interpreter \"\$BIN\" 2>/dev/null) && [ \"\$INTERP\" = \"$GLIBC_LD\" ]" \
    --arg patch "BIN=\"$NATIVE_BINARY\"; LD_PRELOAD= \"$PATCHELF\" --set-interpreter \"$GLIBC_LD\" \"\$BIN\" && chmod +x \"\$BIN\"; mkdir -p \"$HOME/.local/bin\"; printf '#!/data/data/com.termux/files/usr/bin/bash\\nunset LD_PRELOAD\\nexec \"%s\" \"\$@\"\\n' \"\$BIN\" > \"$NATIVE_WRAPPER\"; chmod +x \"$NATIVE_WRAPPER\"" \
    '{
      id: $id,
      supports_describe_files: true,
      variant: (if $variant == "null_placeholder" then null else $variant end),
      package_name: $pkgname,
      version_registry_key: "claude.version",
      files: [
        {path: $p1, required: true, note: $n1},
        {path: $p2, required: true, note: $n2},
        {path: $p3, required: false, note: $n3}
      ],
      file_globs: [],
      dependencies: [
        {id: "glibc_ld", check_cmd: $dep1_check, install_hint: $dep1_hint},
        {id: "patchelf", check_cmd: $dep2_check, install_hint: $dep2_hint}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "La variante legacy (npm @anthropic-ai/claude-code) no tiene manifest todavía — este describe-files solo cubre native",
        "No reinstala Node.js/npm ni verifica versión mínima — asume que el device destino ya tiene el mismo Termux base"
      ]
    }'
  exit 0
fi

# ── Desinstalación ────────────────────────────────────────────
_uninstall_native() {
  info "Desinstalando Claude native..."
  rm -rf "$HOME/.local/share/claude-code" 2>/dev/null || true
  rm -f "$NATIVE_WRAPPER" 2>/dev/null || true
  registry_write claude
  rm -f "$CHECKPOINT" 2>/dev/null || true
  log "Claude native desinstalado"
}

_uninstall_legacy() {
  info "Desinstalando Claude legacy..."
  npm uninstall -g @anthropic-ai/claude-code 2>/dev/null || true
  npm cache clean --force 2>/dev/null || true
  local npm_root; npm_root=$(npm root -g 2>/dev/null)
  # Guard: si "npm root -g" falla/devuelve vacío (npm roto/no en PATH en ese
  # instante), sin este guard la ruta quedaría "/@anthropic-ai/claude-code"
  # (raíz absoluta) — el "2>/dev/null || true" ya evitaba que esto abortara el
  # script, pero es más seguro no intentar siquiera un rm -rf sobre una ruta
  # derivada de una variable vacía.
  [ -n "$npm_root" ] && rm -rf "${npm_root}/@anthropic-ai/claude-code" 2>/dev/null || true
  rm -f "$LEGACY_WRAPPER" 2>/dev/null || true
  registry_write claude
  rm -f "$CHECKPOINT" 2>/dev/null || true
  log "Claude legacy desinstalado"
}

# ── Registry ──────────────────────────────────────────────────
_update_registry() {
  local version="$1"
  local method="$2"
  registry_install claude "$version" "method=$method" "location=termux_native"
}

# ── Verificar si ya está instalado ────────────────────────────
INSTALLED_METHOD=$(_detect_method)
INSTALLED_VER=""
[ "$INSTALLED_METHOD" != "none" ] && [ "$INSTALLED_METHOD" != "broken" ] && \
  INSTALLED_VER=$(_detect_version "$INSTALLED_METHOD")

if ! $FORCE; then
  if [ "$INSTALLED_METHOD" = "$INSTALL_MODE" ] || \
     { [ -z "$INSTALL_MODE" ] && [ "$INSTALLED_METHOD" != "none" ] && [ "$INSTALLED_METHOD" != "broken" ]; }; then
    log "Claude Code ($INSTALLED_METHOD) v${INSTALLED_VER} ya instalado"
    # Re-sincroniza el registry aunque no se reinstale nada: la detección de
    # arriba es en vivo (archivos reales), no depende del registry, así que un
    # binario ya funcionando pero con el registry desactualizado o con claves
    # viejas/nunca escritas (ver claude.installed más abajo) queda atascado
    # mostrando "No instalado" en la app para siempre si no se actualiza acá.
    [ -n "$INSTALLED_VER" ] && _update_registry "$INSTALLED_VER" "$INSTALLED_METHOD"
    exit 0
  fi
fi

# Conflicto: método distinto instalado → desinstalar automáticamente en silent
if [ -n "$INSTALL_MODE" ] && [ "$INSTALLED_METHOD" != "none" ] && \
   [ "$INSTALLED_METHOD" != "broken" ] && [ "$INSTALLED_METHOD" != "$INSTALL_MODE" ]; then
  if $SILENT; then
    [ "$INSTALLED_METHOD" = "native" ] && _uninstall_native || _uninstall_legacy
  fi
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: menú + confirmación ─────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Claude Code Installer   ║
  ║   ARM64 · sin root · v4.0.0               ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"

  case "$INSTALLED_METHOD" in
    native) echo -e "  Estado: ${GREEN}native v${INSTALLED_VER} ✓${NC}" ;;
    legacy) echo -e "  Estado: ${GREEN}legacy v${INSTALLED_VER} ✓${NC}" ;;
    broken) echo -e "  Estado: ${RED}instalación rota${NC}" ;;
    none)   echo -e "  Estado: ${YELLOW}no instalado${NC}" ;;
  esac
  echo ""

  if [ -z "$INSTALL_MODE" ]; then
    echo -e "  ${GREEN}[1]${NC} Native v${CLAUDE_VERSION_NATIVE}+ ${CYAN}(recomendado)${NC}"
    echo -e "      ${DIM}binario ELF + glibc-runner${NC}"
    echo -e "  ${GREEN}[2]${NC} Legacy v${CLAUDE_VERSION_LEGACY}"
    echo -e "      ${DIM}npm + Node.js, sin deps extra${NC}"
    echo ""
    echo -n "  Opción [1]: "
    read -r OPT_METHOD < /dev/tty
    case "${OPT_METHOD:-1}" in
      2) INSTALL_MODE="legacy" ;;
      *) INSTALL_MODE="native" ;;
    esac
  fi

  # Conflicto manual
  if [ "$INSTALLED_METHOD" != "none" ] && [ "$INSTALLED_METHOD" != "broken" ] && \
     [ "$INSTALLED_METHOD" != "$INSTALL_MODE" ]; then
    echo ""
    echo -e "  ${YELLOW}Conflicto: $INSTALLED_METHOD instalado, elegiste $INSTALL_MODE${NC}"
    echo -n "  ¿Desinstalar $INSTALLED_METHOD para continuar? (s/n): "
    read -r _CONF < /dev/tty
    [ "$_CONF" != "s" ] && [ "$_CONF" != "S" ] && { echo "Cancelado."; exit 0; }
    [ "$INSTALLED_METHOD" = "native" ] && _uninstall_native || _uninstall_legacy
  fi

  # Fuente
  echo ""
  echo "  [1] Instalación limpia (descarga desde cero)"
  echo "  [2] Desde GitHub Releases (restore backup)"
  echo -n "  Opción [1]: "
  read -r OPT_SRC < /dev/tty
  case "${OPT_SRC:-1}" in
    2) INSTALL_SOURCE="github" ;;
    *) INSTALL_SOURCE="clean" ;;
  esac

  echo ""
  echo "  Variante: $INSTALL_MODE | Fuente: $INSTALL_SOURCE"
  echo -n "  ¿Continuar? (s/n): "
  read -r _CONF2 < /dev/tty
  [ "$_CONF2" != "s" ] && [ "$_CONF2" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ════════════════════════════════════════════════════════════
#  DEPENDENCIAS COMUNES
# ════════════════════════════════════════════════════════════
_ensure_termux() {
  if check_done "termux_update" || [ -n "$ANDROID_SERVER_READY" ]; then
    log "Termux preparado [skip]"; return
  fi
  info "Actualizando Termux..."
  pkg update -y -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>&1 | tail -3
  mark_done "termux_update"
}

_ensure_nodejs() {
  check_done "nodejs" && { log "Node.js verificado [checkpoint]"; return; }
  if command -v node &>/dev/null; then
    local ver; ver=$(node --version 2>/dev/null | sed 's/v//' | cut -d'.' -f1)
    if [ "${ver:-0}" -ge 18 ] 2>/dev/null; then
      log "Node.js $(node --version) ✓"; mark_done "nodejs"; return
    fi
  fi
  info "Instalando Node.js..."
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  pkg install nodejs-lts -y -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" || error "Error instalando nodejs-lts"
  # Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): "npm install -g
  # npm" sobreescribe el npm parcheado para Termux (shebang sin /usr/bin/env, que acá
  # no existe) con uno genérico del registry — "bad interpreter" en cualquier npm
  # posterior. El npm que trae nodejs-lts ya alcanza.
  log "Node.js $(node --version)"
  mark_done "nodejs"
}

_ensure_glibc() {
  check_done "glibc_deps" && { log "glibc-runner verificado [checkpoint]"; return; }
  local NEED_INSTALL=false
  [ ! -f "$GLIBC_LD" ] && NEED_INSTALL=true
  [ ! -f "$PATCHELF" ] && NEED_INSTALL=true
  if $NEED_INSTALL; then
    info "Instalando glibc-runner + patchelf-glibc..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc-repo 2>/dev/null || true
    pkg update -y -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>&1 | tail -2
    pkg_update_with_fallback
    pkg install -y glibc-runner patchelf-glibc jq \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" || error "No se pudo instalar glibc-runner"
  fi
  [ -f "$GLIBC_LD" ] || error "ld.so no encontrado"
  [ -f "$PATCHELF" ] || error "patchelf no encontrado"
  if ! grep -q '\.local/bin' "$BASHRC" 2>/dev/null; then
    echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$BASHRC"
    export PATH="$HOME/.local/bin:$PATH"
  fi
  log "glibc-runner listo"
  mark_done "glibc_deps"
}

# ════════════════════════════════════════════════════════════
#  HELPERS
# ════════════════════════════════════════════════════════════
_find_legacy_cli() {
  local wrapper="$LEGACY_WRAPPER"
  if [ -f "$wrapper" ]; then
    local p; p=$(grep "node " "$wrapper" 2>/dev/null | grep "cli\.js" | \
      grep -oE '/[^ "]+cli\.js' | head -1)
    [ -n "$p" ] && [ -f "$p" ] && { echo "$p"; return; }
  fi
  local npm_root; npm_root=$(npm root -g 2>/dev/null)
  local known=(
    "$npm_root/@anthropic-ai/claude-code/cli.js"
    "$TERMUX_PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js"
    "$HOME/.npm-global/lib/node_modules/@anthropic-ai/claude-code/cli.js"
  )
  for p in "${known[@]}"; do
    [ -f "$p" ] && { echo "$p"; return; }
  done
  echo "${npm_root}/@anthropic-ai/claude-code/cli.js"
}

_validate_legacy_cli() {
  local p="$1"
  [ -f "$p" ] && [ -s "$p" ] || return 1
  node "$p" --version 2>&1 | grep -qv "SyntaxError\|not found\|No such" || return 1
  local first; first=$(head -1 "$p" 2>/dev/null)
  echo "$first" | grep -q "^#!/.*bash" && return 1
  return 0
}

# ════════════════════════════════════════════════════════════
#  INSTALACIÓN NATIVE — limpia
# ════════════════════════════════════════════════════════════
# Descarga + verifica SHA256 + parchea el binario de una versión concreta.
# Devuelve 0/1 en vez de abortar con error() — así _install_native_clean()
# puede reintentar con el pin viejo conocido-bueno cuando la versión "latest"
# falla, en vez de matar la instalación entera (pedido explícito del usuario,
# docs/humano274.md/humano275.md: "si da error que instale la version vieja").
_download_and_patch_native() {
  local VERSION="$1"
  local DL="https://downloads.claude.ai/claude-code-releases/${VERSION}"
  mkdir -p "$(dirname "$NATIVE_BINARY")" "$(dirname "$NATIVE_WRAPPER")"

  info "Descargando binario v${VERSION}..."
  curl -fL "${DL}/linux-arm64/claude" -o "$NATIVE_BINARY" || {
    warn "Descarga de v${VERSION} fallida"; return 1
  }
  if [ ! -s "$NATIVE_BINARY" ]; then
    warn "Binario v${VERSION} descargado vacío"
    rm -f "$NATIVE_BINARY"
    return 1
  fi

  # Verificar checksum
  info "Verificando SHA256..."
  local expected actual
  expected=$(curl -fsSL "${DL}/manifest.json" 2>/dev/null | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d['platforms']['linux-arm64']['checksum'])" \
    2>/dev/null)
  if [ -n "$expected" ]; then
    actual=$(sha256sum "$NATIVE_BINARY" | cut -d' ' -f1)
    if [ "$actual" = "$expected" ]; then
      log "SHA256 verificado ✓"
    else
      rm -f "$NATIVE_BINARY"
      warn "SHA256 de v${VERSION} no coincide — binario corrupto"
      return 1
    fi
  else
    warn "No se pudo verificar checksum de v${VERSION}"
  fi

  chmod +x "$NATIVE_BINARY"
  info "Parcheando intérprete ELF..."
  if ! LD_PRELOAD= "$PATCHELF" --set-interpreter "$GLIBC_LD" "$NATIVE_BINARY"; then
    warn "patchelf falló para v${VERSION}"
    rm -f "$NATIVE_BINARY"
    return 1
  fi
  log "Intérprete ELF parcheado ✓"
  return 0
}

_install_native_clean() {
  local VERSION="$CLAUDE_VERSION_NATIVE"

  if $USE_LATEST; then
    info "Consultando versión latest..."
    # Bug real confirmado 2026-08-27 (verificado en vivo con curl real): la URL vieja
    # "claude-code-releases/latest/manifest.json" devuelve 404 (NoSuchKey) — el manifest
    # con el campo "version" solo existe POR VERSIÓN CONCRETA
    # ("claude-code-releases/<version>/manifest.json", confirmado 200 OK), no bajo
    # "latest/". El puntero real a la última versión es
    # "claude-code-releases/latest" A SECAS (sin /manifest.json) — devuelve texto plano,
    # solo el número de versión (ej. "2.1.247"), sin JSON. Con el 404 de antes, esta rama
    # SIEMPRE caía al warn+fallback de abajo sin que nadie lo notara — y como
    # "$SILENT && USE_LATEST=true" (línea ~56) hace que TODA instalación silenciosa
    # (uso normal desde la app) pase por acá, el pin viejo ($CLAUDE_VERSION_NATIVE) se
    # instalaba siempre en vez de la versión real más reciente, sin ningún error visible
    # (el warn queda enterrado en el log de instalación).
    VERSION=$(curl -fsSL "https://downloads.claude.ai/claude-code-releases/latest" 2>/dev/null | tr -d '[:space:]')
    echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || VERSION=""
    [ -z "$VERSION" ] && { warn "No se pudo obtener latest — usando v${CLAUDE_VERSION_NATIVE}"; VERSION="$CLAUDE_VERSION_NATIVE"; }
    info "Versión: v${VERSION}"
  fi

  # Fallback real (pedido explícito del usuario, docs/humano275.md): el método
  # "nuevo" (VERSION resuelta arriba, típicamente "latest") se intenta primero;
  # si la descarga/checksum/patchelf falla por CUALQUIER motivo (release nueva
  # todavía sin binario linux-arm64 publicado, CDN caído, corrupción de red),
  # se reintenta con la versión vieja conocida-buena ($CLAUDE_VERSION_NATIVE,
  # 2.1.152) en vez de abortar la instalación entera con error() — aplica
  # igual en foreground o background porque ambos corren este mismo script vía
  # ProcessBuilder (ModuleController.kt), no hay lógica de UI de por medio.
  # NO es la variante "legacy" (npm, deprecada — ver comentario grande sobre
  # _install_legacy_clean() más abajo) — sigue siendo la variante native con
  # patchelf, solo con un número de versión distinto.
  if ! _download_and_patch_native "$VERSION"; then
    if [ "$VERSION" != "$CLAUDE_VERSION_NATIVE" ]; then
      warn "v${VERSION} falló — reintentando con la versión vieja conocida-buena v${CLAUDE_VERSION_NATIVE}"
      VERSION="$CLAUDE_VERSION_NATIVE"
      _download_and_patch_native "$VERSION" || error "Instalación fallida: ni la versión nueva ni v${CLAUDE_VERSION_NATIVE} (respaldo) pudieron instalarse"
    else
      error "Instalación fallida: v${CLAUDE_VERSION_NATIVE} (respaldo) no pudo instalarse"
    fi
  fi

  # Wrapper
  cat > "$NATIVE_WRAPPER" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
exec "$NATIVE_BINARY" "\$@"
WRAPPER
  chmod +x "$NATIVE_WRAPPER"
  log "Wrapper creado"

  # Settings — "autoUpdates": false NO es una key real (no existe en el schema
  # de Claude Code, ver code.claude.com/docs/en/settings — la key real es
  # "autoUpdatesChannel", y la forma documentada de desactivar del todo es
  # DISABLE_AUTOUPDATER dentro de "env"; confirmado además por los issues
  # anthropics/claude-code#10079 y #56723 sobre esta misma confusión). Sin
  # DISABLE_AUTOUPDATER + DISABLE_UPDATES el binario native (parcheado con
  # patchelf para correr vía glibc-runner) puede autoactualizarse y
  # sobreescribirse con un ELF sin parchear que ya no corre en Bionic/Termux
  # — se rompe en un uso posterior, no en la instalación, así que el
  # ver_check de más abajo no lo detecta.
  mkdir -p "$HOME/.claude"
  cat > "$HOME/.claude/settings.json" << 'SETTINGS'
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1",
    "LD_PRELOAD": "/data/data/com.termux/files/usr/lib/libtermux-exec-ld-preload.so"
  }
}
SETTINGS
  log "settings.json configurado"

  _update_registry "$VERSION" "native"
  mark_done "claude_install"

  local ver_check
  ver_check=$("$NATIVE_WRAPPER" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -n "$ver_check" ] && log "Claude native v${ver_check} funcionando ✓" || \
    warn "Wrapper creado pero --version no respondió"
}

# ════════════════════════════════════════════════════════════
#  INSTALACIÓN NATIVE — desde GitHub Releases
# ════════════════════════════════════════════════════════════
_install_native_github() {
  if [ ! -f "$HOME/restore.sh" ] || [ ! -s "$HOME/restore.sh" ]; then
    info "Descargando restore.sh..."
    curl -fsSL "https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/restore.sh" \
      -o "$HOME/restore.sh" && chmod +x "$HOME/restore.sh" || \
      error "No se pudo obtener restore.sh"
  fi

  bash "$HOME/restore.sh" --module claude-native --source github || \
    error "Restore de Claude native falló"

  local ver_check
  ver_check=$("$NATIVE_WRAPPER" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -n "$ver_check" ] && log "Claude native v${ver_check} restaurado ✓" || \
    warn "Restaurado pero --version no respondió"
}

# ════════════════════════════════════════════════════════════
#  INSTALACIÓN LEGACY — limpia (npm multi-estrategia)
#
#  LIMITACIÓN REAL CONFIRMADA (2026-08-27, docs/humano274.md): Anthropic
#  cambió el modelo de distribución del paquete npm @anthropic-ai/claude-code
#  a partir de una versión posterior a 2.1.111 — pasó de un CLI en JS puro
#  (bin: cli.js, corrido con node) a un binario nativo compilado por
#  plataforma, distribuido como optionalDependencies
#  (@anthropic-ai/claude-code-linux-arm64, etc., bin: bin/claude.exe),
#  instalado por su propio postinstall (install.cjs) sin ningún patch para
#  Bionic/Android. Confirmado leyendo el package.json real de npm: 2.1.111
#  todavía tenía "bin":{"claude":"cli.js"}; 2.1.250 ya tiene
#  "bin":{"claude":"bin/claude.exe"} y "optionalDependencies" con binarios
#  reales por plataforma. Ese binario está compilado para glibc estándar, NO
#  para Bionic — corre en Termux exactamente igual de mal que el binario
#  nativo SIN el patchelf que sí aplica la variante "native" de este mismo
#  script. Por eso "legacy" queda pineado para siempre en 2.1.111 (la última
#  versión que sigue siendo JS puro): no hay forma de que npm instale una
#  versión más nueva y funcional en Termux sin reimplementar el mismo patch
#  glibc que "native" ya hace correctamente. "native" es la vía real y
#  recomendada — el catálogo de la app (modules.json) ya no ofrece "legacy"
#  como opción, este código se conserva solo para desinstalar limpio una
#  instalación legacy que ya exista en el dispositivo.
# ════════════════════════════════════════════════════════════
_install_legacy_clean() {
  warn "Variante legacy: npm no puede instalar nada más nuevo que v${CLAUDE_VERSION_LEGACY} en Termux (Anthropic distribuye versiones más nuevas como binario glibc sin parchear, incompatible con Bionic) — se recomienda la variante 'native' en su lugar."
  local NPM_ROOT; NPM_ROOT=$(npm root -g 2>/dev/null)
  local CLAUDE_DIR="$NPM_ROOT/@anthropic-ai/claude-code"
  local CLAUDE_OK=false
  local CLI_PATH

  info "Limpiando instalación anterior..."
  npm uninstall -g @anthropic-ai/claude-code 2>/dev/null || true
  npm cache clean --force 2>/dev/null || true
  # Guard: mismo riesgo que _uninstall_legacy() más arriba — si "npm root -g"
  # devolvió vacío, CLAUDE_DIR sería "/@anthropic-ai/claude-code" (ruta
  # absoluta desde raíz). El "2>/dev/null || true" ya evitaba abortar el
  # script, pero evitamos directamente intentar el rm -rf en ese caso.
  [ -n "$NPM_ROOT" ] && rm -rf "$CLAUDE_DIR" 2>/dev/null || true

  # Estrategia 1: npm directo
  info "Estrategia 1: npm install @${CLAUDE_VERSION_LEGACY}..."
  npm install -g @anthropic-ai/claude-code@${CLAUDE_VERSION_LEGACY} --save-exact \
    2>&1 | tail -5
  CLI_PATH=$(_find_legacy_cli)
  _validate_legacy_cli "$CLI_PATH" && { CLAUDE_OK=true; log "Estrategia 1 ✓"; }

  # Estrategia 2: npm --ignore-scripts
  if [ "$CLAUDE_OK" = "false" ]; then
    warn "Estrategia 2: npm --ignore-scripts..."
    npm uninstall -g @anthropic-ai/claude-code 2>/dev/null || true
    npm cache clean --force 2>/dev/null || true
    npm install -g @anthropic-ai/claude-code@${CLAUDE_VERSION_LEGACY} \
      --ignore-scripts --save-exact 2>&1 | tail -5
    CLI_PATH=$(_find_legacy_cli)
    _validate_legacy_cli "$CLI_PATH" && { CLAUDE_OK=true; log "Estrategia 2 ✓"; }
  fi

  # Estrategia 3: tarball directo
  if [ "$CLAUDE_OK" = "false" ]; then
    warn "Estrategia 3: tarball desde registry.npmjs.org..."
    local URL="https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-${CLAUDE_VERSION_LEGACY}.tgz"
    local TMP_TGZ="$HOME/claude_npm_direct.tgz"
    local TMP_EXT="$HOME/claude_extract_direct"
    curl -fL "$URL" -o "$TMP_TGZ" 2>/dev/null
    if [ -s "$TMP_TGZ" ]; then
      mkdir -p "$TMP_EXT"
      tar -xzf "$TMP_TGZ" -C "$TMP_EXT" 2>/dev/null
      if [ -f "$TMP_EXT/package/cli.js" ]; then
        mkdir -p "$CLAUDE_DIR"
        cp -r "$TMP_EXT/package/." "$CLAUDE_DIR/"
        CLI_PATH=$(_find_legacy_cli)
        _validate_legacy_cli "$CLI_PATH" && { CLAUDE_OK=true; log "Estrategia 3 ✓"; }
      fi
    fi
    rm -rf "$TMP_EXT" "$TMP_TGZ" 2>/dev/null || true
  fi

  # Estrategia 4: reparar cli.js desde GitHub Releases
  if [ "$CLAUDE_OK" = "false" ] && [ -d "$CLAUDE_DIR" ]; then
    warn "Estrategia 4: cli.js desde GitHub Releases..."
    local CLI_URL
    CLI_URL=$(curl -fsSL "$RELEASE_API" 2>/dev/null | \
      grep -o '"browser_download_url": *"[^"]*part2-claude[^"]*"' | \
      grep -o 'https://[^"]*' | head -1)
    if [ -n "$CLI_URL" ]; then
      local TMP_TAR="$HOME/claude_gh_release.tar.xz"
      local TMP_EXT2="$HOME/claude_extract_gh"
      curl -fL "$CLI_URL" -o "$TMP_TAR" 2>/dev/null
      if [ -s "$TMP_TAR" ]; then
        mkdir -p "$TMP_EXT2"
        tar -xJf "$TMP_TAR" -C "$TMP_EXT2" 2>/dev/null
        local CLI_GH="$TMP_EXT2/npm_modules/@anthropic-ai/claude-code/cli.js"
        if [ -f "$CLI_GH" ]; then
          cp "$CLI_GH" "$CLAUDE_DIR/cli.js" && chmod +x "$CLAUDE_DIR/cli.js"
          CLI_PATH=$(_find_legacy_cli)
          _validate_legacy_cli "$CLI_PATH" && { CLAUDE_OK=true; log "Estrategia 4 ✓"; }
        fi
      fi
      rm -rf "$TMP_EXT2" "$TMP_TAR" 2>/dev/null || true
    fi
  fi

  [ "$CLAUDE_OK" = "false" ] && \
    error "Ninguna estrategia funcionó"

  # Wrapper + settings
  local version
  version=$(node "$CLI_PATH" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$version" ] && version="$CLAUDE_VERSION_LEGACY"

  cat > "$LEGACY_WRAPPER" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
export DISABLE_AUTOUPDATER=1
export DISABLE_UPDATES=1
exec node "${CLI_PATH}" "\$@"
WRAPPER
  chmod +x "$LEGACY_WRAPPER"
  log "Wrapper creado"

  # "autoUpdates" no es una key real del schema (code.claude.com/docs/en/settings)
  # — se quita para no confundir; DISABLE_AUTOUPDATER/DISABLE_UPDATES en "env"
  # es la forma documentada real de desactivar auto-update.
  mkdir -p "$HOME/.claude"
  cat > "$HOME/.claude/settings.json" << 'SETTINGS'
{
  "env": {
    "DISABLE_AUTOUPDATER": "1",
    "DISABLE_UPDATES": "1"
  }
}
SETTINGS

  # Aliases legacy
  grep -v "alias claude=\|alias claude-update=\|alias claude-check=\|# Claude Code" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"
  cat >> "$BASHRC" << ALIASES

# ════════════════════════════════
#  Claude Code legacy · aliases
# ════════════════════════════════
alias claude='DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1 node ${CLI_PATH}'
alias claude-check='node ${CLI_PATH} --version 2>/dev/null && echo "OK" || echo "ERROR"'
ALIASES

  _update_registry "$version" "legacy"
  mark_done "claude_install"
  log "Claude legacy v${version} instalado ✓"
}

# ════════════════════════════════════════════════════════════
#  INSTALACIÓN LEGACY — desde GitHub Releases
# ════════════════════════════════════════════════════════════
_install_legacy_github() {
  if [ ! -f "$HOME/restore.sh" ] || [ ! -s "$HOME/restore.sh" ]; then
    info "Descargando restore.sh..."
    curl -fsSL "https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/restore.sh" \
      -o "$HOME/restore.sh" && chmod +x "$HOME/restore.sh" || \
      error "No se pudo obtener restore.sh"
  fi

  bash "$HOME/restore.sh" --module claude --source github || \
    error "Restore de Claude legacy falló"

  local cli_path; cli_path=$(_find_legacy_cli)
  _validate_legacy_cli "$cli_path" || error "cli.js del backup no es válido"

  local version
  version=$(node "$cli_path" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$version" ] && version="$CLAUDE_VERSION_LEGACY"

  cat > "$LEGACY_WRAPPER" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
export DISABLE_AUTOUPDATER=1
export DISABLE_UPDATES=1
exec node "${cli_path}" "\$@"
WRAPPER
  chmod +x "$LEGACY_WRAPPER"

  # "autoUpdates" no es una key real del schema (code.claude.com/docs/en/settings)
  # — se quita para no confundir; DISABLE_AUTOUPDATER/DISABLE_UPDATES en "env"
  # es la forma documentada real de desactivar auto-update.
  mkdir -p "$HOME/.claude"
  cat > "$HOME/.claude/settings.json" << 'SETTINGS'
{
  "env": { "DISABLE_AUTOUPDATER": "1", "DISABLE_UPDATES": "1" }
}
SETTINGS

  _update_registry "$version" "legacy"
  mark_done "claude_install"
  log "Claude legacy v${version} restaurado ✓"
}

# ════════════════════════════════════════════════════════════
#  MAIN — Ejecutar según método + fuente
# ════════════════════════════════════════════════════════════

if [ "$INSTALL_MODE" = "native" ]; then
  TOTAL_STEPS=4
  step "1/$TOTAL_STEPS Preparando Termux"
  _ensure_termux

  step "2/$TOTAL_STEPS Instalando glibc-runner"
  _ensure_glibc

  step "3/$TOTAL_STEPS Instalando Claude Code (native, $INSTALL_SOURCE)"
  case "$INSTALL_SOURCE" in
    github) _install_native_github ;;
    *)      _install_native_clean ;;
  esac

  step "4/$TOTAL_STEPS Finalizando"
  rm -f "$CHECKPOINT"

elif [ "$INSTALL_MODE" = "legacy" ]; then
  TOTAL_STEPS=4
  step "1/$TOTAL_STEPS Preparando Termux"
  _ensure_termux

  step "2/$TOTAL_STEPS Instalando Node.js"
  _ensure_nodejs

  step "3/$TOTAL_STEPS Instalando Claude Code (legacy, $INSTALL_SOURCE)"
  case "$INSTALL_SOURCE" in
    github) _install_legacy_github ;;
    *)      _install_legacy_clean ;;
  esac

  step "4/$TOTAL_STEPS Finalizando"
  rm -f "$CHECKPOINT"
fi

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  FINAL_VER=$(_detect_version "$INSTALL_MODE")
  [ -z "$FINAL_VER" ] && FINAL_VER="instalado"
  echo ""
  echo -e "${GREEN}${BOLD}  Claude Code instalado ✓${NC}"
  echo "  Versión: v${FINAL_VER}"
  echo "  Método:  $INSTALL_MODE"
  echo ""
fi

notify_event "claude" "install_done" "$INSTALL_MODE"
log "Instalación de Claude Code completada"
exit 0
