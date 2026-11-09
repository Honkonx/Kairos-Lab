# NCU.md — módulo `ncu` (npm-check-updates)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
`npm-check-updates` (comando `ncu`): revisa y actualiza versiones de dependencias de un `package.json`.
`modules.json`: `ncu`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "ncu"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Instala el binario y expone 2 acciones sobre `$HOME/package.json`. Sin servicio. Opciones de UI: 3 (switch + 2 acciones).

## 3. Instalación
`modulos/ncu.sh:81` → `install_npm_global "ncu" "npm-check-updates" "ncu"` (`lib.sh:613`; post-condición `ncu --version`;
`registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/npm-check-updates/**`).
Nota: el id del módulo (`ncu`) difiere del paquete npm (`npm-check-updates`); la desinstalación usa el nombre del paquete
(`ModuleController.kt:989`).

## 4. Ejecución
Acciones con `cd "$HOME" && <comando>`, salida en diálogo al final.

## 5. Opciones de la UI (`PackagesFragment.kt:147-166`, pestaña «Formato y calidad»)
1. Switch instalar/desinstalar.
2. «Revisar actualizaciones» → `ncu` (30 s, solo lectura; lista `nombre ^actual → ^nueva`, `formatNcuOutput`).
3. «Actualizar package.json» → `ncu -u` (30 s) con confirmación previa; reescribe `package.json`.

## 6. Archivos y rutas
`$PREFIX/bin/ncu`, `$(npm root -g)/npm-check-updates`, `$HOME/package.json` (objetivo), registry `ncu.version`.

## 7. Seguridad
`ncu -u` muta `package.json` (con confirmación; no hace backup ni ejecuta `npm install`). Sin secretos.

## 8. Problemas / gaps
- Solo opera sobre `$HOME/package.json`; normalmente no existe uno ahí y el resultado es «No package.json found» (el propio formatter lo admite, `PackagesFragment.kt:298`). No hay selector de proyecto.
- No ofrece `ncu -i`, `--target`, ni filtros.
- 30 s de timeout puede quedarse corto con red lenta (consulta el registro npm).

## 9. Fecha de alta
`modulos/ncu.sh`: 2026-08-18.
