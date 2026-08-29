#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · openclaw.sh (silent mode)
#  Instala OpenClaw en Termux ARM64 (sin root) — SOLO nativo
#
#  USO DESDE APP (KairosApp):
#    bash openclaw.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_openclaw.sh
#
#  FLAGS:
#    --silent               Sin preguntas, instala todo directo
#    --force                Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ glibc-runner + Node v22 linux-arm64
#    ✅ openclaw vía npm --ignore-scripts + postinstall propio a mano
#    ✅ koffi stub, clipboard stub, patches Android
#    ✅ glibc-compat.js (os.networkInterfaces + homedir)
#    ✅ openclaw update (best-effort, compila módulos nativos como sharp)
#    ✅ Scripts de control + aliases
#    ✅ Registry actualizado
#
#  NO HACE:
#    ❌ Onboard/wizard (lo maneja la app después)
#    ❌ Iniciar gateway (lo maneja la app después)
#
#  2026-07-24: rama proot removida — ya no es necesaria (NVM+Debian
#  en proot), la variante nativa (glibc-runner) es la correcta y la
#  única mantenida, igual que en termux-ai-stack/actu ai-stack/
#  install_openclaw.sh. La rama proot queda archivada en
#  termux-ai-stack/proot-legacy/install_openclaw_proot.sh.
#
#  2026-08-14: PASO 8 (Finalizando) incluye ahora "openclaw update" best-effort
#  — hallazgo #2 de docs/referencias/REFERENCIA_OPENCLAW_ANDROID_MAIN.md
#  (openclaw-android-main lo corre tras instalar, comentario original: "builds
#  native modules like sharp"). Como Kairos instala con --ignore-scripts (bug
#  node-gyp-build sin compilador), los módulos nativos (ej. sharp) quedan sin
#  construir; "openclaw update" es el paso que los compila/repara. Se ejecuta
#  con timeout y NUNCA aborta la instalación.
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 5.0.0 | Julio 2026
# ============================================================

set -euo pipefail

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$HOME/.local/bin:$HOME/.openclaw-android/bin:$HOME/.npm-global/bin:$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Constantes ────────────────────────────────────────────────
GLIBC_LD="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
GLIBC_NODE_DIR="$HOME/.openclaw-android/node"
GLIBC_BIN_DIR="$HOME/.openclaw-android/bin"
# 22.22.0 (versión anterior de esta constante) queda POR DEBAJO del mínimo real
# que exige el propio OpenClaw: "Node 22.22.3+, 24.15+, or 25.9+ ... Node 23 is
# unsupported" (docs.openclaw.ai/install/node, verificado Julio 2026) — el
# gateway no crashea al instalar, arranca y recién ahí rechaza el runtime con
# un "unsupported Node" gateway error. Con 22.22.0 el módulo queda instalado
# pero el gateway nunca funciona, sin importar cuántas veces se reinstale —
# mismo patrón que el bug real encontrado en ollama.sh (paso post-install
# desactualizado respecto al proyecto de origen). 22.23.2 es el último release
# de la línea 22.x LTS (Jod) al momento de este fix, verificado en
# nodejs.org/dist/index.json.
NODE_VERSION_TARGET="22.23.2"
NPM_GLOBAL="$HOME/.npm-global"
NPM_BIN="$NPM_GLOBAL/bin"
LOG_DIR="$HOME/openclaw-logs"
TMP_DIR="$HOME/tmp"
COMPAT_JS="$HOME/.openclaw/glibc-compat.js"
REGISTRY="$HOME/.android_server_registry"
PORT=18789
BASHRC="$HOME/.bashrc"

# Sanea NODE_OPTIONS heredado (.bashrc de una instalación previa) antes de que
# este propio script use node/npm — si trae --require/-r a un COMPAT_JS que ya
# no existe (reinstall/cleanup), cualquier invocación crashea (fix portado de
# install_openclaw.sh, sexta ronda 2026-07-27).
if [ -n "${NODE_OPTIONS:-}" ] && [ ! -f "$COMPAT_JS" ]; then
  case "$NODE_OPTIONS" in
    *"--require $COMPAT_JS"*|*"-r $COMPAT_JS"*)
      NODE_OPTIONS=$(echo "$NODE_OPTIONS" | sed "s#--require $COMPAT_JS##;s#-r $COMPAT_JS##")
      export NODE_OPTIONS
      ;;
  esac
fi

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
{"id":"openclaw","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"proot removido, ver termux-ai-stack/proot-legacy/"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Instalación de varias capas
# propias de Kairos (no paquetes apt genéricos): runtime Node.js v22 propio
# via glibc-runner ($HOME/.openclaw-android/), el paquete npm global openclaw
# completo (con los stubs koffi/clipboard y patches /tmp, /bin/npm aplicados
# IN-PLACE dentro del propio árbol node_modules — ver PASO 4), y los scripts
# de control. file_globs generoso para cada árbol completo, igual criterio
# que codegraph.sh (runtime Node.js completo, no listable archivo por archivo).
if $DESCRIBE_FILES; then
  jq -n \
    --arg glob1 "$HOME/.openclaw-android/**" \
    --arg glob2 "$HOME/.npm-global/lib/node_modules/openclaw/**" \
    --arg glob3 "$HOME/.npm-global/bin/openclaw" \
    --arg glob4 "$HOME/scripts/openclaw/**" \
    --arg p1 "$HOME/.openclaw/glibc-compat.js" \
    --arg verify "command -v openclaw >/dev/null 2>&1 && NODE_OPTIONS= openclaw --version >/dev/null 2>&1" \
    '{
      id: "openclaw",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-openclaw",
      version_registry_key: "openclaw.version",
      files: [{path: $p1, required: true, note: "glibc-compat.js — parcha os.networkInterfaces()/os.homedir() para Android"}],
      file_globs: [
        {pattern: $glob1, required: true, note: "runtime glibc-runner + Node v22 privado (bin/node, bin/npm, bin/npx wrappers + node.real)"},
        {pattern: $glob2, required: true, note: "árbol npm global de openclaw completo, con stubs koffi/clipboard y patches /tmp,/bin/npm ya aplicados"},
        {pattern: $glob3, required: true, note: "wrapper bash real de openclaw (reemplaza el symlink npm, que no ejecuta directo en este dispositivo)"},
        {pattern: $glob4, required: true, note: "scripts de control (openclaw_start.sh, openclaw_stop.sh)"}
      ],
      dependencies: [
        {id: "pkg:glibc-runner", check_cmd: "test -f \"$PREFIX/glibc/lib/ld-linux-aarch64.so.1\"", install_hint: "pkg install -y glibc-repo && pkg install -y glibc-runner patchelf-glibc"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "$HOME/.openclaw/openclaw.json (config real: gateway.mode, credenciales de proveedor de IA elegidas en el wizard openclaw onboard) es dato de usuario — no se empaqueta",
        "$HOME/openclaw-logs/ y $HOME/tmp/ son estado de runtime, no de instalación",
        "El wrapper de systemctl stub ($PREFIX/bin/systemctl) no se lista — vive fuera del árbol propio de openclaw y es compartido/idempotente (no-op)"
      ]
    }'
  exit 0
fi

CHECKPOINT="$HOME/.install_openclaw_native_checkpoint"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1" location="$2"
  registry_install openclaw "$version" "location=$location" "port=$PORT"
}

# ── Verificar si ya está instalado ────────────────────────────
if ! $FORCE; then
  if [ -f "$NPM_BIN/openclaw" ] || command -v openclaw &>/dev/null 2>&1; then
    # Bug real (2026-08-06, ver docs/humano/humano82.md): mismo patrón de "|| true" faltante
    # ya cazado 5 veces en este archivo — grep sin match devuelve 1, y bajo pipefail eso
    # aborta el script silenciosamente incluso con cut() exitoso después.
    _LOC=$(grep "^openclaw\.location=" "$REGISTRY" 2>/dev/null | cut -d'=' -f2) || true
    if [ "$_LOC" = "nativo_termux" ]; then
      log "OpenClaw nativo ya instalado"
      exit 0
    fi
  fi
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: confirmación ─────────────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · OpenClaw Installer      ║
  ║   Nativo ARM64 · sin root · v5.0.0         ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Instala OpenClaw nativo (glibc + npm, sin proot)."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r _CONF < /dev/tty
  [ "$_CONF" != "s" ] && [ "$_CONF" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=8

# Chequeo real de versión mínima (mayor.menor.parche), no solo el major — un
# Node "22.x cualquiera" NO alcanza: OpenClaw exige 22.22.3+ o 24.15+ dentro
# de ese rango y rechaza el resto con un "unsupported Node" gateway error en
# runtime (docs.openclaw.ai/install/node, ver comentario en NODE_VERSION_TARGET
# más arriba). Solo se validan 22.x/24.x porque este wrapper únicamente
# provisiona ese rango (fix ABI 2026-07-26, ver más abajo) — instalaciones
# previas con Node 22.22.0 (por debajo del mínimo real) quedan detectadas como
# insuficientes acá y se re-provisionan solas en el próximo run, sin necesitar
# --force.
# 2026-08-01: el comentario de arriba (y el mensaje de error de PASO 2) ya
# documentaban "22.22.3+, 24.15+, o 25.9+" desde julio, pero el case de abajo
# solo implementaba las ramas 22.x/24.x — cualquier Node 25.x o 26.x (26 es
# hoy el runtime "recomendado por defecto" según docs.openclaw.ai/install/node,
# verificado de nuevo esta ronda) caía en el `*) return 1` y se trataba como
# insuficiente aunque cumpliera el mínimo real. No rompía la instalación (este
# wrapper igual provisiona su propio Node 22.23.2 privado en
# ~/.openclaw-android/node), pero sí hacía que un Node 25/26 ya presente en
# PATH nunca se aceptara — comentario y código quedaban contradictorios entre
# sí. Fix: agregar rama 25.x (25.9+) y aceptar cualquier 26.x+ sin más chequeo.
_node_meets_openclaw_min() {
  local ver="$1" maj min pat
  IFS='.' read -r maj min pat <<< "$ver"
  pat="${pat:-0}"
  case "$maj" in
    22) [ "$min" -gt 22 ] 2>/dev/null || { [ "$min" -eq 22 ] && [ "$pat" -ge 3 ] 2>/dev/null; } ;;
    24) [ "$min" -gt 15 ] 2>/dev/null || { [ "$min" -eq 15 ] && [ "$pat" -ge 0 ] 2>/dev/null; } ;;
    25) [ "$min" -gt 9 ]  2>/dev/null || { [ "$min" -eq 9 ]  && [ "$pat" -ge 0 ] 2>/dev/null; } ;;
    *)  [ "$maj" -ge 26 ] 2>/dev/null ;;
  esac
}

# ── PASO 1 — glibc-runner + Node ─────────────────────────────
step "1/$TOTAL_STEPS Infraestructura glibc + Node"

_ensure_glibc_node() {
  local _NODE_OK=false
  # Rango 22-24: un Node "cualquiera >=22" del sistema puede resolver en una versión
  # mucho más nueva (ej. v26) cuyos cambios de ABI en addons nativos rompen openclaw
  # (fix portado de termux-ai-stack-dev 2026-07-26, confirmado en dispositivo real).
  if command -v node &>/dev/null; then
    local _NV; _NV=$(node --version 2>/dev/null | sed 's/v//')
    if [ -n "$_NV" ] && _node_meets_openclaw_min "$_NV"; then
      # command -v solo confirma que el archivo existe en PATH, no que se pueda
      # ejecutar — un npm de Termux roto/incompleto (nodejs-lts corrupto o
      # pisado a medias por otra instalación) pasaba desapercibido acá y recién
      # explotaba 2 pasos después con "cannot execute: required file not found"
      # (bug confirmado en dispositivo real, ver log/install_openclaw.log).
      command -v npm &>/dev/null && npm --version &>/dev/null && _NODE_OK=true
    fi
  fi
  if ! $_NODE_OK && [ -x "$GLIBC_BIN_DIR/node" ] && [ -x "$GLIBC_BIN_DIR/npm" ]; then
    local _NV2; _NV2=$("$GLIBC_BIN_DIR/node" --version 2>/dev/null | sed 's/v//')
    if [ -n "$_NV2" ] && _node_meets_openclaw_min "$_NV2"; then
      export PATH="$GLIBC_BIN_DIR:$PATH"
      "$GLIBC_BIN_DIR/npm" --version &>/dev/null && _NODE_OK=true
    fi
  fi
  $_NODE_OK && { log "Node.js $(node --version 2>/dev/null) disponible"; return 0; }

  info "Instalando glibc-runner + Node v${NODE_VERSION_TARGET}..."

  if [ ! -f "$GLIBC_LD" ]; then
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc-repo 2>/dev/null || true
    # `|| true`: bajo `set -euo pipefail`, si `pkg update` falla (mirror caído,
    # timeout de red — real en dispositivos, ver historial de fixes de mirrors
    # en este proyecto) pipefail propaga ESE fallo aunque `tail` termine bien,
    # y el script entero abortaba acá sin llegar siquiera al `pkg install
    # glibc-runner` de abajo (que sí tiene su propio `|| error` con mensaje
    # claro). Este `pkg update` es best-effort — la instalación real la valida
    # el `[ -f "$GLIBC_LD" ] || error ...` de más abajo.
    pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1 | tail -2 || true
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y glibc-runner patchelf-glibc \
      -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" || \
      error "No se pudo instalar glibc-runner"
    [ -f "$GLIBC_LD" ] || error "ld.so no encontrado"
    log "glibc-runner instalado"
  fi

  local _GLIBC_ETC="$TERMUX_PREFIX/glibc/etc"
  if [ -d "$_GLIBC_ETC" ] && [ ! -f "$_GLIBC_ETC/hosts" ]; then
    printf '127.0.0.1 localhost localhost.localdomain\n::1 localhost\n' > "$_GLIBC_ETC/hosts"
  fi

  local _NODE_URL="https://nodejs.org/dist/v${NODE_VERSION_TARGET}/node-v${NODE_VERSION_TARGET}-linux-arm64.tar.xz"
  local _NODE_TMP="$HOME/.node-openclaw-dl.tar.xz"
  mkdir -p "$GLIBC_NODE_DIR" "$GLIBC_BIN_DIR"

  info "Descargando Node.js v${NODE_VERSION_TARGET}..."
  curl -fL "$_NODE_URL" -o "$_NODE_TMP" 2>/dev/null || \
    timeout 30 wget -q -O "$_NODE_TMP" "$_NODE_URL" 2>/dev/null || \
    error "Descarga Node.js fallida"
  [ -s "$_NODE_TMP" ] || error "Archivo Node.js vacío"

  tar -xJf "$_NODE_TMP" -C "$GLIBC_NODE_DIR" --strip-components=1 || \
    error "Error extrayendo Node.js"
  rm -f "$_NODE_TMP"

  [ -f "$GLIBC_NODE_DIR/bin/node" ] && [ ! -L "$GLIBC_NODE_DIR/bin/node" ] && \
    mv "$GLIBC_NODE_DIR/bin/node" "$GLIBC_NODE_DIR/bin/node.real"

  cat > "$GLIBC_BIN_DIR/node" << NODEWRAP
#!/data/data/com.termux/files/usr/bin/bash
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD
_OA_COMPAT="\$HOME/.openclaw/glibc-compat.js"
# Sanea NODE_OPTIONS heredado (.bashrc, sesión previa) antes de exec — si trae
# --require/-r a un glibc-compat.js que ya no existe (reinstall/cleanup), CUALQUIER
# invocación de node/npm — incluyendo gateway/onboard/token/TUI de openclaw, que
# pasan todos por este wrapper — crashea intentando requerir un archivo inexistente
# (bug confirmado en dispositivo real, fix portado de install_openclaw.sh, sexta
# ronda 2026-07-27: el wrapper solo AGREGABA el --require si faltaba, nunca
# eliminaba una referencia rota heredada).
if [ -n "\${NODE_OPTIONS:-}" ] && [ ! -f "\$_OA_COMPAT" ]; then
  case "\$NODE_OPTIONS" in
    *"--require \$_OA_COMPAT"*|*"-r \$_OA_COMPAT"*)
      NODE_OPTIONS=\$(echo "\$NODE_OPTIONS" | sed "s#--require \$_OA_COMPAT##;s#-r \$_OA_COMPAT##")
      export NODE_OPTIONS
      ;;
  esac
fi
if [ -f "\$_OA_COMPAT" ]; then
  case "\${NODE_OPTIONS:-}" in
    *"\$_OA_COMPAT"*) ;;
    *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
  esac
fi
exec "$GLIBC_LD" --library-path "$TERMUX_PREFIX/glibc/lib" "$GLIBC_NODE_DIR/bin/node.real" "\$@"
NODEWRAP
  chmod +x "$GLIBC_BIN_DIR/node"

  if [ -f "$GLIBC_NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
    cat > "$GLIBC_BIN_DIR/npm" << NPMWRAP
#!/data/data/com.termux/files/usr/bin/bash
exec "$GLIBC_BIN_DIR/node" "$GLIBC_NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
NPMWRAP
    chmod +x "$GLIBC_BIN_DIR/npm"
  fi
  if [ -f "$GLIBC_NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
    cat > "$GLIBC_BIN_DIR/npx" << NPXWRAP
#!/data/data/com.termux/files/usr/bin/bash
exec "$GLIBC_BIN_DIR/node" "$GLIBC_NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
    chmod +x "$GLIBC_BIN_DIR/npx"
  fi

  ! grep -q "openclaw-android/bin" "$BASHRC" 2>/dev/null && \
    echo 'export PATH="$HOME/.openclaw-android/bin:$PATH"' >> "$BASHRC"
  export PATH="$GLIBC_BIN_DIR:$PATH"

  # Mismo gotcha de bash ya documentado y corregido en PASO 7 más abajo (ver
  # _CL_VER_F): una asignación bare `VAR=$(cmd)` propaga el exit code del
  # comando bajo `set -e` — si el wrapper de node recién armado falla al
  # correr, el script moría ACÁ sin llegar nunca al mensaje explícito
  # "Wrapper de Node falló" de la línea siguiente. `|| true` deja que ese
  # diagnóstico se muestre siempre en vez de un abort silencioso genérico.
  local _VCK; _VCK=$("$GLIBC_BIN_DIR/node" --version 2>/dev/null) || true
  [ -z "$_VCK" ] && error "Wrapper de Node falló"
  log "Node.js $_VCK listo (glibc wrapper)"
}

_ensure_glibc_node

# ── PASO 2 — Verificar Node + npm ─────────────────────────────
step "2/$TOTAL_STEPS Verificando Node.js y npm"

command -v node &>/dev/null || error "Node.js no encontrado"
# `|| true`: mismo patrón ya documentado y corregido 6 veces en este archivo
# (ver NPM_VERSION_CHECK dos líneas más abajo, _CL_VER_F en PASO 7, etc.) —
# bajo `set -euo pipefail`, si "node --version" fallara (binario roto/
# corrupto pese a que `command -v node` sí lo encontró en PATH), pipefail
# abortaría el script en esta misma línea, saltándose el mensaje explícito
# de "Node.js insuficiente" de las líneas siguientes. Faltaba en esta
# instancia aunque el mismo riesgo ya estaba cubierto justo debajo para npm.
NODE_FULL_VER=$(node --version 2>/dev/null | sed 's/v//') || true
{ [ -z "$NODE_FULL_VER" ] || ! _node_meets_openclaw_min "$NODE_FULL_VER"; } && \
  error "Node.js $(node --version) insuficiente — OpenClaw exige 22.22.3+, 24.15+, 25.9+ o 26.x (docs.openclaw.ai/install/node, Node 23 no soportado)"
log "Node.js $(node --version)"

command -v npm &>/dev/null || error "npm no encontrado"
# `|| true`: sin esto, si `npm --version` devuelve código != 0 (npm roto/
# incompleto), la asignación bare `VAR=$(cmd)` dispara `set -e` de inmediato
# (gotcha real de bash: el exit status de una asignación simple ES el del
# comando sustituido) y el script muere ACÁ MISMO, saltándose el mensaje de
# diagnóstico explícito de la línea de abajo — mismo patrón de bug que el de
# PASO 7 (ver _CL_VER_F más abajo), solo que este no afecta al registry.
NPM_VERSION_CHECK=$(npm --version 2>&1) || true
[ -z "$NPM_VERSION_CHECK" ] && error "npm encontrado en PATH pero no ejecuta: $NPM_VERSION_CHECK"
log "npm $NPM_VERSION_CHECK"

mkdir -p "$LOG_DIR" "$TMP_DIR" "$HOME/.openclaw"
export TMPDIR="$TMP_DIR"
npm config set prefix "$NPM_GLOBAL" 2>/dev/null || true
! grep -q "export PATH=$NPM_BIN" "$BASHRC" 2>/dev/null && \
  echo "export PATH=$NPM_BIN:\$PATH" >> "$BASHRC"
export PATH="$NPM_BIN:$PATH"
! grep -q 'max-old-space-size=5632' "$BASHRC" 2>/dev/null && \
  echo 'export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=5632"' >> "$BASHRC"
export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=5632"

# Stub de systemctl — OpenClaw chequea systemd al arrancar (AnyClaw/
# openclaw-android-assistant crea este mismo stub por el mismo motivo, ver
# auditoría de ver/, docs/referencias/REFERENCIA_OPENCLAW_ANDROID_ASSISTANT.md). Termux
# nunca tuvo systemd real, así que un no-op "exit 0" es seguro — no se pudo
# confirmar si esto causa un fallo real hoy, pero es barato y no tiene forma
# de romper nada que ya funcione.
if ! command -v systemctl &>/dev/null; then
  cat > "$TERMUX_PREFIX/bin/systemctl" << 'SYSTEMCTLSTUB'
#!/data/data/com.termux/files/usr/bin/bash
exit 0
SYSTEMCTLSTUB
  chmod +x "$TERMUX_PREFIX/bin/systemctl"
fi

# ── PASO 3 — Instalar openclaw ────────────────────────────────
step "3/$TOTAL_STEPS Instalando OpenClaw"

# El checkpoint es solo una bandera — si el paquete real desapareció desde la
# última corrida exitosa (ej. tras un SIGKILL de Android en otro paso), confiar
# ciegamente en él deja el script fallando para siempre en el mismo error.
# Verificar el artefacto real antes de saltar la instalación (portado de
# install_openclaw.sh, 2026-08-04, ver auditoría de sincronización).
if check_done "n_openclaw_install" && [ ! -d "$NPM_GLOBAL/lib/node_modules/openclaw" ]; then
  warn "Checkpoint 'n_openclaw_install' activo pero el paquete no está en disco — reinstalando"
  grep -v "^n_openclaw_install$" "$CHECKPOINT" > "$CHECKPOINT.tmp" 2>/dev/null && mv "$CHECKPOINT.tmp" "$CHECKPOINT" || true
fi

if check_done "n_openclaw_install"; then
  log "OpenClaw ya instalado [checkpoint]"
else
  # Bug real confirmado (auditoría 2026-08-05, ver docs/humano65.md/humano66.md):
  # "--allow-scripts=<paquete>" NO es una flag real de npm CLI (es un concepto de
  # pnpm, ver "pnpm approve-builds"/"pnpm install --allow-build") — el comentario
  # original de esta sección citaba mal el comportamiento. npm no bloquea lifecycle
  # scripts por defecto, así que TODOS los postinstall del árbol corrían sin filtrar,
  # incluidos los de dependencias transitivas nativas (ej. tree-sitter-bash intentando
  # compilar con "node-gyp-build", que no está en PATH acá — exit 127, y por
  # "set -euo pipefail" el script entero abortaba en el paso 3/8, dejando carpetas
  # creadas pero openclaw nunca instalado — "openclaw no se instala bien" del reporte
  # del usuario). Fix: --ignore-scripts (flag real, bloquea TODO el árbol) + correr a
  # mano el postinstall PROPIO de openclaw después (el hotfix real de baileys que
  # describía el comentario original, ver github.com/openclaw/openclaw
  # scripts/postinstall-bundled-plugins.mjs), sin exponer al resto de dependencias
  # transitivas a sus propios scripts nativos.
  info "npm install -g openclaw@latest --ignore-scripts"
  env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true \
    TMPDIR="$TMP_DIR" \
    npm install -g openclaw@latest --ignore-scripts 2>&1 | tail -10

  _OC_BIN=$(command -v openclaw 2>/dev/null || find "$NPM_BIN" -name "openclaw" 2>/dev/null | head -1)
  [ -z "$_OC_BIN" ] && error "openclaw no encontrado tras instalación"
  log "openclaw instalado: $_OC_BIN"

  _OC_POSTINSTALL=$(find "$NPM_GLOBAL/lib/node_modules/openclaw" -maxdepth 2 \
    \( -iname "postinstall*.mjs" -o -iname "postinstall*.js" \) 2>/dev/null | head -1)
  if [ -n "$_OC_POSTINSTALL" ]; then
    info "Aplicando postinstall real de openclaw ($_OC_POSTINSTALL)..."
    # Riesgo real detectado (auditoría de referencia/, 2026-08-05, ver docs/humano70.md y
    # openclaw-android-main/post-setup.sh): el postinstall real de openclaw puede a su vez
    # instalar/tocar dependencias nativas propias (ej. sharp) que disparen SUS postinstall —
    # mismo patrón de node-gyp-build que ya rompió el paso anterior. npm_config_ignore_scripts
    # también bloquea eso, no solo el "npm install" de arriba.
    npm_config_ignore_scripts=true node "$_OC_POSTINSTALL" 2>&1 | tail -5 || warn "postinstall de openclaw falló — puede seguir funcionando igual"
  else
    warn "No se encontró el postinstall propio de openclaw (hotfix de baileys) — puede no estar aplicado"
  fi

  mark_done "n_openclaw_install"
fi

# Bug real (2026-08-06, ver docs/humano/humano77.md): sin "|| true", bajo
# set -euo pipefail el exit status de esta pipeline es el de "npm list"
# (que devuelve != 0 cuando detecta deps extraneous/missing en el árbol —
# rutinario justo después de instalar con --ignore-scripts), abortando el
# script acá SIN ningún [ERROR] visible. Mismo patrón ya corregido 3 veces
# antes en este archivo (líneas ~306, ~329, ~599) — esta 4ta instancia se
# había perdido en el port desde termux-ai-stack-dev/scripts/install_openclaw.sh
# (que sí tiene el || true, línea 633).
OC_BASE=$(npm list -g openclaw --depth=0 2>/dev/null | grep -oE "/.+/openclaw" | head -1) || true
[ -z "$OC_BASE" ] && OC_BASE="$NPM_GLOBAL/lib/node_modules/openclaw"
[ ! -d "$OC_BASE" ] && error "Directorio openclaw no encontrado: $OC_BASE"

# Bug real confirmado en dispositivo (auditoría ADB 2026-08-21, ver docs/humano/humano183.md y
# docs/humano/humano184.md): el symlink que "npm install -g" genera en "$NPM_BIN/openclaw" (shebang
# "#!/usr/bin/env node") no se puede ejecutar directamente en este dispositivo/Android —
# probable restricción W^X sobre archivos escritos en runtime fuera del $PREFIX normal de
# Termux ("timeout: failed to run command '.../openclaw': No such file or directory" pese a
# que el archivo existe). Invocar el intérprete de Termux de forma explícita sí funciona
# (confirmado en vivo: "node openclaw.mjs --version" corrió perfecto). Fix: reemplazar el
# symlink por un wrapper bash real que llama a node explícitamente con la entrada real
# declarada en package.json (no hardcodear "openclaw.mjs" — el nombre puede cambiar entre
# versiones de upstream).
_OC_ENTRY_REL=$(node -e "try{const p=require('$OC_BASE/package.json');const b=p.bin;console.log(typeof b==='string'?b:(b&&(b.openclaw||Object.values(b)[0]))||'')}catch(e){}" 2>/dev/null) || true
if [ -n "$_OC_ENTRY_REL" ] && [ -f "$OC_BASE/$_OC_ENTRY_REL" ]; then
  rm -f "$NPM_BIN/openclaw"
  cat > "$NPM_BIN/openclaw" << WRAPPER
#!$TERMUX_PREFIX/bin/bash
exec "$TERMUX_PREFIX/bin/node" "$OC_BASE/$_OC_ENTRY_REL" "\$@"
WRAPPER
  chmod +x "$NPM_BIN/openclaw"
  log "Wrapper bash real aplicado a openclaw (el symlink de npm no ejecuta directo en este dispositivo)"
else
  warn "No se pudo determinar el entrypoint real de openclaw para el wrapper — el symlink de npm puede no ejecutar directo en este dispositivo"
fi

# ── Pre-sembrar gateway.mode=local + gateway.auth (token persistente) ───────
# Causa raíz real confirmada 2026-08-25 (ver docs/modulos/OPENCLAW.md sección 12,
# contra referencia/interfaz/openclaw-termux-main/): "openclaw gateway" se niega a
# arrancar — y por lo tanto nunca auto-genera su propio "gateway.auth.token" — si
# el config no tiene "gateway.mode" seteado. Exigir que el usuario corra el wizard
# completo de "openclaw onboard" (que además elige proveedor de IA) antes de dejar
# arrancar el gateway es más estricto de lo que el binario real necesita solo para
# tener token. Se pre-siembra acá, fuera de cualquier checkpoint (idempotente, no
# pisa un config ya existente que no tenga ese campo), para que el gateway arranque
# solo después de instalar, sin bloquear al usuario a completar el wizard primero.
#
# Bug real #2 confirmado por ADB en dispositivo real (2026-08-28, docs/humano278.md/279.md):
# "gateway.mode=local" solo no alcanza para tener un token estable — sin
# "gateway.auth.mode=token" explícito, el gateway genera un token EFÍMERO en cada
# arranque (confirmado en runtime.log real: "auth token was missing. Generated a
# runtime token for this startup without changing config; restart will generate a
# different token. Persist one with `openclaw config set gateway.auth.mode token`
# and `openclaw config set gateway.auth.token <token>`") — nunca se persiste ni se
# imprime en ningún log que Kairos pueda leer, así que la UI mostraba el gateway
# "iniciado" pero sin token nunca visible. Fix: pre-sembrar también
# gateway.auth.mode=token + gateway.auth.token=<hex aleatorio> — mismo mecanismo que
# OpenClawNative.ensureGatewayModeLocal() (Kotlin) refuerza en cada arranque desde la
# app, esto cubre el primer arranque manual desde una terminal real.
OPENCLAW_CONFIG="$HOME/.openclaw/openclaw.json"
_openclaw_token="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
if [ ! -f "$OPENCLAW_CONFIG" ]; then
  printf '{"gateway":{"mode":"local","auth":{"mode":"token","token":"%s"}}}\n' "$_openclaw_token" > "$OPENCLAW_CONFIG"
  log "gateway.mode=local + gateway.auth.token pre-sembrados en openclaw.json"
elif ! grep -q '"mode"[[:space:]]*:[[:space:]]*"local"' "$OPENCLAW_CONFIG" 2>/dev/null \
   || ! grep -q '"token"[[:space:]]*:[[:space:]]*"[0-9a-f]' "$OPENCLAW_CONFIG" 2>/dev/null; then
  node -e "
    const fs = require('fs');
    const p = '$OPENCLAW_CONFIG';
    let cfg = {};
    try { cfg = JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) {}
    cfg.gateway = cfg.gateway || {};
    if (!cfg.gateway.mode) cfg.gateway.mode = 'local';
    cfg.gateway.auth = cfg.gateway.auth || {};
    if (!cfg.gateway.auth.mode) cfg.gateway.auth.mode = 'token';
    if (!cfg.gateway.auth.token) cfg.gateway.auth.token = '$_openclaw_token';
    fs.writeFileSync(p, JSON.stringify(cfg, null, 2));
  " 2>/dev/null && log "gateway.mode=local + gateway.auth.token reforzados en openclaw.json existente" || \
    warn "No se pudo mergear gateway.mode/auth.token en config existente"
fi

# ── PASO 4 — glibc-compat.js ─────────────────────────────────
step "4/$TOTAL_STEPS Patches Android (compat + stubs)"

if check_done "n_openclaw_compat"; then
  log "Patches ya aplicados [checkpoint]"
else
  cat > "$COMPAT_JS" << 'EOF'
const os = require('os');
const _ni = os.networkInterfaces.bind(os);
os.networkInterfaces = function () {
  try { const r = _ni(); if (r && Object.keys(r).length > 0) return r; } catch (_) {}
  return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4',
    mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }] };
};
const _hd = os.homedir.bind(os);
os.homedir = function () { return process.env.HOME || _hd(); };
EOF
  ! grep -q "glibc-compat.js" "$BASHRC" 2>/dev/null && {
    sed -i '/max-old-space-size=5632/d' "$BASHRC"
    echo "export NODE_OPTIONS=\"\${NODE_OPTIONS:+\$NODE_OPTIONS }--require $COMPAT_JS --max-old-space-size=5632\"" >> "$BASHRC"
  }
  export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--require $COMPAT_JS"
  log "glibc-compat.js configurado"

  # koffi stub
  _KOFFI_DIR="$OC_BASE/node_modules/koffi"
  if [ -d "$_KOFFI_DIR" ]; then
    cat > "$_KOFFI_DIR/index.js" << 'EOF'
const handler = { get(_, prop) {
  if (prop === '__esModule') return false;
  if (prop === 'default') return proxy;
  if (prop === 'then') return undefined;
  return function () { throw new Error('koffi stub: no disponible en android-arm64'); };
}};
const proxy = new Proxy({}, handler);
module.exports = proxy; module.exports.default = proxy;
EOF
    log "koffi stub aplicado"
  fi

  # clipboard stub
  _CLIP_DIR="$OC_BASE/node_modules/clipboardy"
  if [ -d "$_CLIP_DIR" ]; then
    cat > "$_CLIP_DIR/index.js" << 'EOF'
module.exports.writeSync = () => {}; module.exports.readSync = () => '';
module.exports.write = async () => {}; module.exports.read = async () => '';
EOF
    log "clipboard stub aplicado"
  fi

  # Patch /tmp → $HOME/tmp
  find "$OC_BASE/dist" -name "*.js" -exec \
    sed -i "s|'/tmp'|'$TMP_DIR'|g; s|\"/tmp\"|\"$TMP_DIR\"|g" {} + 2>/dev/null || true
  log "Patches /tmp aplicados"

  # Patch /bin/npm
  # Bug real (2026-08-06, ver docs/humano/humano82.md): 5ta instancia del mismo patrón —
  # grep -l sin matches en ningún archivo devuelve 1 (find -exec ... + propaga ese código),
  # y bajo pipefail eso aborta el script silenciosamente ACÁ, antes de llegar a PASO 5 —
  # confirmado en log real de dispositivo (el log se cortaba justo después de "Patches /tmp
  # aplicados", nunca llegaba a "Patch /bin/npm aplicado" ni a "[STEP] 5/8").
  _NPM_JS=$(find "$OC_BASE/dist" -name "*.js" -exec grep -l "'/bin/npm'" {} + 2>/dev/null | head -1) || true
  [ -n "$_NPM_JS" ] && {
    sed -i "s|'/bin/npm'|'$GLIBC_BIN_DIR/npm'|g" "$_NPM_JS" 2>/dev/null || true
    log "Patch /bin/npm aplicado"
  }

  mark_done "n_openclaw_compat"
fi

# ── PASO 5 — Scripts de control ───────────────────────────────
step "5/$TOTAL_STEPS Creando scripts de control"

if check_done "n_openclaw_scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  mkdir -p "$HOME/scripts/openclaw"

  # Heap del primer arranque: escalado a RAM disponible (60%, clamp 1024-5632MB),
  # no el 5.6GB fijo del resto de las sesiones — evita que Android mate el
  # proceso por OOM justo cuando Node carga el bundle completo por primera vez
  # (portado de install_openclaw.sh, 2026-08-04, ver auditoría de sincronización).
  _MEM_AVAIL_KB=$(grep -m1 MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}') || true
  if [ -n "$_MEM_AVAIL_KB" ]; then
    _HEAP_MB=$(( _MEM_AVAIL_KB * 60 / 100 / 1024 ))
    [ "$_HEAP_MB" -lt 1024 ] && _HEAP_MB=1024
    [ "$_HEAP_MB" -gt 5632 ] && _HEAP_MB=5632
  else
    _HEAP_MB=2048
  fi

  cat > "$HOME/scripts/openclaw/openclaw_start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
PORT=$PORT
LOG_DIR="$LOG_DIR"
TMP_DIR="$TMP_DIR"
SESSION="openclaw"

# --no-wait: dispara tmux y termina de inmediato, sin esperar el health-check —
# disponible para quien quiera un arranque no bloqueante manual (portado de
# install_openclaw.sh).
NOWAIT=0
for arg in "\$@"; do [ "\$arg" = "--no-wait" ] && NOWAIT=1; done

curl -sf http://127.0.0.1:\$PORT &>/dev/null && {
  echo "[OK] Gateway ya corriendo :\$PORT"; exit 0; }

# Bug real confirmado por ADB en dispositivo (2026-08-23, ver docs/humano219.md/humano220.md):
# "pkill -9 -f 'openclaw'" mataba con SIGKILL (exit 137) al propio proceso que corre ESTE
# script, en ~0.3s, siempre — no era el phantom process killer de Android (descartado por el
# usuario, confirmado desactivado). "pkill -f" matchea contra la línea de comando COMPLETA de
# cualquier proceso vivo, y el bash que corre este script tiene "openclaw" en su propia ruta
# ("\$HOME/scripts/openclaw/openclaw_start.sh") — pkill lo mataba a él mismo (su propio padre)
# antes de llegar a crear la sesión tmux. Reproducido y aislado con un script de control: el
# mismo comando desde una ruta SIN "openclaw" no se automata (confirmado con "time" + exit
# code). Fix: patrón de 2 palabras ("openclaw gateway", el comando real que corre dentro de
# tmux en la línea de abajo) — no matchea la ruta del wrapper, sí matchea el proceso real.
pkill -9 -f 'openclaw gateway' 2>/dev/null || true
tmux kill-session -t \$SESSION 2>/dev/null || true
sleep 1
tmux new -d -s \$SESSION
sleep 1
tmux send-keys -t \$SESSION \
  "export TMPDIR=\$TMP_DIR; export NODE_OPTIONS=\"--max-old-space-size=$_HEAP_MB\"; openclaw gateway --bind loopback --port \$PORT 2>&1 | tee \$LOG_DIR/runtime.log" C-m

if [ "\$NOWAIT" = "1" ]; then
  echo "[OK] OpenClaw lanzado en background (tmux 'openclaw') — verifica el estado en unos segundos"
  exit 0
fi

# Health-check HTTP real — no solo "existe la sesión tmux" (eso no confirma que
# el gateway realmente responda). Bug real (2026-08-06, ver docs/humano/humano86.md):
# la ventana original de ~12s (6x2s) resultó insuficiente en dispositivo real — el
# primer arranque de "openclaw gateway" (Node cargando el bundle completo, mismo
# patrón lento ya visto en el heap-sizing de PASO 5) puede tardar bastante más que
# eso. Subida a ~45s (22x2s), mismo orden de magnitud que el margen ya usado para
# n8n (90s) — sin llegar tan lejos porque el gateway ya viene con el heap escalado
# a RAM disponible desde el fix anterior, no debería necesitar tanto como n8n+udocker.
HEALTH_OK=false
for i in \$(seq 1 22); do
  sleep 2
  curl -sf --max-time 2 http://127.0.0.1:\$PORT &>/dev/null && { HEALTH_OK=true; break; }
  tmux has-session -t \$SESSION 2>/dev/null || break
done

# Bug real (2026-08-07, ver docs/humano/humano90.md): confirmado contra la documentación
# oficial real (docs.openclaw.ai/cli/gateway) que "openclaw gateway" puede rechazar arrancar
# por un config REPARABLE (ej. falta "gateway.mode") — en una terminal interactiva el propio
# CLI ofrece correr "openclaw doctor --fix" y reintentar una vez (confirmado también con
# evidencia real de dispositivo: el TUI mostró exactamente ese comando como sugerencia). Acá
# no hay terminal interactiva (tmux send-keys no espera confirmación), así que se automatiza:
# si el primer intento no respondió, correr "doctor --fix" una vez y reintentar el arranque
# completo antes de darlo por fallido. "doctor --fix" no puede inventar credenciales de un
# proveedor de IA que el usuario nunca configuró — si el gateway sigue sin responder después,
# lo más probable es que haga falta completar "openclaw onboard" (proveedor real) primero.
if ! \$HEALTH_OK; then
  echo "[WARN] Gateway no respondió — probando reparar config con 'openclaw doctor --fix'..."
  openclaw doctor --fix >> \$LOG_DIR/runtime.log 2>&1
  tmux kill-session -t \$SESSION 2>/dev/null || true
  sleep 1
  tmux new -d -s \$SESSION
  sleep 1
  tmux send-keys -t \$SESSION \
    "export TMPDIR=\$TMP_DIR; export NODE_OPTIONS=\"--max-old-space-size=$_HEAP_MB\"; openclaw gateway --bind loopback --port \$PORT 2>&1 | tee -a \$LOG_DIR/runtime.log" C-m
  for i in \$(seq 1 22); do
    sleep 2
    curl -sf --max-time 2 http://127.0.0.1:\$PORT &>/dev/null && { HEALTH_OK=true; break; }
    tmux has-session -t \$SESSION 2>/dev/null || break
  done
fi

if \$HEALTH_OK; then
  echo "[OK] OpenClaw iniciado :\$PORT"
else
  echo "[ERROR] Gateway no respondió — revisa: cat \$LOG_DIR/runtime.log. Si falta configurar un proveedor de IA, corré 'openclaw onboard' desde el TUI."
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/openclaw/openclaw_start.sh"

  cat > "$HOME/scripts/openclaw/openclaw_stop.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
PORT=$PORT

# SIGTERM primero, SIGKILL solo si no cierra a tiempo — patrón de
# referencia/interfaz/openclaw-termux-main/.../GatewayService.kt (stopGateway():
# proc.destroy() + waitFor(3s) + destroyForcibly() como fallback). Antes esto era
# un "pkill -9" directo sin gracia, que puede matar a node/openclaw en medio de una
# escritura (ej. onboard/config) y dejar procesos hijos huérfanos si proot está de
# por medio (auditoría referencia/ia externa, 2026-08-19).
# Mismo bug real que arriba (ver comentario en openclaw_start.sh de este mismo generador,
# docs/humano219.md/humano220.md): "-f \"openclaw\"" a secas matchea la ruta del propio wrapper
# ("openclaw_stop.sh" vive en "\$HOME/scripts/openclaw/") y se automata con SIGTERM/SIGKILL
# antes de terminar de detener el gateway real. Patrón de 2 palabras = mismo fix.
pkill -TERM -f "openclaw gateway" 2>/dev/null || true
tmux kill-session -t openclaw 2>/dev/null || true
for i in \$(seq 1 6); do
  pgrep -f "openclaw gateway" >/dev/null 2>&1 || break
  sleep 0.5
done
pkill -9 -f "openclaw gateway" 2>/dev/null || true
sleep 1
curl -sf http://127.0.0.1:\$PORT &>/dev/null \\
  && echo "[WARN] Aún responde" \\
  || echo "[OK] Gateway detenido"
SCRIPT
  chmod +x "$HOME/scripts/openclaw/openclaw_stop.sh"

  log "Scripts de control creados"
  mark_done "n_openclaw_scripts"
fi

# ── PASO 6 — Aliases ─────────────────────────────────────────
step "6/$TOTAL_STEPS Configurando aliases"

if check_done "n_openclaw_aliases"; then
  log "Aliases ya configurados [checkpoint]"
else
  grep -v "openclaw-start\|openclaw-stop\|openclaw-status\|openclaw-tui\|openclaw-token\|ocr\|oclog\|ockill\|# OpenClaw\|OpenClaw Start\|OpenClaw End" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << ALIASES

# ════════════════════════════════
#  OpenClaw nativo · aliases
# ════════════════════════════════
export TMPDIR="$TMP_DIR"
alias openclaw-start='bash ~/scripts/openclaw/openclaw_start.sh'
alias openclaw-stop='bash ~/scripts/openclaw/openclaw_stop.sh'
alias openclaw-status='curl -sf --max-time 1 http://127.0.0.1:$PORT &>/dev/null && echo "OpenClaw: ● :$PORT" || echo "OpenClaw: ○ detenido"'
alias openclaw-tui='openclaw tui'
ALIASES

  log "Aliases configurados"
  mark_done "n_openclaw_aliases"
fi

# ── PASO 7 — Registry ────────────────────────────────────────
step "7/$TOTAL_STEPS Actualizando registry"

# BUG REAL (causa raíz del reporte "OpenClaw no se instala / sigue en 'No
# instalado'" pese a varias rondas previas de fixes): esta era una asignación
# bare `VAR=$(pipeline)`. Bajo `set -euo pipefail`, si `openclaw --version`
# fallaba/crasheaba por CUALQUIER motivo (entorno Android, primera corrida sin
# config, timeout, etc.) — algo que además queda invisible porque su stderr se
# manda a /dev/null — pipefail propagaba ese fallo a la asignación completa y
# `set -e` abortaba el script EN ESTA LÍNEA, antes de llegar a
# `update_registry`. El resto de la instalación (npm install, patches,
# scripts, aliases) ya había quedado bien en disco y sus checkpoints marcados
# como hechos, pero el registry (`~/.android_server_registry`) nunca recibía
# `openclaw.installed=true` / `openclaw.location=nativo_termux` — que es
# exactamente lo que lee `ModuleRegistry`/`isModuleInstalled()` en
# OpenClawFragment.kt y ModulesFragment. Resultado: la app mostraba "No
# instalado" de forma permanente, y cada reintento del usuario repetía el
# mismo camino (los pasos 3-6 se saltaban por checkpoint, llegaba de nuevo a
# este mismo punto, mismo resultado) — coincide con los reportes repetidos
# pese a 6+ rondas de fixes previas que nunca tocaron este paso. Fix: `|| true`
# para que un `openclaw --version` fallido no mate el script — ya cae al
# fallback "unknown" de la línea siguiente, y el registry SIEMPRE se actualiza
# si se llegó hasta acá (que ya implica que el binario se instaló bien, ver
# chequeo de PASO 3).
_CL_VER_F=$(openclaw --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9.]+' | head -1) || true
[ -z "$_CL_VER_F" ] && _CL_VER_F="unknown"
update_registry "$_CL_VER_F" "nativo_termux"

# ── PASO 8 — Finalizando ─────────────────────────────────────
step "8/$TOTAL_STEPS Finalizando"

# ── openclaw update (best-effort) ────────────────────────────
# Hallazgo #2 de docs/referencias/REFERENCIA_OPENCLAW_ANDROID_MAIN.md:
# el proyecto de referencia openclaw-android-main corre "openclaw update" tras
# instalar (comentario original: "builds native modules like sharp"). Kairos
# instala con --ignore-scripts (bug node-gyp-build sin compilador), lo que deja
# módulos nativos (ej. sharp) SIN construir — este paso los compila/repara.
# Best-effort: si falla o se corta por timeout, se ignora con warn (NUNCA error).
if check_done "n_openclaw_native_update"; then
  log "openclaw update ya ejecutado [checkpoint]"
else
  # Mismo PATH que el resto del script (NPM_BIN/GLIBC_BIN_DIR en $PATH), con
  # fallback a $_OC_BIN por si la instalación se saltó por checkpoint.
  _OC_UPDATE_BIN=$(command -v openclaw 2>/dev/null) || true
  [ -z "$_OC_UPDATE_BIN" ] && _OC_UPDATE_BIN="${_OC_BIN:-}"
  info "openclaw update (módulos nativos, best-effort)..."
  if command -v timeout &>/dev/null; then
    _OC_UPDATE_PFX="timeout 120"
  else
    _OC_UPDATE_PFX=""
  fi
  if $_OC_UPDATE_PFX "$_OC_UPDATE_BIN" update 2>&1 | tail -5; then
    mark_done "n_openclaw_native_update"
    log "openclaw update ejecutado"
  else
    warn "openclaw update falló (se ignora)"
  fi
fi

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  OpenClaw nativo instalado ✓${NC}"
  echo "  Versión: ${_CL_VER_F}"
  echo "  Puerto:  $PORT"
  echo "  Node:    $(node --version 2>/dev/null)"
  echo ""
fi

notify_event "openclaw" "install_done" "$_CL_VER_F"
log "Instalación de OpenClaw completada"
exit 0
