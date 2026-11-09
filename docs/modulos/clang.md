# C/C++ (Clang) — módulo `clang`

> Contenedor: ver `LENGUAJES.md`. Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Compilador Clang (C/C++) y herramientas LLVM del paquete Termux; ~150 MB declarado.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) = 1 control.
Acción extra: ninguna a propósito (comentario en LanguagesFragment.kt:58-62).

## 3. Instalación
- Script: `modulos/clang.sh` (72 líneas; idéntico a `app/src/main/assets/scripts/clang.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `clang`. Binario de verificación: `clang`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: `install_single_pkg "clang" "clang" clang` (`modulos/clang.sh:68`). Es el lenguaje con menos UI: solo switch y versión.
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, solo escribe `installed=true`.
- Registry: `clang.installed` / `clang.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: clang`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `clang --version` (kt:251-262). Acción: ninguna a propósito (comentario en LanguagesFragment.kt:58-62).

## 6. Archivos y rutas
`$PREFIX/bin/clang`; registry `~/.android_server_registry`; checkpoint `~/.install_clang_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red solo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y clang` (ModuleController.kt:970). Sin limpieza propia.

## 8. Limitaciones conocidas
Ninguna opción útil en UI (no hay "compilar archivo"/proyecto). `python.sh` también instala `clang` (PASO 4, python.sh:237-250), así que desactivarlo aquí puede dejar a Python sin compilador para pips nativos (inferencia, no probado en dispositivo).

## 9. Fecha de alta
`modulos/clang.sh`: 2026-08-18.
