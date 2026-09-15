#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · opencode.sh (silent mode)
#  Instala OpenCode en Termux ARM64 — 2 vías reales + fallback universal
#
#  USO DESDE APP (KairosApp):
#    bash opencode.sh --silent
#    bash opencode.sh --silent --variant glibc   (default)
#    bash opencode.sh --silent --variant bionic
#
#  USO MANUAL (standalone):
#    bash install_opencode.sh
#
#  FLAGS:
#    --silent              Sin preguntas, instala todo directo
#    --force                Reinstala aunque ya esté
#    --variant <glibc|bionic>  Vía de instalación (default: glibc)
#
#  QUÉ INSTALA (2026-09-09, ver docs/humano/ ronda "rediseño OpenCode 2 vías"):
#    --variant glibc  (default, la vía de siempre, SIN cambios de lógica):
#      ✅ glibc + openssl-glibc + ncurses (paquetes de Termux, no proot)
#      ✅ Binario desde github.com/Honkonx/opencode-termux, rama pure-android
#         (.pkg.tar.xz con fallback a .deb)
#    --variant bionic (nueva — ELF Bionic nativo, SIN glibc):
#      ✅ Binario desde github.com/Honkonx/opencode-termux, rama native-android
#         (si esa rama todavía no tiene releases propios, cae al fallback de abajo)
#    Fallback universal (automático, para CUALQUIERA de las 2 vías si falla):
#      ✅ Binario Bionic nativo desde github.com/wallentx/opencode-termux
#         (upstream real — el usuario pidió forkearlo, pero esta sesión no puede
#         crear forks de GitHub sin credenciales; apunta al original por ahora,
#         ver constantes WALLENTX_OWNER/WALLENTX_REPO más abajo)
#    ✅ Scripts: opencode_start.sh, opencode_stop.sh (iguales para ambas vías)
#    ✅ Aliases en .bashrc
#    ✅ Registry actualizado (incluye "variant=" con la vía que terminó funcionando)
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
#  2026-09-09: rediseño de 2 vías + fallback (ver docs/referencias/modulos/
#  AUDITORIA_OPENCODE_TERMUX_2026-09-09.md). Hallazgo real: el upstream real
#  de OpenCode-Termux (wallentx/opencode-termux) abandonó el formato glibc/
#  Termux hace más de un mes y pasó a un ELF Bionic nativo parcheado (Bun
#  compilado con NDK + relocation ELF manual, ver auditoría). El fork propio
#  (Honkonx/opencode-termux) ya tiene una rama "native-android" creada para
#  seguir esa migración, pero a esta fecha esa rama TODAVÍA NO PUBLICÓ ningún
#  release real — confirmado contra la API de GitHub (releases/target_commitish),
#  y el único workflow que existe ahí (.github/workflows/build-native-android.yml)
#  es explícitamente diagnóstico ("NOT a release path", según su propio
#  comentario) — no genera un asset descargable. Por eso la vía "bionic" del
#  fork propio, hoy, siempre cae al fallback de wallentx en la práctica — el
#  código queda listo para cuando el fork publique una release real ahí.
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 5.0.0 | Septiembre 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
VARIANT="glibc"

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --variant)  shift; VARIANT="$1" ;;
  esac
  shift
done

# Cualquier valor desconocido cae al default en vez de romper el flujo — mismo
# criterio tolerante que el resto de módulos de modulos/ (nunca error() por un
# --variant mal tipeado, solo se normaliza).
case "$VARIANT" in
  glibc|bionic) ;;
  *) VARIANT="glibc" ;;
esac

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"opencode","supports_silent":true,"supports_force":true,"variants":["glibc","bionic"],"variant_required":false,"note":"glibc = Honkonx/opencode-termux rama pure-android (paquete .pkg.tar.xz/.deb, requiere glibc-repo). bionic = Honkonx/opencode-termux rama native-android (ELF Bionic nativo, sin glibc); si esa rama no tiene releases, cae automáticamente al fallback wallentx/opencode-termux (también Bionic nativo). Cualquiera de las 2 vías cae al mismo fallback si falla."}
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
# Fallback universal — upstream real, confirmado Bionic nativo (ver auditoría citada
# arriba). El usuario pidió forkearlo a Honkonx/ también, pero esta sesión no tiene forma
# de crear forks de GitHub sin credenciales — apunta al repo original por ahora. Si en el
# futuro existe un fork propio, cambiar SOLO estas 2 constantes.
WALLENTX_OWNER="wallentx"
WALLENTX_REPO="opencode-termux"
DL_DIR="$HOME/.opencode_install_tmp"
_OC_UA="kairos-app/opencode-installer (+https://github.com/Honkonx/kairos-lab)"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  local location="$2"
  local variant="${3:-glibc}"
  registry_install opencode "$version" "location=$location" "port=3000" "variant=$variant"
}

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Reemplaza el manifest a mano modulos/manifests/opencode.json (borrado
# como código muerto en humano165, ver docs/arquitectura/MODULEDEB_GENERICO.md).
# Contenido migrado 1:1 del manifest piloto original
# (git show 838544d^:modulos/manifests/opencode.json).
# NOTA (2026-09-09): este bloque sigue describiendo únicamente los archivos de
# la vía glibc (paths de $TERMUX_PREFIX/lib/opencode) — si la variante REAL
# instalada es bionic/bionic-wallentx, el campo "variant" de abajo lo refleja
# igual (honesto, leído del registry) pero el resto del manifest (files[]/
# dependencies[]) sigue asumiendo glibc; moduledeb pack fallará limpio por
# archivos requeridos faltantes en ese caso, no produce un .deb mal armado —
# ver docs/arquitectura/MODULEDEB_GENERICO.md para el manifest bionic propio
# pendiente.
#
# La variante real se lee de "opencode.variant" en el registry (glibc|bionic|
# bionic-wallentx, escrito por update_registry() — ver PASO 2 más abajo) en
# vez de hardcodear "glibc" — mismo patrón que ollama.sh/ollama.install_mode
# (2026-09-11, ver MEJORAS_PENDIENTES.md "moduledeb: variant en nombre de .deb").
if $DESCRIBE_FILES; then
  _df_registry="$HOME/.android_server_registry"
  _df_variant=$(grep -m1 '^opencode\.variant=' "$_df_registry" 2>/dev/null | cut -d= -f2 | tr -d '\r\n')
  [ -z "$_df_variant" ] && _df_variant="null" || _df_variant="\"$_df_variant\""
  jq -n \
    --argjson variant "$_df_variant" \
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
      variant: $variant,
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
        "No hay parche real conocido para OpenCode (a diferencia de Claude native) — patch_cmd es solo re-chmod, no una reparación funcional",
        "Describe únicamente la vía --variant glibc (default) — la vía bionic/wallentx (2026-09-09) no tiene manifest propio todavía"
      ]
    }'
  exit 0
fi

# ── Verificar si ya está instalado ────────────────────────────
if command -v opencode &>/dev/null && ! $FORCE; then
  log "OpenCode ya instalado — $(opencode --version 2>/dev/null | head -1)"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT" "$CHECKPOINT.data.glibc" "$CHECKPOINT.data.bionic" "$CHECKPOINT.data.wallentx"

# ── Modo manual: confirmación ─────────────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · OpenCode Installer      ║
  ║   ARM64 · sin root · v5.0.0                ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Instala OpenCode (variante: ${VARIANT})."
  echo "  Si esa vía falla, cae automáticamente a un binario Bionic universal."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=4

# ── Helpers compartidos por las 3 vías (glibc/bionic/wallentx) ─────────
# DRY — antes esta lógica de descarga estaba duplicada línea por línea entre
# PASO 3 (paquete) y no existía todavía para bionic/wallentx.
_oc_download() {
  local _url="$1" _out="$2" _ok=false
  if command -v curl &>/dev/null; then
    curl -fL --max-time 120 "$_url" -o "$_out" && _ok=true
  fi
  if ! $_ok && command -v wget &>/dev/null; then
    wget --timeout=120 "$_url" -O "$_out" && _ok=true
  fi
  $_ok && [ -s "$_out" ]
}

# Fetch genérico de un JSON de la API de GitHub, con 1 reintento — usado por
# las resoluciones de bionic/wallentx (la de glibc mantiene su propio fetch
# con diagnóstico 403/rate-limit detallado, ver install_opencode_glibc()).
_oc_fetch_json() {
  local _url="$1" _out="$2" _ok=false _attempt
  for _attempt in 1 2; do
    if command -v curl &>/dev/null; then
      curl -sSL --max-time 30 -H "User-Agent: ${_OC_UA}" -o "$_out" "$_url" 2>/dev/null && _ok=true
    fi
    if ! $_ok && command -v wget &>/dev/null; then
      wget -q --timeout=30 --header="User-Agent: ${_OC_UA}" "$_url" -O "$_out" 2>/dev/null && _ok=true
    fi
    $_ok && [ -s "$_out" ] && return 0
    _ok=false
    [ "$_attempt" = "1" ] && sleep 2
  done
  return 1
}

# Instala un asset Bionic nativo (bionic/wallentx) — formato del archivo
# descubierto en runtime por extensión: .tar.gz/.tgz o .tar.xz se extraen y se
# busca dentro un archivo llamado "opencode" (o, si no hay ninguno con ese
# nombre exacto, el primer archivo regular encontrado); cualquier otra
# extensión se asume que ES el binario crudo. Confirmado empíricamente
# (2026-09-09, `tar -tzf` contra el asset real de wallentx v1.18.30-termux,
# ver docs/referencias/modulos/AUDITORIA_OPENCODE_TERMUX_2026-09-09.md): el
# .tar.gz de wallentx es un único archivo plano llamado "opencode" en la raíz,
# sin prefijo de directorio (ni "bin/" ni "usr/").
_oc_install_bionic_asset() {
  local _url="$1" _dl="$DL_DIR/opencode_bionic_asset"
  mkdir -p "$DL_DIR"
  _oc_download "$_url" "$_dl" || return 1

  local _bin=""
  case "$_url" in
    *.tar.gz|*.tgz)
      local _extract="$DL_DIR/bionic_extract"
      rm -rf "$_extract"; mkdir -p "$_extract"
      tar -xzf "$_dl" -C "$_extract" || { rm -f "$_dl"; return 1; }
      _bin=$(find "$_extract" -type f -name "opencode" 2>/dev/null | head -1)
      [ -z "$_bin" ] && _bin=$(find "$_extract" -maxdepth 3 -type f 2>/dev/null | head -1)
      ;;
    *.tar.xz)
      local _extract="$DL_DIR/bionic_extract"
      rm -rf "$_extract"; mkdir -p "$_extract"
      tar -xJf "$_dl" -C "$_extract" || { rm -f "$_dl"; return 1; }
      _bin=$(find "$_extract" -type f -name "opencode" 2>/dev/null | head -1)
      [ -z "$_bin" ] && _bin=$(find "$_extract" -maxdepth 3 -type f 2>/dev/null | head -1)
      ;;
    *)
      _bin="$_dl"
      ;;
  esac

  [ -n "$_bin" ] && [ -f "$_bin" ] || { rm -rf "$DL_DIR/bionic_extract" "$_dl"; return 1; }
  cp "$_bin" "$TERMUX_PREFIX/bin/opencode" || { rm -rf "$DL_DIR/bionic_extract" "$_dl"; return 1; }
  chmod 755 "$TERMUX_PREFIX/bin/opencode" 2>/dev/null
  rm -rf "$DL_DIR/bionic_extract" "$_dl"
  return 0
}

# ── PASO 1 — Dependencias glibc (solo si --variant glibc) ─────
step "1/$TOTAL_STEPS Instalando dependencias"

if [ "$VARIANT" = "glibc" ]; then
  if check_done "deps_glibc"; then
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

    timeout "$TIMEOUT_PKG" pkg update -y || true

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

    mark_done "deps_glibc"
  fi
else
  log "Variante Bionic — sin dependencias glibc (binario ELF nativo Android, no necesita glibc-repo/openssl-glibc/ncurses)"
fi

# ── Vía glibc — Honkonx/opencode-termux, rama pure-android ─────────────
# Lógica ORIGINAL del script (resolver release + descargar + extraer
# .pkg.tar.xz/.deb) SIN cambios de comportamiento — el único cambio real acá
# es que los "error()" (exit duro) pasan a "warn()+return 1" para permitir el
# fallback automático a wallentx pedido por el usuario (antes esta vía era la
# única y un fallo terminaba el script entero).
install_opencode_glibc() {
  mkdir -p "$DL_DIR"
  local _pkg_url="" _ver="" _fmt="pkg.tar.xz"

  if check_done "resolved_glibc"; then
    _pkg_url=$(grep "^_oc_pkg_url=" "$CHECKPOINT.data.glibc" 2>/dev/null | cut -d'=' -f2-)
    _ver=$(grep "^_oc_ver=" "$CHECKPOINT.data.glibc" 2>/dev/null | cut -d'=' -f2-)
    _fmt=$(grep "^_oc_fmt=" "$CHECKPOINT.data.glibc" 2>/dev/null | cut -d'=' -f2-)
    log "Versión resuelta [checkpoint]: v${_ver} (glibc)"
  else
    info "Consultando GitHub API: ${FORK_OWNER}/${FORK_REPO} (glibc)..."

    local _release_json="$DL_DIR/release_glibc.json"

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
    local _download_ok=false _http_code="" _curl_exit="" _attempt
    for _attempt in 1 2; do
      if command -v curl &>/dev/null; then
        _http_code=$(curl -sSL --max-time 30 \
          -H "User-Agent: ${_OC_UA}" \
          -o "$_release_json" -w '%{http_code}' \
          "$GITHUB_API" 2>/dev/null)
        _curl_exit=$?
        [ "$_curl_exit" = "0" ] && [ "$_http_code" = "200" ] && _download_ok=true
      fi
      if ! $_download_ok && command -v wget &>/dev/null; then
        wget -q --timeout=30 --header="User-Agent: ${_OC_UA}" \
          "$GITHUB_API" -O "$_release_json" 2>/dev/null && _download_ok=true
      fi
      $_download_ok && [ -s "$_release_json" ] && grep -q '"tag_name"' "$_release_json" 2>/dev/null && break
      _download_ok=false
      [ "$_attempt" = "1" ] && { warn "Descarga de metadata incompleta, reintentando..."; sleep 2; }
    done

    if ! $_download_ok || [ ! -s "$_release_json" ]; then
      if [ "$_http_code" = "403" ] && grep -qi "rate limit" "$_release_json" 2>/dev/null; then
        warn "GitHub rechazó la petición por límite de tasa sin autenticar (60/hora por IP) — NO es un problema de conexión. Esperá unos minutos y reintentá."
      elif [ "$_http_code" = "403" ]; then
        warn "GitHub rechazó la petición (HTTP 403) — NO es un problema de conexión real. Respuesta: $(head -c 200 "$_release_json" 2>/dev/null | tr -d '\n')"
      elif [ -n "$_http_code" ] && [ "$_http_code" != "000" ]; then
        warn "La API de GitHub respondió con HTTP ${_http_code}. Respuesta: $(head -c 200 "$_release_json" 2>/dev/null | tr -d '\n')"
      else
        warn "No se pudo consultar la API de GitHub (vía glibc). Verifica conexión."
      fi
      return 1
    fi

    local _tag
    _tag=$(grep -o '"tag_name": *"[^"]*"' "$_release_json" | head -1 | cut -d'"' -f4)
    _ver=$(echo "$_tag" | sed 's/^v//')

    # Buscar .pkg.tar.xz aarch64
    _pkg_url=$(grep -o '"browser_download_url": *"[^"]*"' "$_release_json" \
      | grep "aarch64.*\.pkg\.tar\.xz" | head -1 | cut -d'"' -f4)
    _fmt="pkg.tar.xz"

    # Fallback: .deb
    if [ -z "$_pkg_url" ]; then
      _pkg_url=$(grep -o '"browser_download_url": *"[^"]*"' "$_release_json" \
        | grep "aarch64.*\.deb" | head -1 | cut -d'"' -f4)
      _fmt="deb"
    fi

    rm -f "$_release_json"

    if [ -z "$_pkg_url" ]; then
      warn "No se encontró binario aarch64 en release '${_tag}' (${FORK_OWNER}/${FORK_REPO}, vía glibc)."
      return 1
    fi

    log "Release: ${_tag} (${_fmt}, glibc)"

    cat > "$CHECKPOINT.data.glibc" << EOF
_oc_pkg_url=${_pkg_url}
_oc_ver=${_ver}
_oc_fmt=${_fmt}
EOF
    mark_done "resolved_glibc"
  fi

  # ── Descargar paquete ──
  local _pkg_file=""
  if check_done "downloaded_glibc"; then
    _pkg_file=$(ls "$DL_DIR"/opencode*.${_fmt##*.} 2>/dev/null \
      | grep -E "aarch64\.(pkg\.tar\.xz|deb)$" | head -1)
    if [ -z "$_pkg_file" ] || [ ! -f "$_pkg_file" ]; then
      warn "Archivo glibc no encontrado — re-descargando"
      grep -v "^downloaded_glibc$" "$CHECKPOINT" > "$CHECKPOINT.tmp" 2>/dev/null && mv "$CHECKPOINT.tmp" "$CHECKPOINT"
    fi
  fi

  if ! check_done "downloaded_glibc"; then
    [[ "$_fmt" == "pkg.tar.xz" ]] && _pkg_file="$DL_DIR/opencode-${_ver}-1-aarch64.pkg.tar.xz"
    [[ "$_fmt" == "deb" ]] && _pkg_file="$DL_DIR/opencode_${_ver}_aarch64.deb"

    info "Descargando desde GitHub Releases (glibc)..."
    if ! _oc_download "$_pkg_url" "$_pkg_file"; then
      rm -f "$_pkg_file"
      warn "Descarga glibc fallida. Verifica conexión."
      return 1
    fi

    log "Descargado: $(du -sh "$_pkg_file" | cut -f1)"
    mark_done "downloaded_glibc"
  fi

  # ── Instalar binario ──
  if check_done "installed_glibc"; then
    log "Instalación glibc ya completada [checkpoint]"
  else
    case "$_fmt" in
      pkg.tar.xz)
        info "Extrayendo .pkg.tar.xz..."
        local _extract_tmp="$DL_DIR/extract"
        mkdir -p "$_extract_tmp"
        if tar -xJf "$_pkg_file" -C "$_extract_tmp"; then
          if [ -d "$_extract_tmp/usr" ]; then
            cp -r "$_extract_tmp/usr/"* "$TERMUX_PREFIX/" || true
            chmod 755 "$TERMUX_PREFIX/bin/opencode" 2>/dev/null || true
            [ -f "$TERMUX_PREFIX/lib/opencode/runtime/opencode" ] && \
              chmod 755 "$TERMUX_PREFIX/lib/opencode/runtime/opencode" 2>/dev/null || true
            log "Extraído correctamente"
          else
            tar -xJf "$_pkg_file" -C "$TERMUX_PREFIX/" --strip-components=1 || {
              warn "No se pudo extraer el paquete glibc"
              rm -rf "$_extract_tmp"
              return 1
            }
          fi
        else
          warn "Fallo al extraer .pkg.tar.xz"
          rm -rf "$_extract_tmp"
          return 1
        fi
        rm -rf "$_extract_tmp"
        ;;
      deb)
        info "Instalando .deb..."
        if command -v dpkg &>/dev/null; then
          dpkg -i "$_pkg_file" || warn "dpkg advertencias — verificando..."
        elif command -v ar &>/dev/null; then
          local _deb_tmp="$DL_DIR/deb_extract"
          mkdir -p "$_deb_tmp"
          ar x "$_pkg_file" --output="$_deb_tmp" || true
          if [ -f "$_deb_tmp/data.tar.xz" ]; then
            tar -xJf "$_deb_tmp/data.tar.xz" -C "$_deb_tmp/" || true
          elif [ -f "$_deb_tmp/data.tar.gz" ]; then
            tar -xzf "$_deb_tmp/data.tar.gz" -C "$_deb_tmp/" || true
          fi
          [ -d "$_deb_tmp/usr" ] && cp -r "$_deb_tmp/usr/"* "$TERMUX_PREFIX/" || true
          rm -rf "$_deb_tmp"
        else
          warn "No se puede extraer .deb sin ar o dpkg (glibc)."
          return 1
        fi
        chmod 755 "$TERMUX_PREFIX/bin/opencode" 2>/dev/null || true
        ;;
    esac
    mark_done "installed_glibc"
  fi

  # ── Verificación real (empirical-verification-before-fix.md): el binario
  # tiene que CORRER, no solo existir en disco — reemplaza el chequeo viejo
  # (command -v a secas, que loggeaba "funcional" incluso con --version vacío).
  if ! verify_binary_installed opencode; then
    warn "opencode (glibc) instalado pero no ejecuta correctamente — descartando esta vía"
    return 1
  fi

  OC_VER=$(opencode --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$OC_VER" ] && OC_VER="$_ver"
  log "opencode v${OC_VER} funcional (glibc)"
  OC_VER_FINAL_TMP="$OC_VER"
  return 0
}

# ── Vía bionic — Honkonx/opencode-termux, rama native-android ──────────
# Nueva (2026-09-09). A diferencia de la vía glibc, GitHub Releases no tiene
# filtro nativo "por rama" — se consulta la lista COMPLETA de releases y se
# filtra client-side por target_commitish=="native-android" (jq — paquete
# core de Kairos, ver kairos.sh "Paquetes core", ya usado por repo.sh/
# ohmypi.sh/moduledeb.sh con el mismo criterio). Si esa rama todavía no
# publicó ningún release (estado real confirmado 2026-09-09), esta función
# devuelve 1 y el flujo principal cae al fallback wallentx.
install_opencode_bionic() {
  if ! command -v jq &>/dev/null; then
    warn "jq no disponible — no se puede resolver la release Bionic nativa de ${FORK_OWNER}/${FORK_REPO}"
    return 1
  fi

  mkdir -p "$DL_DIR"
  local _url="" _ver=""

  if check_done "resolved_bionic"; then
    _url=$(grep "^_oc_url=" "$CHECKPOINT.data.bionic" 2>/dev/null | cut -d'=' -f2-)
    _ver=$(grep "^_oc_ver=" "$CHECKPOINT.data.bionic" 2>/dev/null | cut -d'=' -f2-)
    log "Versión resuelta [checkpoint]: v${_ver} (bionic)"
  else
    info "Consultando GitHub API: ${FORK_OWNER}/${FORK_REPO} (rama native-android)..."
    local _json="$DL_DIR/release_bionic.json"
    local _api="https://api.github.com/repos/${FORK_OWNER}/${FORK_REPO}/releases"

    if ! _oc_fetch_json "$_api" "$_json"; then
      warn "No se pudo consultar releases de ${FORK_OWNER}/${FORK_REPO} (vía bionic)"
      return 1
    fi

    local _release
    _release=$(jq -c '[.[] | select(.target_commitish=="native-android" and .draft==false)] | .[0]' "$_json" 2>/dev/null)
    rm -f "$_json"

    if [ -z "$_release" ] || [ "$_release" = "null" ]; then
      warn "La rama native-android de ${FORK_OWNER}/${FORK_REPO} todavía no tiene ningún release publicado — sin build Bionic nativa propia disponible (ver docs/referencias/modulos/AUDITORIA_OPENCODE_TERMUX_2026-09-09.md)"
      return 1
    fi

    _ver=$(echo "$_release" | jq -r '.tag_name // empty' | sed 's/^v//')
    _url=$(echo "$_release" | jq -r '[.assets[] | select(.name | test("aarch64|arm64"; "i")) | select(.name | test("sha256|\\.sig$|\\.asc$"; "i") | not)][0].browser_download_url // empty')

    if [ -z "$_url" ]; then
      warn "Release native-android encontrado (v${_ver}) pero sin asset aarch64/arm64 reconocible"
      return 1
    fi

    log "Release: v${_ver} (bionic, native-android)"
    cat > "$CHECKPOINT.data.bionic" << EOF
_oc_url=${_url}
_oc_ver=${_ver}
EOF
    mark_done "resolved_bionic"
  fi

  if ! check_done "installed_bionic"; then
    info "Descargando e instalando binario Bionic nativo..."
    if ! _oc_install_bionic_asset "$_url"; then
      warn "Descarga/extracción del binario Bionic (native-android) falló"
      return 1
    fi
    mark_done "installed_bionic"
  fi

  if ! verify_binary_installed opencode; then
    warn "opencode (bionic/native-android) instalado pero no ejecuta correctamente — descartando esta vía"
    return 1
  fi

  OC_VER=$(opencode --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$OC_VER" ] && OC_VER="$_ver"
  log "opencode v${OC_VER} funcional (bionic)"
  OC_VER_FINAL_TMP="$OC_VER"
  return 0
}

# ── Fallback universal — wallentx/opencode-termux (upstream real) ──────
# Se usa cuando CUALQUIERA de las 2 vías de arriba falla (pedido explícito del
# usuario) — también es un binario Bionic nativo (sin glibc), así que sirve de
# red de seguridad tanto para "bionic" (mismo mecanismo, otro repo) como para
# "glibc" (si glibc-repo/el paquete .pkg.tar.xz falla, este binario corre igual
# sin depender de glibc en absoluto). Formato de asset confirmado empíricamente
# 2026-09-09 (ver install_opencode_bionic() arriba y la auditoría citada):
# release "latest" con un único asset "opencode-android-arm64.tar.gz".
install_opencode_wallentx() {
  mkdir -p "$DL_DIR"
  local _url="" _ver=""

  if check_done "resolved_wallentx"; then
    _url=$(grep "^_oc_url=" "$CHECKPOINT.data.wallentx" 2>/dev/null | cut -d'=' -f2-)
    _ver=$(grep "^_oc_ver=" "$CHECKPOINT.data.wallentx" 2>/dev/null | cut -d'=' -f2-)
    log "Versión resuelta [checkpoint]: v${_ver} (wallentx fallback)"
  else
    info "Consultando GitHub API: ${WALLENTX_OWNER}/${WALLENTX_REPO} (fallback universal)..."
    local _json="$DL_DIR/release_wallentx.json"
    local _api="https://api.github.com/repos/${WALLENTX_OWNER}/${WALLENTX_REPO}/releases/latest"

    if ! _oc_fetch_json "$_api" "$_json"; then
      warn "No se pudo consultar releases de ${WALLENTX_OWNER}/${WALLENTX_REPO} (fallback)"
      return 1
    fi

    if command -v jq &>/dev/null; then
      _ver=$(jq -r '.tag_name // empty' "$_json" 2>/dev/null | sed 's/^v//')
      _url=$(jq -r '[.assets[] | select(.name | test("arm64|aarch64"; "i")) | select(.name | test("sha256|\\.sig$|\\.asc$"; "i") | not)][0].browser_download_url // empty' "$_json" 2>/dev/null)
    else
      _ver=$(grep -o '"tag_name": *"[^"]*"' "$_json" | head -1 | cut -d'"' -f4 | sed 's/^v//')
      _url=$(grep -o '"browser_download_url": *"[^"]*"' "$_json" | grep -iE "arm64|aarch64" | grep -viE "sha256|\.sig$|\.asc$" | head -1 | cut -d'"' -f4)
    fi
    rm -f "$_json"

    if [ -z "$_url" ]; then
      warn "No se encontró asset arm64/aarch64 en el último release de ${WALLENTX_OWNER}/${WALLENTX_REPO}"
      return 1
    fi

    log "Release: v${_ver} (wallentx, fallback)"
    cat > "$CHECKPOINT.data.wallentx" << EOF
_oc_url=${_url}
_oc_ver=${_ver}
EOF
    mark_done "resolved_wallentx"
  fi

  if ! check_done "installed_wallentx"; then
    info "Descargando e instalando binario Bionic nativo (wallentx)..."
    if ! _oc_install_bionic_asset "$_url"; then
      warn "Descarga/extracción del binario wallentx falló"
      return 1
    fi
    mark_done "installed_wallentx"
  fi

  if ! verify_binary_installed opencode; then
    warn "opencode (wallentx fallback) instalado pero no ejecuta correctamente"
    return 1
  fi

  OC_VER=$(opencode --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$OC_VER" ] && OC_VER="$_ver"
  log "opencode v${OC_VER} funcional (wallentx fallback)"
  OC_VER_FINAL_TMP="$OC_VER"
  return 0
}

# ── PASO 2 — Instalar OpenCode (variante elegida + fallback automático) ─
step "2/$TOTAL_STEPS Instalando OpenCode (variante: ${VARIANT})"

OC_INSTALL_OK=false
OC_VARIANT_FINAL=""
OC_VER_FINAL_TMP=""

if [ "$VARIANT" = "glibc" ]; then
  if install_opencode_glibc; then
    OC_INSTALL_OK=true
    OC_VARIANT_FINAL="glibc"
  fi
else
  if install_opencode_bionic; then
    OC_INSTALL_OK=true
    OC_VARIANT_FINAL="bionic"
  fi
fi

if ! $OC_INSTALL_OK; then
  warn "Instalación por la vía '${VARIANT}' no se pudo completar — probando fallback universal (wallentx/opencode-termux, Bionic nativo)"
  if install_opencode_wallentx; then
    OC_INSTALL_OK=true
    OC_VARIANT_FINAL="bionic-wallentx"
  fi
fi

$OC_INSTALL_OK || error "No se pudo instalar OpenCode: fallaron tanto la vía '${VARIANT}' como el fallback universal (wallentx). Revisá conexión a internet/almacenamiento y reintentá."

OC_VER_FINAL="$OC_VER_FINAL_TMP"

# ── PASO 3 — Scripts de control ─────────────────────────────
step "3/$TOTAL_STEPS Creando scripts de control"

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

# ── PASO 4 — Aliases + Registry ─────────────────────────────
step "4/$TOTAL_STEPS Finalizando"

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

[ -z "$OC_VER_FINAL" ] && OC_VER_FINAL="unknown"
update_registry "$OC_VER_FINAL" "termux_native" "$OC_VARIANT_FINAL"

rm -rf "$DL_DIR" "$CHECKPOINT.data.glibc" "$CHECKPOINT.data.bionic" "$CHECKPOINT.data.wallentx"
rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  OpenCode instalado ✓${NC}"
  echo "  Versión:  v${OC_VER_FINAL}"
  echo "  Variante: ${OC_VARIANT_FINAL}"
  echo "  Puerto:   3000"
  echo ""
fi

notify_event "opencode" "install_done" "${OC_VER_FINAL} (${OC_VARIANT_FINAL})"
log "Instalación de OpenCode completada (variante: ${OC_VARIANT_FINAL})"
exit 0
