# Crear paquetes `.deb` de módulos

## Desde el APK

Pantalla del módulo **Repo** → "Crear .deb de un módulo" (`RepoFragment.runModuleDebPack`) → ejecuta
`moduledeb pack <id>`. El `.deb` queda en `/storage/emulated/0/Download/kairos/` (carpeta pública, para
compartirlo) con nombre `<package_name>[-variante]_<versión>_aarch64.deb`. Se empaqueta la instalación **ya
hecha** del módulo en ese dispositivo, no se reinstala nada.

## `moduledeb.sh`

| Subcomando | Qué hace |
|---|---|
| `pack <id>` | Resuelve el manifest (`<id>.sh --describe-files`, con fallback a `modulos/manifests/<id>.json`), copia los archivos reales en bloque (`tar \| tar`), escribe `control`, `manifest.json` (v2), `postinst` y `SHA256SUMS.txt`, y construye el `.deb` |
| `inspect <deb>` | Lee solo `manifest.json` + `SHA256SUMS.txt` y devuelve el veredicto en JSON (rc 2 = rechazado) |
| `reseal <deb> [salida]` | Convierte un `.deb` v1 al formato v2: reescribe el manifest y recalcula los hashes **del contenido actual**. Para paquetes propios viejos; no valida autenticidad |
| `extract <deb>` / `verify <dir>` / `apply <dir>` | Fases de instalación (ver [`flujo-instalacion.md`](flujo-instalacion.md)) |
| `install <deb>` | Encadena extract → verify → apply desde terminal |

`moduledeb` no ejecuta nunca el instalador completo del módulo (`claude.sh`, `opencode.sh`...): el `postinst` solo
corre las verificaciones y el parche liviano del manifest.

## Cómo un módulo declara qué se empaqueta (`--describe-files`)

Cada módulo imprime su manifest con `bash <id>.sh --describe-files`: `id`, `package_name`, `variant`,
`version_registry_key`, `files[]`, `file_globs[]`, `dependencies[]`, `verify_cmd`, `patch_cmd`, `not_covered[]`.
`moduledeb pack` añade `kairos_package`, `schema`, `pack_date`, `checksums_file` y, en cada dependencia, `kind` y
`packages[]`. Para que un requisito se resuelva solo (sin comandos libres), su `install_hint` debe ser
exactamente `pkg install -y <paquetes>` (se pueden encadenar con `&&`).

Los manifests de los módulos existentes no se modificaron para el formato v2: siguen siendo los de siempre y el
formato v2 se deduce al empaquetar.

## Pruebas rápidas en WSL

WSL Debian tiene `dpkg-deb`, `jq` y `sha256sum`, suficiente para `inspect` y `reseal` (no para `apply`, que escribe
en `/data/data/com.termux`). Ejemplo con un `.deb` v1 del módulo `claude`:

```
moduledeb inspect kairos-module-claude_2.1.251_aarch64.deb   # v1 → rc 2, "falta SHA256SUMS.txt"
moduledeb reseal  kairos-module-claude_2.1.251_aarch64.deb /tmp/claude_v2.deb
moduledeb inspect /tmp/claude_v2.deb                          # rc 0, requisitos: glibc-repo, glibc-runner, patchelf-glibc
```
