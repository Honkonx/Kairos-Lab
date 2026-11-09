# Stacks / Entornos de Prueba — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (pestaña Módulos, pantalla "Entornos de Prueba"). A diferencia del resto de `modulos/*.sh`, `stacks.sh` **no instala un paquete propio** — es un catálogo que reutiliza módulos ya existentes (`python.sh`, `db.sh`) y agrega Node.js/PHP/Go/Rust/Java directo vía `pkg`.

---

**Proyecto:** Kairos
**Script:** `modulos/stacks.sh` (v1.5.0) — idéntico a `app/src/main/assets/scripts/stacks.sh`
**Fragments:** `app/src/main/java/com/termux/app/ui/StacksFragment.kt` (proyectos reales, enrutado desde `ModuleDetailNavigator.kt:38`) + `StacksPackagesFragment.kt` ("📦 Paquetes necesarios", presets)
**`id` en `modules.json`:** `stacks` — `category: dev`, `installMethods: [pkg, proot-distro]`, `port: ""`, `hasSwitch: false`, `catalogVersion: 1.1.0`. Sin switch central: cada preset/acción invoca el script directo por `ProcessBuilder`, no pasa por `ModuleController.installModule()`.

> Basado en la lectura de `app/src/main/assets/modules.json`, `modulos/stacks.sh` (1122 líneas), `StacksFragment.kt` (1014), `StacksPackagesFragment.kt` (336) y `ModuleController.kt`.

---

## 1. Qué es y para qué sirve

Catálogo de "Entornos de Prueba" para desarrollo dentro del teléfono, con dos modos:

1. **Presets** (recetas prearmadas que instalan un lenguaje/stack, nativo en Termux o dentro de una distro proot) — viven en `StacksPackagesFragment`, accesible desde el detalle de un proyecto ("📦 Paquetes necesarios").
2. **Proyectos reales** — carpetas del usuario a las que se les detecta el stack por los archivos presentes, se les instalan las dependencias reales (`npm install` / `venv+pip`) y se las ejecuta en segundo plano (tmux) en 3 destinos (nativo / distro proot / contenedor udocker), con opción de exponerlas por Cloudflare Tunnel.

## 2. Clasificación: MÓDULO FUNCIONAL

No es solo "instalar y abrir terminal": tiene UI propia con estado persistente (SharedPreferences `kairos_stacks_prefs`: `project_paths`, `project_states`, `project_subdirs`), gestión de múltiples proyectos, monorepos, ejecución con switch en segundo plano, logs en vivo y túnel. Opciones de UI aproximadas: **~20** (lista de proyectos con agregar/quitar; detectar subproyectos; "Iniciar todos" con retardo; Paquetes necesarios; dropdown destino; selector de distro; modo udocker simple/full; instalar dependencias; campo de comando; switch background; ver logs (+terminal completo); exponer Cloudflare; y en `StacksPackagesFragment` 9 filas de preset con botón nativo, botón distro, selector de extras y selector de flavor). Al no tener `hasSwitch` ni proceso propio de módulo, `ModuleController` no tiene ninguna entrada `"stacks"`: el "estado" vive por proyecto, no por módulo.

## 3. Permisos

Ninguno propio — usa los permisos ya otorgados por el wizard de Kairos (almacenamiento para el navegador de carpetas). No abre puertos propios (los procesos que arranca sí, según el comando del usuario).

## 4. Instalación (`modulos/stacks.sh`)

- `stacks.sh --silent` sin `--preset` es un no-op que solo escribe el registry con `presets=python-postgres,php-mysql,react-vite,html,go,rust,java,dotnet,linux-completo` (`stacks.sh:723-731`).
- Flags (`stacks.sh:149-162`): `--silent --force --describe --describe-files --preset --distro --flavor --extra --project-path --project-action --project-target --project-distro --project-cmd --project-udocker-mode`.
- El `catalogVersion` de `modules.json` (1.1.0) está desfasado respecto al script (1.5.0); el banner interactivo del script aún dice `v1.1.0`.

### Presets (`VALID_PRESETS`, `stacks.sh:723`) — 9

| Preset | Nativo (Termux, `pkg`) | Distro (`apt-get` dentro de proot) |
|---|---|---|
| `python-postgres` | `python` + PostgreSQL vía `db.sh` encadenado | `python3 python3-pip postgresql` |
| `php-mysql` | `php` + MySQL/MariaDB vía `db.sh` encadenado | `php php-mysql mysql-server` |
| `react-vite` | `nodejs` (incluye npm — cubre React/Vite/TypeScript/JavaScript) | `nodejs npm` |
| `html` | `python` (sin build, `python3 -m http.server`) | `python3` |
| `go` | `golang` → binario `go` | `golang-go` |
| `rust` | `rust` → binario `cargo` | `rustc cargo` |
| `java` | `openjdk-21` → `java`/`javac` | `default-jdk` |
| `dotnet` | **Sin paquete nativo** (el SDK de .NET solo se empaqueta para Ubuntu/Debian reales vía apt, no para Bionic/Termux) — error si `TARGET != distro` (`stacks.sh:747`) | `dotnet-sdk-8.0` (LTS). Compilar puede fallar en algunos dispositivos por un SIGSEGV conocido de `csc.dll` en ARM64/proot, limitación de .NET en este entorno |
| `linux-completo` | — (SIEMPRE proot-distro) instala una distro Linux real completa (Debian Bookworm por defecto, o Ubuntu con `--flavor ubuntu`); pide flavor debian/ubuntu | — |

- **Destino** (`--distro <nombre>`): si se pasa, instala DENTRO de una distro proot ya instalada (`proot-distro login <nombre> -- apt-get install`) en vez de nativo. La UI deshabilita el botón "instalar en distro" si no hay ninguna distro instalada (`EntornoNative.distroList()`). `modulos/stacks.sh` tiene además guards reales: "proot-distro no está instalado — instalá el módulo Entorno primero" y "udocker no está instalado — instalá el módulo udocker primero".
- **Base de datos encadenada**: `python-postgres`/`php-mysql` nativos corren `bash db.sh --silent` (`ensure_db_native`); `db.sh` es idempotente. Si no está disponible junto a `stacks.sh`, solo avisa y sigue.
- **Resumen final**: cada preset imprime líneas `[RESUMEN]` con los comandos reales para arrancar cada pieza (ej. `python3 tu_app.py`, `bash ~/scripts/db/postgres_start.sh`); la UI las filtra y muestra en el diálogo de éxito (`ProgressDialogController.success()`).
- **Verificación**: `install_native_preset` hace `command -v <binario>` tras el `pkg install` (`stacks.sh:~890-893`); no usa `verify_binary_installed()` (no ejecuta `--version`). En distro no verifica el binario resultante, solo el código de salida de `apt-get`.
- **`--extra`** (catálogo `extra_packages_catalog`): `pip install --user` / `npm install -g` (o `pip3 --break-system-packages` / `npm -g` dentro de la distro). Extras: `python-postgres`: fastapi, django, flask, uvicorn, sqlalchemy, requests, numpy, pandas; `react-vite`: express, next, vue, axios; `php-mysql`: laravel (instala Composer solo en distro).

## 5. Modo Proyecto real (`--project-path`)

Flags: `--project-path <carpeta> --project-action detect|install|start|stop|status|logs [--project-target native|distro|udocker] [--project-distro <nombre>] [--project-cmd <comando>] [--project-udocker-mode simple|full] --silent`.

### 5.1 Detección de stack

Detector simple basado en archivos presentes (deliberadamente no exhaustivo), implementado por duplicado por performance (evita spawnear un proceso para chequear 4-5 archivos): `project_detect()` en `stacks.sh` y `detectProjectStack()` en el Fragment.

| Señal en la carpeta | Tag |
|---|---|
| `package.json` | `node` |
| `requirements.txt` o algún `*.py` | `python` |
| `*.db`/`*.sqlite`/`*.sqlite3` | `sqlite` |
| `composer.json` (o marcador amplio PHP) | `php` |
| `index.html` sin `package.json` | `html` |

### 5.2 Destinos (`--project-target`)

| Destino | Qué hace realmente |
|---|---|
| `native` (default) | `npm install` si hay `package.json`; `python3 -m venv venv` + `pip install -r requirements.txt` si hay Python. Instala Node/Python si faltan |
| `distro` | Instala Debian automáticamente si falta (`_ensure_distro_installed`), copia el proyecto DENTRO del rootfs (`$PREFIX/var/lib/proot-distro/installed-rootfs/<distro>/root/<nombre>`; **borra antes** `rm -rf $rootfs/root/$name`), ejecuta `_install_essentials` (set apt `_ESSENTIAL_APT_PACKAGES`: build-essential, cmake, python3 +pip/venv, sqlite3, postgresql, mariadb-server, nodejs, npm, php...; más el `.deb` de cloudflared) con `dpkg --configure -a` previo como self-heal, y corre `npm install`/`pip3 install -r requirements.txt` DENTRO de la distro (`proot-distro login <distro> -- ...`). `apt-get`/`npm`/`pip` sobre proot son más lentos |
| `udocker` | El proyecto NO se copia — se monta con **bind-mount** (`udocker run --volume=<host>:/app`) sobre una imagen oficial de Docker Hub: modo `simple` (`node:20` / `python:3.12` / `php:8.3-cli`; `html` cae a `python:3.12`) o modo `full` (`debian:bookworm` + esenciales). Nombre de contenedor `kairos-proj-<hash>` |

`composer.json` (PHP) se detecta en los 3 destinos pero **Composer no se automatiza** — solo se avisa (`warn`) que hay que correr `composer install` a mano.

### 5.3 Arrancar / detener / estado / logs

- **`start`**: requiere `--project-cmd`. Arranca en `tmux new-session -d -s proj-<md5[:10] de la ruta> "<cmd> 2>&1 | tee ~/kairos_logs/stacks_project_<hash>.log"` (`stacks.sh:666`), con el comando envuelto según el destino: nativo `cd '<p>' && <cmd>`; distro `proot-distro login <d> -- bash -c "cd '<p>' && <cmd>"`; udocker `udocker run --volume='<p>':/app ... sh -c "cd /app && <cmd>"`.
- **`stop`**: `tmux kill-session -t proj-<hash>`.
- **`status`**: `tmux has-session` → `[RESUMEN] running=true|false session=... log=...`.
- **`logs`**: `tail -n 200` del log persistente.
- La UI llama al script con timeouts: install 900 s, start 30 s, stop/status 15 s.

## 6. Pantalla real de la app

### `StacksFragment.kt` — proyectos

- **Lista de proyectos** (`buildProjectListState`): fila = nombre + stack detectado + estado; tocar el chip activa esa carpeta; "✕" la quita de la lista (no borra del disco). "+ Agregar carpeta de proyecto" (`pickProjectFolder`: raíz Home de Termux o almacenamiento interno, navega carpetas reales con File I/O directo, muestra la detección como confirmación).
- **Detalle de proyecto** (`buildProjectDetailState`):
  - Card "DETECCIÓN" (solo lectura): stack detectado + recomendación de destino (PHP recomienda distro; el resto, nativo).
  - "📦 Paquetes necesarios" → `StacksPackagesFragment`.
  - "Detectar subproyectos" (monorepo): subproyectos de 1er nivel con señal de stack, cada uno se comporta como proyecto independiente; "Iniciar todos" (`startAllSubProjects`) con retardo opcional.
  - Dropdown "Destino" (Nativo/Distro/udocker): "Distro" abre `pickDistroTarget()` (ofrece instalar Debian si no hay ninguna distro); modo udocker simple/full.
  - "⬇ Instalar dependencias reales" → `stacks.sh --project-action install`; el mensaje de confirmación cambia según el destino.
  - Campo "comando a ejecutar", pre-rellenado con una sugerencia según el stack (`suggestedRunCommand()`): `npm run dev -- --host` / `python3 app.py` / `php -S 0.0.0.0:8080 -t .` / `python3 -m http.server 8080`.
  - Switch "Proceso en background" → `startProject()`/`stopProject()` (tmux); revierte a `false` si falla la validación (comando vacío, distro sin elegir).
  - "📋 Ver logs en vivo": panel nativo con poll cada 2 s de `tail -n 150`; el botón "Terminal completo" abre `tail -f` en la terminal. El poll se detiene al cerrar el diálogo.
  - "🌐 Exponer con Cloudflare (túnel)": pide el puerto y llama `TunnelManager.start(puerto, "cloudflared", null)`.

### `StacksPackagesFragment.kt` — presets

9 filas de preset con botón "⚙ Instalar nativo (Termux)" y "📦 Instalar en distro…" (elige distro si hay más de una; deshabilitado si no hay ninguna). `dotnet` no ofrece instalación nativa: tocarlo abre directamente el selector de distro. Todo se traduce a `stacks.sh --preset ... [--distro|--flavor|--extra]` con timeout 900 s. No hay botón "Abrir en terminal" dedicado; solo `launchTerminalCommand` para los logs.

## 7. Detección de estado

- **Instalación**: registry (`stacks.installed`) — se escribe `true` incluso sin ningún preset corrido.
- **"Corriendo"**: no aplica a nivel de módulo; cada proyecto tiene su propio estado vía `tmux has-session`.

## 8. Registry (`~/.android_server_registry`)

```
stacks.installed=true
stacks.version=1.5.0
stacks.presets=python-postgres,php-mysql,react-vite,html,go,rust,java,dotnet,linux-completo   # solo si se corrió sin --preset
stacks.last_preset=<preset>       # tras correr un preset con --preset
stacks.last_target=native|distro|fulldistro
stacks.last_distro=<nombre o vacío>
stacks.last_flavor=debian|ubuntu
stacks.last_extra=<extras>
```

## 9. Archivos y rutas que crea

`~/kairos_logs/stacks_project_<hash>.log` (ejecución) y `..._install.log` (instalación); `<rootfs>/root/<nombre-proyecto>/` dentro de la distro (copia); contenedores udocker `kairos-proj-<hash>`; sesiones tmux `proj-<hash>`; `venv/` y `node_modules/` dentro de la carpeta del proyecto (nativo); SharedPreferences `kairos_stacks_prefs`; registry de módulos. La UI invoca el script desde `~/scripts/install/stacks.sh`.

## 10. Seguridad

- Secretos: el módulo no guarda ninguno. Cloudflare Tunnel expone el puerto al público sin autenticación propia (decisión del usuario al pulsar).
- **Ejecución de comandos arbitrarios por diseño**: el texto del campo de comando va tal cual dentro de `bash -c`/tmux; la **ruta del proyecto se interpola entre comillas simples** (`cd '$p'`): una carpeta con `'` en el nombre rompería el comando (robustez, no escalada de privilegios, porque el usuario ya controla el comando).
- `curl -sS https://getcomposer.org/installer | php` (`stacks.sh:1003`) es un patrón curl|intérprete sin hash; el `.deb` de cloudflared se baja por `releases/latest/download` sin hash (`stacks.sh:392,429`). Sin sha256 de lo que descarga fuera de apt/pkg.
- `--extra` instala paquetes pip/npm con el nombre recibido sin validar contra el catálogo (la UI solo envía los del catálogo, pero el script acepta cualquier valor).
- Permisos: no hace `chmod`; usa los permisos de almacenamiento ya otorgados por el wizard.

## 11. Notas de diseño y limitaciones

- `linux-completo` también puede instalarse desde el módulo **Entorno** ("📦 Instalar distro"), que cubre el mismo caso con su propio flujo.
- **udocker NO expande `$HOME` en `--volume`** (ver `UDOCKER.md`) — requiere ruta absoluta, que ya es lo que trae `$PROJECT_PATH` desde el navegador de carpetas.
- **`udocker cp` descartado deliberadamente**: se prefirió bind-mount (`--volume`), confirmado en producción (variante udocker de `n8n.sh`).
- **Docker real no es una opción**: sin root, `dockerd` (namespaces/cgroups del kernel) no es posible en Android; udocker (vía PRoot) es la alternativa de "contenedores" en este entorno (ver cabecera de `modulos/docker.sh`).
- **`project_start` con `target=distro`** hace `cd '$p'` con la ruta del **host**, mientras `project_install_distro` copió el proyecto a `/root/<nombre>` dentro del rootfs (`stacks.sh:459-466` vs `656`). A menos que proot exponga esa ruta del host dentro de la distro (no verificado), el arranque en distro apuntaría a una carpeta distinta de la instalada. Es un hecho de código; el efecto en dispositivo no está verificado.
- Composer no se automatiza en proyectos (solo `warn`), y `--extra laravel` solo funciona en distro.
- Inferencias (no verificadas): que el `cp -r` completo de proyectos grandes (con `node_modules`) a la distro sea lento; que el `.deb` de cloudflared falle en distros no derivadas de Debian (se asume apt).

## 12. Fecha de alta

`modulos/stacks.sh`: **2026-08-13**.
