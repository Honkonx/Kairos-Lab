#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · nginx.sh (silent mode)
#  Nginx — paquete nativo de Termux (pkg install nginx). Reverse proxy propio para no tener
#  que acordarse de 8+ puertos distintos (Ollama 11434, n8n 5678, llama-server 8085, syncthing
#  8384, gitea 3001, etc.) — pedido real documentado en MEJORAS_PENDIENTES.md.
#
#  Confirmado contra el índice real de termux-packages (packages/nginx/build.sh, paquete
#  normal — NO root-packages, no necesita root).
#
#  LA CONFIG SE GENERA SOLA, NUNCA A MANO (filosofía "no vivir en la terminal" —
#  .claude/rules/kairos-product-philosophy.md): este script solo instala el binario + un
#  nginx.conf base mínimo (listen 8099, include conf.d/*.conf — ya es el comportamiento
#  default del nginx.conf que empaqueta Termux). Quién arma conf.d/kairos_routes.conf con un
#  `location /<módulo>/` por cada módulo REALMENTE corriendo es
#  com.termux.app.util.NginxProxyManager.kt — se llama solo, cada vez que el usuario prende/
#  apaga un módulo con switch, desde ModuleEventBridge.notifySessionEvent(). El usuario nunca
#  edita nginx.conf.
#
#  PUERTO: 8099 (libre, no choca con ningún módulo real de modules.json al momento de
#  verificar) — no 80/8080 (8080 es convención común pero no hay garantía de que algún
#  módulo futuro no lo quiera; 8099 es menos probable de colisionar).
#
#  USO DESDE APP (KairosApp):
#    bash nginx.sh --silent
#    bash nginx.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ nginx (binario)
#    ✅ $PREFIX/etc/nginx/nginx.conf parcheado (listen 8099 en vez del default 8080, pid file
#       propio en vez de /var/run/nginx.pid — Termux no tiene /var/run escribible)
#    ✅ ~/scripts/nginx/start.sh|stop.sh — mismo patrón (proceso + puerto fijo) que gitea.sh/
#       syncthing.sh PASO 5, para el switch estándar de módulos hasSwitch.
#    ✅ Registry actualizado (nginx.*)
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Noviembre 2026
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
{"id":"nginx","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/etc/nginx/nginx.conf" \
    --arg p2 "$HOME/scripts/nginx/start.sh" \
    --arg p3 "$HOME/scripts/nginx/stop.sh" \
    --arg patch "chmod +x \"$HOME/scripts/nginx/start.sh\" \"$HOME/scripts/nginx/stop.sh\" 2>/dev/null || true" \
    '{
      id: "nginx", supports_describe_files: true, variant: null,
      package_name: "kairos-module-nginx",
      version_registry_key: "nginx.version",
      files: [
        {path: $p1, mode: "0644"},
        {path: $p2, mode: "0755"},
        {path: $p3, mode: "0755"}
      ],
      file_globs: [],
      dependencies: [{id: "pkg:nginx", check_cmd: "command -v nginx >/dev/null 2>&1", install_hint: "pkg install -y nginx"}],
      verify_cmd: "command -v nginx >/dev/null 2>&1 && nginx -v >/dev/null 2>&1",
      patch_cmd: $patch,
      not_covered: ["conf.d/kairos_routes.conf se regenera en vivo por NginxProxyManager.kt — no es parte del paquete, no tiene sentido congelarla en un .deb"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_nginx_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

step "PASO 1 — Instalando nginx"
install_single_pkg "nginx" "nginx" nginx

# ── PASO 2 — Parchear nginx.conf: puerto 8099, pid file propio ──────────
step "PASO 2 — Configurando puerto y pid file"
NGINX_CONF="$TERMUX_PREFIX/etc/nginx/nginx.conf"
if check_done "nginx_conf_patch"; then
  log "nginx.conf ya parcheado [checkpoint]"
elif [ -f "$NGINX_CONF" ]; then
  mkdir -p "$HOME/kairos_logs"
  # Bindeado EXPLÍCITO a loopback -- hallazgo real de seguridad (revisión automática 2026-11-09):
  # "listen 8099;" a secas (sin IP) bindea en TODAS las interfaces, exponiendo a la LAN los
  # módulos que proxea (Ollama/n8n/etc. ya bindean solo a 127.0.0.1 a propósito) -- este reverse
  # proxy nunca debe ser la puerta trasera que los vuelve alcanzables desde afuera del propio
  # dispositivo.
  sed -i "s/^\s*listen\s\+[0-9]\+;/    listen 127.0.0.1:8099;/" "$NGINX_CONF"
  sed -i "s#^pid\s\+.*;#pid $HOME/kairos_logs/nginx.pid;#" "$NGINX_CONF"
  if ! grep -q "^pid " "$NGINX_CONF"; then
    sed -i "1i pid $HOME/kairos_logs/nginx.pid;" "$NGINX_CONF"
  fi
  mkdir -p "$TERMUX_PREFIX/etc/nginx/conf.d"
  mkdir -p "$TERMUX_PREFIX/share/kairos-nginx"
  # Página de estado default (NginxProxyManager.kt la regenera en vivo cuando hay módulos
  # corriendo; esto solo cubre el primer arranque antes de que algo prenda).
  if [ ! -f "$TERMUX_PREFIX/share/kairos-nginx/index.html" ]; then
    cat > "$TERMUX_PREFIX/share/kairos-nginx/index.html" << 'HTML'
<!DOCTYPE html><html><head><meta charset="utf-8"><title>Kairos — Reverse proxy</title>
<style>body{font-family:sans-serif;background:#0d1117;color:#e6edf3;padding:24px}</style></head>
<body><h1>Kairos — Reverse proxy</h1><p>Ningún módulo con switch está corriendo todavía.</p></body></html>
HTML
  fi
  # root de "/" apunta a la página de estado; conf.d/*.conf ya se incluye por default en el
  # nginx.conf que empaqueta Termux (server { ... include conf.d/*.conf; } estándar).
  if ! grep -q "kairos-nginx" "$NGINX_CONF"; then
    sed -i "s#root\s\+.*html;#root $TERMUX_PREFIX/share/kairos-nginx;#" "$NGINX_CONF"
  fi
  log "nginx.conf parcheado: puerto 8099, pid propio, root a la página de estado"
  mark_done "nginx_conf_patch"
else
  error "No se encontró $NGINX_CONF tras instalar el paquete nginx"
fi

# ── PASO 3 — Scripts de control del servicio (start/stop) ──────────────
step "PASO 3 — Scripts de control del servicio"
if check_done "serve_scripts"; then
  log "Scripts de servicio ya instalados [checkpoint]"
else
  mkdir -p "$HOME/scripts/nginx"

  cat > "$HOME/scripts/nginx/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
LOG="\$HOME/kairos_logs/nginx_serve.log"
PID_FILE="\$HOME/kairos_logs/nginx.pid"
mkdir -p "\$HOME/kairos_logs"

if [ -f "\$PID_FILE" ] && kill -0 "\$(cat "\$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  echo "[OK] nginx ya corriendo (pid \$(cat "\$PID_FILE"))"
  exit 0
fi

'$TERMUX_PREFIX/sbin/nginx' -c '$TERMUX_PREFIX/etc/nginx/nginx.conf' > "\$LOG" 2>&1

sleep 1
if [ -f "\$PID_FILE" ] && kill -0 "\$(cat "\$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  echo "[OK] nginx iniciado — reverse proxy en 127.0.0.1:8099"
else
  echo "[ERROR] No se pudo iniciar nginx — log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/nginx/start.sh"

  cat > "$HOME/scripts/nginx/stop.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
PID_FILE="\$HOME/kairos_logs/nginx.pid"
if [ -f "\$PID_FILE" ] && kill -0 "\$(cat "\$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  '$TERMUX_PREFIX/sbin/nginx' -c '$TERMUX_PREFIX/etc/nginx/nginx.conf' -s stop
  echo "[OK] nginx detenido"
else
  echo "[OK] nginx no estaba corriendo"
fi
SCRIPT
  chmod +x "$HOME/scripts/nginx/stop.sh"

  log "Scripts de control del servicio creados"
  mark_done "serve_scripts"
fi

registry_install "nginx" "$(nginx -v 2>&1 | head -1)" "http_port=8099"

notify_event "nginx" "install_done" ""
log "nginx instalado — activá el switch del módulo para levantar el reverse proxy en http://localhost:8099 (las rutas se agregan solas al prender/apagar otros módulos)"
rm -f "$CHECKPOINT"
exit 0
