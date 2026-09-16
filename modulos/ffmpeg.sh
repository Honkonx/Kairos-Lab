#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ffmpeg.sh (silent mode)
#  FFmpeg — paquete nativo de Termux (pkg install ffmpeg).
#
#  Propuesto en la ronda 2026-09-15 ("más módulos, el bootstrap") —
#  categoría nueva "multimedia": Kairos no tenía ningún módulo de audio/video.
#  Verificado ANTES de escribir este script contra el índice real de
#  termux-packages (github.com/termux/termux-packages/packages/ffmpeg/build.sh,
#  TERMUX_PKG_VERSION="8.1.2", paquete normal — NO root-packages/x11-packages,
#  no necesita root), no solo asumido por ser un paquete conocido en desktop Linux.
#
#  USO DESDE APP (KairosApp):
#    bash ffmpeg.sh --silent
#    bash ffmpeg.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ ffmpeg/ffprobe (conversión, recorte, extracción de audio, thumbnails,
#       compresión de video/audio grabado o descargado en el dispositivo).
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
{"id":"ffmpeg","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. FFmpeg es un paquete apt completo
# (binario + libs) instalado vía install_single_pkg() — mismo criterio que
# golang.sh/clang.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "ffmpeg", supports_describe_files: true, variant: null,
    package_name: "kairos-module-ffmpeg",
    version_registry_key: "ffmpeg.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:ffmpeg", check_cmd: "command -v ffmpeg >/dev/null 2>&1", install_hint: "pkg install -y ffmpeg"}],
    verify_cmd: "command -v ffmpeg >/dev/null 2>&1 && ffmpeg -version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["FFmpeg es enteramente un paquete apt (binario + libs) — ya gestionado correctamente por pkg. No vale la pena empaquetarlo como .deb propio de Kairos"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ffmpeg_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

install_single_pkg "ffmpeg" "ffmpeg" ffmpeg


notify_event "ffmpeg" "install_done" ""
exit 0
