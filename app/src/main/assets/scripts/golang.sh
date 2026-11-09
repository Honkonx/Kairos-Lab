#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · golang.sh (silent mode)
#  Go — paquete nativo de Termux (pkg install golang).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/golang/install.sh
#  (mismo comando real: pkg install golang) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash golang.sh --silent
#    bash golang.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Go (compilador + toolchain).
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
{"id":"golang","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Go es un paquete apt completo
# (toolchain + stdlib) instalado vía install_single_pkg() — mismo criterio que
# clang.sh/python.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "golang", supports_describe_files: true, variant: null,
    package_name: "kairos-module-golang",
    version_registry_key: "golang.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:golang", check_cmd: "command -v go >/dev/null 2>&1", install_hint: "pkg install -y golang"}],
    verify_cmd: "command -v go >/dev/null 2>&1 && go version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Go es enteramente un paquete apt (toolchain + stdlib) — cientos de archivos ya gestionados correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos — reinstalar via pkg es mas simple y correcto que un snapshot de archivos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_golang_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "golang" "go" golang


notify_event "golang" "install_done" ""
exit 0
