# Arquitectura del bootstrap

Cómo funciona el bootstrap mínimo de Kairos, qué significa que la identidad de paquete sea
`com.termux`, y qué tan viable sería un mirror de paquetes propio. Este documento cubre el
mecanismo real, confirmado leyendo el código fuente (`TermuxInstaller.java`, `app/build.gradle`,
`app/src/main/cpp/`) — no supuestos.

## 1. Cómo funciona el bootstrap hoy

`app/build.gradle` (tarea `downloadBootstrap()` / `downloadBootstraps()`) descarga en
**tiempo de build** (Gradle, no runtime) los cuatro zips de arquitectura
(`bootstrap-aarch64.zip`, `-arm`, `-i686`, `-x86_64`) directo de:

```
https://github.com/termux/termux-packages/releases/download/bootstrap-<version>/bootstrap-<arch>.zip
```

el repo oficial y público de `termux-packages`, sin autenticación. La versión se fija en
`app/build.gradle` (variante `apt-android-7`, la que usa Kairos), con checksum SHA-256
hardcodeado por arquitectura, verificado byte a byte antes de aceptar el archivo — si el
checksum no coincide, el build falla duro en vez de seguir con un bootstrap corrupto.

Los cuatro `.zip` no se versionan en git (patrón `*.zip` en `.gitignore`); quedan cacheados en
`app/src/main/cpp/bootstrap-<arch>.zip` en el filesystem local después de la primera descarga.
Cada build limpio (CI incluido) los vuelve a descargar del repo oficial.

`app/src/main/cpp/termux-bootstrap.c` es un wrapper JNI trivial: expone
`Java_com_termux_app_TermuxInstaller_getZip()`, que copia un array `blob[]` + `blob_size`
(definidos en `termux-bootstrap-zip.S`, que embebe el `.zip` completo como datos binarios crudos
dentro de la librería nativa compilada) a un `jbyteArray`. No hay ninguna lógica de
`sources.list` propio, ni transformación, ni nada específico de Kairos en este paso: el zip que
termina compilado en `libtermux-bootstrap.so` es byte por byte el que publica
`termux-packages`.

`TermuxInstaller.setupBootstrapIfNeeded()` no descarga nada de una URL en runtime — el zip del
bootstrap sale de `loadZipBytes()`, que carga `libtermux-bootstrap.so` (`System.loadLibrary`) y
llama al método nativo `getZip()` descrito arriba. Todo el bootstrap (bash, coreutils, apt,
dpkg, binarios ARM64 precompilados) está compilado dentro del propio APK como librería nativa.

El `sources.list` real que usa `apt` en el dispositivo lo trae el propio bootstrap oficial de
Termux tal cual, apuntando a `packages.termux.dev` — comportamiento estándar de Termux, no algo
que Kairos configure. Si algún día se quisiera un mirror propio, el punto de intervención real
sería *después* de que `apt` ya está funcionando (reescribir `$PREFIX/etc/apt/sources.list` en
runtime, trivial) — no hace falta tocar `app/src/main/cpp/` ni recompilar nada para eso.

## 2. Dos capas de "bootstrap" distintas, por diseño

| Capa | Qué instala | De dónde sale | Cuándo corre | Repo fuente |
|---|---|---|---|---|
| **Bootstrap mínimo** (`TermuxInstaller.java`) | `bash`, `dpkg`, `apt`, coreutils — lo mínimo para que `$PREFIX` exista y `apt` funcione | `libtermux-bootstrap.so` (compilado en build-time desde el zip oficial) | Primer arranque, siempre | `github.com/termux/termux-packages` (oficial, público) |
| **Rootfs embebido** (`RootfsInstaller.kt`) | ~189 paquetes adicionales (`glibc`, `nodejs-lts`, `python`, `git`, etc. — ver `rootfs-embebido.md`) | Artefacto propio de Kairos, vía `apt install` real | Wizard, pantalla de instalación, después del bootstrap mínimo | Propio de Kairos |

La capa 1 nunca depende de infraestructura propia — es pública, de termux-packages. La capa 2 sí
depende de dónde se hostee el artefacto del rootfs. Son independientes hoy y no comparten código
de descarga.

## 3. Identidad de paquete — Kairos es `com.termux`, no una app separada

Confirmado en `AndroidManifest.xml` (`android:sharedUserId="${TERMUX_PACKAGE_NAME}"`) y
`app/build.gradle` (`namespace "com.termux"`, sin `applicationId` propio): Kairos usa
literalmente el mismo paquete que Termux oficial. No es "comparte UID con otra app" — es la
misma identidad de paquete.

Consecuencia práctica: Kairos y el Termux oficial no pueden estar instalados al mismo tiempo en
el mismo dispositivo. Esto es intencional (compatibilidad con addons oficiales como Termux:API,
Termux:Boot, que buscan específicamente `com.termux`), pero implica que, técnicamente, Kairos ya
reemplaza a Termux en el dispositivo en vez de convivir con él.

## 4. `context.filesDir` vs `$HOME` de Termux

`TERMUX_FILES_DIR_PATH = "/data/data/com.termux/files"` y `context.filesDir` para este paquete
resuelven al mismo directorio raíz — `$HOME` (`TERMUX_HOME_DIR_PATH`) es simplemente el
subdirectorio `/home` dentro de ese mismo árbol. Una sesión bash lanzada por
`TermuxService`/`ProcessBuilder` desde la propia app es un proceso hijo del proceso de la app —
hereda el mismo UID automáticamente, y por lo tanto ya tiene acceso total a `context.filesDir`
completo, no solo a `/home`.

No existe una carpeta "más privada" dentro del propio sandbox de la app: todo bajo
`/data/data/com.termux/` es igual de invisible para otras apps (sin root) e igual de accesible
para la propia sesión bash, sea `/home` o cualquier otra subcarpeta. La diferencia real es de
convención (qué carpeta el usuario espera ver con un explorador de archivos o desde su propia
sesión Termux), no de permisos del sistema operativo — cualquier código nuevo que necesite leer
o escribir en el árbol de Termux debe apuntar a `$HOME`/`TERMUX_HOME_DIR_PATH`, no a
`context.filesDir`, para mantener la convención esperada por scripts y por el propio usuario.

## 5. Mirror de paquetes propio — viabilidad

Política oficial de Termux para forks: *"Please don't use the official host in termux forks. Set
up your own repository."* — para un fork con distribución propia, Termux espera que tenga su
propio host.

Puntos clave:

- No hace falta recompilar nada. La forma recomendada de mirror-ear es un simple
  `rsync -a --delete rsync://packages.termux.dev/termux termux` periódico — copia los binarios
  ya compilados, sin pasar por el pipeline de build (Docker + NDK cross-compile) de
  `termux-packages`.
- Como Kairos preserva el mismo `sharedUserId` y el mismo prefix
  (`/data/data/com.termux/files/usr`), los paquetes oficiales ya son binariamente compatibles —
  no hace falta "forkear" los paquetes en el sentido de recompilarlos.
- Alcance real para Kairos: solo usa entre 20 y 30 paquetes en una sola arquitectura (aarch64):
  `glibc`, `glibc-runner`, `openssl-glibc`, `nodejs-lts`, `tmux`, `git`, `python`,
  `proot-distro`, `curl`, `wget`, `tar`, `xz-utils`, `binutils`, `ca-certificates`,
  `resolv-conf`, `qemu-user-aarch64`. Un mirror parcial de solo esos paquetes es un problema de
  tamaño trivial (decenas de MB), viable de hostear en cualquier CDN estático gratuito.
- El riesgo real de un mirror rsync periódico no es quedar desactualizado en seguridad (el rsync
  resuelve eso solo) — aparece únicamente si el proyecto algún día quiere **divergir** de los
  paquetes oficiales (versiones propias, parches). Ahí sí hace falta el pipeline de build
  completo de `termux-packages`, que implica carga de mantenimiento continua.

### Precedente real de mirror propio + bootstrap parcheado

Existen forks de termux-app publicados que ya implementaron este patrón de punta a punta,
incluyendo:

- Un pipeline de rebuild de bootstrap propio (build custom + workflow de CI dedicado).
- Una herramienta de diff entre dos builds de bootstrap (qué paquete cambió, paquete por
  paquete) — útil para auditoría, algo que ni Kairos ni el termux-app oficial tienen hoy.
- Un mirror propio real (`repo/aarch64`, `arm`, `x86_64`, `i686`, `all`, más archivo `Release`),
  con la misma estructura que un repo APT de Debian estándar — confirma en la práctica que un
  mirror parcial de 20-30 paquetes cabe en hosting estático gratuito, no es solo teoría.
- Documentación del flujo real para cuando hace falta parchear un paquete con rutas
  hardcodeadas (usar `strings` sobre el binario para encontrar rutas embebidas del tipo
  `/data/data/com.termux`, rebuild con el builder Docker oficial de `termux-packages`,
  reintegrar al zip y al mirror propio).

Dos advertencias técnicas documentadas ahí, relevantes para cualquier futuro trabajo de
parcheo de binarios de Termux:

- **Las rutas hardcodeadas en un binario no se pueden sobreescribir por variable de entorno** —
  si un binario tiene `/data/data/com.termux/...` embebido en tiempo de compilación, hay que
  recompilarlo desde fuente; no hay atajo de entorno.
- **`LD_LIBRARY_PATH` tiene prioridad sobre `RUNPATH`** — explica por qué la mayoría de las libs
  de Termux funcionan pese a tener un `RUNPATH` "incorrecto", hasta que aparece una que no
  respeta `LD_LIBRARY_PATH` y falla de forma poco intuitiva.

## 6. Precedente de referencia externo: bootstrap alternativo vía VM completa

Un enfoque radicalmente distinto para evitar el bootstrap tipo Termux es correr una VM Linux
completa (kernel custom + QEMU + un rootfs squashfs tipo Alpine) en vez de extraer binarios
precompilados — una escala de esfuerzo enteramente distinta (pipeline que compila un kernel
propio y cross-compila QEMU para NDK). No es un patrón trasladable a "modificar el bootstrap de
Kairos" — es construir un hipervisor, no adaptar un instalador. Lo único genuinamente reusable de
ese enfoque, como idea, es su sistema de **migraciones versionadas**: un script que corre
`/etc/<app>/migrations/<versionCode>.sh` en orden entre la última versión aplicada y la actual,
con un marcador de versión persistido aparte, de forma idempotente. Si Kairos alguna vez necesita
un proceso de bootstrap/setup versionado que corra en cada actualización de la app de forma
incremental (hoy `KairosBootstrap.kt` solo re-extrae todo si la versión cambió, sin pasos
incrementales), ese patrón es aplicable directamente.

## 7. Recovery del bootstrap — estado actual

Si `$PREFIX` ya existe y no está vacío, el código de instalación no verifica nada — continúa
directo, sin comprobar `bash --version`, `pkg --version`, ni ningún otro chequeo de integridad.

El único mecanismo de recuperación existente es el botón "Try Again" del diálogo de error: borra
`$PREFIX` entero y vuelve a ejecutar la instalación desde cero, sin ningún intento de reparación
parcial. Ese botón solo aparece si la extracción falla de forma activa (una excepción durante el
proceso de extracción de zip/symlinks) — si `$PREFIX` queda en un estado silenciosamente
corrupto (extrae bien pero algún binario queda mal, o el usuario borra un archivo a mano
después), no hay ningún camino en la app para detectarlo ni repararlo.

Existe un guard de concurrencia que evita dos extracciones en paralelo — ese problema puntual ya
está resuelto; el trabajo pendiente real es verificación de integridad, progreso no bloqueante y
recuperación selectiva (por ejemplo `apt --fix-broken install` o reconstrucción de
`/var/lib/dpkg/status` en vez de re-extraer todo). No se encontró, ni en el Termux oficial ni en
ningún fork revisado, un mecanismo de reparación granular ya implementado para copiar — el
patrón universal en todo el ecosistema es "borrar y re-extraer todo". Esto confirma que una
recuperación selectiva/inteligente sería una mejora real sobre el estado del arte del ecosistema,
no solo sobre este proyecto — habría que diseñarla desde cero.
