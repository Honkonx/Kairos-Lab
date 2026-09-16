#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · gitea.sh (silent mode)
#  Gitea — paquete nativo de Termux (pkg install gitea). Servidor Git propio,
#  autoalojado en el dispositivo — encaja con la filosofía "home-lab en el
#  bolsillo" (docs/mini-pc/) y con el módulo `ide`/Estudio (push del propio
#  código del IDE integrado a un remote que vive en el mismo teléfono, sin
#  depender de GitHub/GitLab).
#
#  Propuesto y verificado en la ronda 2026-09-15 (MEJORAS_PENDIENTES.md, sección
#  "Módulos nuevos — ronda 2026-09-15") contra el índice real de termux-packages
#  (github.com/termux/termux-packages/packages/gitea/build.sh, paquete normal —
#  NO root-packages, no necesita root; TERMUX_PKG_VERSION="1.27.3" al momento
#  de verificar).
#
#  PUERTO — CONFLICTO REAL CONFIRMADO Y RESUELTO (no asumido):
#  El app.ini que empaqueta termux-packages (packages/gitea/app.ini) NO trae
#  sección [server], así que Gitea corre con su puerto HTTP por default: 3000.
#  Ese puerto YA lo usa el módulo `opencode` en Kairos (ver "port": "3000" en
#  modules.json y getModulePort() en ModuleController.kt) — para no chocar,
#  este script PARCHEA $PREFIX/etc/gitea/app.ini agregando una sección
#  [server] con HTTP_PORT=3001 (PASO 2 abajo) en vez de solo documentar el
#  conflicto y dejarlo roto. También se desactiva el servidor SSH interno de
#  Gitea (DISABLE_SSH=true) — su default es el puerto 22, que Android/Termux
#  sin root no puede bindear (<1024) y además Kairos ya tiene su propio sshd
#  vía el módulo `remote` (puerto 8022) — el acceso Git contra este servidor
#  queda por HTTP (clone/push http://127.0.0.1:3001/user/repo.git), no SSH.
#
#  USO DESDE APP (KairosApp):
#    bash gitea.sh --silent
#    bash gitea.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ gitea (binario CLI + servidor web)
#    ✅ $PREFIX/etc/gitea/app.ini parcheado (HTTP_PORT=3001, DISABLE_SSH=true)
#    ✅ ~/scripts/gitea/start.sh|stop.sh — mismo patrón (tmux + puerto fijo)
#       que ya usa syncthing.sh/cactus.sh PASO 5, para que la app prenda/apague
#       el servicio con el switch estándar de módulos hasSwitch (ver
#       GenericModuleFragment.kt, genérico — este módulo no necesita Fragment
#       propio).
#    ✅ Registry actualizado (gitea.*)
#
#  La creación del primer usuario admin y de repos queda para el usuario vía
#  la UI web propia de Gitea en http://localhost:3001 (wizard de instalación
#  propio de Gitea la primera vez) — es inherentemente interactiva por diseño
#  de la herramienta upstream (mismo criterio que `rclone config`/Syncthing
#  pairing, ver .claude/rules/kairos-product-philosophy.md), vía el
#  webviewUrl que GenericModuleFragment ya abre embebido.
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
{"id":"gitea","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. El binario gitea en sí es un
# paquete apt normal (ya gestionado por pkg) — los archivos propios de Kairos
# son el app.ini parcheado (puerto/SSH, PASO 2) y los wrappers start.sh/
# stop.sh (mismo criterio que syncthing.sh, que ya los lista). Los repos Git
# y la base de datos del usuario (var/lib/gitea/) son estado propio, no se
# empaquetan.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/etc/gitea/app.ini" \
    --arg p2 "$HOME/scripts/gitea/start.sh" \
    --arg p3 "$HOME/scripts/gitea/stop.sh" \
    --arg patch "chmod +x \"$HOME/scripts/gitea/start.sh\" \"$HOME/scripts/gitea/stop.sh\" 2>/dev/null || true" \
    '{
      id: "gitea", supports_describe_files: true, variant: null,
      package_name: "kairos-module-gitea",
      version_registry_key: "gitea.version",
      files: [
        {path: $p1, mode: "0644"},
        {path: $p2, mode: "0755"},
        {path: $p3, mode: "0755"}
      ],
      file_globs: [],
      dependencies: [{id: "pkg:gitea", check_cmd: "command -v gitea >/dev/null 2>&1", install_hint: "pkg install -y gitea"}],
      verify_cmd: "command -v gitea >/dev/null 2>&1 && gitea --version >/dev/null 2>&1",
      patch_cmd: $patch,
      not_covered: ["Repos Git y base de datos del usuario (var/lib/gitea/) son estado propio, no se empaquetan"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_gitea_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

step "PASO 1 — Instalando gitea"
install_single_pkg "gitea" "gitea" gitea

# ── PASO 2 — Parchear app.ini: puerto 3001 (evita choque con opencode:3000) ──
# y desactivar el servidor SSH interno de Gitea (su default, puerto 22, no se
# puede bindear sin root en Android/Termux — ver bloque de comentarios arriba).
step "PASO 2 — Configurando puerto y SSH interno"
APP_INI="$TERMUX_PREFIX/etc/gitea/app.ini"
if check_done "app_ini_patch"; then
  log "app.ini ya parcheado [checkpoint]"
elif [ -f "$APP_INI" ] && grep -q "^\[server\]" "$APP_INI" 2>/dev/null; then
  log "app.ini ya tiene sección [server] propia — no se toca (posible reinstalación sobre config existente)"
  mark_done "app_ini_patch"
elif [ -f "$APP_INI" ]; then
  cat >> "$APP_INI" << 'INI'

[server]
HTTP_PORT = 3001
DISABLE_SSH = true
INI
  log "app.ini parcheado: HTTP_PORT=3001, DISABLE_SSH=true"
  mark_done "app_ini_patch"
else
  error "No se encontró $APP_INI tras instalar el paquete gitea"
fi

# ── PASO 3 — Scripts de control del servicio (start/stop) ──────────────
# Mismo patrón (tmux + script start/stop + puerto fijo) que syncthing.sh/
# cactus.sh PASO 5 — lo que la app arranca/detiene con el switch estándar de
# módulos hasSwitch (ver GenericModuleFragment.kt).
step "PASO 3 — Scripts de control del servicio"
if check_done "serve_scripts"; then
  log "Scripts de servicio ya instalados [checkpoint]"
else
  mkdir -p "$HOME/scripts/gitea"

  cat > "$HOME/scripts/gitea/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="gitea-server"
LOG="\$HOME/kairos_logs/gitea_serve.log"
mkdir -p "\$HOME/kairos_logs"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] gitea ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

tmux new-session -d -s "\$SESSION" \\
  "'$TERMUX_PREFIX/bin/gitea' web --config '$TERMUX_PREFIX/etc/gitea/app.ini' > '\$LOG' 2>&1"

sleep 3
if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] gitea iniciado — UI web en 127.0.0.1:3001"
else
  echo "[ERROR] No se pudo iniciar gitea — log en ~/kairos_logs/gitea_serve.log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/gitea/start.sh"

  cat > "$HOME/scripts/gitea/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="gitea-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] gitea detenido"
else
  echo "[OK] gitea no estaba corriendo"
fi
SCRIPT
  chmod +x "$HOME/scripts/gitea/stop.sh"

  log "Scripts de control del servicio creados"
  mark_done "serve_scripts"
fi

registry_install "gitea" "$(gitea --version 2>&1 | head -1)" "http_port=3001"

notify_event "gitea" "install_done" ""
log "gitea instalado — activá el switch del módulo para arrancar la UI web en http://localhost:3001"
rm -f "$CHECKPOINT"
exit 0
