# Pi Coding Agent — ficha de módulo (`pi`)

> Todo lo marcado "Verificado en código" fue leído en el código citado (archivo:línea); "Inferencia" = sin verificar en dispositivo. No se probó nada en un dispositivo.
> Documento compartido de la familia: `docs/modulos/cli-tools.md`. Donde difieran, manda `CliToolFragment.kt`.

## 1. Qué es y para qué sirve
Agente de codificación por terminal (`earendil-works/pi`, paquete npm `@earendil-works/pi-coding-agent`, comando `pi`). Según el KDoc de `CliToolFragment.kt:327-340` no tiene MCP ni subagentes; el login es `/login` dentro de la sesión.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación (verificado en código): sin servicio, sin estado propio, sin función nativa; acciones = lanzadores de terminal. Es la dependencia declarada de `ohmypi`.

Opciones de UI (aprox.): **~11** (5 comunes + Login (hint) + Enviar prompt (con modelo opcional) + Continuar sesión + Instalar extensión + Extensiones instaladas + Elegir sesión).

## 3. Instalación (`modulos/pi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`pi.sh:53-61`). Versión 1.0.0.
- PASO 1 (`:153-180`): Node/npm (`nodejs-lts` si falta) + `ripgrep git fd` si faltan. PASO 2 (`:182-200`): `npm install -g @earendil-works/pi-coding-agent@latest --ignore-scripts` + `fix_npm_shebang_wrapper pi` + `verify_binary_installed pi`.
- Registry: `installed, version, channel=npm, install_date` (`:206-210`). Sin hash ni pin de versión.
- `--describe-files` resuelve el binario con `command -v pi` (para soportar un prefix de `npm` redirigido a `~/.npm-global`, `:78-85`) y empaqueta `$(npm root -g)/@earendil-works/**`.
- `modules.json`: `installMethods: ["npm"]`, `requires: []` (no declara nodejs aunque el script lo instala), `catalogVersion: "1.0.0"`.

## 4. Ejecución
- Comando: `pi` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:341-356`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:62`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` (`CliToolFragment.kt:350`): antepone `unset LD_PRELOAD; ` aunque el módulo se instala por npm. El KDoc del campo (`:77-79`) reserva esa bandera para glibc/Bun y dice que los shims npm necesitan `LD_PRELOAD`; aquí el wrapper tiene shebang absoluto, por lo que es probablemente inofensivo (inferencia).

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && pi`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && pi`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:341-356`):
- **Iniciar sesión**: sin `authCommand`; toast `clitool_cfg_authhint_pi` (`/login` dentro de la sesión) y abre `pi` (`:344`).
- **Enviar prompt**: `pi -p '<prompt>'`; con modelo `pi --model '<modelo>' -p '<prompt>'` (`:346-348`).
- **Continuar última sesión**: `pi -c` (con diálogo de carpeta, `:349`).
- **Instalar extensión** (card EXTENSIONES): diálogo de texto libre (path/npm/git) -> `pi install '<fuente>' -l` (`:351`).
- **Extensiones instaladas**: `pi list` (`:353`). **Elegir sesión**: `pi -r` (`:354`).

## 6. Archivos y rutas que crea
- Paquete npm global `@earendil-works/pi-coding-agent` y wrapper bash `pi` (en `$PREFIX/bin`, o `~/.npm-global/bin` si el prefix de npm está redirigido).
- `~/.pi` (config/estado, lo crea el propio binario, no el instalador; comentario `pi.sh:26-27`).
- `~/.install_pi_checkpoint` (borrado). Registry: `pi.*`.

## 7. Seguridad
- **Instalar extensión** ejecuta `pi install <fuente> -l` con fuente libre (path/npm/git): instala y ejecuta código de terceros arbitrario elegido por el usuario; hay escapado de comillas, pero sin advertencia en la UI.
- `npm install -g ... --ignore-scripts` reduce el riesgo de scripts postinstall (`:188`).
- La app no guarda credenciales de Pi.

## 8. Problemas y limitaciones detectados
- No hay plan en `deepUninstallPlan` para `pi`; la desinstalación profunda solo resetea estado y deja el paquete npm y `~/.pi`.
- El `--describe` del script y la descripción de `modules.json` dicen "sin flags/login propios confirmados" (`pi.sh:66`, `modules.json`), mientras `CliToolFragment.kt` ya expone `-p`, `-c`, `--model`, `-r` e `install/list`: texto desactualizado.
- `modules.json` tiene `requires: []` pero el script instala Node, ripgrep, git, fd; la dependencia no se muestra en el catálogo.
- El comentario `pi.sh:12-15` dice que el comando lo publica npm "sin wrapper", contradicho por el propio `fix_npm_shebang_wrapper` de `:194` (corregido en el bloque `--describe-files`, no en la cabecera).
- Inferencia: `needsGlibcCompat=true` es inconsistente con el criterio documentado.

## 9. Fecha de alta
2026-08-18 (primer commit que añade `modulos/pi.sh`).
