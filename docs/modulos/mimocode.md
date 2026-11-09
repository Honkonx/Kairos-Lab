# MiMo Code (Xiaomi) — ficha de módulo (`mimocode`)

> Ficha basada en la lectura del código. **Confirmado** = leído en el código citado (archivo:línea); **Sin verificar** = inferencia no comprobada en dispositivo.

## 1. Qué es y para qué sirve
Agente de código de Xiaomi (CLI `mimo`) con memoria persistente entre sesiones. En `modules.json` figura como `experimental: true`, categoría `ai`, tamaño estimado ~60MB. El módulo solo instala el CLI y ofrece lanzadores de terminal; la autenticación (MiMo Auto anónimo, OAuth Xiaomi, API compatible OpenAI) la guía el propio TUI de `mimo` en el primer arranque (`mimocode.sh:49-53`).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN (con una función nativa real: configurar proveedor IA local)**

Justificación (confirmado): no tiene Fragment propio (usa el genérico `CliToolFragment`), ni servicio, ni estado propio; todas las acciones abren una terminal con un comando. La única operación que la app ejecuta por sí misma es **Usar Ollama / llama-server local**, que escribe `~/.config/mimocode/mimocode.jsonc` desde Kotlin (`LocalCliProviderNative.configureMimoCode`, `LocalCliProviderNative.kt:41-58`).

Opciones de UI (aprox.): **~11** (5 comunes + Login + Enviar prompt + Continuar sesión + Sesiones + Usar Ollama + Usar llama-server).

## 3. Instalación (`modulos/mimocode.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`mimocode.sh:70-83`). Sin variantes (`--describe` devuelve `variants: []`, `:88`).
- Versión del script: 3.0.0 (`:60`). Checkpoint `~/.install_mimocode_checkpoint`; registry `~/.android_server_registry`.
- Orden real de métodos (`:301-358`): (1) **método original** glibc: instala `glibc-repo glibc patchelf-glibc curl` si faltan (`:313-326`), descarga `*linux-arm64.tar.gz` de `XiaomiMiMo/MiMo-Code` (GitHub API, `:216-226`), lo extrae en `~/.local/share/mimocode`, aplica `patchelf --set-interpreter` y crea el wrapper `$PREFIX/bin/mimo` con `unset LD_PRELOAD` (`:248-266`); (2) **respaldo**: `.deb` aarch64 de `Honkonx/MiMoCode-Termux` o `Hope2333/MiMoCode-Termux` extraído con `dpkg-deb -x` y copiado con `cp -a` a `$PREFIX` (`:151-206`) — binario Bionic puro, sin glibc.
- Verificación: `mimo --version` funcional tras cada método (`:201`, `:269`). Registry: `installed, version, channel (native_arm64 | fork_native_bionic), install_date` (`:366-370`).
- Descargas **sin sha256** (`curl -fsSL` directo, `:233`, `:172`; no se llama a `verify_sha256`).
- Dependencias declaradas en `modules.json`: `requires: ["nodejs"]`, `installMethods: ["npm"]`, `arch: "bionic"` — ver gaps.

## 4. Ejecución
- Comando: `mimo` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:205-215`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:55`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> la UI antepone `unset LD_PRELOAD; ` a cada comando (`CliToolFragment.kt:452`).

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && mimo`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && mimo`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:205-215`):
- **Iniciar sesión**: `mimo auth login` (`:208`).
- **Enviar prompt**: `mimo run '<prompt>'` (`:210`, texto escapado con `shellEscape`, `:642`); sin selector de modelo.
- **Continuar última sesión**: `mimo --continue` (con diálogo de carpeta, `:213`, `:516-531`).
- **Sesiones** (card MÁS OPCIONES): `mimo session list` (`:214`).
- **Usar Ollama local / Usar llama-server local** (card PROVEEDOR IA LOCAL, `:597-602`): lista modelos reales (`OllamaApiClient.listModels()` / `LocalModelManager.listModels`) y escribe el provider `custom` (`@ai-sdk/openai-compatible`, baseURL `http://127.0.0.1:11434/v1` u `:8085/v1`, apiKey `local`) en `mimocode.jsonc` (`:848-861`, `LocalCliProviderNative.kt:41-58`).

## 6. Archivos y rutas que crea
- `$PREFIX/bin/mimo` (wrapper bash o, en el método de respaldo, el de `usr/bin` del .deb).
- Método glibc: `~/.local/share/mimocode/<binario>` (`MIMO_DIR`, `:124`).
- Método respaldo: árbol completo de `usr/` del .deb, p.ej. `$PREFIX/lib/mimocode/runtime/mimocode` (`:196`).
- Temporales `~/tmp/mimocode_*` (borrados). `~/.install_mimocode_checkpoint` (borrado al final).
- Registry: claves `mimocode.*`.
- Config de proveedor local: `~/.config/mimocode/mimocode.jsonc` (la crea la UI, no el script).

## 7. Seguridad
- Sin sha256 en ninguna descarga binaria (tarball oficial o .deb del fork); el .deb se vuelca con `cp -a` sobre `$PREFIX` (puede sobrescribir cualquier archivo de `usr/` del paquete).
- Prompt del usuario: escapado de comillas simples (`shellEscape`) antes de interpolarlo en el comando; no hay inyección por `'` pero el prompt llega a la terminal tal cual.
- `apiKey` del provider local se fija en el literal `local` (no es un secreto). No se guardan secretos reales desde la UI.
- No hay `curl | bash`.

## 8. Problemas y gaps detectados
- Confirmado: la cabecera del script (`mimocode.sh:13-26`, `:60-63`) afirma que el fork Bionic es el MÉTODO 1 preferido, pero el flujo real ejecuta el método glibc primero y el fork solo como respaldo (`:301-358`, y el comentario de `:302-307` lo confirma). Cabecera desactualizada.
- Confirmado: `modules.json` dice `installMethods: ["npm"]`, `requires: ["nodejs"]`, `arch: "bionic"`; el script ya no usa npm (`:34-47`) y el método principal es glibc+patchelf. El catálogo no refleja la instalación real (afecta dependencias mostradas).
- Confirmado: `ModuleController.deepUninstallPlan` para `mimocode` ejecuta `npm uninstall -g @mimo-ai/cli` (`ModuleController.kt:890`), que no quita nada de lo que instala el script actual (wrapper, `~/.local/share/mimocode`, árbol del .deb). La desinstalación profunda deja el binario.
- Confirmado: `configureMimoCode` hace `configFile.writeText(...)` completo (`LocalCliProviderNative.kt:41-58`), pisando cualquier `mimocode.jsonc` previo del usuario (a diferencia de `configureMistralVibe`, que preserva el resto del archivo).
- Confirmado: `--describe-files` solo cubre el layout glibc (`$HOME/.local/share/mimocode/**`, `:99`); si se instaló por el fork, el payload está en `$PREFIX/lib/mimocode` y `moduledeb pack` quedaría incompleto (inferencia directa del código, no probado).
- Sin verificar: `needsGlibcCompat=true` aplica también a la instalación por fork (Bionic), donde `unset LD_PRELOAD` es innecesario pero probablemente inofensivo.
- Sin verificar: el botón de MCP/Engram no existe a propósito (sintaxis no confirmada, comentario `CliToolFragment.kt:201-204`).

## 9. Fecha de alta
2026-08-12.
