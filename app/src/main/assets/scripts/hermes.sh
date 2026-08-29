#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · hermes.sh (silent mode)
#  Instala Hermes Agent en Termux ARM64 (sin root)
#
#  USO DESDE APP (KairosApp):
#    bash hermes.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_hermes.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Dependencias sistema (python, git, clang, rust, etc.)
#    ✅ Clonar NousResearch/hermes-agent
#    ✅ Virtualenv Python + dependencias
#    ✅ Shim hermes en PATH
#    ✅ Archivos config (.env, config.yaml, SOUL.md)
#    ✅ Registry actualizado
#
#  NO HACE:
#    ❌ Wizard/setup (lo maneja la app después)
#    ❌ Configurar proveedor IA
#
#  OUTPUT (modo --silent):
#    [STEP] N/6 Descripción
#    [OK] mensaje
#    [ERROR] mensaje (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 2.0.0 | Junio 2026
# ============================================================

set -e

[ -n "${PYTHONPATH:-}" ] && unset PYTHONPATH
[ -n "${PYTHONHOME:-}" ] && unset PYTHONHOME
export UV_NO_CONFIG=1

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
{"id":"hermes","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"instalador + wizard, sin gateway/tmux implementado todavia"}
JSON
  exit 0
fi

# ── Variables ─────────────────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_hermes_checkpoint"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
INSTALL_DIR="$HERMES_HOME/hermes-agent"
REPO_URL_HTTPS="https://github.com/NousResearch/hermes-agent.git"
REPO_URL_SSH="git@github.com:NousResearch/hermes-agent.git"
BRANCH="main"

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Hermes es un pip install
# EDITABLE dentro de su propio venv (site-packages + código fuente completo,
# no un binario/wrapper simple) — se empaqueta el árbol completo vía
# file_globs en vez de listar archivos individuales, honesto en not_covered
# sobre el tamaño y la sensibilidad del venv a rutas absolutas.
if $DESCRIBE_FILES; then
  jq -n \
    --arg wrapper "$TERMUX_PREFIX/bin/hermes" \
    --arg glob "${INSTALL_DIR}/**" \
    --arg verify "test -x \"$TERMUX_PREFIX/bin/hermes\" && \"$TERMUX_PREFIX/bin/hermes\" --version >/dev/null 2>&1" \
    '{
      id: "hermes", supports_describe_files: true, variant: null,
      package_name: "kairos-module-hermes",
      version_registry_key: "hermes.version",
      files: [
        {path: $wrapper, required: true, note: "Shim que invoca el venv de hermes-agent — corregido 2026-08-28: PASO 5 (más abajo en este script) escribe el shim real en $TERMUX_PREFIX/bin/hermes (LINK_DIR), no en $HOME/.local/bin/hermes como decía este manifiesto antes; el path viejo nunca existía, el packaging fallaba en silencio"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "árbol completo del pip install editable (venv + código fuente) que el shim de arriba invoca — pesado (site-packages), pero sin esto el shim queda roto en un device nuevo"}
      ],
      dependencies: [
        {id: "python3", check_cmd: "command -v python3", install_hint: "pkg install -y python"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "El venv de hermes-agent tiene rutas absolutas baked-in (activate scripts, shebangs de site-packages) — solo portable si HOME es idéntico en origen/destino, que en Termux siempre lo es (path fijo de la app)",
        "No se empaqueta $HOME/.hermes/{cron,sessions,logs,pairing,hooks,image_cache,audio_cache,memories,skills} ni .env/config.yaml — son datos/estado de usuario, no parte de la instalación en sí"
      ]
    }'
  exit 0
fi

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# check_done/mark_done: hermes namespacea las keys (hermes_X=done),
# se sobreescriben acá encima de las genéricas de lib.sh
check_done() { grep -q "^hermes_${1}=done" "$CHECKPOINT" 2>/dev/null; }
mark_done()  { grep -q "^hermes_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "hermes_${1}=done" >> "$CHECKPOINT"; }

# ── Verificar si ya está instalado ────────────────────────────
if command -v hermes &>/dev/null && [ -d "$INSTALL_DIR" ] && ! $FORCE; then
  log "Hermes ya instalado — $(hermes --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  exit 0
fi

if $FORCE; then
  rm -f "$CHECKPOINT" 2>/dev/null
  rm -rf "$INSTALL_DIR" 2>/dev/null || true
  rm -f "${TERMUX_PREFIX}/bin/hermes" 2>/dev/null || true
  rm -f "$HOME/.local/bin/hermes" 2>/dev/null || true
  info "Instalación anterior eliminada"
fi

# ── Modo manual: cabecera y confirmación ──────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Hermes Agent Installer  ║
  ║   ARM64 · sin root · v2.0.0               ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instala:"
  echo "  ▸ Hermes Agent (NousResearch)"
  echo "  ▸ Virtualenv Python + dependencias"
  echo "  ▸ Archivos de configuración"
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=6

# ============================================================
# PASO 1 — Dependencias del sistema
# ============================================================
step "1/$TOTAL_STEPS Instalando dependencias del sistema"

if check_done "pkgs"; then
  log "Paquetes ya instalados [checkpoint]"
else
  # nodejs-lts (no "nodejs" a secas): el propio repo de hermes-agent tiene un issue
  # abierto pidiendo exactamente esto para Termux/Android (NousResearch/hermes-agent
  # #35816, "termux/android should use nodejs-lts") — la TUI de Hermes (`hermes --tui`)
  # es un bundle Node/Ink, y "nodejs" (la versión más nueva, no-LTS) le rompe la
  # compatibilidad ahí. Además ya es la convención del resto del proyecto: kairos.sh
  # (paquetes core), claude.sh, codex.sh, ollama.sh y rootfs_package_list.txt instalan
  # todos "nodejs-lts" — "nodejs" a secas entraba en conflicto con eso a nivel de
  # paquete Termux (mismos binarios node/npm, se pisan entre sí).
  TERMUX_PKGS=(
    python git clang rust make pkg-config
    libffi openssl ca-certificates curl
    ripgrep ffmpeg nodejs-lts
  )
  info "Instalando: ${TERMUX_PKGS[*]}"
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  pkg_update_with_fallback
  pkg update -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>/dev/null || true
  # mark_done SOLO si pkg install realmente devolvió éxito. Antes el checkpoint
  # se marcaba incondicionalmente (fuera del if/else), así que un fallo real de
  # `pkg install` (red inestable, mirror caído, etc.) quedaba enmascarado como
  # "listo" para siempre: en el siguiente intento el usuario reintentaba, este
  # PASO 1 se saltaba por el checkpoint falso, y el script fallaba río abajo en
  # PASO 4 al faltar clang/rust/make para compilar dependencias nativas — sin
  # ninguna forma de auto-recuperarse salvo borrar el checkpoint a mano o usar
  # --force (reinstalación completa). Bug real confirmado leyendo el script:
  # este era el único de los 6 pasos donde el checkpoint no reflejaba el
  # resultado real del comando. Ahora, si falla, no se marca done y el próximo
  # intento reintenta la instalación de paquetes en vez de saltarla.
  if pkg install -y \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" \
    "${TERMUX_PKGS[@]}" 2>/dev/null; then
    log "Paquetes instalados"
    mark_done "pkgs"
  else
    warn "La instalación de paquetes falló — se reintentará en el próximo intento"
  fi
fi

# ============================================================
# PASO 2 — Clonar repositorio
# ============================================================
step "2/$TOTAL_STEPS Clonando repositorio"

mkdir -p "$HERMES_HOME"

if check_done "clone" && [ -d "$INSTALL_DIR/.git" ]; then
  log "Repositorio ya clonado [checkpoint]"
else
  [ -d "$INSTALL_DIR" ] && rm -rf "$INSTALL_DIR"

  info "Intentando clonar via SSH..."
  if GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=5" \
     git clone --depth 1 --branch "$BRANCH" "$REPO_URL_SSH" "$INSTALL_DIR" 2>/dev/null; then
    log "Clonado via SSH"
  else
    info "SSH falló — intentando HTTPS..."
    git clone --depth 1 --branch "$BRANCH" "$REPO_URL_HTTPS" "$INSTALL_DIR" || \
      error "No se pudo clonar el repositorio"
    log "Clonado via HTTPS"
  fi
  mark_done "clone"
fi

# ============================================================
# PASO 3 — Virtualenv Python
# ============================================================
step "3/$TOTAL_STEPS Creando entorno virtual Python"

cd "$INSTALL_DIR"

if check_done "venv" && [ -f "$INSTALL_DIR/venv/bin/python" ]; then
  log "Virtualenv ya existe [checkpoint]"
else
  PYTHON_PATH=$(command -v python 2>/dev/null || command -v python3 2>/dev/null) || true
  [ -z "$PYTHON_PATH" ] && error "Python no encontrado"

  info "Python: $($PYTHON_PATH --version 2>/dev/null)"
  [ -d "venv" ] && rm -rf venv
  "$PYTHON_PATH" -m venv venv
  log "Virtualenv creado"
  mark_done "venv"
fi

# ============================================================
# PASO 4 — Dependencias Python
# ============================================================
step "4/$TOTAL_STEPS Instalando dependencias Python"

cd "$INSTALL_DIR"
PIP_PYTHON="$INSTALL_DIR/venv/bin/python"
export VIRTUAL_ENV="$INSTALL_DIR/venv"

if check_done "deps"; then
  log "Dependencias ya instaladas [checkpoint]"
else
  if [ -z "${ANDROID_API_LEVEL:-}" ]; then
    ANDROID_API_LEVEL="$(getprop ro.build.version.sdk 2>/dev/null || echo 34)"
    export ANDROID_API_LEVEL
  fi

  info "Actualizando pip, setuptools, wheel..."
  # pip_install() (lib.sh) en vez de "$PIP_PYTHON" -m pip directo en los 4 call-sites de este
  # archivo — bug real de instalación concurrente confirmado por ADB (docs/humano281.md): los
  # wrappers bash python3()/pip() de lib.sh solo interceptan el nombre pelado, nunca una ruta
  # absoluta resuelta como $PIP_PYTHON, así que hermes.sh quedaba sin el lock compartido de pip
  # entre módulos pese a que ciberseguridad.sh/mistralvibe.sh ya lo tenían.
  # pip_install() (lib.sh) ya antepone "-m pip install" internamente — pasarle "install" acá
  # de nuevo duplicaba el argumento ("pip install install --upgrade ...") y pip lo interpretaba
  # como un requirement literal llamado "install", fallando siempre con "ERROR: Could not find
  # a version that satisfies the requirement install". Bug real confirmado por ejecución en
  # dispositivo (ronda 2026-08-29) — introducido en el mismo cambio 2026-08-28 (docs/humano281.md)
  # que migró estos 4 call-sites a pip_install(); mismo bug confirmado también en hf.sh.
  pip_install "$PIP_PYTHON" --upgrade pip setuptools wheel -q

  # psutil y cryptography vía pkg (binarios ARM64 precompilados de Termux) — evita
  # que pip los compile desde fuente dentro del venv (build nativo/Rust, lento o
  # directamente falla en Android). Patrón confirmado en core-termux-main (proyecto
  # de referencia, fix portado de install_hermes.sh 2026-07-26).
  info "Verificando psutil/cryptography del sistema (evita compilar en el venv)..."
  _HERMES_PKG_DEPS=()
  python -c "import psutil" &>/dev/null || _HERMES_PKG_DEPS+=("python-psutil")
  python -c "import cryptography" &>/dev/null || _HERMES_PKG_DEPS+=("python-cryptography")
  if [ "${#_HERMES_PKG_DEPS[@]}" -gt 0 ]; then
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y "${_HERMES_PKG_DEPS[@]}" || \
      warn "No se pudieron instalar algunos paquetes del sistema: ${_HERMES_PKG_DEPS[*]}"
  fi

  # Symlink de cryptography del sistema dentro del venv — el venv no ve el
  # site-packages del sistema por defecto, así que sin esto pip lo reconstruiría
  # ahí de todas formas. Solo cryptography: no hay mecanismo oficial de hermes-agent
  # que la reinstale, así que el symlink es la solución final para ese paquete.
  # Bug real confirmado (ver docs/humano/humano63.md): asignación bare bajo "set -e"
  # (vigente en todo el archivo) — si "$PIP_PYTHON -c ..." fallaba por cualquier motivo,
  # esta línea abortaba TODO el script en silencio (el 2>/dev/null esconde el motivo),
  # pese a que el chequeo "if [ -n ... ]" de abajo la trata como best-effort, no
  # obligatoria. Mismo patrón exacto ya confirmado real 2 veces en openclaw.sh.
  _PY_VER_SHORT=$("$PIP_PYTHON" -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null) || true
  if [ -n "$_PY_VER_SHORT" ]; then
    _VENV_SITE="$INSTALL_DIR/venv/lib/python${_PY_VER_SHORT}/site-packages"
    _SYS_SITE="$TERMUX_PREFIX/lib/python${_PY_VER_SHORT}/site-packages"
    if [ -d "$_SYS_SITE/cryptography" ] && [ -d "$_VENV_SITE" ] && [ ! -e "$_VENV_SITE/cryptography" ]; then
      ln -s "$_SYS_SITE/cryptography" "$_VENV_SITE/cryptography" 2>/dev/null
    fi
  fi

  # psutil NO se symlinkea desde el paquete de sistema (a diferencia de cryptography
  # arriba): hermes-agent trae su propio instalador oficial (scripts/install_psutil_android.py,
  # con fix de seguridad en tar-extraction ya mergeado río arriba — PR #33742 de
  # NousResearch/hermes-agent) que baja el sdist real de psutil, lo parchea para
  # detectar Android (sys.platform de Termux siempre reporta "linux", nunca "android",
  # así que el chequeo de plataforma de psutil upstream necesita ese parche) y lo
  # instala DENTRO del venv vía pip. Si symlinkeábamos primero el psutil de `pkg`
  # (gestionado por dpkg) en esa misma ruta del venv, este paso de pip escribía
  # encima de un symlink hacia un paquete trackeado por el gestor de paquetes de
  # Termux — sin dist-info propio en el venv, pip no lo reconoce como "ya instalado"
  # y reinstala ahí de todas formas, mutando archivos que `pkg upgrade` espera
  # intactos. Confirmado leyendo el install_psutil_android.py real de NousResearch
  # (no estaba documentado en el diagnóstico previo, que asumía que el symlink era
  # compatible con este paso). Se deja que el instalador oficial sea la única fuente
  # de verdad para psutil dentro del venv.
  if [ -d /data/data/com.termux ]; then
    if [ -f "$INSTALL_DIR/scripts/install_psutil_android.py" ]; then
      info "Pre-compilando psutil para Android (instalador oficial de hermes-agent)..."
      "$PIP_PYTHON" "$INSTALL_DIR/scripts/install_psutil_android.py" \
        --pip "$PIP_PYTHON -m pip" 2>/dev/null || \
        warn "psutil Android prebuild falló"
    fi
  fi

  # --ignore-requires-python: fuerza a pip a ignorar el gate de versión que
  # hermes-agent declara en su pyproject.toml (<3.14,>=3.11) — necesario porque
  # Termux ya empaqueta Python 3.14 por defecto. Riesgo real, no ocultarlo: esto
  # solo evita el chequeo de METADATA de pip — si el código de hermes-agent usa
  # algo removido en 3.12+/3.14+ (ej. distutils), la instalación puede pasar pero
  # fallar en runtime. Intentar perfiles en orden: termux-all → termux → base.
  #
  # CONFIRMADO 2026-08-01 (no es hipotético, es el estado real hoy):
  #   - termux-packages/packages/python/build.sh en GitHub fija
  #     TERMUX_PKG_VERSION="3.14.6" — el `pkg install python` de PASO 1 instala
  #     Python 3.14.6, no una versión <3.14.
  #   - github.com/NousResearch/hermes-agent/blob/main/pyproject.toml sigue
  #     declarando `requires-python = ">=3.11,<3.14"` (verificado en HEAD).
  #   - Issue abierto y sin resolver: NousResearch/hermes-agent#48723 ("support
  #     Python 3.14") — confirma que el techo <3.14 es deliberado (dependencias
  #     transitivas con bindings Rust, ej. pydantic-core, sin wheels prebuilt
  #     para 3.14 todavía) y no un descuido menor. Los comentarios del issue
  #     mencionan módulos removidos de stdlib entre 3.12-3.14 (typing.io, pipes,
  #     cgi, distutils) como fuente de incompatibilidades reales en runtime, no
  #     solo de checkeo de versión.
  #   - Consecuencia práctica: --ignore-requires-python puede lograr que pip
  #     complete el install (bypassea el chequeo de METADATA), pero (a) algunas
  #     dependencias Rust-backed pueden no tener wheel ARM64/py3.14 e intentar
  #     compilar desde fuente dentro del venv (lento/pesado en un teléfono, o
  #     directamente falla), y (b) aun si el install "termina", `hermes` puede
  #     crashear al ejecutarse por APIs de stdlib removidas. Esto es un bloqueo
  #     externo real (upstream de hermes-agent, no de este script) — no hay
  #     forma de arreglarlo bajando la versión de Python vía pkg (Termux solo
  #     empaqueta una versión de `python` en el repo principal; versiones viejas
  #     quedarían en TUR de forma parcial/no garantizada). Por eso PASO 5 abajo
  #     ahora SÍ falla el script si `hermes --version` no responde tras el
  #     install, en vez de solo advertir — así el registry no reporta
  #     "instalado" cuando el binario está roto por esta incompatibilidad.
  info "Instalando Hermes (perfil .[termux-all])..."
  if pip_install "$PIP_PYTHON" --ignore-requires-python -e '.[termux-all]' -c constraints-termux.txt -q; then
    log "Instalado con perfil .[termux-all]"
  else
    warn "termux-all falló — probando .[termux]..."
    if pip_install "$PIP_PYTHON" --ignore-requires-python -e '.[termux]' -c constraints-termux.txt -q; then
      log "Instalado con perfil .[termux]"
    else
      warn "termux falló — probando instalación base..."
      pip_install "$PIP_PYTHON" --ignore-requires-python -e '.' -c constraints-termux.txt -q || \
        error "Instalación falló en los 3 perfiles"
      log "Instalado con perfil base"
    fi
  fi
  mark_done "deps"
fi

# ============================================================
# PASO 5 — Comando hermes en PATH
# ============================================================
step "5/$TOTAL_STEPS Configurando comando hermes"

HERMES_BIN="$INSTALL_DIR/venv/bin/hermes"
[ ! -x "$HERMES_BIN" ] && error "Binario hermes no encontrado en: $HERMES_BIN"

LINK_DIR="$TERMUX_PREFIX/bin"
rm -f "$LINK_DIR/hermes" 2>/dev/null || true
cat > "$LINK_DIR/hermes" << SHIM
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "$HERMES_BIN" "\$@"
SHIM
chmod +x "$LINK_DIR/hermes"
log "Shim instalado → $LINK_DIR/hermes"

# Bug #12 arreglado (auditoría ADB, ver docs/humano/humano193.md) — el script usaba "hermes version"
# (sintaxis inválida, sin "--") en las 7 ocurrencias de este archivo. Confirmado en vivo:
# "hermes --version" funciona perfecto, "hermes version" siempre falla.
if hermes --version &>/dev/null; then
  HM_VER=$(hermes --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  log "hermes v${HM_VER:-?} accesible"
else
  # Antes esto era solo un warn() y el script seguía hasta PASO 6, que escribe
  # hermes.installed=true en el registry sin condición — es decir, el registry
  # (y por lo tanto HermesFragment.isModuleInstalled(), que lee exactamente esa
  # key) podía reportar "instalado" con un binario que ni siquiera corre
  # `hermes --version`. Escenario real y confirmado arriba (PASO 4): Termux
  # empaqueta Python 3.14.6 pero hermes-agent exige <3.14 — con
  # --ignore-requires-python el `pip install` puede terminar "bien" y aun así
  # dejar un binario que crashea al ejecutarse por incompatibilidades reales de
  # runtime, no solo el chequeo de versión. Ahora se trata como fallo duro para
  # que el registry nunca quede desincronizado del estado real.
  error "hermes no responde tras la instalación (binario roto o incompatible — probable incompatibilidad hermes-agent/Python 3.14, ver comentario PASO 4). Revisa manualmente: hermes --version"
fi

# ============================================================
# PASO 6 — Configuración + Registry
# ============================================================
step "6/$TOTAL_STEPS Preparando configuración y registry"

mkdir -p "$HERMES_HOME"/{cron,sessions,logs,pairing,hooks,image_cache,audio_cache,memories,skills}

# .env
if [ ! -f "$HERMES_HOME/.env" ]; then
  if [ -f "$INSTALL_DIR/.env.example" ]; then
    cp "$INSTALL_DIR/.env.example" "$HERMES_HOME/.env"
  else
    touch "$HERMES_HOME/.env"
  fi
  chmod 600 "$HERMES_HOME/.env" 2>/dev/null || true
  log "~/.hermes/.env creado"
else
  log "~/.hermes/.env conservado"
fi

# config.yaml
if [ ! -f "$HERMES_HOME/config.yaml" ]; then
  [ -f "$INSTALL_DIR/cli-config.yaml.example" ] && \
    cp "$INSTALL_DIR/cli-config.yaml.example" "$HERMES_HOME/config.yaml"
  log "~/.hermes/config.yaml creado"
else
  log "~/.hermes/config.yaml conservado"
fi

# SOUL.md
if [ ! -f "$HERMES_HOME/SOUL.md" ]; then
  cat > "$HERMES_HOME/SOUL.md" << 'SOUL_EOF'
# Hermes Agent Persona
# Edita este archivo para personalizar el tono y estilo del agente.
# Se carga en cada mensaje — no requiere reinicio.
SOUL_EOF
  log "~/.hermes/SOUL.md creado"
fi

# Skills sync
if [ -f "$INSTALL_DIR/tools/skills_sync.py" ]; then
  "$PIP_PYTHON" "$INSTALL_DIR/tools/skills_sync.py" 2>/dev/null && \
    log "Skills sincronizadas" || \
    warn "sync de skills falló — ejecuta: hermes update"
fi

# Registry
HM_VER=$(hermes --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
[ -z "$HM_VER" ] && HM_VER="unknown"

registry_install hermes "$HM_VER" "install_dir=$INSTALL_DIR" "model=no configurado" "location=termux_native"

# ── Limpieza ──────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Hermes Agent instalado ✓${NC}"
  echo "  Versión:  ${HM_VER}"
  echo "  Config:   ${HERMES_HOME}/config.yaml"
  echo "  API keys: ${HERMES_HOME}/.env"
  echo ""
  echo "  Configura con: hermes setup"
  echo ""
fi

notify_event "hermes" "install_done" "$HM_VER"
log "Instalación de Hermes completada"
exit 0
