#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · lib.sh — funciones compartidas por modulos/*.sh
#
#  Elimina la duplicación de log/warn/error/check_done/mark_done
#  que estaba copiada casi idéntica en los 13 scripts de modulos/
#  (ver docs/viejo/PROPUESTA_SCRIPTS_MODULOS.md, sección de duplicación).
#
#  USO — cada script debe, ANTES de sourcear esto:
#    1. Parsear sus propios flags y dejar $SILENT en true/false
#    2. Definir $CHECKPOINT (ruta del archivo de checkpoint)
#    3. Definir $REGISTRY (normalmente $HOME/.android_server_registry)
#  Luego:
#    source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#
#  NO incluye (dejado en cada script — parseo propio):
#    - Parseo de flags propias (--variant, --source, etc.)
#
#  VERSIÓN: 1.2.0 | Agosto 2026 (agrega install_single_pkg/ensure_node_installed/
#  install_npm_global — helpers genéricos para "paquetes adicionales" de una sola
#  línea: pkg install <x> o npm install -g <x>, ver docs/humano/ ronda "paquetes
#  adicionales core-termux". Evita repetir el mismo esqueleto de 40 líneas
#  (dependencias/check/error/registry) en cada script de lenguaje/herramienta npm.)
# ============================================================

# ── PATH universal: cubre el prefix alternativo de npm ────────
# Bug real confirmado (auditoría ADB 2026-08-22, ver docs/humano/humano189.md, bug #25):
# openclaw.sh corre "npm config set prefix ~/.npm-global", que muta ~/.npmrc GLOBAL
# del usuario — desde ese momento TODO "npm install -g" de cualquier módulo instala
# ahí en vez del prefix default de Termux. Cada script de modulos/ solo agrega
# $TERMUX_PREFIX/bin a su PATH (nunca ~/.npm-global/bin), así que "command -v <cmd>"
# fallaba para cualquier módulo npm-CLI instalado después de que el prefix cambiara
# — rompió Qwen Code por completo pese a instalar bien. Se agrega acá (fuente
# compartida por todos los scripts) en vez de repetir la línea en cada uno, para que
# funcione sin importar qué módulo cambió el prefix ni cuándo.
#
# Mismo motivo se agrega $HOME/.local/bin acá (auditoría de módulos 2026-08-27,
# ver docs/arquitectura/AUDITORIA_MODULOS_2026-08-27.md): cursor.sh/hf.sh/claude.sh/
# codex.sh (instaladores oficiales curl-based, no pkg/npm) dejan su binario en
# $HOME/.local/bin — ningún script de modulos/ lo agrega a su propio PATH (cada uno
# solo antepone $TERMUX_PREFIX/bin), y lib.sh tampoco lo hacía hasta ahora. En la
# invocación real de la app esto queda encubierto porque ProcessBuilderExt.kt SÍ
# setea $HOME/.local/bin en el PATH del proceso hijo (ver PATH en
# app/src/main/java/com/termux/app/util/ProcessBuilderExt.kt) y el bootstrap de
# kairos.sh también lo agrega al .bashrc de sesiones interactivas — pero
# verificar.sh (y cualquier script de modulos/ corrido suelto, ej. debug manual
# por SSH/adb shell sin esos dos mecanismos) hacía "command -v cursor-agent"/"hf"
# con un PATH incompleto, dando un falso WARN/SKIP pese a que el binario sí existe.
export PATH="${HOME:-/data/data/com.termux/files/home}/.local/bin:${HOME:-/data/data/com.termux/files/home}/.npm-global/bin:$PATH"

# ── Guard anti-recursión del wrapper de apt (~/.local/bin/apt, ver kairos.sh) ──
# Bug real encontrado en auditoría forense 2026-08-29 (docs/humano285.md, ronda del fix de
# ide.sh): el wrapper de apt intercepta CUALQUIER "pkg install <pkg>" cuyo <pkg> coincida con
# un id de módulo Kairos (ollama, python, etc.) — pero varios módulos reales instalan el
# paquete de Termux "python" (mismo nombre que el módulo) con una lista de UN solo elemento:
# cactus.sh, ciberseguridad.sh, hf.sh, mistralvibe.sh, stacks.sh, y el propio python.sh. Como
# ese caso de un solo elemento coincidente NO cae en la rama "hay paquetes reales mezclados"
# (ya cubierta por el fix de ide.sh), el wrapper corre "bash python.sh --silent" en vez de
# instalar el paquete real — y python.sh, al llegar a su propio "pkg install python", vuelve a
# pasar por este mismo wrapper, disparando OTRA recursión idéntica sin cota (mismo patrón para
# ollama.sh instalándose a sí mismo con la variante "standard": "pkg install ollama -y").
# Confirmado por lectura de código que esto es recursión real sin caso base natural (python.sh
# nunca llega a instalar python de verdad, así que "ya instalado" nunca se cumple para cortar
# el ciclo). Fix: cualquier script de módulo que sourcea lib.sh (prácticamente todos) exporta
# esta variable — el wrapper de apt la chequea y, si ya está seteada, NUNCA reintenta detectar
# ids de módulo: reenvía todo directo al apt real sin filtrar. La conveniencia de "apt install
# <id>" solo tiene sentido para una sesión de terminal interactiva del usuario (que nunca
# sourcea lib.sh) — dentro de un script de módulo ya en ejecución, cualquier pkg install debe
# ir siempre al paquete real, nunca disparar otro instalador de módulo implícitamente.
export KAIROS_MODULE_SCRIPT_ACTIVE=1

# ── Lock real sobre pkg/apt — serializa instalaciones concurrentes ──────────
# Bug real confirmado (log interno ~/kairos_logs/kairos_app.log + install_db.log/
# install_engram.log del dispositivo, ronda 2026-08-26): la app permite lanzar hasta
# MAX_CONCURRENT_INSTALLS=4 instalaciones de módulos a la vez (ver
# InstallQueueManager.kt) sin ningún mecanismo que serialice el segmento de CADA
# instalación que toca `pkg`/`apt` — ese límite acota CUÁNTAS instalaciones corren
# en paralelo, no evita que 2+ de ellas corran `pkg install`/`pkg update` al mismo
# tiempo real. Confirmado con evidencia real: "db" y "cactus" arrancaron con 2s de
# diferencia, "engram"/"hermes"/"hf" casi al mismo tiempo, y los logs de instalación
# mostraban el error real de apt: "E: Could not get lock .../var/lib/apt/lists/lock.
# It is held by process N (apt)".
#
# Se envuelve el binario real "pkg" con una función bash del mismo nombre que toma
# un flock exclusivo (un único lockfile para toda la app) antes de delegar al pkg
# real — cualquier script que sourcee lib.sh (57 de 57 en modulos/) queda protegido
# automáticamente, sin tener que tocar cada uno de los ~57 call-sites de
# "pkg install"/"pkg update" repartidos en modulos/*.sh (muchos NO pasan por los
# helpers install_single_pkg()/install_npm_global()/pkg_update_with_fallback() de
# más abajo — llaman a "pkg" directo). `command pkg` (no `command -v pkg`) bypassea
# a propósito esta misma función de nombre "pkg" para no recursar sobre sí misma.
#
# `apt-get`/`apt` corridos DENTRO de un proot-distro (ver cactus.sh
# _install_cactus_glibc_fallback(), n8n.sh instalación en proot Debian) NO pasan por
# acá — corren en un filesystem/namespace de apt completamente distinto al de
# Termux (host), así que no compiten por el mismo lock real y no necesitan este
# wrapper.
#
# -w 300 (5 min): más que suficiente incluso para el peor caso de 4 instalaciones
# encoladas una detrás de otra en el segmento apt; si se agota, falla con un
# mensaje claro en vez de colgar el script para siempre.
#
# Self-heal de "dpkg was interrupted" (auditoría 2026-08-27, ver
# docs/humano270.md/docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md): este mismo
# mecanismo (un `apt-get`/`pkg install` anterior matado a mitad de un dpkg — señal 15 por
# falta de memoria durante una tanda masiva de instalaciones concurrentes, ver
# InstallQueueManager.kt) ya se confirmó y arregló 3 veces, pero SOLO dentro de contenedores
# proot (n8n.sh proot, entorno.sh GUI, ciberseguridad.sh) — nunca en el `pkg` del HOST de
# Termux, pese a que este wrapper es exactamente el choke point compartido por TODOS los
# módulos con instalación apt pesada (ollama.sh variante GPU, db.sh con 3 motores, qemu.sh,
# apk.sh, cactus.sh, engram.sh, y el resto de modulos/*.sh que llaman a "pkg install"). El
# mismo kill por OOM que ya rompió dpkg dentro de proot puede romper igual el dpkg del host
# — sin este self-heal, CUALQUIER "pkg install" posterior en el resto de la sesión (incluso
# de un módulo totalmente distinto) fallaría con el mismo "E: dpkg was interrupted, you must
# manually run 'dpkg --configure -a'" hasta que alguien lo reparara a mano.
#
# Se preserva el streaming en vivo del output (tee, no una captura buffereada) — un pkg()
# que solo devuelve texto al final rompería la lectura incremental de logs de
# ModuleController.kt para las ~57 llamadas existentes a este wrapper. PIPESTATUS[0] toma el
# exit code real de "command pkg", no el de tee. Solo reintenta (una vez) cuando el error es
# específicamente "dpkg was interrupted" — cualquier otro fallo de pkg (paquete no
# encontrado, sin red) sale tal cual, sin reintento ni ruido extra.
_KAIROS_APT_LOCKFILE="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/kairos_apt.lock"
pkg() {
  (
    flock -w 300 200 || { echo "[ERROR] pkg: no se pudo obtener el lock de apt tras 300s (otra instalación lo tiene tomado)" >&2; exit 1; }
    _out="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/kairos_pkg_out.$$"
    command pkg "$@" 2>&1 | tee "$_out"
    _status="${PIPESTATUS[0]}"
    if [ "$_status" -ne 0 ] && grep -q "dpkg was interrupted" "$_out" 2>/dev/null; then
      rm -f "$_out"
      echo "[WARN] pkg: dpkg quedó interrumpido (kill/OOM en una instalación anterior) — autoreparando con 'dpkg --configure -a' y reintentando..." >&2
      dpkg --configure -a >/dev/null 2>&1
      command pkg "$@"
    else
      rm -f "$_out"
      exit "$_status"
    fi
  ) 200>"$_KAIROS_APT_LOCKFILE"
}

# ── Lock real sobre npm — serializa "npm install -g" concurrentes ──────────
# Bug real confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md, ronda
# 2026-08-27 "instalación masiva de 45 módulos"): tras correr ~52 módulos con concurrencia
# 5 (varios de ellos "npm install -g <pkg>", vía install_npm_global() o directo), CASI TODOS
# los paquetes npm-globales instalados esa tanda (kimi, prettier, ncu, ngrok, vercel,
# markserv, nestjs, psqlformat, livesrv, minimaxcli, mistralvibe, qwencode, copilotcli,
# codebuff, cursor, hf, pi, openclaw, opencode...) desaparecieron por completo del
# filesystem (ni el binario en $PREFIX/bin ni el paquete en $PREFIX/lib/node_modules/ — no
# es un problema de PATH ni de prefix de npm distinto: se confirmó con `find` que no existen
# EN NINGÚN LADO) pese a que cada script logueó una instalación exitosa y verificada
# (verify_binary_installed pasó en su momento). Se descartaron por prueba real: corrupción
# simple de 2-5 "npm install -g" en paralelo (reproducido a mano, no falló), prefix de npm
# mutado por openclaw.sh (~/.npmrc no existía), reinstalación de nodejs-lts a otra versión
# (node --version quedó igual antes/después). Causa raíz exacta del "desaparecido" sin
# confirmar con certeza total (hipótesis más consistente con la evidencia: presión de
# recursos sostenida — ~1h de instalaciones/compilaciones concurrentes — provocó que Android
# matara/reiniciara el proceso de Termux bajo memoria baja en algún punto intermedio). Lo que
# SÍ es una mitigación real y de bajo riesgo, mismo patrón ya probado con `pkg()` arriba:
# serializar "npm install -g" con un flock reduce la ventana de escritura concurrente al
# árbol global de npm (que de por sí no es atómico entre procesos) y evita que 5 procesos
# escriban/creen symlinks en $PREFIX/bin al mismo tiempo. `command npm` (no `command -v npm`)
# bypassea a propósito esta misma función para no recursar. Solo se serializa "install -g" —
# cualquier otro subcomando de npm (ls, --version, config, etc., usados por describe-files/
# verify_cmd) pasa directo, sin tomar el lock, para no demorarlos innecesariamente.
_KAIROS_NPM_LOCKFILE="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/kairos_npm.lock"
npm() {
  case "$1" in
    install)
      (
        flock -w 300 200 || { echo "[ERROR] npm: no se pudo obtener el lock de npm tras 300s (otra instalación lo tiene tomado)" >&2; exit 1; }
        command npm "$@"
      ) 200>"$_KAIROS_NPM_LOCKFILE"
      ;;
    *)
      command npm "$@"
      ;;
  esac
}

# ── Lock real sobre pip — serializa "pip install"/"python -m pip install" concurrentes ──
# Bug real confirmado por evidencia de dispositivo (docs/humano278.md, ronda 2026-08-28,
# kairos_app.log del dispositivo): mistralvibe.sh falló (exitCode=1, instalación de
# 15:32:02 a 15:42:02) y n8n.sh --variant udocker falló (exitCode=1 a las 15:45:31) mientras
# ciberseguridad.sh corría en paralelo "pip install" real de theHarvester/sqlmap durante esa
# misma ventana (ciberseguridad terminó justo a las 15:45:22, ~9s antes de que n8n fallara) —
# mismo patrón exacto de "escritura concurrente sin lock sobre el mismo site-packages global
# de Termux" que ya se confirmó y arregló para pkg()/npm()/registry_write() arriba (ver esos
# comentarios), nunca cerrado del lado de pip pese a que 9+ módulos de modulos/ instalan con
# pip (ciberseguridad, cactus, hf, mistralvibe, hermes, python, udocker, n8n, entorno).
#
# A diferencia de pkg/npm, la mayoría de estos scripts NO llaman al binario "pip"/"pip3"
# directo — resuelven el intérprete con `command -v python3` a una ruta ABSOLUTA
# ($PIP_PYTHON/_venv_python/etc.) y corren "$PIP_PYTHON" -m pip install — una función bash
# llamada "python3"/"pip" NUNCA se dispara para una invocación por ruta absoluta (solo para
# el nombre "pelado" resuelto por PATH), así que envolver el binario no alcanza acá como sí
# alcanzó para pkg/npm. Se expone en cambio un helper explícito que cada call-site debe usar
# en vez de invocar pip directo — mismo criterio que install_single_pkg()/install_npm_global()
# más abajo (helper compartido en vez de repetir flock+lockfile en cada script).
_KAIROS_PIP_LOCKFILE="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/kairos_pip.lock"

# Wrappers de los binarios "pip"/"pip3" en sí (mismo patrón que pkg()/npm() arriba) — cubren
# los call-sites que SÍ invocan el binario pelado directo (udocker.sh/n8n.sh/entorno.sh:
# "pip3 install udocker"/"pip install udocker"), a diferencia de pip_install() (arriba), que
# hace falta cuando el call-site resuelve el intérprete a una ruta ABSOLUTA
# ("$PIP_PYTHON" -m pip install ...) — una función bash de nombre "pip"/"pip3" nunca se
# dispara para esa forma, solo para el nombre pelado. `command pip`/`command pip3` (no
# `command -v`) bypassea a propósito esta misma función para no recursar.
pip()  { ( flock -w 300 200 || { echo "[ERROR] pip: no se pudo obtener el lock de pip tras 300s (otra instalación lo tiene tomado)" >&2; exit 1; }; command pip "$@" ) 200>"$_KAIROS_PIP_LOCKFILE"; }
pip3() { ( flock -w 300 200 || { echo "[ERROR] pip3: no se pudo obtener el lock de pip tras 300s (otra instalación lo tiene tomado)" >&2; exit 1; }; command pip3 "$@" ) 200>"$_KAIROS_PIP_LOCKFILE"; }

pip_install() {
  local _py="$1"; shift
  (
    flock -w 300 200 || { echo "[ERROR] pip: no se pudo obtener el lock de pip tras 300s (otra instalación lo tiene tomado)" >&2; exit 1; }
    "$_py" -m pip install "$@"
  ) 200>"$_KAIROS_PIP_LOCKFILE"
}

# Wrappers de "python3"/"python" (nombre pelado, ej. cactus.sh/python.sh: "python3 -m pip
# install ...") — solo toman el lock cuando los argumentos son realmente "-m pip ..."
# (_kairos_is_pip_module_call), para no penalizar con una espera innecesaria el resto de usos
# de python3/python de este proyecto (la inmensa mayoría, sin relación con pip). No cubre
# invocaciones por RUTA ABSOLUTA ($PIP_PYTHON/_venv_python resueltos con `command -v`) — esas
# necesitan pip_install() explícito en el call-site, ver comentario de arriba.
_kairos_is_pip_module_call() { [ "$1" = "-m" ] && [ "$2" = "pip" ]; }
python3() {
  if _kairos_is_pip_module_call "$@"; then
    ( flock -w 300 200 || { echo "[ERROR] python3 -m pip: no se pudo obtener el lock de pip tras 300s" >&2; exit 1; }; command python3 "$@" ) 200>"$_KAIROS_PIP_LOCKFILE"
  else
    command python3 "$@"
  fi
}
python() {
  if _kairos_is_pip_module_call "$@"; then
    ( flock -w 300 200 || { echo "[ERROR] python -m pip: no se pudo obtener el lock de pip tras 300s" >&2; exit 1; }; command python "$@" ) 200>"$_KAIROS_PIP_LOCKFILE"
  else
    command python "$@"
  fi
}

# ── Logging (silencioso o con color, según $SILENT) ──────────
if [ "${SILENT:-false}" = "true" ]; then
  log()    { echo "[OK] $1"; }
  warn()   { echo "[WARN] $1"; }
  error()  { echo "[ERROR] $1"; exit 1; }
  info()   { echo "[INFO] $1"; }
  step()   { echo "[STEP] $1"; }
else
  KLIB_RED='\033[0;31m'
  KLIB_GREEN='\033[0;32m'
  KLIB_YELLOW='\033[1;33m'
  KLIB_CYAN='\033[0;36m'
  KLIB_BOLD='\033[1m'
  KLIB_DIM='\033[2m'
  KLIB_NC='\033[0m'
  log()    { echo -e "${KLIB_GREEN}[OK]${KLIB_NC} $1"; }
  warn()   { echo -e "${KLIB_YELLOW}[WARN]${KLIB_NC} $1"; }
  error()  { echo -e "${KLIB_RED}[ERROR]${KLIB_NC} $1"; exit 1; }
  info()   { echo -e "${KLIB_CYAN}[INFO]${KLIB_NC} $1"; }
  step()   { echo -e "${KLIB_CYAN}[STEP]${KLIB_NC} $1"; }
  # Alias con nombres cortos usados por algunos scripts (mantener
  # compatibilidad con echo -e "${RED}..." ya existente en el código
  # module-specific que no se tocó en esta migración)
  RED="$KLIB_RED"; GREEN="$KLIB_GREEN"; YELLOW="$KLIB_YELLOW"
  CYAN="$KLIB_CYAN"; BOLD="$KLIB_BOLD"; DIM="$KLIB_DIM"; NC="$KLIB_NC"
fi

# ── Título de sección (usado por entorno.sh y scripts que aún lo usan) ──
titulo() { echo -e "\n${CYAN:-}${BOLD:-}━━━ $1 ━━━${NC:-}\n"; }

# ── Checkpoints ($CHECKPOINT debe estar seteado antes de sourcear) ──
check_done() { grep -q "^$1$" "$CHECKPOINT" 2>/dev/null; }
mark_done()  { echo "$1" >> "$CHECKPOINT"; }

# ── Registry ($REGISTRY debe estar seteado antes de sourcear) ──────────
# Escribe/actualiza las claves <id>.* del registry (~/.android_server_registry):
# borra las anteriores del mismo prefijo y escribe las dadas. Recibe pares
# key=valor YA prefijados (el id se antepone a cada uno). Unifica las ~24 copias
# (update_registry/_update_reg/_update_registry/update_registry_ssh) que cada
# módulo repetía con el mismo esqueleto — antes del refactor cada una era una
# copia literal de ~10 líneas con solo el prefijo y los campos cambiando.
#
#   Uso:  registry_write <id> "campo=valor" ["campo=valor" ...]
#   Ej:   registry_write ollama "installed=true" "version=$VER" "port=11434"
#
# Atajo para instalación exitosa: registry_install <id> <version> [campo=valor ...]
# escribe installed=true, version, install_date (hoy) + campos extra. Así el
# "skeleton" standard (installed/version/install_date) no se repite en cada script.
# Lock real sobre $REGISTRY — evita perder entradas por escritura concurrente.
# Bug real confirmado por auditoría ADB 2026-08-27 (docs/arquitectura/AUDITORIA_MODULOS_2026-08-27.md,
# sección markserv/psqlformat/ncu/ngrok): registry_write() hacía un read-modify-write
# (grep -v ... > tmp; mv tmp REGISTRY) sin ningún lock, mismo patrón de bug ya arreglado
# arriba para pkg()/npm() con InstallQueueManager.kt corriendo hasta 4 instalaciones a la
# vez. Evidencia real en dispositivo: markserv, psqlformat, ncu y ngrok tenían binarios
# funcionando en PATH (instalación real exitosa, verify_binary_installed() pasó) pero CERO
# entradas en $REGISTRY — perdidas porque otro módulo, instalado en paralelo, leyó el
# registry ANTES de que esta escritura terminara, y su propio mv posterior pisó el archivo
# entero sin la entrada recién agregada (lost update clásico). Mismo mecanismo de flock ya
# usado para pkg()/npm() arriba, aplicado acá al archivo $REGISTRY en vez del lock de apt/npm.
_KAIROS_REGISTRY_LOCKFILE="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/kairos_registry.lock"
registry_write() {
  local _id="$1"; shift
  [ -z "$_id" ] && return 1
  (
    flock -w 300 200 || { echo "[ERROR] registry: no se pudo obtener el lock de $REGISTRY tras 300s" >&2; exit 1; }
    [ ! -f "$REGISTRY" ] && touch "$REGISTRY"
    local _tmp="$REGISTRY.tmp.$$"
    grep -v "^${_id}\." "$REGISTRY" > "$_tmp" 2>/dev/null || touch "$_tmp"
    local _kv
    for _kv in "$@"; do
      printf '%s.%s\n' "$_id" "$_kv" >> "$_tmp"
    done
    mv "$_tmp" "$REGISTRY"
  ) 200>"$_KAIROS_REGISTRY_LOCKFILE"
}

registry_install() {
  local _id="$1" _ver="$2"; shift 2
  registry_write "$_id" \
    "installed=true" \
    "version=$_ver" \
    "install_date=$(date +%Y-%m-%d)" \
    "$@"
}

# ── Eventos módulo→app (bridge sin polling, patrón adoptado de Podroid —
#    ver docs/referencias/REFERENCIA_PODROID.md "Profundización 2026-08-01" punto 3) ──
# Escribe una línea "modulo:evento:detalle" al FIFO $HOME/.kairos_events, que
# ModuleEventBridge.kt (Kotlin) lee bloqueante y despacha a una notificación
# Android real — reemplaza el polling para eventos que el propio script SÍ
# observa directamente (fin de instalación, cierre de una sesión VNC, etc.).
# Silencioso y no bloqueante a propósito: si el FIFO no existe todavía (la app
# nunca abrió, o corre en una versión vieja sin este mecanismo) o nadie lo
# está leyendo del otro lado, `echo` a un FIFO sin lector se bloquearía
# indefinidamente — por eso va con timeout corto vía `timeout` y `|| true`,
# nunca debe poder trabar un script de instalación real.
notify_event() {
  local _module="$1" _event="$2" _detail="${3:-}"
  local _pipe="$HOME/.kairos_events"
  [ -p "$_pipe" ] || return 0
  timeout 1 bash -c "echo '${_module}:${_event}:${_detail}' > '$_pipe'" 2>/dev/null || true
}

# ── Detección de glibc (repetida en antigravity/claude/kairos/openclaw/opencode) ──
detect_glibc() {
  local _prefix="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
  [ -f "${_prefix}/glibc/lib/ld-linux-aarch64.so.1" ] || \
    command -v glibc-runner &>/dev/null || \
    pkg list-installed 2>/dev/null | grep -q "^glibc/"
}

# ── pkg update con selección de mirror por velocidad real ─────
# Quick win de la auditoría de referencia/ (2026-08-05, ver docs/humano70.md) —
# entorno.sh ya tenía esta lógica (5 mirrors + medición real de velocidad vía curl, idea
# de sabamdarif/termux-desktop, auditoría de ver/ 2026-07-28) pero kairos.sh/ollama.sh/
# n8n.sh seguían con "probar 2 mirrors fijos en orden", más lento y menos confiable.
# Centralizada acá para que los 4 scripts compartan la misma lógica en vez de 4 copias
# ligeramente distintas.
_KAIROS_MIRRORS=(
  "https://packages.termux.dev/apt/termux-main"
  "https://grimler.se/termux/termux-main"
  "https://mirror.accum.se/mirror/termux.dev/apt/termux-main"
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
  "https://mirrors.sjtug.sjtu.edu.cn/termux/apt/termux-main"
)

_set_mirror() {
  echo "deb $1 stable main" > "${TERMUX_PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"
  info "Mirror: $1"
}

# Mide el tiempo de respuesta real de cada mirror candidato y devuelve el más rápido —
# reemplaza el "primero que funcione en orden fijo" por una elección basada en la red
# real del dispositivo en ese momento.
#
# Bug real confirmado por lectura de código (auditoría 2026-08-27, ver
# docs/arquitectura/AUDITORIA_MODULOS_2026-08-27.md): esta función medía SOLO el tiempo
# de respuesta (curl -w '%{time_total}') sin chequear el código HTTP — un mirror caído
# que responde rápido con 404/redirect/página de error (conexión instantánea, sin
# transferencia real de contenido) podía ganar la carrera de "más rápido" y quedar
# seleccionado en sources.list, para recién fallar después en el pkg_update_with_fallback()
# de arriba. Coincide con el síntoma reportado ("todos los mirrors fallaron" en varias
# corridas): el mirror "ganador" nunca era realmente válido, así que el pkg update de
# verificación posterior fallaba también, y el loop de fallback terminaba agotando los 5
# sin encontrar ninguno funcional aunque alguno sí lo era. Se agrega el chequeo de
# %{http_code}==200 para descartar mirrors que solo responden rápido sin servir contenido
# real. (No cubre el caso de un mirror real pero a mitad de sincronización — ahí InRelease
# sí responde 200 rápido con metadata desactualizada; eso es una condición transitoria del
# servidor, no un bug del método de medición — pkg_update_with_fallback ya reintenta con
# los otros mirrors candidatos para ese caso.)
_fastest_mirror() {
  local best="" best_time="999" m result code t
  for m in "${_KAIROS_MIRRORS[@]}"; do
    result=$(curl -s -o /dev/null -m 8 -w '%{http_code} %{time_total}' "$m/dists/stable/InRelease" 2>/dev/null)
    code="${result%% *}"; t="${result#* }"
    [ "$code" = "200" ] || continue
    [ -z "$t" ] && continue
    if awk -v a="$t" -v b="$best_time" 'BEGIN{exit !(a<b)}'; then
      best_time="$t"; best="$m"
    fi
  done
  echo "$best"
}

pkg_update_with_fallback() {
  local out
  out=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
  if echo "$out" | grep -q "unexpected size\|Mirror sync in progress\|Err:2\|No mirror or mirror group selected"; then
    # Bug real reportado (2026-08-22, ver docs/humano/humano200.md): scripts como entorno.sh llaman a
    # esta función más de una vez en la MISMA corrida — sin este flag, cada llamada repetía la
    # ronda completa de probar los 5 mirrors candidatos (~8s de timeout cada uno) aunque la
    # anterior ya hubiera confirmado que TODOS fallan, sumando ~50-60s tirados por cada llamada
    # extra. El wizard corre entorno.sh con 2 llamadas a esta función — eran ~2 minutos perdidos
    # solo en reintentos inútiles durante el primer arranque. El flag es por proceso (no
    # persiste entre corridas de script distintas) — si la red se recupera, la siguiente
    # ejecución del script vuelve a probar normal.
    if [ "${_KAIROS_MIRROR_ALL_FAILED:-0}" = "1" ]; then
      warn "Mirror sigue roto (ya se probaron los ${#_KAIROS_MIRRORS[@]} candidatos antes en esta misma corrida) — sin reintentar, continuando con el índice actual"
      return
    fi
    warn "Mirror roto — midiendo velocidad real de ${#_KAIROS_MIRRORS[@]} mirrors candidatos..."
    local fastest; fastest=$(_fastest_mirror)
    local ok=0
    if [ -n "$fastest" ]; then
      _set_mirror "$fastest"
      out=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
      if ! echo "$out" | grep -q "unexpected size\|Mirror sync in progress\|Err:2\|No mirror or mirror group selected"; then
        log "Mirror más rápido OK: $fastest"; ok=1
      fi
    fi
    if [ "$ok" = "0" ]; then
      local m
      for m in "${_KAIROS_MIRRORS[@]}"; do
        [ "$m" = "$fastest" ] && continue
        _set_mirror "$m"
        out=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
        if ! echo "$out" | grep -q "unexpected size\|Mirror sync in progress\|Err:2\|No mirror or mirror group selected"; then
          log "Mirror OK: $m"; ok=1; break
        fi
      done
    fi
    if [ "$ok" = "0" ]; then
      warn "Todos los mirrors fallaron — continuando con el índice actual (puede haber errores de instalación)"
      _KAIROS_MIRROR_ALL_FAILED=1
    fi
  fi
}

# ── Config persistente del usuario (patrón pkg2conf de i-Haklab) ──
# Adoptado del hallazgo #3 de docs/referencias/AUDITORIA_CATEGORIA_CIBERSEGURIDAD.md
# (patrón `pkg2conf` de referencia/ciberseguridad/i-Haklab-master): crea un config
# default SOLO si no existe, y expone la ruta del config que el usuario puede haber
# persistido — evita perder la config del usuario en reinstalaciones de módulos que
# tengan config editable (ej. re-instalar el módulo no pisa el archivo ya editado).
#   Uso: ensure_persisted_config <tool> <default_url> <dest_path>
ensure_persisted_config() {
  local tool="$1" default_url="$2" dest="$3"
  if [ ! -f "$dest" ]; then
    mkdir -p "$(dirname "$dest")"
    if [ -n "$default_url" ]; then
      curl -fsSL "$default_url" -o "$dest" 2>/dev/null || printf '# config default %s\n' "$tool" > "$dest"
    else
      printf '# config default %s\n' "$tool" > "$dest"
    fi
  fi
  echo "$dest"
}

# ── "Paquetes adicionales" genéricos (lenguajes/runtimes vía pkg, herramientas
#    de dev vía npm) — patrón confirmado en referencia/termux/core-termux-main/
#    core/tools/lang/*/install.sh y core/tools/npm/*/install.sh: cada uno es
#    "chequear si ya está" → "pkg install <paquete>" o "npm install -g <paquete>"
#    → chequear que el comando exista. Estos dos helpers evitan repetir ese
#    esqueleto (~40 líneas) en cada módulo de una sola herramienta. Requieren
#    $FORCE ya seteado por el script (parseo de flags propio, igual que el resto
#    de modulos/*.sh) y lib.sh ya sourceado (usan log/info/error/registry_write/
#    registry_install/pkg_update_with_fallback).

# verify_binary_installed <comando> [flag_de_version]
# Chequeo de POST-CONDICIÓN real (no solo exit code) — patrón encontrado auditando
# referencia/termux/termux-desktop43-main/distro-container-setup (2026-08-22, ver
# docs/humano/humano194.md): ese proyecto nunca confía en que "npm install"/"pkg install" haya
# terminado sin error para declarar éxito — verifica en el filesystem/ejecutando el binario
# real antes de escribir el registry. Kairos tuvo 3 bugs reales exactamente por NO hacer esto
# (auditoría ADB 2026-08-22): #28 codegraph (directorio doble-anidado, MODULE_NOT_FOUND pese a
# exit 0), #29 copilotcli (falta paquete nativo de plataforma, wrapper existe pero no corre),
# #30 expo/eas ("ya instalado" pero el binario no existía en disco). Usar en vez de un
# "command -v" a secas cuando un script termina su instalación — command -v solo confirma que
# el ARCHIVO existe y es ejecutable, no que funcione de verdad.
verify_binary_installed() {
  local _bin="$1" _flag="${2:---version}"
  command -v "$_bin" &>/dev/null || return 1
  "$_bin" "$_flag" &>/dev/null
}

# install_single_pkg <id> <check_cmd> <paquete_pkg> [paquete_pkg2 ...]
# Instala uno o más paquetes de Termux (pkg) para una sola herramienta. Ej.:
#   install_single_pkg "rust" "rustc" rust
install_single_pkg() {
  local _id="$1" _check="$2"; shift 2
  if command -v "$_check" &>/dev/null && [ "${FORCE:-false}" != "true" ]; then
    log "$_id ya instalado"
    registry_write "$_id" "installed=true"
    return 0
  fi
  pkg_update_with_fallback
  if ! pkg install -y "$@" &>/dev/null; then
    error "No se pudo instalar $_id (pkg install $* falló)"
  fi
  # Bug real evitado (2026-08-22, ver docs/humano/humano201.md): retrofit de verify_binary_installed()
  # a install_single_pkg() — cubre de una sola vez clang/golang/nodejs/perl/php/rust, los 6
  # módulos que llaman a este helper. Mismo criterio ya documentado arriba (command -v solo
  # confirma que el archivo existe, no que el binario corre de verdad).
  verify_binary_installed "$_check" || error "$_id no disponible tras la instalación"
  registry_install "$_id" "$("$_check" --version 2>&1 | head -1)"
  log "$_id instalado"
}

# ensure_node_installed — dependencia compartida de todas las herramientas npm
# de abajo (typescript, prettier, vercel, ngrok, ...). Node.js puede ya estar
# instalado por otro módulo (n8n, etc.) — idempotente, no reinstala si ya está.
ensure_node_installed() {
  if command -v node &>/dev/null && command -v npm &>/dev/null; then
    return 0
  fi
  info "Node.js no está instalado — instalando nodejs-lts primero (dependencia)..."
  pkg_update_with_fallback
  pkg install -y nodejs-lts &>/dev/null || error "No se pudo instalar Node.js (dependencia de este paquete)"
  command -v node &>/dev/null || error "Node.js no disponible tras instalar nodejs-lts"
}

# install_npm_global <id> <npm_pkg> <check_cmd> [paquete_pkg_extra ...]
# Instala una herramienta npm global, asegurando Node.js primero. Los
# paquetes pkg extra (ej. "perl" para psqlformat) se instalan ANTES del
# npm install -g. Ej.:
#   install_npm_global "typescript" "typescript" "tsc"
#   install_npm_global "psqlformat" "psqlformat" "psqlformat" perl
# fix_npm_shebang_wrapper <check_cmd> <npm_pkg>
# Bug real confirmado en dispositivo (auditoría ADB 2026-08-21, ver docs/humano/humano183.md y
# docs/humano/humano184.md): el symlink que "npm install -g" genera para el binario final (shebang
# "#!/usr/bin/env node") no se puede ejecutar directamente en este dispositivo/Android —
# probable restricción W^X sobre archivos escritos en runtime fuera del $PREFIX normal de
# Termux ("timeout: failed to run command '.../<bin>': No such file or directory" pese a que
# el archivo existe). Confirmado en vivo con OpenClaw y Cursor CLI. Esto también explica el
# patrón "version=?/unknown" ya documentado en varios módulos npm-CLI (golang, expo, qwencode,
# minimaxcli, kimi, copilotcli, n8n) — el "$_check --version" que arma esa versión falla en
# silencio por el mismo motivo. Fix: reemplazar el symlink por un wrapper bash real que invoca
# el node de Termux de forma explícita, resolviendo el entrypoint real desde el package.json
# del paquete (no asume ningún nombre de archivo fijo, puede variar entre versiones upstream).
# No-op seguro si el comando no existe, no es un symlink, o no tiene shebang de node — para
# poder llamarse siempre después de un "npm install -g" sin chequeos previos en el caller.
fix_npm_shebang_wrapper() {
  local _check="$1" _npm_pkg="$2"
  local _prefix="${TERMUX_PREFIX:-${PREFIX:-/data/data/com.termux/files/usr}}"
  local _link; _link=$(command -v "$_check" 2>/dev/null) || return 0
  [ -L "$_link" ] || return 0
  head -1 "$_link" 2>/dev/null | grep -q "node" || return 0
  local _target; _target=$(readlink -f "$_link" 2>/dev/null) || return 0
  local _pkg_dir; _pkg_dir="$(npm root -g 2>/dev/null)/$_npm_pkg"
  [ -d "$_pkg_dir" ] || _pkg_dir=$(dirname "$_target")
  local _entry_rel
  _entry_rel=$(node -e "try{const p=require('$_pkg_dir/package.json');const b=p.bin;const v=typeof b==='string'?b:(b&&(b['$_check']||Object.values(b)[0]));console.log(v||'')}catch(e){}" 2>/dev/null) || true
  local _entry="$_target"
  [ -n "$_entry_rel" ] && [ -f "$_pkg_dir/$_entry_rel" ] && _entry="$_pkg_dir/$_entry_rel"
  rm -f "$_link"
  cat > "$_link" << WRAPPER
#!$_prefix/bin/bash
exec "$_prefix/bin/node" "$_entry" "\$@"
WRAPPER
  chmod +x "$_link"
  log "Wrapper bash real aplicado a $_check (el symlink de npm no ejecuta directo en este dispositivo)"
}

install_npm_global() {
  local _id="$1" _npm_pkg="$2" _check="$3"; shift 3
  if command -v "$_check" &>/dev/null && [ "${FORCE:-false}" != "true" ]; then
    log "$_id ya instalado"
    registry_write "$_id" "installed=true"
    return 0
  fi
  ensure_node_installed
  if [ $# -gt 0 ]; then
    pkg_update_with_fallback
    pkg install -y "$@" &>/dev/null || error "No se pudieron instalar dependencias de $_id ($*)"
  fi
  if ! npm install -g "$_npm_pkg" &>/dev/null; then
    error "No se pudo instalar $_id (npm install -g $_npm_pkg falló)"
  fi
  command -v "$_check" &>/dev/null || error "$_id no disponible tras la instalación (npm)"
  fix_npm_shebang_wrapper "$_check" "$_npm_pkg"
  # Bug real evitado (2026-08-22, ver docs/humano/humano201.md): retrofit de verify_binary_installed()
  # a install_npm_global() — cubre de una sola vez livesrv/localtunnel/markserv/ncu/nestjs/
  # ngrok/prettier/psqlformat/typescript/vercel. Corre DESPUÉS de fix_npm_shebang_wrapper (no
  # antes) — antes del wrapper el symlink puede existir en PATH pero no ejecutar todavía (el
  # bug real que motivó fix_npm_shebang_wrapper), así que chequear ejecución ahí habría sido un
  # falso negativo.
  verify_binary_installed "$_check" || error "$_id no ejecuta tras la instalación (revisá manualmente: $_check --version)"
  registry_install "$_id" "$(npm ls -g "$_npm_pkg" --depth=0 2>/dev/null | sed -nE "s#.*${_npm_pkg}@([0-9.A-Za-z-]+).*#\\1#p")"
  log "$_id instalado"
}
