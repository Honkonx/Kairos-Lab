# Compilador de APK (`apk`) — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos / Tienda). Instalación manejada por la app vía `ProcessBuilder` → `modulos/apk.sh`; la pantalla dedicada (`ApkFragment.kt`) expone el pipeline de compilación real (aapt2 → javac + kotlinc → d8 → zipalign → apksigner) con progreso paso a paso, en vez de una caja de texto de terminal cruda.

**Alcance:** compila **Java + Kotlin** (JVM puro, sin Gradle). NO compila React Native/JS (necesita Gradle real con autolinking) ni C/C++ nativo vía CMake/NDK (el NDK real de Android, no el toolchain nativo de Termux, es un proyecto aparte).

---

**Proyecto:** Kairos
**Script:** `modulos/apk.sh` (v2.1.0, 818 líneas) — espejo en `app/src/main/assets/scripts/apk.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ApkFragment.kt` (905 líneas; `ModuleDetailNavigator.kt:47`) — dedicado, porque `apk` no tiene `terminalCommand` compatible con el flujo genérico
**`id` en `modules.json`:** `apk` — `hasSwitch: false` (no es un servidor con proceso persistente; es una herramienta de línea de comandos que se invoca por build)
**Atribución:** adaptado de i-Haklab / `termux-apk-make`. El repo `termux-apk-make` no trae ningún script real — solo un `README.md` que describe el pipeline y delega la instalación al repo apt externo de i-Haklab; `apk.sh` es una reimplementación propia que sigue el mismo orden de pipeline, con dos decisiones propias: el mirror `Sable/android-platforms` para `android.jar` (API 30), y un keystore generado localmente con `keytool` en el primer build (en vez de distribuido como archivo público).

---

## 1. Descripción General

Módulo que instala un compilador de APKs completo **dentro del dispositivo**, sin depender de Android Studio ni de una PC — pensado para compilar proyectos Android simples (manifest + `.java`/`.kt` + recursos opcionales) directo desde Termux/Kairos. Puerta de entrada: el comando `compil-apk-termux` (o el botón "🔨 Compilar APK" de `ApkFragment`). Además del build, ofrece inspección/fusión/decodificación de APK y un sistema de "instrumentación sin root" (decompila con apktool, inyecta llamadas a un logger remoto en un método Smali, recompila y firma).

El script no es un servidor: instala herramientas de build (aapt2, JDK, d8, zipalign, apksigner) y deja un wrapper (`compil-apk-termux`) en `$PREFIX/bin`. Cada compilación es una invocación puntual del wrapper, no un proceso de fondo — por eso `hasSwitch: false`.

**Clasificación: MÓDULO FUNCIONAL.** Pantalla dedicada con selección de proyecto, compilación con progreso, lista de APK generados (compartir/instalar), herramientas info/decode sobre cualquier APK, flujo de instrumentación (decode, buscar clase, elegir método, inyectar), control del servidor de logs (iniciar/detener/ver) y mantenimiento. ~15 botones fijos + filas dinámicas.

## 2. Permisos

- **Android**: ninguno específico para instalar/compilar. Para **compartir/instalar** el APK resultante usa un `FileProvider` dedicado (`${TERMUX_PACKAGE_NAME}.apkbuilder`, declarado en `AndroidManifest.xml` con `android:grantUriPermissions="true"` y `res/xml/apk_builder_paths.xml` como alcance) — **no** reutiliza el provider `.files` existente (`TermuxOpenReceiver.ContentProvider`), porque ese exige la policy `allow-external-apps` pensada para plugins de terceros que llaman a la API de Termux, no para un artefacto que la propia app genera y quiere entregarle al instalador de paquetes del sistema.
- **Termux interno**: `aapt2`, `openjdk-17`, `d8`, `zipalign`, `apksigner` se instalan vía `pkg` (paquetes nativos aarch64 de Termux) — sin proot, sin red además de la descarga inicial de paquetes y de `android.jar`.

## 3. Lógica de instalación (`modulos/apk.sh`)

Script con el contrato `lib.sh` estándar (SILENT/CHECKPOINT/REGISTRY) y los flags estándar:

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts, mismo output `[OK]`/`[ERROR]` |
| `--force` | Reinstala aunque ya esté (borra el checkpoint) |
| `--describe` | Manifiesto JSON: `{"id":"apk","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |

"Ya instalado" exige el wrapper + `aapt2`, `d8`, `zipalign`, `apksigner` en el PATH (`apk.sh:185-198`).

**PASO 1 — Herramientas de compilación**: `pkg install aapt2 openjdk-17 d8 aapt apksigner unzip zip wget` (`zipalign` viene en el paquete `aapt`, no existe como paquete propio). Captura stdout+stderr completo de `pkg install` y solo lo vuelca al log si algo falla. Verifica cada binario con `command -v` antes de marcar el checkpoint.

**PASO 2 — `android.jar`**: descarga `android-30/android.jar` desde `raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar` (mirror usado también por BuildAPKs) a `$PREFIX/share/android-platform/android.jar` (timeout 30 s; **sin sha256 ni versión de commit**, rama `master`) — se guarda una sola vez, reusado por todos los proyectos futuros. Verifica que el archivo descargado no esté vacío.

**PASO 3 — Wrapper `compil-apk-termux` + keystore**: escribe el script wrapper completo (ver §4) en `$PREFIX/bin/compil-apk-termux` por heredoc, **solo si no existe o con `--force`**, y prepara la carpeta del keystore (`$HOME/.local/share/kairos-apk/`) — el keystore en sí (`key.keystore`) se genera recién en el primer build real (`keytool -genkey`), no en la instalación.

**FINALIZANDO**: escribe en el registry (`apk.installed=true`, `apk.command=compil-apk-termux`, `apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner`, `apk.android_jar=API30`, `apk.install_date`) y dispara `notify_event apk install_done`.

`apktool` y `kotlinc` no son dependencia dura: apktool se instala en el primer uso de `instrument-decode`; Kotlin requiere el módulo `kotlin` aparte.

## 4. El wrapper `compil-apk-termux` (subcomandos)

`apk.sh` no compila nada por sí mismo — genera un wrapper standalone de ~250 líneas que sí lo hace. Subcomandos:

| Subcomando | Uso | Qué hace |
|---|---|---|
| `build <proyecto>` | `compil-apk-termux build ~/proyectos/mi_app` | Pipeline completo de 7 pasos (8 si el proyecto tiene Kotlin, ver §5). Es el **default** si se invoca sin subcomando reconocido (`compil-apk-termux <proyecto>` == `build <proyecto>`). |
| `info <apk>` | `compil-apk-termux info final.apk` | `aapt2 dump badging` (package/label/sdkVersion/permisos) + `apksigner verify --print-certs` + resumen de contenido (`unzip -l`). |
| `merge <base.apk> <partes...>` | `compil-apk-termux merge base.apk config.arm64-v8a.apk` | Equivalente funcional al merge de APKEditor sin Java/Gradle: desempaqueta base + splits en un dir temporal, re-empaqueta, `zipalign`, re-firma con el keystore local. Los splits traen `AndroidManifest.xml` duplicado — se conserva el de la base, sin des-dupe semántico. |
| `decode <apk> <dir>` | `compil-apk-termux decode app.apk salida/` | Extrae el APK + deja el `AndroidManifest.xml` legible vía `aapt2 dump xmltree` y lista los `.dex`. **No** hace smali/decompile — para eso hace falta `pkg install apktool` aparte. |

Variables de entorno opcionales que sobrescriben los defaults del wrapper: `ANDROID_JAR`, `APK_ALIAS` (default `kairos`), `APK_PASS` (default `password`), `APK_KEYSTORE`.

## 5. Pipeline de `build` (7 pasos, 8 con Kotlin)

Todo-o-nada — el wrapper corre con `set -e`, corta en el primer error de cualquiera de los pasos, no hay forma de "solo compilar sin firmar" como paso intermedio invocable por separado. El paso `kotlinc` es **condicional**: solo corre si `find "$PROJECT/src" -name '*.kt'` encuentra algo — un proyecto Java puro ve exactamente 7 pasos, sin overhead:

| Paso (Java puro / con Kotlin) | Herramienta | Qué produce |
|---|---|---|
| 1/7 · 1/8 | `aapt2 compile --dir res/` | `resources.zip` (solo si el proyecto tiene carpeta `res/`) |
| 2/7 · 2/8 | `aapt2 link -I android.jar --manifest ...` | `base.apk` (recursos compilados) + `R.java` generado en `gen/` |
| — · 3/8 (solo si hay `.kt`) | `kotlinc -classpath android.jar` | `.class` de `src/*.kt` → `build/kotlin-classes/` (directorio propio, separado de `build/classes/`) |
| 3/7 · 4/8 | `javac -source 11 -target 11 -classpath android.jar[:kotlin-classes]` | `.class` de `src/*.java` + del `R.java` generado — el classpath incluye `kotlin-classes/` cuando hubo paso Kotlin, para que código Java pueda referenciar clases Kotlin |
| 4/7 · 5/8 | `d8 --release --lib android.jar` | `classes.dex` — recoge `.class` de `build/classes/` **y** `build/kotlin-classes/` |
| 5/7 · 6/8 | `zip` | Empaqueta `classes.dex` + `assets/` (opcional) + `lib/` (opcional, `.so` nativas) dentro de `base.apk` |
| 6/7 · 7/8 | `zipalign -p 4` | `aligned.apk` (alineación requerida por ART en Android 11+) |
| 7/7 · 8/8 | `apksigner sign --v1/v2/v3-signing-enabled true` | `final.apk` — genera el keystore con `keytool` si todavía no existe (10000 días) |

`d8` es la herramienta de DEX disponible como paquete de Termux (R8 no está empaquetado ahí); `API30` como target es razonable para compilar APKs simples desde el dispositivo.

**Kotlin es dependencia blanda, no dura**: si el proyecto tiene `.kt` pero el módulo `kotlin.sh` no instaló `kotlinc` (no está en el PATH), `cmd_build()` corta con `[ERROR] Proyecto tiene archivos .kt pero kotlinc no está instalado — instalá el módulo Kotlin primero` **antes** de tocar cualquier archivo — `apk.sh` no fuerza la instalación de `kotlin.sh` (son módulos independientes), solo detecta y avisa en tiempo de build.

**Estructura mínima de proyecto** (confirmado en `cmd_build()`):
```
proyecto/
├── AndroidManifest.xml   (obligatorio)
├── src/                  (.java y/o .kt, puede tener paquetes anidados; opcional si el proyecto es solo recursos)
├── res/                  (opcional)
├── assets/               (opcional)
└── lib/                  (opcional — .so nativas)
```

**Salida real**: `<proyecto>/build/apk/final.apk` (la variable usada en `cmd_build()` es `$BUILD_DIR/apk/final.apk` con `BUILD_DIR="$PROJECT/build"`; el comentario del header del script dice `build/final.apk`, desactualizado). `ApkFragment.scanGeneratedApks()` escanea la ruta real.

**Parser de salida del compilador**: `_parse_compiler_output()` (port a bash de un parser de salida de compilador de un proyecto de IDE de referencia) reconoce el formato GNU estándar de `javac`/`kotlinc`/`aapt2` (`archivo:línea[:col]: error|warning: mensaje`) y muestra `[ERROR]`/`[WARN]` con conteo final en vez de un dump crudo. `ApkFragment` traduce las líneas `=== [n/N] ... ===` que emite el wrapper en actualizaciones de `ProgressDialogController` paso a paso (`N` es 7 u 8 según haya Kotlin).

## 6. Detección de estado

- **Instalación**: registry (`apk.installed=true`) + existen `compil-apk-termux` y `aapt2` en el PATH (`command -v`, sin `--force`).
- **`hasSwitch: false`**: no hay concepto de "corriendo"/"detenido" — el módulo no aparece con toggle en la Tienda, solo con botón de instalar/abrir.
- **`ApkFragment.isModuleInstalled()`**: si no está instalado, muestra la pantalla estándar "no instalado" con botón que llama a `installModuleInBackground`.

## 7. Pantalla de la app (`ApkFragment.kt`)

`getModuleId()` → `"apk"`, `getModuleName()` → `"Compilador de APK"`.

| Control | Qué hace | Por qué |
|---|---|---|
| Card "PROYECTO A COMPILAR" — "Seleccionado" | Solo lectura, muestra el proyecto elegido (`— ninguno —` por default) | — |
| "📂 Elegir proyecto a compilar" | Lista `~/proyectos` vía `ProjectsManager.projectsList()`, elegir uno lo guarda como `selectedProjectPath` | Reusa la misma fuente que `ProjectActions.pickProjectToOpen` en vez de duplicar la lógica de listado |
| "🗂 Gestionar proyectos (symlink / copiar)" | `showProjectsMenu()` — importar/crear/borrar proyectos | — |
| Card "COMPILAR" (pipeline `aapt2 → javac + kotlinc → d8 → zipalign → apksigner`, fila "Lenguajes": Java y Kotlin — NO React Native/JS ni C/C++ nativo) + "🔨 Compilar APK" | Valida proyecto seleccionado y existente; abre `ProgressDialogController` ("Compilando APK"); corre `compil-apk-termux build <ruta>` en un `Thread` vía `ProcessBuilder` (bash + `applyTermuxEnv()`), leyendo línea a línea con `redirectErrorStream(true)` (permite segundo plano + notificación, `ApkFragment.kt:214-280`); traduce cada línea `=== ... ===` (paso nuevo) o `  [ERROR]` a una actualización de progreso en vivo (guard `isAdded` antes de tocar la UI); al terminar, `progress.success(...)` o `progress.failure(...)` con las últimas 4000 chars del log y refresca la lista de APKs | Sin este parseo el usuario solo vería un spinner genérico durante un pipeline de 7-8 pasos que puede tardar minutos |
| Card "APKS GENERADOS" — fila de APK (tap) | `scanGeneratedApks()` recorre `~/proyectos/*/build/apk/final.apk` por fecha descendente (nombre + tamaño humano). El tap abre `shareOrInstallApk()`: **📤 Compartir** (`Intent.ACTION_SEND` con el `content://` del FileProvider `apkbuilder`, mime `application/vnd.android.package-archive`) o **📲 Instalar** (`Intent.ACTION_VIEW` con el mismo URI + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`, delega en el instalador de paquetes de Android) | El provider `.files` genérico está bloqueado por su policy `allow-external-apps` |
| Elegir APK (SAF) + Info + Decodificar | `info`/`decode` del wrapper sobre cualquier APK (con ofrecer abrir la carpeta) | — |
| Instrumentación | decode, elegir clase Smali, elegir método y acción (`enter\|exit\|both\|d`), inyectar y ofrecer instalar | Herramienta de ingeniería inversa; solo bajo acción explícita del usuario |
| Log | Iniciar / detener / ver el servidor de logs (`log_server.py`, `127.0.0.1:9999`, sesión tmux con `logstart/logstop/logstatus/logtail`) | — |
| MANTENIMIENTO — "🔄 Actualizar herramientas" / Diagnosticar / "🗑 Desinstalar" | `updateModuleService`; desinstalar pide confirmación y borra las herramientas de build (aapt2/d8/zipalign/apksigner/android.jar) + wrapper — **no** borra `~/proyectos` ni los APKs ya compilados (aclarado en el diálogo) | — |

## 8. Registry (`~/.android_server_registry`)

```
apk.installed=true
apk.command=compil-apk-termux
apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner
apk.android_jar=API30
apk.install_date=<YYYY-MM-DD>
```

## 9. Archivos y rutas

`$PREFIX/bin/compil-apk-termux`, `$PREFIX/share/android-platform/android.jar`, `~/.local/share/kairos-apk/{key.keystore,instrument/…,logger/}`, `~/.kairos_apk_logger/` (`RemoteLogger.java`, `native_logger.c`, `log_server.py`, `smali_hook.py`, copiados por `KairosBootstrap`), `<proyecto>/build/{gen,classes,kotlin-classes,dex,apk}/`, registry `apk.*`.

## 10. Seguridad

- El keystore por defecto usa alias `kairos` y contraseña `password` (`apk.sh:367-368`, sobreescribibles por `APK_ALIAS/APK_PASS`); se genera automáticamente en la primera firma. Los APK resultantes comparten una firma de depuración débil conocida; no es un secreto de producción, pero no debe usarse para publicar.
- La contraseña viaja como argumento (`pass:$KEY_PASS`) visible en `ps` mientras firma.
- `android.jar` se descarga de un repo de terceros sin verificación de integridad (supply-chain) y se usa como classpath de compilación.
- El proyecto/APK se pasa a `bash -c` escapado con `shellQuote`.
- `instrument-*` modifica APK de terceros y agrega un logger que envía datos a `127.0.0.1:9999`: herramienta de ingeniería inversa que se ejecuta solo bajo acción explícita del usuario.

## 11. Notas y limitaciones conocidas

- **`hasSwitch: false` pero tiene proceso durante el build**: cada compilación es una ejecución puntual que termina sola; no hay `start.sh`/`stop.sh` como en otros módulos.
- **FileProvider dedicado, no el `.files` genérico**: cualquier cambio futuro a `TermuxOpenReceiver.ContentProvider` no debe asumirse compatible con compartir/instalar APKs generados por este módulo.
- `merge`/`info`/`decode` del wrapper también están disponibles a mano desde la terminal (`compil-apk-termux info/merge/decode ...`).
- `decode` no decompila smali/Java — solo deja el manifest legible y extrae los `.dex` crudos; la decompilación real requiere `apktool`.
- El wrapper embebido solo se reescribe con `--force`: actualizar `apk.sh` no actualiza `compil-apk-termux` si ya existe, así que correcciones del wrapper no llegan a dispositivos ya instalados sin reinstalación forzada.
- `zip … 2>/dev/null || true` (paso de empaquetado) oculta fallos al agregar `classes.dex`, `assets` o `lib`; luego `zipalign`/`apksigner` firman un APK sin código.
- `javac -source 11 -target 11` fijo; `android.jar` API 30 fijo (sin selección de plataforma). Fuera de alcance: React Native/NDK/CMake.
- El paso `d8` pasa `$(find … -name '*.class')` sin comillas (rompe con rutas con espacios).
- Inferencia: `openjdk-17` y `apktool` (que requiere `openjdk-21` según su propio mensaje) pueden chocar de versión en el mismo dispositivo.
- **Sincronización**: `modulos/apk.sh` ↔ `app/src/main/assets/scripts/apk.sh` deben mantenerse iguales.

## 12. Fecha de alta

`modulos/apk.sh`: **2026-08-13**.
