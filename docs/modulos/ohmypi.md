# Oh-My-Pi (omp) — ficha de módulo (`ohmypi`)

> Ficha basada en la lectura del código. **Confirmado** = leído en el código citado (archivo:línea); **Sin verificar** = inferencia no comprobada en dispositivo.

## 1. Qué es y para qué sirve
Versión mejorada/standalone de Pi Coding Agent (`can1357/oh-my-pi`): binario compilado con `bun build --compile` contra glibc, con addons Rust (AST grep, diff, fuzzy find, shell exec), sesiones y MCP; comando `omp` (`ohmypi.sh:6-26`). Instala SIEMPRE por el método glibc nativo.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación (confirmado): sin servicio, sin estado propio, sin función nativa; acciones = lanzadores de terminal. Lo más cercano a una función propia es **Commit asistido**, que igualmente lanza `omp commit` en una terminal.

Opciones de UI (aprox.): **~9** (5 comunes + Enviar prompt (con modelo opcional) + Continuar sesión + Configurar proveedor + Commit asistido (no tiene login)).

## 3. Instalación (`modulos/ohmypi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`ohmypi.sh:71-79`). Versión 1.0.0.
- PASO 1 (`:170-205`): instala `glibc-repo` (+`pkg update`), `glibc` y `jq curl tar clang` si faltan. PASO 2 (`:207-227`): consulta `releases?per_page=5` de `can1357/oh-my-pi`, toma el primer tag que empieza con `v` y descarga `omp-linux-arm64` a `~/.local/share/kairos-data/oh-my-pi/omp` (sin sha256, `:218`); escribe la versión en `.../version`. PASO 3 (`:229-291`): compila con `clang` un helper C que hace `execv` del loader glibc (`ld-linux-aarch64.so.1 --library-path ... omp`), con `unsetenv(LD_PRELOAD)` y rutas absolutas embebidas, en `$PREFIX/bin/omp`; `verify_binary_installed omp`.
- Registry: `installed, version, channel=github_release, install_method=glibc_native, install_date` (`:297-302`).
- `modules.json`: `arch: "glibc"` (`:98-101`), `installMethods: ["github_release"]`, `requires: ["pi"]`, ~90MB.
- Declarar `requires: ["pi"]` es solo informativo: el script NO instala ni comprueba `pi` (cabecera `:15-19`).

## 4. Ejecución
- Comando: `omp` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:408-419`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:64`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` (`CliToolFragment.kt:415`); el helper ya limpia `LD_PRELOAD` por su cuenta.

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && omp`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && omp`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:408-419`):
- **Enviar prompt**: `omp -p '<prompt>'`; con modelo `omp --model '<modelo>' -p '<prompt>'` (`:411-413`).
- **Continuar última sesión**: `omp -c` (con diálogo de carpeta, `:414`).
- **Configurar proveedor** (MÁS OPCIONES): `omp setup` (config interactiva en terminal, `:416`).
- **Commit asistido** (card ACCIÓN): elige un proyecto con `showProjectsMenu` y lanza `cd '<proyecto>' && omp commit` (`:417-418`).
- Sin botón de login (`hasAuth=false`: no hay subcomando propio confirmado, comentario `:392-401`).

## 6. Archivos y rutas que crea
- `$PREFIX/bin/omp` (helper ELF compilado, no un script).
- `~/.local/share/kairos-data/oh-my-pi/omp` (binario) y `.../version`.
- `$PREFIX/tmp/omp_helper.c` (temporal, se borra).
- `~/.omp` (config/estado del propio binario).
- `~/.install_ohmypi_checkpoint` (borrado). Registry: `ohmypi.*`.

## 7. Seguridad
- Binario ejecutable descargado de GitHub Releases sin sha256 ni firma; se ejecuta con los permisos de la app.
- El helper fija rutas absolutas y `SSL_CERT_FILE`/`TMPDIR`; no usa `system()`: pasa `argv` a `execv` sin shell (sin inyección por argumentos).
- Prompt/modelo/ruta de proyecto se interpolan en comandos de shell; prompt y modelo con `shellEscape`, la ruta del proyecto entre comillas simples (`:569-571`).
- Sin `curl | bash`.

## 8. Problemas y gaps detectados
- Confirmado: `requires: ["pi"]` no se aplica en la instalación; Oh-My-Pi funciona o no por sí solo (el script no usa Pi), pero el catálogo sugiere una dependencia que el código no exige.
- Confirmado: no hay plan en `deepUninstallPlan` para `ohmypi`; la desinstalación profunda solo resetea estado y deja helper, binario y `~/.omp`.
- Confirmado: sin sha256 del binario (`:218`).
- Confirmado: el helper embebe rutas literales de HOME/PREFIX (reconocido en `--describe-files`, `:129`): no reubicable.
- Confirmado: `get_installed_ver` devuelve el tag (`v...`) leído de un archivo, no la salida de `omp --version`.
- Sin verificar: el selector de modelo es texto libre; `omp --smol/--slow/--plan` quedan fuera a propósito (`:396-399`).

## 9. Fecha de alta
2026-08-18.
