# TYPESCRIPT.md — módulo `typescript` (TypeScript / `tsc`)

> Basado en la lectura del código. Ficha corta; el resumen del conjunto de las 10 herramientas npm
> está en `PAQUETES.md`. No probado en dispositivo: todo lo marcado "Verificado en código" es lectura de código.

## 1. Qué es
Compilador `tsc` de TypeScript instalado como paquete npm global (`modules.json`: `typescript`,
`type: Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "tsc"`, ~15MB).

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Solo instala el binario y expone 1 acción puntual (`tsc --init`). No tiene servicio, estado ni
pantalla propia: vive como una fila con switch dentro de `PackagesFragment`. Opciones de UI: 2
(switch instalar/desinstalar + «Crear tsconfig.json»).

## 3. Instalación
- `modulos/typescript.sh` (146 líneas). Flags: `--silent`, `--force`, `--describe`,
  `--describe-files` (manifiesto para `moduledeb`, líneas 43-80).
- A diferencia de las otras 9, NO usa `install_npm_global()`: instala a mano
  (`ensure_node_installed` → `npm install -g typescript` → `fix_npm_shebang_wrapper` → parche de
  plataforma → `verify_binary_installed "tsc"` → `registry_install`), `typescript.sh:~95-141`.
  Motivo documentado en el propio script: `install_npm_global` verifica `tsc --version` antes de
  que el parche llegue a correr.
- Parche de plataforma: TypeScript 7.x trae `tsc` nativo en Go; no existe el paquete
  `@typescript/typescript-android-arm64`, así que el script instala `@typescript/typescript-linux-arm64@<misma versión>`,
  lo copia a `typescript-android-arm64`, corrige el `name` del `package.json` y hace `chmod +x lib/tsc`.
- Verificación: `verify_binary_installed "tsc"` (`lib.sh:501`) + `registry_install typescript <versión>`.
  Rama «ya instalado» (`command -v tsc`, sin `--force`) solo hace `registry_write typescript installed=true` (sin versión).

## 4. Ejecución
No es un servicio. La acción de UI corre `bash -c 'cd "$HOME" && tsc --init'` (30 s por defecto,
`ConsolidatedModuleFragment.runItemAction`).

## 5. Opciones de la UI (`PackagesFragment.kt:56-69`, pestaña «Formato y calidad», `:203`)
1. Switch instalar/desinstalar (desinstalación: `npm uninstall -g typescript`, `ModuleController.kt:981`).
2. «Crear tsconfig.json» → `tsc --init` en `$HOME` (no en un proyecto elegido).

## 6. Archivos y rutas
Binario `$PREFIX/bin/tsc` (wrapper bash), `$(npm root -g)/typescript`, `$(npm root -g)/@typescript/typescript-android-arm64`,
registry `~/.android_server_registry` (`typescript.version`), checkpoint `~/.install_typescript_checkpoint` (declarado, no usado en el flujo manual).

## 7. Seguridad
Sin secretos. `npm install -g` sin versión pineada ni verificación de integridad propia
(supply-chain npm estándar). El parche copia un paquete de otra plataforma (linux-arm64) con `--force`.

## 8. Problemas / limitaciones
- `tsc --init` corre siempre en `$HOME`; no hay selector de proyecto ni acción de compilar/`--noEmit`.
- El parche depende de que exista `@typescript/typescript-linux-arm64@<versión>`; si `node -e` no obtiene la versión, el `npm install` se omite y falla recién en el chequeo posterior.
- Inferencia: el parche está validado según los comentarios del script, no re-probado.

## 9. Fecha de alta
`modulos/typescript.sh`: 2026-08-18.
