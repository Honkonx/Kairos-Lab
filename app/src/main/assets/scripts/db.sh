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
#    ✅ Registry actualizado (db.installed, db.version, ...)
#
#  NOTA (2026-08-19, cruce contra referencia/termux/core-termux-main/core/cli/
#  commands/list.sh::_list_db): ese proyecto de referencia expone 5 motores
#  (PostgreSQL, MariaDB, SQLite, MongoDB, Redis) contra los 3 que este módulo
#  cubría. Redis es un paquete real y oficial del repo main de Termux
#  (aarch64) — se agrega acá con el mismo patrón start/stop que MariaDB/
#  PostgreSQL. MongoDB NO se agrega: no tiene build oficial ARM64 en el repo
#  main de Termux (community/x11-repo tampoco lo confirman) — instalarlo
#  requeriría compilar desde fuente o depender de un repo de terceros no
#  auditado, fuera del alcance de un fix acotado. Documentado en
#  docs/viejo/AUDITORIA_MODULOS_SISTEMA_VS_REFERENCIA_2026-08-19.md
#  como pendiente sin ejecutar.
#
#  OUTPUT (modo --silent):
#    [STEP] N/6 Descripción     ← para barra de progreso
#    [OK] mensaje                ← paso completado
#    [ERROR] mensaje             ← fallo (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 1.1.0 | Agosto 2026 (v1.1.0: agrega Redis, ver NOTA arriba)
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
      file_globs: [{pattern: $glob, required: true, note: "scripts de control generados (mysql_start/stop.sh, postgres_start/stop.sh, redis_start/stop.sh, start.sh, stop.sh)"}],
      dependencies: [
        {id: "pkg:mariadb", check_cmd: "command -v mariadbd >/dev/null 2>&1", install_hint: "pkg install -y mariadb"},
        {id: "pkg:postgresql", check_cmd: "command -v postgres >/dev/null 2>&1", install_hint: "pkg install -y postgresql"},
        {id: "pkg:redis", check_cmd: "command -v redis-server >/dev/null 2>&1", install_hint: "pkg install -y redis"},
        {id: "pkg:sqlite", check_cmd: "command -v sqlite3 >/dev/null 2>&1", install_hint: "pkg install -y sqlite"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "MariaDB/PostgreSQL/Redis son paquetes apt completos — cientos de archivos ya gestionados por pkg, no se snapshotean",
        "Los datadirs ($TERMUX_PREFIX/var/lib/{mysql,postgresql,redis}) son datos de usuario, nunca se empaquetan"
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
  # Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano183.md/humano184.md):
  # "pgrep -x" compara contra el nombre corto del proceso (comm), poco confiable en este
  # Android/Termux — MariaDB y Redis arrancan y funcionan perfecto pero el chequeo reportaba
  # [ERROR] igual. Además el binario real de MariaDB se llama "mariadbd", no "mysqld". Fix:
  # "pgrep -f" (matchea la línea de comando completa) + nombre real del binario.
  pgrep -f mariadbd &>/dev/null && MYSQL_RUNNING=true
  # Bug #31 (ver docs/humano/humano193.md): "pgrep -f postgres" da falso positivo con un subproceso
  # "postgres --check" colgado (de pg_ctl) — pg_isready no se deja engañar, hace una conexión real.
  pg_isready -q 2>/dev/null && PGSQL_RUNNING=true
  REDIS_RUNNING=false
  pgrep -f redis-server &>/dev/null && REDIS_RUNNING=true
  MYSQL_VER=$(mariadbd --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+\.[0-9]+)' | head -1)
  PGSQL_VER=$(psql --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+)' | head -1)
  SQLITE_VER=$(sqlite3 --version 2>/dev/null | awk '{print $1}')
  REDIS_VER=$(redis-server --version 2>/dev/null | grep -oE 'v=[0-9]+\.[0-9]+\.[0-9]+' | head -1 | cut -d= -f2)
  cat << EOF
{"ok":true,"mysql":{"installed":$([ -n "$MYSQL_VER" ] && echo true || echo false),"running":$MYSQL_RUNNING,"version":"$MYSQL_VER"},"postgres":{"installed":$([ -n "$PGSQL_VER" ] && echo true || echo false),"running":$PGSQL_RUNNING,"version":"$PGSQL_VER"},"sqlite":{"installed":$([ -n "$SQLITE_VER" ] && echo true || echo false),"version":"$SQLITE_VER"},"redis":{"installed":$([ -n "$REDIS_VER" ] && echo true || echo false),"running":$REDIS_RUNNING,"version":"$REDIS_VER"}}
EOF
  exit 0
fi

# ── Uninstall ───────────────────────────────────────────────
if $UNINSTALL; then
  pkill -f mariadbd 2>/dev/null; pkill -f postgres 2>/dev/null; pkill -f redis-server 2>/dev/null
  rm -f "$REGISTRY.tmp"
  [ -f "$REGISTRY" ] && grep -v "^db\.\|^mysql\.\|^postgres\.\|^sqlite\.\|^redis\." "$REGISTRY" > "$REGISTRY.tmp"
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
  if $START; then
    [ -f "$DB_MYSQL" ] && bash "$DB_MYSQL" 2>/dev/null || warn "MySQL: script no disponible (instala el módulo db)"
    [ -f "$DB_PG" ] && bash "$DB_PG" 2>/dev/null || warn "PostgreSQL: script no disponible (instala el módulo db)"
    [ -f "$DB_REDIS" ] && bash "$DB_REDIS" 2>/dev/null || warn "Redis: script no disponible (instala el módulo db)"
  else
    [ -f "$DB_MYSQL_STOP" ] && bash "$DB_MYSQL_STOP" 2>/dev/null
    [ -f "$DB_PG_STOP" ] && bash "$DB_PG_STOP" 2>/dev/null
    [ -f "$DB_REDIS_STOP" ] && bash "$DB_REDIS_STOP" 2>/dev/null
  fi
  echo "[OK] $([ $START ] && echo 'Servidores iniciados' || echo 'Servidores detenidos')"
  exit 0
fi

# ── Registry helper ─────────────────────────────────────────
update_registry() {
  local version="$1"
  registry_write db "installed=true" "version=${version}" "install_date=$(date +%Y-%m-%d)"
  registry_write mysql "installed=true" "version=$(mariadbd --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+\.[0-9]+)' | head -1 || echo 'unknown')"
  registry_write postgres "installed=true" "version=$(psql --version 2>/dev/null | grep -oE '([0-9]+\.[0-9]+)' | head -1 || echo 'unknown')"
  registry_write sqlite "installed=true" "version=$(sqlite3 --version 2>/dev/null | awk '{print $1}' || echo 'unknown')"
  registry_write redis "installed=true" "version=$(redis-server --version 2>/dev/null | grep -oE 'v=[0-9]+\.[0-9]+\.[0-9]+' | head -1 | cut -d= -f2 || echo 'unknown')"
}

# ── Verificar si ya está instalado ──────────────────────────
# Nota: se exige también redis-server acá (no solo mariadb+postgres) para que
# una instalación previa a v1.1.0 (sin Redis) reciba el paso nuevo la próxima
# vez que se corra el módulo, en vez de quedar salteada por el checkpoint.
DB_CONFIGURED=false
command -v mariadbd &>/dev/null && command -v postgres &>/dev/null && command -v redis-server &>/dev/null && DB_CONFIGURED=true

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
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instalará:"
  echo "  ▸ MariaDB (MySQL) con datadir en $MYSQL_DATA"
  echo "  ▸ PostgreSQL con datadir en $PGSQL_DATA"
  echo "  ▸ SQLite CLI (sqlite3)"
  echo "  ▸ Redis con datadir en $REDIS_DATA"
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=6

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
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
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
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
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
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" redis || \
      warn "redis no se pudo instalar (no crítico — MariaDB/PostgreSQL/SQLite siguen disponibles)"
  fi
  command -v redis-server &>/dev/null && log "Redis ✓" || warn "redis-server no quedó disponible"
  mark_done "db_redis"
fi

# ============================================================
# PASO 5 — SQLite + scripts de control
# ============================================================
step "5/$TOTAL_STEPS Garantizando SQLite y scripts"

if ! check_done "db_sqlite_scripts"; then
  # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
  command -v sqlite3 &>/dev/null || { pkg_update_with_fallback; pkg install -y sqlite; } || warn "sqlite3 no instalado"
  command -v sqlite3 &>/dev/null && log "SQLite ✓" || warn "sqlite3 no disponible"

  mkdir -p "$DB_SCRIPTS"

  cat > "$DB_SCRIPTS/mysql_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
MYSQL_DATA="$TERMUX_PREFIX/var/lib/mysql"
# Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano183.md/humano184.md): "pgrep -x"
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
# Bug #31 arreglado (auditoría ADB 2026-08-22, ver docs/humano/humano193.md): "pgrep -f postgres"
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
# Causa raíz CONFIRMADA con strace en vivo (auditoría ADB 2026-08-22, ver docs/humano/humano194.md):
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
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# "timeout 45" a secas manda SIGTERM al expirar — el "postgres --check" colgado (read() sobre un
# self-pipe interno que nunca recibe write, ver docs/humano/humano194.md bug #31) NO responde a
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
  echo "[ERROR] PostgreSQL no puede inicializarse en este dispositivo — 'postgres --check' se cuelga de forma reproducible en el sandbox de Android (causa raíz confirmada con strace, ver docs/humano/humano194.md bug #31). No es un problema de configuración: el motor de PostgreSQL en sí no arranca acá. Alternativa real: usar el módulo de bases de datos vía proot-distro/udocker en vez del PostgreSQL nativo de Termux."
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
  echo "[ERROR] No se pudo iniciar PostgreSQL — 'postgres --check' se cuelga de forma reproducible en este dispositivo (causa raíz confirmada con strace, revisá ~/postgres.log y docs/humano/humano194.md bug #31). No es un falso negativo de detección: el motor no arranca acá."
  exit 1
fi
SCRIPT
  chmod +x "$DB_SCRIPTS/postgres_start.sh"

  cat > "$DB_SCRIPTS/postgres_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
PGSQL_DATA="$TERMUX_PREFIX/var/lib/postgresql"
# Mismo fix que postgres_start.sh (bug #31, ver docs/humano/humano193.md) — "pg_isready" en vez de
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
# Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano183.md/humano184.md): "pgrep -x"
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

  # Wrappers start/stop del módulo — los invoca ModuleController (Kotlin) como
  # "bash <script>" SIN flags, así que no pueden ser el propio db.sh (que sin
  # --start/--stop instalaría de nuevo). Cada uno arranca/detiene los 3 servidores.
  cat > "$DB_SCRIPTS/start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
[ -f "$HOME/scripts/db/mysql_start.sh" ] && bash "$HOME/scripts/db/mysql_start.sh"
[ -f "$HOME/scripts/db/postgres_start.sh" ] && bash "$HOME/scripts/db/postgres_start.sh"
[ -f "$HOME/scripts/db/redis_start.sh" ] && bash "$HOME/scripts/db/redis_start.sh"
SCRIPT
  chmod +x "$DB_SCRIPTS/start.sh"

  cat > "$DB_SCRIPTS/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
[ -f "$HOME/scripts/db/mysql_stop.sh" ] && bash "$HOME/scripts/db/mysql_stop.sh"
[ -f "$HOME/scripts/db/postgres_stop.sh" ] && bash "$HOME/scripts/db/postgres_stop.sh"
[ -f "$HOME/scripts/db/redis_stop.sh" ] && bash "$HOME/scripts/db/redis_stop.sh"
SCRIPT
  chmod +x "$DB_SCRIPTS/stop.sh"

  log "Scripts de control creados en $DB_SCRIPTS"
  mark_done "db_sqlite_scripts"
fi

# ============================================================
# PASO 6 — Registry
# ============================================================
step "6/$TOTAL_STEPS Actualizando registry"

update_registry "1.1.0"

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
  echo ""
  echo "  Iniciar:   bash ~/scripts/db/mysql_start.sh"
  echo "             bash ~/scripts/db/postgres_start.sh"
  echo "             bash ~/scripts/db/redis_start.sh"
  echo ""
fi

notify_event "db" "install_done" ""
log "Instalación de Base de Datos completada"
exit 0
