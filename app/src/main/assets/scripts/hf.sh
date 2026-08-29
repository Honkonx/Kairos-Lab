#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · hf.sh (silent mode)
#  Instala Hugging Face CLI (hf) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash hf.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ curl + python3 (si faltan)
#    ✅ Hugging Face CLI (instalador oficial https://hf.co/cli/install.sh)
#       — comando: hf
#    ✅ Registry actualizado
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login de Hugging Face (HF_TOKEN / hf login) — queda para que el
#       usuario lo configure manualmente con 'hf auth login' o el token
#
#  QUÉ HACE EL CLI:
#    Gestión de modelos/datasets/spaces y flujos de trabajo de Hugging
#    Face desde la terminal: hf download <repo> (descarga GGUF con resume),
#    hf upload, hf auth login, etc. Complementa el ecosistema llama.cpp
#    (los .gguf del Chat IA se descargan de repos HF).
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.2.0 | Agosto 2026 — mitigación real para el límite conocido
#  documentado en docs/humano272.md ("hf_xet build de extensión Rust sin
#  wheel prebuilt para Termux/Android, mismo patrón que mistralvibe pero sin
#  investigar una mitigación real"). Confirmado por WebFetch a
#  pypi.org/pypi/hf_xet/json y pypi.org/pypi/huggingface_hub/json: hf-xet es
#  dependencia base (no opcional) de huggingface_hub en aarch64
#  ("platform_machine == aarch64"), y PyPI solo publica wheels
#  manylinux_2_28/musllinux para linux-aarch64 (glibc/musl, incompatibles
#  con el linker Bionic de Android/Termux) — nunca hay wheel instalable
#  directo, pip cae siempre a compilar el sdist (maturin + toolchain Rust).
#  Mismo patrón "prebuilt-primero, compilar-como-fallback" ya usado por
#  freebuff.sh (runtime bun nativo bun-termux vs. npm) aplicado acá: PASO 1.5
#  ahora intenta descargar un wheel de hf_xet precompilado para
#  aarch64-linux-android desde Releases de kairos-lab (mismo repo, convención
#  de nombre "hf_xet-*-aarch64-android*.whl" — todavía NO existe ningún
#  Release con ese asset, es forward-compatible: en cuanto CI publique uno,
#  este módulo empieza a usarlo sin más cambios). Si no se encuentra (caso
#  actual, siempre hasta que exista ese Release), cae al PASO 1.6 —
#  toolchain rust/clang (antes AUSENTE del todo en este script, a diferencia
#  de mistralvibe.sh que sí lo tenía — sin esto el pip install del PASO 2
#  intentaba compilar hf_xet sin cargo disponible y fallaba/colgaba sin pista
#  de la causa real, igual que el bug ya documentado de mistralvibe en
#  docs/humano215.md). Ver docs/arquitectura/HF_XET_PREBUILD_PLAN_2026-08-28.md
#  para el plan completo de cross-compile en CI (bloqueado en esta ronda por
#  falta de toolchain Rust en la PC de desarrollo — necesita go-ahead del
#  dueño del proyecto antes de agregarlo a build-app.yml, ver esa nota).
#  VERSIÓN: 1.1.0 | Agosto 2026 (comparación línea por línea contra
#  referencia/termux/core-termux-main/core/tools/ai/hugging-face/install.sh,
#  ver docs/viejo/AUDITORIA_MODULOS_IA_DEV_VS_REFERENCIA_2026-08-19.md
#  sección "hf.sh vs hugging-face": agregada dependencia python3 (el
#  instalador oficial de HF cae a un flujo pip si no hay binario prebuilt
#  para la arquitectura) y limpieza de config/caché propia en desinstalación
#  profunda (ver ModuleController.kt::deepUninstallPlan). No se adoptó el
#  parcheo de PATH en .bashrc/.zshrc del script de referencia — Kairos ya
#  agrega "$HOME/.local/bin" al PATH global en el bootstrap de kairos.sh
#  (bloque BASHRC_BLOCK), así que sería una adopción redundante.
#  VERSIÓN previa: 1.0.0 | Agosto 2026 (nuevo módulo, candidato core-termux
#  v4.25.0, ver docs/humano/humano123.md)
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
{"id":"hf","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. El instalador oficial
# (hf.co/cli/install.sh) no documenta su layout exacto de archivos — en vez
# de asumir una ruta fija, se resuelve `command -v hf` en vivo al momento de
# empaquetar (path real de ESTE device, no una suposición).
if $DESCRIBE_FILES; then
  _hf_bin=$(command -v hf 2>/dev/null || echo "$HOME/.local/bin/hf")
  _hf_cli_dir="${HF_HOME:+$HF_HOME/cli}"
  _hf_cli_dir="${_hf_cli_dir:-$HOME/.hf-cli}"
  jq -n \
    --arg p1 "$_hf_bin" \
    --arg glob "$_hf_cli_dir/**" \
    --arg dep1_check "command -v curl >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1" \
    --arg verify "command -v hf >/dev/null 2>&1" \
    '{
      id: "hf",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-hf",
      version_registry_key: "",
      files: [
        {path: $p1, required: true, note: "binario/wrapper del instalador oficial hf.co/cli/install.sh — ruta resuelta en vivo via command -v hf"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "venv real que crea/usa hf.co/cli/install.sh ($HF_HOME/cli/venv o ~/.hf-cli/venv por default, confirmado via WebFetch al script real 2026-08-28) — el binario en PATH depende de este venv para correr"}
      ],
      dependencies: [
        {id: "curl_python3", check_cmd: $dep1_check, install_hint: "pkg install -y curl python"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No incluye HF_TOKEN/login — el usuario debe autenticarse de nuevo con \"hf auth login\" tras reinstalar"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_hf_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^hf_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "hf_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^hf_${1}=done" "$CHECKPOINT" 2>/dev/null; }

get_installed_ver() {
  command -v hf &>/dev/null && hf --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || echo ""
}

# ── Mitigación hf_xet: wheel prebuilt primero, compilar como fallback ────
# Ver nota de versión 1.2.0 arriba. hf_xet es dependencia base de
# huggingface_hub en aarch64 y PyPI no publica ningún wheel compatible con
# Bionic (Android) — sin esta mitigación, el instalador oficial (PASO 2)
# siempre termina compilando hf_xet desde fuente (maturin + Rust).
HF_XET_PREBUILT_REPO="Honkonx/kairos-lab"

# Ruta REAL del venv que crea hf.co/cli/install.sh (confirmado por WebFetch
# al script real, 2026-08-28): "$HF_CLI_DIR/venv", con HF_CLI_DIR="$HF_HOME/cli"
# si HF_HOME está seteado, o "$HOME/.hf-cli" si no. El instalador reutiliza
# ese venv si ya existe (no lo recrea salvo que se le pase --force, y hf.sh
# nunca le pasa ese flag) — por eso instalar hf_xet ACÁ, en esa ruta exacta,
# antes de correr el instalador, es lo que evita la recompilación: instalar
# en el python3 del sistema (como se hizo en un primer intento) no sirve de
# nada porque install.sh usa un venv aislado, no el Python del sistema.
_hf_xet_venv_dir() {
  local _cli_dir="${HF_HOME:+$HF_HOME/cli}"
  echo "${_cli_dir:-$HOME/.hf-cli}/venv"
}

# Busca en los Releases de kairos-lab un wheel de hf_xet ya cross-compilado
# para aarch64-linux-android (convención de nombre "hf_xet-*aarch64*android*.whl"),
# lo instala en el venv que va a usar el instalador oficial (creándolo antes
# si todavía no existe, para que install.sh lo detecte y lo reutilice en vez
# de pisarlo). Devuelve 1 (sin error ruidoso) si no existe el asset todavía o
# si algo falla — es un intento best-effort, el fallback real es compilar
# (PASO 1.6).
_hf_try_prebuilt_xet_wheel() {
  local _system_python="$1"
  command -v curl &>/dev/null || return 1

  local _releases_json _asset_url
  _releases_json=$(curl -fsSL "https://api.github.com/repos/${HF_XET_PREBUILT_REPO}/releases?per_page=10" 2>/dev/null)
  [ -z "$_releases_json" ] && return 1
  _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*hf_xet-[^"]*aarch64[^"]*android[^"]*\.whl"' | \
    head -1 | grep -o 'https://[^"]*')
  [ -z "$_asset_url" ] && return 1

  local _venv_dir _venv_python
  _venv_dir=$(_hf_xet_venv_dir)
  _venv_python="$_venv_dir/bin/python"
  if [ ! -x "$_venv_python" ]; then
    info "Creando el venv de HF CLI por adelantado ($_venv_dir) para preinstalar hf_xet..."
    mkdir -p "$(dirname "$_venv_dir")"
    "$_system_python" -m venv "$_venv_dir" >/dev/null 2>&1 || return 1
    # pip_install() (lib.sh) en vez de "$_venv_python" -m pip directo — bug real de
    # instalación concurrente confirmado por ADB (docs/humano281.md): los wrappers bash
    # python3()/pip() de lib.sh solo interceptan el nombre pelado, nunca una ruta absoluta
    # resuelta como $_venv_python, así que este call-site quedaba sin el lock de pip
    # compartido entre módulos (mismo site-packages/pip cache global) pese a que
    # ciberseguridad.sh/mistralvibe.sh ya lo tenían desde el fix de esta misma ronda.
    # pip_install() (lib.sh) ya antepone "-m pip install" internamente — pasarle "install" acá
    # de nuevo duplicaba el argumento y pip lo interpretaba como un requirement literal llamado
    # "install", fallando siempre. Mismo bug confirmado en hermes.sh (ronda 2026-08-29),
    # introducido en el mismo cambio 2026-08-28 (docs/humano281.md).
    pip_install "$_venv_python" --upgrade pip >/dev/null 2>&1
  fi

  info "Wheel prebuilt de hf_xet encontrado en kairos-lab (aarch64-linux-android) — instalando sin compilar..."
  pip_install "$_venv_python" --force-reinstall --no-deps "$_asset_url" >/dev/null 2>&1 || {
    warn "El wheel prebuilt de hf_xet no se pudo instalar en el venv — se compilará desde fuente"
    return 1
  }
  # Verificación funcional real (no solo "pip install" con exit 0) — misma
  # disciplina que verify_binary_installed(), aplicada acá a un import Python.
  "$_venv_python" -c "import hf_xet" >/dev/null 2>&1 || {
    warn "wheel prebuilt de hf_xet instalado pero no importa en el venv del instalador — se compilará desde fuente"
    return 1
  }
  log "hf_xet instalado desde wheel prebuilt en el venv del instalador (sin compilar en el dispositivo)"
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  🤗 HUGGING FACE CLI — Instalador        ║"
  echo "  ║  Modelos · Datasets · Spaces             ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Hugging Face CLI ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Hugging Face CLI? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — curl + python3 ───────────────────────────────────
# python3 se agrega como dependencia porque el instalador oficial de HF cae
# a un flujo pip cuando no hay binario prebuilt para la arquitectura del
# dispositivo (mismo patrón confirmado en install_hugging_face_impl() de
# core-termux-main — declara curl + python3 como dependencias, no solo curl).
step "PASO 1 — Verificando curl y python3"
if check_done "deps"; then
  log "Dependencias ya verificadas [checkpoint]"
else
  pkg_update_with_fallback
  if command -v curl &>/dev/null; then
    log "curl detectado: $(curl --version 2>/dev/null | head -1)"
  else
    info "Instalando curl..."
    pkg install curl -y 2>/dev/null || error "No se pudo instalar curl"
    command -v curl &>/dev/null || error "curl no disponible tras instalación"
  fi
  if command -v python3 &>/dev/null; then
    log "python3 detectado: $(python3 --version 2>/dev/null)"
  else
    info "Instalando python3..."
    pkg install python -y 2>/dev/null || error "No se pudo instalar python3"
    command -v python3 &>/dev/null || error "python3 no disponible tras instalación"
  fi
  mark_done "deps"
fi

# ── PASO 1.5 — hf_xet: wheel prebuilt (si existe) ────────────
# Intento best-effort ANTES de correr el instalador oficial — si funciona,
# el pip install de huggingface_hub del PASO 2 encuentra hf_xet ya
# satisfecho y no intenta compilarlo. Usa el mismo Python que va a usar el
# instalador oficial (venv propio o el del sistema, ver PASO 1).
step "PASO 1.5 — hf_xet: buscando wheel prebuilt (evita compilar Rust en el dispositivo)"
if check_done "xet_prebuilt_attempt"; then
  log "Intento de wheel prebuilt ya resuelto [checkpoint]"
else
  _HF_PYTHON=$(command -v python3 2>/dev/null || command -v python 2>/dev/null)
  if [ -n "$_HF_PYTHON" ] && _hf_try_prebuilt_xet_wheel "$_HF_PYTHON"; then
    mark_done "xet_toolchain"  # no hace falta el toolchain de compilación
  else
    info "Sin wheel prebuilt disponible todavía — se compilará hf_xet desde fuente (PASO 1.6)"
  fi
  mark_done "xet_prebuilt_attempt"
fi

# ── PASO 1.6 — Toolchain de compilación (rust + clang) ────────
# Fallback cuando no hay wheel prebuilt: hf_xet es dependencia base de
# huggingface_hub en aarch64 (confirmado vía pypi.org/pypi/huggingface_hub/json,
# requires_dist: "hf-xet...; platform_machine == aarch64") y PyPI no publica
# ningún wheel compatible con Bionic — pip SIEMPRE cae a compilar el sdist
# (maturin + Rust) si llega hasta acá. Sin este toolchain el PASO 2 fallaba
# sin pista de la causa real (mismo bug ya documentado para mistral-vibe/
# rpds-py en docs/humano215.md, nunca aplicado acá hasta ahora).
step "PASO 1.6 — Toolchain de compilación (rust, clang, make) — fallback si no hubo wheel prebuilt"
if check_done "xet_toolchain"; then
  log "Toolchain de compilación no requerido (wheel prebuilt) o ya verificado [checkpoint]"
else
  info "Instalando: rust clang make libffi openssl pkg-config"
  pkg_update_with_fallback
  pkg install -y rust clang make libffi openssl pkg-config \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" 2>/dev/null || \
    error "No se pudo instalar el toolchain de compilación (rust/clang/make) para hf_xet"
  command -v cargo &>/dev/null || error "cargo no disponible tras instalar rust"
  mark_done "xet_toolchain"
  log "Toolchain de compilación listo (hf_xet se compilará desde fuente)"
fi

# ── PASO 2 — Instalador oficial HF ───────────────────────────
step "PASO 2 — Instalando Hugging Face CLI (instalador oficial)"
if check_done "hf_install"; then
  log "Hugging Face CLI ya instalado [checkpoint]"
else
  info "Ejecutando: curl -LsSf https://hf.co/cli/install.sh | bash"
  curl -LsSf https://hf.co/cli/install.sh | bash 2>&1 | tail -15
  [ ${PIPESTATUS[0]} -eq 0 ] || error "Instalador de Hugging Face falló"
  # El instalador deja el binario en ~/.local/bin — asegurar que quede en PATH.
  export PATH="$HOME/.local/bin:$PATH"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  verify_binary_installed hf || error "hf no ejecuta tras la instalación (revisá manualmente: hf --version)"
  log "Hugging Face CLI instalado: $(hf --version 2>/dev/null | head -1)"
  mark_done "hf_install"
fi

# ── PASO 3 — Login (omitido en modo silencioso) ──────────────
step "PASO 3 — Autenticación"
if check_done "auth"; then
  log "Autenticación ya completada [checkpoint]"
elif $SILENT; then
  warn "Modo silencioso — autenticá con 'hf auth login' o exportá HF_TOKEN manualmente"
  mark_done "auth"
else
  warn "Ejecutá 'hf auth login' para autenticarte (o usá el token)"
  mark_done "auth"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_DATE=$(date +%Y-%m-%d)
registry_write hf \
  "installed=true" \
  "version=${_VER_FINAL:-?}" \
  "channel=official_installer" \
  "install_date=${_DATE}"

notify_event "hf" "install_done" "$_VER_FINAL"
log "Hugging Face CLI instalado correctamente (v${_VER_FINAL:-?})"
rm -f "$CHECKPOINT"
exit 0