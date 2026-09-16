#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · gh.sh (silent mode)
#  GitHub CLI — paquete nativo de Termux (pkg install gh).
#
#  FUENTE: paquete oficial de Termux (gh) — hueco real confirmado
#  auditando referencia/ia/termux_AI-master (no cubierto por ningún
#  módulo existente de modulos/).
#
#  USO DESDE APP (KairosApp):
#    bash gh.sh --silent
#    bash gh.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ GitHub CLI (gh).
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login interactivo (gh auth login) — queda para que el usuario
#       lo haga manualmente después, no se puede automatizar sin
#       credenciales.
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
{"id":"gh","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. gh es un paquete apt completo
# instalado vía install_single_pkg() — mismo criterio que golang.sh/clang.sh:
# files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "gh", supports_describe_files: true, variant: null,
    package_name: "kairos-module-gh",
    version_registry_key: "gh.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:gh", check_cmd: "command -v gh >/dev/null 2>&1", install_hint: "pkg install -y gh"}],
    verify_cmd: "command -v gh >/dev/null 2>&1 && gh --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["gh es enteramente un paquete apt — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos — reinstalar via pkg es mas simple y correcto que un snapshot de archivos", "No automatiza el login (gh auth login) — queda manual"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_gh_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "gh" "gh" gh

notify_event "gh" "install_done" ""
exit 0
