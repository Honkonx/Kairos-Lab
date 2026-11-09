# Kotlin — módulo `kotlin`

> Ficha basada en la lectura de `modulos/kotlin.sh`, `modules.json`, `LanguagesFragment.kt`, `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `lenguajes.md`. Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Compilador Kotlin (`kotlinc`) nativo. Según el comentario de `modulos/kotlin.sh:7-10`, el paquete Termux declara OpenJDK 17 como dependencia apt (no verificado contra apt).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) = 1 control.
Acción extra: ninguna (sin RunAction) — LanguagesFragment.kt:74.

## 3. Instalación
- Script: `modulos/kotlin.sh` (87 líneas; idéntico a `app/src/main/assets/scripts/kotlin.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `kotlin`. Binario de verificación: `kotlinc`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- NO usa `install_single_pkg()`: instala con `pkg install -y kotlin` y verifica con `verify_binary_installed kotlinc "-version"` (`modulos/kotlin.sh:76-82`) porque `kotlinc` no acepta `--version` (documentado en el propio script). Registra la versión con `kotlinc -version` + grep (`kotlin.sh:83`). Un comentario en ModuleInstalled.kt:161 todavía dice que usa `install_single_pkg`.
- Verificación: `verify_binary_installed` (`modulos/lib.sh:510-528`): `command -v` + ejecutar el flag de versión antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `kotlin.installed` / `kotlin.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: kotlinc`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `kotlinc -version` (kt:251-262). Acción: ninguna (sin RunAction) — LanguagesFragment.kt:74.

## 6. Archivos y rutas
`$PREFIX/bin/kotlinc`; registry `~/.android_server_registry`; checkpoint `~/.install_kotlin_checkpoint` (declarado en el script).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: **no existe `DeepUninstallPlan` para `kotlin`** — el switch OFF sólo resetea estado; el paquete y OpenJDK siguen instalados.

## 8. Problemas / gaps
Gap real: sin desinstalación real, igual que Ruby. La versión se lee con `redirectErrorStream(true)` (ConsolidatedModuleFragment.kt:257), por lo que la salida a stderr de `kotlinc -version` sí se captura. Sin acción extra de UI.

## 9. Fecha de alta
`modulos/kotlin.sh`: 2026-08-25.
