#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · n8n.sh (silent mode)
#  Instala n8n en Termux — variante proot Debian o udocker
#
#  USO DESDE APP (KairosApp):
#    bash n8n.sh --silent --variant proot --source clean
#    bash n8n.sh --silent --variant udocker
#
#  USO MANUAL (standalone):
#    bash install_n8n.sh
#
#  FLAGS:
#    --silent              Sin preguntas, instala todo directo
#    --force                Reinstala aunque ya esté
#    --variant <tipo>       udocker (default) | proot
#    --source <modo>        Solo aplica a --variant proot:
#       github       — todo desde GitHub Releases (rootfs + n8n)
#       clean        — todo limpio (proot-distro + npm install) [default]
#       rootfs-github — rootfs GitHub + n8n limpio (npm)
#       rootfs-clean  — rootfs limpio + n8n GitHub
#
#  QUÉ INSTALA (proot):
#    ✅ proot-distro + Debian Bookworm ARM64
#    ✅ Node.js 22 LTS + n8n (dentro del proot)
#    ✅ cloudflared (dentro del proot)
#    ✅ Scripts de control (start/stop/url/status/backup)
#
#  QUÉ INSTALA (udocker):
#    ✅ udocker + imagen oficial n8nio/n8n (sin proot)
#    ✅ cloudflared nativo Termux (para el túnel)
#    ✅ Scripts de control en ~/scripts/n8n-udocker/
#
#  AMBAS VARIANTES:
#    ✅ Arranque automático (Termux:Boot)
#    ✅ Aliases + Registry (n8n.mode=proot|udocker)
#
#  NO HACE:
#    ❌ Configurar token cloudflared (lo maneja la app después)
#    ❌ Permiso de almacenamiento (lo maneja la app)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 4.0.0 | Julio 2026 (agrega variante udocker, portada de
#  termux-ai-stack/actu ai-stack/install_n8n.sh v3.0.0)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib"
export DEBIAN_FRONTEND=noninteractive

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
REPAIR_SCRIPTS=false
INSTALL_SOURCE="clean"
VARIANT="udocker"
VARIANT_EXPLICIT=false

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)          SILENT=true ;;
    --force)           FORCE=true ;;
    --describe)        DESCRIBE=true ;;
    --describe-files)  DESCRIBE_FILES=true ;;
    --repair-scripts)  REPAIR_SCRIPTS=true ;;
    --source)          shift; INSTALL_SOURCE="$1" ;;
    --variant)         shift; VARIANT="$1"; VARIANT_EXPLICIT=true ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"n8n","supports_silent":true,"supports_force":true,"variants":["udocker","proot"],"variant_required":false,"variant_default":"udocker","extra_flags":[{"name":"source","applies_to_variant":"proot","values":["clean","github","rootfs-github","rootfs-clean"],"default":"clean"},{"name":"repair-scripts","type":"flag","description":"Regenera solo los scripts de control (start/stop/log/etc.) sin tocar datos/workflows"}]}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Reemplaza el manifest a mano modulos/manifests/n8n.json (borrado como
# código muerto en humano165, ver docs/arquitectura/MODULEDEB_GENERICO.md).
# Contenido migrado 1:1 del manifest piloto original
# (git show 838544d^:modulos/manifests/n8n.json). PILOTO LIMITADO: solo
# empaqueta scripts de control/boot — NO la imagen/contenedor udocker
# (~800MB de estado, no un archivo reubicable 1:1), ver not_covered.
if $DESCRIBE_FILES; then
  _n8n_scripts="$HOME/scripts/n8n-udocker"
  jq -n \
    --arg p1 "$_n8n_scripts/start.sh" \
    --arg n1 "Arranca el contenedor udocker 'n8n' + túnel cloudflared, healthcheck real contra :5678/healthz" \
    --arg p2 "$_n8n_scripts/stop.sh" \
    --arg n2 "pkill cloudflared + tmux kill-session n8n-udocker" \
    --arg p3 "$_n8n_scripts/status.sh" \
    --arg p4 "$_n8n_scripts/log.sh" \
    --arg p5 "$_n8n_scripts/update.sh" \
    --arg n5 "udocker pull + recreate, actualiza registry" \
    --arg p6 "$_n8n_scripts/backup.sh" \
    --arg n6 "tar de \$HOME/n8n-udocker a /sdcard/Download" \
    --arg p7 "$HOME/.termux/boot/start_n8n_udocker.sh" \
    --arg n7 "Arranque automático vía Termux:Boot" \
    --arg dep1_check "command -v udocker >/dev/null 2>&1" \
    --arg dep2_check "test -s \"$HOME/.udocker/lib/VERSION\"" \
    --arg dep2_hint "UDOCKER_TARBALL=https://raw.githubusercontent.com/jorge-lip/udocker-builds/master/tarballs/udocker-englib-1.2.11.tar.gz udocker install --force (mirror fijo por fallo intermitente del origen dinámico)" \
    --arg dep4_check "command -v cloudflared >/dev/null 2>&1" \
    --arg verify "test -x \"$_n8n_scripts/start.sh\" && udocker inspect n8n >/dev/null 2>&1" \
    --arg patch "chmod +x \"$_n8n_scripts/\"*.sh 2>/dev/null || true; [ -f \"$HOME/.udocker_force_p2\" ] || touch \"$HOME/.udocker_force_p2\"; command -v udocker >/dev/null 2>&1 && udocker inspect n8n >/dev/null 2>&1 && udocker setup --execmode=P2 n8n 2>/dev/null || true" \
    '{
      id: "n8n",
      supports_describe_files: true,
      variant: "udocker",
      package_name: "kairos-module-n8n",
      version_registry_key: "n8n.version",
      files: [
        {path: $p1, required: true, note: $n1},
        {path: $p2, required: true, note: $n2},
        {path: $p3, required: false, note: "estado del contenedor"},
        {path: $p4, required: false, note: "logs en vivo"},
        {path: $p5, required: false, note: $n5},
        {path: $p6, required: false, note: $n6},
        {path: $p7, required: false, note: $n7}
      ],
      file_globs: [],
      dependencies: [
        {id: "udocker_bin", check_cmd: $dep1_check, install_hint: "pkg install -y udocker"},
        {id: "udockertools_ready", check_cmd: $dep2_check, install_hint: $dep2_hint},
        {id: "n8n_image_and_container", check_cmd: "udocker inspect n8n >/dev/null 2>&1", install_hint: "NO reparable por este mecanismo — requiere udocker pull n8nio/n8n (~800MB) + udocker create --name=n8n n8nio/n8n, ejecutar n8n.sh --variant udocker normalmente"},
        {id: "cloudflared", check_cmd: $dep4_check, install_hint: "pkg install -y cloudflared"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "La imagen udocker n8nio/n8n (~800MB) y el contenedor n8n NO se empaquetan — son estado de udocker (tarballs en $HOME/.udocker/), no archivos simples reubicables. Si no existen en el device destino, verify_cmd falla y hay que correr n8n.sh normalmente",
        "$HOME/n8n-udocker (datos/workflows del usuario) tampoco se empaqueta, a propósito",
        "La variante proot (Debian + n8n vía npm) no tiene describe-files todavía"
      ]
    }'
  exit 0
fi

case "$INSTALL_SOURCE" in
  github|1)         INSTALL_SOURCE="github" ;;
  clean|2)          INSTALL_SOURCE="clean" ;;
  rootfs-github|3)  INSTALL_SOURCE="rootfs-github" ;;
  rootfs-clean|4)   INSTALL_SOURCE="rootfs-clean" ;;
  *)                INSTALL_SOURCE="clean" ;;
esac

case "$VARIANT" in
  proot*|debian) VARIANT="proot" ;;
  udocker)       VARIANT="udocker" ;;
  *)             VARIANT="udocker" ;;
esac

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
BASHRC="$HOME/.bashrc"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
GRAY='\033[0;90m'  # usado en el menú manual, no lo define lib.sh

update_registry() {
  local mode="$1" version="$2"
  registry_install n8n "$version" "mode=$mode" "port=5678"
}

# Bug real (2026-08-06, ver docs/humano/humano77.md): si este intento de
# instalación falla DESPUÉS de que un intento anterior (de la OTRA variante,
# proot o udocker) ya haya escrito el registry con éxito, el registry se
# queda con ese valor viejo — la UI termina mostrando "instalado, modo: X"
# aunque X ya no sea real (el intento actual falló antes de llegar a
# actualizarlo). Se invalida el registry explícitamente antes de salir por
# error, para que la UI refleje "no instalado" en vez de un estado stale
# potencialmente incorrecto.
invalidate_registry() {
  registry_write n8n "installed=false"
}

# ── Detección de rootfs (compartida) ──────────────────────────
# Definida acá (antes de las dos variantes) porque tanto la instalación
# proot normal como --repair-scripts en modo proot necesitan encontrar el
# rootfs ya instalado. ROOTFS_BASE = layout LEGACY de proot-distro
# ("installed-rootfs/<nombre>"). proot-distro fue reescrito de bash a
# Python y las versiones modernas instalan en "containers/<nombre>/rootfs/"
# — confiar solo en la ruta legacy hacía que _detect_rootfs nunca
# encontrara un rootfs real ya instalado, causando el bug persistente
# "container already exists" (fix portado de
# termux-ai-stack-dev/scripts/install_n8n.sh, 2026-07-26/27).
ROOTFS_BASE="$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs"
CONTAINERS_BASE="$TERMUX_PREFIX/var/lib/proot-distro/containers"
DISTRO_NAME=""
ROOTFS_PATH=""

_detect_rootfs() {
  DISTRO_NAME=""; ROOTFS_PATH=""
  local _d
  if [ -d "$CONTAINERS_BASE" ]; then
    for _d in "$CONTAINERS_BASE"/*/; do
      _d="${_d%/}"
      [ -d "$_d/rootfs" ] && { DISTRO_NAME=$(basename "$_d"); ROOTFS_PATH="$_d/rootfs"; return 0; }
    done
  fi
  if [ -d "$ROOTFS_BASE" ]; then
    for _d in "$ROOTFS_BASE"/*/; do
      _d="${_d%/}"
      if [ -f "${_d}/bin/bash" ] || [ -f "${_d}/usr/bin/bash" ] || [ -f "${_d}/etc/os-release" ]; then
        DISTRO_NAME=$(basename "$_d"); ROOTFS_PATH="$_d"; return 0
      fi
    done
  fi
  return 1
}

# ── Scripts de control — udocker (compartida instalación + reparación) ──
# Extraída de PASO 4 de la variante udocker (ver más abajo) para poder
# invocarla también desde --repair-scripts sin duplicar los heredocs.
# USO: _n8n_write_control_scripts_udocker <directorio_destino>
# Regenera start.sh/stop.sh/status.sh/log.sh/update.sh/backup.sh — nunca
# toca el contenedor udocker ni $HOME/n8n-udocker (datos/workflows).
_n8n_write_control_scripts_udocker() {
  local _dir="$1"
  mkdir -p "$_dir"

  cat > "$_dir/start.sh" << 'STARTSCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
N8N_DATA_ABS="${TERMUX_HOME}/n8n-udocker"
SESSION="n8n-udocker"
CF_LOG="$TERMUX_HOME/.cf_ud_url.log"
export UDOCKER_USE_PROOT_EXECUTABLE="${TERMUX_PREFIX}/bin/proot"
export UDOCKER_TARBALL="https://raw.githubusercontent.com/jorge-lip/udocker-builds/master/tarballs/udocker-englib-1.2.11.tar.gz"

echo "[*] Iniciando n8n (udocker)..."
[ ! -d "$N8N_DATA_ABS" ] && mkdir -p "$N8N_DATA_ABS" && chmod 777 "$N8N_DATA_ABS"
if ! command -v udocker &>/dev/null; then
  echo "[ERROR] udocker no instalado."; exit 1
fi
if ! udocker inspect n8n &>/dev/null; then
  udocker images 2>/dev/null | grep -q "n8nio/n8n" && \
    udocker create --name=n8n n8nio/n8n || { echo "[ERROR] Reinstala n8n"; exit 1; }
fi
[ -f "$TERMUX_HOME/.udocker_force_p2" ] && udocker setup --execmode=P2 n8n 2>/dev/null || true

# Bug real (2026-08-06, ver docs/humano/humano88.md): el modo proot ya soportaba
# "Configurar dominio webhook" (N8N_WEBHOOK_URL en ~/.env_n8n, portado del [d] del
# TUI original) pero el modo udocker no leía ese archivo en absoluto — el botón de
# la app escribía el dominio pero solo tenía efecto si el usuario había instalado
# la variante proot, no la recomendada (udocker).
WEBHOOK_URL_CFG=$(grep "^N8N_WEBHOOK_URL=" "$TERMUX_HOME/.env_n8n" 2>/dev/null | cut -d'=' -f2)
UDOCKER_ENV_ARGS="--env=N8N_HOST=0.0.0.0 --env=N8N_PORT=5678 --env=N8N_SECURE_COOKIE=false --env=N8N_RUNNERS_ENABLED=true --env=NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os --env=NODE_FUNCTION_ALLOW_EXTERNAL=*"
[ -n "$WEBHOOK_URL_CFG" ] && UDOCKER_ENV_ARGS="${UDOCKER_ENV_ARGS} --env=WEBHOOK_URL=${WEBHOOK_URL_CFG}"
[ -n "$WEBHOOK_URL_CFG" ] && echo "[*] Webhook URL: $WEBHOOK_URL_CFG"

tmux kill-session -t "$SESSION" 2>/dev/null || true
sleep 1
tmux new-session -d -s "$SESSION" -n "n8n"
tmux send-keys -t "$SESSION:n8n" \
  "udocker run --publish=5678:5678 --volume=${N8N_DATA_ABS}:/home/node/.n8n ${UDOCKER_ENV_ARGS} n8n" Enter

echo "[*] Esperando n8n..."
# Antes este loop nunca marcaba fallo: si los 30 intentos (60s) agotaban sin que
# /healthz respondiera, el for simplemente terminaba y el script seguía de largo
# (arrancaba el túnel igual, y el proceso terminaba con exit 0 por ser el último
# comando exitoso) — ModuleController.startModule()/installModule() en Kotlin
# interpretan waitFor()==0 como "éxito", así que tanto el botón "Iniciar n8n" como
# la instalación reportaban éxito aunque el contenedor jamás respondiera en :5678.
# Bug real reportado (ver docs/humano/humano57.md): "n8n... dura mucho y al final
# ni siquiera se si se inicia" — 60s (30x2s) es corto para el arranque en frío de
# udocker (pull/extract de la imagen + primer boot de n8n dentro de proot P2, más
# lento que un contenedor nativo). Subido a 90s (45x2s), mismo criterio que el
# timeout equivalente de ModuleController.kt (Kotlin) para n8n.
N8N_UP=false
for i in $(seq 1 45); do
  sleep 2
  curl -sf --max-time 2 http://localhost:5678/healthz >/dev/null 2>&1 && { N8N_UP=true; break; }
done

if ! $N8N_UP; then
  echo "[ERROR] n8n no respondió en :5678 tras 90s — revisa: tmux attach -t n8n-udocker"
  exit 1
fi

# Bug real reportado (auditoría 2026-08-05, ver docs/humano65.md): /healthz responde
# "ok" apenas el proceso de n8n está vivo, ANTES de que terminen las migraciones de
# base de datos — el usuario veía "listo" en la app varios segundos antes de que la
# interfaz de n8n realmente sirviera algo. Margen extra + segunda verificación real
# antes de declarar éxito.
echo "[*] n8n respondió — confirmando que terminó de inicializar (margen ~15s)..."
sleep 15
if curl -sf --max-time 3 http://localhost:5678/healthz >/dev/null 2>&1; then
  echo "[OK] n8n activo"
else
  echo "[WARN] n8n dejó de responder durante el margen extra — puede seguir inicializando, revisa: tmux attach -t n8n-udocker"
fi

if [ -f "$TERMUX_HOME/.n8n_local_only" ]; then
  echo "[*] Modo solo local activo (.n8n_local_only) — sin túnel cloudflared"
elif command -v cloudflared &>/dev/null; then
  tmux new-window -t "$SESSION" -n "tunnel"
  if [ -f "$TERMUX_HOME/.cf_token" ] && [ -s "$TERMUX_HOME/.cf_token" ]; then
    CF_TOK=$(cat "$TERMUX_HOME/.cf_token")
    tmux send-keys -t "$SESSION:tunnel" \
      "cloudflared tunnel --no-autoupdate run --token ${CF_TOK} 2>&1 | tee ${CF_LOG}" Enter
  else
    tmux send-keys -t "$SESSION:tunnel" \
      "cloudflared tunnel --no-autoupdate --url http://localhost:5678 2>&1 | tee ${CF_LOG}" Enter
  fi
  sleep 20
fi

CF_URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$CF_LOG" 2>/dev/null | head -1)
[ -n "$CF_URL" ] && echo "$CF_URL" > "$TERMUX_HOME/.last_cf_url"
echo "[OK] n8n (udocker) activo :5678"
[ -n "$CF_URL" ] && echo "[OK] URL: $CF_URL"
# Bug real confirmado con evidencia de dispositivo (2026-08-14, ver docs/humano/humano120.md):
# sin este "exit 0", el código de salida del script es el de la última línea ejecutada —
# cuando CF_URL está vacío (modo .n8n_local_only, sin túnel), "[ -n "$CF_URL" ] && echo ..."
# evalúa a falso y devuelve 1, así que start.sh "fallaba" pese a haber impreso
# "[OK] n8n (udocker) activo :5678" dos líneas antes — el caller (PASO 7 más abajo)
# interpretaba ese 1 como fallo real y mostraba un error contradictorio con el OK previo.
exit 0
STARTSCRIPT
  chmod +x "$_dir/start.sh"

  cat > "$_dir/stop.sh" << 'STOPSCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
pkill -f "cloudflared tunnel" 2>/dev/null || true
tmux kill-session -t "n8n-udocker" 2>/dev/null || true
rm -f "$HOME/.cf_ud_url.log" "$HOME/.last_cf_url" 2>/dev/null
echo "[OK] n8n (udocker) detenido"
STOPSCRIPT
  chmod +x "$_dir/stop.sh"

  cat > "$_dir/status.sh" << 'STATUSSCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
tmux has-session -t "n8n-udocker" 2>/dev/null \
  && echo "[OK] n8n (udocker): activo :5678" || echo "[INFO] n8n (udocker): detenido"
[ -f "$HOME/.last_cf_url" ] && echo "[OK] URL: $(cat "$HOME/.last_cf_url")"
STATUSSCRIPT
  chmod +x "$_dir/status.sh"

  cat > "$_dir/log.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
tmux has-session -t "n8n-udocker" 2>/dev/null && tmux attach-session -t "n8n-udocker" || \
  echo "[WARN] n8n no corriendo — ejecuta: n8n-ud-start"
SCRIPT
  chmod +x "$_dir/log.sh"

  cat > "$_dir/update.sh" << 'UPDATESCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
export UDOCKER_TARBALL="https://raw.githubusercontent.com/jorge-lip/udocker-builds/master/tarballs/udocker-englib-1.2.11.tar.gz"
REGISTRY="$HOME/.android_server_registry"
VER_ANTES=$(grep "^n8n\.version=" "$REGISTRY" 2>/dev/null | cut -d'=' -f2)
tmux kill-session -t "n8n-udocker" 2>/dev/null || true
sleep 2
# --platform=linux/arm64: ver comentario del PASO 2 en n8n.sh (mismo bug real
# confirmado por ADB, docs/humano269.md) — sin esto, udocker pide el manifest
# de "android/arm64" (Python de Termux reporta platform.system()="Android") y
# Docker Hub siempre lo rechaza.
udocker pull --platform=linux/arm64 n8nio/n8n || { echo "[ERROR] Falló la descarga"; exit 1; }
udocker rm n8n 2>/dev/null || true
udocker create --name=n8n n8nio/n8n || { echo "[ERROR] Falló la creación"; exit 1; }
[ -f "$HOME/.udocker_force_p2" ] && udocker setup --execmode=P2 n8n 2>/dev/null || true
VER_NUEVA=$(udocker images 2>/dev/null | grep "n8nio/n8n" | awk '{print $2}' | head -1)
[ -z "$VER_NUEVA" ] && VER_NUEVA="latest-$(date +%Y%m%d)"
if [ -f "$REGISTRY" ]; then
  TMP="$REGISTRY.tmp"
  grep -v "^n8n\.version=\|^n8n\.install_date=" "$REGISTRY" > "$TMP" 2>/dev/null || true
  echo "n8n.version=$VER_NUEVA" >> "$TMP"
  echo "n8n.install_date=$(date +%Y-%m-%d)" >> "$TMP"
  mv "$TMP" "$REGISTRY"
fi
echo "[OK] n8n actualizado: ${VER_ANTES:-?} → ${VER_NUEVA:-?}"
UPDATESCRIPT
  chmod +x "$_dir/update.sh"

  cat > "$_dir/backup.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
N8N_DATA_ABS="$HOME/n8n-udocker"
FECHA=$(date +%Y%m%d_%H%M)
DESTINO="/sdcard/Download/n8n_udocker_${FECHA}.tar.gz"
tar -czf "$DESTINO" -C "$N8N_DATA_ABS" . 2>/dev/null
echo "[OK] Backup: $DESTINO"
SCRIPT
  chmod +x "$_dir/backup.sh"
}

# ── Scripts de control — proot (compartida instalación + reparación) ──
# Extraída de PASO 4 de la variante proot (ver más abajo) para poder
# invocarla también desde --repair-scripts sin duplicar los heredocs.
# USO: _n8n_write_control_scripts_proot <directorio_destino> <distro_name>
# Regenera start_servidor.sh/stop_servidor.sh/ver_url.sh/n8n_status.sh/
# n8n_log.sh/n8n_update.sh/n8n_backup.sh/cf_token.sh + $HOME/debian.sh —
# nunca toca el rootfs Debian ni /root/.n8n dentro del proot (datos/workflows).
_n8n_write_control_scripts_proot() {
  local _dir="$1" _distro="$2"
  mkdir -p "$_dir"

  # --- start_servidor.sh ---
  cat > "$_dir/start_servidor.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null &
LAST_URL="\$HOME/.last_cf_url"
SESSION="n8n-server"
DISTRO_NAME="${_distro}"
WEBHOOK_URL_CFG=\$(grep "^N8N_WEBHOOK_URL=" "\$HOME/.env_n8n" 2>/dev/null | cut -d'=' -f2)

echo "[*] Iniciando n8n + cloudflared..."
tmux kill-session -t "\$SESSION" 2>/dev/null || true
sleep 1

tmux new-session -d -s "\$SESSION" -n "n8n"

N8N_CMD="export HOME=/root"
N8N_CMD="\${N8N_CMD} && export NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os"
N8N_CMD="\${N8N_CMD} && export NODE_FUNCTION_ALLOW_EXTERNAL=*"
N8N_CMD="\${N8N_CMD} && export N8N_HOST=0.0.0.0"
N8N_CMD="\${N8N_CMD} && export N8N_PORT=5678"
N8N_CMD="\${N8N_CMD} && export N8N_PROXY_HOPS=1"
N8N_CMD="\${N8N_CMD} && export N8N_SECURE_COOKIE=false"
N8N_CMD="\${N8N_CMD} && export N8N_RUNNERS_ENABLED=true"
N8N_CMD="\${N8N_CMD} && export N8N_RUNNERS_HEARTBEAT_INTERVAL=300"
[ -n "\$WEBHOOK_URL_CFG" ] && N8N_CMD="\${N8N_CMD} && export WEBHOOK_URL=\${WEBHOOK_URL_CFG}"
N8N_CMD="\${N8N_CMD} && n8n start"

tmux send-keys -t "\$SESSION:n8n" \
  "proot-distro login \"\$DISTRO_NAME\" -- bash -c '\${N8N_CMD}'" Enter

echo "[*] Esperando n8n..."
# Antes: "sleep 35" ciego, sin ningún chequeo real de que n8n haya levantado —
# el script seguía de largo (arrancaba el túnel igual) y terminaba con el exit
# code del último comando (siempre 0), así que tanto el botón "Iniciar n8n" como
# la verificación post-instalación (n8n.sh, PASO 7) interpretaban éxito aunque
# n8n nunca hubiera llegado a responder en :5678 (mismo patrón ya corregido en
# start.sh de la variante udocker, arriba en este mismo archivo).
# Bug real reportado (ver docs/humano/humano57.md): "n8n... dura mucho y al final
# ni siquiera se si se inicia" — 60s (20x3s) puede ser corto para un login a
# proot-distro + arranque en frío de n8n dentro de Debian (más lento que nativo).
# Subido a 120s (40x3s), mismo criterio que el timeout equivalente de
# ModuleController.kt (Kotlin) para n8n.
N8N_UP=false
for i in \$(seq 1 40); do
  sleep 3
  curl -sf --max-time 2 http://localhost:5678/healthz >/dev/null 2>&1 && { N8N_UP=true; break; }
done

if ! \$N8N_UP; then
  echo "[ERROR] n8n no respondió en :5678 tras 120s — revisa: tmux attach -t \$SESSION"
  exit 1
fi

# Bug real reportado (auditoría 2026-08-05, ver docs/humano65.md): /healthz responde
# "ok" apenas el proceso de n8n está vivo, ANTES de que terminen las migraciones de
# base de datos — el usuario veía "listo" en la app varios segundos antes de que la
# interfaz de n8n realmente sirviera algo. Margen extra + segunda verificación real
# antes de declarar éxito.
echo "[*] n8n respondió — confirmando que terminó de inicializar (margen ~15s)..."
sleep 15
if curl -sf --max-time 3 http://localhost:5678/healthz >/dev/null 2>&1; then
  echo "[OK] n8n activo"
else
  echo "[WARN] n8n dejó de responder durante el margen extra — puede seguir inicializando, revisa: tmux attach -t \$SESSION"
fi

if [ -f "\$HOME/.n8n_local_only" ]; then
  echo "[*] Modo solo local activo (.n8n_local_only) — sin túnel cloudflared"
else
  tmux new-window -t "\$SESSION" -n "tunnel"
  if [ -f "\$HOME/.cf_token" ]; then
    CF_TOK=\$(cat "\$HOME/.cf_token")
    tmux send-keys -t "\$SESSION:tunnel" \
      "proot-distro login \"\$DISTRO_NAME\" -- bash -c 'cloudflared tunnel --no-autoupdate run --token \${CF_TOK} 2>&1 | tee /root/cf_url.log'" Enter
  else
    tmux send-keys -t "\$SESSION:tunnel" \
      "proot-distro login \"\$DISTRO_NAME\" -- bash -c 'cloudflared tunnel --no-autoupdate --url http://localhost:5678 2>&1 | tee /root/cf_url.log'" Enter
  fi

  sleep 40
  CF_URL=\$(proot-distro login "\$DISTRO_NAME" -- bash -c \
    "grep -o 'https://[a-zA-Z0-9.-]*\\.trycloudflare\\.com' /root/cf_url.log 2>/dev/null | head -1" 2>/dev/null)
  [ -n "\$CF_URL" ] && echo "\$CF_URL" > "\$HOME/.last_cf_url"
fi

echo "[OK] n8n activo :5678"
[ -n "\${CF_URL:-}" ] && echo "[OK] URL: \$CF_URL"
exit 0
SCRIPT
  chmod +x "$_dir/start_servidor.sh"

  # --- stop_servidor.sh ---
  cat > "$_dir/stop_servidor.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
DISTRO_NAME="${_distro}"
# Mismo bug real que en openclaw_start.sh/openclaw_stop.sh (ver docs/humano219.md/humano220.md,
# confirmado por ADB con "time"+exit code): "pkill -f n8n" matchea contra la línea de comando
# COMPLETA de cualquier proceso vivo — el propio "bash -c 'pkill -f n8n ...'" que lo ejecuta
# tiene "n8n" en SU PROPIA línea de comando (el texto del pkill que está corriendo), así que se
# automataba con SIGKILL antes de llegar a "pkill -f cloudflared". Patrón de 2 palabras
# ("n8n start", el comando real lanzado en N8N_CMD más arriba) no matchea el wrapper.
proot-distro login "\$DISTRO_NAME" -- bash -c \
  'pkill -f "n8n start" 2>/dev/null; pkill -f cloudflared 2>/dev/null; rm -f /root/cf_url.log' 2>/dev/null || true
tmux kill-session -t "n8n-server" 2>/dev/null || true
rm -f "\$HOME/.last_cf_url" 2>/dev/null
echo "[OK] n8n detenido"
SCRIPT
  chmod +x "$_dir/stop_servidor.sh"

  # --- ver_url.sh ---
  cat > "$_dir/ver_url.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
DISTRO_NAME="${_distro}"
URL=""
[ -f "\$HOME/.last_cf_url" ] && URL=\$(cat "\$HOME/.last_cf_url")
if [ -z "\$URL" ]; then
  URL=\$(proot-distro login "\$DISTRO_NAME" -- bash -c \
    "grep -o 'https://[a-zA-Z0-9.-]*\\.trycloudflare\\.com' /root/cf_url.log 2>/dev/null | head -1" 2>/dev/null)
fi
[ -n "\$URL" ] && echo "\$URL" || echo "[WARN] URL no disponible — ejecuta n8n-start"
SCRIPT
  chmod +x "$_dir/ver_url.sh"

  # --- n8n_status.sh ---
  cat > "$_dir/n8n_status.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
tmux has-session -t "n8n-server" 2>/dev/null && \
  echo "[OK] n8n: corriendo :5678" || echo "[INFO] n8n: detenido"
[ -f "$HOME/.last_cf_url" ] && echo "[OK] URL: $(cat "$HOME/.last_cf_url")"
[ -f "$HOME/.cf_token" ] && echo "[OK] Túnel: URL fija" || echo "[INFO] Túnel: temporal"
SCRIPT
  chmod +x "$_dir/n8n_status.sh"

  # --- n8n_log.sh ---
  cat > "$_dir/n8n_log.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
tmux has-session -t "n8n-server" 2>/dev/null && \
  tmux attach-session -t "n8n-server" || \
  echo "[WARN] n8n no corriendo — ejecuta: n8n-start"
SCRIPT
  chmod +x "$_dir/n8n_log.sh"

  # --- n8n_update.sh ---
  cat > "$_dir/n8n_update.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
DISTRO_NAME="${_distro}"
echo "[INFO] Actualizando n8n..."
proot-distro login "\$DISTRO_NAME" -- bash -c \
  'export HOME=/root && npm update -g n8n && echo "n8n: \$(n8n --version)"'
SCRIPT
  chmod +x "$_dir/n8n_update.sh"

  # --- n8n_backup.sh ---
  cat > "$_dir/n8n_backup.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
DISTRO_NAME="${_distro}"
FECHA=\$(date +%Y%m%d_%H%M)
DESTINO="/sdcard/Download/n8n_workflows_\$FECHA.tar.gz"
echo "[INFO] Backup de n8n..."
proot-distro login "\$DISTRO_NAME" -- bash -c \
  'tar -czf - -C /root/.n8n . 2>/dev/null' > "\$DESTINO"
SIZE=\$(du -h "\$DESTINO" 2>/dev/null | cut -f1)
echo "[OK] Backup: \$DESTINO (\$SIZE)"
SCRIPT
  chmod +x "$_dir/n8n_backup.sh"

  # --- cf_token.sh (no interactivo — recibe por argumento) ---
  cat > "$_dir/cf_token.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: bash cf_token.sh [token]
# Sin argumento = eliminar token (URL temporal)
TOKEN="$1"
if [ -n "$TOKEN" ]; then
  echo "$TOKEN" > "$HOME/.cf_token"
  echo "[OK] Token guardado — URL fija"
else
  rm -f "$HOME/.cf_token"
  echo "[OK] Token eliminado — URL temporal"
fi
SCRIPT
  chmod +x "$_dir/cf_token.sh"

  # --- debian.sh ---
  cat > "$HOME/debian.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
proot-distro login "${_distro}"
SCRIPT
  chmod +x "$HOME/debian.sh"
}

# ── Modo reparación: --repair-scripts ──────────────────────────
# Regenera SOLO los scripts de control (start/stop/log/status/update/
# backup/etc.) sin tocar el contenedor udocker, el rootfs proot, ni los
# datos/workflows del usuario (~/n8n-udocker o /root/.n8n dentro del
# proot) — útil cuando esos scripts se corrompieron, se borraron a mano,
# o quedaron desactualizados tras un update de la app, pero la
# instalación de n8n en sí sigue sana. Equivalente a "[r] Reparar
# scripts de control" del menú TUI original
# (termux-ai-stack-dev/scripts/menu_proot.sh, _n8n_repair_scripts()).
if $REPAIR_SCRIPTS; then
  N8N_INSTALLED=$(grep "^n8n\.installed=" "$REGISTRY" 2>/dev/null | cut -d= -f2)
  N8N_REG_MODE=$(grep "^n8n\.mode=" "$REGISTRY" 2>/dev/null | cut -d= -f2)

  if [ "$N8N_INSTALLED" != "true" ]; then
    warn "n8n no está instalado (registry) — nada que reparar. Instalá n8n primero."
    exit 0
  fi

  # Si el usuario pasó --variant explícito, respetarlo; si no, usar el
  # modo real que quedó registrado en la última instalación exitosa.
  if $VARIANT_EXPLICIT; then
    REPAIR_MODE="$VARIANT"
  else
    REPAIR_MODE="${N8N_REG_MODE:-$VARIANT}"
  fi

  case "$REPAIR_MODE" in
    proot)
      _detect_rootfs
      [ -z "$DISTRO_NAME" ] && error "No se encontró el rootfs proot — no se puede reparar (¿n8n está realmente instalado en modo proot?)"
      N8N_SCRIPTS="$HOME/scripts/n8n"
      _n8n_write_control_scripts_proot "$N8N_SCRIPTS" "$DISTRO_NAME"
      log "Scripts de control (proot) regenerados en $N8N_SCRIPTS: start_servidor.sh, stop_servidor.sh, ver_url.sh, n8n_status.sh, n8n_log.sh, n8n_update.sh, n8n_backup.sh, cf_token.sh + \$HOME/debian.sh"
      ;;
    *)
      N8N_SCRIPTS_UDOCKER="$HOME/scripts/n8n-udocker"
      _n8n_write_control_scripts_udocker "$N8N_SCRIPTS_UDOCKER"
      log "Scripts de control (udocker) regenerados en $N8N_SCRIPTS_UDOCKER: start.sh, stop.sh, status.sh, log.sh, update.sh, backup.sh"
      ;;
  esac

  notify_event "n8n" "repair_scripts_done" "$REPAIR_MODE"
  log "Datos/workflows de n8n no modificados — solo se regeneraron los scripts de control"
  exit 0
fi

# ════════════════════════════════════════════════════════════
#  VARIANTE: udocker (sin proot — imagen oficial n8nio/n8n)
# ════════════════════════════════════════════════════════════
if [ "$VARIANT" = "udocker" ]; then
  CHECKPOINT="$HOME/.install_n8n_udocker_checkpoint"
  N8N_SCRIPTS_UDOCKER="$HOME/scripts/n8n-udocker"
  N8N_DATA_ABS="$HOME/n8n-udocker"

  # Mirrors fijos de udockertools — el origen por defecto de "udocker install"
  # falla de forma intermitente en red móvil/CGNAT ("Error: installation of
  # udockertools failed" / "Error: in download: %s" x6, confirmado en
  # log/install_n8n.log). "udocker pull" repite el mismo chequeo de
  # udockertools antes de bajar la imagen, así que el fallo se propaga y
  # también rompe la descarga de n8nio/n8n. Fix: fijar UDOCKER_TARBALL a un
  # tarball conocido-bueno en vez del origen dinámico, con un segundo mirror
  # de respaldo (el propio manual de instalación de udocker documenta ambos
  # como reemplazo del origen roto para udockertools 1.2.11 en 1.3.17+;
  # verificado accesible — mismo tamaño de archivo, 46.237.418 bytes — el
  # 2026-07-31). Deben quedar disponibles ANTES del checkpoint de PASO 0 para
  # que también apliquen si esa parte ya estaba marcada como hecha.
  UDOCKER_TARBALL_MIRRORS=(
    "https://raw.githubusercontent.com/jorge-lip/udocker-builds/master/tarballs/udocker-englib-1.2.11.tar.gz"
    "https://download.a.incd.pt/udocker/udocker-englib-1.2.11.tar.gz"
  )

  if ! $SILENT; then
    clear; echo -e "${CYAN}${BOLD}"
    echo "  ╔══════════════════════════════════════════════╗"
    echo "  ║   n8n · Instalador — variante udocker       ║"
    echo "  ╚══════════════════════════════════════════════╝"
    echo -e "${NC}"
  fi

  # ── Ya instalado ────────────────────────────────────────────
  # udocker inspect en vez de "udocker ps | grep": ps/ps -a listan primero el
  # UUID del contenedor y el nombre en una columna NAMES posterior, así que
  # grep -q "^n8n" nunca matcheaba — un contenedor recién creado siempre se
  # reportaba como "no existe" (fix portado de install_n8n.sh, 2026-07-26).
  if command -v udocker &>/dev/null && udocker inspect n8n &>/dev/null && ! $FORCE; then
    log "n8n (udocker) ya instalado"
    # Bug real confirmado por ADB (2026-08-28, docs/humano281.md): este atajo hacía "exit 0"
    # SIN pasar nunca por update_registry() — cualquier corrida previa donde el registry se
    # hubiera invalidado (invalidate_registry, arriba) o nunca se hubiera escrito (primera
    # instalación interrumpida a mitad) quedaba con "n8n.installed=false" PARA SIEMPRE, aunque
    # el contenedor udocker existiera y funcionara — la UI mostraba "Instalar" en vez de
    # "Desactivar" de forma persistente. Se refuerza el registry acá también, mismo criterio
    # que el camino de instalación completo (línea ~929).
    _N8N_SHORTCUT_VER=$(udocker images 2>/dev/null | grep "n8nio/n8n" | awk '{print $1}' | head -1 | cut -d: -f2)
    [ -z "$_N8N_SHORTCUT_VER" ] && _N8N_SHORTCUT_VER="latest"
    update_registry "udocker" "$_N8N_SHORTCUT_VER"
    exit 0
  fi
  $FORCE && { rm -f "$CHECKPOINT"; command -v udocker &>/dev/null && udocker rm n8n 2>/dev/null || true; }

  if ! $SILENT; then
    echo -n "  ¿Instalar n8n vía udocker? (s/n): "
    read -r _CONF < /dev/tty
    [ "$_CONF" != "s" ] && [ "$_CONF" != "S" ] && { echo "Cancelado."; exit 0; }
  fi

  # ── PASO 0 — udocker ──────────────────────────────────────
  step "0/7 Instalando udocker"
  # Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): esta variable
  # vivía DENTRO del bloque "else" de abajo, así que solo se exportaba la PRIMERA vez
  # que corría el script. Si un intento anterior dejó "udocker_install" marcado como
  # hecho (checkpoint) pero falló más adelante (PASO 2/3, pull o create de la imagen),
  # cualquier reintento saltaba directo el bloque entero — UDOCKER_USE_PROOT_EXECUTABLE
  # quedaba sin definir para el resto del script, incluyendo el pull/create de la
  # imagen n8n — coincide con "udocker sí se instala pero n8n no" del reporte del
  # usuario. Se exporta siempre, antes del checkpoint, para que un reintento tenga el
  # mismo entorno que la primera corrida.
  export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot 2>/dev/null || echo "$TERMUX_PREFIX/bin/proot")
  if check_done "udocker_install"; then
    log "udocker ya instalado [checkpoint]"
  else
    if command -v udocker &>/dev/null; then
      log "udocker ya disponible"
    else
      info "Instalando udocker..."
      # Bug real confirmado por ADB (docs/humano246.md, 2026-08-26): "udocker" NO es un paquete
      # del repo apt de Termux — "pkg install udocker" fallaba siempre con "Unable to locate
      # package" (oculto por el "2>/dev/null" anterior).
      # Bug real #2 confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
      # 2026-08-27): el fix anterior (descarga directa de udocker.py) quedó roto — indigo-dc/
      # udocker reestructuró el repo, "udocker.py" ya no existe en la raíz (404 siempre). Mismo
      # fix que modulos/udocker.sh: "pip install udocker" (paquete real en PyPI, vía oficial
      # documentada por el propio proyecto), probado en dispositivo real.
      pip3 install --quiet --upgrade udocker || pip install --quiet --upgrade udocker || {
        error "No se pudo instalar udocker (pip3 install udocker falló)"
      }
    fi
    # "udocker install 2>&1 | grep -v '^$'" (versión anterior) chequeaba el
    # exit code de grep, no el de udocker — grep devuelve 0 apenas hay UNA
    # línea no vacía en la salida, así que la condición era casi siempre
    # verdadera y el script marcaba "udockertools instalado" incluso cuando
    # udocker install había fallado del todo. El fallo real recién explotaba
    # 2 pasos después, al descargar la imagen, con el mismo error repetido
    # (confirmado en log/install_n8n.log). Ahora se verifica de verdad contra
    # el filesystem: udockertools deja $HOME/.udocker/lib/VERSION al extraer
    # el tarball correctamente (fuente: udocker/tools.py, is_available()).
    # Bug real #2 confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
    # 2026-08-27): pasarle una URL remota directo a UDOCKER_TARBALL falla SIEMPRE en este
    # dispositivo aunque la URL responda 200 OK y un curl manual descargue el tarball
    # completo sin problema — el downloader interno del paquete udocker (pip) no completa
    # la descarga por esa vía acá. Confirmado con prueba real: "UDOCKER_TARBALL=/ruta/local
    # udocker install --force" SÍ funciona. Fix: descargar el tarball con curl a un archivo
    # temporal y apuntar UDOCKER_TARBALL a esa ruta local, no a la URL remota.
    _UDOCKER_READY=false
    # Nombre único por PID — mismo bug de carrera confirmado en modulos/udocker.sh (dos
    # scripts corriendo en paralelo compartían este nombre fijo y se pisaban el archivo a
    # mitad de descarga).
    _udocker_tmp_tarball="$HOME/.udocker_tarball_tmp.$$.tar.gz"
    for _mirror in "${UDOCKER_TARBALL_MIRRORS[@]}"; do
      info "Descargando udockertools (mirror: ${_mirror##*/})..."
      rm -f "$_udocker_tmp_tarball" 2>/dev/null
      if curl -fsSL -m 120 -o "$_udocker_tmp_tarball" "$_mirror" && [ -s "$_udocker_tmp_tarball" ]; then
        export UDOCKER_TARBALL="$_udocker_tmp_tarball"
        info "Inicializando udocker (mirror: ${_mirror##*/})..."
        udocker install --force &>/dev/null
        if [ -s "$HOME/.udocker/lib/VERSION" ]; then
          _UDOCKER_READY=true
          log "udockertools instalado (mirror: ${_mirror##*/})"
          break
        fi
      fi
      warn "Mirror ${_mirror##*/} no funcionó — probando siguiente..."
    done
    rm -f "$_udocker_tmp_tarball" 2>/dev/null

    if ! $_UDOCKER_READY; then
      error "No se pudo instalar udockertools (probados ${#UDOCKER_TARBALL_MIRRORS[@]} mirrors) — la variante 'proot' no depende de udocker/udockertools, probá esa en su lugar"
    fi

    # execmode P2 (proot puro) forzado siempre, no solo ante advertencias
    # (comportamiento anterior, atado al mismo chequeo roto de arriba) — los
    # modos F1-F3 de udocker dependen de fakechroot interceptando llamadas
    # libc, algo que Bionic/Android no soporta de forma confiable sin root;
    # P2 es el único modo realista en este entorno, igual que proot-distro ya
    # usa proot puro para la variante "proot" de este mismo script.
    touch "$HOME/.udocker_force_p2"
    mark_done "udocker_install"
  fi

  # ── PASO 1 — Directorio de datos ─────────────────────────
  step "1/7 Directorio de datos"
  if check_done "datadir"; then
    log "Directorio ya creado [checkpoint]"
  else
    mkdir -p "$N8N_DATA_ABS"
    chmod 777 "$N8N_DATA_ABS"
    mark_done "datadir"
    log "Directorio creado: $N8N_DATA_ABS"
  fi

  # ── PASO 1.5 — cloudflared nativo ────────────────────────
  step "1.5/7 Instalando cloudflared"
  if check_done "cloudflared"; then
    log "cloudflared ya instalado [checkpoint]"
  else
    if command -v cloudflared &>/dev/null; then
      log "cloudflared ya disponible"
    else
      # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
      pkg_update_with_fallback
      if pkg install -y cloudflared 2>/dev/null; then
        log "cloudflared instalado via pkg"
      else
        timeout 30 wget -q "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64" \
          -O "$TERMUX_PREFIX/bin/cloudflared" 2>/dev/null
        chmod +x "$TERMUX_PREFIX/bin/cloudflared"
        # Verificar que el binario descargado realmente ejecuta — no solo que
        # exista en PATH (fix 2026-07-27: el binario ARM64 de cloudflared no
        # siempre corre sobre Bionic; sin este chequeo podía quedar un binario
        # roto instalado en silencio, mismo bug ya corregido en ssh.sh).
        CF_VER=$(cloudflared --version 2>/dev/null | head -1)
        if [ -n "$CF_VER" ]; then
          log "cloudflared instalado — $CF_VER"
        else
          rm -f "$TERMUX_PREFIX/bin/cloudflared"
          error "cloudflared descargado pero no ejecuta (incompatible con Bionic)"
        fi
      fi
    fi
    mark_done "cloudflared"
  fi

  # ── PASO 2 — Imagen n8n ───────────────────────────────────
  step "2/7 Descargando imagen n8nio/n8n"
  if check_done "image_pull"; then
    log "Imagen ya descargada [checkpoint]"
  else
    info "Descargando imagen oficial n8nio/n8n (~800MB, puede tardar)..."
    # Con udockertools ya verificado en PASO 0, un fallo acá es de red/registry
    # (DNS, rate-limit de Docker Hub, conexión inestable) — no el problema
    # original de "installation of udockertools failed". Mensaje accionable
    # en vez del error crudo de udocker, con la variante 'proot' como salida
    # (no depende de Docker Hub ni de udocker).
    #
    # --platform=linux/arm64 explícito: bug real confirmado por ADB en dispositivo
    # (docs/humano269.md ronda de auditoría) — udocker arma el selector de
    # plataforma con HostInfo.osversion() = platform.system().lower(), y el
    # Python de Termux devuelve "Android" (no "Linux") ahí, así que sin este
    # flag udocker pide el manifest de "android/arm64" a Docker Hub, que no
    # existe ("no image found in manifest for platform (android/arm64)") y la
    # descarga falla siempre, en todo dispositivo. --platform es un flag real
    # y documentado del propio udocker (ver udocker/cli.py, "udocker pull
    # --platform=linux/arm64 <imagen>" en su propio --help).
    udocker pull --platform=linux/arm64 n8nio/n8n || \
      error "Falló la descarga de la imagen n8n (red/Docker Hub) — probá de nuevo o usá la variante 'proot' en su lugar"
    mark_done "image_pull"
    log "Imagen descargada"
  fi

  # ── PASO 3 — Contenedor ───────────────────────────────────
  step "3/7 Creando contenedor n8n"
  if check_done "container_create"; then
    log "Contenedor ya creado [checkpoint]"
  else
    udocker rm n8n 2>/dev/null || true
    udocker create --name=n8n n8nio/n8n || error "Falló la creación del contenedor n8n"
    if [ -f "$HOME/.udocker_force_p2" ]; then
      udocker setup --execmode=P2 n8n 2>/dev/null || warn "No se pudo cambiar execmode"
    fi
    mark_done "container_create"
    log "Contenedor n8n creado"
  fi

  # ── PASO 4 — Scripts de control ──────────────────────────
  step "4/7 Creando scripts de control"
  if check_done "scripts"; then
    log "Scripts ya creados [checkpoint]"
  else
    _n8n_write_control_scripts_udocker "$N8N_SCRIPTS_UDOCKER"
    mark_done "scripts"
    log "Scripts de control creados"
  fi

  # ── PASO 5 — Aliases ──────────────────────────────────────
  step "5/7 Configurando aliases"
  if check_done "aliases"; then
    log "Aliases ya configurados [checkpoint]"
  else
    [ -f "$BASHRC" ] && grep -v "n8n-ud-start\|n8n-ud-stop\|n8n-ud-status\|n8n-ud-log\|n8n-ud-update\|n8n-ud-backup" \
      "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"
    cat >> "$BASHRC" << 'ALIASES'

# ════ n8n udocker · aliases ════
alias n8n-ud-start='bash ~/scripts/n8n-udocker/start.sh'
alias n8n-ud-stop='bash ~/scripts/n8n-udocker/stop.sh'
alias n8n-ud-status='bash ~/scripts/n8n-udocker/status.sh'
alias n8n-ud-log='bash ~/scripts/n8n-udocker/log.sh'
alias n8n-ud-update='bash ~/scripts/n8n-udocker/update.sh'
alias n8n-ud-backup='bash ~/scripts/n8n-udocker/backup.sh'
ALIASES
    mark_done "aliases"
    log "Aliases configurados"
  fi

  # ── PASO 6 — Arranque automático ─────────────────────────
  step "6/7 Arranque automático"
  if check_done "boot"; then
    log "Arranque automático ya configurado [checkpoint]"
  else
    BOOT_DIR="$HOME/.termux/boot"
    mkdir -p "$BOOT_DIR"
    cat > "$BOOT_DIR/start_n8n_udocker.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/sbin:\$PATH
sleep 30
termux-wake-lock
bash ~/scripts/n8n-udocker/start.sh
SCRIPT
    chmod +x "$BOOT_DIR/start_n8n_udocker.sh"
    mark_done "boot"
    log "Arranque automático configurado"
  fi

  # ── PASO 7 — Verificación real + registry ────────────────
  step "7/7 Finalizando"
  # Antes: update_registry (n8n.installed=true) se escribía ANTES de arrancar n8n,
  # y "bash start.sh" corría después sin chequear su resultado — start.sh siempre
  # devolvía 0 (su loop de healthz nunca hacía exit 1 si n8n no respondía en 60s),
  # así que el registry quedaba marcado "instalado" aunque el contenedor nunca
  # levantara de verdad (mismo patrón de checkpoint-sin-verificar ya corregido en
  # otros módulos esta sesión). Ahora start.sh devuelve código real (ver fix en su
  # heredoc, PASO 4) y se verifica ANTES de tocar el registry.
  info "Verificando arranque inicial..."
  if ! bash "$N8N_SCRIPTS_UDOCKER/start.sh"; then
    invalidate_registry
    error "n8n (udocker) se instaló pero no arrancó — revisa el log con: bash ~/scripts/n8n-udocker/log.sh (o ~/kairos_logs/install_n8n.log)"
  fi

  # Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): "udocker images" en la
  # versión real instalada (1.3.17) devuelve REPO:TAG en una sola columna
  # ("n8nio/n8n:latest"), no en 2 columnas separadas — "awk '{print $2}'" agarraba la
  # columna del flag "protected" (".") en vez del tag, dejando "n8n.version=." en el
  # registry. Se extrae el tag real de después de los ":" en la primera columna.
  N8N_IMG_VER=$(udocker images 2>/dev/null | grep "n8nio/n8n" | awk '{print $1}' | head -1 | cut -d: -f2)
  [ -z "$N8N_IMG_VER" ] && N8N_IMG_VER="latest"
  update_registry "udocker" "$N8N_IMG_VER"
  rm -f "$CHECKPOINT"

  notify_event "n8n" "install_done" "udocker"
  log "n8n (udocker) instalado correctamente"
  exit 0
fi

# ════════════════════════════════════════════════════════════
#  VARIANTE: proot Debian (comportamiento original, sin cambios)
# ════════════════════════════════════════════════════════════
CHECKPOINT="$HOME/.install_n8n_checkpoint"
N8N_SCRIPTS="$HOME/scripts/n8n"

ensure_restore_sh() {
  if [ ! -f "$HOME/restore.sh" ] || [ ! -s "$HOME/restore.sh" ]; then
    info "Descargando restore.sh..."
    curl -fsSL "https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/restore.sh" \
      -o "$HOME/restore.sh" && chmod +x "$HOME/restore.sh" || \
      error "No se pudo descargar restore.sh"
  fi
}

# ── Detectar rootfs ──────────────────────────────────────────
# ROOTFS_BASE/CONTAINERS_BASE/_detect_rootfs() ahora se definen cerca del
# principio del archivo (junto al resto de funciones compartidas) porque
# --repair-scripts en modo proot también necesita detectar el rootfs antes
# de llegar a esta sección — ver bloque "Detección de rootfs (compartida)".
_detect_rootfs

# ── Verificar si ya está instalado ────────────────────────────
if [ -n "$DISTRO_NAME" ] && ! $FORCE; then
  if proot-distro login "$DISTRO_NAME" -- bash -c 'command -v n8n' &>/dev/null 2>&1; then
    N8N_VER=$(proot-distro login "$DISTRO_NAME" -- bash -c 'n8n --version 2>/dev/null' 2>/dev/null | head -1)
    log "n8n ya instalado — v${N8N_VER} ($DISTRO_NAME)"
    # Mismo bug real que la variante udocker (docs/humano281.md) — este atajo tampoco pasaba
    # por update_registry(), dejando "n8n.installed=false" atascado en cualquier corrida donde
    # el registry se hubiera invalidado o nunca escrito, pese a que n8n funciona de verdad.
    update_registry "proot" "${N8N_VER:-desconocida}"
    exit 0
  fi
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: menú + confirmación ─────────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · n8n Installer           ║
  ║   proot Debian · cloudflared · v4.0.0      ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"

  echo -e "  ${BOLD}¿Cómo instalar n8n?${NC}"
  echo ""
  echo -e "  [1] Todo desde GitHub       ${GRAY}~834MB · 5-10 min${NC}"
  echo -e "  [2] Todo limpio             ${GRAY}~300MB · 25-40 min${NC}"
  echo -e "  [3] Rootfs GitHub + n8n limpio"
  echo -e "  [4] Rootfs limpio + n8n GitHub"
  echo ""
  echo -n "  Opción [2]: "
  read -r _MODE < /dev/tty
  case "${_MODE:-2}" in
    1) INSTALL_SOURCE="github" ;;
    3) INSTALL_SOURCE="rootfs-github" ;;
    4) INSTALL_SOURCE="rootfs-clean" ;;
    *) INSTALL_SOURCE="clean" ;;
  esac

  echo ""
  echo "  Modo: $INSTALL_SOURCE"
  echo -n "  ¿Continuar? (s/n): "
  read -r _CONF < /dev/tty
  [ "$_CONF" != "s" ] && [ "$_CONF" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── Pre-restaurar según modo ─────────────────────────────────
CLEAN_ROOTFS=true
CLEAN_N8N=true

case "$INSTALL_SOURCE" in
  github)
    ensure_restore_sh
    info "Restaurando rootfs + n8n desde GitHub..."
    bash "$HOME/restore.sh" --module proot-base --source github || \
      error "Fallo restore rootfs"
    bash "$HOME/restore.sh" --module n8n --source github || \
      error "Fallo restore n8n"
    mark_done "debian_install"
    mark_done "n8n_install"
    CLEAN_ROOTFS=false
    CLEAN_N8N=false
    ;;
  rootfs-github)
    ensure_restore_sh
    info "Restaurando rootfs desde GitHub..."
    bash "$HOME/restore.sh" --module proot-base --source github || \
      error "Fallo restore rootfs"
    mark_done "debian_install"
    CLEAN_ROOTFS=false
    CLEAN_N8N=true
    ;;
  rootfs-clean)
    ensure_restore_sh
    CLEAN_ROOTFS=true
    CLEAN_N8N=false
    ;;
  clean)
    CLEAN_ROOTFS=true
    CLEAN_N8N=true
    ;;
esac

TOTAL_STEPS=8

# ============================================================
# PASO 1 — Termux update
# ============================================================
step "1/$TOTAL_STEPS Verificando Termux"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado [skip]"
elif check_done "termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  # Quick win de la auditoría de referencia/ (2026-08-05, ver docs/humano70.md) — antes
  # probaba solo 2 mirrors fijos en orden; ahora comparte la selección por velocidad
  # real centralizada en lib.sh (mismo criterio que entorno.sh/kairos.sh).
  pkg_update_with_fallback

  pkg upgrade -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>/dev/null || true

  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
    curl wget tar xz-utils tmux proot proot-distro busybox iproute2 git unzip \
    2>/dev/null || warn "Algunos paquetes tuvieron advertencias"

  log "Termux actualizado"
  mark_done "termux_update"
fi

# ============================================================
# PASO 2 — Debian Bookworm
# ============================================================
step "2/$TOTAL_STEPS Instalando Debian"

_detect_rootfs

if ! $CLEAN_ROOTFS; then
  log "Rootfs instalado desde GitHub [skip]"
  [ -z "$DISTRO_NAME" ] && { _detect_rootfs; }
  [ -z "$DISTRO_NAME" ] && error "Rootfs no encontrado tras restore"
elif check_done "debian_install"; then
  log "Debian ya instalado [checkpoint]"
  [ -z "$DISTRO_NAME" ] && { _detect_rootfs; }
  [ -z "$DISTRO_NAME" ] && error "Checkpoint activo pero rootfs no encontrado"
else
  if [ -n "$DISTRO_NAME" ]; then
    log "Rootfs ya existe: $DISTRO_NAME"
  else
    if ! command -v proot-distro &>/dev/null; then
      info "Instalando proot-distro..."
      # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
      pkg_update_with_fallback
      pkg install proot-distro proot -y \
        -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" || \
        error "No se pudo instalar proot-distro"
    fi

    info "Instalando Debian Bookworm..."
    _OUT=$(proot-distro install debian 2>&1); _RC=$?
    echo "$_OUT" | grep -q "already exists" && log "Debian ya registrado" || \
      { [ $_RC -ne 0 ] && error "Falló instalación de Debian: $_OUT"; }

    sleep 2
    _detect_rootfs
    [ -z "$DISTRO_NAME" ] && error "Rootfs no encontrado tras instalación"
    log "Debian instalado: $DISTRO_NAME"
  fi
  mark_done "debian_install"
fi

info "Usando distro: $DISTRO_NAME"

# ============================================================
# PASO 3 — n8n + cloudflared dentro del proot
# ============================================================
step "3/$TOTAL_STEPS Instalando n8n + cloudflared en Debian"

if check_done "n8n_install"; then
  log "n8n ya instalado [checkpoint]"
elif ! $CLEAN_N8N; then
  if [ "$INSTALL_SOURCE" = "rootfs-clean" ]; then
    info "Restaurando n8n desde GitHub..."
    ensure_restore_sh
    bash "$HOME/restore.sh" --module n8n --source github || \
      error "Falló restore de n8n"
  fi
  mark_done "n8n_install"
  log "n8n restaurado"
else
  info "Instalando software en Debian (15-25 min)..."

  proot-distro login "$DISTRO_NAME" -- bash << 'INNER'
set -e
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
DPKG_OPTS='-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold'

# dpkg self-heal: mismo patrón real ya confirmado y aplicado en ciberseguridad.sh/entorno.sh
# (2026-08-27, ver docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md) — un dpkg/apt-get de una
# corrida ANTERIOR de este mismo proot-distro (killeada por Android en background, o por
# ProcessBuilder.destroy() sin llegar a matar la cadena completa de descendientes de proot) puede
# quedar colgado sosteniendo /var/lib/dpkg/lock-frontend, dejando la base de dpkg a medio
# configurar. Sin este self-heal, CUALQUIER "apt-get update/install" posterior en este mismo
# contenedor falla instantáneo con "dpkg was interrupted, you must manually run 'dpkg --configure
# -a'" — no reportado todavía para n8n específicamente, pero el mecanismo (mismo proot-distro,
# mismo apt-get, mismo riesgo de kill a mitad de instalación) es idéntico al ya confirmado en
# Kali/ciberseguridad. No-op instantáneo si no hay nada que reparar.
echo "[0/6] Verificando estado de dpkg..."
dpkg --configure -a 2>&1 | tail -20 || true

echo "[1/6] Actualizando Debian..."
apt-get update -qq
apt-get upgrade -y -qq $DPKG_OPTS 2>/dev/null
apt-get install -y -qq $DPKG_OPTS \
  curl wget git nano build-essential \
  python3 python3-pip python3-setuptools python3-dev \
  ca-certificates gnupg lsb-release \
  procps apt-transport-https iproute2
echo "[OK] Debian actualizado"

echo "[2/6] Instalando Node.js 22 LTS..."
# Node 20 llegó a EOL el 2026-04-30 (sin parches de seguridad desde entonces) y
# n8n exige Node >=20.19 (docs.n8n.io/deploy/host-n8n/install-options/install-with-npm,
# confirmado 2026-07-31) — Node 22 LTS (soportado hasta 2027-04-30) es la versión
# activa recomendada hoy y cae dentro del rango que n8n soporta (20.19–24.x).
#
# "curl | bash >/dev/null 2>&1" (versión anterior) escondía CUALQUIER fallo real de
# curl (DNS, TLS, script de NodeSource deprecado/movido) — el pipe siempre "tenía
# éxito" porque su exit code es el de `bash -` (último comando), no el de curl: si
# curl fallaba, bash simplemente leía stdin vacío y salía con 0. apt-get install
# nodejs procedía igual, cayendo al nodejs 18.x de los repos base de Debian
# Bookworm en vez de fallar con un mensaje claro — el chequeo de NODE_MAJOR de abajo
# lo detectaba, pero con un mensaje engañoso ("Node < 20") que ocultaba la causa
# real (falla de red/curl, no una versión vieja instalada a propósito).
NODESOURCE_LOG="/tmp/nodesource_setup.log"
curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >"$NODESOURCE_LOG" 2>&1
CURL_RC=${PIPESTATUS[0]}
if [ "$CURL_RC" -ne 0 ]; then
  echo "[ERROR] curl a deb.nodesource.com falló (exit $CURL_RC) — sin esto, nodejs cae al 18.x de los repos base de Debian"
  tail -20 "$NODESOURCE_LOG"
  exit 1
fi
apt-get install -y $DPKG_OPTS nodejs
echo "[OK] Node.js $(node --version)"

# Sospecha real (ver docs/humano/humano63.md): si "node --version" falla, NODE_MAJOR queda
# vacío y la comparación de abajo tira un error de bash genérico ("integer expression
# expected") en vez de diagnosticar el problema real — se chequea explícito antes.
NODE_MAJOR=$(node --version | sed 's/v//' | cut -d'.' -f1)
if ! [[ "$NODE_MAJOR" =~ ^[0-9]+$ ]]; then
  echo "[ERROR] No se pudo determinar la versión de Node instalada (node --version no devolvió un número válido) — revisá que nodejs se haya instalado bien arriba"
  exit 1
fi
[ "$NODE_MAJOR" -lt 20 ] && echo "[ERROR] Node < 20 (n8n requiere >=20.19) — nodesource pudo haber fallado silenciosamente" && exit 1

echo "[3/6] Configurando Python/node-gyp..."
export npm_config_python=$(which python3)
export PYTHON=$(which python3)
cat >> /root/.bashrc << 'PROFILE'
export npm_config_python=$(which python3)
export PYTHON=$(which python3)
export N8N_HOST=0.0.0.0
export N8N_PORT=5678
export N8N_SECURE_COOKIE=false
PROFILE
echo "[OK] Variables configuradas"

echo "[4/6] Instalando n8n (10-20 min)..."
# "npm install ... | tail -3" (versión anterior) truncaba TODO el output real de
# npm a 3 líneas y, peor, el exit code de la pipeline era el de `tail` (siempre 0),
# no el de npm — un fallo de npm (registry caído, EACCES, dependencia rota) no
# frenaba el script acá; recién se detectaba 2 líneas después vía `n8n --version`,
# sin ningún detalle real del error de npm en el log (mismo patrón de pipe sin
# pipefail que el paso de Node.js de arriba). PIPESTATUS[0] captura el exit code
# real de npm (primer comando del pipe) sin necesitar `set -o pipefail` global.
npm install -g n8n --unsafe-perm 2>&1 | tail -20
NPM_RC=${PIPESTATUS[0]}
[ "$NPM_RC" -ne 0 ] && echo "[ERROR] npm install -g n8n falló (exit $NPM_RC) — ver output de npm arriba" && exit 1
N8N_VER=$(n8n --version 2>/dev/null || echo "error")
[ "$N8N_VER" = "error" ] && echo "[ERROR] n8n no instaló (npm reportó éxito pero el binario no funciona)" && exit 1
echo "[OK] n8n $N8N_VER"

echo "[5/6] Instalando cloudflared..."
timeout 30 wget -q "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64" \
  -O /usr/local/bin/cloudflared
chmod +x /usr/local/bin/cloudflared
echo "[OK] $(cloudflared --version 2>/dev/null | head -1)"

echo "[6/6] Verificación final..."
echo "  Node.js:     $(node --version)"
echo "  n8n:         $(n8n --version 2>/dev/null)"
echo "  cloudflared: $(cloudflared --version 2>/dev/null | head -1)"
echo "[COMPLETADO]"
INNER

  [ $? -eq 0 ] || error "Setup de Debian falló"
  mark_done "n8n_install"
  log "n8n + cloudflared instalados"
fi

# ============================================================
# PASO 4 — Scripts de control
# ============================================================
step "4/$TOTAL_STEPS Creando scripts de control"

if check_done "scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  _n8n_write_control_scripts_proot "$N8N_SCRIPTS" "$DISTRO_NAME"
  mark_done "scripts"
  log "Scripts de control creados"
fi

# ============================================================
# PASO 5 — Aliases
# ============================================================
step "5/$TOTAL_STEPS Configurando aliases"

if check_done "aliases"; then
  log "Aliases ya configurados [checkpoint]"
else
  [ -f "$BASHRC" ] && grep -v "n8n-start\|n8n-stop\|n8n-url\|n8n-status\|n8n-log\|n8n-update\|n8n-backup\|cf-token\|alias debian\|# n8n" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  n8n · aliases
# ════════════════════════════════
alias n8n-start='bash ~/scripts/n8n/start_servidor.sh'
alias n8n-stop='bash ~/scripts/n8n/stop_servidor.sh'
alias n8n-url='bash ~/scripts/n8n/ver_url.sh'
alias n8n-status='bash ~/scripts/n8n/n8n_status.sh'
alias n8n-log='bash ~/scripts/n8n/n8n_log.sh'
alias n8n-update='bash ~/scripts/n8n/n8n_update.sh'
alias n8n-backup='bash ~/scripts/n8n/n8n_backup.sh'
alias cf-token='bash ~/scripts/n8n/cf_token.sh'
alias debian='bash ~/debian.sh'
ALIASES

  mark_done "aliases"
  log "Aliases configurados"
fi

# ============================================================
# PASO 6 — Arranque automático (Termux:Boot)
# ============================================================
step "6/$TOTAL_STEPS Configurando arranque automático"

if check_done "boot"; then
  log "Arranque automático ya configurado [checkpoint]"
else
  BOOT_DIR="$HOME/.termux/boot"
  mkdir -p "$BOOT_DIR"
  cat > "$BOOT_DIR/start_n8n.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/sbin:\$PATH
sleep 25
termux-wake-lock
bash ~/scripts/n8n/start_servidor.sh
SCRIPT
  chmod +x "$BOOT_DIR/start_n8n.sh"
  mark_done "boot"
  log "Arranque automático configurado"
fi

# ============================================================
# PASO 7 — Registry
# ============================================================
step "7/$TOTAL_STEPS Actualizando registry"

_detect_rootfs
N8N_VER_REG=$(proot-distro login "$DISTRO_NAME" -- bash -c \
  'n8n --version 2>/dev/null' 2>/dev/null | head -1)
# Antes: si N8N_VER_REG salía vacío (n8n --version realmente falla — rootfs
# corrompido entre pasos, o restore.sh/--source github dejó algo a medias sin que
# CLEAN_N8N=false lo detectara) el script igual escribía "unknown" y llamaba
# update_registry, marcando n8n.installed=true sin haber confirmado que el
# binario funciona (mismo patrón de checkpoint-sin-verificar corregido en la
# variante udocker más arriba). El check real de PASO 3 (dentro del proot) ya
# valida esto al instalar, pero este es el chequeo final antes de tocar el
# registry — debe fallar duro si no puede confirmarlo, no asumir éxito.
[ -z "$N8N_VER_REG" ] && { invalidate_registry; error "n8n no respondió a --version tras la instalación — revisa ~/kairos_logs/install_n8n.log"; }
update_registry "proot" "$N8N_VER_REG"

# ============================================================
# PASO 8 — Limpieza
# ============================================================
step "8/$TOTAL_STEPS Finalizando"
rm -f "$CHECKPOINT"

if ! $SILENT; then
  IP=$(ip addr show wlan0 2>/dev/null | grep "inet " | awk '{print $2}' | cut -d'/' -f1)
  echo ""
  echo -e "${GREEN}${BOLD}  n8n instalado ✓${NC}"
  echo ""
  echo "  n8n:     v${N8N_VER_REG}"
  echo "  Puerto:  5678"
  [ -n "$IP" ] && echo "  WiFi:    http://${IP}:5678"
  echo "  Distro:  $DISTRO_NAME"
  echo ""
fi

notify_event "n8n" "install_done" "proot"
log "Instalación de n8n completada"
exit 0
