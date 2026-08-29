#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ruby.sh (silent mode)
#  Ruby — paquete nativo de Termux (pkg install ruby).
#
#  Agregado 2026-08-25 (auditoría de código+docs oficiales pedida por el usuario,
#  "toca agregar mas lenguajes") — mismo patrón exacto que php.sh/rust.sh/golang.sh,
#  confirmado real: "ruby" existe como paquete oficial de Termux (packages.termux.dev).
#
#  USO DESDE APP (KairosApp):
#    bash ruby.sh --silent
#    bash ruby.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Ruby CLI + gem (gestor de paquetes que trae el propio paquete).
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
{"id":"ruby","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Ruby es un paquete apt completo
# (intérprete + stdlib + gems core) instalado vía install_single_pkg() — mismo
# criterio que clang.sh/python.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "ruby", supports_describe_files: true, variant: null,
    package_name: "kairos-module-ruby",
    version_registry_key: "ruby.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:ruby", check_cmd: "command -v ruby >/dev/null 2>&1", install_hint: "pkg install -y ruby"}],
    verify_cmd: "command -v ruby >/dev/null 2>&1 && ruby --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Ruby es enteramente un paquete apt (interprete + stdlib + gems core) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ruby_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "ruby" "ruby" ruby

notify_event "ruby" "install_done" ""
exit 0
