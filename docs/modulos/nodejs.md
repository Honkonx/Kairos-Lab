# Node.js LTS — módulo `nodejs`

> Ficha basada en la lectura de `modulos/nodejs.sh`, `modules.json`, `LanguagesFragment.kt`, `ConsolidatedModuleFragment.kt`, `ModuleController.kt` y `lib.sh`. Contenedor: ver `lenguajes.md`. Lo marcado "inferencia" no fue probado en dispositivo.

## 1. Qué es y para qué sirve
Runtime JavaScript (Node.js LTS con npm) nativo de Termux. Es además dependencia compartida de ~10 módulos npm (`ensure_node_installed()` en `modulos/lib.sh:533`) y de las herramientas del módulo Paquetes.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**, no módulo funcional: sin pantalla propia (`internal: true` en modules.json; es una
fila de `LanguagesFragment` sobre `ConsolidatedModuleFragment`), sin servicio, sin puerto.
Opciones de UI reales: 1 switch (instala/desinstala) + 1 botón de acción = 2 controles.
Acción extra: "Paquetes npm -g" (`npm list -g --depth=0`, 20 s) — LanguagesFragment.kt:47-49.

## 3. Instalación
- Script: `modulos/nodejs.sh` (76 líneas; idéntico a `app/src/main/assets/scripts/nodejs.sh`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (manifiesto `files: []` deliberado para `moduledeb`: paquete apt gestionado por pkg).
- Paquete Termux: `nodejs-lts`. Binario de verificación: `node`.
- Sin dependencias en modules.json (`requires: []`), sin variantes (`installMethods: ["standard"]`).
- Tras instalar, habilita Corepack si existe (`modulos/nodejs.sh:71-73`, `corepack enable`) para tener pnpm/yarn. Única diferencia real con el resto de lenguajes. El paso es best-effort: su fallo no aborta ni se reporta.
- Verificación: `verify_binary_installed` dentro de `install_single_pkg()` (`modulos/lib.sh:510-528`): `command -v` + ejecutar `--version` antes de `registry_install`. Si ya está instalado y no hay `--force`, sólo escribe `installed=true`.
- Registry: `nodejs.installed` / `nodejs.version` en `~/.android_server_registry`.

## 4. Ejecución
Sin proceso ni servicio. modules.json declara `terminalCommand: node`, pero `ConsolidatedModuleFragment` no ofrece botón "Abrir en terminal": el campo no se usa en esta pantalla.

## 5. Funciones de la UI
Switch ON: `ModuleController.installModule` (ConsolidatedModuleFragment.kt:271-282). Switch OFF: diálogo de confirmación + `deepUninstallModule` (284-322). Estado/versión: `ModuleInstalled.isInstalledRobust` + versión del registry o `node --version` (kt:251-262). Acción: "Paquetes npm -g" (`npm list -g --depth=0`, 20 s) — LanguagesFragment.kt:47-49.

## 6. Archivos y rutas
`$PREFIX/bin/node`; registry `~/.android_server_registry`; checkpoint `~/.install_nodejs_checkpoint` (declarado en el script).

## 7. Seguridad
Sin secretos; red sólo vía `pkg`. Los comandos de las acciones son fijos (sin entrada del usuario). Desinstalación: `pkg uninstall -y nodejs-lts; rm -rf ~/.npm ~/.npmrc ~/.node_repl_history ~/.config/yarn ~/.cache/yarn` (ModuleController.kt:959-963). Riesgo real: desinstalar Node deja sin runtime a los módulos npm que dependen de él (n8n nativo, CLIs de IA, Paquetes); la UI sólo pide un diálogo de confirmación genérico, sin avisar de esa dependencia (ConsolidatedModuleFragment.kt:284-306).

## 8. Problemas / gaps
La desinstalación no avisa que otros módulos dependen de Node (impacto inferido; el código no lo verifica). `~/.npm` se borra con la caché, sin opción de conservar paquetes globales.

## 9. Fecha de alta
`modulos/nodejs.sh`: 2026-08-18.
