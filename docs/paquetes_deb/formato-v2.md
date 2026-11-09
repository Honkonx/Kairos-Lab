# Formato v2 de un `.deb` de módulo

Un `.deb` de Kairos es un `.deb` Debian normal (`dpkg-deb -b`) cuyo **área de control** (`DEBIAN/`) lleva,
además de `control` y `postinst`, dos archivos obligatorios.

## `DEBIAN/manifest.json`

Lo genera `moduledeb pack` a partir de `<id>.sh --describe-files` y le agrega los campos marcados con ★.

| Campo | Significado |
|---|---|
| `id` | id del módulo del catálogo (`claude`, `opencode`, ...). Obligatorio. |
| `package_name`, `variant`, `version_registry_key` | nombre del paquete, variante instalada (`native`, `gpu`, ...), clave del registry de la que sale la versión |
| `files[]` | rutas **exactas** que el paquete instala: `{path, required, note}` |
| `file_globs[]` | árboles/patrones: `{pattern, required, note}` (`dir/**` = todo el árbol, `dir/lib*.so` = un directorio) |
| `dependencies[]` | **requisitos**: `{id, check_cmd, install_hint}` + ★`kind` + ★`packages[]` (ver abajo) |
| `verify_cmd` | comando que dice si el módulo ya está bien instalado |
| `patch_cmd` | reparación liviana tras copiar (p. ej. `patchelf` del intérprete) |
| `not_covered[]` | limitaciones honestas: qué NO incluye el paquete |
| ★`kairos_package` | `true` — marca de paquete Kairos |
| ★`schema` | `2`. Menos de 2 se rechaza |
| ★`pack_date` | fecha de creación |
| ★`checksums_file` | `"SHA256SUMS.txt"` |

### Requisitos (`dependencies[]`)

Cada requisito tiene un `kind` que decide cómo se comprueba y se resuelve **sin ejecutar nada ajeno antes de
tiempo**:

| `kind` | Qué es | Cómo se comprueba antes de confirmar | Cómo se resuelve si falta |
|---|---|---|---|
| `package` | solo `pkg install -y <nombres>` (el instalador lo deduce al empaquetar si `install_hint` es exactamente eso) | `dpkg -s <cada paquete>` — sin `eval` | `pkg install -y` con nombres validados (`^[A-Za-z0-9+._-]+$`), sin shell |
| `addon` | complemento de Kairos (Tor, Syncthing, Restic, 7z...) — **reservado**, ningún módulo lo declara todavía | — | el complemento se instala con su `AddonEngine` |
| `module` | otro módulo del catálogo — **reservado** | — | instalación normal del módulo |
| `other` | cualquier otro comando | no se puede (queda `unknown`) | ejecuta `install_hint` **solo después** de la confirmación o de un origen verificado por hash |

`check_cmd` se evalúa recién en la fase `verify`, después de que el paquete pasó la inspección.

## `DEBIAN/SHA256SUMS.txt`

Formato estándar de `sha256sum` (se puede comprobar a mano con `sha256sum -c`):

```
# Kairos package checksums (sha256). Formato: <sha256>  <ruta relativa a />. '#' = comentario.
bc58dcd7...974d  DEBIAN/manifest.json
f4682aea...3b86  data/data/com.termux/files/home/.local/bin/claude
79343690...036c  data/data/com.termux/files/home/.local/share/claude-code/claude
# symlink data/data/.../lib/libfoo.so -> libfoo.so.1
```

- Primera línea de datos: el hash de `manifest.json`. Si no coincide, el manifest fue alterado → rechazo.
- Una línea por **archivo regular** del payload (rutas relativas a `/`).
- Los **enlaces simbólicos** van como comentario `# symlink <ruta> -> <destino>`.
- El conjunto debe ser **exactamente** el del paquete: un archivo de más, de menos o con otro hash → rechazo.

## Rutas permitidas

Cada archivo del payload debe cumplir, además de estar en el `.txt`:

- Su ruta absoluta (`/` + ruta relativa) está bajo `$HOME` (`/data/data/com.termux/files/home`) o `$PREFIX`
  (`/data/data/com.termux/files/usr`), sin componentes `..`.
- Está **declarada** en el manifest: igual a un `files[].path` o dentro de un `file_globs[].pattern`.
- `~/.bashrc`, `.profile`, `.bash_profile`, `.zshrc` y `~/.ssh/` solo se aceptan si el manifest los declara
  como ruta **exacta** en `files[]` (un glob nunca los cubre).
- No hay archivos especiales (dispositivos, fifos).

## Qué NO es

El `SHA256SUMS.txt` **dentro** del `.deb` protege contra corrupción y contra un manifest alterado, pero no
contra alguien que arma un paquete a propósito (cambia contenido y hash a la vez). La autenticidad viene del
hash publicado **aparte** en el Release ([`catalogo-github.md`](catalogo-github.md)); un archivo local no la tiene, y por eso pide
confirmación. Una firma (minisign/GPG) sería la mejora siguiente: no está implementada.
