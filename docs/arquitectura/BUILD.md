# BUILD.md — Compilación

La compilación se hace **vía CI** (recomendado, reproducible) o **local** (ver abajo, requiere
Android Studio + SDK/NDK instalados). GitHub Actions es la vía principal de CI; GitLab CI
(`.gitlab-ci.yml`, raíz del repo) es una alternativa equivalente cuando hace falta.

## Build local (Windows, `tools/build-local.ps1`)

Replica los pasos de `build-app.yml`/`.gitlab-ci.yml` (mismo comando Gradle, mismos
prerequisitos de `llama-engine`) sin depender de WSL — todos los pasos son Gradle/CMake/git
normales.

```powershell
.\tools\build-local.ps1              # build liviano completo (clona Vulkan/SPIRV-Headers si faltan)
.\tools\build-local.ps1 -SkipVulkan  # si Vulkan-Headers/SPIRV-Headers ya están clonados/instalados
```

Qué hace el script:
1. Configura `JAVA_HOME` apuntando a un JDK 17 (o al JBR embebido de Android Studio si no hay
   uno mejor seteado). CI usa JDK 17 "temurin" — si el build local falla por versión de Java,
   instalar un JDK 17 aparte y setear `JAVA_HOME` a mano antes de correr el script.
2. Autodetecta `ANDROID_HOME`/`ANDROID_SDK_ROOT` (`%LOCALAPPDATA%\Android\Sdk` si no está
   seteado).
3. Reescribe `local.properties` (`sdk.dir`) para que apunte al SDK real de la máquina actual —
   evita builds rotos por una ruta heredada de otra máquina/usuario.
4. Verifica el NDK `27.2.12479018` (el que usa `llama-engine`/Vulkan, distinto del NDK `29.x`
   del resto del proyecto); lo instala vía `sdkmanager` si falta.
5. Clona `Vulkan-Headers`/`SPIRV-Headers` como hermanos del checkout (`../Vulkan-Headers`,
   `../SPIRV-Headers` — exactamente lo que `llama-engine/build.gradle` espera vía
   `rootProject.file("../...")`) y compila/instala `SPIRV-Headers` con CMake, si no existen ya.
6. `./gradlew downloadBootstraps assembleDebug --no-daemon` con
   `TERMUX_PACKAGE_VARIANT=apt-android-7`/`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.

APK resultante en `app\build\outputs\apk\debug\*.apk`, igual que el artifact de CI.

### Compilar `llama-engine` en Windows

Confirmado funcionando end-to-end (build completo, incluyendo `llama-engine` con Vulkan, APK
universal). Compilar `llama.cpp`/Vulkan en Windows tiene varios detalles no obvios frente a un
build en Linux (que es lo que corre en CI):

- **Compilador de host real necesario**: la herramienta `llama-ui-embed` de llama.cpp busca un
  compilador de host (`g++`/`clang++`) con `find_program`, y en Windows puede encontrar por
  error el `clang++.exe` del NDK de Android (que solo compila para Android, nunca para
  Windows) si no hay un compilador de host real en el PATH. CMake además cachea el compilador
  detectado en `CMakeCache.txt`, así que una corrida repetida sobre el mismo directorio de
  build sigue usando el valor viejo aunque se instale un compilador real después. La solución
  del script es pasar el compilador de host explícito como argumento de CMake en vez de
  confiar en la autodetección.
- **Toolchain de Vulkan generado con rutas de Linux hardcodeadas**: la generación del
  toolchain de host para `vulkan-shaders-gen` puede asumir rutas de compilador estilo
  `/usr/bin/gcc` — inválidas en Windows. Hay que sustituirlas por el compilador MinGW/w64devkit
  real cuando el host es Windows.
- **Herramienta `glslc` específica de plataforma**: el NDK trae binarios de `glslc` por host
  (`shader-tools/<host>/`) — hay que elegir el subdirectorio/binario correcto según el sistema
  operativo, en vez de asumir siempre la variante Linux.

**Requisito**: `w64devkit` (MinGW g++ portable, sin instalador —
github.com/skeeto/w64devkit) extraído en `.build-tools/w64devkit/` (raíz del repo,
gitignoreado). Necesario únicamente para compilar `llama-engine` en Windows — sin él,
`build-local.ps1` avisa con instrucciones y el resto del proyecto (`app`,
`terminal-emulator`, `terminal-view`, `x11-server`, `termux-shared`) compila igual.

## Los workflows de CI (`.github/workflows/`)

| Workflow | Trigger | Qué hace |
|---|---|---|
| `build-app.yml` ("Build Kairos APK") | manual (`workflow_dispatch`) | Build liviano — sin rootfs embebido, el wizard lo descarga en runtime |
| `build-app-rootfs.yml` ("Build Kairos APK (con rootfs embebido)") | manual, requiere el tag de una Release de rootfs previa | Build con el rootfs ya empaquetado dentro del APK |
| `build-app-rootfsv1.yml` ("Build Kairos APK (con rootfs embebido) v1") | manual, tag opcional | Variante mejorada: auto-detecta la release más reciente con prefijo `rootfs-` si no se pasa el tag a mano |
| `build-rootfs.yml` ("Build Kairos rootfs") | manual | Arma el rootfs (`tools/rootfs/build_rootfs.py`) y lo publica como GitHub Release |

## GitLab CI (`.gitlab-ci.yml`, raíz del repo)

Un solo job (`build-apk`), equivalente al `build-app.yml` liviano de arriba — mismo
`downloadBootstraps assembleDebug`, mismos prerequisitos de `llama-engine` (NDK
27.2.12479018 + clone/install de `Vulkan-Headers`/`SPIRV-Headers` como hermanos del
checkout). `when: manual` — hay que dispararlo a mano desde CI/CD → Pipelines. Sin
restricción de rama. El APK queda como artifact del job, con retención de 30 días (igual que
en GitHub).

### Diferencias reales GitLab CI vs GitHub Actions

No son "el mismo Ubuntu con otro nombre" — cada diferencia real cuesta un run fallido hasta
encontrarla:

| Diferencia | GitHub Actions (`ubuntu-latest`) | GitLab CI (`eclipse-temurin:17-jdk` + runner compartido) |
|---|---|---|
| Toolchain C/C++ | `build-essential` (gcc+g++) preinstalado | Imagen solo-JDK, sin ningún compilador — `g++` hay que pedirlo a mano |
| SDK Android preinstalado | Sí — NDKs y versiones de `cmake` comunes (incluida 3.22.1) ya en disco de fábrica | No — imagen genérica, absolutamente nada de Android preinstalado, se arma todo vía `sdkmanager` en el propio job |
| Orden de instalación del componente SDK `cmake;3.22.1` | Irrelevante — ya estaba en disco antes de que corriera cualquier tarea de Gradle | AGP lo instala **lazy, recién dentro del build** (la primera vez que un `configureCMake*` lo necesita) — si algún código propio lo necesita ANTES de eso, en un SDK armado desde cero no está, hay que pre-instalarlo a mano |
| Tamaño del runner | 4 vCPU / 16GB RAM (hosted estándar) | `saas-linux-small-amd64` — bastante más chico |

## Cómo lanzar el build liviano (el más común)

1. GitHub → Actions → "Build Kairos APK"
2. "Run workflow" (rama principal)
3. Comando real que corre: `./gradlew downloadBootstraps assembleDebug --no-daemon`
4. Descargar el APK del artifact al terminar

## Variables de entorno reales

- `TERMUX_PACKAGE_VARIANT=apt-android-7`
- `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`
- `KAIROS_EMBED_ROOTFS` — solo en `build-app-rootfs.yml`, gatea la tarea `downloadRootfsAsset` de `app/build.gradle`
- `GITHUB_TOKEN` — necesario en `build-app-rootfs.yml` para descargar el asset del rootfs desde una Release (ver `docs/bootstrap/ROOTFS_EMBEBIDO.md`)

## Versiones fijas reales (`gradle.properties`/`app/build.gradle`)

- NDK `29.0.14206865` (`ndkVersion`, override posible vía `JITPACK_NDK_VERSION`)
- compileSdk/targetSdk/minSdk — ver `app/build.gradle` directamente
- Kotlin, AGP, Gradle — ver `build.gradle` (raíz) y `gradle/wrapper/gradle-wrapper.properties`

## Estructura del Gradle real

```
kairos/                        ← raíz del repo (el fork de termux-app vive acá directo)
├── build.gradle                ← build script root
├── settings.gradle             ← incluye módulos: app, terminal-emulator, terminal-view, termux-shared, llama-engine
├── gradle.properties           ← SDK/NDK versions
├── app/build.gradle             ← módulo app — UI nativa
├── terminal-emulator/build.gradle
├── terminal-view/build.gradle
├── termux-shared/build.gradle
└── llama-engine/build.gradle    ← módulo llama.cpp NDK (ver LLAMA_CPP_EMBEBIDO.md)
```

## Troubleshooting

### NDK error
El NDK principal (r29.0.14206865) lo instala el propio workflow de CI; `llama-engine` además
necesita el NDK 27.2.12479018 aparte (ver "Build local" arriba, que sí lo instala solo vía
`sdkmanager`). Si un build local falla por NDK, confirmar cuál de los dos hace falta según qué
módulo está compilando.

### Rootfs 404 en build liviano
Si el rootfs se aloja como asset de una Release privada o restringida, la build liviana
(`build-app.yml`) puede recibir 404 al intentar descargarlo en runtime sin credenciales. Ver
`docs/bootstrap/ROOTFS_EMBEBIDO.md` — el wizard cae automáticamente a la instalación clásica
paquete por paquete si esto pasa.

### llama-engine no compila / build más pesado
`llama-engine` compila llama.cpp+Vulkan desde cero en cada build (incluido `build-app.yml`),
así que es normal que el build sea más pesado que un build sin motor de inferencia embebido.
Ver `docs/ia-local/LLAMA_CPP_EMBEBIDO.md`.
