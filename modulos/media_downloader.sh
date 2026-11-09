#!/data/data/com.termux/files/usr/bin/bash
# Kairos · descargador unificado de vídeo/audio.
# El control de URL, formato y destino vive en la interfaz nativa; este script solo
# instala y valida el motor (ffmpeg + python-yt-dlp), nunca pide comandos en terminal.

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
while [ $# -gt 0 ]; do
  case "$1" in
    --silent) SILENT=true ;;
    --force) FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
  shift
done

if $DESCRIBE; then
  printf '%s\n' '{"id":"media-downloader","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}'
  exit 0
fi

if $DESCRIBE_FILES; then
  jq -n '{
    id: "media-downloader", supports_describe_files: true, variant: null,
    package_name: "kairos-module-media-downloader",
    version_registry_key: "media-downloader.version",
    files: [], file_globs: [],
    dependencies: [
      {id: "pkg:python-yt-dlp", check_cmd: "command -v yt-dlp >/dev/null 2>&1", install_hint: "pkg install -y python-yt-dlp"},
      {id: "pkg:ffmpeg", check_cmd: "command -v ffmpeg >/dev/null 2>&1", install_hint: "pkg install -y ffmpeg"}
    ],
    verify_cmd: "command -v yt-dlp >/dev/null 2>&1 && yt-dlp --version >/dev/null 2>&1 && command -v ffmpeg >/dev/null 2>&1 && ffmpeg -version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["Los vídeos y audios descargados son archivos del usuario y no forman parte del paquete"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_media_downloader_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

if ! command -v yt-dlp >/dev/null 2>&1 || ! command -v ffmpeg >/dev/null 2>&1 || $FORCE; then
  pkg_update_with_fallback
  pkg install -y python-yt-dlp ffmpeg || error "No se pudieron instalar yt-dlp y FFmpeg"
fi
command -v yt-dlp >/dev/null 2>&1 || error "yt-dlp no está disponible después de instalarlo"
command -v ffmpeg >/dev/null 2>&1 || error "FFmpeg no está disponible después de instalarlo"
yt-dlp --version >/dev/null 2>&1 || error "yt-dlp no pudo ejecutarse"
ffmpeg -version >/dev/null 2>&1 || error "FFmpeg no pudo ejecutarse"
registry_install "media-downloader" "yt-dlp $(yt-dlp --version 2>&1 | head -1) + ffmpeg"
notify_event "media-downloader" "install_done" ""
log "Descargador listo: yt-dlp + FFmpeg"
exit 0
