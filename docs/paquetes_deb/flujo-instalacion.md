# Flujo de instalación de un `.deb` (igual para GitHub, local y creado en el APK)

Entrada común: `ModuleDebInstaller.installFromDeb(debFile, expectedSha256?, confirm, onProgress)`.
Se llama siempre desde un hilo de fondo.

```
 origen ──► [0] hash del archivo (solo si hay canal de confianza)
            [1] moduledeb inspect   → lee SOLO manifest.json + SHA256SUMS.txt
            [2] confirmación         → solo si NO hay hash externo (paquete local)
            [3] moduledeb extract    → control primero, luego payload; todo contra el .txt
            [4] moduledeb verify     → compara con el sistema (requisitos, archivos)
            [5] resolver requisitos  → descargar lo que falte (pkg) y re-verificar
            [6] moduledeb apply      → copiar lo declarado, verify_cmd / patch_cmd
```

## [0] Hash del archivo

Solo GitHub: el `.deb` se descarga a una carpeta **privada** de la app
(`~/kairos_local/module_deb_downloads/`, no a la carpeta pública `Download/kairos/`, para que otra app no pueda
reemplazarlo entre la descarga y la comprobación) y su sha256 se compara con el publicado en el Release
(`catalog.json` o `SHA256SUMS.txt`). Si no coincide, se borra y se aborta. Si el Release **no publica** el hash,
no se instala.

## [1] `moduledeb inspect` — lo primero que se lee

`dpkg-deb -e` extrae **solo el área de control**; no se toca el payload ni se ejecuta nada del paquete. Se
comprueba (rc 2 = no instalar, con los motivos):

- existen `DEBIAN/manifest.json` **y** `DEBIAN/SHA256SUMS.txt`;
- el manifest es JSON válido, trae `kairos_package:true`, `id` y `schema >= 2`;
- el hash de `manifest.json` coincide con su línea en el `.txt`.

Devuelve un JSON con lo que el paquete declara: `id`, `version`, `files_count`, `declared_paths`, `requires`
(cada requisito con su `status`: `present`/`missing`/`unknown`) y `runs_commands` (si ejecuta `verify_cmd`/
`patch_cmd`, y los `install_hint` de tipo `other`).

## [2] Confirmación

Un archivo local no tiene hash externo. La Tienda muestra un diálogo con el resumen (nº de archivos, requisitos
que faltan, si ejecuta comandos de verificación/reparación y el aviso de que no hay hash de un canal de
confianza). Sin "Instalar" no se sigue. Un origen GitHub con hash coincidente no pregunta.

## [3] `moduledeb extract`

1. Extrae el control a un directorio temporal y repite la comprobación del paso 1.
2. Recién entonces extrae el payload y exige que **sea exactamente** lo que lista el `.txt` (mismos archivos,
   mismos hashes, mismos enlaces simbólicos) y que cada ruta cumpla la política de [`formato-v2.md`](formato-v2.md).
3. Deja un marcador `.kairos_checked`; `apply` se niega a trabajar sobre un directorio sin él.

Cualquier fallo borra el directorio temporal y aborta con el motivo.

## [4]–[5] Comparar con el sistema y resolver requisitos

`verify` evalúa cada `check_cmd` y compara archivos (`install` / `overwrite` / `skip_identical`). Si faltan
requisitos:

- `kind: package` → `pkg install -y <nombres>` (nombres validados, sin shell);
- `kind: other` → su `install_hint`, solo porque ya hubo confirmación u origen verificado.

Se vuelve a verificar. Si siguen faltando, se aborta y no se copia nada.

## [6] `moduledeb apply`

Copia el payload (ya verificado, por lo tanto solo lo declarado) a su ruta real, corre `verify_cmd`; si falla,
`patch_cmd` y vuelve a verificar. Emite el evento `moduledeb:apply_done`.

## Qué todavía depende de la confianza en el autor

`verify_cmd`, `patch_cmd` y `check_cmd` se ejecutan con `eval`/`bash -c`: son comandos del manifest. Con hash
publicado por el Release el riesgo es el de confiar en el repo; con un archivo local, el diálogo del paso 2 es
la única barrera. Es una limitación conocida del diseño (los módulos necesitan, p. ej., `patchelf`).
