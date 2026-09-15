# Compilador de APK (`apk`)

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos / Tienda). Instalación manejada por la app vía `ProcessBuilder` → `modulos/apk.sh`; la pantalla dedicada (`ApkFragment.kt`) expone el pipeline de compilación real (aapt2 → javac + kotlinc → d8 → zipalign → apksigner) con progreso paso a paso, en vez de una caja de texto de terminal cruda.

**Alcance real:** compila **Java + Kotlin** (JVM puro, sin Gradle). NO compila React Native/JS (necesita Gradle real con autolinking) ni C/C++ nativo vía CMake/NDK (el NDK real de Android, no el toolchain nativo de Termux, es un proyecto aparte).

---

**Script:** `modulos/apk.sh` — espejo en `app/src/main/assets/scripts/apk.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ApkFragment.kt`
**`id` en `modules.json`:** `apk` — `hasSwitch: false` (no es un servidor con proceso persistente; es una herramienta de línea de comandos que se invoca por build)

---

## 1. Descripción General

Módulo que instala un compilador de APKs completo **dentro del dispositivo**, sin depender de Android Studio ni de una PC — pensado para compilar proyectos Android simples (manifest + `.java`/`.kt` + recursos opcionales) directo desde Termux/Kairos. Puerta de entrada para el usuario: el comando `compil-apk-termux` (o el botón "🔨 Compilar APK" de `ApkFragment`).

El script no es un servidor: instala herramientas de build (aapt2, JDK, d8, zipalign, apksigner) y deja un wrapper (`compil-apk-termux`) en `$PREFIX/bin`. Cada compilación es una invocación puntual del wrapper, no un proceso de fondo — por eso `hasSwitch: false` en `modules.json`.

El keystore: Kairos genera el suyo localmente con `keytool` en el primer build (password por defecto `"password"`, generado en el propio dispositivo, no distribuido como archivo público).

## 2. Permisos

- **Android**: ninguno específico para instalar/compilar. Para **compartir/instalar** el APK resultante usa un `FileProvider` dedicado (`${TERMUX_PACKAGE_NAME}.apkbuilder`, declarado en `AndroidManifest.xml` con `android:grantUriPermissions="true"` y `res/xml/apk_builder_paths.xml` como alcance) — no reutiliza el provider `.files` existente, porque ese exige la policy `allow-external-apps` pensada para plugins de terceros que llaman a la API de Termux, no para un artefacto que la propia app genera y quiere entregarle al instalador de paquetes del sistema.
- **Termux interno**: `aapt2`, `openjdk-17`, `d8`, `zipalign`, `apksigner` se instalan vía `pkg` (paquetes nativos aarch64 de Termux) — sin proot, sin red además de la descarga inicial de paquetes y de `android.jar`.

## 3. Lógica de instalación (`modulos/apk.sh`)

Script con el contrato estándar (SILENT/CHECKPOINT/REGISTRY) y los flags estándar:

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts, mismo output `[OK]`/`[ERROR]` |
| `--force` | Reinstala aunque ya esté (borra el checkpoint) |
| `--describe` | Manifiesto JSON: `{"id":"apk","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |

**PASO 1 — Herramientas de compilación**: instala `aapt2 openjdk-17 d8 zipalign apksigner unzip zip wget` vía `pkg install`. Verifica cada binario con `command -v` antes de marcar el checkpoint.

**PASO 2 — `android.jar`**: descarga `android-30/android.jar` desde el mirror `Sable/android-platforms` en GitHub a `$PREFIX/share/android-platform/android.jar` — se guarda una sola vez, reusado por todos los proyectos futuros. Verifica que el archivo descargado no esté vacío.

**PASO 3 — Wrapper `compil-apk-termux` + keystore**: escribe el script wrapper completo (ver §4) en `$PREFIX/bin/compil-apk-termux` y prepara la carpeta del keystore (`$HOME/.local/share/kairos-apk/`) — el keystore en sí (`key.keystore`, password `"password"`) se genera recién en el primer build real (`keytool -genkey`), no en la instalación.

**FINALIZANDO**: escribe en el registry (`apk.installed=true`, `apk.command=compil-apk-termux`, `apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner`, `apk.android_jar=API30`, `apk.install_date`).

## 4. El wrapper `compil-apk-termux` (subcomandos)

`apk.sh` no compila nada por sí mismo — genera un wrapper standalone de ~250 líneas que sí lo hace. Subcomandos:

| Subcomando | Uso | Qué hace |
|---|---|---|
| `build <proyecto>` | `compil-apk-termux build ~/proyectos/mi_app` | Pipeline completo de 7 pasos (8 si el proyecto tiene Kotlin, ver §5). Es el **default** si se invoca sin subcomando reconocido (`compil-apk-termux <proyecto>` == `build <proyecto>`) |
| `info <apk>` | `compil-apk-termux info final.apk` | `aapt2 dump badging` (package/label/sdkVersion/permisos) + `apksigner verify --print-certs` + resumen de contenido (`unzip -l`) |
| `merge <base.apk> <partes...>` | `compil-apk-termux merge base.apk config.arm64-v8a.apk` | Combina un APK base con splits, sin Java/Gradle: desempaqueta base + splits en un dir temporal, re-empaqueta, `zipalign`, re-firma con el keystore local. Los splits traen `AndroidManifest.xml` duplicado — se conserva el de la base, no hay des-dupe semántico |
| `decode <apk> <dir>` | `compil-apk-termux decode app.apk salida/` | Extrae el APK + deja el `AndroidManifest.xml` legible vía `aapt2 dump xmltree` y lista los `.dex`. No hace smali/decompile — para eso hace falta `pkg install apktool` aparte (no lo instala `apk.sh`) |

Variables de entorno opcionales que sobrescriben los defaults del wrapper: `ANDROID_JAR`, `APK_ALIAS` (default `kairos`), `APK_PASS` (default `password`), `APK_KEYSTORE`.

## 5. Pipeline de `build` (7 pasos, 8 con Kotlin)

Todo-o-nada — el wrapper corre con `set -e`, corta en el primer error de cualquiera de los pasos, no hay forma de "solo compilar sin firmar" como paso intermedio invocable por separado. El paso `kotlinc` es **condicional**: solo corre si `find "$PROJECT/src" -name '*.kt'` encuentra algo — un proyecto Java puro sigue viendo exactamente 7 pasos, sin overhead:

| Paso (Java puro / con Kotlin) | Herramienta | Qué produce |
|---|---|---|
| 1/7 · 1/8 | `aapt2 compile --dir res/` | `resources.zip` (solo si el proyecto tiene carpeta `res/`) |
| 2/7 · 2/8 | `aapt2 link -I android.jar --manifest ...` | `base.apk` (recursos compilados) + `R.java` generado en `gen/` |
| — · 3/8 (solo si hay `.kt`) | `kotlinc -classpath android.jar` | `.class` de `src/*.kt` → `build/kotlin-classes/` (directorio propio, separado de `build/classes/`) |
| 3/7 · 4/8 | `javac -source 11 -target 11 -classpath android.jar[:kotlin-classes]` | `.class` de `src/*.java` + del `R.java` generado — el classpath incluye `kotlin-classes/` cuando hubo paso Kotlin |
| 4/7 · 5/8 | `d8 --release --lib android.jar` | `classes.dex` — recoge `.class` de `build/classes/` y `build/kotlin-classes/` |
| 5/7 · 6/8 | `zip` | Empaqueta `classes.dex` + `assets/` (opcional) + `lib/` (opcional, `.so` nativas) dentro de `base.apk` |
| 6/7 · 7/8 | `zipalign -p 4` | `aligned.apk` (alineación requerida por ART en Android 11+) |
| 7/7 · 8/8 | `apksigner sign --v1/v2/v3-signing-enabled true` | `final.apk` — genera el keystore con `keytool` si todavía no existe |

**Kotlin es dependencia blanda, no dura**: si el proyecto tiene `.kt` pero el módulo Kotlin no instaló `kotlinc` (no está en el PATH), `cmd_build()` corta con `[ERROR] Proyecto tiene archivos .kt pero kotlinc no está instalado — instalá el módulo Kotlin primero` antes de tocar cualquier archivo — `apk.sh` no fuerza la instalación de Kotlin como dependencia dura, solo detecta y avisa en tiempo de build.

**Estructura mínima de proyecto**:
```
proyecto/
├── AndroidManifest.xml   (obligatorio)
├── src/                  (.java y/o .kt, puede tener paquetes anidados; opcional si el proyecto es solo recursos)
├── res/                  (opcional)
├── assets/               (opcional)
└── lib/                  (opcional — .so nativas)
```

**Salida real**: `<proyecto>/build/apk/final.apk` (`BUILD_DIR="$PROJECT/build"`); `ApkFragment.scanGeneratedApks()` escanea esa ruta.

**Parser de salida del compilador**: `_parse_compiler_output()` reconoce el formato GNU estándar de `javac`/`kotlinc`/`aapt2` (`archivo:línea[:col]: error|warning: mensaje`) y muestra `[ERROR]`/`[WARN]` con conteo final en vez de un dump crudo. `ApkFragment` traduce las líneas `=== [n/N] ... ===` que emite el wrapper en actualizaciones de `ProgressDialogController` paso a paso (`N` es 7 u 8 según haya Kotlin).

## 6. Detección de estado

- **Instalación**: registry (`apk.installed=true`) — la instalación se considera hecha si además existen `compil-apk-termux` y `aapt2` en el PATH (`command -v`, sin `--force`).
- **`hasSwitch: false`**: no hay concepto de "corriendo"/"detenido" — el módulo no aparece con toggle en la Tienda, solo con botón de instalar/abrir.
- **`ApkFragment.isModuleInstalled()`**: si no está instalado, muestra la pantalla estándar "no instalado" con botón que llama a `installModuleInBackground`.

## 7. Pantalla real de la app (`ApkFragment.kt`)

`getModuleId()` → `"apk"`, `getModuleName()` → `"Compilador de APK"`.

**PROYECTO A COMPILAR** — fila con el proyecto seleccionado (`— ninguno —` por default). Botón "📂 Elegir proyecto a compilar" lista `~/proyectos` vía `ProjectsManager.projectsList()` en un diálogo. Botón "🗂 Gestionar proyectos (symlink / copiar)" abre el menú compartido de gestión de proyectos.

**COMPILAR** — muestra el pipeline (`aapt2 → javac + kotlinc → d8 → zipalign → apksigner`) y una fila "Lenguajes" (Java y Kotlin — NO React Native/JS ni C/C++ nativo), y el botón "🔨 Compilar APK", que:
1. Valida que haya un proyecto seleccionado y que su carpeta siga existiendo.
2. Abre `ProgressDialogController` ("Compilando APK").
3. Corre `compil-apk-termux build <ruta>` en un `Thread` vía `ProcessBuilder`, leyendo línea a línea con `redirectErrorStream(true)`.
4. Traduce cada línea `=== ... ===` (paso nuevo) o `  [ERROR]` a una actualización de progreso en vivo.
5. Al terminar: `progress.success(...)` o `progress.failure(...)` con las últimas 4000 chars del log completo, y refresca la lista de APKs generados.

**APKS GENERADOS** — `scanGeneratedApks()` recorre `~/proyectos/*/build/apk/final.apk`, ordenado por fecha de modificación descendente, mostrando nombre de proyecto + tamaño humano. Cada fila es clickeable → `shareOrInstallApk()`:
- **📤 Compartir**: `Intent.ACTION_SEND` con el `content://` del FileProvider `apkbuilder` (mime `application/vnd.android.package-archive`).
- **📲 Instalar**: `Intent.ACTION_VIEW` con el mismo URI + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`, delega en el instalador de paquetes real de Android.

**MANTENIMIENTO** — muestra el script fuente (`modulos/apk.sh`), botón "🔄 Actualizar herramientas" y "🗑 Desinstalar" (confirmación previa, deja explícito que no borra `~/proyectos` ni los APKs ya compilados, solo las herramientas de build y el wrapper).

## 8. Registry

```
apk.installed=true
apk.command=compil-apk-termux
apk.chain=aapt2,kotlinc,javac,d8,zipalign,apksigner
apk.android_jar=API30
apk.install_date=<YYYY-MM-DD>
```

## 9. Notas técnicas

- **`hasSwitch: false` pero tiene proceso durante el build**: a diferencia de los módulos servidor, `apk` no tiene start/stop — cada compilación es una ejecución puntual que termina sola.
- **FileProvider dedicado**: cualquier cambio futuro al provider `.files` genérico no debería asumirse compatible con compartir/instalar APKs generados por este módulo.
- **`merge`/`info`/`decode` no tienen UI dedicada**: solo `build` está expuesto en `ApkFragment` — los otros 3 subcomandos del wrapper siguen disponibles a mano desde la terminal.
- **`decode` no decompila smali/Java** — solo deja el manifest legible y extrae los `.dex` crudos; decompilación real requiere `apktool` instalado aparte.
- **Elección de herramientas**: `d8` es la herramienta de DEX disponible como paquete de Termux (R8, el sucesor con shrinking/obfuscation de Android Gradle Plugin moderno, no está empaquetado ahí) — es la mejor opción real disponible sin compilar herramientas propias. `API30` como target es razonable para el caso de uso (compilar APKs simples desde el dispositivo, no un reemplazo de Android Studio).
