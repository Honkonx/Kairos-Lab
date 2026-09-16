#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · syncthing.sh (silent mode)
#  Syncthing — paquete nativo de Termux (pkg install syncthing). Sincronización
#  P2P de archivos entre dispositivos, sin depender de ningún proveedor cloud
#  (a diferencia de rclone, que sincroniza CONTRA un proveedor externo) —
#  expone su propia UI web (puerto 8384 por default), mismo patrón de módulo
#  con switch+puerto+webview que ya usan n8n/ollama.
#
#  Propuesto y verificado en la ronda 2026-09-15 (MEJORAS_PENDIENTES.md, sección
#  "Módulos nuevos — ronda 2026-09-15") contra el índice real de termux-packages
#  (github.com/termux/termux-packages/packages/syncthing/build.sh,
#  TERMUX_PKG_VERSION="2.1.5", paquete normal — NO root-packages, no necesita
#  root), no solo asumido por ser una herramienta conocida en desktop Linux.
#  Syncthing 2.x requiere el subcomando "serve" (versiones 1.x corrían directo
#  con solo flags) — confirmado contra el build.sh real, no asumido.
#
#  USO DESDE APP (KairosApp):
#    bash syncthing.sh --silent
#    bash syncthing.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ syncthing (binario CLI + daemon)
#    ✅ ~/scripts/syncthing/start.sh|stop.sh — mismo patrón (tmux + puerto fijo)
#       que ya usa cactus.sh PASO 5 / llamaserver.sh, para que la app
#       prenda/apague el servicio con el switch estándar de módulos hasSwitch
#       (ver GenericModuleFragment.kt, genérico — este módulo no necesita
#       Fragment propio).
#    ✅ Registry actualizado (syncthing.*)
#
#  La configuración de carpetas/dispositivos a sincronizar (pairing, IDs de
#  dispositivo) queda para el usuario vía la UI web propia de Syncthing en
#  http://localhost:8384 — es inherentemente interactiva por diseño de la
#  herramienta upstream (mismo criterio que `rclone config` en rclone.sh) —
#  único paso que hoy sigue requiriendo salir de la app, vía el webviewUrl
#  que GenericModuleFragment ya abre embebido.
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
{"id":"syncthing","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. El binario syncthing en sí es un
# paquete apt normal (ya gestionado por pkg) — los ÚNICOS archivos propios de
# Kairos que este módulo agrega son los wrappers start.sh/stop.sh de control
# del servicio (mismo criterio que cactus.sh PASO 5, que sí los lista). La
# config real de Syncthing (~/.config/syncthing/, carpetas/dispositivos
# pareados por el usuario) es estado propio del usuario, no se empaqueta.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$HOME/scripts/syncthing/start.sh" \
    --arg p2 "$HOME/scripts/syncthing/stop.sh" \
    --arg patch "chmod +x \"$HOME/scripts/syncthing/start.sh\" \"$HOME/scripts/syncthing/stop.sh\" 2>/dev/null || true" \
    '{
      id: "syncthing", supports_describe_files: true, variant: null,
      package_name: "kairos-module-syncthing",
      version_registry_key: "syncthing.version",
      files: [
        {path: $p1, mode: "0755"},
        {path: $p2, mode: "0755"}
      ],
      file_globs: [],
      dependencies: [{id: "pkg:syncthing", check_cmd: "command -v syncthing >/dev/null 2>&1", install_hint: "pkg install -y syncthing"}],
      verify_cmd: "command -v syncthing >/dev/null 2>&1 && syncthing --version >/dev/null 2>&1",
      patch_cmd: $patch,
      not_covered: ["Config real de Syncthing (~/.config/syncthing/, carpetas/dispositivos pareados) es estado propio del usuario, no se empaqueta"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_syncthing_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

step "PASO 1 — Instalando syncthing"
install_single_pkg "syncthing" "syncthing" syncthing

# ── PASO 2 — Scripts de control del servicio (start/stop) ──────────────
# Mismo patrón (tmux + script start/stop + puerto fijo) que cactus.sh PASO 5 /
# llamaserver.sh — lo que la app arranca/detiene con el switch estándar de
# módulos hasSwitch (ver GenericModuleFragment.kt).
step "PASO 2 — Scripts de control del servicio"
if check_done "serve_scripts"; then
  log "Scripts de servicio ya instalados [checkpoint]"
else
  mkdir -p "$HOME/scripts/syncthing"

  cat > "$HOME/scripts/syncthing/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="syncthing-server"
LOG="\$HOME/kairos_logs/syncthing_serve.log"
PORT="\${SYNCTHING_GUI_PORT:-8384}"
mkdir -p "\$HOME/kairos_logs"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] syncthing ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

tmux new-session -d -s "\$SESSION" \\
  "'$TERMUX_PREFIX/bin/syncthing' serve --no-browser --no-restart --gui-address=127.0.0.1:\$PORT > '\$LOG' 2>&1"

sleep 3
if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] syncthing iniciado — UI web en 127.0.0.1:\$PORT"
else
  echo "[ERROR] No se pudo iniciar syncthing — log en ~/kairos_logs/syncthing_serve.log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/syncthing/start.sh"

  cat > "$HOME/scripts/syncthing/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="syncthing-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] syncthing detenido"
else
  echo "[OK] syncthing no estaba corriendo"
fi
SCRIPT
  chmod +x "$HOME/scripts/syncthing/stop.sh"

  log "Scripts de control del servicio creados"
  mark_done "serve_scripts"
fi

registry_install "syncthing" "$(syncthing --version 2>&1 | head -1)" "gui_port=8384"

notify_event "syncthing" "install_done" ""
log "syncthing instalado — activá el switch del módulo para arrancar la UI web en http://localhost:8384"
rm -f "$CHECKPOINT"
exit 0
