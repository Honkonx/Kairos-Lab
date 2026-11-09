# Go — módulo `golang`

> Contenedor: ver `LENGUAJES.md`. Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Toolchain Go (compilador + stdlib) nativo de Termux; ~120 MB declarado.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Entorno" (`go env`, 15 s) — LanguagesFragment.kt:64-65.

## 3. Instalación
- Script: `modulos/golang.sh` (71 líneas; idéntico a `app/src/main/assets/scripts/golang.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `golang`. Binario de verificación: `go`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Sin pasos extra: `install_single_pkg "golang" "go" golang` (`modulos/golang.sh:67`). El id es `golang` pero el binario es `go` (mapeado en ModuleInstalled.kt:132).
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, solo escribe `installed=true`.
- Registry: `golang.installed` / `golang.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: go`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `go version` (kt:251-262). Acción: "Entorno" (`go env`, 15 s) — LanguagesFragment.kt:64-65.

## 6. Archivos y rutas
`$PREFIX/bin/go`; registry `~/.android_server_registry`; checkpoint `~/.install_golang_checkpoint` (declarado en el script, `install_single_pkg` no lo usa).

## 7. Seguridad
Sin secretos; red solo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y golang; rm -rf ~/.cache/go ~/.cache/go-build` (ModuleController.kt:971-974). No borra `~/go` (GOPATH).

## 8. Limitaciones conocidas
`~/go` queda huérfano al desinstalar (según lectura del plan de desinstalación; no probado).

## 9. Fecha de alta
`modulos/golang.sh`: 2026-08-18.
