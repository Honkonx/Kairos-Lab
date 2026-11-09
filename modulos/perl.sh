#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · perl.sh (silent mode)
#  Perl — paquete nativo de Termux (pkg install perl).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/lang/perl/install.sh
#  (mismo comando real: pkg install perl) — ver ronda "paquetes adicionales
#  core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash perl.sh --silent
#    bash perl.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Perl (intérprete + herramientas asociadas, ej. psqlformat lo usa como dependencia).
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
{"id":"perl","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Perl es un paquete apt completo
# (intérprete + módulos core) instalado vía install_single_pkg() — mismo
# criterio que clang.sh/python.sh: files:[] deliberado, no un gap sin
# investigar. También es dependencia de psqlformat.sh (pkg extra).
if $DESCRIBE_FILES; then
  jq -n '{
    id: "perl", supports_describe_files: true, variant: null,
    package_name: "kairos-module-perl",
    version_registry_key: "perl.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:perl", check_cmd: "command -v perl >/dev/null 2>&1", install_hint: "pkg install -y perl"}],
    verify_cmd: "command -v perl >/dev/null 2>&1 && perl --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Perl es enteramente un paquete apt (interprete + modulos core) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_perl_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "perl" "perl" perl


notify_event "perl" "install_done" ""
exit 0
