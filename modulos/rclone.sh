#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · rclone.sh (silent mode)
#  rclone — paquete nativo de Termux (pkg install rclone). "rsync para la nube":
#  sincroniza/monta 70+ proveedores (Google Drive, Dropbox, S3, WebDAV, SFTP, ...).
#
#  Expande la categoría "nube" de Kairos más allá de lo que ya cubre el módulo
#  `remote` (SSH/cloudflared): rclone es la pieza que falta para que el usuario
#  pueda respaldar/sincronizar datos del propio teléfono contra un proveedor
#  externo sin salir de la app (la config real de un remote, `rclone config`,
#  es interactiva por diseño de la herramienta; ese es el único paso que hoy
#  sigue requiriendo terminal hasta que exista UI propia).
#  Verificado ANTES de escribir este script contra el índice real de
#  termux-packages (github.com/termux/termux-packages/packages/rclone/build.sh,
#  TERMUX_PKG_VERSION="1.75.1", paquete normal — NO root-packages, no necesita
#  root), no solo asumido por ser una herramienta conocida en desktop Linux.
#
#  USO DESDE APP (KairosApp):
#    bash rclone.sh --silent
#    bash rclone.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ rclone (binario CLI — configuración de remotes queda para el usuario,
#       `rclone config` es interactivo por diseño de la herramienta).
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
{"id":"rclone","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. rclone es un paquete apt completo
# (binario Go estático) instalado vía install_single_pkg() — mismo criterio que
# golang.sh/clang.sh: files:[] deliberado, no un gap sin investigar. La config de
# remotes del usuario (~/.config/rclone/rclone.conf) es estado propio del
# usuario, no parte del paquete — no se empaqueta.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "rclone", supports_describe_files: true, variant: null,
    package_name: "kairos-module-rclone",
    version_registry_key: "rclone.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:rclone", check_cmd: "command -v rclone >/dev/null 2>&1", install_hint: "pkg install -y rclone"}],
    verify_cmd: "command -v rclone >/dev/null 2>&1 && rclone version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["rclone es enteramente un paquete apt (binario Go estático) — ya gestionado correctamente por pkg. La config de remotes del usuario (~/.config/rclone/rclone.conf) es estado propio, no se empaqueta"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_rclone_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "rclone" "rclone" rclone


notify_event "rclone" "install_done" ""
exit 0
