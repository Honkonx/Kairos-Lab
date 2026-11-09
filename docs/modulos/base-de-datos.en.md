# Database (`db`) — Kairos Module

**Kairos Module** — managed through the Kairos UI (Modules tab). Installation is handled by the app via `ProcessBuilder` → `modulos/db.sh`; the detail screen (`DbFragment.kt`) combines state/control of the servers (MariaDB, PostgreSQL, Redis; MongoDB without UI) with SQLite database management.

---

**Script:** `modulos/db.sh` (v1.2.1, 662 lines) — identical mirror at `app/src/main/assets/scripts/db.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DbFragment.kt` (993 lines) + `DbSchemaFragment.kt` (619 lines); navigation: `ModuleDetailNavigator.kt:37` → `DbFragment`
**`modules.json` (`:604-628`):** `id: db`, `script: db.sh`, `port: "3306"`, `category: db`, `installMethods: [standard]`, `catalogVersion: 1.2.1`, `hasSwitch: false`, `tmuxSession: ""`

---

## 1. What it is and what it is for

Local database servers + a SQLite manager: MariaDB, PostgreSQL, Redis and MongoDB (attempted) installed with `pkg`, start/stop scripts of their own, and a screen to control them, create/delete/back up/restore databases and work with SQLite files. It groups these engines into a single screen:

- **MySQL / MariaDB** — a real server with its own datadir (`$PREFIX/var/lib/mysql`), manual start/stop from the app.
- **PostgreSQL** — a real server with its own datadir (`$PREFIX/var/lib/postgresql`). **Known limitation**: on at least one real device (confirmed with `strace`), the PostgreSQL engine **cannot start at all** — any single-process mode of `postgres` (`--check`, and the internal equivalent that `initdb` triggers) hangs reading an internal self-pipe/latch `socketpair` that never receives a reply, apparently due to an interaction with the Android sandbox on that hardware. It is not a shared-memory problem (`libandroid-shmem` already solves that) nor a configuration one — `dynamic_shared_memory_type=mmap` hangs too. `DbFragment.kt` shows an amber banner warning about this (non-blocking, not every device suffers it) and `db.sh` detects/cleans a datadir corrupted by an interrupted `initdb`. **If PostgreSQL fails repeatedly on a device, use MySQL/MariaDB or Redis instead.**
- **Redis** (the official `redis` package from Termux's main repo) — a real server, its own datadir (`$PREFIX/var/lib/redis`), no auth by default. If `pkg install redis` fails, the step stays at `warn` (non-critical, it does not abort the rest of `db.sh`).
- **MongoDB** — an installation attempt with its own scripts (`mongo_start.sh`/`mongo_stop.sh`) included in `start.sh`/`stop.sh`, but **with no row or switch in the UI**.
- **SQLite** — not a server (it is a file) — management of `.db`/`.sqlite` files (including n8n's inside the proot rootfs) using the Android framework's own `android.database.sqlite.SQLiteDatabase`, with no Python subprocess.

**Classification: FUNCTIONAL MODULE.** A real persistent service (processes `mariadbd`/`postgres`/`redis-server`/`mongod`), per-engine state, three tabs and real operations on data. ~27 UI options (4 status rows + refresh; 3 server switches; structure; 8 SQLite actions; per SQL engine: create/delete/backup/restore = 8; Update/Uninstall maintenance from `BaseModuleFragment`; `DbSchemaFragment`: engine/database/view chips).

## 2. Permissions

- **Android**: none specific — it uses the same Termux process (storage, granted in the wizard). It needs no extra `INTERNET`, no root.
- **Termux internals**: `mariadb`, `postgresql`, `redis` are installed via `pkg` (native ARM64 builds). Servers listen on loopback (`127.0.0.1`) — `mysqld` on `3306`, `postgres` on `5432`, `redis` on `6379`, Mongo on `27017` (`--bind_ip 127.0.0.1`, `db.sh:571`) — reachable from the app for the n8n module (which can point its credentials there).

## 3. Installation logic (`modulos/db.sh`)

A script with the standard `lib.sh` contract (SILENT/CHECKPOINT/REGISTRY). Flags (`db.sh:88-94`):

| Flag | What it does |
|---|---|
| `--silent` | App mode: no prompts, same `[OK]`/`[ERROR]` output |
| `--force` | Reinstalls even if already installed (ignores the `command -v` guards) |
| `--describe` / `--describe-files` | JSON manifests (`{"id":"db","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}`) |
| `--status` | JSON with the state of each engine (`installed`, `running`, `version`) |
| `--uninstall` | Runs `pkill -f mariadbd/postgres/redis-server/mongod` and deletes the registry and `~/scripts/db`; it does **not** uninstall packages or delete datadirs (`db.sh:~196-205`); note: `pkill -f postgres` matches a broad pattern |
| `--start` / `--stop` | Starts / stops the servers (`db.sh:~207-227`) |

`TOTAL_STEPS=7` (`db.sh:281`): Termux update → `pkg install mariadb` → `postgresql` → `redis` → `mongodb` (non-critical, `warn`) → sqlite3 + write wrappers in `~/scripts/db/` → registry.

- Verification: after each `pkg install`, `command -v <binary>` (`db.sh:~322`); it does **not** run `--version` via `verify_binary_installed`. The registry (`update_registry`, `db.sh:225-238`) does condition `installed=true` on the binary responding to `--version` (non-empty version), per engine.
- Datadirs: MariaDB `mariadb-install-db --auth-root-authentication-method=normal` → root **without a password** (`db.sh:413`); PostgreSQL `initdb -D ... -U "$(whoami)"` (the superuser is the app's Linux user, not always `postgres`) wrapped in `timeout --kill-after=10 45` (`db.sh:479`), with cleanup of a previously corrupted datadir (`rm -rf "$PGSQL_DATA"`, `db.sh:465`, only if there is no `PG_VERSION`); Redis and Mongo without auth.
- No sha256 or external downloads: everything comes from `pkg` (Termux repos).
- **Idempotence**: `DB_CONFIGURED` also requires `mongod` (`db.sh:245-246`). Since, per the script's own header, the `mongodb` package does not exist in Termux repos (a claim by the script, not reproduced), `DB_CONFIGURED` would always stay `false` and the script never takes the "already installed" shortcut.
- The final message of `--start`/`--stop` uses `[ $START ]`, which with `START=false` (a non-empty string) evaluates true → it always prints "Servers started" even for `--stop` (cosmetic).

## 4. Execution and state detection

- Wrappers `~/scripts/db/{mysql,postgres,redis,mongo}_{start,stop}.sh`, plus `start.sh`/`stop.sh` which start/stop all **4** (`db.sh:~595-626`; `ModuleController.kt:1171,1190`) — `ModuleController.startModule()` runs `bash <script>` with no arguments, which is why `db.sh` generates parameterless wrappers instead of the app calling `db.sh --start`.
- Process detection with `pgrep -f` (not `-x`), `pg_isready` for Postgres. Redis starts with `redis-server --daemonize yes --dir ... --logfile ~/redis.log` with no config of its own (`db.sh:532`): it inherits Redis defaults (protected-mode) — whether it listens only on loopback is not verified on a device. `mariadbd --datadir ... --skip-grant-tables=false` has no explicit `--bind-address` (`db.sh:~419`): the bind depends on Termux's my.cnf (inference).
- `ModuleController.isRunning("db")` = `checkPort(3306) || checkPort(5432) || checkPort(6379)` (`ModuleController.kt:357-359`): it uses **ports**, not `isProcessAlive`; it **does not include 27017**. `getModulePort("db")` = 3306 (`:1241`). There is no tmux and no central switch (`hasSwitch:false`).
- **Installation**: registry (`~/.android_server_registry`, `db.installed=true`); `ModuleInstalled` has a binary fallback (`BINARY_FALLBACK` maps `db` → `mariadbd`) so the Modules tab detects a real installation even if the registry is out of sync. If neither `mysqld` nor `postgres` exists (or `db.installed != true`), `DbFragment` shows the standard "Module not installed".
- Logs: `~/postgres.log`, `~/redis.log`, `~/mongo.log`.

## 5. Control scripts (`~/scripts/db/`, 10 files)

| Script | What it does |
|---|---|
| `mysql_start.sh` / `mysql_stop.sh` | Starts / stops only MySQL/MariaDB (`mysqld_safe`/`mysqld`) |
| `postgres_start.sh` / `postgres_stop.sh` | Starts / stops only PostgreSQL |
| `redis_start.sh` / `redis_stop.sh` | Starts (`redis-server --daemonize yes --dir "$REDIS_DATA"`) / stops (`redis-cli shutdown nosave`, fallback `pkill -f redis-server`) only Redis |
| `mongo_start.sh` / `mongo_stop.sh` | Starts / stops only MongoDB |
| `start.sh` / `stop.sh` | Start / stop all 4 engines (wrapper for the ON/OFF switch, `getModuleStartScript("db")` / `getModuleStopInfo("db")`) |

## 6. App screen

`getModuleId()` → `"db"`, `getModuleName()` → `"Base de Datos"`. Three tabs (`setupTabs`, `DbFragment.kt:66-75`, helper `BaseModuleFragment.setupTabs()`); the maintenance card (Update/Uninstall, `addMaintenanceCard()`) stays OUTSIDE the tabs, at the bottom of the screen.

| Tab | Cards it groups |
|---|---|
| **Engines** (`buildMotoresTab`, `:87`) | STATUS (MySQL/PostgreSQL/SQLite/Redis — **Mongo does not appear** — + "🔁 Refresh status") + SERVERS (MySQL/PostgreSQL/Redis switches) + STRUCTURE ("🗺 Database structure") |
| **SQLite** (`buildSqliteTab`, `:143`) | SQLITE: 4 visible buttons (list DBs, explore/open, n8n DB, create empty); the other actions (view tables, export CSV, backup, SQL query, open in terminal) live in the menu opened by `openDbFileMenu` (`:581`) |
| **Backups** (`buildBackupsTab`, `:152`) | MYSQL/MARIADB — DATABASES + POSTGRESQL — DATABASES (create/delete/backup/restore for each engine) |

**STATUS** (refreshed on opening the screen and with "🔁 Refresh status"):

| Row | Source |
|---|---|
| MySQL/MariaDB | live `pgrep` + `mysql.version` from the registry |
| PostgreSQL | live `pgrep` + `postgres.version` from the registry |
| SQLite | `sqlite.version` from the registry |
| Redis | live `isAlive("redis-server")` + `redis.version` from the registry |

**SERVERS** — independent switches for MySQL/MariaDB, PostgreSQL (with the amber notice about the limitation above) and Redis: `startServer`/`stopServer` → `runServerScript` runs `bash ~/scripts/db/<engine>_start|stop.sh` with `ProcessBuilder` (`:262-288`); the result is shown in a Snackbar. They are independent engines running in parallel, not mutually exclusive variants.

**STRUCTURE** — "🗺 Database structure" navigates to `DbSchemaFragment.kt`: a view of the real schema (tables by category + a mind map with FK relationships) for the three engines.

**MYSQL/MARIADB — DATABASES** and **POSTGRESQL — DATABASES** — with no JDBC driver in the app (Android does not ship one), they run the real CLI (`mysql`/`psql`/`mysqldump`/`pg_dump`, the binaries `db.sh` installs) via `ProcessBuilder(bash -c)` (`runShellCommand`, `:337`) in a `Thread`, result in a Snackbar. DB name validated with the regex `^[A-Za-z][A-Za-z0-9_]*$` (`:302`). Backups go to `~/backups/db/`:

| Button | Action | Real command |
|---|---|---|
| ＋ Create DB (MySQL) | Name prompt | `mysql -u root -e 'CREATE DATABASE IF NOT EXISTS \`name\`;'` |
| 🗑 Delete DB (MySQL) | Prior confirmation ("cannot be undone") | `mysql -u root -e 'DROP DATABASE IF EXISTS \`name\`;'` |
| 💾 Backup (mysqldump) | Writes to `~/backups/db/mysql_<name>_<timestamp>.sql` | `mysqldump -u root <name> > <file>` |
| ＋ Create DB (PostgreSQL) | Name prompt (same regex) | `psql -U "$(whoami)" -d postgres -c 'CREATE DATABASE name;'` |
| 🗑 Delete DB (PostgreSQL) | Prior confirmation | `psql -U "$(whoami)" -d postgres -c 'DROP DATABASE IF EXISTS name;'` |
| 💾 Backup (pg_dump) | Writes to `~/backups/db/postgres_<name>_<timestamp>.sql` | `pg_dump -U "$(whoami)" <name> > <file>` |
| 📥 Restore backup (MySQL and PostgreSQL) | `promptRestoreBackup()`/`restoreMysqlDb()`/`restorePgDb()` — lists the `.sql` files in `~/backups/db` filtered by engine, suggests the original DB name from the file name (`<engine>_<name>_<timestamp>.sql`, goes through the same regex, `:398`), creates the target DB if it does not exist and runs `mysql`/`psql` to import the `.sql` | — |

**SQLITE** — uses `android.database.sqlite.SQLiteDatabase.openDatabase()` (it can open any `.db`/`.sqlite` on the filesystem), with no Python subprocess (except "Open DB (interactive)", which launches `sqlite3` in the terminal with `shellQuote`). All actions run in a `Thread` and report via Snackbar:

| Button | Action |
|---|---|
| 📋 List DBs in ~ | Walks `~` (depth 3, skipping `node_modules`/`.git`/`venv`/…) + detects n8n's DB (`rootfs/debian/root/.n8n/database.sqlite`) |
| 📂 Open DB (interactive) | Path prompt → `launchTerminalCommand("sqlite3 <path>")` |
| 📊 View a DB's tables | Path prompt → `SELECT name FROM sqlite_master WHERE type='table'` |
| ⚡ n8n DB (quick access) | Locates and lists the tables of n8n's real DB inside the proot rootfs |
| 📤 Export DB to CSV | Prompt for path + table (`all` = all) → writes `<table>.csv` next to the file |
| ＋ Create new empty DB | Creates `~/<name>.db` |
| 💾 Back up a DB | Path prompt → `File.copyTo()` to `~/backups/db/<name>_<timestamp>.bak` (direct copy of the single file) |
| ⚠ SQL query | Prompt for path + SQL — autodetects SELECT/PRAGMA/WITH/EXPLAIN (`rawQuery`, returns rows) vs the rest (`execSQL`, shows `changes()`) |

**`DbSchemaFragment.kt` — "Database structure"**:

| Control | What it does |
|---|---|
| Close button (X, top left) | `parentFragmentManager.popBackStack()` |
| Engine chip row (SQLite/MySQL/PostgreSQL) | Changes `selectedEngine`, reloads that engine's database list. MySQL/PostgreSQL are queried via the real CLI client; SQLite 100% in-process with `SQLiteDatabase` + `PRAGMA foreign_key_list` |
| Database chip row | Selects `selectedDatabase`, loads its schema |
| "📂 By category" / "🗺 Mind map" chip | Switches `viewMode` between a list of tables grouped by category and the visual graph (`SchemaGraphView`, a Canvas with nodes=tables and lines=real FK relationships; no drag/zoom/pan of its own) |

There is no `mysql`/`psql`/`redis-cli`/`mongosh` shell from the UI ("Open in terminal" is only for interactive SQLite).

## 7. Files and registry

`~/scripts/db/*.sh` (10 files); datadirs `$PREFIX/var/lib/{mysql,postgresql,redis,mongodb}`; `~/backups/db/*.sql|*.bak`; logs in `~`; `lib.sh` checkpoint; CSV exported next to the source DB.

Registry (`~/.android_server_registry`): `db.installed`, `db.version`, `mysql.installed/version`, `postgres.installed/version`, `sqlite.installed/version`, `redis.installed/version`, `mongo.installed/version`.

## 8. Security

- No authentication on MariaDB (root without a password), PostgreSQL (user = the app's Linux user) and Redis/Mongo by default: isolation depends on listening on loopback. Mongo explicitly sets `127.0.0.1`; MariaDB/Redis do **not** in the scripts. The data lives in the app's private space.
- Injection: DB names are validated by regex before being interpolated into `mysql -e`/`psql -c`; paths use `shellQuote`. No `curl|bash`.
- `.sql` backups stay in clear text in `~/backups/db/` (default permissions, unencrypted).

## 9. Known limitations

- `ModuleController.isRunning("db")` is computed by ports and does not include 27017.
- MongoDB has scripts and is part of `start.sh`/`stop.sh` but has no row or switch in the UI; the `description` in `modules.json` advertises "Redis + MongoDB", consistent with the script but not with the UI.
- Idempotence broken by requiring `mongod` (see §3).
- `--stop` prints "started" (cosmetic).
- PostgreSQL may not start on certain devices (see §1).
- Inference: that the `mongodb` package really does not exist (claimed by the script, not reproduced); that the `pkill -f postgres` of `--uninstall` may kill unrelated processes with "postgres" in their command line.
- **Sync**: `modulos/db.sh` ↔ `app/src/main/assets/scripts/db.sh` must be kept identical.

## 10. Date added

`modulos/db.sh`: **2026-08-11**.
