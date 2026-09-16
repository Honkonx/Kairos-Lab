#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · yt-dlp.sh (silent mode)
#  yt-dlp — paquete nativo de Termux, id real del paquete "python-yt-dlp" (¡OJO!
#  NO existe un paquete llamado "yt-dlp" a secas en termux-packages — gotcha real
#  de nombre de paquete, ver .claude/rules/empirical-verification-before-fix.md).
#  Descargador de video/audio (YouTube y ~1800 sitios más, fork de youtube-dl).
#
#  Propuesto y verificado en la ronda 2026-09-15 (MEJORAS_PENDIENTES.md, sección
#  "Módulos nuevos — ronda 2026-09-15") contra el índice real de termux-packages
#  (github.com/termux/termux-packages/packages/python-yt-dlp/build.sh —
#  TERMUX_PKG_PROVIDES='yt-dlp', paquete normal, NO root-packages, no necesita
#  root; el binario instalado sí se llama "yt-dlp", solo el nombre del PAQUETE
#  apt es "python-yt-dlp"), no solo asumido por ser una herramienta conocida.
#  TERMUX_PKG_RECOMMENDS="ffmpeg, yt-dlp-ejs" en el build.sh real — yt-dlp
#  funciona sin ffmpeg pero lo necesita para remux/extracción de audio y
#  algunos formatos; Kairos ya tiene un módulo `ffmpeg` propio (categoría
#  multimedia) que cubre esa dependencia opcional sin duplicarla acá.
#
#  USO DESDE APP (KairosApp):
#    bash yt-dlp.sh --silent
#    bash yt-dlp.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ yt-dlp (binario CLI — la descarga en sí (URL, formato, carpeta destino)
#       queda para el usuario vía terminal, es inherentemente un comando por
#       descarga, mismo criterio que `rclone`/`restic`).
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
{"id":"yt-dlp","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. yt-dlp es enteramente un paquete
# apt (python-yt-dlp instala el binario "yt-dlp" vía pip --prefix) — mismo
# criterio que rclone.sh/restic.sh: files:[] deliberado, no un gap sin
# investigar. Los archivos que el usuario descarga con la herramienta son
# estado propio del usuario, no parte del paquete — no se empaquetan.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "yt-dlp", supports_describe_files: true, variant: null,
    package_name: "kairos-module-yt-dlp",
    version_registry_key: "yt-dlp.version",
    files: [], file_globs: [],
    dependencies: [{id: "pkg:python-yt-dlp", check_cmd: "command -v yt-dlp >/dev/null 2>&1", install_hint: "pkg install -y python-yt-dlp"}],
    verify_cmd: "command -v yt-dlp >/dev/null 2>&1 && yt-dlp --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["yt-dlp es enteramente un paquete apt (python-yt-dlp, binario yt-dlp instalado vía pip --prefix) — ya gestionado correctamente por pkg. Los videos/audios descargados por el usuario son estado propio, no se empaquetan"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_yt-dlp_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# El nombre del PAQUETE apt real es "python-yt-dlp" (NO "yt-dlp" a secas) — el
# binario resultante sí se llama "yt-dlp", por eso el check_cmd usa ese nombre.
install_single_pkg "yt-dlp" "yt-dlp" python-yt-dlp


notify_event "yt-dlp" "install_done" ""
exit 0
