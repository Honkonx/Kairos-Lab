# Cursor CLI (cursor-agent) — ficha de módulo (`cursor`)

> Los datos citados salen del código (archivo:línea); "inferencia" = deducido sin verificar en dispositivo. No se probó nada en un dispositivo.
> Doc compartida de la familia: `docs/modulos/cli-tools.md` (puede estar desactualizada respecto a `CliToolFragment.kt`; esta ficha manda donde difieran).

## 1. Qué es y para qué sirve
Agente de código de Cursor en terminal. El binario real instalado es `cursor-agent` (no `cursor`), distribuido por el instalador oficial `cursor.com/install` con un Node.js glibc embebido (`cursor.sh:14-31`).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación: sin servicio, sin estado propio, sin función nativa; acciones = lanzadores de terminal.

Opciones de UI (aprox.): **~9** (5 comunes + Login + Enviar prompt (con modelo opcional) + Continuar sesión + Estado de cuenta).

## 3. Instalación (`modulos/cursor.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`cursor.sh:51-59`). Versión 1.0.0.
- PASO 1 (`:155-193`): instala `glibc-repo` (+`pkg update`), `glibc-runner`, `patchelf-glibc`, `curl`, `ca-certificates` si faltan. PASO 2 (`:195-250`): ejecuta **`curl https://cursor.com/install -fsSL | bash`** (`:201`), luego corrige el shebang `#!/usr/bin/env bash` del script real a `$PREFIX/bin/bash` con `sed -i` (`:227-230`) y aplica `patchelf --set-interpreter` al `node` embebido en `~/.local/share/cursor-agent/` (`:231-241`). `verify_binary_installed cursor-agent` (`:246`).
- Registry: `installed, version, channel=official_installer, glibc=true, install_date` (`:268-273`).
- Sin sha256 (el instalador remoto no se verifica). `modules.json`: `arch: "glibc"`, `installMethods: ["glibc"]`, `requires: ["glibc"]`, ~150MB.
- `--describe-files` define `patch_cmd` con patchelf para reaplicar tras desempaquetar (`:74-110`).

## 4. Ejecución
- Comando: `cursor-agent` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:290-301`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:60`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> `unset LD_PRELOAD; ` antepuesto (`CliToolFragment.kt:298`).

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && cursor-agent`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && cursor-agent`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:290-301`):
- **Iniciar sesión**: `cursor-agent login` (`:293`).
- **Enviar prompt**: `cursor-agent -p '<prompt>'`; con modelo `... --model '<modelo>'` (`:295-297`).
- **Continuar última sesión**: `cursor-agent --continue` (con diálogo de carpeta, `:299`).
- **Estado de cuenta**: `cursor-agent status` (solo lectura, `:300`).

## 6. Archivos y rutas que crea
- `~/.local/bin/cursor-agent` (symlink a un script bash) y `~/.local/share/cursor-agent/` (script, `node` embebido parcheado, `index.js`).
- `~/.cursor_install` (variable `CURSOR_WORKDIR` declarada, `:114`; no se observó uso en el script).
- `~/.install_cursor_checkpoint` (borrado). Registry: `cursor.*`.
- Credenciales de Cursor en el directorio propio del CLI (no creado por el script).

## 7. Seguridad
- **`curl | bash` sin verificación** (`:201`): se ejecuta código remoto del dominio `cursor.com` con permisos de la app.
- `sed -i` sobre el script instalado y `patchelf` sobre el `node` embebido: modifican binarios descargados.
- Prompt/modelo escapados con `shellEscape`. La app no guarda tokens de Cursor.

## 8. Limitaciones conocidas
- Instalación por `curl | bash` sin sha256 ni pin de versión (`:201`).
- No hay plan en `deepUninstallPlan` para `cursor`; la desinstalación profunda solo resetea estado y deja `~/.local/share/cursor-agent` y el symlink.
- `get_installed_ver` solo mira `~/.local/bin/cursor-agent` (`:121-128`); si el instalador oficial cambia de ruta el módulo se vería "no instalado".
- El modelo se pide en texto libre sin validar.
- Inferencia: cada actualización del instalador oficial puede reintroducir un ELF/shebang distinto y romper el parche (el fix actual depende de la estructura observada el 2026-08-24, comentario `:206-221`).

## 9. Fecha de alta
2026-08-14 (`modulos/cursor.sh`).
