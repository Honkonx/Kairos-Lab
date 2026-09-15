# Database (`db`)

**Kairos module** — managed through the Kairos UI (Modules tab). Installation handled by the
app via `ProcessBuilder` → `modulos/db.sh`; the detail screen (`DbFragment.kt`) combines
status/control of the three servers (MySQL/MariaDB, PostgreSQL, Redis) with SQLite database
management.

---

**Script:** `modulos/db.sh` — mirrored at `app/src/main/assets/scripts/db.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DbFragment.kt`
**`id` in `modules.json`:** `db` — `hasSwitch: true` (persistent process: `mysqld`, `postgres`
and/or `redis-server`)

---

## 1. Overview

Groups **four** database engines into a single screen:

- **MySQL / MariaDB** — real server with its own datadir (`$PREFIX/var/lib/mysql`), manual
  start/stop from the app and via the module switch.
- **PostgreSQL** — real server with its own datadir (`$PREFIX/var/lib/postgresql`), manual
  start/stop from the app and via the module switch. **Known limitation**: on some Android
  devices, the PostgreSQL engine can't start at all — any single-process mode of `postgres`
  hangs reading an internal self-pipe/latch `socketpair` that never gets a response, apparently
  due to an interaction with Android's sandbox on that specific hardware. It's not a shared
  memory or standard-configuration issue — it's a real platform limitation, not a Kairos bug
  fixable with more retries. The app shows a non-blocking notice about this (not every device
  is affected); if PostgreSQL repeatedly fails on a device, MySQL/MariaDB or Redis are the
  alternative.
- **Redis** — real server, own datadir (`$PREFIX/var/lib/redis`), no auth by default (same
  criterion as the other engines — loopback-only). If the package installation fails, the step
  is non-critical (doesn't abort the rest of the script).
- **SQLite** — not a server (it's a file) — management of `.db`/`.sqlite` files (including
  n8n's, when it runs inside a proot container) using Android's native SQLite API, with no
  Python subprocess.

## 2. Permissions

- **Android**: none specific — uses the same Termux process (storage, granted in the first-run
  wizard). Requires no extra network permission, no root.
- **Internal Termux**: `mariadb` and `postgresql` are installed via `pkg` (native ARM64
  builds). The servers listen on loopback (`127.0.0.1`) — `mysqld` on `3306`, `postgres` on
  `5432` — accessible from the app for the n8n module (which can point its credentials there).

## 3. Installation logic (`modulos/db.sh`)

Installation script with the standard contract (SILENT/CHECKPOINT/REGISTRY) and the standard
flags:

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts, same `[OK]`/`[ERROR]` output |
| `--force` | Reinstalls even if already present (ignores the `command -v` guards) |
| `--describe` | JSON manifest: `{"id":"db","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |
| `--status` | JSON with the status of the four engines: `{mysql:{installed,running,version},postgres:{...},sqlite:{...},redis:{installed,running,version}}` |
| `--uninstall` | Uninstalls (removes packages, datadirs, scripts and registry keys) |
| `--start` / `--stop` | Starts / stops the 3 servers (mysql+postgres+redis, used by the wrappers) |

Steps: `pkg update/upgrade` → installs `mariadb` + `postgresql` + `redis` + `sqlite3` →
initializes datadirs if missing → writes the **control wrappers** to `~/scripts/db/` (see §5)
→ registry.

## 4. Status detection

- **Installation**: the registry — `db.installed=true`. The UI also verifies the real binaries
  (`mysqld`/`postgres`) with `pgrep -x`.
- **"Running"**: the module counts as running if any of the servers is alive (`mysqld` or
  `postgres` or `redis-server`).
- **Port**: `3306` (used as a reference to wait for the server to be ready).

## 5. Control scripts (`~/scripts/db/`)

Wrappers at `$HOME/scripts/db/`:

| Script | What it does |
|---|---|
| `mysql_start.sh` | Starts only MySQL/MariaDB (`mysqld_safe`/`mysqld`) |
| `mysql_stop.sh` | Stops only MySQL/MariaDB |
| `postgres_start.sh` | Starts only PostgreSQL |
| `postgres_stop.sh` | Stops only PostgreSQL |
| `redis_start.sh` | Starts only Redis (`redis-server --daemonize yes --dir "$REDIS_DATA"`) |
| `redis_stop.sh` | Stops only Redis (`redis-cli shutdown nosave`, fallback `pkill -f redis-server`) |
| `start.sh` | Starts all 3 (MySQL + PostgreSQL + Redis, in that order — wrapper for the central switch ON) |
| `stop.sh` | Stops all 3 (wrapper for the central switch OFF) |

## 6. Real app screen (`DbFragment.kt`)

Organized into **3 tabs**, grouped by type:

| Tab | Cards it groups |
|---|---|
| **Engines** | STATUS (the 4 engines + "🔁 Refresh status") + SERVERS (MySQL/PostgreSQL/Redis switches) + STRUCTURE ("🗺 Database Structure") |
| **SQLite** | SQLITE (the 8 `.db`/`.sqlite` file management actions) |
| **Backups** | MYSQL/MARIADB — DATABASES + POSTGRESQL — DATABASES (create/delete/backup/restore for each engine) |

The maintenance card (Update/Uninstall) sits outside the tabs, at the bottom of the screen.

**STATUS** (refreshes only when the screen opens and with "🔁 Refresh status"):

| Row | Source |
|---|---|
| MySQL/MariaDB | live `pgrep -x mysqld` + version from the registry |
| PostgreSQL | live `pgrep -x postgres` + version from the registry |
| SQLite | version from the registry |
| Redis | live `redis-server` process + version from the registry |

**SERVERS** — starts/stops each server separately. The script result is shown as a short
on-screen message. The Modules screen's central switch controls all 3 servers together; each
one's individual switch on this card is independent of that central switch.

**STRUCTURE** — "🗺 Database Structure" navigates to `DbSchemaFragment.kt`: a view of the real
schema (tables by category + a mind map with FK relations) for the three relational engines.

**MYSQL/MARIADB — DATABASES** and **POSTGRESQL — DATABASES** — with no JDBC driver in the app
(Android doesn't ship one), these run the real CLI (`mysql`/`psql`/`mysqldump`/`pg_dump`) via
`ProcessBuilder`, result shown as an on-screen message:

| Button | Action | Real command |
|---|---|---|
| ＋ Create DB (MySQL) | Name prompt (regex `^[A-Za-z][A-Za-z0-9_]*$`) | `mysql -u root -e 'CREATE DATABASE IF NOT EXISTS \`name\`;'` |
| 🗑 Delete DB (MySQL) | Prior confirmation | `mysql -u root -e 'DROP DATABASE IF EXISTS \`name\`;'` |
| 💾 Backup (mysqldump) | Writes to `~/backups/db/mysql_<name>_<timestamp>.sql` | `mysqldump -u root <name> > <file>` |
| 📥 Restore backup | Lists the real `.sql` files in the backup directory, filtered by engine | `mysql -u root <name> < <file>` |
| ＋ Create DB (PostgreSQL) | Name prompt (same regex) | `psql -U "$(whoami)" -d postgres -c 'CREATE DATABASE name;'` |
| 🗑 Delete DB (PostgreSQL) | Prior confirmation | `psql -U "$(whoami)" -d postgres -c 'DROP DATABASE IF EXISTS name;'` |
| 💾 Backup (pg_dump) | Writes to `~/backups/db/postgres_<name>_<timestamp>.sql` | `pg_dump -U "$(whoami)" <name> > <file>` |
| 📥 Restore backup | Lists the real `.sql` files | `psql -U "$(whoami)" <name> < <file>` |

Documented defaults: MariaDB's installation uses
`--auth-root-authentication-method=normal` → MySQL's root has no password; PostgreSQL's
initialization uses `-U "$(whoami)"` → PostgreSQL's superuser is the app's Linux user (not
always `postgres`).

**SQLITE** — uses `android.database.sqlite.SQLiteDatabase.openDatabase()` (Android's
first-class API, can open any `.db`/`.sqlite` on the filesystem) — no ProcessBuilder or Python
subprocess (except "Open DB interactively", which does launch `sqlite3` in the terminal):

| Button | Action |
|---|---|
| 📋 List DBs in ~ | Walks `~` (depth 3, skipping `node_modules`/`.git`/`venv`/…) + detects n8n's DB |
| 📂 Open DB (interactive) | Path prompt → opens the real `sqlite3` CLI in the terminal |
| 📊 View a DB's tables | Path prompt → `SELECT name FROM sqlite_master WHERE type='table'` |
| ⚡ n8n DB (quick access) | Locates and lists the tables of the real n8n database |
| 📤 Export DB to CSV | Path + table prompt (`all` = all tables) → writes `<table>.csv` next to the file |
| ＋ Create new empty DB | Creates `~/<name>.db` |
| 💾 Backup a DB | Path prompt → direct file copy (SQLite stores everything in a single file) |
| ⚠ SQL query | Path + SQL prompt — auto-detects SELECT/PRAGMA/WITH/EXPLAIN (rawQuery, returns rows) vs everything else (execSQL, shows `changes()`) |

## 7. Registry

```
db.installed=true
db.version=<installer script version>
mysql.installed=true
mysql.version=<mariadb version, e.g. 11.x>
postgres.installed=true
postgres.version=<postgres version, e.g. 16.x>
sqlite.installed=true
sqlite.version=<sqlite3 version, e.g. 3.x>
redis.installed=true
redis.version=<redis-server version, e.g. 7.x>
```

## 8. Technical notes

- **Four engines, one switch**: `db` is a module with independent persistent processes
  (mysqld/postgres/redis-server). "Running" detection uses OR (any of the three) and the
  central switch stops all three.
- The script generates parameter-less `start.sh`/`stop.sh` wrappers because the app's module
  startup mechanism runs the script without passing arguments.
- There's a fallback binary mapping (`db` → `mariadbd`) so the Modules tab detects real
  installation even if the registry were out of sync.

## `DbSchemaFragment.kt` — "Database Structure"

| Control | What it does |
|---|---|
| Close button | Returns to the previous screen |
| Engine chip row (SQLite/MySQL/PostgreSQL) | Switches the selected engine, reloads that engine's database list — MySQL/PostgreSQL are queried via the real CLI client, SQLite entirely in-process with the native API + `PRAGMA foreign_key_list` |
| Database chip row | Selects the database, loads its schema |
| "📂 By category" / "🗺 Mind map" chip | Switches between a table list grouped by category and the visual graph (nodes=tables, lines=real FK relations) |
