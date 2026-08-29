#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · kairos.sh
#  Bootstrap de KairosApp — setup inicial completo
#
#  SE EJECUTA UNA VEZ al primer arranque de la app.
#  Después de completar, la app detecta ~/.kairos_ready
#
#  USO DESDE APP (KairosApp):
#    bash kairos.sh --silent
#
#  USO MANUAL (standalone):
#    bash kairos.sh
#
#  FLAGS:
#    --silent   Sin preguntas ni cabecera (modo app)
#    --force    Reinstalar todo aunque ya esté
#
#  QUÉ HACE:
#    ✅ PASO 1  — Permisos de almacenamiento
#    ✅ PASO 2  — pkg update && pkg upgrade
#    ✅ PASO 3  — Paquetes core (python, nodejs, git, tmux, etc.)
#    ✅ PASO 4  — Paquetes build (clang, rust, make, etc.)
#    ✅ PASO 5  — Paquetes glibc (para Claude Code, OpenCode, OpenClaw)
#    ✅ PASO 6  — Paquetes multimedia + utilidades
#    ✅ PASO 7  — Python pip upgrade
#    ✅ PASO 8  — npm globales (npm, corepack, pm2)
#    ✅ PASO 9  — Tema visual (GitHub Dark + JetBrains Mono + extra-keys)
#    ✅ PASO 10 — Estructura de carpetas + .bashrc
#    ✅ PASO 11 — Registry + .kairos_ready
#
#  NO HACE:
#    ❌ Descargar scripts de GitHub (ya extraídos por la app)
#    ❌ Instalar módulos (cada uno tiene su .sh: ollama.sh, n8n.sh, etc.)
#    ❌ Instalar pip packages extras (lo hace install_python.sh)
#    ❌ Instalar ollama, opencode (módulos opcionales)
#    ❌ Menú interactivo (la app es la UI)
#
#  kairos_manager.py usa SOLO stdlib:
#    json, subprocess, os, sys, urllib.request, sqlite3, datetime
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Junio 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib"
export DEBIAN_FRONTEND=noninteractive

# Bug real (2026-08-06, ver docs/humano/humano77.md): este script llama a
# pkg_update_with_fallback() en el PASO 2 pero nunca importaba lib.sh (donde
# vive esa función) — el error "command not found" se tragaba en silencio
# (sin set -e) y el PASO 2 terminaba sin refrescar el índice de paquetes
# contra ningún mirror, marcándose igual como completado.
#
# Bug real #2 (2026-08-27, ver docs/humano261.md, reproducido de punta a punta con el
# wizard real en dispositivo): el fix de arriba asume que lib.sh vive en el MISMO
# directorio que kairos.sh — pero KairosBootstrap.kt::doExtract() extrae kairos.sh
# directo a ~/scripts/kairos.sh (caso especial) mientras que TODO el resto de scripts,
# lib.sh incluido, va a ~/scripts/install/ — "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# resolvía a ~/scripts/lib.sh, que nunca existió ahí ("No such file or directory",
# silencioso otra vez sin set -e) — mismo síntoma exacto que el bug #1 de arriba, causa
# distinta. Corregido apuntando al subdirectorio real.
source "$(dirname "${BASH_SOURCE[0]}")/install/lib.sh"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
for arg in "$@"; do
  case "$arg" in
    --silent) SILENT=true ;;
    --force)  FORCE=true ;;
  esac
done

# ── Colores ───────────────────────────────────────────────────
if $SILENT; then
  log()    { echo "[OK] $1"; }
  warn()   { echo "[WARN] $1"; }
  error()  { echo "[ERROR] $1"; exit 1; }
  info()   { echo "[INFO] $1"; }
  step()   { echo "[STEP] $1"; }
else
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
  log()    { echo -e "${GREEN}[OK]${NC} $1"; }
  warn()   { echo -e "${YELLOW}[WARN]${NC} $1"; }
  error()  { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
  info()   { echo -e "${CYAN}[INFO]${NC} $1"; }
  step()   { echo -e "${CYAN}[STEP]${NC} $1"; }
fi

# ── Subcomando help (kairos help <módulo>, ver hallazgo #4 de
#    docs/referencias/AUDITORIA_CATEGORIA_CIBERSEGURIDAD.md — ayuda
#    in-terminal renderizada con glow, paquete real de Termux) ──
# Renderiza docs/modulos/<MODULO>.md si existe (fallback a cat si glow no está),
# lista los ids disponibles sin módulo, e instala glow solo con help --install-glow.
_kairos_help() {
  local DOCS_DIR="$(dirname "${BASH_SOURCE[0]}")/../docs/modulos"
  local _id="${1:-}"

  if [ "$_id" = "--install-glow" ]; then
    info "Instalando glow (renderer de markdown en terminal)..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install glow -y 2>/dev/null || error "No se pudo instalar glow"
    log "glow instalado — usá: kairos help <módulo>"
    return 0
  fi

  if [ -n "$_id" ]; then
    local _doc=""
    case "$_id" in
      # Algunos ids no coinciden con el nombre del .md (claude→CLAUDE_CODE, etc.)
      claude) _doc="$DOCS_DIR/CLAUDE_CODE.md" ;;
      remote) _doc="$DOCS_DIR/REMOTE.md" ;;
      *)      _doc=$(find "$DOCS_DIR" -maxdepth 1 -iname "${_id}.md" -print -quit 2>/dev/null) ;;
    esac
    if [ -n "$_doc" ] && [ -f "$_doc" ]; then
      if command -v glow &>/dev/null; then
        glow -p "$_doc"
      else
        cat "$_doc"
      fi
    else
      warn "No hay documentación para el módulo '$_id' (falta docs/modulos/${_id}.md)"
    fi
  else
    info "Módulos con documentación in-terminal (docs/modulos/):"
    for _f in "$DOCS_DIR"/*.md; do
      [ -f "$_f" ] || continue
      _base=$(basename "$_f" .md)
      case "$_base" in
        INDEX|AUDITORIA_*) continue ;;
      esac
      echo "  $(echo "$_base" | tr '[:upper:]' '[:lower:]')"
    done
    echo ""
    info "Uso: kairos help <módulo>   (instalá el renderer con: kairos help --install-glow)"
  fi
}

if [ "$1" = "help" ] || [ "$1" = "--help" ]; then
  _kairos_help "$2"
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.kairos_bootstrap_checkpoint"
BASHRC="$HOME/.bashrc"
TERMUX_CONFIG="$HOME/.termux"

check_done() { grep -q "^$1$" "$CHECKPOINT" 2>/dev/null; }
mark_done()  { echo "$1" >> "$CHECKPOINT"; }

# Helper: pkg install silencioso
pkg_install() {
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md — cubre
  # a todos los llamadores de este helper en un solo lugar (DRY).
  pkg_update_with_fallback
  pkg install -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" \
    "$@" 2>&1 | tail -3
}

# ── Verificar si ya está listo ────────────────────────────────
if [ -f "$HOME/.kairos_ready" ] && ! $FORCE; then
  log "KairosApp ya configurado"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT" "$HOME/.kairos_ready"

# ── Modo manual: cabecera ─────────────────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   KairosApp · Bootstrap                     ║
  ║   termux-ai-stack · ARM64 · v1.0.0         ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script prepara Termux con todos los paquetes"
  echo "  necesarios para KairosApp. Tarda 5-15 minutos."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=11

# ============================================================
# PASO 1 — Permisos de almacenamiento
# ============================================================
step "1/$TOTAL_STEPS Verificando permisos de almacenamiento"

if check_done "storage_perms"; then
  log "Permisos verificados [checkpoint]"
else
  if touch /sdcard/Download/.kairos_test 2>/dev/null; then
    rm -f /sdcard/Download/.kairos_test
    log "Acceso a /sdcard OK"
  else
    info "Solicitando permisos de almacenamiento..."
    termux-setup-storage 2>/dev/null || true
    sleep 4
    if touch /sdcard/Download/.kairos_test 2>/dev/null; then
      rm -f /sdcard/Download/.kairos_test
      log "Acceso a /sdcard OK"
    else
      warn "Sin permisos de almacenamiento — backups a /sdcard no funcionarán"
    fi
  fi
  mark_done "storage_perms"
fi

# ============================================================
# PASO 2 — pkg update && pkg upgrade
# ============================================================
step "2/$TOTAL_STEPS Actualizando Termux"

if check_done "pkg_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando repositorios..."
  # Quick win de la auditoría de referencia/ (2026-08-05, ver docs/humano70.md) — antes
  # probaba solo 2 mirrors fijos en orden; ahora comparte la misma selección por
  # velocidad real que ya usaba entorno.sh (5 mirrors, medida vía curl).
  pkg_update_with_fallback

  info "Upgrade de paquetes existentes..."
  pkg upgrade -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>&1 | tail -5

  log "Termux actualizado"
  mark_done "pkg_update"
fi

# ============================================================
# PASO 3 — Paquetes core
# ============================================================
step "3/$TOTAL_STEPS Instalando paquetes core"

if check_done "core_pkgs"; then
  log "Paquetes core ya instalados [checkpoint]"
else
  info "Instalando tur-repo..."
  pkg_install tur-repo || warn "tur-repo: advertencia"

  info "Instalando paquetes core..."
  pkg_install \
    python python-pip python-ensurepip-wheels \
    nodejs-lts npm \
    git curl wget \
    tmux \
    openssh openssh-sftp-server \
    proot proot-distro \
    busybox \
    tar xz-utils unzip \
    iproute2 net-tools \
    jq \
    termux-api \
    sqlite \
    ca-certificates \
    || warn "Algunos paquetes tuvieron advertencias"

  # Verificar críticos
  for cmd in python3 node npm git curl tmux proot-distro ssh sqlite3; do
    command -v "$cmd" &>/dev/null && log "$cmd ✓" || warn "$cmd no instalado"
  done

  mark_done "core_pkgs"
fi

# ============================================================
# PASO 4 — Paquetes build (compilación)
# ============================================================
step "4/$TOTAL_STEPS Instalando paquetes de compilación"

if check_done "build_pkgs"; then
  log "Paquetes build ya instalados [checkpoint]"
else
  info "Instalando toolchain de compilación..."
  pkg_install \
    build-essential \
    clang \
    make \
    pkg-config \
    binutils \
    rust rust-std-aarch64-linux-android \
    libffi \
    openssl \
    libopenblas \
    || warn "Algunos paquetes build tuvieron advertencias"

  log "Toolchain de compilación instalado"
  mark_done "build_pkgs"
fi

# ============================================================
# PASO 5 — Paquetes glibc
# ============================================================
step "5/$TOTAL_STEPS Instalando paquetes glibc"

if check_done "glibc_pkgs"; then
  log "Paquetes glibc ya instalados [checkpoint]"
else
  info "Instalando glibc-repo..."
  pkg_install glibc-repo || warn "glibc-repo: advertencia"

  # Actualizar índice con el nuevo repo
  pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1 | tail -2

  info "Instalando glibc + patchelf..."
  pkg_install \
    glibc \
    glibc-runner \
    openssl-glibc \
    patchelf-glibc \
    || warn "Algunos paquetes glibc tuvieron advertencias"

  # Verificar ld.so — bug real confirmado (auditoría 2026-08-01, ver docs/humano/humano42.md):
  # esto antes era solo un warn() informativo, mark_done corría igual sin importar el
  # resultado. Mismo patrón de "checkpoint marcado sin verificar" ya corregido varias
  # veces esta sesión en scripts de módulo — acá vive en la infraestructura BASE
  # compartida (claude.sh/opencode.sh/openclaw.sh dependen de esto, aunque cada uno
  # también tiene su propio respaldo redundante de glibc/patchelf). Si ld.so no está,
  # abortar y dejar que el próximo intento reinstale de verdad en vez de saltarse este
  # paso para siempre con un "listo" falso.
  GLIBC_LD="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
  if [ ! -f "$GLIBC_LD" ]; then
    error "glibc ld.so no encontrado tras la instalación ($GLIBC_LD) — reintentá, puede ser un fallo transitorio de red/mirror"
  fi
  log "glibc ld.so OK"

  # glibc /etc/hosts para dns.lookup localhost
  GLIBC_ETC="$TERMUX_PREFIX/glibc/etc"
  if [ -d "$GLIBC_ETC" ] && [ ! -f "$GLIBC_ETC/hosts" ]; then
    printf '127.0.0.1 localhost localhost.localdomain\n::1 localhost\n' > "$GLIBC_ETC/hosts"
    log "glibc /etc/hosts creado"
  fi

  mark_done "glibc_pkgs"
fi

# ============================================================
# PASO 6 — Multimedia + GPU + utilidades
# ============================================================
step "6/$TOTAL_STEPS Instalando multimedia y utilidades"

if check_done "media_util_pkgs"; then
  log "Multimedia + utils ya instalados [checkpoint]"
else
  info "Instalando multimedia..."
  pkg_install \
    ffmpeg \
    libjpeg-turbo libpng zlib \
    ripgrep \
    || warn "Algunos paquetes multimedia tuvieron advertencias"

  info "Instalando GPU Vulkan..."
  pkg_install \
    vulkan-icd \
    vulkan-loader-generic \
    || warn "Paquetes Vulkan no disponibles — GPU puede no funcionar"

  info "Instalando utilidades..."
  pkg_install \
    nano \
    lsof \
    procps \
    psmisc \
    bc \
    dos2unix \
    || warn "Algunas utilidades tuvieron advertencias"

  log "Multimedia + utilidades instalados"
  mark_done "media_util_pkgs"
fi

# ============================================================
# PASO 7 — Python pip upgrade
# ============================================================
step "7/$TOTAL_STEPS Actualizando pip"

if check_done "pip_upgrade"; then
  log "pip ya actualizado [checkpoint]"
else
  if command -v python3 &>/dev/null; then
    info "Actualizando pip..."
    python3 -m pip install --upgrade pip --break-system-packages 2>&1 | tail -2
    PIP_VER=$(python3 -m pip --version 2>/dev/null | awk '{print $2}')
    log "pip $PIP_VER"
  else
    warn "Python3 no disponible — pip no actualizado"
  fi
  mark_done "pip_upgrade"
fi

# ============================================================
# PASO 8 — npm globales
# ============================================================
step "8/$TOTAL_STEPS Instalando npm globales"

if check_done "npm_globals" && command -v pm2 &>/dev/null; then
  log "npm globales ya instalados [checkpoint]"
else
  if command -v npm &>/dev/null; then
    # Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): "npm install
    # -g npm" sobreescribe el npm parcheado para Termux (shebang sin /usr/bin/env, que
    # acá no existe) con uno genérico del registry — "bad interpreter" en cualquier
    # npm posterior (corepack/pm2 de abajo incluidos). El npm que ya trae nodejs-lts
    # alcanza, no hace falta "actualizarlo".

    info "Instalando corepack..."
    npm install -g corepack 2>&1 | tail -2

    info "Instalando pm2..."
    npm install -g pm2 2>&1 | tail -2

    # Bug real confirmado (reporte de usuario, 2026-07-31): "por que pm2 no se instala".
    # `npm install -g` de arriba nunca chequeaba su propio exit code (silenciado por el
    # pipe a `tail -2`, sin `set -o pipefail`) — si la instalación de pm2 fallaba (red,
    # timeout del registry de npm, etc.), el checkpoint se marcaba "hecho" igual y pm2
    # nunca se reintentaba en un próximo arranque de la app. Verificación real del
    # binario antes de marcar el paso como completo — mismo patrón ya usado en
    # python.sh/n8n.sh/cloudflared para este mismo tipo de bug.
    if command -v pm2 &>/dev/null; then
      log "npm globales: npm, corepack, pm2"
      mark_done "npm_globals"
    else
      warn "pm2 no se pudo instalar (revisar conexión / registry de npm) — se reintentará en el próximo arranque"
    fi
  else
    warn "npm no disponible — globales no instalados"
  fi
fi

# ============================================================
# PASO 9 — Tema visual
# ============================================================
step "9/$TOTAL_STEPS Configurando tema visual"

if check_done "theme"; then
  log "Tema ya configurado [checkpoint]"
else
  mkdir -p "$TERMUX_CONFIG"

  # Colores GitHub Dark
  cat > "$TERMUX_CONFIG/colors.properties" << 'COLORS'
background=#0d1117
foreground=#c9d1d9
color0=#484f58
color1=#ff7b72
color2=#3fb950
color3=#d29922
color4=#58a6ff
color5=#bc8cff
color6=#39c5cf
color7=#b1bac4
color8=#6e7681
color9=#ffa198
color10=#56d364
color11=#e3b341
color12=#79c0ff
color13=#d2a8ff
color14=#56d4dd
color15=#f0f6fc
COLORS
  log "Colores GitHub Dark aplicados"

  # Extra-keys
  cat > "$TERMUX_CONFIG/termux.properties" << 'PROPS'
# KairosApp · Configuración Termux
extra-keys = [['ESC','TAB','CTRL','ALT','|','/','UP','DOWN'],['n8n-start','n8n-url','claude','ollama-start','~','help','LEFT','RIGHT']]
bell-character=ignore
PROPS
  log "Extra-keys configuradas"

  # Fuente JetBrains Mono
  FONT_FILE="$TERMUX_CONFIG/font.ttf"
  if [ ! -f "$FONT_FILE" ]; then
    info "Descargando JetBrains Mono..."
    FONT_URL="https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip"
    FONT_TMP="$HOME/.jbmono_dl.zip"
    curl -fL "$FONT_URL" -o "$FONT_TMP" 2>/dev/null || \
      timeout 30 wget -q "$FONT_URL" -O "$FONT_TMP" 2>/dev/null

    if [ -f "$FONT_TMP" ] && [ -s "$FONT_TMP" ]; then
      FONT_EXTRACT="$HOME/.jbmono_extract"
      mkdir -p "$FONT_EXTRACT"
      unzip -q "$FONT_TMP" "fonts/ttf/JetBrainsMono-Regular.ttf" -d "$FONT_EXTRACT" 2>/dev/null
      if [ -f "$FONT_EXTRACT/fonts/ttf/JetBrainsMono-Regular.ttf" ]; then
        mv "$FONT_EXTRACT/fonts/ttf/JetBrainsMono-Regular.ttf" "$FONT_FILE"
        log "JetBrains Mono instalada"
      else
        warn "No se pudo extraer la fuente"
      fi
      rm -rf "$FONT_TMP" "$FONT_EXTRACT"
    else
      warn "No se pudo descargar la fuente — usando fuente por defecto"
      rm -f "$FONT_TMP"
    fi
  else
    log "JetBrains Mono ya instalada"
  fi

  # Aplicar tema
  command -v termux-reload-settings &>/dev/null && termux-reload-settings 2>/dev/null

  mark_done "theme"
fi

# ============================================================
# PASO 10 — Estructura de carpetas + .bashrc
# ============================================================
step "10/$TOTAL_STEPS Creando estructura y configuración"

if check_done "structure"; then
  log "Estructura ya creada [checkpoint]"
else
  # Estructura de carpetas
  mkdir -p "$HOME/scripts/install"
  mkdir -p "$HOME/scripts/ollama"
  mkdir -p "$HOME/scripts/n8n"
  mkdir -p "$HOME/scripts/openclaw"
  mkdir -p "$HOME/scripts/opencode"
  mkdir -p "$HOME/scripts/hermes"
  mkdir -p "$HOME/scripts/remote"
  mkdir -p "$HOME/scripts/expo"
  mkdir -p "$HOME/projects"
  mkdir -p "$HOME/tmp"
  mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
  log "Estructura de carpetas creada"

  # .bashrc
  [ -f "$BASHRC" ] && grep -v "kairos\|ANDROID_SERVER\|termux-ai-stack\|# KairosApp\|# FIN KAIROS" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'BASHRC_BLOCK'

# ════════════════════════════════════════
#  KairosApp · termux-ai-stack · base
# ════════════════════════════════════════
export TMPDIR="$HOME/tmp"
export OLLAMA_VULKAN=1
export PATH="$HOME/.local/bin:$HOME/.openclaw-android/bin:$HOME/.npm-global/bin:$PATH"

# Aliases globales
alias py3='python3'
alias pip3-install='pip install --break-system-packages'
alias km='python3 ~/kairos_manager.py'
alias debian='proot-distro login debian'
# FIN KAIROS
BASHRC_BLOCK

  log ".bashrc configurado"

  # ──────────────────────────────────────────────────────────
  # Wrapper de "apt" — adaptado de i-Haklab (ivam3/i-Haklab, GPLv3,
  # ver docs/referencias/REFERENCIA_IHAKLAB.md). Permite instalar módulos de
  # Kairos escribiendo "apt install <id>" desde CUALQUIER sesión de
  # Termux, no solo desde la pestaña Módulos de la app — se ubica en
  # ~/.local/bin/apt, que ya queda antes que $PREFIX/bin/apt en el
  # PATH (ver el bloque de arriba: "$HOME/.local/bin:...:$PATH").
  # Si el paquete no es un módulo conocido de Kairos, cae directo al
  # apt real (ruta absoluta, para no recursar contra sí mismo).
  # ──────────────────────────────────────────────────────────
  mkdir -p "$HOME/.local/bin"
  cat > "$HOME/.local/bin/apt" << 'APTWRAPPER'
#!/data/data/com.termux/files/usr/bin/bash
# Wrapper de apt de KairosApp — instala módulos de Kairos con
# "apt install <id>" desde cualquier terminal. Adaptado de i-Haklab
# (ivam3/i-Haklab, GPLv3) — ver docs/referencias/REFERENCIA_IHAKLAB.md.
REAL_APT="/data/data/com.termux/files/usr/bin/apt"
# Los scripts de módulo (extraídos del APK) viven en scripts/install/,
# no en scripts/ directo — ver KairosBootstrap.kt doExtract(). "remote"
# es el único id cuyo nombre de archivo no coincide (ssh.sh, no remote.sh
# — ver ModuleController.kt installScriptFile()).
SCRIPTS_DIR="$HOME/scripts/install"

_kairos_module_script() {
  case "$1" in
    remote) echo "$SCRIPTS_DIR/ssh.sh" ;;
    ollama|n8n|python|claude|codex|antigravity|openclaw|opencode|hermes|expo|entorno|engram)
      echo "$SCRIPTS_DIR/$1.sh" ;;
    *) echo "" ;;
  esac
}

if [ "$1" = "install" ] || [ "$1" = "reinstall" ]; then
  # Bug real #2 (auditoría forense 2026-08-29, docs/humano285.md, ver el guard exportado en
  # lib.sh): un módulo que YA está corriendo (python.sh, ollama.sh variante standard,
  # cactus.sh, ciberseguridad.sh, hf.sh, mistralvibe.sh, stacks.sh — todos instalan el
  # paquete real "python"/"ollama" de Termux con una lista de UN solo elemento que coincide
  # con un id de módulo) dispara este mismo wrapper de nuevo al llamar "pkg install python" —
  # sin ningún paquete real mezclado en la lista, así que el bug #1 de abajo no lo cubre. Sin
  # este guard, el wrapper corre "bash python.sh --silent" en vez del pkg real, y python.sh
  # vuelve a pasar por acá al llegar a su propio "pkg install python" — recursión sin cota
  # real (python nunca queda instalado de verdad, así que el caso base "ya instalado" nunca
  # se cumple). KAIROS_MODULE_SCRIPT_ACTIVE lo exporta lib.sh — si ya está seteada, estamos
  # dentro de un script de módulo (no una sesión interactiva del usuario): reenviar SIEMPRE
  # al apt real, sin intentar detectar ids de módulo acá.
  if [ -n "$KAIROS_MODULE_SCRIPT_ACTIVE" ]; then
    exec "$REAL_APT" "$@"
  fi
  # Bug real confirmado en dispositivo (ronda 2026-08-29, ide.sh/hermes.sh): la versión
  # anterior de este loop, ante CUALQUIER paquete de la lista que coincidiera con un id de
  # módulo Kairos (ej. "python"), corría el script del módulo y después hacía "exit 0"
  # incondicional — descartando en silencio TODOS los demás paquetes reales de esa misma
  # invocación ("pkg install git neovim nodejs-lts python perl curl ..." nunca llegaba a
  # instalar git/neovim/perl/curl/etc., solo corría python.sh). Como $HOME/.local/bin
  # antepone este wrapper al "apt" real en el PATH de TODOS los scripts de módulo
  # (ver applyTermuxEnvShared en ProcessBuilderExt.kt), cualquier "pkg install <lista>" de
  # cualquier módulo que incluyera "python" (u otro id de módulo) junto con paquetes reales
  # quedaba con esos paquetes reales sin instalar, sin ningún error — pkg/apt seguía
  # devolviendo exit 0 pese a no haber instalado nada de la lista real. Confirmado real
  # ejecutando ide.sh en el dispositivo: "nvim no disponible tras la instalación" con
  # "pkg install ... -y" devolviendo exit 0.
  #
  # Fix: separar los paquetes reales (no-módulo) de los ids de módulo Kairos. Los ids de
  # módulo se instalan con su propio script; los paquetes reales SIEMPRE se reenvían al apt
  # real (si hay alguno) — nunca se hace "exit 0" descartándolos.
  _real_pkgs=()
  for pkg in "${@:2}"; do
    script=$(_kairos_module_script "$pkg")
    if [ -n "$script" ] && [ -f "$script" ]; then
      echo "[i] '$pkg' es un módulo de KairosApp — instalando con $script --silent"
      bash "$script" --silent
    else
      _real_pkgs+=("$pkg")
    fi
  done
  if [ "${#_real_pkgs[@]}" -eq 0 ]; then
    exit 0
  fi
  # "${@:2}" ya no hace falta acá — _real_pkgs conserva TODO lo que no matcheó un id de
  # módulo (paquetes reales + flags como "-y" o "-o Dpkg::Options::=...", en el mismo orden
  # en que llegaron), así que es el reemplazo directo y completo del resto de argumentos.
  exec "$REAL_APT" "$1" "${_real_pkgs[@]}"
fi

exec "$REAL_APT" "$@"
APTWRAPPER
  chmod +x "$HOME/.local/bin/apt"
  log "Wrapper de apt instalado (~/.local/bin/apt)"

  # ──────────────────────────────────────────────────────────
  # Skill "kairos-oracle" para agentes de IA — adaptado de
  # termux-oracle (ivam3/termux-oracle, GPLv3, ver
  # docs/referencias/REFERENCIA_TERMUX_ORACLE.md). Le da a los agentes de IA que
  # corren DENTRO de Kairos (OpenCode, Claude Code, Codex, etc.)
  # contexto real sobre el sistema de módulos de Kairos, sin que el
  # usuario tenga que explicarlo desde cero cada vez.
  # ──────────────────────────────────────────────────────────
  ORACLE_DIR="$HOME/.agents/skills/kairos-oracle"
  mkdir -p "$ORACLE_DIR/scripts" "$ORACLE_DIR/references"

  cat > "$ORACLE_DIR/scripts/detect-env.sh" << 'DETECTENV'
#!/data/data/com.termux/files/usr/bin/bash
# detect-env.sh — detecta el entorno de KairosApp para la skill
# kairos-oracle. Patrón tomado de termux-oracle (ivam3/termux-oracle).
REGISTRY="$HOME/.android_server_registry"
KAIROS_READY=false
[ -f "$HOME/.kairos_ready" ] && KAIROS_READY=true
ROOTFS_DONE=false
grep -q "^core_pkgs$" "$HOME/.kairos_bootstrap_checkpoint" 2>/dev/null && ROOTFS_DONE=true
INSTALLED=$(grep -oE '^[a-z0-9_]+\.installed=true' "$REGISTRY" 2>/dev/null | cut -d'.' -f1 | tr '\n' ',' | sed 's/,$//')
cat << EOF
{
  "kairos_ready": $KAIROS_READY,
  "rootfs_or_bootstrap_done": $ROOTFS_DONE,
  "modules_installed": "$INSTALLED",
  "prefix": "$PREFIX",
  "home": "$HOME"
}
EOF
DETECTENV
  chmod +x "$ORACLE_DIR/scripts/detect-env.sh"

  cat > "$ORACLE_DIR/SKILL.md" << 'SKILLMD'
---
name: kairos-oracle
description: >
  Conocimiento del sistema de módulos y convenciones de KairosApp (fork de
  termux-app con UI nativa). El agente usa esta skill para entender cómo
  están organizados los módulos (modulos/*.sh, modules.json), el contrato
  de scripts (--silent/--force/--describe, checkpoints), y ayudar a
  depurar o escribir módulos nuevos siguiendo el mismo patrón.
allowed-tools:
  - Bash(read:kairos-oracle/*, detect-env, apt, bash)
  - Read
  - Glob
  - Grep
---

# Kairos-Oracle Skill

Contexto sobre el sistema de módulos de KairosApp, para agentes de IA que
corren dentro de la terminal de la app (OpenCode, Claude Code, Codex, etc.).
Inspirada en termux-oracle (ivam3/termux-oracle) — ver
docs/referencias/REFERENCIA_TERMUX_ORACLE.md en el repo de Kairos.

## 1. Detección del entorno

```bash
bash ~/.agents/skills/kairos-oracle/scripts/detect-env.sh
```

Devuelve JSON con: `kairos_ready`, `rootfs_or_bootstrap_done`, `modules_installed`.

## 2. Qué es KairosApp

- Fork de termux-app (motor Java/terminal) + UI nativa Kotlin/Java — no es
  Termux normal ni una webview.
- Cada módulo (Ollama, n8n, Claude Code, etc.) tiene un script en
  `~/scripts/<id>.sh` (extraído del APK, copia de `modulos/<id>.sh` en el
  repo) — se instala/controla vía la app o con el wrapper de `apt`
  instalado en `~/.local/bin/apt` (`apt install <id>` funciona igual que
  desde la app).
- Estado real de instalación/versión en `~/.android_server_registry`
  (formato `modulo.clave=valor`).

## 3. Routing por intención

| El usuario pregunta sobre... | Archivo de referencia |
|---|---|
| Cómo está estructurado un módulo, `modules.json` | `references/module-system.md` |
| Contrato de scripts (`--silent`, checkpoints, `lib.sh`) | `references/script-contract.md` |

## 4. Reglas de ejecución

1. Los módulos se instalan con `bash ~/scripts/<id>.sh --silent` (o `apt install <id>`, equivalente).
2. Nunca asumas que un módulo está instalado — comprobá `~/.android_server_registry` (`<id>.installed=true`) primero.
3. Si vas a escribir o modificar un módulo, seguí el patrón de `references/script-contract.md` — no inventes uno nuevo.
4. El rootfs embebido (si está disponible) pre-marca checkpoints de `kairos.sh` — no asumas que un paso "core_pkgs" faltante significa que algo falló, puede estar cubierto por el rootfs.
SKILLMD

  cat > "$ORACLE_DIR/references/module-system.md" << 'MODSYS'
# Sistema de módulos de KairosApp

Cada módulo se declara en `app/src/main/assets/modules.json` (en el APK,
no accesible desde el dispositivo directamente) con: `id`, `name`,
`description`, `script` (nombre del `.sh`), `icon`/`iconBg`, `port`,
`size`, `type`, `requiresProot`, `hasVariants`, `estimate`, `hasSwitch`
(true = servicio persistente con toggle, false = herramienta CLI sin
servicio en background), `tmuxSession` (si aplica), `webviewUrl` (si
aplica), `terminalCommand` (comando a correr en terminal).

Los scripts reales viven en `~/scripts/<id>.sh` una vez extraídos del
APK. Todos aceptan `--silent` (sin prompts, modo app) y `--force`
(reinstalar). Algunos aceptan `--describe` (imprime un manifiesto JSON de
una línea con sus capacidades).

Módulos con servicio persistente (tmux-backed): ollama, n8n, openclaw,
opencode. Módulos CLI-tool (sin servicio, `hasSwitch:false`): python,
claude, codex, antigravity, hermes, engram. Remote (SSH) es
process-backed, no tmux.
MODSYS

  cat > "$ORACLE_DIR/references/script-contract.md" << 'SCRIPTCONTRACT'
# Contrato de scripts de módulos (modulos/*.sh)

Cada script sigue este patrón (ver `modulos/codex.sh` o `modulos/engram.sh`
como referencia completa):

1. Header con TERMUX_PREFIX + PATH.
2. Parseo de flags: `--silent`, `--force`, `--describe`.
3. `--describe` imprime `{"id":"...","supports_silent":true,...}` y sale.
4. `source modulos/lib.sh` — helpers compartidos: `log()`, `warn()`,
   `error()` (sale con exit 1), `info()`, `step()`, `check_done()`,
   `mark_done()` (checkpoints namespacados por variable `$CHECKPOINT`).
5. Chequeo de "ya instalado" (short-circuit si ya está, salvo `--force`).
6. Confirmación interactiva SOLO si no es `--silent`.
7. PASOs numerados, cada uno con su propio checkpoint — permite
   reanudar si el script se interrumpe a mitad de camino.
8. Actualiza `~/.android_server_registry` al final
   (`<id>.installed=true`, `<id>.version=...`, `<id>.install_date=...`).

No inventar un patrón distinto para un módulo nuevo — copiar la
estructura de un módulo CLI-tool existente (`codex.sh`/`engram.sh`) o de
uno con servicio (`opencode.sh`) según corresponda, y adaptar.
SCRIPTCONTRACT

  log "Skill kairos-oracle instalada (~/.agents/skills/kairos-oracle/)"

  mark_done "structure"
fi

# ============================================================
# PASO 11 — Registry + .kairos_ready
# ============================================================
step "11/$TOTAL_STEPS Finalizando"

# Registry base
registry_write kairos \
  "bootstrap=true" \
  "version=1.0.0" \
  "date=$(date +%Y-%m-%d)" \
  "python=$(python3 --version 2>/dev/null | awk '{print $2}' || echo 'none')" \
  "node=$(node --version 2>/dev/null || echo 'none')" \
  "npm=$(npm --version 2>/dev/null || echo 'none')"

# 2026-08-11 (humano97 feedback): python3 se instala como parte del bootstrap/rootfs
# (rootfs_package_list.txt), pero kairos.sh solo escribía kairos.python=<versión> y NUNCA
# python.installed=true -> PythonFragment.isModuleInstalled() (registry) decía
# "Python no está instalado" aunque el binario exista. El módulo python también registra
# python.installed=true (python.sh) pero queda desincronizado si el usuario nunca lo tocó.
# Se sincroniza acá: si el binario existe, el módulo está instalado.
if command -v python3 >/dev/null 2>&1; then
  registry_write python \
    "installed=true" \
    "version=$(python3 --version 2>/dev/null | awk '{print $2}' || echo 'unknown')"
fi

log "Registry actualizado"

# Limpiar
rm -f "$CHECKPOINT"

# Marcar como listo
touch "$HOME/.kairos_ready"
log "Bootstrap completado — ~/.kairos_ready creado"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  KairosApp bootstrap completado ✓${NC}"
  echo ""
  echo "  Python:  $(python3 --version 2>/dev/null)"
  echo "  Node.js: $(node --version 2>/dev/null)"
  echo "  npm:     $(npm --version 2>/dev/null)"
  echo "  pip:     $(python3 -m pip --version 2>/dev/null | awk '{print $2}')"
  echo "  glibc:   $([ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] && echo "OK" || echo "no")"
  echo ""
  echo "  Instala módulos desde la app o con:"
  echo "    bash modulos/ollama.sh"
  echo "    bash modulos/n8n.sh"
  echo "    ..."
  echo ""
fi

exit 0
