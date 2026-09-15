# Python

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/python.sh`; toda interacción en runtime (info, pip, REPL, scripts) corre directo desde `PythonFragment.kt` sin pasar por el script de instalación.

---

**Script:** `modulos/python.sh` — espejo en `app/src/main/assets/scripts/python.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/PythonFragment.kt`
**`id` en `modules.json`:** `python` — sin switch (herramienta CLI, sin proceso propio que arrancar/detener)

---

## 1. Descripción general

Python es la base de scripting nativa del stack — Python 3 (el paquete `python` real de Termux, no una versión fija) más un conjunto amplio de librerías científicas/de red, SQLite integrado, y una estructura de carpetas (`~/sports`, `~/trading`, `~/bots`) usada por scripts de referencia (bots de Telegram, señales de trading, archivo de imágenes con visión).

No es un servicio con proceso persistente — no tiene switch ON/OFF, es una herramienta CLI que otros módulos (n8n para scripts de visión, el módulo Base de Datos para SQLite) y el propio usuario (REPL, `pip install`, correr scripts `.py`) usan bajo demanda.

La gestión de bases de datos (incluida la SQLite que viene con Python) vive en el módulo **Base de Datos**. La versión de SQLite se sigue reportando como dato dentro de la info de Python (`import sqlite3`), sin botón propio de SQLite en esta pantalla.

## 2. Permisos

- **Android**: ninguno específico de este módulo — usa el mismo proceso Termux que ya tiene sus permisos base (almacenamiento, otorgado en el asistente de configuración). No requiere `INTERNET` extra, no requiere overlay ni notificaciones.
- **Termux interno**: ninguno más allá de la instalación de paquetes vía `pkg`/`pip` — no toca `sshd`, no abre puertos, no requiere root.

## 3. Lógica de instalación (`modulos/python.sh`)

Script de 10 pasos, con checkpoints para poder reanudar tras una interrupción sin repetir pasos ya hechos:

| Paso | Qué hace |
|---|---|
| 1 | `pkg update && pkg upgrade` (con fallback a 3 mirrors si el principal falla) |
| 2 | Instala `tur-repo` (Termux User Repository) |
| 3 | Instala `python` (pkg) + actualiza `pip` — verifica que `python3` responda de verdad antes de saltar el paso |
| 4 | `proot-distro`, `binutils`, `libopenblas`, `clang` |
| 5 | SQLite CLI + deps de imagen (`libjpeg-turbo`, `libpng`, `zlib`) |
| 6 | 12 paquetes pip, en orden estricto: `six`, `certifi`, `idna`, `urllib3`, `charset-normalizer`, `soupsieve`, `typing_extensions`, `python-dateutil`, `beautifulsoup4`, `requests`, `websockets`, `pillow` — cada paquete se intenta individualmente, uno que falla no aborta el resto |
| 7 | `numpy`/`scipy`/`pandas` — intenta primero el paquete `pkg` precompilado ARM64, si falla cae a `pip install` como fallback |
| 8 | Descarga scripts de referencia (carpetas `python/sports`, `python/trading`, `python/bots`, listado dinámico de archivos `.py`) + 3 scripts de visión (`vision_bot.py`, `bot_utils.py`, `image_archive.py`) |
| 9 | Aliases en `.bashrc`: `py3`, `pip3-install`, `sqlite-n8n` |
| 10 | Registry |

**Estructura de carpetas creada**: `~/sports/{scripts,db,logs,models}`, `~/trading/{scripts,db}`, `~/bots/{scripts,db}`.

**Flags soportados**: `--silent` (sin prompts, modo app), `--force` (reinstala aunque ya esté), `--describe` (manifiesto JSON declarativo, sin variantes).

## 4. Detección de estado

- **Instalación**: el registry (`~/.android_server_registry`) — pero `PythonFragment.isModuleInstalled()` no confía ciegamente en eso: además verifica que `python3` responda de verdad (`command -v python3` vía shell) antes de considerar el módulo instalado. La razón: el registry puede quedar marcado como instalado con `python3` roto en la práctica.
- **"Corriendo"**: no aplica — herramienta CLI sin proceso persistente propio.

## 5. Pantalla de la app (`PythonFragment.kt`)

Card "Estado" (versión, pip) + 5 botones:

| Botón | Acción real |
|---|---|
| Ver versión e info | Corre `python3 --version`, `pip --version`, `command -v python3`, y prueba `import` de 7 paquetes (numpy/scipy/pandas/requests/websockets/PIL/bs4) para armar el estado real |
| Abrir REPL (python3) | Abre la terminal adaptada con el intérprete corriendo |
| Instalar paquete (pip) | Prompt de texto libre → `python3 -m pip install --break-system-packages <paquete>` |
| Paquetes por categoría | Catálogo curado de 8 categorías (Algoritmos/Math, Datos, IA/ML, NLP/Texto, Web/Scraping, Ciencia, Herramientas, Ciberseguridad) — el usuario elige categoría → paquete → confirma, sin memorizar nombres de PyPI |
| Listar paquetes instalados | `pip list --format=json` (con fallback a parseo de texto plano si el JSON falla) |
| Paquetes desactualizados | `pip list --outdated --format=json` — cada fila es tocable, ofrece actualizar ese paquete puntual |
| Ejecutar script .py | Busca `.py` reales en `~/python`, `~` (profundidad 1) y almacenamiento de Descargas (profundidad 2), salteando `node_modules`/`.git`/`venv`/`.venv`/`.gradle`/`build`/`__pycache__` — los muestra en una lista numerada; si no encuentra nada, cae a pedir una ruta manual |
| Entorno virtual (venv) | Gestión de venv por proyecto (elige carpeta de `~/proyectos`) — ver sección 5b |
| Actualizar Python | Reinstala/actualiza vía `modulos/python.sh` |

Toda la lógica de estas acciones vive en Kotlin (`PythonFragment.kt`, con `ProcessBuilder`) — no pasa por ningún script Python intermediario.

## 5b. Entornos virtuales por proyecto (venv)

Permite aislar dependencias de un proyecto sin tocar el resto del sistema, con detección/instalación de un `requirements.txt` existente.

- **Elegir proyecto**: lista carpetas de `~/proyectos` (la misma carpeta compartida que usa el resto de los módulos con "Gestionar proyectos") — si no hay ninguna, avisa y no ofrece nada más.
- **Crear venv** → `python3 -m venv --system-site-packages <proyecto>/.venv`. El flag `--system-site-packages` es deliberado: en Termux los paquetes pesados (numpy/scipy/pandas) vienen precompilados vía `pkg install`, no como wheels ARM64 en PyPI — un venv aislado del todo no podría instalarlos por pip, así que el venv hereda esos paquetes del sistema y solo aísla lo que el proyecto instale con pip.
- **Instalar requirements.txt**: si el proyecto tiene `requirements.txt`, instala con `<proyecto>/.venv/bin/pip install -r requirements.txt` (si ya hay venv) o con pip global `--break-system-packages` (si no hay venv creado).
- **Eliminar venv**: borra `<proyecto>/.venv` recursivamente.
- El menú de acciones es contextual: solo muestra "Crear venv" si no existe, solo muestra "Instalar requirements.txt" si el archivo existe, solo muestra "Eliminar venv" si ya hay uno.
- **Detalle no obvio**: el resolvedor de rutas normalmente antepone `$PREFIX/bin/` al primer elemento del comando (ej. `python3` → `$PREFIX/bin/python3`) — pero si el elemento ya es una ruta absoluta (ej. `<proyecto>/.venv/bin/pip`), se deja tal cual, para que la instalación con venv invoque la ruta correcta.

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

## 7. Notas de arquitectura

- La verificación de instalación siempre confirma que el binario real responda (no solo que el checkpoint diga "hecho") — tanto en el script (`command -v python3` real antes de confiar en el checkpoint) como en la UI (mismo criterio en `isModuleInstalled()`).
- Toda la lógica de runtime (info/pip-install/pip-list/find-scripts/run-script) corre directo en Kotlin, sin pasar por ningún subproceso Python intermediario.
