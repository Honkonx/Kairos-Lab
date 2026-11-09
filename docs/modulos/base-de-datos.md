# Base de Datos (`db`) — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/db.sh`; el detalle (`DbFragment.kt`) combina estado/control de los servidores (MariaDB, PostgreSQL, Redis; MongoDB sin UI) con la gestión de bases SQLite.

---

**Script:** `modulos/db.sh` (v1.2.1, 662 líneas) — espejo idéntico en `app/src/main/assets/scripts/db.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DbFragment.kt` (993 líneas) + `DbSchemaFragment.kt` (619 líneas); navegación: `ModuleDetailNavigator.kt:37` → `DbFragment`
**`modules.json` (`:604-628`):** `id: db`, `script: db.sh`, `port: "3306"`, `category: db`, `installMethods: [standard]`, `catalogVersion: 1.2.1`, `hasSwitch: false`, `tmuxSession: ""`

---

## 1. Qué es y para qué sirve

Servidores de base de datos locales + gestor SQLite: MariaDB, PostgreSQL, Redis y MongoDB (intento) instalados con `pkg`, scripts de arranque/parada propios y una pantalla para controlarlos, crear/eliminar/respaldar/restaurar bases y trabajar con archivos SQLite. Agrupa estos motores en una sola pantalla:

- **MySQL / MariaDB** — servidor real con datadir propio (`$PREFIX/var/lib/mysql`), arranque/detención manual desde la app.
- **PostgreSQL** — servidor real con datadir propio (`$PREFIX/var/lib/postgresql`). **Limitación conocida**: en al menos un dispositivo real (comprobado con `strace`), el motor de PostgreSQL **no puede arrancar en absoluto** — cualquier modo single-process de `postgres` (`--check`, y el equivalente interno que dispara `initdb`) se cuelga leyendo un `socketpair` interno de self-pipe/latch que nunca recibe respuesta, aparentemente por una interacción con el sandbox de Android en ese hardware. No es un problema de shared memory (`libandroid-shmem` ya lo resuelve) ni de configuración — `dynamic_shared_memory_type=mmap` también cuelga. `DbFragment.kt` muestra un banner ámbar avisando esto (no bloqueante, no todos los dispositivos lo sufren) y `db.sh` detecta/limpia un datadir corrupto por un `initdb` interrumpido. **Si PostgreSQL falla repetidamente en un dispositivo, usar MySQL/MariaDB o Redis en su lugar.**
- **Redis** (paquete oficial `redis` del repo main de Termux) — servidor real, datadir propio (`$PREFIX/var/lib/redis`), sin auth por defecto. Si `pkg install redis` falla, el paso queda en `warn` (no crítico, no aborta el resto de `db.sh`).
- **MongoDB** — intento de instalación con scripts propios (`mongo_start.sh`/`mongo_stop.sh`) incluidos en `start.sh`/`stop.sh`, pero **sin fila ni switch en la UI**.
- **SQLite** — no es un servidor (es un archivo) — gestión de archivos `.db`/`.sqlite` (incluida la de n8n dentro del rootfs proot) usando `android.database.sqlite.SQLiteDatabase` del propio framework Android, sin ningún subproceso Python.

**Clasificación: MÓDULO FUNCIONAL.** Servicio persistente real (procesos `mariadbd`/`postgres`/`redis-server`/`mongod`), estado por motor, tres pestañas y operaciones reales sobre los datos. ~27 opciones de UI (4 filas de estado + refrescar; 3 switches de servidor; estructura; 8 acciones SQLite; por motor SQL: crear/eliminar/backup/restaurar = 8; mantenimiento Actualizar/Desinstalar de `BaseModuleFragment`; `DbSchemaFragment`: chips de motor/base/vista).

## 2. Permisos

- **Android**: ninguno específico — usa el mismo proceso Termux (almacenamiento, otorgado en el wizard). No requiere `INTERNET` extra, no requiere root.
- **Termux interno**: `mariadb`, `postgresql`, `redis` se instalan vía `pkg` (compilados nativo ARM64). Los servidores escuchan en el loopback (`127.0.0.1`) — `mysqld` en `3306`, `postgres` en `5432`, `redis` en `6379`, Mongo en `27017` (`--bind_ip 127.0.0.1`, `db.sh:571`) — accesibles desde la app para el módulo n8n (que puede apuntar sus credenciales ahí).

## 3. Lógica de instalación (`modulos/db.sh`)

Script con el contrato `lib.sh` estándar (SILENT/CHECKPOINT/REGISTRY). Flags (`db.sh:88-94`):

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts, mismo output `[OK]`/`[ERROR]` |
| `--force` | Reinstala aunque ya esté (ignora los guards `command -v`) |
| `--describe` / `--describe-files` | Manifiestos JSON (`{"id":"db","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}`) |
| `--status` | JSON con estado de cada motor (`installed`, `running`, `version`) |
| `--uninstall` | Hace `pkill -f mariadbd/postgres/redis-server/mongod` y borra registry y `~/scripts/db`, **no** desinstala paquetes ni borra datadirs (`db.sh:~196-205`); ojo: `pkill -f postgres` es por patrón amplio |
| `--start` / `--stop` | Arranca / detiene los servidores (`db.sh:~207-227`) |

`TOTAL_STEPS=7` (`db.sh:281`): update de Termux → `pkg install mariadb` → `postgresql` → `redis` → `mongodb` (no crítico, `warn`) → sqlite3 + escribir wrappers en `~/scripts/db/` → registry.

- Verificación: tras cada `pkg install`, `command -v <binario>` (`db.sh:~322`); **no** ejecuta `--version` vía `verify_binary_installed`. El registry (`update_registry`, `db.sh:225-238`) sí condiciona `installed=true` a que el binario responda a `--version` (versión no vacía), por motor.
- Datadirs: MariaDB `mariadb-install-db --auth-root-authentication-method=normal` → root **sin contraseña** (`db.sh:413`); PostgreSQL `initdb -D ... -U "$(whoami)"` (el superusuario es el usuario Linux de la app, no siempre `postgres`) envuelto en `timeout --kill-after=10 45` (`db.sh:479`), con limpieza de un datadir corrupto previo (`rm -rf "$PGSQL_DATA"`, `db.sh:465`, solo si no hay `PG_VERSION`); Redis y Mongo sin auth.
- Sin sha256 ni descargas externas: todo viene de `pkg` (repos de Termux).
- **Idempotencia**: `DB_CONFIGURED` exige también `mongod` (`db.sh:245-246`). Como según el propio encabezado del script el paquete `mongodb` no existe en los repos de Termux (afirmación del script, no reproducida), `DB_CONFIGURED` quedaría siempre en `false` y el script nunca toma el atajo de "ya instalado".
- El mensaje final de `--start`/`--stop` usa `[ $START ]`, que con `START=false` (cadena no vacía) evalúa verdadero → siempre imprime "Servidores iniciados" aunque sea `--stop` (cosmético).

## 4. Ejecución y detección de estado

- Wrappers `~/scripts/db/{mysql,postgres,redis,mongo}_{start,stop}.sh`, más `start.sh`/`stop.sh` que arrancan/detienen los **4** (`db.sh:~595-626`; `ModuleController.kt:1171,1190`) — `ModuleController.startModule()` ejecuta `bash <script>` sin argumentos, por eso `db.sh` genera wrappers sin parámetros en vez de que la app llame a `db.sh --start`.
- Detección de procesos con `pgrep -f` (no `-x`), `pg_isready` para Postgres. Redis arranca con `redis-server --daemonize yes --dir ... --logfile ~/redis.log` sin config propia (`db.sh:532`): hereda los defaults de Redis (protected-mode) — no verificado en dispositivo si escucha solo en loopback. `mariadbd --datadir ... --skip-grant-tables=false` sin `--bind-address` explícito (`db.sh:~419`): el bind depende del my.cnf de Termux (inferencia).
- `ModuleController.isRunning("db")` = `checkPort(3306) || checkPort(5432) || checkPort(6379)` (`ModuleController.kt:357-359`): usa **puertos**, no `isProcessAlive`; **no incluye 27017**. `getModulePort("db")` = 3306 (`:1241`). No hay tmux ni switch central (`hasSwitch:false`).
- **Instalación**: registry (`~/.android_server_registry`, `db.installed=true`); `ModuleInstalled` tiene fallback binario (`BINARY_FALLBACK` mapea `db` → `mariadbd`) para que el tab Módulos detecte instalación real incluso con el registry desincronizado. Si ni `mysqld` ni `postgres` existen (o `db.installed != true`), `DbFragment` muestra el "Módulo no instalado" estándar.
- Logs: `~/postgres.log`, `~/redis.log`, `~/mongo.log`.

## 5. Scripts de control (`~/scripts/db/`, 10 archivos)

| Script | Qué hace |
|---|---|
| `mysql_start.sh` / `mysql_stop.sh` | Arranca / detiene solo MySQL/MariaDB (`mysqld_safe`/`mysqld`) |
| `postgres_start.sh` / `postgres_stop.sh` | Arranca / detiene solo PostgreSQL |
| `redis_start.sh` / `redis_stop.sh` | Arranca (`redis-server --daemonize yes --dir "$REDIS_DATA"`) / detiene (`redis-cli shutdown nosave`, fallback `pkill -f redis-server`) solo Redis |
| `mongo_start.sh` / `mongo_stop.sh` | Arranca / detiene solo MongoDB |
| `start.sh` / `stop.sh` | Arrancan / detienen los 4 motores (wrapper del switch ON/OFF, `getModuleStartScript("db")` / `getModuleStopInfo("db")`) |

## 6. Pantalla de la app

`getModuleId()` → `"db"`, `getModuleName()` → `"Base de Datos"`. Tres pestañas (`setupTabs`, `DbFragment.kt:66-75`, helper `BaseModuleFragment.setupTabs()`); la card de mantenimiento (Actualizar/Desinstalar, `addMaintenanceCard()`) queda FUERA de las pestañas, al final de la pantalla.

| Pestaña | Cards que agrupa |
|---|---|
| **Motores** (`buildMotoresTab`, `:87`) | ESTADO (MySQL/PostgreSQL/SQLite/Redis — **Mongo no aparece** — + "🔁 Refrescar estado") + SERVIDORES (switches MySQL/PostgreSQL/Redis) + ESTRUCTURA ("🗺 Estructura de la BD") |
| **SQLite** (`buildSqliteTab`, `:143`) | SQLITE: 4 botones visibles (listar BDs, explorar/abrir, BD de n8n, crear vacía); las demás acciones (ver tablas, exportar CSV, backup, query SQL, abrir en terminal) viven en el menú que abre `openDbFileMenu` (`:581`) |
| **Backups** (`buildBackupsTab`, `:152`) | MYSQL/MARIADB — BASES DE DATOS + POSTGRESQL — BASES DE DATOS (crear/eliminar/backup/restaurar de cada motor) |

**ESTADO** (se refresca al abrir la pantalla y con "🔁 Refrescar estado"):

| Fila | Fuente |
|---|---|
| MySQL/MariaDB | `pgrep` en vivo + `mysql.version` del registry |
| PostgreSQL | `pgrep` en vivo + `postgres.version` del registry |
| SQLite | `sqlite.version` del registry |
| Redis | `isAlive("redis-server")` en vivo + `redis.version` del registry |

**SERVIDORES** — switches independientes para MySQL/MariaDB, PostgreSQL (con aviso ámbar de la limitación arriba descrita) y Redis: `startServer`/`stopServer` → `runServerScript` ejecuta `bash ~/scripts/db/<motor>_start|stop.sh` con `ProcessBuilder` (`:262-288`); el resultado se muestra en un Snackbar. Son motores independientes que corren en paralelo, no variantes excluyentes.

**ESTRUCTURA** — "🗺 Estructura de la BD" navega a `DbSchemaFragment.kt`: vista del esquema real (tablas por categoría + mapa mental con relaciones FK) para los tres motores.

**MYSQL/MARIADB — BASES DE DATOS** y **POSTGRESQL — BASES DE DATOS** — sin driver JDBC en la app (Android no lo trae), corren el CLI real (`mysql`/`psql`/`mysqldump`/`pg_dump`, los binarios que instala `db.sh`) vía `ProcessBuilder(bash -c)` (`runShellCommand`, `:337`) en un `Thread`, resultado en Snackbar. Nombre de BD validado con regex `^[A-Za-z][A-Za-z0-9_]*$` (`:302`). Backups a `~/backups/db/`:

| Botón | Acción | Comando real |
|---|---|---|
| ＋ Crear BD (MySQL) | Prompt de nombre | `mysql -u root -e 'CREATE DATABASE IF NOT EXISTS \`nombre\`;'` |
| 🗑 Eliminar BD (MySQL) | Confirmación previa ("no se puede deshacer") | `mysql -u root -e 'DROP DATABASE IF EXISTS \`nombre\`;'` |
| 💾 Backup (mysqldump) | Escribe a `~/backups/db/mysql_<nombre>_<timestamp>.sql` | `mysqldump -u root <nombre> > <archivo>` |
| ＋ Crear BD (PostgreSQL) | Prompt de nombre (mismo regex) | `psql -U "$(whoami)" -d postgres -c 'CREATE DATABASE nombre;'` |
| 🗑 Eliminar BD (PostgreSQL) | Confirmación previa | `psql -U "$(whoami)" -d postgres -c 'DROP DATABASE IF EXISTS nombre;'` |
| 💾 Backup (pg_dump) | Escribe a `~/backups/db/postgres_<nombre>_<timestamp>.sql` | `pg_dump -U "$(whoami)" <nombre> > <archivo>` |
| 📥 Restaurar backup (MySQL y PostgreSQL) | `promptRestoreBackup()`/`restoreMysqlDb()`/`restorePgDb()` — lista los `.sql` en `~/backups/db` filtrados por motor, sugiere el nombre de BD original desde el nombre de archivo (`<motor>_<nombre>_<timestamp>.sql`, pasa por el mismo regex, `:398`), crea la BD destino si no existe y corre `mysql`/`psql` para importar el `.sql` | — |

**SQLITE** — usa `android.database.sqlite.SQLiteDatabase.openDatabase()` (puede abrir cualquier `.db`/`.sqlite` del filesystem), sin subproceso Python (salvo "Abrir BD interactivo", que lanza `sqlite3` en la terminal con `shellQuote`). Todas las acciones corren en un `Thread` y reportan vía Snackbar:

| Botón | Acción |
|---|---|
| 📋 Listar BDs en ~ | Recorre `~` (profundidad 3, salteando `node_modules`/`.git`/`venv`/…) + detecta la BD de n8n (`rootfs/debian/root/.n8n/database.sqlite`) |
| 📂 Abrir BD (interactivo) | Prompt de ruta → `launchTerminalCommand("sqlite3 <ruta>")` |
| 📊 Ver tablas de una BD | Prompt de ruta → `SELECT name FROM sqlite_master WHERE type='table'` |
| ⚡ BD de n8n (acceso rápido) | Localiza y lista las tablas de la BD real de n8n dentro del rootfs proot |
| 📤 Exportar BD a CSV | Prompt ruta + tabla (`all` = todas) → escribe `<tabla>.csv` al lado del archivo |
| ＋ Crear nueva BD vacía | Crea `~/<nombre>.db` |
| 💾 Backup de una BD | Prompt de ruta → `File.copyTo()` a `~/backups/db/<nombre>_<timestamp>.bak` (copia directa del archivo único) |
| ⚠ Query SQL | Prompt ruta + SQL — autodetecta SELECT/PRAGMA/WITH/EXPLAIN (`rawQuery`, devuelve filas) vs el resto (`execSQL`, muestra `changes()`) |

**`DbSchemaFragment.kt` — "Estructura de la BD"**:

| Control | Qué hace |
|---|---|
| Botón cerrar (X, arriba a la izquierda) | `parentFragmentManager.popBackStack()` |
| Fila de chips de motor (SQLite/MySQL/PostgreSQL) | Cambia `selectedEngine`, recarga la lista de bases de ese motor. MySQL/PostgreSQL se consultan vía el cliente CLI real; SQLite 100% en proceso con `SQLiteDatabase` + `PRAGMA foreign_key_list` |
| Fila de chips de base de datos | Selecciona `selectedDatabase`, carga su esquema |
| Chip "📂 Por categoría" / "🗺 Mapa mental" | Cambia `viewMode` entre lista de tablas agrupadas por categoría y el grafo visual (`SchemaGraphView`, Canvas con nodos=tablas y líneas=relaciones FK reales; sin drag/zoom/pan propios) |

No hay shell de `mysql`/`psql`/`redis-cli`/`mongosh` desde la UI ("Abrir en terminal" es solo para SQLite interactivo).

## 7. Archivos y registry

`~/scripts/db/*.sh` (10 archivos); datadirs `$PREFIX/var/lib/{mysql,postgresql,redis,mongodb}`; `~/backups/db/*.sql|*.bak`; logs en `~`; checkpoint de `lib.sh`; CSV exportado al lado de la BD origen.

Registry (`~/.android_server_registry`): `db.installed`, `db.version`, `mysql.installed/version`, `postgres.installed/version`, `sqlite.installed/version`, `redis.installed/version`, `mongo.installed/version`.

## 8. Seguridad

- Sin autenticación en MariaDB (root sin contraseña), PostgreSQL (usuario = usuario Linux de la app) y Redis/Mongo por defecto: el aislamiento depende de escuchar en loopback. Mongo fija `127.0.0.1` explícito; MariaDB/Redis **no** lo fijan en los scripts. Los datos viven en el espacio privado de la app.
- Inyección: nombres de BD validados por regex antes de interpolarse en `mysql -e`/`psql -c`; rutas con `shellQuote`. Sin `curl|bash`.
- Los backups `.sql` quedan en claro en `~/backups/db/` (permisos por defecto, sin cifrar).

## 9. Limitaciones conocidas

- `ModuleController.isRunning("db")` se calcula por puertos y no incluye 27017.
- MongoDB tiene scripts y entra en `start.sh`/`stop.sh` pero no tiene fila ni switch en la UI; la `description` de `modules.json` anuncia "Redis + MongoDB", consistente con el script pero no con la UI.
- Idempotencia rota por exigir `mongod` (ver §3).
- `--stop` imprime "iniciados" (cosmético).
- PostgreSQL puede no arrancar en ciertos dispositivos (ver §1).
- Inferencia: que el paquete `mongodb` realmente no exista (lo afirma el script, no se reprodujo); que el `pkill -f postgres` de `--uninstall` pueda matar procesos ajenos con "postgres" en la línea de comandos.
- **Sincronización**: `modulos/db.sh` ↔ `app/src/main/assets/scripts/db.sh` deben mantenerse iguales.

## 10. Fecha de alta

`modulos/db.sh`: **2026-08-11**.
