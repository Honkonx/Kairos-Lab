#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · nestjs.sh (silent mode)
#  NestJS CLI — npm install -g @nestjs/cli (requiere Node.js, se instala solo si falta).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/npm/nestjs/install.sh
#  (mismo comando real: npm install -g @nestjs/cli) — ver ronda "paquetes
#  adicionales core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash nestjs.sh --silent
#    bash nestjs.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (solo si no está ya instalado por otro módulo)
#    ✅ NestJS CLI (nest).
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
{"id":"nestjs","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. CLI npm global — el binario real
# se resuelve por PATH al momento de empaquetar (wrapper/symlink de npm, ya
# parcheado por fix_npm_shebang_wrapper en lib.sh), mismo patrón que freebuff.sh.
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v nest 2>/dev/null || echo "$TERMUX_PREFIX/bin/nest")
  jq -n \
    --arg path "$_bin" \
    --arg glob "$(npm root -g 2>/dev/null)/@nestjs/**" \
    --arg verify "command -v nest >/dev/null 2>&1 && nest --version >/dev/null 2>&1" \
    '{
      id: "nestjs",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-nestjs",
      version_registry_key: "nestjs.version",
      files: [{path: $path, required: true, note: "Wrapper/symlink npm de @nestjs/cli, resuelto por PATH al momento de empaquetar"}],
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
CHECKPOINT="$HOME/.install_nestjs_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_npm_global "nestjs" "@nestjs/cli" "nest"

notify_event "nestjs" "install_done" ""
exit 0
