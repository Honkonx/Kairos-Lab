#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · opencode.sh (silent mode)
#  Instala OpenCode en Termux ARM64 — SOLO nativo (glibc Termux)
#
#  USO DESDE APP (KairosApp):
#    bash opencode.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_opencode.sh
#
#  FLAGS:
#    --silent              Sin preguntas, instala todo directo
#    --force                Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Binario desde github.com/Honkonx/opencode-termux releases
#    ✅ glibc + openssl-glibc + ncurses (paquetes de Termux, no proot)
#    ✅ Scripts: opencode_start.sh, opencode_stop.sh
#    ✅ Aliases en .bashrc
#    ✅ Registry actualizado
#
#  OUTPUT (modo --silent):
#    [STEP] N/M Descripción
#    [OK] mensaje
#    [ERROR] mensaje (exit 1)
#
#  2026-07-24: rama proot removida — investigación externa confirmó
#  que glibc-repo de Termux (sin distro completa) es el enfoque
#  correcto para OpenCode, igual que en termux-ai-stack/actu ai-stack/
#  install_opencode.sh. La rama proot queda archivada en
#  termux-ai-stack/proot-legacy/install_opencode_proot.sh.
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 4.0.0 | Julio 2026
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
    --variant)  shift ;; # aceptado por compatibilidad, ya no hay variantes
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"opencode","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"proot removido 2026-07-24, ver termux-ai-stack/proot-legacy/"}
JSON
  exit 0
fi

# ── Constantes ────────────────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_opencode_checkpoint"
OPENCODE_SCRIPTS="$HOME/scripts/opencode"
FORK_OWNER="Honkonx"
FORK_REPO="opencode-termux"
GITHUB_API="https://api.github.com/repos/${FORK_OWNER}/${FORK_REPO}/releases/latest"
DL_DIR="$HOME/.opencode_install_tmp"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  local location="$2"
  registry_install opencode "$version" "location=$location" "port=3000"
}

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Reemplaza el manifest a mano modulos/manifests/opencode.json (borrado
# como código muerto en humano165, ver docs/arquitectura/MODULEDEB_GENERICO.md).
# Contenido migrado 1:1 del manifest piloto original
# (git show 838544d^:modulos/manifests/opencode.json).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/opencode" \
    --arg n1 "Binario/wrapper extraído del .pkg.tar.xz (o .deb fallback) de github.com/${FORK_OWNER}/${FORK_REPO} — se extrae 'usr/*' directo dentro de \$PREFIX, sin parche" \
    --arg p2 "$TERMUX_PREFIX/lib/opencode/runtime/opencode" \
    --arg n2 "Runtime real cuando el paquete lo separa del wrapper de \$PREFIX/bin" \
    --arg p3 "$OPENCODE_SCRIPTS/opencode_start.sh" \
    --arg n3 "Arranca 'opencode web --port 3000' en sesión tmux 'opencode'" \
    --arg p4 "$OPENCODE_SCRIPTS/opencode_stop.sh" \
    --arg n4 "Mata cualquier sesión tmux 'opencode*' + pkill -f 'opencode web'" \
    --arg glob "$TERMUX_PREFIX/lib/opencode/**" \
    --arg dep1_check "test -d \"$TERMUX_PREFIX/glibc\"" \
    --arg dep1_hint "pkg install -y glibc-repo && pkg update -y && pkg install -y glibc openssl-glibc ncurses" \
    --arg dep2_check "! ldd \"$TERMUX_PREFIX/bin/opencode\" 2>&1 | grep -q 'not found'" \
    --arg dep2_hint "pkg install -y glibc openssl-glibc ncurses (ver README de ${FORK_OWNER}/${FORK_REPO}, rama pure-android, sección Dependencies — ncurses obligatorio para la TUI vía @opentui/solid)" \
    --arg verify "test -x \"$TERMUX_PREFIX/bin/opencode\" && ! ldd \"$TERMUX_PREFIX/bin/opencode\" 2>&1 | grep -q 'not found'" \
    --arg patch "chmod 755 \"$TERMUX_PREFIX/bin/opencode\" 2>/dev/null || true; [ -f \"$TERMUX_PREFIX/lib/opencode/runtime/opencode\" ] && chmod 755 \"$TERMUX_PREFIX/lib/opencode/runtime/opencode\"; chmod +x \"$OPENCODE_SCRIPTS/\"*.sh 2>/dev/null || true" \
    '{
      id: "opencode",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-opencode",
      version_registry_key: "opencode.version",
      files: [
        {path: $p1, required: true, note: $n1},
        {path: $p2, required: false, note: $n2},
        {path: $p3, required: true, note: $n3},
        {path: $p4, required: true, note: $n4}
      ],
      file_globs: [
        {pattern: $glob, required: false, note: "árbol completo del runtime, tamaño variable según versión upstream"}
      ],
      dependencies: [
        {id: "glibc", check_cmd: $dep1_check, install_hint: $dep1_hint},
        {id: "runtime_libs_ok", check_cmd: $dep2_check, install_hint: $dep2_hint}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "opencode.sh extrae TODO el árbol usr/* del paquete original — este describe-files captura el binario+runtime conocidos vía files[] y agrega el resto vía file_globs",
        "No hay parche real conocido para OpenCode (a diferencia de Claude native) — patch_cmd es solo re-chmod, no una reparación funcional"
      ]
    }'
  exit 0
fi

# ── Verificar si ya está instalado ────────────────────────────
if command -v opencode &>/dev/null && ! $FORCE; then
  log "OpenCode ya instalado — $(opencode --version 2>/dev/null | head -1)"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT" "$CHECKPOINT.data"

# ── Modo manual: confirmación ─────────────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · OpenCode Installer      ║
  ║   Nativo ARM64 · sin root · v4.0.0         ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Instala OpenCode nativo (glibc Termux, sin proot)."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=6

# ── PASO 1 — Dependencias glibc ─────────────────────────────
step "1/$TOTAL_STEPS Instalando dependencias glibc"

if check_done "native_glibc_deps"; then
  log "Dependencias glibc ya instaladas [checkpoint]"
else
  info "Instalando glibc-repo, glibc, openssl-glibc y ncurses..."

  # Bug real (2026-08-06, ver docs/humano/humano77.md): a diferencia del PASO 2
  # (curl --max-time / wget --timeout), este PASO 1 no tenía ningún timeout —
  # con conexión lenta/inestable "pkg install" puede quedarse colgado
  # indefinidamente sin devolver el control al script. Se envuelve con
  # "timeout" (coreutils, ya viene con Termux) para que un cuelgue real
  # termine en error en vez de silencio infinito.
  TIMEOUT_PKG=180

  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback

  timeout "$TIMEOUT_PKG" pkg install -y glibc-repo \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" || \
    warn "glibc-repo: advertencia (o timeout de ${TIMEOUT_PKG}s) — continuando"

  timeout "$TIMEOUT_PKG" pkg update -y 2>/dev/null || true

  # 2026-07-31: el README real del proyecto de origen (Honkonx/opencode-termux,
  # rama pure-android — https://github.com/Honkonx/opencode-termux, sección
  # "Dependencies") lista ncurses como dependencia obligatoria ("TUI support"),
  # junto a glibc y openssl-glibc — el binario usa @opentui/solid para la TUI.
  # Sin ncurses, "opencode --version" (lo único que valida el PASO 4) funciona
  # igual, pero "opencode ." (botón "TUI en terminal" de OpenCodeFragment.kt)
  # falla en runtime — checkpoint pasaba en verde con un paso real faltante.
  timeout "$TIMEOUT_PKG" pkg install -y glibc openssl-glibc ncurses \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" || \
    warn "glibc/openssl-glibc/ncurses: advertencia (o timeout de ${TIMEOUT_PKG}s) — continuando"

  if detect_glibc; then
    log "glibc disponible"
  else
    warn "glibc no detectado — OpenCode puede fallar"
  fi

  mark_done "native_glibc_deps"
fi

# ── PASO 2 — Detectar release GitHub ────────────────────────
step "2/$TOTAL_STEPS Detectando última versión"

if check_done "native_version_resolved"; then
  OC_RELEASE_URL=$(grep "^_oc_pkg_url=" "$CHECKPOINT.data" 2>/dev/null | cut -d'=' -f2-)
  OC_RELEASE_VER=$(grep "^_oc_ver=" "$CHECKPOINT.data" 2>/dev/null | cut -d'=' -f2-)
  log "Versión resuelta [checkpoint]: v${OC_RELEASE_VER}"
else
  info "Consultando GitHub API: ${FORK_OWNER}/${FORK_REPO}..."

  RELEASE_JSON_PATH="$DL_DIR/release.json"
  mkdir -p "$DL_DIR"

  # 2026-07-28: se vio fallar en dispositivo real con "No se encontró binario
  # aarch64 en release " (tag vacío) pese a que el release en GitHub SÍ tiene
  # los assets correctos — indica una descarga cortada a medias en conexión
  # móvil (curl puede devolver éxito con el archivo truncado si el server
  # cierra la conexión). Un reintento + timeout más alto cubre ese caso; si
  # vuelve a fallar, el mensaje de error ahora muestra qué se descargó de
  # verdad para poder diagnosticarlo sin adivinar.
  #
  # 2026-08-14 (ver docs/humano/... investigación del log real
  # install_opencode.log): se investigaron 2 hipótesis para el
  # "[ERROR] No se pudo consultar la API de GitHub. Verifica conexión."
  # 1) Falta de header User-Agent → 403. Verificado CON PRUEBA REAL contra
  #    api.github.com: curl SIEMPRE manda un User-Agent por default
  #    ("curl/x.y.z") aunque no se pase -A/-H explícito, y GitHub acepta ese
  #    default (200 OK) — solo rechaza con 403 cuando el header viene
  #    explícitamente vacío/ausente (curl -A "" o -H "User-Agent:"), cosa que
  #    este script NUNCA hacía. Esta hipótesis queda descartada como causa
  #    real, pero se agrega igual un User-Agent explícito abajo por buena
  #    práctica (recomendado por la doc de GitHub, no depende del comportamiento
  #    default de la build de curl/wget de Termux, que no podemos verificar
  #    remotamente).
  # 2) Rate limit sin autenticar (60 req/hora por IP) → también 403, pero con
  #    body "API rate limit exceeded...". Con "curl -fsSL" el flag "-f" hace
  #    que curl descarte el body y devuelva solo exit≠0 en cualquier HTTP >=400
  #    — por eso el script NUNCA podía distinguir "sin conexión real" (curl no
  #    pudo ni conectar, exit 6/7/28) de "GitHub respondió pero rechazó la
  #    petición" (403, con body). El mensaje "verifica conexión" podía ser
  #    directamente falso. Se quita "-f" y se captura el HTTP status code +
  #    el body de error para diagnosticar cuál de los dos pasó de verdad.
  _download_ok=false
  _oc_http_code=""
  _oc_curl_exit=""
  _oc_ua="kairos-app/opencode-installer (+https://github.com/Honkonx/kairos-lab)"
  for _attempt in 1 2; do
    if command -v curl &>/dev/null; then
      _oc_http_code=$(curl -sSL --max-time 30 \
        -H "User-Agent: ${_oc_ua}" \
        -o "$RELEASE_JSON_PATH" -w '%{http_code}' \
        "$GITHUB_API" 2>/dev/null)
      _oc_curl_exit=$?
      [ "$_oc_curl_exit" = "0" ] && [ "$_oc_http_code" = "200" ] && _download_ok=true
    fi
    if ! $_download_ok && command -v wget &>/dev/null; then
      wget -q --timeout=30 --header="User-Agent: ${_oc_ua}" \
        "$GITHUB_API" -O "$RELEASE_JSON_PATH" 2>/dev/null && _download_ok=true
    fi
    $_download_ok && [ -s "$RELEASE_JSON_PATH" ] && grep -q '"tag_name"' "$RELEASE_JSON_PATH" 2>/dev/null && break
    _download_ok=false
    [ "$_attempt" = "1" ] && { warn "Descarga de metadata incompleta, reintentando..."; sleep 2; }
  done

  if ! $_download_ok || [ ! -s "$RELEASE_JSON_PATH" ]; then
    if [ "$_oc_http_code" = "403" ] && grep -qi "rate limit" "$RELEASE_JSON_PATH" 2>/dev/null; then
      error "GitHub rechazó la petición por límite de tasa sin autenticar (60/hora por IP) — NO es un problema de conexión. Esperá unos minutos y reintentá."
    elif [ "$_oc_http_code" = "403" ]; then
      error "GitHub rechazó la petición (HTTP 403) — NO es un problema de conexión real. Respuesta: $(head -c 200 "$RELEASE_JSON_PATH" 2>/dev/null | tr -d '\n')"
    elif [ -n "$_oc_http_code" ] && [ "$_oc_http_code" != "000" ]; then
      error "La API de GitHub respondió con HTTP ${_oc_http_code}. Respuesta: $(head -c 200 "$RELEASE_JSON_PATH" 2>/dev/null | tr -d '\n')"
    else
      error "No se pudo consultar la API de GitHub. Verifica conexión."
    fi
  fi

  OC_RELEASE_TAG=$(grep -o '"tag_name": *"[^"]*"' "$RELEASE_JSON_PATH" | head -1 | cut -d'"' -f4)
  OC_RELEASE_VER=$(echo "$OC_RELEASE_TAG" | sed 's/^v//')

  # Buscar .pkg.tar.xz aarch64
  OC_RELEASE_URL=$(grep -o '"browser_download_url": *"[^"]*"' "$RELEASE_JSON_PATH" \
    | grep "aarch64.*\.pkg\.tar\.xz" | head -1 | cut -d'"' -f4)
  _PKG_FORMAT="pkg.tar.xz"

  # Fallback: .deb
  if [ -z "$OC_RELEASE_URL" ]; then
    OC_RELEASE_URL=$(grep -o '"browser_download_url": *"[^"]*"' "$RELEASE_JSON_PATH" \
      | grep "aarch64.*\.deb" | head -1 | cut -d'"' -f4)
    _PKG_FORMAT="deb"
  fi

  if [ -z "$OC_RELEASE_URL" ]; then
    error "No se encontró binario aarch64 en release '${OC_RELEASE_TAG}'. Respuesta recibida: $(head -c 200 "$RELEASE_JSON_PATH" 2>/dev/null | tr -d '\n')"
  fi

  log "Release: ${OC_RELEASE_TAG} (${_PKG_FORMAT})"
  rm -f "$RELEASE_JSON_PATH"

  cat > "$CHECKPOINT.data" << EOF
_oc_pkg_url=${OC_RELEASE_URL}
_oc_ver=${OC_RELEASE_VER}
_oc_fmt=${_PKG_FORMAT}
EOF
  mark_done "native_version_resolved"
fi

_PKG_FORMAT=$(grep "^_oc_fmt=" "$CHECKPOINT.data" 2>/dev/null | cut -d'=' -f2-)
[ -z "$_PKG_FORMAT" ] && _PKG_FORMAT="pkg.tar.xz"

# ── PASO 3 — Descargar paquete ──────────────────────────────
step "3/$TOTAL_STEPS Descargando paquete v${OC_RELEASE_VER}"

if check_done "native_download"; then
  OC_PKG_FILE=$(ls "$DL_DIR"/opencode*.${_PKG_FORMAT##*.} 2>/dev/null \
    | grep -E "aarch64\.(pkg\.tar\.xz|deb)$" | head -1)
  [ -z "$OC_PKG_FILE" ] || [ ! -f "$OC_PKG_FILE" ] && {
    warn "Archivo no encontrado — re-descargando"
    grep -v "^native_download$" "$CHECKPOINT" > "$CHECKPOINT.tmp" && mv "$CHECKPOINT.tmp" "$CHECKPOINT"
  }
fi

if ! check_done "native_download"; then
  mkdir -p "$DL_DIR"
  [[ "$_PKG_FORMAT" == "pkg.tar.xz" ]] && \
    OC_PKG_FILE="$DL_DIR/opencode-${OC_RELEASE_VER}-1-aarch64.pkg.tar.xz"
  [[ "$_PKG_FORMAT" == "deb" ]] && \
    OC_PKG_FILE="$DL_DIR/opencode_${OC_RELEASE_VER}_aarch64.deb"

  info "Descargando desde GitHub Releases..."
  _dl_ok=false
  if command -v curl &>/dev/null; then
    curl -fL --max-time 120 "$OC_RELEASE_URL" -o "$OC_PKG_FILE" 2>/dev/null && _dl_ok=true
  fi
  if ! $_dl_ok && command -v wget &>/dev/null; then
    wget -q --timeout=120 "$OC_RELEASE_URL" -O "$OC_PKG_FILE" 2>/dev/null && _dl_ok=true
  fi

  if ! $_dl_ok || [ ! -s "$OC_PKG_FILE" ]; then
    rm -f "$OC_PKG_FILE"
    error "Descarga fallida. Verifica conexión."
  fi

  log "Descargado: $(du -sh "$OC_PKG_FILE" | cut -f1)"
  mark_done "native_download"
fi

# ── PASO 4 — Instalar binario ───────────────────────────────
step "4/$TOTAL_STEPS Instalando en Termux"

if check_done "native_install"; then
  log "Instalación ya completada [checkpoint]"
else
  case "$_PKG_FORMAT" in
    pkg.tar.xz)
      info "Extrayendo .pkg.tar.xz..."
      _EXTRACT_TMP="$DL_DIR/extract"
      mkdir -p "$_EXTRACT_TMP"
      if tar -xJf "$OC_PKG_FILE" -C "$_EXTRACT_TMP" 2>/dev/null; then
        if [ -d "$_EXTRACT_TMP/usr" ]; then
          cp -r "$_EXTRACT_TMP/usr/"* "$TERMUX_PREFIX/" 2>/dev/null || true
          chmod 755 "$TERMUX_PREFIX/bin/opencode" 2>/dev/null || true
          [ -f "$TERMUX_PREFIX/lib/opencode/runtime/opencode" ] && \
            chmod 755 "$TERMUX_PREFIX/lib/opencode/runtime/opencode" 2>/dev/null || true
          log "Extraído correctamente"
        else
          tar -xJf "$OC_PKG_FILE" -C "$TERMUX_PREFIX/" --strip-components=1 2>/dev/null || \
            error "No se pudo extraer el paquete"
        fi
      else
        error "Fallo al extraer .pkg.tar.xz"
      fi
      rm -rf "$_EXTRACT_TMP"
      ;;
    deb)
      info "Instalando .deb..."
      if command -v dpkg &>/dev/null; then
        dpkg -i "$OC_PKG_FILE" 2>/dev/null || warn "dpkg advertencias — verificando..."
      elif command -v ar &>/dev/null; then
        _DEB_TMP="$DL_DIR/deb_extract"
        mkdir -p "$_DEB_TMP"
        ar x "$OC_PKG_FILE" --output="$_DEB_TMP" 2>/dev/null || true
        if [ -f "$_DEB_TMP/data.tar.xz" ]; then
          tar -xJf "$_DEB_TMP/data.tar.xz" -C "$_DEB_TMP/" 2>/dev/null || true
        elif [ -f "$_DEB_TMP/data.tar.gz" ]; then
          tar -xzf "$_DEB_TMP/data.tar.gz" -C "$_DEB_TMP/" 2>/dev/null || true
        fi
        [ -d "$_DEB_TMP/usr" ] && cp -r "$_DEB_TMP/usr/"* "$TERMUX_PREFIX/" 2>/dev/null || true
        rm -rf "$_DEB_TMP"
      else
        error "No se puede extraer .deb sin ar o dpkg. Instala binutils primero."
      fi
      chmod 755 "$TERMUX_PREFIX/bin/opencode" 2>/dev/null || true
      ;;
  esac

  # Verificar binario
  if command -v opencode &>/dev/null; then
    OC_VER=$(opencode --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    log "opencode v${OC_VER:-?} funcional"
  elif [ -f "$TERMUX_PREFIX/bin/opencode" ]; then
    OC_VER=$("$TERMUX_PREFIX/bin/opencode" --version 2>/dev/null \
      | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    log "Binario presente: v${OC_VER:-?}"
  else
    error "Binario no encontrado tras instalación"
  fi

  mark_done "native_install"
fi

# ── PASO 5 — Scripts de control ─────────────────────────────
step "5/$TOTAL_STEPS Creando scripts de control"

if check_done "native_scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  mkdir -p "$OPENCODE_SCRIPTS"

  cat > "$OPENCODE_SCRIPTS/opencode_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="opencode"
PORT=3000
CWD="${1:-$HOME}"
LOG="$HOME/kairos_logs/opencode_web.log"
mkdir -p "$HOME/kairos_logs"

if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[OK] OpenCode ya corriendo — http://127.0.0.1:${PORT}"
  exit 0
fi

# El "2>&1" original solo mezclaba stderr con stdout DENTRO del pane de tmux —
# si "opencode web" moría antes de los 2s de sleep, la sesión se cerraba con
# ella y ese output se perdía para siempre (bug confirmado en dispositivo
# real: [ERROR] sin ninguna pista de la causa, ver log/photo_..._773.jpg y
# docs/humano/humano8.md). Ahora queda en un archivo persistente en disco.
#
# Bug real (2026-08-07, ver docs/humano/humano88.md): "--cwd" no es un flag válido de
# "opencode web" (confirmado con el --help real, log/kairos_logs/opencode_web.log) — yargs
# lo rechaza e imprime --help en vez de arrancar el server, así que :3000 nunca respondía
# (el usuario solo podía usar :4096, que OpenCodeNative.kt arranca sin --cwd). El working
# directory se fija cambiando el cwd del propio shell antes de invocar opencode, igual que
# ya hace el resto de módulos con directorio de proyecto configurable.
tmux new-session -d -s "$SESSION" \
  "cd '$CWD' && BROWSER= opencode web --port $PORT --hostname 127.0.0.1 > '$LOG' 2>&1"

sleep 2
if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[OK] OpenCode iniciado — http://127.0.0.1:${PORT}"
else
  echo "[ERROR] No se pudo iniciar OpenCode — log en ~/kairos_logs/opencode_web.log: $(tail -c 200 "$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$OPENCODE_SCRIPTS/opencode_start.sh"

  # 2026-08-01: OpenCodeFragment.kt (app) ofrece dos botones de servidor web, :3000
  # (sesión tmux "opencode", este script) y :4096 (sesión tmux propia "opencode-4096",
  # ver OpenCodeNative.kt) — este stop solo mataba la sesión "opencode" literal, dejando
  # "opencode-4096" corriendo para siempre sin forma de detenerla desde la UI (bug real
  # reportado: "no se detienen TUI o web"). Ahora mata cualquier sesión que empiece con
  # "opencode" (cualquier puerto), más un pkill de red de seguridad para procesos
  # `opencode web` huérfanos de una sesión ya cerrada.
  cat > "$OPENCODE_SCRIPTS/opencode_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
KILLED=0
while IFS= read -r session; do
  [ -z "$session" ] && continue
  case "$session" in
    opencode|opencode-*) tmux kill-session -t "$session" 2>/dev/null && KILLED=1 ;;
  esac
done < <(tmux list-sessions -F '#{session_name}' 2>/dev/null)
pkill -f 'opencode web' 2>/dev/null

if [ "$KILLED" = "1" ]; then
  echo "[OK] OpenCode detenido"
else
  echo "[OK] OpenCode no estaba corriendo"
fi
SCRIPT
  chmod +x "$OPENCODE_SCRIPTS/opencode_stop.sh"

  log "Scripts de control creados"
  mark_done "native_scripts"
fi

# ── PASO 6 — Aliases + Registry ─────────────────────────────
step "6/$TOTAL_STEPS Finalizando"

if ! check_done "native_aliases"; then
  BASHRC="$HOME/.bashrc"
  [ -f "$BASHRC" ] && grep -v "opencode-web\|opencode-stop\|opencode-status\|opencode-tui\|# OpenCode" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  OpenCode · aliases (nativo)
# ════════════════════════════════
alias opencode-web='bash ~/scripts/opencode/opencode_start.sh'
alias opencode-stop='bash ~/scripts/opencode/opencode_stop.sh'
alias opencode-status='tmux has-session -t opencode 2>/dev/null && echo "OpenCode: ● :3000" || echo "OpenCode: ○ detenido"'
alias opencode-tui='opencode'
ALIASES

  log "Aliases configurados"
  mark_done "native_aliases"
fi

OC_VER_FINAL=$("$TERMUX_PREFIX/bin/opencode" --version 2>/dev/null \
  | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
[ -z "$OC_VER_FINAL" ] && OC_VER_FINAL="${OC_RELEASE_VER:-unknown}"
update_registry "$OC_VER_FINAL" "termux_native"

rm -rf "$DL_DIR" "$CHECKPOINT.data"
rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  OpenCode nativo instalado ✓${NC}"
  echo "  Versión: v${OC_VER_FINAL}"
  echo "  Puerto:  3000"
  echo ""
fi

notify_event "opencode" "install_done" "$OC_VER_FINAL"
log "Instalación de OpenCode completada"
exit 0
