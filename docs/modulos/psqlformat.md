# PSQLFORMAT — módulo `psqlformat` (formateador de SQL)

> Basado en la lectura del código. Resumen del conjunto en `PAQUETES.md`. No probado en dispositivo.

## 1. Qué es
Formateador de SQL (`psqlformat`, paquete npm; requiere Perl). `modules.json`: `psqlformat`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]` (Perl NO figura en `requires`), `terminalCommand: "psqlformat"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN (con una acción nativa propia)
Instala el binario y ofrece un flujo de archivo real (selector SAF + vista del SQL formateado), sin servicio.
Opciones de UI: 2 (switch + tarjeta «Formatear archivo .sql»).

## 3. Instalación
- `modulos/psqlformat.sh:85` → `install_npm_global "psqlformat" "psqlformat" "psqlformat" perl`: el 4º argumento hace
  `pkg_update_with_fallback` + `pkg install -y perl` ANTES del `npm install -g` (`lib.sh:613-625`); luego `fix_npm_shebang_wrapper`,
  `verify_binary_installed psqlformat` (`--version`) y `registry_install`.
- `--describe-files` declara dependencias `node` y `pkg:perl` (`psqlformat.sh:~58-61`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Ejecución
Al elegir un archivo, `PackagesFragment.formatSqlFile` copia el Uri SAF a un temporal en `cacheDir` (`psqlformat_input_<ts>.sql`),
corre `psqlformat '<ruta>'` (20 s), borra el temporal y muestra el SQL en un diálogo monoespaciado seleccionable (`PackagesFragment.kt:240-294`).
Salida a stdout; el archivo original no se modifica.

## 5. Opciones de la UI (`PackagesFragment.kt:143-146`, `:217-233`)
1. Switch instalar/desinstalar (`npm uninstall -g psqlformat`; NO desinstala Perl, `ModuleController.kt:988`).
2. Tarjeta «Formatear archivo .sql»: botón «Elegir archivo» (`GetContent("*/*")`).

## 6. Archivos y rutas
`$PREFIX/bin/psqlformat`, `$(npm root -g)/psqlformat`, temporal `cacheDir/psqlformat_input_*.sql`, registry `psqlformat.version`.

## 7. Seguridad
El archivo elegido se copia a `cacheDir` y se elimina en éxito y error. Ruta citada con comillas simples en `bash -c` (la ruta sale de
`System.currentTimeMillis`, no del usuario). Sin secretos.

## 8. Problemas / limitaciones
- El resultado solo se muestra en pantalla; no hay «guardar» ni «copiar» explícito (el texto es seleccionable) ni escribir de vuelta al archivo.
- `modules.json` no declara Perl en `requires`; la dependencia solo se resuelve dentro del script.
- El selector acepta `*/*` (cualquier archivo), sin validar extensión `.sql`.

## 9. Fecha de alta
`modulos/psqlformat.sh`: 2026-08-18.
