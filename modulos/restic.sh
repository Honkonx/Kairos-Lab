#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · restic.sh (silent mode)
#  restic — paquete nativo de Termux (pkg install restic). Backups incrementales
#  cifrados con versionado real, puede usar como destino cualquier remote que
#  el usuario ya haya configurado con el módulo `rclone` (rclone serve restic /
#  restic con backend "rclone:<remote>:<path>") — complementa a rclone en vez de
#  solaparlo: rclone sincroniza/monta, restic respalda con historial+cifrado.
#
#  Propuesto y verificado en la ronda 2026-09-15 (MEJORAS_PENDIENTES.md, sección
#  "Módulos nuevos — ronda 2026-09-15") contra el índice real de termux-packages
#  (github.com/termux/termux-packages/packages/restic/build.sh, paquete normal
#  — NO root-packages, no necesita root), no solo asumido por ser una
#  herramienta conocida en desktop Linux.
#
#  USO DESDE APP (KairosApp):
#    bash restic.sh --silent
#    bash restic.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ restic (binario CLI — la creación/gestión de repositorios (`restic init`,
#       `restic backup`, `restic snapshots`) queda para el usuario, es
#       inherentemente interactiva/específica de cada backup por diseño de la
#       herramienta upstream, mismo criterio que `rclone config` en rclone.sh).
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Septiembre 2026
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
{"id":"restic","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. restic es un paquete apt completo
# (binario Go estático) instalado vía install_single_pkg() — mismo criterio que
# rclone.sh/golang.sh: files:[] deliberado, no un gap sin investigar. Los
# repositorios de backup del usuario (ubicación/credenciales elegidas por él)
# son estado propio del usuario, no parte del paquete — no se empaquetan.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "restic", supports_describe_files: true, variant: null,
    package_name: "kairos-module-restic",
    version_registry_key: "restic.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:restic", check_cmd: "command -v restic >/dev/null 2>&1", install_hint: "pkg install -y restic"}],
    verify_cmd: "command -v restic >/dev/null 2>&1 && restic version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["restic es enteramente un paquete apt (binario Go estático) — ya gestionado correctamente por pkg. Los repositorios de backup del usuario (destino/contraseña elegidos por él) son estado propio, no se empaquetan"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_restic_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "restic" "restic" restic


notify_event "restic" "install_done" ""
exit 0
