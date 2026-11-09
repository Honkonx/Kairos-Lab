# Ruby — módulo `ruby`

> Ficha basada en la lectura de `modulos/ruby.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `LENGUAJES.md`.
> Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Intérprete Ruby + `gem` nativos de Termux.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Gems" (`gem list`, 15 s) — LanguagesFragment.kt:72-73.

## 3. Instalación
- Script: `modulos/ruby.sh` (70 líneas; idéntico a `app/src/main/assets/scripts/ruby.sh`, verificado con `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `ruby`. Binario de verificación: `ruby`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: `install_single_pkg "ruby" "ruby" ruby` (`modulos/ruby.sh:67`). El ítem en modules.json no declara `iconAsset` (usa emoji).
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `ruby.installed` / `ruby.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: ruby`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `ruby --version` (kt:251-262). Acción: "Gems" (`gem list`, 15 s) — LanguagesFragment.kt:72-73.

## 6. Archivos y rutas
`$PREFIX/bin/ruby`; registry `~/.android_server_registry`; checkpoint `~/.install_ruby_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: **no existe `DeepUninstallPlan` para `ruby`** (sin coincidencias en ModuleController.kt). El switch OFF corre `deepUninstallModule` y, con `plan == null`, sólo resetea el estado (ModuleController.kt:1066-1068, mensaje "desinstalación real no soportada") — el paquete `ruby` sigue instalado en Termux.

## 8. Problemas / limitaciones
Limitación real (leída en código): el switch OFF no desinstala el paquete; al refrescar, `isInstalledRobust` seguirá viendo `ruby` y el switch vuelve a ON (comportamiento inferido, no probado en dispositivo). LENGUAJES.md no lo menciona.

## 9. Fecha de alta
`modulos/ruby.sh`: 2026-08-25.
