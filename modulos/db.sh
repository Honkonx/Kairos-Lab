#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · db.sh (silent mode)
#  Módulo Base de Datos: MySQL/MariaDB + PostgreSQL + SQLite
#
#  USO DESDE APP (KairosApp):
#    bash db.sh --silent
#    bash db.sh --status
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#    --status   Modo estado: imprime JSON y sale
#    --describe Describe lo que hace el script
#    --uninstall Desinstala (borra registry)
#    --start    Inicia los servidores de BD
#    --stop     Detiene los servidores de BD
#
#  QUÉ INSTALA (en orden):
#    ✅ pkg update (si hace falta)
#    ✅ mariadb (MySQL/MariaDB) + inicializa datadir si es necesario
#    ✅ postgresql (psql) + inicializa datadir si es necesario
#    ✅ SQLite CLI (sqlite3) — suele venir con el wizard, se garantiza
#    ✅ redis (paquete oficial de Termux, main repo, aarch64) + wrappers
#       start/stop propios — agregado v1.1.0, ver NOTA abajo
#    ⚠️ mongodb (best-effort, paquete probablemente INEXISTENTE — ver NOTA
#       2026-09-15 abajo) + wrappers start/stop propios — agregado v1.2.0
#    ✅ Registry actualizado (db.installed, db.version, ...)
#
#  NOTA (2026-08-19, cruce contra referencia/termux/core-termux-main/core/cli/
#  commands/list.sh::_list_db): ese proyecto de referencia expone 5 motores
#  (PostgreSQL, MariaDB, SQLite, MongoDB, Redis) contra los 3 que este módulo
#  cubría. Redis es un paquete real y oficial del repo main de Termux
#  (aarch64) — se agrega acá con el mismo patrón start/stop que MariaDB/
#  PostgreSQL. MongoDB en su momento se dejó pendiente (creído sin build
#  oficial ARM64) — la nota anterior de 2026-09-01 decía "confirmado en vivo
#  que 'pkg install mongodb' instala un binario real", pero esa afirmación NO
#  se pudo reproducir en la auditoría 2026-09-15 (verificación empírica
#  directa, no solo confiar en un reporte previo): el paquete "mongodb"
#  no existe en packages.termux.dev/apt/termux-main (pool/main/m/mongodb/ →
#  404) ni en termux-user-repository/tur (sin ningún paquete "mongo*") —
#  "pkg install mongodb" falla con "Unable to locate package" en el repo main
#  actual. Se deja el código tal cual (PASO 5 ya trata el fallo como
#  no-crítico con warn(), y update_registry() ya NO marca mongo.installed=true
#  a menos que "mongod" exista de verdad tras el intento — fix real de esta
#  ronda) en vez de eliminarlo, por si Termux agrega el paquete en el futuro o
#  el usuario confirma otro mirror/repo que sí lo tenga — pendiente de
#  verificación empírica real en dispositivo antes de confiar en cualquier
#  reporte futuro de "ya funciona".
#
#  OUTPUT (modo --silent):
#    [STEP] N/7 Descripción     ← para barra de progreso
#    [OK] mensaje                ← paso completado
#    [ERROR] mensaje             ← fallo (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 1.2.1 | Septiembre 2026 (v1.2.0: agrega MongoDB, ver NOTA arriba. v1.2.1: fix real
#  del wrapper $HOME/scripts/db/start.sh — le faltaba un "exit 0" final, así que su código de
#  salida terminaba siendo el de mongo_start.sh nada más, no un resultado agregado de los 4
#  motores; confirmado en vivo por ADB que esto hacía fallar el switch de la app aunque
#  MySQL/PostgreSQL/Redis arrancaran bien, ver comentario junto al heredoc de start.sh abajo)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Config ──────────────────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_db_checkpoint"
DB_SCRIPTS="$HOME/scripts/db"
MYSQL_DATA="$TERMUX_PREFIX/var/lib/mysql"
PGSQL_DATA="$TERMUX_PREFIX/var/lib/postgresql"
REDIS_DATA="$TERMUX_PREFIX/var/lib/redis"
MONGO_DATA="$TERMUX_PREFIX/var/lib/mongodb"

# ── Parsear flags ───────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
STATUS=false
UNINSTALL=false
START=false
STOP=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --status)   STATUS=true ;;
    --uninstall) UNINSTALL=true ;;
    --start)    START=true ;;
    --stop)     STOP=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ─────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"db","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Los 4 motores (MariaDB/
# PostgreSQL/Redis/SQLite) son paquetes apt completos — mismo criterio que
# python.sh/clang.sh: no se empaquetan (files:[] para esa parte, ver
# not_covered). Lo propio de Kairos es DB_SCRIPTS ($HOME/scripts/db/*.sh,
# generados por este script) — esos sí se empaquetan vía file_globs.
if $DESCRIBE_FILES; then
  jq -n \
    --arg glob "$HOME/scripts/db/**" \
    --arg verify "command -v sqlite3 >/dev/null 2>&1" \
    '{
      id: "db",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-db",
      version_registry_key: "db.version",
      files: [],
      file_globs: [{pattern: $glob, required: true, note: "scripts de control generados (mysql_start/stop.sh, postgres_start/stop.sh, redis_start/stop.sh, mongo_start/stop.sh, start.sh, stop.sh)"}],
      dependencies: [
        {id: "pkg:mariadb", check_cmd: "command -v mariadbd >/dev/null 2>&1", install_hint: "pkg install -y mariadb"},
        {id: "pkg:postgresql", check_cmd: "command -v postgres >/dev/null 2>&1", install_hint: "pkg install -y postgresql"},
        {id: "pkg:redis", check_cmd: "command -v redis-server >/dev/null 2>&1", install_hint: "pkg install -y redis"},
        {id: "pkg:sqlite", check_cmd: "command -v sqlite3 >/dev/null 2>&1", install_hint: "pkg install -y sqlite"},
        {id: "pkg:mongodb", check_cmd: "command -v mongod >/dev/null 2>&1", install_hint: "pkg install -y mongodb"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "MariaDB/PostgreSQL/Redis/MongoDB son paquetes apt completos — cientos de archivos ya gestionados por pkg, no se snapshotean",
        "Los datadirs ($TERMUX_PREFIX/var/lib/{mysql,postgresql,redis,mongodb}) son datos de usuario, nunca se empaquetan"
      ]
    }'
  exit 0
fi

# ── Cargar librería compartida ──────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh" 2>/dev/null || {
  echo "Error: lib.sh no encontrado"
  exit 1
}

# ── Modo estado ─────────────────────────────────────────────
if $STATUS; then
  MYSQL_RUNNING=false; PGSQL_RUNNING=false
  # Bug real confirmado por auditoría ADB en dispositivo real:
  # "pgrep -x" compara contra el nombre corto del proceso (comm), poco confiable en este
  # Android/Termux — MariaDB y Redis arrancan y funcionan perfecto pero el chequeo reportaba
  # [ERROR] igual. Además el binario real de MariaDB se llama "mariadbd", no "mysqld". Fix:
  # "pgrep -f" (matchea la línea de comando completa) + nombre real del binario.
  pgrep -f mariadbd &>/dev/null && MYSQL_RUNNING=true
  # Bug real: "pgrep -f postgres" da falso positivo con un subproceso
  # "postgres --check" colgado (de pg_ctl) — pg_isready no se deja engañar, hace una conexión real.
  pg_isready -q 2>/dev/null && PGSQL_RUNNING=true
  REDIS_RUNNING=false
  pgrep -f redis-server &>/dev/null && REDIS_RUNNING=true
  MONGO_RUNNING=false
  pgrep -f mongod &>/dev/null && MONGO_RUNNING=true
  MYSQL_VER=$(mariadbd --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+\.[0-9]+)' | head -1)
  PGSQL_VER=$(psql --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+)' | head -1)
  SQLITE_VER=$(sqlite3 --version 2>/dev/null | awk '{print $1}')
  REDIS_VER=$(redis-server --version 2>/dev/null | grep -oE 'v=[0-9]+\.[0-9]+\.[0-9]+' | head -1 | cut -d= -f2)
  MONGO_VER=$(mongod --version 2>/dev/null | grep -oE 'db version v[0-9]+\.[0-9]+\.[0-9]+' | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  cat << EOF
{"ok":true,"mysql":{"installed":$([ -n "$MYSQL_VER" ] && echo true || echo false),"running":$MYSQL_RUNNING,"version":"$MYSQL_VER"},"postgres":{"installed":$([ -n "$PGSQL_VER" ] && echo true || echo false),"running":$PGSQL_RUNNING,"version":"$PGSQL_VER"},"sqlite":{"installed":$([ -n "$SQLITE_VER" ] && echo true || echo false),"version":"$SQLITE_VER"},"redis":{"installed":$([ -n "$REDIS_VER" ] && echo true || echo false),"running":$REDIS_RUNNING,"version":"$REDIS_VER"},"mongo":{"installed":$([ -n "$MONGO_VER" ] && echo true || echo false),"running":$MONGO_RUNNING,"version":"$MONGO_VER"}}
EOF
  exit 0
fi

# ── Uninstall ───────────────────────────────────────────────
if $UNINSTALL; then
  pkill -f mariadbd 2>/dev/null; pkill -f postgres 2>/dev/null; pkill -f redis-server 2>/dev/null; pkill -f mongod 2>/dev/null
  rm -f "$REGISTRY.tmp"
  [ -f "$REGISTRY" ] && grep -v "^db\.\|^mysql\.\|^postgres\.\|^sqlite\.\|^redis\.\|^mongo\." "$REGISTRY" > "$REGISTRY.tmp"
  mv "$REGISTRY.tmp" "$REGISTRY"
  rm -rf "$DB_SCRIPTS"
  rm -f "$CHECKPOINT"
  echo "[OK] db desinstalado (paquetes mariadb/postgresql siguen instalados en el sistema)"
  exit 0
fi

# ── Start / Stop ────────────────────────────────────────────
if $START || $STOP; then
  DB_MYSQL="$DB_SCRIPTS/mysql_start.sh"; DB_MYSQL_STOP="$DB_SCRIPTS/mysql_stop.sh"
  DB_PG="$DB_SCRIPTS/postgres_start.sh"; DB_PG_STOP="$DB_SCRIPTS/postgres_stop.sh"
  DB_REDIS="$DB_SCRIPTS/redis_start.sh"; DB_REDIS_STOP="$DB_SCRIPTS/redis_stop.sh"
  DB_MONGO="$DB_SCRIPTS/mongo_start.sh"; DB_MONGO_STOP="$DB_SCRIPTS/mongo_stop.sh"
  if $START; then
    [ -f "$DB_MYSQL" ] && bash "$DB_MYSQL" 2>/dev/null || warn "MySQL: script no disponible (instala el módulo db)"
    [ -f "$DB_PG" ] && bash "$DB_PG" 2>/dev/null || warn "PostgreSQL: script no disponible (instala el módulo db)"
    [ -f "$DB_REDIS" ] && bash "$DB_REDIS" 2>/dev/null || warn "Redis: script no disponible (instala el módulo db)"
    [ -f "$DB_MONGO" ] && bash "$DB_MONGO" 2>/dev/null || warn "MongoDB: script no disponible (instala el módulo db)"
  else
    [ -f "$DB_MYSQL_STOP" ] && bash "$DB_MYSQL_STOP" 2>/dev/null
    [ -f "$DB_PG_STOP" ] && bash "$DB_PG_STOP" 2>/dev/null
    [ -f "$DB_REDIS_STOP" ] && bash "$DB_REDIS_STOP" 2>/dev/null
    [ -f "$DB_MONGO_STOP" ] && bash "$DB_MONGO_STOP" 2>/dev/null
  fi
  echo "[OK] $([ $START ] && echo 'Servidores iniciados' || echo 'Servidores detenidos')"
  exit 0
fi

# ── Registry helper ─────────────────────────────────────────
# Bug real corregido (auditoría 2026-09-15): las 5 líneas de abajo escribían
# "installed=true" de forma INCONDICIONAL para cada motor, sin chequear si el
# binario correspondiente existe de verdad — mismo patrón de bug ya documentado
# en otros casos conocidos (#28/#29/#30). Esto es
# especialmente grave para MongoDB: se confirmó por auditoría de
# packages.termux.dev/pool/main/m/ (404) y del repo termux-user-repository/tur
# (sin ningún paquete "mongo*") que el paquete "mongodb" del comentario de
# cabecera de este archivo ("paquete oficial de Termux, main repo, aarch64",
# "confirmado en vivo 2026-09-01") NO existe en ningún repo real accesible por
# pkg/apt — "pkg install mongodb" falla con "Unable to locate package" (ya
# tratado como no-crítico en PASO 5 de abajo), pero como esta función se llama
# igual al final del script, "mongo.installed=true" quedaba escrito en el
# registry con version=unknown aunque mongod nunca se haya instalado en
# absoluto. Ahora cada motor solo se marca instalado si su binario responde de
# verdad — mismo criterio que el propio modo --status de este script ya usa
# (ver JSON de arriba, $([ -n "$X_VER" ] && echo true || echo false)).
update_registry() {
  local version="$1"
  registry_write db "installed=true" "version=${version}" "install_date=$(date +%Y-%m-%d)"
  local _v
  _v=$(mariadbd --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+\.[0-9]+)' | head -1)
  registry_write mysql "installed=$([ -n "$_v" ] && echo true || echo false)" "version=${_v:-unknown}"
  _v=$(psql --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+)' | head -1)
  registry_write postgres "installed=$([ -n "$_v" ] && echo true || echo false)" "version=${_v:-unknown}"
  _v=$(sqlite3 --version 2>/dev/null | awk '{print $1}')
  registry_write sqlite "installed=$([ -n "$_v" ] && echo true || echo false)" "version=${_v:-unknown}"
  _v=$(redis-server --version 2>/dev/null | grep -oE 'v=[0-9]+\.[0-9]+\.[0-9]+' | head -1 | cut -d= -f2)
  registry_write redis "installed=$([ -n "$_v" ] && echo true || echo false)" "version=${_v:-unknown}"
  _v=$(mongod --version 2>/dev/null | grep -oE 'db version v[0-9]+\.[0-9]+\.[0-9]+' | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  registry_write mongo "installed=$([ -n "$_v" ] && echo true || echo false)" "version=${_v:-unknown}"
}

# ── Verificar si ya está instalado ──────────────────────────
# Nota: se exige también redis-server/mongod acá (no solo mariadb+postgres) para que
# una instalación previa a v1.1.0/v1.2.0 (sin Redis/MongoDB) reciba el paso nuevo la
# próxima vez que se corra el módulo, en vez de quedar salteada por el checkpoint.
DB_CONFIGURED=false
command -v mariadbd &>/dev/null && command -v postgres &>/dev/null && command -v redis-server &>/dev/null && command -v mongod &>/dev/null && DB_CONFIGURED=true

if $DB_CONFIGURED && ! $FORCE; then
  log "db ya instalado (MariaDB + PostgreSQL + SQLite)"
  DB_VER="db-ok"
  update_registry "$DB_VER"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   kairos-app · Base de Datos Installer       ║
  ║   MariaDB + PostgreSQL + SQLite + Redis      ║
  ║   + MongoDB                                  ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instalará:"
  echo "  ▸ MariaDB (MySQL) con datadir en $MYSQL_DATA"
  echo "  ▸ PostgreSQL con datadir en $PGSQL_DATA"
  echo "  ▸ SQLite CLI (sqlite3)"
  echo "  ▸ Redis con datadir en $REDIS_DATA"
  echo "  ▸ MongoDB con datadir en $MONGO_DATA"
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=7

# ============================================================
# PASO 1 — Termux update (solo standalone)
# ============================================================
step "1/$TOTAL_STEPS Verificando Termux"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado por kairos.sh [skip]"
elif check_done "db_termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  # Bug real (auditoría 2026-08-27): este PASO tenía su propio fallback de mirror
  # inline (2 candidatos fijos, sin medir velocidad real) en vez de usar
  # pkg_update_with_fallback() de lib.sh — los PASOs 2-5 de este mismo script ya la
  # usan (5 mirrors + selección por velocidad real + flag de "todos fallaron" para
  # no repetir la ronda completa en cada llamada). Se alinea PASO 1 con el resto del
  # script y con el patrón compartido del resto de modulos/*.sh.
  pkg_update_with_fallback
  log "Termux actualizado"
  mark_done "db_termux_update"
fi

# ============================================================
# PASO 2 — MariaDB (MySQL)
# ============================================================
step "2/$TOTAL_STEPS Instalando MariaDB"

if check_done "db_mariadb"; then
  log "MariaDB ya instalado [checkpoint]"
else
  if ! command -v mariadbd &>/dev/null; then
    info "Instalando mariadb..."
    # Bug real, mismo patrón que el bug ya encontrado en el módulo de VNC.
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" mariadb || \
      error "mariadb no se pudo instalar"
  fi
  command -v mariadbd &>/dev/null && log "MariaDB ✓" || error "mariadbd no quedó disponible"
  mark_done "db_mariadb"
fi

# ============================================================
# PASO 3 — PostgreSQL
# ============================================================
step "3/$TOTAL_STEPS Instalando PostgreSQL"

if check_done "db_postgres"; then
  log "PostgreSQL ya instalado [checkpoint]"
else
  if ! command -v postgres &>/dev/null; then
    info "Instalando postgresql..."
    # Bug real, mismo patrón que el bug ya encontrado en el módulo de VNC.
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" postgresql || \
      error "postgresql no se pudo instalar"
  fi
  command -v postgres &>/dev/null && log "PostgreSQL ✓" || error "postgres no quedó disponible"
  mark_done "db_postgres"
fi

# ============================================================
# PASO 4 — Redis
# ============================================================
# Agregado v1.1.0 (cruce contra referencia/termux/core-termux-main/, ver
# NOTA en el header) — paquete oficial "redis" del repo main de Termux.
step "4/$TOTAL_STEPS Instalando Redis"

if check_done "db_redis"; then
  log "Redis ya instalado [checkpoint]"
else
  if ! command -v redis-server &>/dev/null; then
    info "Instalando redis..."
    # Bug real, mismo patrón que el bug ya encontrado en el módulo de VNC.
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" redis || \
      warn "redis no se pudo instalar (no crítico — MariaDB/PostgreSQL/SQLite siguen disponibles)"
  fi
  command -v redis-server &>/dev/null && log "Redis ✓" || warn "redis-server no quedó disponible"
  mark_done "db_redis"
fi

# ============================================================
# PASO 5 — MongoDB
# ============================================================
# Agregado v1.2.0 (gap confirmado en vivo 2026-09-01, mongod --version funciona en
# Termux vía core-termux-main, ver NOTA en el header) — paquete oficial "mongodb"
# del repo main de Termux, mismo patrón que Redis (v1.1.0).
step "5/$TOTAL_STEPS Instalando MongoDB"

if check_done "db_mongo"; then
  log "MongoDB ya instalado [checkpoint]"
else
  if ! command -v mongod &>/dev/null; then
    info "Instalando mongodb..."
    # Bug real, mismo patrón que el bug ya encontrado en el módulo de VNC.
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" mongodb || \
      warn "mongodb no se pudo instalar (no crítico — MariaDB/PostgreSQL/SQLite/Redis siguen disponibles)"
  fi
  command -v mongod &>/dev/null && log "MongoDB ✓" || warn "mongod no quedó disponible"
  mark_done "db_mongo"
fi

# ============================================================
# PASO 6 — SQLite + scripts de control
# ============================================================
step "6/$TOTAL_STEPS Garantizando SQLite y scripts"

if ! check_done "db_sqlite_scripts"; then
  # Bug real, mismo patrón que el bug ya encontrado en el módulo de VNC.
  command -v sqlite3 &>/dev/null || { pkg_update_with_fallback; pkg install -y sqlite; } || warn "sqlite3 no instalado"
  command -v sqlite3 &>/dev/null && log "SQLite ✓" || warn "sqlite3 no disponible"

  mkdir -p "$DB_SCRIPTS"

  cat > "$DB_SCRIPTS/mysql_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
MYSQL_DATA="$TERMUX_PREFIX/var/lib/mysql"
# Bug real confirmado por auditoría ADB en dispositivo real: "pgrep -x"
# no es confiable en este Android/Termux y el binario real de MariaDB es "mariadbd", no
# "mysqld" — MariaDB arrancaba perfecto pero el chequeo reportaba [ERROR] igual.
if pgrep -f mariadbd &>/dev/null; then
  echo "[OK] MySQL ya corriendo → mysql -u root"
  exit 0
fi
# Primera corrida: inicializar datadir si no existe
if [ ! -d "$MYSQL_DATA/mysql" ]; then
  mkdir -p "$MYSQL_DATA"
  mariadb-install-db --datadir="$MYSQL_DATA" --auth-root-authentication-method=normal &>/dev/null || \
    mariadb-install-db --datadir="$MYSQL_DATA" &>/dev/null
fi
mariadbd --datadir="$MYSQL_DATA" --skip-grant-tables=false &>/dev/null &
sleep 3
if pgrep -f mariadbd &>/dev/null; then
  echo "[OK] MySQL iniciado → mysql -u root"
else
  echo "[ERROR] No se pudo iniciar MySQL"
  exit 1
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/mysql_start.sh"

  cat > "$DB_SCRIPTS/mysql_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
if pgrep -f mariadbd &>/dev/null; then
  pkill -f mariadbd 2>/dev/null; sleep 2
  pgrep -f mariadbd &>/dev/null && echo "[ERROR] No se pudo detener MySQL" || echo "[OK] MySQL detenido"
else
  echo "[OK] MySQL no estaba corriendo"
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/mysql_stop.sh"

  cat > "$DB_SCRIPTS/postgres_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
PGSQL_DATA="$TERMUX_PREFIX/var/lib/postgresql"
# Bug real arreglado (auditoría ADB en dispositivo real): "pgrep -f postgres"
# (fix anterior para el bug #15 de MySQL/Redis) da FALSO POSITIVO acá — "pg_ctl start"
# invoca internamente "postgres --check" como paso de validación, y ese subproceso puede
# quedar colgado en este dispositivo sin que el servidor real llegue a arrancar nunca;
# "pgrep -f postgres" matchea igual ese subproceso colgado y reporta "corriendo" cuando en
# realidad PostgreSQL nunca escucha conexiones. "pg_isready" hace un intento de conexión
# real en vez de mirar procesos — no se deja engañar por un subproceso colgado.
if pg_isready -q 2>/dev/null; then
  echo "[OK] PostgreSQL ya corriendo → psql"
  exit 0
fi
# Causa raíz CONFIRMADA con strace en vivo (auditoría ADB en dispositivo real):
# "postgres --check" (invocado por pg_ctl Y por el propio initdb en su fase
# test_config_settings()) se cuelga siempre en este dispositivo — read() de 4 bytes sobre un
# socketpair AF_UNIX interno (mecanismo self-pipe/latch de Postgres) que nunca recibe el write
# correspondiente. No es un problema de shared memory/dynamic_shared_memory_type — ya se
# descartó empíricamente. Consecuencia real ya observada: un initdb colgado y matado a mitad de
# camino deja $PGSQL_DATA a medio inicializar (postgresql.conf de 0 bytes, sin pg_hba.conf) —
# y ese datadir corrupto rompe TODOS los intentos futuros, no solo el que se colgó. Por eso acá
# se detecta y limpia antes de reintentar, en vez de solo detectar "no existe".
if [ -d "$PGSQL_DATA" ] && [ ! -f "$PGSQL_DATA/PG_VERSION" ] && [ -n "$(ls -A "$PGSQL_DATA" 2>/dev/null)" ]; then
  echo "[WARN] Datadir de PostgreSQL a medio inicializar (initdb previo interrumpido) — limpiando para reintentar"
  rm -rf "$PGSQL_DATA"
fi
# Primera corrida: initdb si el datadir no existe. "timeout" evita que initdb quede colgado
# para siempre (su fase test_config_settings() dispara el mismo cuelgue que pg_ctl start).
# Bug real encontrado en pruebas funcionales reales por ADB:
# "timeout 45" a secas manda SIGTERM al expirar — el "postgres --check" colgado (read() sobre un
# self-pipe interno que nunca recibe write) NO responde a
# SIGTERM, así que el proceso seguía vivo varios MINUTOS después del timeout nominal, confirmado
# en dispositivo real (tuvo que matarse a mano con SIGKILL). "--kill-after=10" fuerza SIGKILL 10s
# después del SIGTERM si el proceso sigue vivo, garantizando el cierre real que el comentario de
# arriba ya prometía mas nunca cumplía del todo.
if [ ! -f "$PGSQL_DATA/PG_VERSION" ]; then
  mkdir -p "$PGSQL_DATA"
  chmod 700 "$PGSQL_DATA"
  timeout --kill-after=10 45 initdb -D "$PGSQL_DATA" -U "$(whoami)" &>/dev/null
fi
if [ ! -f "$PGSQL_DATA/PG_VERSION" ]; then
  echo "[ERROR] PostgreSQL no puede inicializarse en este dispositivo — 'postgres --check' se cuelga de forma reproducible en el sandbox de Android (causa raíz confirmada con strace). No es un problema de configuración: el motor de PostgreSQL en sí no arranca acá. Alternativa real: usar el módulo de bases de datos vía proot-distro/udocker en vez del PostgreSQL nativo de Termux."
  exit 1
fi
# -t 30: timeout explícito de espera (pg_ctl por defecto también espera, pero sin límite
# claro documentado en todas las versiones) — evita que este script quede colgado para
# siempre si "postgres --check" nunca vuelve. "-t 30" de pg_ctl solo bounda SU PROPIA espera
# de confirmación, no garantiza matar el subproceso "postgres --check" que sigue colgado por
# detrás (mismo hallazgo real que el initdb de arriba) — se envuelve todo el comando en
# "timeout --kill-after" también, para que el script en sí no quede nunca con un proceso hijo
# huérfano corriendo indefinidamente.
timeout --kill-after=10 60 pg_ctl -D "$PGSQL_DATA" -l "$HOME/postgres.log" -t 30 -w start &>/dev/null
if pg_isready -q 2>/dev/null; then
  echo "[OK] PostgreSQL iniciado → psql"
else
  echo "[ERROR] No se pudo iniciar PostgreSQL — 'postgres --check' se cuelga de forma reproducible en este dispositivo (causa raíz confirmada con strace, revisá ~/postgres.log). No es un falso negativo de detección: el motor no arranca acá."
  exit 1
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/postgres_start.sh"

  cat > "$DB_SCRIPTS/postgres_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
PGSQL_DATA="$TERMUX_PREFIX/var/lib/postgresql"
# Mismo fix que postgres_start.sh — "pg_isready" en vez de
# "pgrep -f postgres" para no confundir un subproceso "postgres --check" colgado con el
# servidor real corriendo.
if pg_isready -q 2>/dev/null; then
  pg_ctl -D "$PGSQL_DATA" stop -m fast &>/dev/null; sleep 2
  pg_isready -q 2>/dev/null && echo "[ERROR] No se pudo detener PostgreSQL" || echo "[OK] PostgreSQL detenido"
else
  echo "[OK] PostgreSQL no estaba corriendo"
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/postgres_stop.sh"

  cat > "$DB_SCRIPTS/redis_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
REDIS_DATA="$TERMUX_PREFIX/var/lib/redis"
# Bug real confirmado por auditoría ADB en dispositivo real: "pgrep -x"
# no es confiable en este Android/Termux — Redis arrancaba perfecto pero el chequeo reportaba
# [ERROR] igual. Reemplazado por "pgrep -f" (mismo fix que MySQL/PostgreSQL).
if pgrep -f redis-server &>/dev/null; then
  echo "[OK] Redis ya corriendo → redis-cli"
  exit 0
fi
mkdir -p "$REDIS_DATA"
redis-server --daemonize yes --dir "$REDIS_DATA" --logfile "$HOME/redis.log" &>/dev/null
sleep 2
if pgrep -f redis-server &>/dev/null; then
  echo "[OK] Redis iniciado → redis-cli"
else
  echo "[ERROR] No se pudo iniciar Redis"
  exit 1
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/redis_start.sh"

  cat > "$DB_SCRIPTS/redis_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
if pgrep -f redis-server &>/dev/null; then
  redis-cli shutdown nosave &>/dev/null || pkill -f redis-server 2>/dev/null
  sleep 2
  pgrep -f redis-server &>/dev/null && echo "[ERROR] No se pudo detener Redis" || echo "[OK] Redis detenido"
else
  echo "[OK] Redis no estaba corriendo"
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/redis_stop.sh"

  # mongo_start.sh/mongo_stop.sh — mismo patrón start/stop que MariaDB/PostgreSQL/Redis
  # arriba (agregado v1.2.0). "pgrep -f mongod" (no "pgrep -x", mismo bug real #15/#31 ya
  # documentado en los otros motores de este mismo archivo) + arranque en background con
  # &, no "--fork" (mongod --fork usa syslog interno para confirmar el fork, no siempre
  # disponible en el sandbox de Android/Termux — mismo criterio ya usado por mariadbd
  # arriba, que tampoco usa un flag de "daemonize" propio del motor).
  cat > "$DB_SCRIPTS/mongo_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
MONGO_DATA="$TERMUX_PREFIX/var/lib/mongodb"
if pgrep -f mongod &>/dev/null; then
  echo "[OK] MongoDB ya corriendo → mongosh"
  exit 0
fi
mkdir -p "$MONGO_DATA"
mongod --dbpath "$MONGO_DATA" --port 27017 --bind_ip 127.0.0.1 --logpath "$HOME/mongo.log" --logappend &>/dev/null &
sleep 3
if pgrep -f mongod &>/dev/null; then
  echo "[OK] MongoDB iniciado (puerto 27017) → mongosh"
else
  echo "[ERROR] No se pudo iniciar MongoDB (revisá ~/mongo.log)"
  exit 1
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/mongo_start.sh"

  cat > "$DB_SCRIPTS/mongo_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
if pgrep -f mongod &>/dev/null; then
  pkill -f mongod 2>/dev/null; sleep 2
  pgrep -f mongod &>/dev/null && echo "[ERROR] No se pudo detener MongoDB" || echo "[OK] MongoDB detenido"
else
  echo "[OK] MongoDB no estaba corriendo"
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/mongo_stop.sh"

  # Wrappers start/stop del módulo — los invoca ModuleController (Kotlin) como
  # "bash <script>" SIN flags, así que no pueden ser el propio db.sh (que sin
  # --start/--stop instalaría de nuevo). Cada uno arranca/detiene los 4 servidores.
  #
  # Bug real confirmado en vivo por ADB (auditoría en dispositivo real — módulo Base de
  # Datos: "al encender no pasa nada y da error"): sin un "exit" explícito al final, el código
  # de salida de ESTE script es el de la ÚLTIMA línea ejecutada — o sea, exclusivamente el de
  # mongo_start.sh. Confirmado reproduciendo a mano: mysql_start.sh corrió con éxito real
  # (exit 0, "[OK] MySQL iniciado"), pero mongo_start.sh falló (exit 1, "[ERROR] No se pudo
  # iniciar MongoDB", MongoDB ya documentado como "no crítico" en el header de este archivo) —
  # el switch de la app reportaba "error" igual, aunque MySQL/PostgreSQL/Redis hubieran
  # arrancado perfecto. Los 4 motores son independientes (no hay "&&" entre líneas, cada uno ya
  # imprime su propio [OK]/[ERROR] a stdout) — el problema es solo el exit code agregado. Fix:
  # "exit 0" explícito siempre — ModuleController.kt ya verifica el arranque real por su cuenta
  # (waitForPortOpen() sobre el puerto 3306 de MySQL, ver getModulePort("db")), así que este
  # wrapper no necesita (ni debe) fallar por un motor secundario opcional.
  cat > "$DB_SCRIPTS/start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
[ -f "$HOME/scripts/db/mysql_start.sh" ] && bash "$HOME/scripts/db/mysql_start.sh"
[ -f "$HOME/scripts/db/postgres_start.sh" ] && bash "$HOME/scripts/db/postgres_start.sh"
[ -f "$HOME/scripts/db/redis_start.sh" ] && bash "$HOME/scripts/db/redis_start.sh"
[ -f "$HOME/scripts/db/mongo_start.sh" ] && bash "$HOME/scripts/db/mongo_start.sh"
exit 0
SCRIPT
  chmod +x "$DB_SCRIPTS/start.sh"

  cat > "$DB_SCRIPTS/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
[ -f "$HOME/scripts/db/mysql_stop.sh" ] && bash "$HOME/scripts/db/mysql_stop.sh"
[ -f "$HOME/scripts/db/postgres_stop.sh" ] && bash "$HOME/scripts/db/postgres_stop.sh"
[ -f "$HOME/scripts/db/redis_stop.sh" ] && bash "$HOME/scripts/db/redis_stop.sh"
[ -f "$HOME/scripts/db/mongo_stop.sh" ] && bash "$HOME/scripts/db/mongo_stop.sh"
SCRIPT
  chmod +x "$DB_SCRIPTS/stop.sh"

  log "Scripts de control creados en $DB_SCRIPTS"
  mark_done "db_sqlite_scripts"
fi

# ============================================================
# PASO 7 — Registry
# ============================================================
step "7/$TOTAL_STEPS Actualizando registry"

update_registry "1.2.1"

# ── Limpieza ────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ──────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Base de Datos instalado ✓${NC}"
  echo ""
  echo "  MariaDB:     $(mariadbd --version 2>/dev/null | grep -oE 'MariaDB [0-9.]+' | head -1)"
  echo "  PostgreSQL:  $(psql --version 2>/dev/null)"
  echo "  SQLite:      $(sqlite3 --version 2>/dev/null | awk '{print $1}')"
  echo "  Redis:       $(redis-server --version 2>/dev/null | grep -oE 'v=[0-9.]+' | head -1)"
  echo "  MongoDB:     $(mongod --version 2>/dev/null | grep -oE 'db version v[0-9.]+' | head -1)"
  echo ""
  echo "  Iniciar:   bash ~/scripts/db/mysql_start.sh"
  echo "             bash ~/scripts/db/postgres_start.sh"
  echo "             bash ~/scripts/db/redis_start.sh"
  echo "             bash ~/scripts/db/mongo_start.sh"
  echo ""
fi

notify_event "db" "install_done" ""
log "Instalación de Base de Datos completada"
exit 0
