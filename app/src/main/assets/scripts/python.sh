#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · python.sh (silent mode)
#  Instala Python + SQLite + paquetes esenciales en Termux
#
#  USO DESDE APP (KairosApp):
#    bash python.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_python.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA (en orden):
#    ✅ pkg update && pkg upgrade
#    ✅ tur-repo (Termux User Repository)
#    ✅ Python 3 + pip upgrade
#    ✅ proot-distro
#    ✅ binutils, libopenblas, clang
#    ✅ pip: six, certifi, idna, urllib3, charset-normalizer,
#           soupsieve, typing_extensions, python-dateutil,
#           beautifulsoup4, requests, websockets, pillow
#    ✅ pkg: python-numpy (ARM64 precompilado)
#    ✅ scipy (pkg primero, pip fallback)
#    ✅ pkg: python-pandas (ARM64 precompilado)
#    ✅ SQLite CLI
#    ✅ libjpeg-turbo, libpng, zlib (deps imagen)
#    ✅ Scripts (sports + trading + bots) desde GitHub
#    ✅ Vision scripts (movidos de install_ollama)
#    ✅ Aliases + Registry
#
#  ESTRUCTURA:
#    ~/sports/{scripts,db}   ~/trading/{scripts,db}   ~/bots/{scripts,db}
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 3.0.0 | Junio 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"python","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Python NO es un candidato real
# para moduledeb: es enteramente paquetes apt (python, sqlite) + pip
# (numpy/scipy/pandas/requests/PIL/etc.) — cientos de archivos por paquete,
# gestionados correctamente por pkg/pip, no algo que valga la pena snapshot-
# ear como .deb propio. files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "python", supports_describe_files: true, variant: null,
    package_name: "kairos-module-python",
    version_registry_key: "python.version",
    files: [], file_globs: [], dependencies: [],
    verify_cmd: "command -v python3 >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Python es enteramente paquetes apt (python, sqlite) + pip (numpy/scipy/pandas/requests/PIL/websockets/beautifulsoup4) — cientos de archivos por paquete, ya gestionados correctamente por pkg/pip. No vale la pena empaquetarlo como .deb propio de Kairos — reinstalar vía pkg/pip es más simple y correcto que un snapshot de archivos"]
  }'
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_python_checkpoint"
BASHRC="$HOME/.bashrc"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Helper: pkg install silencioso
pkg_install() {
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md — el
  # fallback de mirrors del PASO 1 de este script no detecta "No mirror or
  # mirror group selected" (solo lo hace pkg_update_with_fallback de lib.sh),
  # así que se cubre acá para todos los llamadores de este helper (DRY).
  pkg_update_with_fallback
  pkg install -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" \
    "$@" 2>&1 | tail -3
}

# Helper: pip install con --break-system-packages
pip_install() {
  python3 -m pip install --break-system-packages "$@" 2>&1 | tail -3
}

update_registry() {
  local version="$1"
  registry_install python "$version" "location=termux_native" "sqlite=true" "pillow=true" "numpy=true" "scipy=true" "pandas=true" "requests=true" "websockets=true" "beautifulsoup4=true" "sports=true" "bots=true"
}

# ── Verificar si ya está instalado ────────────────────────────
if command -v python3 &>/dev/null && ! $FORCE; then
  # Verificar que los paquetes críticos estén
  _ALL_OK=true
  python3 -c "import numpy" 2>/dev/null || _ALL_OK=false
  python3 -c "import requests" 2>/dev/null || _ALL_OK=false
  python3 -c "import PIL" 2>/dev/null || _ALL_OK=false

  if $_ALL_OK; then
    log "Python ya instalado con paquetes completos — $(python3 --version 2>/dev/null)"
    exit 0
  else
    info "Python existe pero faltan paquetes — continuando instalación"
  fi
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ──────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Python Installer        ║
  ║   Termux ARM64 · sin root · v3.0.0         ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instala Python 3 + ~20 paquetes esenciales"
  echo "  incluyendo numpy, scipy, pandas, requests, pillow, etc."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=10

# ============================================================
# PASO 1 — pkg update && pkg upgrade
# ============================================================
step "1/$TOTAL_STEPS Actualizando Termux"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado por instalar.sh [skip]"
elif check_done "termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  MIRRORS=(
    "https://packages.termux.dev/apt/termux-main"
    "https://mirror.accum.se/mirror/termux.dev/apt/termux-main"
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
  )
  OUT=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
  if echo "$OUT" | grep -q "unexpected size\|Mirror sync in progress\|Err:2"; then
    for m in "${MIRRORS[@]}"; do
      echo "deb $m stable main" > "$TERMUX_PREFIX/etc/apt/sources.list"
      OUT=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
      echo "$OUT" | grep -q "unexpected size\|Mirror sync" || { log "Mirror OK"; break; }
    done
  fi

  info "Upgrade de paquetes..."
  pkg upgrade -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>&1 | tail -5

  log "Termux actualizado"
  mark_done "termux_update"
fi

# ============================================================
# PASO 2 — tur-repo
# ============================================================
step "2/$TOTAL_STEPS Instalando tur-repo"

if check_done "tur_repo"; then
  log "tur-repo ya instalado [checkpoint]"
else
  info "Instalando tur-repo (Termux User Repository)..."
  pkg_install tur-repo || warn "tur-repo: advertencia — continuando"
  log "tur-repo instalado"
  mark_done "tur_repo"
fi

# ============================================================
# PASO 3 — Python + pip upgrade
# ============================================================
step "3/$TOTAL_STEPS Instalando Python"

# Bug real confirmado con evidencia de dispositivo (capturas + logs, docs/humano*.md
# 2026-07-31): python.installed=true en el registry y checkpoint "python_install" marcado
# de una corrida anterior exitosa, pero python3 roto/ausente en la práctica ("Cannot run
# program python3" en 6+ pantallas). El checkpoint por sí solo no prueba nada sobre el
# estado ACTUAL del binario — solo que en algún momento pasado la instalación funcionó.
# Igual que el fix ya aplicado a npm_globals/pm2 en kairos.sh: no confiar ciegamente en el
# checkpoint, reverificar que el binario responda ahora mismo antes de saltar el paso.
if check_done "python_install" && command -v python3 &>/dev/null; then
  log "Python ya instalado [checkpoint]"
else
  info "Instalando Python vía pkg..."
  pkg_install python || error "Error instalando Python"
  command -v python3 &>/dev/null || error "python3 no disponible"

  PY_VER=$(python3 --version 2>/dev/null | awk '{print $2}')
  log "Python $PY_VER instalado"

  # NO ejecutar "pip install --upgrade pip": Termux empaqueta pip como su propio
  # paquete (python-pip) y su binario trae un guard que rechaza auto-actualizarse
  # ("ERROR: Installing pip is forbidden, this will break the python-pip package
  # (termux)." — evidencia real de dispositivo, install_python.log línea 45,
  # 2026-08-14). El pip que trae "pkg install python" ya es el correcto; se
  # actualiza vía "pkg upgrade", no vía pip. Solo se informa la versión actual.
  log "pip $(python3 -m pip --version 2>/dev/null | awk '{print $2}') disponible"

  mark_done "python_install"
fi

# ============================================================
# PASO 4 — proot-distro + binutils + clang + libopenblas
# ============================================================
step "4/$TOTAL_STEPS Instalando paquetes sistema"

if check_done "system_pkgs"; then
  log "Paquetes sistema ya instalados [checkpoint]"
else
  # proot-distro
  if ! command -v proot-distro &>/dev/null; then
    info "Instalando proot-distro..."
    pkg_install proot-distro proot
  else
    log "proot-distro ya disponible"
  fi

  # binutils, libopenblas, clang
  info "Instalando binutils, libopenblas, clang..."
  pkg_install binutils libopenblas clang || warn "Algunos paquetes tuvieron advertencias"

  log "Paquetes sistema instalados"
  mark_done "system_pkgs"
fi

# ============================================================
# PASO 5 — SQLite CLI + dependencias imagen
# ============================================================
step "5/$TOTAL_STEPS Instalando SQLite y dependencias imagen"

if check_done "sqlite_image_deps"; then
  log "SQLite + deps imagen ya instalados [checkpoint]"
else
  info "Instalando SQLite CLI..."
  pkg_install sqlite || warn "sqlite CLI no instalado"

  if python3 -c "import sqlite3; print(sqlite3.sqlite_version)" 2>/dev/null; then
    log "Módulo Python sqlite3 OK"
  else
    warn "Módulo sqlite3 no verificado"
  fi

  info "Instalando dependencias imagen (libjpeg-turbo, libpng, zlib)..."
  pkg_install libjpeg-turbo libpng zlib || warn "Algunas deps imagen fallaron"

  log "SQLite + deps imagen instalados"
  mark_done "sqlite_image_deps"
fi

# ============================================================
# PASO 6 — Paquetes pip (en orden estricto)
# ============================================================
step "6/$TOTAL_STEPS Instalando paquetes pip"

if check_done "pip_packages"; then
  log "Paquetes pip ya instalados [checkpoint]"
else
  # Orden exacto solicitado
  PIP_PACKAGES=(
    six
    certifi
    idna
    urllib3
    charset-normalizer
    soupsieve
    typing_extensions
    python-dateutil
    beautifulsoup4
    requests
    websockets
    pillow
  )

  TOTAL_PIP=${#PIP_PACKAGES[@]}
  PIP_OK=0
  PIP_FAIL=0

  for pkg in "${PIP_PACKAGES[@]}"; do
    info "[$((PIP_OK + PIP_FAIL + 1))/$TOTAL_PIP] Instalando $pkg..."
    if pip_install "$pkg"; then
      PIP_OK=$((PIP_OK + 1))
    else
      warn "$pkg falló — continuando"
      PIP_FAIL=$((PIP_FAIL + 1))
    fi
  done

  log "pip: $PIP_OK/$TOTAL_PIP instalados${PIP_FAIL:+, $PIP_FAIL fallaron}"
  mark_done "pip_packages"
fi

# ============================================================
# PASO 7 — numpy, scipy, pandas (pkg ARM64 + pip fallback)
# ============================================================
step "7/$TOTAL_STEPS Instalando numpy, scipy, pandas"

if check_done "scientific_pkgs"; then
  log "numpy + scipy + pandas ya instalados [checkpoint]"
else
  # ── numpy ──────────────────────────────────────────────────
  info "Instalando python-numpy (ARM64 precompilado)..."
  pkg_install python-numpy || warn "pkg python-numpy falló"

  if ! python3 -c "import numpy" 2>/dev/null; then
    info "numpy pkg falló — intentando pip..."
    pip_install numpy || warn "numpy pip también falló"
  fi
  python3 -c "import numpy; print('numpy', numpy.__version__)" 2>/dev/null && \
    log "numpy OK" || warn "numpy no disponible"

  # ── scipy ──────────────────────────────────────────────────
  info "Instalando python-scipy (ARM64 precompilado)..."
  pkg_install python-scipy || warn "pkg python-scipy falló"

  if ! python3 -c "import scipy" 2>/dev/null; then
    info "scipy pkg falló — intentando pip (puede tardar)..."
    pip_install scipy || warn "scipy pip también falló"
  fi
  python3 -c "import scipy; print('scipy', scipy.__version__)" 2>/dev/null && \
    log "scipy OK" || warn "scipy no disponible — motor math puro como fallback"

  # ── pandas ─────────────────────────────────────────────────
  info "Instalando python-pandas (ARM64 precompilado)..."
  pkg_install python-pandas || warn "pkg python-pandas falló"

  if ! python3 -c "import pandas" 2>/dev/null; then
    info "pandas pkg falló — intentando pip..."
    pip_install pandas || warn "pandas pip también falló"
  fi
  python3 -c "import pandas; print('pandas', pandas.__version__)" 2>/dev/null && \
    log "pandas OK" || warn "pandas no disponible"

  mark_done "scientific_pkgs"
fi

# ============================================================
# PASO 8 — Descargar scripts (sports + trading + bots + vision)
# ============================================================
step "8/$TOTAL_STEPS Descargando scripts"

REPO_API="https://api.github.com/repos/Honkonx/termux-ai-stack/contents/python"
REPO_RAW="https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/python"

if check_done "all_scripts"; then
  log "Scripts ya descargados [checkpoint]"
else
  # Crear estructura
  mkdir -p "$HOME/sports/scripts"  "$HOME/sports/db" \
           "$HOME/sports/logs"     "$HOME/sports/models" \
           "$HOME/trading/scripts" "$HOME/trading/db" \
           "$HOME/bots/scripts"    "$HOME/bots/db"
  log "Estructura de directorios creada"

  descargar_carpeta() {
    local carpeta="$1"
    local destino="$2"
    local ok=0 fail=0

    info "Descargando python/$carpeta/ → $destino"

    local lista
    lista=$(curl -fsSL "$REPO_API/$carpeta" 2>/dev/null || \
            wget -qO- "$REPO_API/$carpeta" 2>/dev/null)

    if [ -z "$lista" ]; then
      warn "No se pudo consultar API para python/$carpeta"
      return
    fi

    local archivos
    archivos=$(echo "$lista" | \
      grep -o '"name": *"[^"]*\.py"' | \
      grep -o '"[^"]*\.py"' | \
      tr -d '"')

    if [ -z "$archivos" ]; then
      info "Sin archivos .py en python/$carpeta"
      return
    fi

    for script in $archivos; do
      local TMP="$destino/${script}.tmp"
      curl -fsSL "$REPO_RAW/$carpeta/$script" -o "$TMP" 2>/dev/null || \
        timeout 30 wget -q "$REPO_RAW/$carpeta/$script" -O "$TMP" 2>/dev/null
      if [ -f "$TMP" ] && [ -s "$TMP" ]; then
        mv "$TMP" "$destino/$script"
        chmod +x "$destino/$script"
        ok=$((ok + 1))
      else
        rm -f "$TMP"
        fail=$((fail + 1))
      fi
    done

    [ $ok -gt 0 ] && log "$ok scripts descargados en $destino"
    [ $fail -gt 0 ] && warn "$fail scripts fallaron en $carpeta"
  }

  descargar_carpeta "sports"  "$HOME/sports/scripts"
  descargar_carpeta "trading" "$HOME/trading/scripts"
  descargar_carpeta "bots"    "$HOME/bots/scripts"

  # ── Vision scripts (movidos de install_ollama) ──────────────
  VISION_RAW="https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/python/dashboard"
  VISION_SCRIPTS=("vision_bot.py" "bot_utils.py" "image_archive.py")
  VISION_DIR="$HOME/bots/scripts"

  info "Descargando vision scripts..."
  for vs in "${VISION_SCRIPTS[@]}"; do
    TMP="$VISION_DIR/${vs}.tmp"
    curl -fsSL "$VISION_RAW/$vs" -o "$TMP" 2>/dev/null || \
      timeout 30 wget -q "$VISION_RAW/$vs" -O "$TMP" 2>/dev/null
    if [ -f "$TMP" ] && [ -s "$TMP" ]; then
      mv "$TMP" "$VISION_DIR/$vs"
      chmod +x "$VISION_DIR/$vs"
    else
      rm -f "$TMP"
      warn "No se pudo descargar $vs"
    fi
  done
  log "Vision scripts descargados"

  # ── Init BD sports ──────────────────────────────────────────
  if [ -f "$HOME/sports/scripts/db_query.py" ]; then
    python3 "$HOME/sports/scripts/db_query.py" verificar_acceso '{"user_id":"init"}' \
      > /dev/null 2>&1 && log "BD sports inicializada" || \
      info "BD sports se creará al primer uso"
  fi

  # ── Init BD trading ─────────────────────────────────────────
  if [ -f "$HOME/trading/scripts/signal_bot.py" ]; then
    python3 "$HOME/trading/scripts/signal_bot.py" init 2>/dev/null && \
      log "BD trading inicializada" || \
      info "BD trading se creará al primer uso"
  fi

  mark_done "all_scripts"
fi

# ============================================================
# PASO 9 — Aliases
# ============================================================
step "9/$TOTAL_STEPS Configurando aliases"

if check_done "python_aliases"; then
  log "Aliases ya configurados [checkpoint]"
else
  [ -f "$BASHRC" ] && grep -v "py3\|pip3-install\|sqlite-n8n\|# Python" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  Python · aliases
# ════════════════════════════════
alias py3='python3'
alias pip3-install='pip install --break-system-packages'
alias sqlite-n8n='proot-distro login debian -- sqlite3 /root/.n8n/database.sqlite'
ALIASES

  log "Aliases configurados"
  mark_done "python_aliases"
fi

# ============================================================
# PASO 10 — Registry
# ============================================================
step "10/$TOTAL_STEPS Actualizando registry"

# Verificación funcional real antes de marcar installed=true (.claude/rules/
# empirical-verification-before-fix.md — python.sh era uno de los 2 únicos módulos sin
# ningún chequeo --version antes de registry_install, confirmado en la auditoría
# docs/arquitectura/AUDITORIA_CONSISTENCIA_MODULOS_2026-08-26.md § 3). "--version" es el flag
# default de verify_binary_installed(), python3 lo soporta sin problema.
verify_binary_installed python3 || error "python3 no ejecuta tras la instalación"

PY_VER=$(python3 --version 2>/dev/null | awk '{print $2}')
[ -z "$PY_VER" ] && PY_VER="unknown"
update_registry "$PY_VER"

# ── Limpieza ──────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Python instalado ✓${NC}"
  echo ""
  echo "  Python:  $(python3 --version 2>/dev/null)"
  echo "  pip:     $(python3 -m pip --version 2>/dev/null | awk '{print $2}')"
  echo "  sqlite3: $(python3 -c 'import sqlite3; print(sqlite3.sqlite_version)' 2>/dev/null)"
  echo ""
  echo "  Paquetes verificados:"
  for mod in numpy scipy pandas requests websockets PIL beautifulsoup4; do
    _import="$mod"
    [ "$mod" = "PIL" ] && _import="PIL"
    [ "$mod" = "beautifulsoup4" ] && _import="bs4"
    python3 -c "import $_import" 2>/dev/null && \
      echo "    ✓ $mod" || echo "    ✗ $mod"
  done
  echo ""
fi

notify_event "python" "install_done" "$PY_VER"
log "Instalación de Python completada"
exit 0
