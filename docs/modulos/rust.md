# Rust — módulo `rust`

> Ficha basada en la lectura de `modulos/rust.sh`, `modules.json`, `LanguagesFragment.kt`,
> `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `LENGUAJES.md`.
> Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Toolchain Rust (rustc + cargo) del paquete Termux. Tamaño declarado ~200 MB en modules.json.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Binarios cargo install" (`cargo install --list`, 15 s) — LanguagesFragment.kt:55-57.

## 3. Instalación
- Script: `modulos/rust.sh` (71 líneas; idéntico a `app/src/main/assets/scripts/rust.sh`, verificado con `cmp`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `rust`. Binario de verificación: `rustc`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: `install_single_pkg "rust" "rustc" rust` (`modulos/rust.sh:68`).
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `rust.installed` / `rust.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: rustc`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `rustc --version` (kt:251-262). Acción: "Binarios cargo install" (`cargo install --list`, 15 s) — LanguagesFragment.kt:55-57.

## 6. Archivos y rutas
`$PREFIX/bin/rustc`; registry `~/.android_server_registry`; checkpoint `~/.install_rust_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y rust; rm -rf ~/.cargo` (ModuleController.kt:966-969). Borrar `~/.cargo` elimina también los binarios instalados con `cargo install`.

## 8. Problemas / limitaciones
El diálogo de confirmación genérico no advierte que `~/.cargo` se borra (según el plan de desinstalación). Sin selector de versión (pkg ofrece una sola).

## 9. Fecha de alta
`modulos/rust.sh`: 2026-08-18.
