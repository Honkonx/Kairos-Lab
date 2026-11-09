# REPO.md — Repo apt local + empaquetado `.deb` (`repo.sh` + `moduledeb.sh`)

## Qué son (2 mecanismos hermanos, no confundir)

Kairos tiene **dos** scripts de empaquetado `.deb`, con propósitos distintos:

| Script | Empaqueta | Consumidor típico | UI |
|---|---|---|---|
| `modulos/repo.sh` | Paquetes **apt/pkg reales** ya instalados en el dispositivo (`repo pack <paquete>`, ej. `nano`) — usa `dpkg -L` + `dpkg-deb -b`, preserva scripts de mantenimiento reales (`postinst`/`prerm`/etc.) leídos de `$PREFIX/var/lib/dpkg/info/` | Reinstalar el MISMO paquete apt en otro dispositivo, vía `pkg install kairos-<id>` desde el repo local propio | `RepoFragment.kt` |
| `modulos/moduledeb.sh` | **Instaladores propios de Kairos** que NO pasan por `dpkg` (Claude Code, OpenCode, n8n — descargan binarios sueltos, los parchean con `patchelf`, escriben scripts propios en `$HOME/scripts/`) | Reempaquetar una instalación de módulo YA HECHA y parcheada, para reinstalarla en otro dispositivo sin re-descargar/re-parchear desde cero | `RepoFragment.kt` (misma pantalla, sección separada) |

Ambos reusan el mismo formato de `.deb` real (`debian-binary` + `control.tar.xz` + `data.tar.xz`,
vía `dpkg-deb -b` con fallback manual `ar`+`tar`) pero son código independiente — `moduledeb.sh`
reimplementa `_moduledeb_build_deb` en vez de sourcear `repo.sh`, porque `repo.sh` parsea `"$@"`
del proceso que lo invoca para su propio dispatch de subcomandos (ver comentario de cabecera de
`moduledeb.sh`).

## `repo.sh` — repo apt local

Subcomandos: `init` (crea `$PREFIX/../kairos-repo` con estructura `dists/stable/main/
binary-aarch64`), `add <módulo_id>` (empaqueta un binario+scripts de un módulo de Kairos con
prefijo `kairos-<id>`), `pack <paquete>` (repackea CUALQUIER paquete apt ya instalado, sin
prefijo — reinstala el mismo paquete), `publish` (regenera `Packages`/`Packages.gz`/`Release`),
`remove <paquete>` (saca uno o más `.deb` del repo local, busca tanto `<paquete>_*.deb` como
`kairos-<paquete>_*.deb`), `source` (imprime la línea `deb [trusted=yes] file://...` para agregar
a `sources.list.d/`), `keys` y `sign <KEYID>`.

Registro de paquetes empaquetados con `repo pack` en `$HOME/kairos_local/repo_registry.json`
(upsert por paquete: `package`, `version`, `date`, `deb_path`, `scripts_preserved`) — separado
del registry global de módulos de Kairos (`$HOME/.android_server_registry`).

No usa `dpkg-repack` (no está empaquetado para Termux) — replica manualmente lo que
hace por dentro: `dpkg -L <paquete>` para los archivos ya instalados + lectura directa de
`$PREFIX/var/lib/dpkg/info/<paquete>.{preinst,postinst,prerm,postrm,conffiles}` para los scripts
de mantenimiento reales (`dpkg -e`/`--control` no sirve acá porque opera sobre un `.deb` real en
disco, no sobre un paquete instalado por nombre — no existe el `.deb` original, ese es justo el
problema que resuelve este subcomando).

El formato del repo es Debian real (`Packages`/`Packages.gz`/`Release` con checksums MD5/SHA1/
SHA256), con firma GPG opt-in (`InRelease` clearsign + `Release.gpg` detached, mismo mecanismo que
`apt-secure(8)`; nunca genera una clave nueva).

## `moduledeb.sh` — empaquetado de módulos propios de Kairos

Subcomandos: `pack <id>` (empaqueta según el manifest del módulo), `install <ruta.deb>`
(instala/aplica — hoy en un solo paso: `dpkg -i`, o fallback manual `ar`+`tar` que extrae y corre
el `postinst` en la misma pasada), `list` (lista manifests disponibles).

El `.deb` generado lleva el manifest completo embebido como `DEBIAN/manifest.json`, y un
`postinst` genérico (el mismo para cualquier módulo) que en el dispositivo destino:

1. Corre `dependencies[].check_cmd` de cada dependencia y avisa qué falta (con `install_hint`)
2. Corre `verify_cmd` — si ya pasa, termina sin tocar nada más
3. Si hay dependencias faltantes, se detiene ahí (no intenta parchear sin las dependencias)
4. Si no, corre `patch_cmd` (si el manifest lo define) y vuelve a verificar

Hay manifests escritos a mano en `modulos/manifests/` para tres módulos:

| Manifest | Variante cubierta | Parche real | Limitación (`not_covered`) |
|---|---|---|---|
| `claude.json` | `native` (ELF+glibc-runner) | `patchelf --set-interpreter` sobre glibc-runner | Variante `legacy` (npm) sin cubrir |
| `opencode.json` | única (nativo glibc) | ninguno real, solo re-`chmod` | No captura libs/assets nuevos si el paquete upstream crece — solo binario+runtime conocidos |
| `n8n.json` | `udocker` | ninguno real (`setup --execmode=P2` best-effort) | La imagen udocker (~800MB) y el contenedor NO se empaquetan — solo scripts de control; datos/workflows del usuario tampoco |

Tres manifests a mano no escalan a todo el catálogo de módulos, por eso existe el diseño del
mecanismo genérico (sección "Diseño: manifest genérico + instalación en 2 fases").

## UI — `RepoFragment.kt`

Una sola pantalla cubre ambos mecanismos: sección "Repositorio propio" (`repo init`/`publish`/
`source`, tabla de paquetes instalados con botón "Empaquetar" por fila → `repo pack <paquete>`) y
sección "Crear .deb de módulo" (un botón "Crear .deb de <módulo>" por cada módulo instalado, que
invoca `moduledeb.sh pack <id> --silent`). El registro de `.deb` ya generados se lee de
`repo_registry.json` para mostrar tamaño/fecha reales.

## Diseño: manifest genérico + instalación en 2 fases

Documentado en detalle en **`docs/paquetes_deb/index.md`**:

- Los manifests se generan dinámicamente llamando `modulos/<id>.sh --describe-files` (extensión
  del contrato `--describe` existente) en vez de leer JSON escrito a mano — así el mecanismo
  escala a cualquier módulo del catálogo sin mantener un archivo por módulo.
- `moduledeb install` se separa en 3 fases explícitas — `extract` (solo descomprime, no ejecuta
  nada) → `verify` (compara el manifest contra el estado real del dispositivo, muestra un resumen)
  → `apply` (recién ahí copia archivos y corre `patch_cmd` si hace falta). `install` se mantiene
  como atajo que encadena las 3 para uso desde terminal.
- Los manifests a mano quedan como fallback opcional (siguen funcionando si un módulo todavía no
  implementa `--describe-files`).

Ver ese documento para el contrato JSON completo.

## Referencias

- `modulos/repo.sh`, `modulos/moduledeb.sh` — código real
- `modulos/manifests/{claude,opencode,n8n}.json` — manifests a mano
- `app/src/main/java/com/termux/app/ui/RepoFragment.kt` — UI
- `docs/paquetes_deb/index.md` — diseño del mecanismo genérico

## Controles de la pantalla (`RepoFragment.kt`)

| Control | Qué hace | Por qué |
|---|---|---|
| Card "REPO APT LOCAL" — texto | Aclara que este repo (`pkg install kairos-<módulo>`) es distinto de "Crear .deb" (empaqueta paquetes YA instalados de cualquier tipo) | Los 2 flujos comparten pantalla pero resuelven cosas distintas |
| "⚙ Inicializar/reparar repo (init)" | `repo.sh init` — crea la estructura `dists/stable/...` del repo apt local | — |
| "📑 Publicar índice (publish)" | `repo.sh publish` — regenera `Release`/`Packages` tras agregar o quitar `.deb` | — |
| "🔗 Ver línea de sources.list (source)" | `repo.sh source` — muestra la línea real para agregar el repo local a `sources.list.d` | — |
| Card "FIRMA GPG (opcional)" — "🔏 Firmar repo con GPG" | Lista claves secretas propias (`gpg --list-secret-keys`); si no hay ninguna, explica cómo crearla a mano (nunca genera la clave por el usuario); si hay, firma (`repo.sh sign <keyid>`) reemplazando `[trusted=yes]` por `signed-by=` | Kairos nunca controla una clave privada del usuario — la firma es 100% opt-in |
| Card "PAQUETES INSTALADOS" — fila "📦 Crear .deb" (por paquete) | `repo.sh pack <paquete>` — reconstruye un `.deb` real desde un paquete ya instalado (`dpkg -L` + `dpkg-deb -b`, sin `dpkg-repack`) | Permite reempaquetar CUALQUIER paquete apt del sistema, no solo módulos de Kairos |
| Card "CREAR .DEB DE UN MÓDULO" — "📦 Crear .deb de <módulo>" | `moduledeb.sh pack <id> --silent` — arma un `.deb` con el contenido YA instalado/parcheado del módulo + su manifest, reutilizable sin reinstalar de cero | — |
| Card ".DEB GENERADOS" — fila "👁 Ver" | `dpkg-deb --info`/`--contents` sobre el `.deb` real — muestra control file + árbol de archivos | Solo lectura, vista previa antes de instalar |
| Card ".DEB GENERADOS" — fila "🗑 Eliminar" | `repo.sh remove <paquete>` — borra el `.deb` del repo local y republica el índice | No afecta el paquete ya instalado en el sistema — aclarado en el diálogo de confirmación |
| Card MANTENIMIENTO (genérica) | `addMaintenanceCard()` — Actualizar/Desinstalar estándar | — |

**Detalle no obvio**: el registro de `.deb` generados (`~/kairos_local/repo_registry.json`) es
un archivo JSON plano — `RepoFragment` lo lee directo sin pasar por el script (`readRegistry()`),
porque listar es una operación de solo lectura y no justifica un `ProcessBuilder` extra.

---

## Ficha técnica

> Basada en la lectura de `modulos/repo.sh` (773 líneas), `RepoFragment.kt` (703) y la entrada de `modules.json`.

**1. Qué es.** Repo apt local en `$PREFIX/../kairos-repo` (`/data/data/com.termux/files/kairos-repo`)
más empaquetado de `.deb`: `repo add <módulo>` (binario + `~/scripts/<id>`), `repo pack <paquete>`
(cualquier paquete apt instalado, conservando postinst/prerm/conffiles), `publish`, `remove`,
`source`, `keys`, `sign <KEYID>` (`repo.sh:12-60`). Id `repo`, `type: "DevOps"`,
`terminalCommand: "repo"`.

**2. Clasificación: MÓDULO FUNCIONAL (herramienta con estado, sin servicio).** `RepoFragment`
(`ModuleDetailNavigator.kt:43`) tiene estado del repo, 3 botones fijos (inicializar, publicar,
ver línea `sources.list`), firma GPG, lista dinámica de paquetes instalados con "Crear .deb",
lista de .deb generados (ver/borrar) y un botón "Crear .deb de <módulo>" por cada módulo instalado
(vía `moduledeb.sh pack`, `RepoFragment.kt:163-215`) + mantenimiento. ~4 botones fijos + 3 listas
dinámicas con acciones por fila (~10+ controles reales).

**3. Instalación.** Sin binarios ni descargas: el "instalar" sin subcomando en modo `--silent`
ejecuta `repo init` (`repo.sh:~733-742`: crea `deb/` y `dists/stable/main/binary-aarch64`,
`Packages` vacío y `Release`, registry `repo.*`). Usa `dpkg-deb` (o `ar`+`tar` de respaldo),
`jq` para el JSON `~/kairos_local/repo_registry.json`, `gpg` solo si se firma. `--force` se acepta
pero no hace nada (comentario del propio script, y el manifiesto declara `supports_force:false`).

**4. Ejecución.** Sin servicio/puerto. Cada botón lanza `bash repo.sh <subcmd> --silent` con
`ProcessBuilder` y muestra la última línea `[OK]`/`[ERROR]` (`RepoFragment.kt:654-675`).

**5. UI.** Ver punto 2. La firma GPG solo ofrece claves secretas ya existentes
(`repo keys`/`gpg --list-secret-keys`); nunca genera claves (decisión documentada en el script).

**6. Archivos.** `…/files/kairos-repo/{deb,dists/stable/{Release,InRelease,Release.gpg},repo-key.gpg|.asc}`,
`~/kairos_local/repo_registry.json`, registry `repo.*`, checkpoint `~/.install_repo_checkpoint`.
Los `.deb` llevan rutas absolutas de prefijo de Termux (`./data/data/com.termux/files/usr/...`).

**7. Seguridad.**
- Sin firma, `repo source` imprime `deb [trusted=yes] file://…` (desactiva verificación apt para
  ese repo; es local y propio, el script lo declara a propósito).
- La clave privada nunca se toca ni se pide passphrase en UI. Firmar con `--batch --yes` requiere
  que gpg-agent ya tenga la clave desbloqueada (inferencia: no probado con passphrase).
- `repo add/pack` interpolan `<id>`/`<paquete>` en rutas sin validar caracteres (`repo.sh:300+`),
  pero la UI solo pasa ids del catálogo o nombres de `dpkg-query`.
- `repo pack` empaqueta archivos reales instalados: un `.deb` de un módulo con credenciales
  guardadas en `~/scripts/<id>` las incluiría (inferencia, no revisado por módulo).

**8. Limitaciones.**
- `runScript`/`runModuleDebPack` leen stdout con `readText()` sin timeout; un `dpkg-deb`
  colgado deja el hilo bloqueado y el Snackbar nunca aparece.
- `repo publish` borra silenciosamente `InRelease`/`Release.gpg` (con warn) y exige volver a
  firmar a mano; la UI no encadena publish+sign.
- `repo add` etiqueta la versión desde el registry (`<id>.version`), con 1.0.0 por defecto;
  no refleja la versión real del binario si no está registrada.
- `_repo_binary_name` solo traduce `remote→ssh`; para el resto asume binario = id.
- Inferencia: el repo es `file://` local; no existe servidor HTTP para consumirlo desde otro
  dispositivo (la nota de `repo source` pide copiar `repo-key.gpg` a mano).

**9. Alta.** `modulos/repo.sh` agregado el **2026-08-14**.
