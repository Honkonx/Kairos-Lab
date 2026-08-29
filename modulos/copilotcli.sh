#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · copilotcli.sh (silent mode)
#  Instala GitHub Copilot CLI en Termux ARM64 (vía npm)
#
#  USO DESDE APP (KairosApp):
#    bash copilotcli.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Node.js 22+ (nodejs-lts, si falta)
#    ✅ GitHub Copilot CLI (@github/copilot@latest, npm oficial)
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login (requiere cuenta GitHub con Copilot activo, o GH_TOKEN/GITHUB_TOKEN
#       con permiso "Copilot Requests") — queda para que el usuario haga
#       '/login' manualmente al abrir 'copilot' por primera vez
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (nuevo módulo, candidato de i-Haklab, ver
#  docs/humano/humano99.md — producto oficial de GitHub, github.com/github/copilot-cli)
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
{"id":"copilotcli","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  _bin=$(command -v copilot 2>/dev/null || echo "$TERMUX_PREFIX/bin/copilot")
  _copilot_nm="$(npm root -g 2>/dev/null)/@github"
  jq -n \
    --arg path "$_bin" \
    --arg glob "$_copilot_nm/copilot-android-arm64/**" \
    --arg verify "command -v copilot >/dev/null 2>&1 && copilot --version >/dev/null 2>&1" \
    '{
      id: "copilotcli",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-copilotcli",
      version_registry_key: "copilotcli.version",
      files: [{path: $path, required: true, note: "Wrapper/binario real de copilot (@github/copilot), resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob, required: true, note: "paquete de plataforma real que el wrapper carga — copiado a mano desde @github/copilot-linux-arm64 y renombrado a copilot-android-arm64 (Android reporta linux+arm64 pero el loader busca ese nombre exacto), con el binario nativo patcheado via patchelf --set-interpreter al loader glibc. Sin este glob, el wrapper de arriba queda roto en un device nuevo."}
      ],
      dependencies: [
        {id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"},
        {id: "glibc_loader", check_cmd: "test -f \"$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1\"", install_hint: "pkg install -y glibc-repo && pkg install -y glibc"},
        {id: "patchelf", check_cmd: "test -x \"$TERMUX_PREFIX/glibc/bin/patchelf\"", install_hint: "pkg install -y patchelf-glibc"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "El árbol de node_modules global de npm (@github/copilot en sí, sin contar el paquete de plataforma ya cubierto arriba) no se empaqueta — solo el wrapper/symlink resuelto por PATH"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_copilotcli_checkpoint"
COPILOT_PKG="@github/copilot@latest"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^copilotcli_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "copilotcli_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^copilotcli_${1}=done" "$CHECKPOINT" 2>/dev/null; }


get_installed_ver() {
  command -v copilot &>/dev/null && copilot --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ GITHUB COPILOT CLI — Instalador       ║"
  echo "  ║  GitHub · Termux ARM64                    ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "GitHub Copilot CLI ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar GitHub Copilot CLI? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Node.js ─────────────────────────────────────────
# Copilot CLI exige Node 22+ — nodejs-lts en los repos de Termux ya cumple
# (LTS actual >=22 al momento de escribir esto); si en el futuro deja de
# cumplir, el propio "npm install" abajo va a fallar con un mensaje claro
# de versión mínima, no en silencio.
step "PASO 1 — Verificando Node.js (22+)"
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

# ── PASO 2 — npm install ─────────────────────────────────────
step "PASO 2 — Instalando GitHub Copilot CLI vía npm"
if check_done "npm_install"; then
  log "GitHub Copilot CLI ya instalado [checkpoint]"
else
  info "Ejecutando: npm install -g ${COPILOT_PKG}"
  npm install -g "$COPILOT_PKG" 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
  # Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano184.md): el symlink npm no
  # ejecuta directo en este dispositivo — mismo patrón que explica el "version=?" ya visto acá.
  fix_npm_shebang_wrapper "copilot" "${COPILOT_PKG%@latest}"
  # Bug real confirmado (auditoría ADB 2026-08-22, ver docs/humano/humano193.md, bug #29): el
  # wrapper puede existir en PATH sin ejecutar de verdad — npm salta
  # @github/copilot-linux-arm64 (el optionalDependency de plataforma) porque Android reporta
  # process.platform="android", no "linux". "copilot --version" imprime "no platform package
  # found" en ese caso.
  #
  # CORRECCIÓN 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB — una nota
  # anterior acá decía "@github/copilot no publica un paquete nativo para esta plataforma",
  # FALSO: confirmado leyendo el package.json real instalado, "@github/copilot-linux-arm64" SÍ
  # existe y SÍ se publica en npm. 3 capas de bug encontradas y arregladas en cadena (misma
  # investigación real, no supuesta):
  #  1. npm excluye el optionalDependency por el campo "os" del package.json ("linux", no
  #     "android") — se instala a mano con --force, mismo patrón que codebuff/kilo.
  #  2. npm-loader.js (minificado, de @github/copilot) resuelve el paquete nativo con
  #     `import.meta.resolve("@github/copilot-${process.platform}-${process.arch}")` — SOLO
  #     mapea "linux"→["linuxmusl","linux"], para CUALQUIER otra plataforma (incluida
  #     "android") usa el string literal tal cual, sin fallback a "linux". Nunca iba a
  #     encontrar el paquete "-linux-arm64" instalado, sin importar qué tan bien instalado
  #     estuviera. Fix: copiar ese paquete a un directorio nuevo llamado
  #     "@github/copilot-android-arm64" (el nombre que el loader SÍ busca en este device) +
  #     corregir el campo "name" de su package.json (Node valida el nombre interno, un
  #     symlink/copia con el package.json viejo no resuelve).
  #  3. El binario ELF dentro de ese paquete está linkeado contra glibc ("libc":["glibc"] en
  #     su propio package.json) — mismo patrón que TODOS los binarios nativos de este proyecto
  #     (codebuff/kilo/mimocode/cursor): necesita patchelf --set-interpreter al loader glibc
  #     de Termux antes de poder ejecutar sobre Bionic.
  # Antes acá había un `error()` inmediato apenas fallaba `copilot --version` — cortaba el
  # script antes de siquiera intentar nada de esto.
  if ! copilot --version &>/dev/null; then
    info "copilot no ejecuta (falta el paquete de plataforma) — instalando @github/copilot-linux-arm64 a mano..."
    _COPILOT_NM="$(npm root -g 2>/dev/null)/@github"
    _COPILOT_VER=$(node -e "console.log(require('$_COPILOT_NM/copilot/package.json').version)" 2>/dev/null)
    if [ -n "$_COPILOT_VER" ]; then
      npm install -g "@github/copilot-linux-arm64@${_COPILOT_VER}" --force 2>&1 | tail -5
    fi
    if [ -d "$_COPILOT_NM/copilot-linux-arm64" ]; then
      rm -rf "$_COPILOT_NM/copilot-android-arm64"
      cp -r "$_COPILOT_NM/copilot-linux-arm64" "$_COPILOT_NM/copilot-android-arm64"
      node -e "
        const fs=require('fs');
        const p='$_COPILOT_NM/copilot-android-arm64/package.json';
        const j=JSON.parse(fs.readFileSync(p,'utf8'));
        j.name='@github/copilot-android-arm64';
        fs.writeFileSync(p, JSON.stringify(j,null,2));
      " 2>/dev/null
      # glibc/patchelf pueden no estar instalados todavía en un device limpio (este módulo es
      # npm-only, no los instalaba antes) — se instalan acá si faltan, mismo patrón que
      # freebuff.sh/kilo.sh/mimocode.sh.
      if [ ! -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || [ ! -x "$TERMUX_PREFIX/glibc/bin/patchelf" ]; then
        info "Instalando glibc + patchelf (necesarios para el binario nativo de plataforma)..."
        pkg_update_with_fallback
        pkg install -y glibc-repo 2>/dev/null || true
        pkg install -y glibc patchelf-glibc \
          -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>/dev/null || \
          warn "No se pudo instalar glibc/patchelf — el binario nativo de plataforma puede no ejecutar"
      fi
      if [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] && [ -x "$TERMUX_PREFIX/glibc/bin/patchelf" ]; then
        "$TERMUX_PREFIX/glibc/bin/patchelf" --set-interpreter "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" \
          "$_COPILOT_NM/copilot-android-arm64/copilot" 2>/dev/null
        chmod +x "$_COPILOT_NM/copilot-android-arm64/copilot" 2>/dev/null
      fi
    fi
    copilot --version &>/dev/null || error "copilot no ejecuta tras instalar @github/copilot-linux-arm64 (revisá manualmente: copilot --version)"
    log "copilot ejecuta correctamente tras instalar el paquete de plataforma"
  fi
  log "GitHub Copilot CLI instalado"
  mark_done "npm_install"
fi

# ── PASO 3 — Login (omitido en modo silencioso) ──────────────
step "PASO 3 — Autenticación"
if check_done "login"; then
  log "Login ya completado [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — login omitido, ejecutá 'copilot' y usá '/login', o exportá GH_TOKEN/GITHUB_TOKEN"
  mark_done "login"
else
  warn "Ejecutá 'copilot' y usá el comando '/login' para autenticarte"
  mark_done "login"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write copilotcli \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=npm" \
  "install_date=${_DATE}"

notify_event "copilotcli" "install_done" "$_VER_FINAL"
log "GitHub Copilot CLI instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0
