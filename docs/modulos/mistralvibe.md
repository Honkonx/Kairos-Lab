# Mistral Vibe — ficha de módulo (`mistralvibe`)

> Ficha basada en la lectura del código. **Confirmado** = leído en el código citado (archivo:línea); **Sin verificar** = inferencia no comprobada en dispositivo.

## 1. Qué es y para qué sirve
Agente de código oficial de Mistral AI en terminal (paquete PyPI `mistral-vibe`, comando `vibe`). El módulo instala el paquete por pip y deja la configuración de modelo/proveedor para `~/.vibe/config.toml` o `MISTRAL_API_KEY` (`mistralvibe.sh:18-20`).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN (con una función nativa real: configurar proveedor IA local)**

Justificación (confirmado): sin Fragment propio, sin servicio ni estado propio; todo abre terminal. Única operación nativa: escribir el bloque de proveedor local en `~/.vibe/config.toml` (`LocalCliProviderNative.configureMistralVibe`, `LocalCliProviderNative.kt:70-100`).

Opciones de UI (aprox.): **~9** (5 comunes + Enviar prompt + Continuar sesión + Usar Ollama + Usar llama-server (sin botón de login: no hay subcomando de shell confirmado)).

## 3. Instalación (`modulos/mistralvibe.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`mistralvibe.sh:61-69`). Versión del script 1.2.1.
- Pasos: PASO 1 verifica/instala `python` (`:196-213`); PASO 1.5 instala toolchain de compilación `rust clang make libffi openssl pkg-config` (`:225-238`) porque la dependencia transitiva `rpds-py` se compila desde fuente; PASO 2 `pip_install "$PIP_PYTHON" mistral-vibe` con `ANDROID_API_LEVEL=24`, `GYP_DEFINES`, `CARGO_BUILD_JOBS=1` (`:245-271`).
- Verificación: `verify_binary_installed vibe` (`:273`) + `_vibe_runtime_check` (`vibe --version` con `timeout 15`, buscando Traceback/ImportError, `:150-158`, `:275-284`); si falla repara `charset-normalizer` (reinstalación limpia y luego pin `3.3.2`, `:164-169`, `:131-134`).
- Registry: `installed, version, channel=pip, install_date` (`:293-297`). `pip_install` serializa con flock contra otros módulos pip (`lib.sh:219`).
- Sin hashes (paquete PyPI sin `--require-hashes`, sin pin de versión de `mistral-vibe`).
- `modules.json`: `installMethods: ["pip"]`, `requires: ["python"]`, tamaño ~40MB.

## 4. Ejecución
- Comando: `vibe` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:223-229`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:56`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat` = false (default): no se antepone `unset LD_PRELOAD`.

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && vibe`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && vibe`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:223-229`):
- **Enviar prompt**: `vibe --prompt '<prompt>'` (`:226`); sin selector de modelo.
- **Continuar última sesión**: `vibe --continue` (con diálogo de carpeta, `:228`).
- **Usar Ollama / llama-server local**: genera un bloque delimitado por marcadores (`# --- kairos-local-provider ... ---`) con `active_model`, `[[providers]]` (`backend=generic`, `api_style=openai`) y `[[models]]` al inicio de `~/.vibe/config.toml`, reemplazando solo el bloque anterior y conservando el resto del archivo (`LocalCliProviderNative.kt:61-100`).
- No hay botón de login (`hasAuth=false`, comentario `CliToolFragment.kt:217-219`).

## 6. Archivos y rutas que crea
- Paquete pip `mistral-vibe` + dependencias en el site-packages de Python de Termux (compartido con otros módulos Python).
- Binario/wrapper `vibe` en `$PREFIX/bin` (lo crea pip).
- `~/.vibe/config.toml` (lo crea la UI si se configura proveedor local; el propio `vibe` guarda ahí su config).
- Toolchain Rust/clang instalado como efecto colateral (varios cientos de MB, tamaño sin verificar).
- `~/.install_mistralvibe_checkpoint` (borrado al final). Registry: `mistralvibe.*`.

## 7. Seguridad
- `pip install mistral-vibe` sin pin de versión ni verificación de hash: se confía en PyPI.
- Prompt escapado con `shellEscape` antes de lanzar `vibe --prompt`.
- `api_key_env_var = ""` en el provider local: no se escribe ninguna clave (`LocalCliProviderNative.kt:70-100`). Sin secretos guardados por la app.
- Sin `curl | bash`.

## 8. Problemas y gaps detectados
- Confirmado: `deepUninstallPlan` solo hace `pip uninstall -y mistral-vibe` (`ModuleController.kt:896-900`): no quita `~/.vibe`, ni las dependencias transitivas ni el toolchain rust/clang instalado por el PASO 1.5.
- Confirmado: no hay login desde la UI; el usuario debe editar `config.toml` o exportar `MISTRAL_API_KEY` por su cuenta (sin UI para ello).
- Confirmado: el tamaño de catálogo (~40MB) no incluye el toolchain Rust que el script instala siempre (`:225-238`), aunque pip pudiera no necesitarlo (el script no intenta wheel prebuilt antes de instalar rust; sin verificar).
- Confirmado: `charset-normalizer` queda anclado a `3.3.2` solo en el camino de reparación; la instalación normal no lo fija.
- Sin verificar: `CARGO_BUILD_JOBS=1` evita la carrera de cargo/maturin (comentario `:251-260`) pero alarga la instalación notablemente.

## 9. Fecha de alta
2026-08-12.
