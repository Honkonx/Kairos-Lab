# Rootfs embebido

Mecanismo que permite que el primer arranque de Kairos **extraiga e instale** un conjunto base
de paquetes en vez de descargarlos uno por uno vía `pkg install`, con el objetivo de que el
wizard de primer arranque funcione sin red (o con muy poca).

## Diseño: paquetes `.deb` reales, instalados vía `apt`, no un volcado de archivos

El rootfs embebido **no** es un backup de archivos ya descomprimidos que se copian directo a
`$PREFIX`. Es un conjunto de archivos `.deb` reales (sin extraer), empaquetados en un
`.tar.xz`, que en el dispositivo se extraen a una carpeta temporal y se instalan con
`apt install -y` de verdad.

Esta decisión de diseño no es incidental: un volcado de archivos ya descomprimidos sería más
rápido de instalar, pero dejaría a `dpkg`/`apt` sin ningún registro de que esos paquetes están
instalados — `pkg list --upgradable` nunca los vería, `dpkg -L <paquete>` no sabría qué archivos
le pertenecen, y cualquier script que dependa de consultar el estado real de paquetes (por
ejemplo para detectar si `glibc` ya está instalado) dejaría de funcionar. Pasar por `apt install`
real, aunque sea sobre `.deb` locales, mantiene a dpkg/apt como única fuente de verdad sobre qué
está instalado — sin inventar un sistema paralelo de bookkeeping.

## Resumen del pipeline

```
                      ┌─────────────────────────┐
                      │  Build del rootfs (CI)   │
                      │  tools/rootfs/*.py        │
                      │  solo descarga .deb reales│
                      │  (sin extraer)             │
                      └───────────┬──────────────┘
                                  │ publica
                                  ▼
                     Artefacto "rootfs-<tag>"
        kairos-rootfs-aarch64.tar.xz (.deb sueltos) + .sha256 + manifest.json
                     │                              │
        (build-time) │                              │ (runtime)
                      ▼                              ▼
         Build "con rootfs embebido"          Build "sin rootfs"
         descarga+embebe como asset           APK liviano, sin el asset
                      │                              │
                      ▼                              ▼
         APK "con rootfs" (sin red          APK "sin rootfs" (descarga
         en el wizard)                       en runtime, con red)
                      │                              │
                      └──────────────┬───────────────┘
                                     ▼
                     RootfsInstaller.kt (misma extracción/
                     instalación para ambos casos — solo
                     cambia la fuente del tar.xz)
                                     │
                                     ▼
              Extracción 100% JVM del .tar.xz a una carpeta
              temporal (NO directo a $PREFIX)
                                     │
                                     ▼
              apt install -y <todos los .deb> — instalación REAL,
              dpkg/apt quedan con el registro correcto
                                     │
                                     ▼
              se pre-marcan checkpoints del script de setup
              (core_pkgs/build_pkgs/media_util_pkgs) — el
              script de setup los salta con su propio mecanismo
                                     │
                                     ▼
        Wizard: pantalla "Comprobar paquetes" (opcional, con spinner)
        → verifica instalados, instala lo que falte, revisa y aplica
          actualizaciones (misma acción disponible después en
          Ajustes → "Comprobar paquetes del sistema")
```

## Generación del artefacto (`tools/rootfs/build_rootfs.py`)

Script Python puro (sin `dpkg-deb`, sin dependencias externas, corre igual en CI que en
cualquier máquina con Python 3 y red), invocado como
`build_rootfs.py <staging_dir> <cache_dir>`. Flujo:

1. **`fetch_packages_index()`** — descarga `Packages.gz` del índice real de
   `packages.termux.dev` (repo `termux-main`, `binary-aarch64`).
2. **`parse_packages_index(raw)`** — parser propio del formato de control de `apt` (bloques
   separados por línea en blanco, campos `Clave: valor`, continuaciones con indentación). Para
   cada paquete extrae `version`/`filename`/`sha256`/`depends` — el campo `Depends:` toma solo la
   primera alternativa de cada `|` y descarta el rango de versión.
3. **`resolve_closure(roots, index)`** — BFS de dependencias transitivas a partir de
   `package_list.txt`. Un paquete raíz faltante en el índice es un error duro (típicamente un
   typo real en `package_list.txt`); una dependencia transitiva faltante solo genera un aviso, no
   bloquea el build (puede ser un paquete virtual o algo ya provisto por el sistema base de
   Termux). Cuando una dependencia ofrece varias alternativas mutuamente excluyentes (por ejemplo
   `nodejs | nodejs-lts`, paquetes que Termux declara en conflicto entre sí), la resolución
   prefiere la alternativa que ya figura explícita en `package_list.txt` en vez de tomar siempre
   la primera de la lista — evita que terminen dos paquetes en conflicto dentro del mismo rootfs,
   lo que haría fallar `apt install` con código de error 100 sobre el conjunto completo.
4. **`download_deb(name, info, cache_dir)`** — descarga cada `.deb` con `urllib.request`,
   verificando el SHA256 real contra el que publica el propio índice de `apt` (no un hash
   hardcodeado a mano). Si el archivo ya existe en `cache_dir` con el hash correcto, no se vuelve
   a descargar.
5. **`main()`** — orquesta todo lo anterior, copia (sin extraer) cada `.deb` resuelto al
   `staging_dir`, y escribe `manifest.json` (paquete → versión) para trazabilidad.

El propio script nunca extrae los `.deb` — el motivo es exactamente el descrito arriba: si lo
hiciera, dpkg/apt en el dispositivo no tendría registro de qué está instalado.

El paso de empaquetado final (`tar -cJf` del `.tar.xz` y cálculo del `.sha256`) lo hace el
workflow de CI, no el script Python — `build_rootfs.py` solo deja los `.deb` sueltos más
`manifest.json` en `staging_dir`. Este build es pesado (decenas de descargas), así que se
dispara manualmente, no en cada push — solo cuando cambia `package_list.txt` o hace falta
refrescar versiones.

## Extracción e instalación en el dispositivo (`RootfsInstaller.kt`)

La extracción del `.tar.xz` es 100% Java/Kotlin, sin invocar `tar` por `ProcessBuilder`: usa
Apache Commons Compress (`TarArchiveInputStream`) y XZ for Java
(`XZCompressorInputStream`), con progreso real basado en bytes leídos del `.xz` comprimido
contra el tamaño total del archivo.

La instalación de los `.deb` extraídos sigue siendo `apt install -y` real, vía `ProcessBuilder`,
usando rutas absolutas a los binarios (`bash`, `apt`) en vez de nombres relativos resueltos por
`PATH` — el uso de nombres relativos justo después de que el bootstrap termina de extraerse
demostró no ser confiable de forma consistente en todos los dispositivos, así que el mecanismo
actual usa rutas absolutas con reintento y una breve espera si el primer intento falla.

Después de la instalación, `RootfsInstaller` pre-escribe los checkpoints correspondientes en el
archivo de progreso que usa el script de setup principal (`kairos.sh`) — el script los salta con
su propio mecanismo de checkpoints existente, sin necesidad de ningún cambio en ese script.

**Fallback silencioso y no bloqueante**: si la instalación del rootfs falla por cualquier motivo
(artefacto no disponible, sin red, un `.deb` corrupto, `apt install` falla), no se marcan los
checkpoints y el script de setup principal continúa con `pkg install` normal, paquete por
paquete — el rootfs embebido es una optimización de velocidad, no una dependencia dura del flujo
de primer arranque.

## Comprobación de paquetes y actualizaciones (`RootfsPackageChecker.kt`)

Toda la lógica de verificación/instalación/actualización es Kotlin puro — no invoca ningún
intérprete externo para esto. Lee la lista de paquetes empaquetada en el propio APK
(`assets/scripts/rootfs_package_list.txt`) y parsea `/data/data/com.termux/files/usr/var/lib/
dpkg/status` directo (el archivo real que mantiene dpkg/apt) para saber qué está instalado.

| Función | Qué hace |
|---|---|
| `verify()` | Lee `/var/lib/dpkg/status`, compara contra la lista de paquetes → `{installed, missing}` |
| `installMissing()` | `apt install -y` de lo que falte |
| `checkUpdates()` | `apt update` + `apt list --upgradable`, parseado en Kotlin → lista de paquetes con versión nueva |
| `update()` | `apt install -y --only-upgrade` de lo que se le pase |
| `sync()` | Las cuatro anteriores combinadas — pensada para un botón único con spinner, sin mostrar el log crudo en pantalla |

Esta acción está disponible en dos lugares de la app, ambos usando `RootfsPackageChecker.sync()`:

1. **Wizard de primer arranque** — pantalla opcional al final del proceso, con botón "Comprobar
   y actualizar" / "Omitir".
2. **Ajustes** — fila "Comprobar paquetes del sistema" en la sección general.

## Otras decisiones de diseño

- **Sin parser de `.deb`/`ar` propio en ningún punto crítico.** La resolución de dependencias y
  descarga las hace Python contra el índice real de `apt`; la instalación real la hace `apt` en
  el dispositivo; la extracción del `.tar.xz` usa una librería real y probada (Apache Commons
  Compress + XZ for Java), no un parser escrito a mano.
- **`tar.xz` simple**, sin trucos de empaquetado exóticos — se priorizó lo simple y mantenible
  sobre la extracción de tiempo cero.
- **Checksum publicado junto al artefacto** (`.sha256`), no hardcodeado en el código fuente —
  evita tener que actualizar un hash a mano cada vez que se re-arma el rootfs.
- **El script de setup principal (`kairos.sh`) no necesita cambios** — el mecanismo de
  checkpoints pre-escritos por `RootfsInstaller` es compatible con su lógica existente de
  "saltar pasos ya hechos".
- Un subconjunto de paquetes (los de `glibc`) queda fuera del rootfs embebido por ahora, porque
  vienen de un repositorio APT distinto al índice principal de Termux.
- El artefacto se empaqueta en el APK con `androidResources { noCompress += [".xz"] }`, para que
  la herramienta de empaquetado de Android no vuelva a comprimir un archivo `.tar.xz` que ya está
  comprimido — evita tanto un gasto de build innecesario como una descompresión adicional en
  tiempo de ejecución sobre un binario grande.

## Limitaciones conocidas

- El artefacto del rootfs embebido pesa varios cientos de MB (el `.tar.xz` completo ronda los
  300MB para el conjunto de paquetes base) — se regenera solo cuando cambia `package_list.txt`
  (agregar/quitar/actualizar paquetes), no en cada build.
- El camino de descarga en runtime (para la variante liviana, sin rootfs embebido) depende de
  que el artefacto esté hospedado en un lugar accesible sin autenticación desde el dispositivo
  del usuario final — un token de autenticación embebido en el APK no es viable como solución,
  porque cualquier APK es extraíble/decompilable y un token quedaría expuesto. Si se quiere que
  la variante liviana funcione de punta a punta para usuarios reales, el artefacto del rootfs
  tiene que estar hospedado en un lugar de acceso público sin autenticación.
- El flujo completo (extracción sin red + `apt install` de todos los paquetes + checkpoints
  pre-marcados + continuación del script de setup principal) está confirmado funcionando de
  punta a punta en dispositivo real. El primer intento de `apt install` sobre el conjunto
  completo puede tardar varios minutos; si ese primer intento se agota por tiempo, el mecanismo
  reintenta automáticamente una segunda vez, que normalmente completa rápido porque la mayor
  parte del trabajo de instalación ya quedó hecho en el primer intento.

## Archivos involucrados

| Archivo | Rol |
|---|---|
| `tools/rootfs/package_list.txt` | Lista canónica de paquetes (fuente única) |
| `tools/rootfs/build_rootfs.py` | Resuelve dependencias y descarga los `.deb` (sin extraer) |
| `modulos/rootfs_package_list.txt` (+ copia en assets) | Copia on-device de la lista, leída por `RootfsPackageChecker.kt` |
| `app/build.gradle` (tarea `downloadRootfsAsset`, deps `commons-compress`/`xz`/`viewpager2`) | Descarga el artefacto en build-time cuando corresponde, más librerías de extracción/wizard |
| `app/src/main/java/com/termux/app/util/RootfsInstaller.kt` | Extracción 100% JVM (Commons Compress + XZ) más `apt install` real |
| `app/src/main/java/com/termux/app/util/RootfsPackageChecker.kt` | verify/installMissing/checkUpdates/update/sync — 100% Kotlin |
| `app/src/main/java/com/termux/app/KairosBootstrap.kt` | Extrae la lista de paquetes al dispositivo |
| `app/src/main/java/com/termux/app/wizard/WizardActivity.java` | Host del `ViewPager2` que aloja las pantallas del wizard |
| `app/src/main/java/com/termux/app/wizard/Wizard{Welcome,Permissions,PhantomProcess,Battery,Install,Check}Fragment.kt` | Pantallas del wizard, cada una dueña de su propia UI y lógica |
| `app/src/main/java/com/termux/app/wizard/WizardPagerAdapter.kt` | `FragmentStateAdapter` de las pantallas del wizard |
| `app/src/main/java/com/termux/app/ui/ConfigFragment.kt` | Botón "Comprobar paquetes del sistema" en Ajustes |
