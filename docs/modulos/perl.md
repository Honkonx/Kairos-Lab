# Perl — módulo `perl`

> Ficha basada en la lectura de `modulos/perl.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `LENGUAJES.md`.
> Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Intérprete Perl nativo. El `modules.json` lo describe como dependencia de herramientas (p. ej. PSQL Format del módulo Paquetes).

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Config" (`perl -V`, 15 s) — LanguagesFragment.kt:50-52.

## 3. Instalación
- Script: `modulos/perl.sh` (72 líneas; idéntico a `app/src/main/assets/scripts/perl.sh`, verificado con `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `perl`. Binario de verificación: `perl`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: sólo `install_single_pkg "perl" "perl" perl` (`modulos/perl.sh:68`).
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `perl.installed` / `perl.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: perl`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `perl --version` (kt:251-262). Acción: "Config" (`perl -V`, 15 s) — LanguagesFragment.kt:50-52.

## 6. Archivos y rutas
`$PREFIX/bin/perl`; registry `~/.android_server_registry`; checkpoint `~/.install_perl_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y perl` (ModuleController.kt:964). Sin limpieza de carpetas (el comentario de ModuleController.kt:956-958 indica que no tiene configuración propia).

## 8. Problemas / limitaciones
Desinstalarlo rompe `psqlformat` sin aviso (psqlformat.sh instala perl como pkg extra, según la descripción de modules.json y los comentarios de ModuleController.kt:976-980).

## 9. Fecha de alta
`modulos/perl.sh`: 2026-08-18.
