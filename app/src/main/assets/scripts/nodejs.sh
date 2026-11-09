#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · nodejs.sh (silent mode)
#  Node.js LTS — paquete nativo de Termux (pkg install nodejs-lts).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/nodejs/install.sh
#  (mismo comando real: pkg install nodejs-lts) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash nodejs.sh --silent
#    bash nodejs.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (incluye npm) + Corepack habilitado (pnpm/yarn sin instalación aparte).
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
{"id":"nodejs","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Node.js LTS es un paquete apt
# completo (runtime + npm + corepack) instalado vía install_single_pkg() —
# mismo criterio que clang.sh/python.sh: files:[] deliberado, no un gap sin
# investigar. Es además dependencia compartida de otros ~10 módulos npm-based
# de este proyecto (ensure_node_installed en lib.sh), otro motivo para no
# duplicarlo en un .deb propio.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "nodejs", supports_describe_files: true, variant: null,
    package_name: "kairos-module-nodejs",
    version_registry_key: "nodejs.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:nodejs-lts", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}],
    verify_cmd: "command -v node >/dev/null 2>&1 && node --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Node.js LTS es enteramente un paquete apt (runtime + npm + corepack) — ya gestionado correctamente por pkg. Es tambien dependencia compartida de otros modulos npm-based de este proyecto (ensure_node_installed en lib.sh) — reinstalar via pkg es mas simple que un snapshot propio"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_nodejs_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "nodejs" "node" nodejs-lts
if command -v corepack &>/dev/null; then
  corepack enable &>/dev/null && log "Corepack habilitado (pnpm/yarn)"
fi

notify_event "nodejs" "install_done" ""
exit 0
