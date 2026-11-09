#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · vercel.sh (silent mode)
#  Vercel CLI — npm install -g vercel (requiere Node.js, se instala solo si falta).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/npm/vercel/install.sh
#  (mismo comando real: npm install -g vercel) — ver ronda "paquetes
#  adicionales core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash vercel.sh --silent
#    bash vercel.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (solo si no está ya instalado por otro módulo)
#    ✅ Vercel CLI (deploys desde el dispositivo).
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
{"id":"vercel","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. CLI npm global — el binario real
# se resuelve por PATH al momento de empaquetar (wrapper/symlink de npm, ya
# parcheado por fix_npm_shebang_wrapper en lib.sh), mismo patrón que freebuff.sh.
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v vercel 2>/dev/null || echo "$TERMUX_PREFIX/bin/vercel")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$(npm root -g 2>/dev/null)/vercel/**" \
    --arg verify "command -v vercel >/dev/null 2>&1 && vercel --version >/dev/null 2>&1" \
    '{
      id: "vercel",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-vercel",
      version_registry_key: "vercel.version",
      files: [{path: $path, required: true, note: "Wrapper/symlink npm de vercel, resuelto por PATH al momento de empaquetar"}],
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
CHECKPOINT="$HOME/.install_vercel_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_npm_global "vercel" "vercel" "vercel"

notify_event "vercel" "install_done" ""
exit 0
