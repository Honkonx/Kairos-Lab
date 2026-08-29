#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · localtunnel.sh (silent mode)
#  Localtunnel — npm install -g localtunnel (requiere Node.js).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/npm/localtunnel/install.sh
#  (mismo comando real: npm install -g localtunnel, + fix de Android: el paquete
#  openurl intenta abrir el navegador con un comando que no existe en Termux —
#  se parchea openurl.js para usar termux-open-url en 'android') — ver ronda
#  "paquetes adicionales core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash localtunnel.sh --silent
#    bash localtunnel.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (solo si no está ya instalado por otro módulo)
#    ✅ Localtunnel (comando `lt`)
#    ✅ Fix openurl.js para Android (termux-open-url)
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

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

if $DESCRIBE; then
  cat << 'JSON'
{"id":"localtunnel","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. CLI npm global (paquete
# "localtunnel", binario "lt") — el binario real se resuelve por PATH al
# momento de empaquetar (wrapper/symlink de npm, ya parcheado por
# fix_npm_shebang_wrapper en lib.sh), mismo patrón que freebuff.sh. El fix
# real de openurl.js (patch Android, ver _localtunnel_fix_openurl más abajo)
# vive DENTRO del árbol node_modules de localtunnel — cubierto por el glob.
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v lt 2>/dev/null || echo "$TERMUX_PREFIX/bin/lt")
  _nm=$(npm root -g 2>/dev/null || echo "$TERMUX_PREFIX/lib/node_modules")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$_nm/localtunnel/**" \
    --arg verify "command -v lt >/dev/null 2>&1 && lt --version >/dev/null 2>&1" \
    '{
      id: "localtunnel",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-localtunnel",
      version_registry_key: "localtunnel.version",
      files: [{path: $path, required: true, note: "Wrapper/symlink npm de lt, resuelto por PATH al momento de empaquetar"}],
      file_globs: [{pattern: $glob, required: true, note: "árbol npm global de localtunnel, incluye openurl.js ya parcheado (termux-open-url) — ver _localtunnel_fix_openurl()"}],
      dependencies: [{id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No reinstala Node.js — asume que el device destino ya tiene el mismo Termux base"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_localtunnel_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

_localtunnel_fix_openurl() {
  local openurl_js
  openurl_js="$(npm root -g)/localtunnel/node_modules/openurl/openurl.js"
  if [ ! -f "$openurl_js" ]; then
    openurl_js="$(npm root -g)/openurl/openurl.js"
  fi
  if [ -f "$openurl_js" ]; then
    sed -i "/default:/i\\
    case 'android':\\
        command = 'termux-open-url';\\
        break;" "$openurl_js"
    info "Fix openurl.js aplicado (termux-open-url)"
  fi
}

# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# antes esto era "install_npm_global localtunnel localtunnel lt" seguido de
# _localtunnel_fix_openurl() — pero install_npm_global() YA verifica "lt --version" internamente
# (lib.sh, verify_binary_installed) ANTES de devolver el control acá, y esa verificación falla
# SIEMPRE porque openurl (dependencia de lt, intenta abrir el navegador) revienta con
# "Unsupported platform: android" al sólo importarse — install_npm_global() llama a error()
# (exit 1) ahí mismo, así que _localtunnel_fix_openurl() de abajo NUNCA se llegaba a ejecutar:
# código muerto, el fix nunca se aplicaba en la práctica (confirmado en dispositivo real:
# "lt --version" seguía roto tras una instalación "exitosa"). Fix: no usar install_npm_global()
# de punta a punta acá — instalar+parchear+verificar en el orden correcto, a mano.
if command -v lt &>/dev/null && [ "${FORCE:-false}" != "true" ]; then
  log "localtunnel ya instalado"
  registry_write localtunnel "installed=true"
else
  ensure_node_installed
  if ! npm install -g localtunnel &>/dev/null; then
    error "No se pudo instalar localtunnel (npm install -g localtunnel falló)"
  fi
  command -v lt &>/dev/null || error "localtunnel no disponible tras la instalación (npm)"
  fix_npm_shebang_wrapper "lt" "localtunnel"
  _localtunnel_fix_openurl
  verify_binary_installed "lt" || error "localtunnel no ejecuta tras la instalación (revisá manualmente: lt --version)"
  registry_install localtunnel "$(npm ls -g localtunnel --depth=0 2>/dev/null | sed -nE 's#.*localtunnel@([0-9.A-Za-z-]+).*#\1#p')"
  log "localtunnel instalado"
fi

notify_event "localtunnel" "install_done" ""
exit 0
