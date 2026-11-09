#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · clang.sh (silent mode)
#  C/C++ (Clang) — paquete nativo de Termux (pkg install clang).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/clang/install.sh
#  (mismo comando real: pkg install clang) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash clang.sh --silent
#    bash clang.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Clang (compilador C/C++, incluye herramientas LLVM asociadas del paquete Termux).
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
{"id":"clang","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Clang es un paquete apt completo
# (compilador + herramientas LLVM asociadas + libs/headers) instalado vía
# install_single_pkg() — cientos de archivos gestionados correctamente por pkg,
# igual criterio que python.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "clang", supports_describe_files: true, variant: null,
    package_name: "kairos-module-clang",
    version_registry_key: "clang.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:clang", check_cmd: "command -v clang >/dev/null 2>&1", install_hint: "pkg install -y clang"}],
    verify_cmd: "command -v clang >/dev/null 2>&1 && clang --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Clang es enteramente un paquete apt (compilador + herramientas LLVM asociadas + libs/headers) — cientos de archivos ya gestionados correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos — reinstalar via pkg es mas simple y correcto que un snapshot de archivos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_clang_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "clang" "clang" clang


notify_event "clang" "install_done" ""
exit 0
