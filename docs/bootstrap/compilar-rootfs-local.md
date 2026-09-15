# Compilar el rootfs y el APK localmente

Guía para armar los tres artefactos de build sin depender de GitHub Actions: el rootfs
embebido, el APK sin rootfs (variante liviana) y el APK con rootfs embebido. Todo el proceso
puede correrse en un WSL Debian (o cualquier Linux con Python 3) más un build de Gradle normal
en el host — sin Docker, sin publicar nada, sin tokens de CI.

## Aclaración de concepto

El rootfs **no** embebe los módulos de Kairos (`modulos/*.sh`). Embebe los **paquetes base que
el wizard necesita en el primer arranque** (`tools/rootfs/package_list.txt`, ~46 paquetes raíz
→ ~189 con dependencias transitivas: git, python, nodejs, etc.), para que el wizard sea solo
extracción + instalación + configuración, sin red. Los módulos siguen descargándose aparte, bajo
demanda, cuando el usuario los activa — eso no cambia.

## Por qué no hace falta pasar por GitHub Releases para un build local

El pipeline de CI (`build-rootfs.yml` → Release → `build-app-rootfs.yml` descarga esa Release)
existe porque GitHub Actions necesita un lugar de donde bajar el artefacto entre dos workflows
separados que corren en máquinas efímeras distintas. Para un build 100% local ese paso
intermedio no hace falta:

- `tools/rootfs/build_rootfs.py` es Python 3 puro (`urllib`, `gzip`, `hashlib` — todo stdlib,
  sin `dpkg-deb` ni dependencias de compilación) — corre igual en un WSL Debian que en CI.
- `app/build.gradle` (tarea `downloadRootfsAsset`) solo se activa si `KAIROS_EMBED_ROOTFS=true`
  está seteada. Si no se setea, Gradle nunca toca `app/src/main/assets/kairos_rootfs.tar.xz`, ni
  para descargarlo ni para borrarlo (excepción: `gradlew clean` sí lo borra si existe — ver más
  abajo).
- `RootfsInstaller.kt` decide en **runtime** si usar el rootfs embebido simplemente chequeando
  si `app/src/main/assets/kairos_rootfs.tar.xz` existe — no depende de ningún flag de build. Si
  el archivo está ahí, la app lo usa, sin importar cómo llegó.

Conclusión: alcanza con generar el `.tar.xz` a mano y copiarlo directo a
`app/src/main/assets/kairos_rootfs.tar.xz` antes de compilar — sin publicar nada, sin
`GITHUB_TOKEN`, sin tocar Releases.

## Paso 1 — Armar el rootfs (WSL Debian)

Requisitos: `python3` (ya viene en Debian), `tar`, `xz-utils` (`sudo apt install xz-utils` si
falta `xz`).

```bash
cd /ruta/al/repo/kairos

mkdir -p /tmp/rootfs_staging /tmp/rootfs_cache
python3 tools/rootfs/build_rootfs.py /tmp/rootfs_staging /tmp/rootfs_cache

# Empaquetar (mismo comando que build-rootfs.yml, sin el paso de Release)
tar -cJf /tmp/kairos-rootfs-aarch64.tar.xz -C /tmp/rootfs_staging .
sha256sum /tmp/kairos-rootfs-aarch64.tar.xz
ls -lh /tmp/kairos-rootfs-aarch64.tar.xz
```

**Salida esperada**: el script lista los paquetes raíz de `package_list.txt`, resuelve la
clausura transitiva de dependencias, descarga cada `.deb` verificando su checksum SHA-256 contra
el índice real de `packages.termux.dev`, y termina con "Listo." Si algún paquete raíz da `ERROR:
paquetes de package_list.txt no encontrados en el índice`, es un typo real en
`package_list.txt` a corregir antes de seguir.

## Paso 2 — Build del APK sin rootfs (variante liviana)

Confirmar primero que `app/src/main/assets/kairos_rootfs.tar.xz` **no existe** (si quedó de un
build anterior, borrarlo a mano). Después, build normal:

```powershell
$env:TERMUX_PACKAGE_VARIANT = "apt-android-7"
$env:TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0"
.\gradlew.bat :app:assembleDebug
```

Este es el APK de referencia — sirve como base de comparación de tamaño y para confirmar que
armar el rootfs (Paso 1) no rompió nada del build normal.

## Paso 3 — Build del APK con rootfs embebido

```bash
# Copiar el rootfs armado en el Paso 1 al lugar exacto que RootfsInstaller.kt espera
cp /tmp/kairos-rootfs-aarch64.tar.xz app/src/main/assets/kairos_rootfs.tar.xz
```

```powershell
# Mismo build de siempre — NO hace falta setear KAIROS_EMBED_ROOTFS ni GITHUB_TOKEN,
# esas variables solo controlan la DESCARGA automática desde una Release, no si el
# archivo ya presente en assets/ se empaqueta (eso lo hace Gradle siempre, es un
# asset normal del módulo app).
.\gradlew.bat :app:assembleDebug
```

Comparar el tamaño resultante contra el Paso 2 para saber cuánto pesa el rootfs en el APK final
(el `.tar.xz` en sí más el overhead de empaquetado de Android).

## Paso 4 — Verificación en dispositivo real

Con un dispositivo conectado por USB:

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

Abrir el wizard de primer arranque (o reinstalar limpio si ya había un `$HOME` viejo) y
confirmar que el rootfs embebido se extrae e instala sin red. Esta verificación de punta a punta
es el paso que cierra el ciclo — compilar sin probar en dispositivo real no es suficiente.

## Sobre el tamaño del APK universal

`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0` (el valor usado en este proyecto) genera un único APK
universal con el bootstrap de las 4 arquitecturas (`aarch64`/`arm`/`i686`/`x86_64`, ~25MB cada
una) más binarios nativos para las 4 ABIs en `terminal-emulator`/`x11-server`. El rootfs en sí es
aarch64-únicamente (`build_rootfs.py` solo pide `binary-aarch64/Packages.gz`), así que no es el
rootfs el que crece con la variante universal — es el bootstrap más los binarios nativos. Probar
`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=1` (o `-Pandroid.injected.build.abi=arm64-v8a`) debería
reducir el tamaño de forma medible si se necesita un APK más liviano para una sola arquitectura.

## Qué no se necesita para este plan

- **No hace falta Docker** — `build_rootfs.py` no compila nada, solo descarga `.deb` ya
  compilados del repo oficial de Termux.
- **No hace falta `GITHUB_TOKEN`** ni publicar ninguna Release — eso solo es necesario para que
  CI (que corre en una máquina efímera sin el repo en disco entre workflows) pueda pasar el
  artefacto de un workflow a otro.
- **No hace falta recompilar `termux-packages` desde fuente** — eso solo sería necesario para un
  rebrand completo de identidad de paquete (ver `arquitectura-bootstrap.md`), un tema
  completamente distinto.

## Gotcha: `gradlew clean` borra el rootfs embebido

El bloque `clean { doLast { if (rootfsAssetFile.exists()) rootfsAssetFile.delete() } }` en
`app/build.gradle` borra `app/src/main/assets/kairos_rootfs.tar.xz` si se corre `gradlew clean`.
Para reconstruir el APK con rootfs después de un `clean`, hay que volver a copiar el `.tar.xz`
(Paso 3) antes de compilar de nuevo.

Para tener ambos APKs (con y sin rootfs) del mismo build local, compilar primero sin el archivo
en `assets/` (Paso 2) y recién después copiar el `.tar.xz` y recompilar (Paso 3) — no al revés,
y confirmar que el archivo no quedó de una corrida anterior antes del primer build liviano.

## Publicar el rootfs como Release sigue siendo necesario para CI/producción

Esta guía cubre solo builds locales de prueba/desarrollo. Si se quiere que el pipeline de CI
vuelva a producir automáticamente un APK con rootfs embebido, hace falta correr
`build-rootfs.yml` de verdad y publicar la Release — el atajo local descrito acá no reemplaza
eso, solo permite iterar sin depender de CI.
