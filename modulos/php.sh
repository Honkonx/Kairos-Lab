#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · php.sh (silent mode)
#  PHP — paquete nativo de Termux (pkg install php).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/php/install.sh
#  (mismo comando real: pkg install php) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash php.sh --silent
#    bash php.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ PHP CLI.
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
{"id":"php","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. PHP es un paquete apt completo
# (intérprete + extensiones) instalado vía install_single_pkg() — mismo criterio
# que clang.sh/python.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "php", supports_describe_files: true, variant: null,
    package_name: "kairos-module-php",
    version_registry_key: "php.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:php", check_cmd: "command -v php >/dev/null 2>&1", install_hint: "pkg install -y php"}],
    verify_cmd: "command -v php >/dev/null 2>&1 && php --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["PHP es enteramente un paquete apt (interprete + extensiones) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_php_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "php" "php" php


notify_event "php" "install_done" ""
exit 0
