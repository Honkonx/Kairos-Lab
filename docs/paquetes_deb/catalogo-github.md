# Catálogo en GitHub: `catalog.json` y `SHA256SUMS.txt`

Un archivo JSON aparte en GitHub identifica todos los `.deb`. Cada Release de
`Honkonx/Kairos-Lab` que publica paquetes lleva, además de los `.deb`, estos assets (los genera
`tools/moduledeb-release.sh`):

| Asset | Para qué |
|---|---|
| `catalog.json` | Identifica todos los paquetes del Release: `module_id`, `version`, `filename`, `size_bytes`, `arch` y **`sha256`** |
| `SHA256SUMS.txt` | Lo mismo en formato `sha256sum` (`<hash>  <archivo>`), comprobable con `sha256sum -c` |
| `kairos-modules.manifest.json`, `kairos-modules-index.json`, `index.md`, `kairos-modules.tar.xz` (+`.sha256`) | Ya existían: manifiesto del tarball e índice liviano |

## `catalog.json` v2

```json
{
  "generated": "2026-10-08T00:00:00Z",
  "schema": 2,
  "repo": "Honkonx/Kairos-Lab",
  "packages": [
    {"module_id": "claude", "version": "2.1.251", "filename": "kairos-module-claude_2.1.251_aarch64.deb",
     "size_bytes": 66962868, "arch": "aarch64", "sha256": "<64 hex>"}
  ]
}
```

El `catalog.json` v1 no lleva `sha256` ni `schema`.

## Cómo lo usa la app

`ModuleDebInstaller.findGithubAsset` recorre los Releases, toma el `.deb` más reciente del módulo y, **en el mismo
Release**, busca `catalog.json` (o `SHA256SUMS.txt`) para sacar el hash de ese archivo. Descarga a una carpeta
privada, calcula el sha256 y lo compara; si no coincide, borra y aborta. **Si el Release no publica el hash, no
se instala** (no hay forma de verificarlo).

El hash viaja por un canal distinto al del `.deb` (otro asset del Release), a diferencia del `.txt` interno.
Sigue confiando en el repositorio: una firma `minisign`/GPG con la clave pública dentro del APK sería lo
siguiente (no implementada).

## Publicar

```
bash tools/moduledeb-release.sh [--debs-dir DIR] --release-tag <tag> --upload
```

Genera `catalog.json` y `SHA256SUMS.txt` junto a los demás archivos y los sube con `gh release create/upload`.

## Paquetes en formato v1

Los paquetes `.deb` en formato v1 (sin `manifest.json` ni `SHA256SUMS.txt` internos y sin hash en el catálogo)
no se instalan: hay que regenerarlos con `moduledeb pack` o convertirlos con `moduledeb reseal`, y publicar el
nuevo `catalog.json`.
