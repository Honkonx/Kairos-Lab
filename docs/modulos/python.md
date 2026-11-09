# Python — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (pestaña Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/python.sh`; toda interacción en runtime (info, pip, REPL, scripts) corre directo desde `PythonFragment.kt` sin pasar por el script de instalación.

---

**Proyecto:** Kairos
**Script:** `modulos/python.sh` (v3.0.0) — espejo en `app/src/main/assets/scripts/python.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/PythonFragment.kt`
**`id` en `modules.json`:** `python` — `hasSwitch: false` (herramienta CLI, sin proceso propio que arrancar/detener)

---

## 1. Descripción General

Python es la base de scripting nativa del stack — Python 3 (el paquete `python` real de Termux, no una versión fija) más un conjunto amplio de librerías científicas/de red, SQLite integrado, y una estructura de carpetas (`~/sports`, `~/trading`, `~/bots`) usada por scripts descargados del repo `Honkonx/termux-ai-stack` (bots de Telegram, señales de trading, archivo de imágenes con visión).

No es un servicio con proceso persistente — no tiene switch ON/OFF (`hasSwitch: false` en `modules.json`), es una herramienta CLI que otros módulos (n8n para scripts de visión, el módulo Base de Datos para SQLite) y el propio usuario (REPL, `pip install`, correr scripts `.py`) usan bajo demanda.

La gestión de bases de datos (incluida la SQLite que viene con Python) vive en el módulo **Base de Datos** (`id: "db"`, ver `docs/modulos/base-de-datos.md`). La versión de SQLite se reporta como dato dentro de la info de Python (`import sqlite3`), pero no hay botón propio de SQLite en esta pantalla.

## 2. Permisos

- **Android**: ninguno específico de este módulo — usa el mismo proceso Termux que ya tiene sus permisos base (almacenamiento, otorgado en el wizard). No requiere `INTERNET` extra (ya lo tiene la app), no requiere overlay ni notificaciones.
- **Termux interno**: ninguno más allá de la instalación de paquetes vía `pkg`/`pip` — no toca `sshd`, no abre puertos, no requiere root.

## 3. Lógica de instalación completa (`modulos/python.sh`)

Script de 10 pasos, con checkpoints (`check_done`/`mark_done`, archivo `$HOME/.install_python_checkpoint`) para poder reanudar tras una interrupción sin repetir pasos ya hechos:

| Paso | Qué hace | Checkpoint |
|---|---|---|
| 1 | `pkg update && pkg upgrade` (con fallback a 3 mirrors si el principal falla: `packages.termux.dev`, `mirror.accum.se`, `mirrors.tuna.tsinghua.edu.cn`) | `termux_update` |
| 2 | Instala `tur-repo` (Termux User Repository) | `tur_repo` |
| 3 | Instala `python` (pkg) + actualiza `pip` | `python_install` — **verifica que `python3` responda de verdad antes de saltar el paso** (ver sección 7) |
| 4 | `proot-distro`, `binutils`, `libopenblas`, `clang` | `system_pkgs` |
| 5 | SQLite CLI + deps de imagen (`libjpeg-turbo`, `libpng`, `zlib`) | `sqlite_image_deps` |
| 6 | 12 paquetes pip, en orden estricto: `six`, `certifi`, `idna`, `urllib3`, `charset-normalizer`, `soupsieve`, `typing_extensions`, `python-dateutil`, `beautifulsoup4`, `requests`, `websockets`, `pillow` | `pip_packages` — cada paquete se intenta individualmente, uno que falla no aborta el resto |
| 7 | `numpy`/`scipy`/`pandas` — intenta primero el paquete `pkg` precompilado ARM64 (`python-numpy`/`python-scipy`/`python-pandas`), si falla cae a `pip install` como fallback | `scientific_pkgs` |
| 8 | Descarga scripts desde `github.com/Honkonx/termux-ai-stack` (carpetas `python/sports`, `python/trading`, `python/bots`) vía la API de GitHub (listado dinámico de archivos `.py`, no una lista hardcodeada) + los 3 scripts de visión (`vision_bot.py`, `bot_utils.py`, `image_archive.py`) | `all_scripts` |
| 9 | Aliases en `.bashrc`: `py3`, `pip3-install`, `sqlite-n8n` | `python_aliases` |
| 10 | Verifica `python3` con `verify_binary_installed` y escribe el registry (ver sección 6) | — |

**Estructura de carpetas creada**: `~/sports/{scripts,db,logs,models}`, `~/trading/{scripts,db}`, `~/bots/{scripts,db}`.

**Flags soportados**: `--silent` (sin prompts, modo app), `--force` (reinstala aunque ya esté), `--describe` (manifiesto JSON declarativo — `{"id":"python","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}`, sin variantes), `--describe-files` (devuelve `files: []` deliberadamente). Los pips usan el wrapper con `flock` de `lib.sh` (lock de pip, 600 s).

## 4. Detección de estado

- **Instalación**: el registry (`~/.android_server_registry`) — pero `PythonFragment.isModuleInstalled()` no confía ciegamente en eso: además verifica `isTermuxBinaryAvailable("python3")` (ejecuta `command -v python3` de verdad vía `bash -c`) antes de considerar el módulo instalado, porque el registry puede decir `python.installed=true` con `python3` roto en la práctica.
- **"Corriendo"**: no aplica — `hasSwitch: false`, es una herramienta CLI sin proceso persistente propio.

## 5. Pantalla real de la app (`PythonFragment.kt`)

Clasificación: **módulo funcional** (pantalla propia `PythonFragment`, enrutada desde `ModuleDetailNavigator.kt`). Sin servicio ni switch, pero con 9 botones de primer nivel (info, REPL, pip install, paquetes por categoría, pip list, pip outdated, ejecutar .py, venv, actualizar) y sub-flujos (venv: crear/requirements/eliminar; catálogo de 8 categorías). En `modules.json`: `recommended: true`, tamaño ~80MB, `terminalCommand: python3`, sin `requires`.

Card "ESTADO" (versión, pip — se llenan al tocar "Ver versión e info") + botones:

| Botón | Acción real |
|---|---|
| ℹ Ver versión e info | Corre `python3 --version`, `pip --version`, `command -v python3`, y prueba `import` de 7 paquetes (numpy/scipy/pandas/requests/websockets/PIL/bs4) para armar el estado real |
| ⌨ Abrir REPL (python3) | `launchTerminalCommand("python3")` — abre la terminal adaptada con el intérprete corriendo |
| 📦 Instalar paquete (pip) | Prompt de texto libre → `python3 -m pip install --break-system-packages <paquete>` |
| 🗂 Paquetes por categoría | Catálogo curado de 8 categorías (Algoritmos/Math, Datos, IA/ML, NLP/Texto, Web/Scraping, Ciencia, Herramientas, Ciberseguridad) — el usuario elige categoría → paquete → confirma, sin tener que memorizar nombres de PyPI. Instala igual que el botón de arriba (`pip install --break-system-packages <paquete>`) |
| 📋 Listar paquetes instalados | `pip list --format=json` (con fallback a parseo de texto plano si el JSON falla) |
| ⬆ Paquetes desactualizados | `pip list --outdated --format=json` — cada fila es tocable, ofrece actualizar ese paquete puntual |
| ▶ Ejecutar script .py | Busca `.py` reales en `~/python`, `~` (profundidad 1) y `/storage/emulated/0/Download` (profundidad 2), salteando `node_modules`/`.git`/`venv`/`.venv`/`.gradle`/`build`/`__pycache__` — los muestra en una lista numerada; si no encuentra nada, cae a pedir una ruta manual |
| 🧪 Entorno virtual (venv) | Gestión de venv **por proyecto** (elige carpeta de `~/proyectos`) — ver sección 5b |
| 🔄 Actualizar Python | `updateModuleService()` — reinstala/actualiza vía `modulos/python.sh`, mismo mecanismo que el resto de los módulos sin CLI dedicada |

Toda la lógica de estas acciones vive en Kotlin (`PythonFragment.kt`, `runCommand()` con `ProcessBuilder` + `applyTermuxEnv()`) — no pasa por ningún script Python intermediario. La card ESTADO se carga sola al abrir la pantalla. El REPL usa `launchTerminalCommand()` (mecanismo real para abrir la terminal con un comando); ninguna otra acción usa el campo `terminalCommand` de `modules.json`.

## 5b. Entornos virtuales por proyecto (venv)

Permite aislar las dependencias de un proyecto sin tocar el resto del sistema (sin esto, `pip install` instala siempre global con `--break-system-packages`).

- **Elegir proyecto**: lista carpetas de `~/proyectos` (la misma carpeta compartida que usa `ExpoFragment.listProjectDirs()` y el resto de los módulos con "Gestionar proyectos") — si no hay ninguna, avisa y no ofrece nada más.
- **Crear venv** (`Crear venv (--system-site-packages)`) → `python3 -m venv --system-site-packages <proyecto>/.venv`. El flag `--system-site-packages` es deliberado: en Termux los paquetes pesados (numpy/scipy/pandas) vienen precompilados vía `pkg install`, no como wheels ARM64 en PyPI — un venv aislado del todo no podría instalarlos por pip, así que el venv hereda esos paquetes del sistema y solo aísla lo que el proyecto instale con pip.
- **Instalar requirements.txt**: si el proyecto tiene `requirements.txt`, instala con `<proyecto>/.venv/bin/pip install -r requirements.txt` (si ya hay venv) o con pip global `--break-system-packages` (si no hay venv creado).
- **Eliminar venv**: borra `<proyecto>/.venv` recursivamente.
- El menú de acciones (`showVenvActions()`) es contextual: solo muestra "Crear venv" si no existe, solo muestra "Instalar requirements.txt" si el archivo existe, solo muestra "Eliminar venv" si ya hay uno.
- **Resolución de rutas**: `runCommand()` antepone `$PREFIX/bin/` al primer elemento del comando (ej. `python3` → `$PREFIX/bin/python3`), salvo que ya sea una ruta absoluta (ej. `<proyecto>/.venv/bin/pip`), que se deja tal cual.

## 6. Registry (`~/.android_server_registry`)

```
python.installed=true
python.version=<versión real, ej. 3.13.2>
python.install_date=<YYYY-MM-DD>
python.location=termux_native
python.sqlite=true
python.pillow=true
python.numpy=true
python.scipy=true
python.pandas=true
python.requests=true
python.websockets=true
python.beautifulsoup4=true
python.sports=true
python.bots=true
```

## 7. Notas de implementación y limitaciones

- **Checkpoint verificado contra el binario real**: si `check_done "python_install"` dice true, el paso se salta solo después de comprobar con `command -v python3` que `python3` realmente responde ahora; la UI (`isModuleInstalled()` en `PythonFragment.kt`) aplica el mismo criterio.
- **Versión**: la versión real la da el paquete pkg; `modules.json` muestra `catalogVersion: 3.13.x` y la descripción "Python 3.13" solo como texto de catálogo.
- **Dependencia de red externa**: `python.sh` declara como repo `github.com/Honkonx/termux-ai-stack` y el PASO 8 descarga ahí los scripts sports/trading/bots/vision vía API de GitHub. Si ese repo cambia o desaparece, el PASO 8 falla sin impedir la instalación (best-effort, ramas `|| warn`; comportamiento inferido, no probado).
- **Seguridad**: se instala con `--break-system-packages` (pip global); sin secretos. El pip-install de texto libre acepta cualquier nombre de paquete (riesgo de typosquatting inherente a pip; sin allowlist).
- **Peso de la instalación**: `proot-distro`, `clang` y `libopenblas` se instalan siempre aunque el usuario solo quiera Python (peso/tiempo).
- **Desinstalación**: no está definida en `ModuleController` — Python se trata como runtime compartido y nunca se desinstala.
- **Fecha de alta**: `modulos/python.sh` 2026-07-19.
