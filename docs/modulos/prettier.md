# PRETTIER — módulo `prettier` (formateador de código)

> Basado en la lectura del código. Resumen del conjunto en `PAQUETES.md`. No probado en dispositivo.

## 1. Qué es
Prettier (paquete npm `prettier`), formateador de código. `modules.json`: `prettier`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]`, `terminalCommand: "prettier"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Instala el binario y expone 2 acciones sobre `$HOME`. Sin servicio. Opciones de UI: 3 (switch + 2 acciones).

## 3. Instalación
- `modulos/prettier.sh:81` → `install_npm_global "prettier" "prettier" "prettier"` (flujo de `lib.sh:613`:
  omitir si existe sin `--force` → `ensure_node_installed` → `npm install -g` → `fix_npm_shebang_wrapper` →
  `verify_binary_installed` (`prettier --version`) → `registry_install`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Ejecución
Sin proceso residente; las acciones corren `cd "$HOME" && <comando>` y muestran el resultado en un diálogo.

## 5. Opciones de la UI (`PackagesFragment.kt:86-104`, pestaña «Formato y calidad»)
1. Switch instalar/desinstalar (`npm uninstall -g prettier`).
2. «Comprobar formato» → `prettier --check .` (solo lectura; lista los archivos con problemas, `formatPrettierCheck`).
3. «Aplicar formato» → `prettier --write .` (60 s), con confirmación previa (`requiresConfirmation`), porque reescribe archivos.

## 6. Archivos y rutas
`$PREFIX/bin/prettier`, `$(npm root -g)/prettier`, registry `prettier.version`. Opera sobre `$HOME` recursivo.

## 7. Seguridad
`--write .` sobre `$HOME` muta archivos reales (mitigado con diálogo de confirmación; no hay copia de respaldo ni dry-run). Sin secretos.

## 8. Problemas / limitaciones
- Ambas acciones corren en `$HOME`, no en un proyecto elegido; `prettier --write .` sobre todo el home puede tocar archivos ajenos al usuario (dotfiles de otros módulos con extensiones soportadas, aunque `.prettierignore`/node_modules se respetan por defecto de Prettier).
- Sin selector de carpeta ni de archivos concretos.
- Inferencia: no se verificó el comportamiento real sobre un `$HOME` grande (tiempo de 60 s).

## 9. Fecha de alta
`modulos/prettier.sh`: 2026-08-18.
