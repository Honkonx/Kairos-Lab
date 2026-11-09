#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · livesrv.sh (silent mode)
#  Live Server — npm install -g live-server (requiere Node.js, se instala solo si falta).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/npm/livesrv/install.sh
#  (mismo comando real: npm install -g live-server) — ver ronda "paquetes
#  adicionales core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash livesrv.sh --silent
#    bash livesrv.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (solo si no está ya instalado por otro módulo)
#    ✅ Live Server (servidor estático con recarga automática).
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
{"id":"livesrv","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. CLI npm global (paquete real
# "live-server", binario "live-server") — el binario real se resuelve por PATH
# al momento de empaquetar (wrapper/symlink de npm, ya parcheado por
# fix_npm_shebang_wrapper en lib.sh), mismo patrón que freebuff.sh.
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v live-server 2>/dev/null || echo "$TERMUX_PREFIX/bin/live-server")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$(npm root -g 2>/dev/null)/live-server/**" \
    --arg verify "command -v live-server >/dev/null 2>&1 && live-server --version >/dev/null 2>&1" \
    '{
      id: "livesrv",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-livesrv",
      version_registry_key: "livesrv.version",
      files: [{path: $path, required: true, note: "Wrapper/symlink npm de live-server, resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob, required: true, note: "árbol npm global real que el wrapper/symlink de arriba ejecuta — sin esto el wrapper queda roto en un device nuevo"}
      ],
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
CHECKPOINT="$HOME/.install_livesrv_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_npm_global "livesrv" "live-server" "live-server"

notify_event "livesrv" "install_done" ""
exit 0
