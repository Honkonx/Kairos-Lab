#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · codex.sh (silent mode)
#  Instala OpenAI Codex CLI en Termux ARM64 (vía npm)
#
#  USO DESDE APP (KairosApp):
#    bash codex.sh --silent [--variant termux|vl]
#
#  USO MANUAL (standalone):
#    bash install_codex.sh
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo (canal termux por defecto)
#    --force       Reinstala aunque ya esté
#    --variant     "termux" (default, DioNanos/codex-termux) | "vl" (DioNanos/codex-vl)
#
#  QUÉ INSTALA:
#    ✅ Node.js (nodejs-lts, si falta)
#    ✅ Codex CLI — 2 variantes reales seleccionables desde la UI (ronda 2026-09-09):
#         · "termux" (default): @mmmbuto/codex-cli-termux (DioNanos/codex-termux — fork de
#           solo milestones grandes de openai/codex). Si esta vía npm falla, cae AUTOMÁTICAMENTE
#           a un binario nativo prebuilt de wallentx/codex-termux (fork completo del monorepo
#           Bazel de openai/codex, con release real). Este fallback reemplaza a la vieja
#           variante --variant native (WangChengYeh/codex_android, repo abandonado desde
#           2025-08-29 — retirada por completo en esta ronda).
#         · "vl": @mmmbuto/codex-vl (DioNanos/codex-vl — canal de mejoras día a día de
#           Android/Termux del mismo autor, restaura "code-mode" con runtime V8 embebido).
#           Sin fallback automático — el usuario la elige a sabiendas (pedido explícito).
#    ✅ Registry actualizado (codex.channel refleja cuál de las 3 rutas reales terminó usándose:
#       "termux" | "termux-fallback" | "vl")
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
#  VERSIÓN: 2.0.0 | Septiembre 2026 (rediseño 2 variantes + fallback automático,
#            reemplaza v1.0.0 de Julio 2026 — adaptado originalmente de install_codex.sh v1.0.0)
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
{"id":"codex","supports_silent":true,"supports_force":true,"variants":["termux","vl"],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Codex tiene 3 rutas reales posibles (ronda
# 2026-09-09): "termux" (npm, default), "termux-fallback"
# (binario nativo automático cuando la vía npm falla) y "vl" (npm, opt-in del usuario). Se
# detecta cuál quedó activa por archivos reales en vez de asumir — mismo criterio que antes.
if $DESCRIBE_FILES; then
  _df_fallback_bin="$TERMUX_PREFIX/opt/codex-wallentx/codex"
  _df_codex_link="$TERMUX_PREFIX/bin/codex"
  _df_vl_link="$TERMUX_PREFIX/bin/codex-vl"
  if [ -f "$_df_fallback_bin" ]; then
    jq -n \
      --arg p1 "$_df_fallback_bin" \
      --arg p2 "$_df_codex_link" \
      --arg verify "test -x \"$_df_fallback_bin\" && \"$_df_codex_link\" --version >/dev/null 2>&1" \
      '{
        id: "codex", supports_describe_files: true, variant: "termux-fallback",
        package_name: "kairos-module-codex",
        version_registry_key: "codex.version",
        files: [
          {path: $p1, required: true, note: "Binario ARM64 sin stripear del fallback automático (wallentx/codex-termux, fork completo del monorepo de openai/codex) — se usa solo cuando la vía npm normal (DioNanos/codex-termux) falla"},
          {path: $p2, required: true, note: "Symlink a codex-wallentx/codex"}
        ],
        file_globs: [], dependencies: [],
        verify_cmd: $verify,
        patch_cmd: "",
        not_covered: ["No hay parche real conocido para el binario del fallback — si el symlink se rompe, recrearlo con ln -sf es suficiente y no hace falta patch_cmd"]
      }'
  elif [ -e "$_df_vl_link" ]; then
    local_npm_root=$(npm root -g 2>/dev/null)
    jq -n \
      --arg glob "${local_npm_root}/@mmmbuto/codex-vl/**" \
      --arg link "$_df_codex_link" \
      '{
        id: "codex", supports_describe_files: true, variant: "vl",
        package_name: "kairos-module-codex",
        version_registry_key: "codex.version",
        files: [{path: $link, required: true, note: "Symlink codex -> codex-vl (el bin real del paquete npm @mmmbuto/codex-vl se llama \"codex-vl\", no \"codex\" — el symlink existe para que el resto de la UI de Kairos, que invoca \"codex\" a secas, siga funcionando igual con esta variante)"}],
        file_globs: [{pattern: $glob, required: false, note: "paquete npm completo @mmmbuto/codex-vl — ruta depende de npm root -g del device que empaqueta"}],
        dependencies: [{id: "node", check_cmd: "command -v node", install_hint: "pkg install -y nodejs-lts"}],
        verify_cmd: "command -v codex-vl >/dev/null 2>&1 && codex-vl --version >/dev/null 2>&1",
        patch_cmd: "",
        not_covered: ["El wrapper bin de npm no se captura como archivo discreto — solo el paquete completo vía file_globs. Reinstalar puede requerir recrear a mano el symlink codex -> codex-vl si no sobrevive la copia"]
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
# Valor inicial — se sobreescribe con el canal REAL que terminó instalándose (ver PASO 2 más
# abajo: "termux" | "termux-fallback" | "vl"). Sirve de default sensato si el checkpoint ya
# tenía "npm_install" marcado done de una corrida anterior (resume), aunque en ese caso puntual
# no distingue "termux" de "termux-fallback" — mismo nivel de granularidad grueso que ya tenían
# los demás checkpoints de este script.
CODEX_CHANNEL="${VARIANT:-termux}"

# 2026-07-31: dist-tag corregido de @next a @latest — el README real del repo
# (github.com/DioNanos/codex-termux) documenta "next" como "tested candidate
# versions published after GitHub Actions validation" (release en camino,
# no promovido aún) y dice explícitamente "@latest es el target recomendado
# para usuarios finales". Confirmado además contra el registry npm real:
# dist-tags actuales son stable=0.144.4, next=0.145.0, latest=0.146.0 — es
# decir "next" queda POR DEBAJO de "latest" en este paquete (no es un canal
# "más nuevo/beta" como en otros paquetes), así que instalar @next instalaba
# a propósito una versión más vieja que la recomendada por el propio proyecto.
CODEX_NORMAL_PKG="@mmmbuto/codex-cli-termux@latest"

# 2026-09-09: variante "vl", opt-in del usuario, sin
# fallback automático (pedido explícito). Confirmado vía npm registry real: el bin del paquete
# se llama "codex-vl" (no "codex") — ver el symlink que se crea en PASO 2 más abajo.
CODEX_VL_PKG="@mmmbuto/codex-vl@latest"
CODEX_VL_BIN="codex-vl"

# Repos reales de referencia (documentación/trazabilidad, confirmados vía GitHub API
# 2026-09-09). El mecanismo real de descarga de "termux"/"vl" es el REGISTRY DE NPM
# (@mmmbuto/...), no una descarga directa de GitHub Releases — forkear estos 2 repos a
# Honkonx no cambia por sí solo la fuente real consumida acá: haría falta que el fork
# republicara un paquete npm bajo un scope propio y recién ahí cambiar CODEX_NORMAL_PKG/
# CODEX_VL_PKG. Se documentan igual para dejar trazado qué proyecto publica cada paquete.
CODEX_NORMAL_REPO="DioNanos/codex-termux"   # publica @mmmbuto/codex-cli-termux en npm
CODEX_VL_REPO="DioNanos/codex-vl"           # publica @mmmbuto/codex-vl en npm

# Fallback automático — este SÍ es una descarga directa de GitHub Releases (sin npm de por
# medio, ver _install_codex_fallback_native() más abajo), así que acá sí alcanza con cambiar
# esta constante cuando exista un fork real en Honkonx con sus propios releases — mismo patrón
# que "_FORK" en antigravity.sh / "FORK_OWNER"+"FORK_REPO" en opencode.sh. Confirmado real vía
# GitHub API 2026-09-09: fork completo de openai/codex (no solo milestones), release
# "rust-v0.153.4-termux" publicada 2026-09-05 por el bot de release del propio repo, asset
# único "codex-aarch64-linux-android.tar.gz" (~381MB comprimido) con un binario "codex" adentro
# (~1.4GB sin stripear — no se encontró un segundo binario "codex-code-mode-host" en ESTA
# release puntual, aunque el flujo de abajo no asume una cantidad fija de binarios).
CODEX_FALLBACK_REPO="wallentx/codex-termux"

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

# ── Fallback nativo automático — wallentx/codex-termux ────────────────────────
# Se usa SOLO cuando la vía npm normal (variante "termux", DioNanos/codex-termux) falla —
# nunca para la variante "vl" (sin fallback, pedido explícito del usuario). No pinnea ningún
# tag fijo — mismo patrón ya probado en mimocode.sh/freebuff.sh/kilo.sh: pide la lista real
# (/releases?per_page=5, no /releases/latest) y toma el primer asset que matchea el nombre,
# para no quedar pinneado a un tag que cambia con cada release de este repo (ritmo casi diario,
# confirmado por los pushed_at reales de la API el 2026-09-09).
_install_codex_fallback_native() {
  info "Consultando releases reales (GitHub API ${CODEX_FALLBACK_REPO})..."
  local _releases_json _asset_url
  _releases_json=$(curl -fsSL "https://api.github.com/repos/${CODEX_FALLBACK_REPO}/releases?per_page=5" 2>/dev/null)
  [ -z "$_releases_json" ] && { warn "No se pudo consultar la API de GitHub (${CODEX_FALLBACK_REPO})"; return 1; }
  _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*aarch64-linux-android\.tar\.gz"' | \
    head -1 | grep -o 'https://[^"]*')
  [ -z "$_asset_url" ] && { warn "No se encontró un asset aarch64-linux-android.tar.gz en ${CODEX_FALLBACK_REPO}"; return 1; }
  info "Descargando (fallback nativo): $_asset_url"
  local tmp="$HOME/tmp/codex_fallback_dl"
  mkdir -p "$tmp"
  local tarball="$tmp/codex-fallback.tar.gz"
  curl -fL --progress-bar --max-time 300 "$_asset_url" -o "$tarball" \
    || wget --timeout=300 "$_asset_url" -O "$tarball" \
    || { warn "Descarga del fallback nativo falló"; rm -rf "$tmp"; return 1; }
  [ -s "$tarball" ] || { warn "Descarga del fallback nativo vacía o incompleta"; rm -rf "$tmp"; return 1; }
  tar -xzf "$tarball" -C "$tmp" || { warn "No se pudo extraer el tarball del fallback nativo (revisá espacio libre — el binario sin stripear pesa ~1.4GB)"; rm -rf "$tmp"; return 1; }
  local bin_src; bin_src=$(find "$tmp" -type f -name "codex" | head -1)
  [ -z "$bin_src" ] && { warn "No se encontró el binario 'codex' dentro del tarball de ${CODEX_FALLBACK_REPO}"; rm -rf "$tmp"; return 1; }
  mkdir -p "$TERMUX_PREFIX/opt/codex-wallentx"
  cp "$bin_src" "$TERMUX_PREFIX/opt/codex-wallentx/codex"
  chmod +x "$TERMUX_PREFIX/opt/codex-wallentx/codex"
  ln -sf "$TERMUX_PREFIX/opt/codex-wallentx/codex" "$TERMUX_PREFIX/bin/codex"
  rm -rf "$tmp"
  # Verificación real de ejecución, no solo que el archivo exista (mismo patrón que
  # cloudflared/ssh.sh y la vieja variante native — un binario ARM64 no siempre corre sobre
  # Bionic, y el asset de wallentx target=android pero conviene confirmar igual).
  if ! verify_binary_installed codex; then
    warn "Binario del fallback nativo descargado pero no ejecuta (incompatible con Bionic)"
    rm -f "$TERMUX_PREFIX/bin/codex"
    rm -rf "$TERMUX_PREFIX/opt/codex-wallentx"
    return 1
  fi
  return 0
}

# ── PASO 1 — Node.js ─────────────────────────────────────────
# Común a ambas variantes — "termux" y "vl" son las dos paquetes npm (el fallback nativo NO
# necesita Node, pero solo se dispara DESPUÉS de que la vía npm ya falló con Node ya presente).
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
    pkg install nodejs-lts -y || error "No se pudo instalar Node.js"
    command -v node &>/dev/null || error "Node.js no disponible tras instalación"
    # Bug real (auditoría 2026-08-05): "npm install
    # -g npm" sobreescribe el npm parcheado para Termux (shebang sin /usr/bin/env, que
    # acá no existe) con uno genérico del registry — "bad interpreter" en cualquier
    # npm posterior. El npm que trae nodejs-lts ya alcanza.
    log "Node.js instalado: $(node --version)"
    mark_done "node"
  fi
fi

# ── PASO 2 — Instalando Codex CLI (según variante) ────────────
step "PASO 2 — Instalando Codex CLI (variante: ${VARIANT:-termux})"
if check_done "npm_install"; then
  log "Codex CLI ya instalado [checkpoint]"
elif [ "$VARIANT" = "vl" ]; then
  info "Ejecutando: npm install -g ${CODEX_VL_PKG} (${CODEX_VL_REPO})"
  npm install -g "$CODEX_VL_PKG" || error "npm install falló (${CODEX_VL_REPO}) — sin fallback para esta variante, revisá manualmente"
  # Bug real confirmado por ADB (mismo patrón ya documentado en
  # lib.sh/expo.sh): el symlink que "npm install -g" genera no ejecuta directo en este
  # dispositivo sin este fix.
  fix_npm_shebang_wrapper "$CODEX_VL_BIN" "$CODEX_VL_PKG"
  verify_binary_installed "$CODEX_VL_BIN" || error "codex-vl no ejecuta tras la instalación (revisá manualmente: codex-vl --version)"
  # Alias "codex" -> "codex-vl": el resto de la UI de Kairos (CodexFragment.kt — login, resume,
  # prompt directo, abrir en terminal/proyecto) invoca el comando "codex" a secas sin conocer
  # la variante instalada. @mmmbuto/codex-vl expone su bin como "codex-vl" (confirmado vía npm
  # registry 2026-09-09), distinto del bin "codex" de @mmmbuto/codex-cli-termux — sin este
  # symlink esos botones romperían para cualquiera que elija VL. Mismo patrón que ya usaba la
  # vieja variante --variant native (symlink forzado a $PREFIX/bin/codex).
  ln -sf "$TERMUX_PREFIX/bin/${CODEX_VL_BIN}" "$TERMUX_PREFIX/bin/codex"
  verify_binary_installed codex || error "el alias codex -> codex-vl no ejecuta tras crear el symlink"
  CODEX_CHANNEL="vl"
  log "Codex VL instalado (${CODEX_VL_REPO})"
  mark_done "npm_install"
else
  info "Ejecutando: npm install -g ${CODEX_NORMAL_PKG} (${CODEX_NORMAL_REPO})"
  if npm install -g "$CODEX_NORMAL_PKG"; then
    fix_npm_shebang_wrapper codex "$CODEX_NORMAL_PKG"
  fi
  # Chequeo funcional real, no solo "existe en PATH" — ver
  # verify_binary_installed() en lib.sh. Si esto falla (npm install falló O el symlink no
  # ejecuta), cae automáticamente al fallback nativo en vez de abortar — reemplaza a la vieja
  # variante --variant native (WangChengYeh/codex_android, repo abandonado, retirada esta ronda).
  if verify_binary_installed codex; then
    CODEX_CHANNEL="termux"
    log "Codex CLI instalado (canal termux, ${CODEX_NORMAL_REPO})"
  else
    warn "Instalación vía npm (${CODEX_NORMAL_REPO}) falló o no ejecuta — probando fallback nativo (${CODEX_FALLBACK_REPO})"
    _install_codex_fallback_native || error "Codex CLI: falló tanto la vía npm (${CODEX_NORMAL_REPO}) como el fallback nativo (${CODEX_FALLBACK_REPO})"
    CODEX_CHANNEL="termux-fallback"
    log "Codex CLI instalado (fallback nativo, ${CODEX_FALLBACK_REPO})"
  fi
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
log "Codex CLI instalado correctamente (v${_VER_FINAL:-?}, canal ${CODEX_CHANNEL})"
rm -f "$CHECKPOINT"
exit 0
