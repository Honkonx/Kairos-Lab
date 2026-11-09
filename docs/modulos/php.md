# PHP — módulo `php`

> Ficha basada en la lectura de `modulos/php.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `LENGUAJES.md`.
> Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
PHP CLI nativo (intérprete + extensiones del paquete Termux). No incluye servidor web ni Composer.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Módulos cargados" (`php -m`, 15 s) — LanguagesFragment.kt:53-54.

## 3. Instalación
- Script: `modulos/php.sh` (71 líneas; idéntico a `app/src/main/assets/scripts/php.sh`, verificado con `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `php`. Binario de verificación: `php`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: `install_single_pkg "php" "php" php` (`modulos/php.sh:67`).
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `php.installed` / `php.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: php`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `php --version` (kt:251-262). Acción: "Módulos cargados" (`php -m`, 15 s) — LanguagesFragment.kt:53-54.

## 6. Archivos y rutas
`$PREFIX/bin/php`; registry `~/.android_server_registry`; checkpoint `~/.install_php_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y php` (ModuleController.kt:965).

## 8. Problemas / limitaciones
La UI no permite lanzar `php -S` (servidor embebido) ni gestionar extensiones; sólo ver `php -m`. Composer no está contemplado (sin referencia en php.sh).

## 9. Fecha de alta
`modulos/php.sh`: 2026-08-18.
