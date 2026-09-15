# Base de Datos (`db`)

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/db.sh`; el detalle (`DbFragment.kt`) combina estado/control de los tres servidores (MySQL/MariaDB, PostgreSQL, Redis) con la gestión de bases SQLite.

---

**Script:** `modulos/db.sh` — espejo en `app/src/main/assets/scripts/db.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DbFragment.kt`
**`id` en `modules.json`:** `db` — `hasSwitch: true` (proceso persistente: `mysqld`, `postgres` y/o `redis-server`)

---

## 1. Descripción General

Agrupa **cuatro** motores de base de datos en una sola pantalla:

- **MySQL / MariaDB** — servidor real con datadir propio (`$PREFIX/var/lib/mysql`), arranque/detención manual desde la app y vía el switch del módulo.
- **PostgreSQL** — servidor real con datadir propio (`$PREFIX/var/lib/postgresql`), arranque/detención manual desde la app y vía el switch del módulo. **Limitación conocida**: en algunos dispositivos Android, el motor de PostgreSQL no puede arrancar en absoluto — cualquier modo single-process de `postgres` se cuelga leyendo un `socketpair` interno de self-pipe/latch que nunca recibe respuesta, aparentemente por una interacción con el sandbox de Android en ese hardware específico. No es un problema de shared memory ni de configuración estándar — es una limitación real de plataforma, no un bug de Kairos arreglable con más reintentos. La app muestra un aviso no bloqueante sobre esto (no todos los dispositivos lo sufren); si PostgreSQL falla repetidamente en un dispositivo, MySQL/MariaDB o Redis son la alternativa.
- **Redis** — servidor real, datadir propio (`$PREFIX/var/lib/redis`), sin auth por defecto (mismo criterio del resto de motores — loopback-only). Si la instalación del paquete falla, el paso no es crítico (no aborta el resto del script).
- **SQLite** — no es un servidor (es un archivo) — gestión de archivos `.db`/`.sqlite` (incluida la de n8n, cuando corre dentro de un contenedor proot) usando la API nativa de Android para SQLite, sin ningún subproceso Python.

## 2. Permisos

- **Android**: ninguno específico — usa el mismo proceso Termux (almacenamiento, otorgado en el asistente de primer uso). No requiere permiso de red extra, no requiere root.
- **Termux interno**: `mariadb` y `postgresql` se instalan vía `pkg` (compilados nativo ARM64). Los servidores escuchan en el loopback (`127.0.0.1`) — `mysqld` en `3306`, `postgres` en `5432` — accesibles desde la app para el módulo n8n (que puede apuntar sus credenciales ahí).

## 3. Lógica de instalación (`modulos/db.sh`)

Script de instalación con el contrato estándar (SILENT/CHECKPOINT/REGISTRY) y los flags estándar:

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts, mismo output `[OK]`/`[ERROR]` |
| `--force` | Reinstala aunque ya esté (ignora los guards `command -v`) |
| `--describe` | Manifiesto JSON: `{"id":"db","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |
| `--status` | JSON con estado de los cuatro motores: `{mysql:{installed,running,version},postgres:{...},sqlite:{...},redis:{installed,running,version}}` |
| `--uninstall` | Desinstala (remueve paquetes, datadirs, scripts y claves de registry) |
| `--start` / `--stop` | Arranca / detiene los 3 servidores (mysql+postgres+redis, usado por los wrappers) |

Pasos: `pkg update/upgrade` → instala `mariadb` + `postgresql` + `redis` + `sqlite3` → inicializa datadirs si faltan → escribe los **wrappers de control** en `~/scripts/db/` (ver §5) → registry.

## 4. Detección de estado

- **Instalación**: el registry — `db.installed=true`. La UI además verifica los binarios reales (`mysqld`/`postgres`) con `pgrep -x`.
- **"Corriendo"**: el módulo cuenta como corriendo si cualquiera de los servidores está vivo (`mysqld` o `postgres` o `redis-server`).
- **Puerto**: `3306` (usado como referencia para esperar a que el servidor esté listo).

## 5. Scripts de control (`~/scripts/db/`)

Wrappers en `$HOME/scripts/db/`:

| Script | Qué hace |
|---|---|
| `mysql_start.sh` | Arranca solo MySQL/MariaDB (`mysqld_safe`/`mysqld`) |
| `mysql_stop.sh` | Detiene solo MySQL/MariaDB |
| `postgres_start.sh` | Arranca solo PostgreSQL |
| `postgres_stop.sh` | Detiene solo PostgreSQL |
| `redis_start.sh` | Arranca solo Redis (`redis-server --daemonize yes --dir "$REDIS_DATA"`) |
| `redis_stop.sh` | Detiene solo Redis (`redis-cli shutdown nosave`, fallback `pkill -f redis-server`) |
| `start.sh` | Arranca los 3 (MySQL + PostgreSQL + Redis, en ese orden — wrapper del switch central ON) |
| `stop.sh` | Detiene los 3 (wrapper del switch central OFF) |

## 6. Pantalla real de la app (`DbFragment.kt`)

Organizada en **3 pestañas** agrupadas por tipo:

| Pestaña | Cards que agrupa |
|---|---|
| **Motores** | ESTADO (los 4 motores + "🔁 Refrescar estado") + SERVIDORES (switches MySQL/PostgreSQL/Redis) + ESTRUCTURA ("🗺 Estructura de la BD") |
| **SQLite** | SQLITE (las 8 acciones de gestión de archivos `.db`/`.sqlite`) |
| **Backups** | MYSQL/MARIADB — BASES DE DATOS + POSTGRESQL — BASES DE DATOS (crear/eliminar/backup/restore de cada motor) |

La card de mantenimiento (Actualizar/Desinstalar) queda fuera de las pestañas, al final de la pantalla.

**ESTADO** (se refresca solo al abrir la pantalla y con "🔁 Refrescar estado"):

| Fila | Fuente |
|---|---|
| MySQL/MariaDB | `pgrep -x mysqld` en vivo + versión del registry |
| PostgreSQL | `pgrep -x postgres` en vivo + versión del registry |
| SQLite | versión del registry |
| Redis | proceso `redis-server` en vivo + versión del registry |

**SERVIDORES** — arrancan/detienen cada servidor por separado. El resultado del script se muestra en un mensaje breve en pantalla. El switch central de la pantalla Módulos controla los 3 servidores juntos; el switch individual de cada uno en esta card es independiente de ese switch central.

**ESTRUCTURA** — "🗺 Estructura de la BD" navega a `DbSchemaFragment.kt`: vista del esquema real (tablas por categoría + mapa mental con relaciones FK) para los tres motores relacionales.

**MYSQL/MARIADB — BASES DE DATOS** y **POSTGRESQL — BASES DE DATOS** — sin driver JDBC en la app (Android no lo trae), corren el CLI real (`mysql`/`psql`/`mysqldump`/`pg_dump`) vía `ProcessBuilder`, resultado en un mensaje en pantalla:

| Botón | Acción | Comando real |
|---|---|---|
| ＋ Crear BD (MySQL) | Prompt de nombre (regex `^[A-Za-z][A-Za-z0-9_]*$`) | `mysql -u root -e 'CREATE DATABASE IF NOT EXISTS \`nombre\`;'` |
| 🗑 Eliminar BD (MySQL) | Confirmación previa | `mysql -u root -e 'DROP DATABASE IF EXISTS \`nombre\`;'` |
| 💾 Backup (mysqldump) | Escribe a `~/backups/db/mysql_<nombre>_<timestamp>.sql` | `mysqldump -u root <nombre> > <archivo>` |
| 📥 Restaurar backup | Lista los `.sql` reales del directorio de backups, filtrados por motor | `mysql -u root <nombre> < <archivo>` |
| ＋ Crear BD (PostgreSQL) | Prompt de nombre (mismo regex) | `psql -U "$(whoami)" -d postgres -c 'CREATE DATABASE nombre;'` |
| 🗑 Eliminar BD (PostgreSQL) | Confirmación previa | `psql -U "$(whoami)" -d postgres -c 'DROP DATABASE IF EXISTS nombre;'` |
| 💾 Backup (pg_dump) | Escribe a `~/backups/db/postgres_<nombre>_<timestamp>.sql` | `pg_dump -U "$(whoami)" <nombre> > <archivo>` |
| 📥 Restaurar backup | Lista los `.sql` reales | `psql -U "$(whoami)" <nombre> < <archivo>` |

Defaults documentados: la instalación de MariaDB usa `--auth-root-authentication-method=normal` → root de MySQL sin contraseña; la inicialización de PostgreSQL usa `-U "$(whoami)"` → el superusuario de PostgreSQL es el usuario Linux de la app (no siempre `postgres`).

**SQLITE** — usa `android.database.sqlite.SQLiteDatabase.openDatabase()` (API de primera clase de Android, puede abrir cualquier `.db`/`.sqlite` del filesystem) — sin ProcessBuilder ni subproceso Python (salvo "Abrir BD interactivo", que sí lanza `sqlite3` en la terminal):

| Botón | Acción |
|---|---|
| 📋 Listar BDs en ~ | Recorre `~` (profundidad 3, salteando `node_modules`/`.git`/`venv`/…) + detecta la BD de n8n |
| 📂 Abrir BD (interactivo) | Prompt de ruta → abre el CLI real de `sqlite3` en la terminal |
| 📊 Ver tablas de una BD | Prompt de ruta → `SELECT name FROM sqlite_master WHERE type='table'` |
| ⚡ BD de n8n (acceso rápido) | Localiza y lista las tablas de la BD real de n8n |
| 📤 Exportar BD a CSV | Prompt ruta + tabla (`all` = todas) → escribe `<tabla>.csv` al lado del archivo |
| ＋ Crear nueva BD vacía | Crea `~/<nombre>.db` |
| 💾 Backup de una BD | Prompt de ruta → copia directa del archivo (SQLite guarda todo en un único archivo) |
| ⚠ Query SQL | Prompt ruta + SQL — autodetecta SELECT/PRAGMA/WITH/EXPLAIN (rawQuery, devuelve filas) vs el resto (execSQL, muestra `changes()`) |

## 7. Registry

```
db.installed=true
db.version=<versión del script instalador>
mysql.installed=true
mysql.version=<versión de mariadb, ej. 11.x>
postgres.installed=true
postgres.version=<versión de postgres, ej. 16.x>
sqlite.installed=true
sqlite.version=<versión de sqlite3, ej. 3.x>
redis.installed=true
redis.version=<versión de redis-server, ej. 7.x>
```

## 8. Notas técnicas

- **Cuatro motores, un switch**: `db` es un módulo con procesos persistentes independientes (mysqld/postgres/redis-server). La detección de "corriendo" usa OR (cualquiera de los tres) y el switch central detiene los tres.
- El script genera wrappers `start.sh`/`stop.sh` sin parámetros porque el mecanismo de arranque de módulos de la app ejecuta el script sin pasar argumentos.
- Hay un mapeo de fallback binario (`db` → `mariadbd`) para que el tab Módulos detecte instalación real incluso si el registry estuviera desincronizado.

## `DbSchemaFragment.kt` — "Estructura de la BD"

| Control | Qué hace |
|---|---|
| Botón cerrar | Vuelve a la pantalla anterior |
| Fila de chips de motor (SQLite/MySQL/PostgreSQL) | Cambia el motor seleccionado, recarga la lista de bases de ese motor — MySQL/PostgreSQL se consultan vía el cliente CLI real, SQLite 100% en proceso con la API nativa + `PRAGMA foreign_key_list` |
| Fila de chips de base de datos | Selecciona la base, carga su esquema |
| Chip "📂 Por categoría" / "🗺 Mapa mental" | Cambia entre lista de tablas agrupadas por categoría y el grafo visual (nodos=tablas, líneas=relaciones FK reales) |
