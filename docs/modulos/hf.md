# Hugging Face CLI — ficha de módulo (`hf`)

> Los datos citados salen del código (archivo:línea); "inferencia" = deducido sin verificar en dispositivo. No se probó nada en un dispositivo.
> Doc compartida de la familia: `docs/modulos/cli-tools.md` (puede estar desactualizada respecto a `CliToolFragment.kt`; esta ficha manda donde difieran).

## 1. Qué es y para qué sirve
CLI oficial de Hugging Face (`hf`) para gestionar modelos, datasets y spaces: `hf download` (con resume), `hf upload`, `hf auth login`, caché local (`hf.sh:3-28`). NO es un agente conversacional. El script lo presenta como complemento del ecosistema llama.cpp (los .gguf se bajan de repos HF).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación: sin servicio ni estado propio; sus botones son lanzadores de terminal. Tiene relativamente más opciones que otros CLI, pero ninguna está integrada con el gestor de modelos de la app (`LocalModelManager`).

Opciones de UI (aprox.): **~11** (5 comunes + Login + Buscar modelo/dataset + Descargar por ID + Sesión activa + Ver caché + Limpiar caché).

## 3. Instalación (`modulos/hf.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`hf.sh:83-91`). Versión 1.2.0.
- PASO 1 `curl` y `python3` (`:266-291`). PASO 1.5 intenta un **wheel prebuilt** de `hf_xet` (`aarch64-android`) desde Releases de `Honkonx/kairos-lab`, instalándolo en el venv `~/.hf-cli/venv` (o `$HF_HOME/cli/venv`) que reutiliza el instalador oficial (`:156-239`, `:293-309`). PASO 1.6 instala `rust clang make libffi openssl pkg-config` solo si no hubo wheel (`:311-332`). Exporta `ANDROID_API_LEVEL=24`, `CARGO_BUILD_JOBS=1` (`:344-345`).
- PASO 2 (`:347-362`): **`curl -LsSf https://hf.co/cli/install.sh | bash`** (`:353`), `PATH` con `~/.local/bin`, `verify_binary_installed hf`.
- Registry: `installed, version, channel=official_installer, install_date` (`:380-384`).
- Verificación de integridad: `verify_sha256` del wheel en modo "solo loggear" porque `HF_XET_PREBUILT_SHA256` está vacío (`:220`, `lib.sh:592-610`); el instalador oficial no se verifica.
- `modules.json`: `installMethods: ["curl"]`, `requires: ["curl"]`, ~20MB (no refleja python ni el posible toolchain Rust).

## 4. Ejecución
- Comando: `hf` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:310-325`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:61`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false. `Abrir en terminal` lanza `hf` a secas (inferencia: imprime la ayuda; no hay sesión interactiva propia).

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && hf`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && hf`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:310-325`):
- **Iniciar sesión**: `hf auth login` (`:313`).
- **Buscar modelo/dataset** (reuso de `symbolQueryTemplate`): diálogo de texto -> `hf models ls --search '<término>'` (`:314-316`, `runSymbolQueryButton` `:669-688`). Solo busca modelos pese a la etiqueta.
- **Descargar por ID** (reuso de `installExtensionTemplate`): diálogo con repo_id -> `hf download '<id>'` (`:317-319`, `showInstallExtensionDialog` `:690-707`).
- **Sesión activa**: `hf auth whoami` (`:321`). **Ver caché**: `hf cache ls` (`:322`). **Limpiar caché**: `hf cache prune` (`:323`), lanzado sin diálogo de confirmación propio.

## 6. Archivos y rutas que crea
- Binario `~/.local/bin/hf` y venv del CLI `~/.hf-cli/` (o `$HF_HOME/cli/`), creados por el instalador oficial.
- Caché de modelos `~/.cache/huggingface/` y config/token `~/.config/huggingface/` (creados por el CLI al usarlo; los borra la desinstalación profunda, `ModuleController.kt:998-1006`).
- Wheel temporal en `mktemp -d` (se borra).
- `~/.install_hf_checkpoint` (borrado). Registry: `hf.*`.

## 7. Seguridad
- **`curl | bash` del instalador oficial sin verificación** (`:353`).
- El wheel prebuilt de hf_xet se descarga de un repo propio sin hash pinneado (solo se loggea el sha256, `:211-224`); se instala con `--force-reinstall --no-deps` en el venv.
- El token de HF (`hf auth login`) lo guarda el CLI en `~/.config/huggingface` / `~/.cache/huggingface`; la app no lo lee ni lo muestra. `hf auth whoami` solo imprime en la terminal.
- `hf cache prune` borra datos del usuario sin confirmación de la app (inferencia: el CLI puede pedirla en la terminal).

## 8. Limitaciones conocidas
- La cabecera (`hf.sh:34-59`) y el comentario del PASO 1.5 indican que puede no existir un Release con el wheel `hf_xet-*aarch64*android*.whl`; no se verificó si hoy existe. Hasta confirmarlo, es inferencia que el camino rápido funcione.
- `HF_XET_PREBUILT_SHA256` no tiene valor por defecto: la verificación del wheel nunca bloquea.
- `Descargar por ID` ejecuta `hf download` en la caché de HF (`~/.cache/huggingface`); no mueve el GGUF al directorio que usa `LocalModelManager`/llama-server, así que no completa el flujo "bajar GGUF para el chat local" que sugiere la cabecera (el código no integra ambos).
- La etiqueta "Buscar modelo/dataset" solo ejecuta `hf models ls --search` (modelos).
- `--describe-files` declara `version_registry_key: ""` y `verify_cmd` con solo `command -v hf` (`:114`, `:119`).
- El tamaño de catálogo (~20MB) subestima python/rust si hacen falta.

## 9. Fecha de alta
2026-08-14 (`modulos/hf.sh`).
