#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · rust.sh (silent mode)
#  Rust — paquete nativo de Termux (pkg install rust).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/rust/install.sh
#  (mismo comando real: pkg install rust) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash rust.sh --silent
#    bash rust.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Rust (rustc + cargo).
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
{"id":"rust","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Rust es un paquete apt completo
# (rustc + cargo + stdlib) instalado vía install_single_pkg() — mismo criterio
# que clang.sh/python.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "rust", supports_describe_files: true, variant: null,
    package_name: "kairos-module-rust",
    version_registry_key: "rust.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:rust", check_cmd: "command -v rustc >/dev/null 2>&1", install_hint: "pkg install -y rust"}],
    verify_cmd: "command -v rustc >/dev/null 2>&1 && rustc --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Rust es enteramente un paquete apt (rustc + cargo + stdlib) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_rust_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "rust" "rustc" rust


notify_event "rust" "install_done" ""
exit 0
