# Kimi Code (Moonshot AI) — ficha de módulo (`kimi`)

> Ficha basada en la lectura del código. **Confirmado** = leído en el código citado (archivo:línea); **Sin verificar** = inferencia no comprobada en dispositivo.

## 1. Qué es y para qué sirve
CLI de código oficial de Moonshot AI (Kimi), paquete npm `@moonshot-ai/kimi-code`, comando `kimi`; requiere Node.js 22.19+ (`kimi.sh:22-25`). El módulo solo instala el CLI.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación (confirmado): sin servicio, sin estado propio, sin función nativa; todas las acciones son lanzadores de terminal.

Opciones de UI (aprox.): **~8** (5 comunes + Login + Enviar prompt (con modelo opcional) + Servidores MCP).

## 3. Instalación (`modulos/kimi.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`kimi.sh:45-53`). Versión 1.0.0.
- PASO 1 Node: acepta el Node existente solo si `node_major >= 22`; si no, instala `nodejs-lts` y solo avisa (`warn`) si sigue <22 (`:141-158`). PASO 2 `npm install -g @moonshot-ai/kimi-code@latest` + `fix_npm_shebang_wrapper kimi` + `verify_binary_installed kimi` (`:160-175`). PASO 3 API key omitida (`:177-187`).
- Registry: `installed, version, channel=npm, install_date` (`:193-197`).
- `--describe-files`: wrapper en PATH + `$(npm root -g)/@moonshot-ai/kimi-code/**`; su `verify_cmd` es solo `command -v kimi` (`:74`, más débil que el resto).
- `modules.json`: `installMethods: ["npm"]`, `requires: ["nodejs"]`.

## 4. Ejecución
- Comando: `kimi` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:257-266`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:58`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false.

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && kimi`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && kimi`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:257-266`):
- **Iniciar sesión**: `kimi login` (device-code flow, `:260`).
- **Enviar prompt**: `kimi -p '<prompt>'`; si se indica modelo (campo libre en un 2º diálogo) `kimi -m '<modelo>' -p '<prompt>'` (`:262-264`, `askModelThenRun` `:779-795`).
- **Servidores MCP**: `kimi mcp list` (solo lectura, `:265`).
- Sin `continueSessionCommand` ni modo local.

## 6. Archivos y rutas que crea
- Paquete npm global `@moonshot-ai/kimi-code` y wrapper bash `$PREFIX/bin/kimi`.
- Config/credenciales del CLI en su propio directorio (no creado por el script; ubicación no verificada).
- `~/.install_kimi_checkpoint` (borrado al final). Registry: `kimi.*`.

## 7. Seguridad
- npm `@latest` sin hash. El login es device-code gestionado por el propio CLI; la app no almacena tokens.
- Prompt/modelo escapados con `shellEscape`.
- Sin `curl | bash`.

## 8. Problemas y gaps detectados
- Confirmado: no hay plan en `deepUninstallPlan` para `kimi` (no aparece en `ModuleController.kt:837+`), así que la desinstalación profunda solo resetea estado y avisa "desinstalación real no soportada" (`ModuleController.kt:1067-1068`); el paquete npm y el wrapper quedan instalados.
- Confirmado: si el Node queda <22 solo se emite un `warn` (`:154`) y la instalación continúa; puede terminar con un CLI que no arranca.
- Confirmado: el modelo se pide en texto libre (formato no validado).
- Confirmado: `--describe-files` verifica con `command -v kimi` en vez de `kimi --version`, incoherente con `verify_binary_installed`.
- Sin verificar: no hay botones para `kimi mcp add/remove/auth` (se dejan a propósito para la terminal, comentario `CliToolFragment.kt:254-256`).

## 9. Fecha de alta
2026-08-14.
