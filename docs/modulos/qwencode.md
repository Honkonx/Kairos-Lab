# Qwen Code (Alibaba) — ficha de módulo (`qwencode`)

> Todo lo marcado "Verificado en código" fue leído en el código citado (archivo:línea); "Inferencia" = sin verificar en dispositivo. No se probó nada en un dispositivo.
> Documento compartido de la familia: `docs/modulos/cli-tools.md`. Donde difieran, manda `CliToolFragment.kt`.

## 1. Qué es y para qué sirve
CLI de código de Alibaba (fork de Gemini CLI) sobre Qwen3-Coder, paquete npm `@qwen-code/qwen-code`, comando `qwen` (`qwencode.sh:3-15`). El módulo instala el CLI; la API key/login se configura dentro de la sesión con `/auth` (el subcomando `qwen auth` fue eliminado upstream, comentario `CliToolFragment.kt:231-233`).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN (el más rico de la familia: modo de aprobación, Engram y proveedor local)**

Justificación (verificado en código): sin servicio ni estado propio; las acciones abren una terminal. Es el CLI con más controles propios (modo de aprobación de 5 valores, conexión a Engram MCP), pero cada uno es un comando de terminal. La función nativa real es la escritura de `~/.qwen/.env` para el proveedor local (`LocalCliProviderNative.kt:25-35`).

Opciones de UI (aprox.): **~12** (5 comunes + Login (hint) + Enviar prompt + Continuar + Modo de aprobación + Conectar Engram + Usar Ollama + Usar llama-server).

## 3. Instalación (`modulos/qwencode.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`qwencode.sh:42-50`). Versión 1.0.0.
- PASO 1 Node.js (`nodejs-lts` si falta, `:124-140`); PASO 2 `npm install -g @qwen-code/qwen-code@latest` (`:142-157`) + `fix_npm_shebang_wrapper qwen` (reescribe el symlink npm como wrapper bash con shebang absoluto, `lib.sh:560`) + `verify_binary_installed qwen` (`qwen --version`); PASO 3 API key omitida en silencioso (solo `warn`, `:159-169`).
- Registry: `installed, version, channel=npm, install_date` (`:175-179`). Paquete sin pin de versión (`@latest`) ni verificación de hash.
- `modules.json`: `installMethods: ["npm"]`, `requires: ["nodejs"]`, ~60MB.
- `--describe-files` empaqueta el wrapper y `$(npm root -g)/@qwen-code/**` (`:53-75`).

## 4. Ejecución
- Comando: `qwen` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:239-250`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:57`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false: se conserva `LD_PRELOAD` (necesario para el shim npm).

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && qwen`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && qwen`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:239-250`):
- **Iniciar sesión**: sin `authCommand`; muestra el toast `clitool_cfg_authhint_qwencode` y abre `qwen` para que el usuario ejecute `/auth` (`runAuth`, `:630-638`).
- **Enviar prompt**: `qwen --prompt '<prompt>'` (`:244`).
- **Continuar última sesión**: `qwen --continue` (con diálogo de carpeta, `:246`).
- **Modo de aprobación**: diálogo con `plan / default / auto-edit / auto / yolo`; lanza `qwen --approval-mode=<modo>` inmediatamente (`:247-248`, `showApprovalModeDialog` `:746-755`).
- **Conectar Engram** (MÁS OPCIONES): `qwen mcp add engram engram mcp --transport stdio` (`:249`).
- **Usar Ollama / llama-server local**: upsert de `OPENAI_API_KEY=local`, `OPENAI_BASE_URL`, `OPENAI_MODEL` en `~/.qwen/.env` (`LocalCliProviderNative.kt:25-35`).

## 6. Archivos y rutas que crea
- Paquete npm global `@qwen-code/qwen-code` (en `npm root -g`) y wrapper `$PREFIX/bin/qwen` (bash, generado por `fix_npm_shebang_wrapper`).
- `~/.qwen/` (config propia del CLI) y `~/.qwen/.env` (lo escribe la UI para proveedor local).
- `~/.install_qwencode_checkpoint` (borrado al final). Registry: `qwencode.*`.

## 7. Seguridad
- La opción **yolo** del modo de aprobación se lanza sin confirmación adicional (`showApprovalModeDialog` ejecuta al tocar el ítem, `:750-752`); el CLI queda con autonomía total sobre el proyecto de la sesión.
- `OPENAI_API_KEY=local` es un literal, no un secreto. La API key real de Qwen la introduce el usuario dentro de la sesión (la app no la guarda).
- npm `@latest` sin hash. Prompt escapado con `shellEscape`.
- Sin `curl | bash`.

## 8. Problemas y limitaciones detectados
- El botón **Conectar Engram** no comprueba que el módulo `engram` esté instalado; `requires` solo lista `nodejs` (`modules.json`). Si no está, el comando falla en la terminal sin explicación en la UI.
- `deepUninstallPlan` solo ejecuta `npm uninstall -g @qwen-code/qwen-code` (`ModuleController.kt:886-889`); deja `~/.qwen` (incluida la `.env` de proveedor local).
- El modo de aprobación no se persiste: cada selección abre una sesión nueva con el flag; no hay estado ni indicador del modo activo.
- `configureQwenCode` usa upsert por clave (no pisa el resto de `.env`), pero un `OPENAI_BASE_URL` previo del usuario sí se sobrescribe.
- Inferencia: el login depende de que el usuario conozca `/auth`; el hint es un toast efímero.

## 9. Fecha de alta
2026-08-12 (primer commit que añade `modulos/qwencode.sh`).
