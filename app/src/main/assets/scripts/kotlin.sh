#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · kotlin.sh (silent mode)
#  Kotlin — paquete nativo de Termux (pkg install kotlin).
#
#  Agregado 2026-08-25 (auditoría de código+docs oficiales pedida por el usuario,
#  "toca agregar mas lenguajes") — mismo patrón exacto que php.sh/rust.sh/golang.sh,
#  confirmado real: "kotlin" existe como paquete oficial de Termux
#  (packages.termux.dev), binario real "kotlinc" (compilador). Depende de un JDK
#  real (el propio paquete de Termux ya declara openjdk-17 como dependencia apt,
#  no hace falta instalarlo aparte acá).
#
#  USO DESDE APP (KairosApp):
#    bash kotlin.sh --silent
#    bash kotlin.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ kotlinc (compilador) + kotlin (runtime del script).
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
{"id":"kotlin","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Kotlin es un paquete apt completo
# (compilador kotlinc + runtime + dependencia openjdk-17) instalado vía
# install_single_pkg() — mismo criterio que clang.sh/python.sh: files:[]
# deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "kotlin", supports_describe_files: true, variant: null,
    package_name: "kairos-module-kotlin",
    version_registry_key: "kotlin.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:kotlin", check_cmd: "command -v kotlinc >/dev/null 2>&1", install_hint: "pkg install -y kotlin"}],
    verify_cmd: "command -v kotlinc >/dev/null 2>&1 && kotlinc -version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Kotlin es enteramente un paquete apt (compilador kotlinc + runtime + dependencia openjdk-17) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_kotlin_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "kotlin" "kotlinc" kotlin

notify_event "kotlin" "install_done" ""
exit 0
