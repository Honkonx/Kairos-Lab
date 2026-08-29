#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · codebuff.sh (silent mode)
#  Instala Codebuff (CodebuffAI) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash codebuff.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA (ARM64/aarch64, 2 métodos en cascada):
#    ✅ MÉTODO 1 (preferido): binario nativo del fork propio
#       github.com/Honkonx/codebuff-termux (fork de Hope2333/codebuff-termux)
#       — mismo patrón que freebuff.sh/bun-termux: se descarga el asset
#       "*_aarch64.deb" real de GitHub Releases (fork primero, upstream
#       Hope2333 como fallback dentro de este mismo método si el fork no
#       tiene el asset todavía adjunto a su release) y se extrae el árbol
#       usr/ COMPLETO a $TERMUX_PREFIX. Confirmado 2026-08-28 descargando y
#       parseando el .deb real (Hope2333/codebuff-termux, tag Push260803):
#       el binario real (usr/lib/codebuff/runtime/codebuff) ya viene con el
#       ELF interpreter PRE-PARCHEADO a
#       "/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1" —
#       no hace falta correr patchelf en este método, solo tener la capa
#       glibc instalada (mismo requisito que el MÉTODO 2 de abajo).
#    ✅ MÉTODO 2 (fallback si el 1 falla): el flujo previo — paquete npm
#       "codebuff" (launcher delgado, ~800 líneas JS) + BINARIO NATIVO real
#       ARM64 (glibc) que el propio launcher descarga solo en su primera
#       ejecución, parcheado con patchelf igual que freebuff.sh Método 2.
#
#  CORRECCIÓN 2026-08-17 (la versión anterior de este comentario estaba
#  MAL — investigación real contra el .tgz de npm y la URL de descarga,
#  no solo contra el listado de repos de GitHub):
#   1. El paquete npm "codebuff" (registry.npmjs.org) NO es la app en sí —
#      es un "launcher" (index.js + launcher.js, ~35KB) sin ningún addon
#      nativo (require('fs')/require('path') únicamente, dependencia npm
#      única: "tar"). No tiene postinstall/install script — por eso
#      `npm install -g codebuff` siempre termina en 0 sin importar la
#      arquitectura: no hace nada arquitectura-específico todavía.
#   2. El binario real se descarga recién en la PRIMERA EJECUCIÓN del
#      comando `codebuff` (launcher.js → ensureBinaryExists() →
#      https://codebuff.com/api/releases/download/<version>/codebuff-linux-arm64.tar.gz,
#      que redirige a github.com/CodebuffAI/codebuff-community/releases/).
#      linux-arm64 SÍ está en el mapa de targets soportados del launcher
#      (PLATFORM_TARGETS) — Codebuff no es "npm-only", el target ARM64 es
#      real. Se descargó y verificó ese tarball: contiene un ELF
#      "aarch64 ... dynamically linked, interpreter
#      /lib/ld-linux-aarch64.so.1" — el mismo patrón glibc que freebuff.sh
#      (linker glibc estándar, no Bionic). El binario se guarda en
#      $HOME/.config/manicode/codebuff.
#   3. Por eso `codebuff se instaló pero 'codebuff --version' no respondió`
#      en el log real: el launcher descarga bien el binario (npm install
#      solo puso el launcher) pero al intentar ejecutarlo con exec()
#      contra un intérprete glibc que no existe en Bionic, el spawn falla
#      silenciosamente — exactamente el mismo bug que freebuff.sh ya
#      resolvía con glibc + patchelf. Este script ahora dispara esa
#      primera ejecución a propósito (para forzar la descarga del binario
#      real) y le aplica patchelf al resultado, en vez de asumir que
#      "npm install" ya deja todo funcional.
#
#  CORRECCIÓN 2026-08-24 (ver docs/humano212.md — pedido explícito del
#  usuario: "busca si alguien lo hizo funcionar en termux android"; 3 bugs
#  reales encontrados y arreglados en cadena, confirmados uno por uno en
#  dispositivo real, no solo por lectura de código):
#   1. Faltaba fix_npm_shebang_wrapper() tras el npm install — el symlink
#      que deja "npm install -g" tiene shebang "#!/usr/bin/env node", que no
#      existe en Termux. Sin esto, NI SIQUIERA el launcher llegaba a correr.
#   2. Con el wrapper arreglado, el launcher fallaba con "Unsupported
#      platform: android arm64" — calcula la clave de descarga como
#      `${process.platform}-${process.arch}` ("android-arm64"), que no
#      existe en su propio PLATFORM_TARGETS (solo "linux-arm64" etc.).
#      Fix: CODEBUFF_BINARY_TARGET=linux-arm64 (override oficial que el
#      propio launcher.js expone vía variable de entorno, sin parchear nada).
#   3. Con 1+2 arreglados, el binario nativo descarga y ejecuta bien
#      (confirmado con patchelf), pero 'codebuff --version' nunca responde
#      rápido — el launcher siempre dibuja un banner ASCII animado antes de
#      cualquier salida. La verificación final se ajustó para no exigir un
#      semver parseado (falso negativo real, confirmado en vivo).
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login/API key (Codebuff soporta cualquier modelo vía OpenRouter) — queda
#       para que el usuario lo configure manualmente después
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.5.0 | Agosto 2026 (fork nativo codebuff-termux como método
#  preferido, ver github.com/Honkonx/codebuff-termux — fallback al launcher
#  npm + descarga diferida + patchelf que ya existía)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# Bug real #2 encontrado 2026-08-24 (ver docs/humano212.md): el launcher npm
# calcula la clave de descarga como `${process.platform}-${process.arch}`
# ("android-arm64" en Termux), que NO existe en su propio PLATFORM_TARGETS
# (solo "linux-arm64", "darwin-arm64", etc. — confirmado leyendo el
# launcher.js real instalado). Sin esto, CUALQUIER invocación de 'codebuff'
# (incluida la de verify_binary_installed en PASO 2, mucho antes de llegar a
# PASO 3) falla rápido con "Unsupported platform: android arm64" — por eso
# el export va acá arriba de todo, no solo dentro del bloque nativo de
# PASO 3. El propio launcher expone este override oficial vía entorno
# (getTargetOverride()), no hace falta parchear nada.
export CODEBUFF_BINARY_TARGET="linux-arm64"

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
{"id":"codebuff","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  _bin=$(command -v codebuff 2>/dev/null || echo "$TERMUX_PREFIX/bin/codebuff")
  _codebuff_native="$HOME/.config/manicode/codebuff"
  jq -n \
    --arg path "$_bin" \
    --arg native "$_codebuff_native" \
    --arg verify "command -v codebuff >/dev/null 2>&1 && codebuff --version >/dev/null 2>&1" \
    '{
      id: "codebuff",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-codebuff",
      version_registry_key: "codebuff.version",
      files: [
        {path: $path, required: true, note: "Wrapper/launcher de codebuff, resuelto por PATH al momento de empaquetar"},
        {path: $native, required: false, note: "Binario nativo ARM64 real descargado por el launcher en su primer arranque (~/.config/manicode/codebuff), parcheado con patchelf --set-interpreter — sin este archivo el wrapper de arriba queda roto en un device nuevo. Marcado no-required porque solo existe tras el primer arranque real del wrapper."}
      ],
      file_globs: [],
      dependencies: [],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "Si el canal instalado fue npm, el árbol de node_modules global no se empaqueta",
        "No reinstala Node.js/glibc — asume que el device destino ya tiene el mismo Termux base",
        "Si ~/.config/manicode/codebuff todavía no existe (wrapper nunca se ejecutó), el .deb resultante solo trae el launcher — reinstalar y correr codebuff una vez antes de empaquetar para incluir el binario real"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_codebuff_checkpoint"
CODEBUFF_PKG="codebuff@latest"
CODE_DIR="$HOME/.local/share/codebuff"
# Ruta real donde el launcher npm descarga el binario nativo en su primera
# ejecución (launcher.js: configDir = path.join(homeDir, '.config', 'manicode'),
# binaryName = 'codebuff') — confirmado leyendo el launcher.js real del .tgz.
CODEBUFF_NATIVE_BIN="$HOME/.config/manicode/codebuff"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^codebuff_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "codebuff_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^codebuff_${1}=done" "$CHECKPOINT" 2>/dev/null; }

# ── Arquitectura ───────────────────────────────────────────────
# En aarch64 el launcher npm descarga un binario nativo real linkeado a
# glibc (ver comentario del header, 2026-08-17) — necesita la misma capa
# glibc + patchelf que freebuff.sh. En otras arquitecturas se deja el
# binario tal como lo baja el launcher (Termux es exclusivamente ARM64,
# ver CLAUDE.md — esta rama es solo defensiva, no se espera ejercitarla).
_ARCH=$(uname -m 2>/dev/null || echo "unknown")
_NATIVE=false
[ "$_ARCH" = "aarch64" ] && _NATIVE=true

get_installed_ver() {
  local v=""
  command -v codebuff &>/dev/null && v=$(codebuff --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$v" ] && [ -x "$CODE_DIR/codebuff" ] && v=$("$CODE_DIR/codebuff" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$v" ] && [ -x "$CODEBUFF_NATIVE_BIN" ] && v=$("$CODEBUFF_NATIVE_BIN" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  echo "$v"
}

# ── Método 1 (preferido) — binario nativo del fork propio (codebuff-termux) ──
# A diferencia de PASO 4/5 más abajo (launcher npm + descarga diferida al primer
# arranque + patchelf manual), este método descarga el .deb REAL ya armado para
# Termux desde el fork propio del proyecto (o su upstream Hope2333 si el fork
# todavía no tiene el asset adjunto a su release — mismo mecanismo de 2 repos ya
# usado en freebuff.sh para bun-termux) y copia el árbol usr/ completo. Confirmado
# 2026-08-28 descargando y parseando el .deb real (Hope2333/codebuff-termux,
# Push260803): el ELF interpreter del binario real
# (usr/lib/codebuff/runtime/codebuff) YA viene apuntando a
# "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" — no hace falta patchelf en
# este método, solo tener esa capa glibc instalada (PASO 1, de la que este
# método también depende).
CODEBUFF_FORK_SOURCE_REPO=""
_codebuff_download_native_fork() {
  command -v dpkg-deb &>/dev/null || { warn "dpkg-deb no disponible — no se puede extraer el .deb del fork nativo de codebuff"; return 1; }
  command -v curl &>/dev/null || return 1

  local _repo _releases_json _asset_url=""
  for _repo in "Honkonx/codebuff-termux" "Hope2333/codebuff-termux"; do
    _releases_json=$(curl -fsSL "https://api.github.com/repos/${_repo}/releases?per_page=5" 2>/dev/null)
    [ -z "$_releases_json" ] && continue
    _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*_aarch64\.deb"' | \
      head -1 | grep -o 'https://[^"]*')
    if [ -n "$_asset_url" ]; then CODEBUFF_FORK_SOURCE_REPO="$_repo"; break; fi
  done
  if [ -z "$_asset_url" ]; then
    warn "No se encontró un asset .deb aarch64 del fork nativo de codebuff (ni en Honkonx/codebuff-termux ni en el upstream Hope2333/codebuff-termux)"
    return 1
  fi

  local _tmp="$HOME/tmp/codebuff_fork_$$"
  local _deb="$_tmp/$(basename "$_asset_url")"
  mkdir -p "$_tmp"
  info "Descargando Codebuff nativo del fork ($(basename "$_asset_url"), repo $CODEBUFF_FORK_SOURCE_REPO)..."
  curl -fsSL "$_asset_url" -o "$_deb" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  local _extract="$_tmp/extract"
  mkdir -p "$_extract"
  dpkg-deb -x "$_deb" "$_extract" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  # Mismo bug/fix real ya confirmado en freebuff.sh (docs/humano281.md): dpkg-deb -x
  # deja el árbol bajo la RUTA ABSOLUTA COMPLETA "$_extract/data/data/com.termux/
  # files/usr/..." (así empaqueta Termux sus .deb), no bajo "$_extract/usr/" — copiar
  # el árbol usr/ COMPLETO preserva la relación wrapper→lib intacta (acá:
  # usr/bin/codebuff invoca usr/lib/codebuff/runtime/codebuff).
  local _extract_usr="$_extract/data/data/com.termux/files/usr"
  [ -d "$_extract_usr" ] || _extract_usr="$_extract/usr"
  if [ ! -d "$_extract_usr" ]; then
    warn "El .deb del fork nativo de codebuff no tiene ningún layout usr/ reconocible tras la extracción"
    rm -rf "$_tmp"
    return 1
  fi
  cp -a "$_extract_usr/." "$TERMUX_PREFIX/" || {
    warn "No se pudo copiar el árbol usr/ del .deb del fork nativo de codebuff a $TERMUX_PREFIX"
    rm -rf "$_tmp"
    return 1
  }
  chmod +x "$TERMUX_PREFIX/bin/codebuff" 2>/dev/null
  [ -f "$TERMUX_PREFIX/lib/codebuff/runtime/codebuff" ] && chmod +x "$TERMUX_PREFIX/lib/codebuff/runtime/codebuff" 2>/dev/null
  rm -rf "$_tmp"

  # Verificación FUNCIONAL real — mismo criterio tolerante al banner animado que
  # PASO 6 más abajo (bug real #3, docs/humano212.md): no exige un semver
  # parseado, solo que no aparezca ninguno de los 2 errores fatales conocidos.
  local _raw
  _raw=$(timeout 25 "$TERMUX_PREFIX/bin/codebuff" --version 2>&1)
  if echo "$_raw" | grep -qE "Unsupported platform|ENOENT|No such file|not found"; then
    warn "Codebuff nativo del fork instalado pero no ejecuta: $(echo "$_raw" | grep -E "Unsupported platform|ENOENT|No such file|not found" | head -1)"
    return 1
  fi
  [ -x "$TERMUX_PREFIX/lib/codebuff/runtime/codebuff" ] || { warn "Codebuff nativo del fork instalado pero el binario real no quedó ejecutable"; return 1; }
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ▶ CODEBUFF — Instalador                 ║"
  echo "  ║  CodebuffAI · Termux ARM64                ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Codebuff ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Codebuff? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Capa de compatibilidad glibc ────────────────────
# Requisito compartido por el MÉTODO 1 (fork nativo, más abajo) y el MÉTODO 2
# (binario nativo descargado por el launcher npm + patchelf) — se instala una
# sola vez arriba de todo, antes de intentar ninguno de los 2.
if $_NATIVE; then
  step "PASO 1 — Capa de compatibilidad glibc (binario nativo ARM64)"
  if check_done "glibc"; then
    log "glibc ya verificado [checkpoint]"
  else
    _MISSING_DEPS=()
    [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || _MISSING_DEPS+=("glibc-repo" "glibc")
    [ -x "$TERMUX_PREFIX/glibc/bin/patchelf" ] || _MISSING_DEPS+=("patchelf-glibc")
    command -v dpkg-deb &>/dev/null || _MISSING_DEPS+=("dpkg")
    command -v curl &>/dev/null || _MISSING_DEPS+=("curl")

    if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
      info "Instalando: ${_MISSING_DEPS[*]}"
      pkg_update_with_fallback
      pkg install -y "${_MISSING_DEPS[@]}" \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" 2>/dev/null || \
        warn "No se pudieron instalar las dependencias glibc — los métodos nativos pueden no funcionar"
    fi
    mark_done "glibc"
    log "Capa glibc verificada"
  fi
fi

# ── PASO 2 — Node.js (Método ORIGINAL, primario) ──────────────
# Orden corregido 2026-08-28 (docs/humano281.md, corrección explícita del usuario: "deben
# quedar con el método que teníamos y los repos como el mío o de hoppe son de respaldo, todo
# es respaldo no remplazo") — el fork propio (Honkonx/codebuff-termux, hoy sin releases
# propios, cae a Hope2333/codebuff-termux) se movió al final (PASO 5b) como RESPALDO real,
# tentado solo si el método original (launcher npm + descarga diferida + patchelf) falla
# por completo — nunca al revés. _CODEBUFF_FORK se define abajo, después de intentar el
# método original.
_CODEBUFF_FORK=false
# PASO 2-5 (método original) corren dentro de un subshell: cualquier error() (que hace
# "exit 1") dentro de este bloque solo termina el subshell, no el script completo — así
# PASO 5b (fork de respaldo) siempre llega a intentarse si el método original falla en
# cualquier punto, en vez de matar la instalación entera de una.
if ! (
step "PASO 2 — Verificando Node.js"
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
    log "Node.js instalado: $(node --version)"
    mark_done "node"
  fi
fi

# ── PASO 4 — npm install (Método ORIGINAL, primario; instala el launcher —
#             el binario real se descarga recién en la primera ejecución,
#             ver PASO 5) ─────────────────────────────────────
step "PASO 4 — Instalando Codebuff vía npm"
if check_done "npm_install"; then
  log "Codebuff (launcher) ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${CODEBUFF_PKG}"
  npm install -g "$CODEBUFF_PKG" --force 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real encontrado 2026-08-24 (ver docs/humano212.md): faltaba este
  # wrapper — el symlink que deja "npm install -g" tiene shebang
  # "#!/usr/bin/env node", que no existe en Termux (no hay /usr en la raíz
  # real del filesystem). Confirmado en vivo: "codebuff --version" fallaba
  # con "/usr/bin/env: bad interpreter: No such file or directory" ANTES de
  # siquiera llegar a intentar descargar el binario nativo — el PASO 3 de
  # abajo (que dispara esa primera ejecución) interpretaba el fallo como
  # "no se pudo descargar el binario nativo (revisar conectividad)", un
  # diagnóstico equivocado. Mismo bug/mismo fix ya documentado en
  # lib.sh:fix_npm_shebang_wrapper() y usado en install_npm_global()/mimocode.sh
  # v1/OpenClaw/Cursor CLI — corre ANTES de verify_binary_installed (el symlink
  # roto de npm no ejecuta "--version" todavía, verificarlo antes del wrapper
  # sería justamente reproducir este mismo error). Debe ir antes del PASO 3
  # (que ahora sí puede disparar la primera ejecución real de 'codebuff' para
  # forzar la descarga del binario nativo).
  fix_npm_shebang_wrapper codebuff codebuff
  # A diferencia de otras herramientas npm de este mismo archivo de patrones,
  # NO se corre verify_binary_installed() (que llamaría "codebuff --version")
  # acá — a esta altura el binario nativo real todavía no se descargó ni se
  # parcheó (eso pasa recién en PASO 3), así que "codebuff --version" está
  # garantizado a fallar en este punto exacto para ESTE CLI puntual, sin que
  # signifique nada malo. La verificación funcional real queda para PASO 4,
  # después de que PASO 3 complete la descarga + patchelf.
  command -v codebuff &>/dev/null || error "codebuff (launcher) no quedó en PATH tras npm install"
  log "Launcher de Codebuff instalado"
  mark_done "npm_install"
fi

# ── PASO 5 — Binario nativo ARM64 (Método ORIGINAL, primario; glibc + patchelf) ──
# El launcher npm recién descarga el binario real la primera vez que se
# ejecuta 'codebuff' (a $HOME/.config/manicode/codebuff) — y ese binario
# está linkeado contra glibc (interpreter /lib/ld-linux-aarch64.so.1), no
# contra Bionic. Se dispara esa primera ejecución a propósito para forzar
# la descarga, y se le aplica patchelf al resultado — mismo patrón ya
# probado en freebuff.sh (glibc-repo/glibc + patchelf-glibc, con el
# binario real de patchelf en $PREFIX/glibc/bin/patchelf, no el nombre del
# paquete). La capa glibc en sí ya se instaló en PASO 1.
if $_NATIVE; then
  step "PASO 5 — Binario nativo ARM64 (glibc + patchelf)"
  if check_done "native_patch"; then
    log "Binario nativo ya parcheado [checkpoint]"
  else
    if [ ! -f "$CODEBUFF_NATIVE_BIN" ]; then
      info "Disparando la primera ejecución de 'codebuff' para forzar la descarga del binario real..."
      timeout 90 codebuff --version >/dev/null 2>&1
    fi

    if [ -f "$CODEBUFF_NATIVE_BIN" ]; then
      _patchelf_bin="$TERMUX_PREFIX/glibc/bin/patchelf"
      if [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] && [ -x "$_patchelf_bin" ]; then
        chmod +x "$CODEBUFF_NATIVE_BIN"
        info "Aplicando patchelf al binario nativo..."
        "$_patchelf_bin" --set-interpreter "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" \
          "$CODEBUFF_NATIVE_BIN" 2>/dev/null || \
          warn "patchelf falló — el binario puede requerir ajuste manual"
        mark_done "native_patch"
      else
        warn "glibc/patchelf no disponibles — el binario nativo puede no ejecutar sobre Bionic"
      fi
    else
      warn "El binario nativo no se descargó en la primera ejecución (revisar conectividad a codebuff.com)"
    fi
  fi
fi
); then
  warn "El método original (npm + glibc/patchelf) falló en algún paso — se intenta el fork de respaldo a continuación"
fi

# ── PASO 5b — Fork nativo (RESPALDO, solo si el método original no dejó un binario
#              ejecutable) ────────────────────────────────────
# Orden corregido 2026-08-28 (docs/humano281.md) — el fork propio (Honkonx/codebuff-termux,
# hoy sin releases propios, cae a Hope2333/codebuff-termux) es RESPALDO real, nunca
# reemplazo: solo se intenta si el método original (PASO 2-5, arriba) terminó sin dejar un
# binario ejecutable funcional.
if $_NATIVE && [ ! -x "$CODEBUFF_NATIVE_BIN" ] && [ ! -x "$TERMUX_PREFIX/lib/codebuff/runtime/codebuff" ]; then
  step "PASO 5b — Binario nativo del fork de respaldo (codebuff-termux)"
  if check_done "fork_install"; then
    log "Codebuff (fork de respaldo) ya instalado [checkpoint]"
    _CODEBUFF_FORK=true
  else
    warn "El método original no dejó un binario funcional — probando el fork de respaldo..."
    if _codebuff_download_native_fork; then
      log "Codebuff instalado vía el fork de respaldo (${CODEBUFF_FORK_SOURCE_REPO})"
      mark_done "fork_install"
      _CODEBUFF_FORK=true
    else
      warn "Falló también el fork de respaldo de codebuff — se sigue con lo que haya quedado del método original"
    fi
  fi
fi

# ── PASO 6 — Verificación FUNCIONAL real ──────────────────────
step "PASO 6 — Verificando instalación"
# Verificación FUNCIONAL real, no solo "command -v" (mismo principio ya
# aplicado a mistralvibe/n8n/freebuff, ver docs/humano/humano121.md) — el
# binario nativo puede estar presente pero no ejecutar sobre Bionic sin el
# patchelf de arriba; "command -v" solo no lo detecta, "--version"
# corriendo de verdad sí.
#
# Bug real #3 encontrado 2026-08-24 (ver docs/humano212.md): a diferencia de
# claude/mimo/freebuff, 'codebuff --version' NO responde rápido — el
# launcher siempre dibuja un banner ASCII animado (pantalla alterna, cursor
# oculto) antes de mostrar cualquier versión, confirmado en vivo en
# dispositivo real (ni con stdin cerrado ni sin TTY se salta la animación).
# Esperar a que ese texto contenga un semver dentro del timeout de arriba
# fallaba SIEMPRE, aunque el binario funcionara perfecto — un falso
# negativo, no un fallo real. Verificación ajustada: si el binario nativo
# existe, es ejecutable, y su salida NO contiene ninguno de los 2 errores
# fatales conocidos ("Unsupported platform"/"ENOENT"), se considera
# instalado — el número de versión exacto queda sin confirmar (limitación
# real de este CLI puntual, no de los otros módulos que usan el mismo
# patrón glibc+patchelf).
_RAW_CHECK=$(timeout 25 codebuff --version 2>&1)
if echo "$_RAW_CHECK" | grep -qE "Unsupported platform|ENOENT"; then
  error "codebuff no ejecuta tras la instalación: $(echo "$_RAW_CHECK" | grep -E "Unsupported platform|ENOENT" | head -1)"
fi
# El binario real "de verdad" varía según el canal: fork nativo lo deja en
# $TERMUX_PREFIX/lib/codebuff/runtime/codebuff, el launcher npm lo descarga a
# $CODEBUFF_NATIVE_BIN ($HOME/.config/manicode/codebuff) — aceptar cualquiera
# de los dos, no asumir uno solo.
if [ ! -x "$CODEBUFF_NATIVE_BIN" ] && [ ! -x "$TERMUX_PREFIX/lib/codebuff/runtime/codebuff" ]; then
  error "codebuff se instaló pero el binario nativo no quedó ejecutable — instalación no funcional"
fi
_VER_CHECK=$(echo "$_RAW_CHECK" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ -n "$_VER_CHECK" ]; then
  log "Codebuff instalado y verificado (v${_VER_CHECK})"
else
  log "Codebuff instalado — el CLI muestra un banner animado y no imprime versión sin una sesión interactiva real (verificado: arranca sin errores fatales)"
fi

# ── PASO 7 — Configuración de modelo (omitido en modo silencioso) ─
step "PASO 7 — Configuración de modelo/API key"
if check_done "config"; then
  log "Configuración ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — configurá tu proveedor de modelo (OpenRouter u otro) manualmente después, ejecutá 'codebuff'"
  mark_done "config"
else
  warn "Ejecutá 'codebuff' y seguí el asistente para configurar tu proveedor de modelo"
  mark_done "config"
fi

# ── Persistir CODEBUFF_BINARY_TARGET para uso diario ──────────
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# el "export CODEBUFF_BINARY_TARGET=linux-arm64" de arriba (línea ~93) solo vive mientras CORRE
# ESTE script — la verificación de PASO 4 (línea 326) pasa porque hereda ese export del mismo
# proceso, pero un usuario real que abre una terminal NUEVA después de instalar y corre
# "codebuff" a secas nunca tiene esa variable seteada, así que pega el mismo "Unsupported
# platform: android arm64" que este mismo bug ya documentó arriba — confirmado reproduciendo el
# fallo real en una sesión bash limpia sin este export. Se persiste en .bashrc, mismo patrón
# idempotente que ssh.sh usa para sus aliases.
if ! check_done "codebuff_bashrc_env"; then
  BASHRC="$HOME/.bashrc"
  [ -f "$BASHRC" ] && grep -v "CODEBUFF_BINARY_TARGET" "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"
  cat >> "$BASHRC" << 'BASHRC_EOF'

# ════════════════════════════════
#  Codebuff · override de plataforma (fix real, ver docs/humano216.md)
# ════════════════════════════════
export CODEBUFF_BINARY_TARGET="linux-arm64"
BASHRC_EOF
  log "CODEBUFF_BINARY_TARGET persistido en .bashrc"
  mark_done "codebuff_bashrc_env"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_CHANNEL="npm"
[ -x "$CODEBUFF_NATIVE_BIN" ] && _CHANNEL="npm_native_arm64"
$_CODEBUFF_FORK && _CHANNEL="fork_native_arm64"
_DATE=$(date +%Y-%m-%d)
registry_write codebuff \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=${_CHANNEL}" \
  "arch=${_ARCH}" \
  "install_date=${_DATE}"

notify_event "codebuff" "install_done" "$_VER_FINAL"
log "Codebuff instalado correctamente (v${_VER_FINAL:-?}, canal ${_CHANNEL})"
rm -f "$CHECKPOINT"
exit 0