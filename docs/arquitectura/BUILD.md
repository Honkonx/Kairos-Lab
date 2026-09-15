# BUILD.md — Compilación

La compilación se hace **vía CI** (recomendado, reproducible) o **local en Windows** (ver abajo,
requiere Android Studio + SDK/NDK instalados) — GitHub Actions es la vía principal de CI, GitLab
CI (`.gitlab-ci.yml`, raíz del repo) es la alternativa. `origin` puede configurarse para empujar
a ambos remotos con un solo `git push`, así ambos pipelines quedan disponibles con el mismo
código.

## Build local (Windows, `tools/build-local.ps1`)

Replica exactamente los pasos de `build-app.yml`/`.gitlab-ci.yml` (mismo comando Gradle, mismos
prerequisitos de `llama-engine`), sin WSL — todos los pasos son Gradle/CMake/git normales, nada
exclusivo de Linux.

```powershell
.\tools\build-local.ps1              # build liviano completo (clona Vulkan/SPIRV-Headers si faltan)
.\tools\build-local.ps1 -SkipVulkan  # si Vulkan-Headers/SPIRV-Headers ya están clonados/instalados
```

Qué hace el script:
1. `JAVA_HOME` → usa el JBR embebido de Android Studio si no hay uno mejor seteado (CI usa JDK
   17 "temurin"; si el build falla por versión de Java, instalar un JDK 17 aparte y setear
   `JAVA_HOME` a mano antes de correr el script).
2. `ANDROID_HOME`/`ANDROID_SDK_ROOT` → autodetecta `%LOCALAPPDATA%\Android\Sdk` si no está seteado.
3. Reescribe `local.properties` (`sdk.dir`) para que apunte al SDK real de la máquina.
4. Verifica NDK `27.2.12479018` (el que usa `llama-engine`/Vulkan, distinto del NDK `29.x` del
   resto); lo instala vía `sdkmanager` si falta.
5. Clona `Vulkan-Headers`/`SPIRV-Headers` como hermanos del checkout (`../Vulkan-Headers`,
   `../SPIRV-Headers` — exactamente lo que `llama-engine/build.gradle` espera vía
   `rootProject.file("../...")`) y compila/instala `SPIRV-Headers` con CMake, si no existen ya.
6. `./gradlew downloadBootstraps assembleDebug --no-daemon` con
   `TERMUX_PACKAGE_VARIANT=apt-android-7`/`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.

APK resultante en `app\build\outputs\apk\debug\*.apk`, igual que el artifact de CI.

### Compilación de `llama-engine` en Windows

`llama-engine` compila llama.cpp (con soporte Vulkan) desde cero, lo que requiere un toolchain
de host además del NDK de Android. Puntos a tener en cuenta al compilar en Windows:

- La herramienta de host `llama-ui-embed` (parte de `tools/ui/CMakeLists.txt` de llama.cpp)
  necesita un compilador C++ real de HOST — no el del NDK de Android (que solo compila para
  Android). Si no hay un compilador de host en el PATH, CMake puede terminar usando por error el
  `clang++.exe` del NDK y fallar con errores tipo `'inttypes.h' file not found`.
- La generación del toolchain de host para el compilador de shaders Vulkan necesita rutas de
  compilador válidas en Windows (`gcc.exe`/`g++.exe`), no rutas de Linux hardcodeadas.
- El compilador de shaders `glslc` viene provisto por el NDK en distintos subdirectorios por
  plataforma de host (`windows-x86_64/`, `linux-x86_64/`, etc.) — hay que apuntar al binario
  correcto para el host actual.

**Requisito:** `w64devkit` (MinGW g++ portable, sin instalador — github.com/skeeto/w64devkit)
extraído en `.build-tools/w64devkit/` (raíz del repo, gitignoreado). Necesario únicamente para
compilar `llama-engine` en Windows — sin él, `build-local.ps1` avisa con instrucciones y el resto
del proyecto (`app`, `terminal-emulator`, `terminal-view`, `x11-server`, `termux-shared`) compila
igual.

## Los 4 workflows reales (`.github/workflows/`)

| Workflow | Nombre real | Trigger | Qué hace |
|---|---|---|---|
| `build-app.yml` | "Build Kairos APK" | `workflow_dispatch` (manual, no automático en push/PR) | Build liviano — sin rootfs embebido, el wizard lo descarga en runtime |
| `build-app-rootfs.yml` | "Build Kairos APK (con rootfs embebido)" | `workflow_dispatch`, requiere `KAIROS_ROOTFS_RELEASE_TAG` | Build con el rootfs ya empaquetado dentro del APK |
| `build-app-rootfsv1.yml` | "Build Kairos APK (con rootfs embebido) v1" | `workflow_dispatch`, `rootfs_release_tag` opcional | Variante mejorada de `build-app-rootfs.yml`: auto-detecta la release más reciente con prefijo `rootfs-` (pre-release o release) si no se pasa el tag a mano; funciona con repos privados vía `GITHUB_TOKEN` automático de Actions |
| `build-rootfs.yml` | "Build Kairos rootfs" | `workflow_dispatch` | Arma el rootfs (`tools/rootfs/build_rootfs.py`) y lo publica como GitHub Release — ver `docs/bootstrap/rootfs-embebido.md` |

## Workflows adicionales: wheels Python prebuilt para módulos específicos

Además de los 4 workflows de arriba (que construyen el APK/rootfs), el repo tiene workflows de
disparo manual que cross-compilan extensiones Python con componentes nativos en Rust
(`aarch64-linux-android`, vía `maturin`) para paquetes que PyPI no publica en formato compatible
con Android/Bionic — solo `manylinux`/`macOS`/Windows. El wheel resultante se publica como asset
de una Release, y el script del módulo correspondiente lo busca ahí antes de intentar compilar
Rust en el propio dispositivo (mucho más lento y con más puntos de falla que en CI). Este
mecanismo está verificado funcionando de punta a punta para `hf_xet` (dependencia de Hugging
Face Hub); el workflow equivalente para `firecrawl-anydoc` (dependencia de Hermes) usa el mismo
mecanismo pero todavía no se confirmó con una corrida real.

## GitLab CI (`.gitlab-ci.yml`, raíz del repo) — alternativa a GitHub Actions

Un solo job (`build-apk`), equivalente al `build-app.yml` liviano de arriba — mismo
`downloadBootstraps assembleDebug`, mismos prerequisitos de `llama-engine` (NDK 27.2.12479018 +
clone/install de `Vulkan-Headers`/`SPIRV-Headers` como hermanos del checkout). `when: manual`
(no corre solo en cada push, hay que dispararlo a mano desde gitlab.com → CI/CD → Pipelines).
Sin restricción de rama. El APK queda como artifact del job, 30 días de retención (igual que en
GitHub).

### Diferencias reales entre GitLab CI y GitHub Actions

No son "el mismo Ubuntu con otro nombre":

| Diferencia | GitHub Actions (`ubuntu-latest`) | GitLab CI (`eclipse-temurin:17-jdk` + runner compartido) |
|---|---|---|
| Toolchain C/C++ | `build-essential` (gcc+g++) preinstalado | Imagen solo-JDK, sin ningún compilador — `g++` hay que pedirlo a mano |
| SDK Android preinstalado | Sí — NDKs y versiones de `cmake` comunes (incluida 3.22.1) ya en disco de fábrica | No — imagen genérica, absolutamente nada de Android preinstalado, se arma todo vía `sdkmanager` en el propio job |
| Orden de instalación del componente SDK `cmake;3.22.1` | Irrelevante — ya estaba en disco antes de que corriera cualquier tarea de Gradle | AGP lo instala lazy, recién dentro del build — si algún código propio lo necesita ANTES de eso, en un SDK armado desde cero no está, hay que pre-instalarlo a mano |
| Tamaño del runner | 4 vCPU / 16GB RAM (hosted estándar) | `saas-linux-small-amd64` — bastante más chico |

## Cómo lanzar el build liviano (el más común)

1. GitHub → Actions → "Build Kairos APK"
2. "Run workflow" (branch `main`)
3. Comando real que corre: `./gradlew downloadBootstraps assembleDebug --no-daemon`
4. Descargar el APK del artifact al terminar

## Variables de entorno reales

- `TERMUX_PACKAGE_VARIANT=apt-android-7`
- `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`
- `KAIROS_EMBED_ROOTFS` — solo en `build-app-rootfs.yml`, gatea el `task downloadRootfsAsset` de `app/build.gradle`
- `GITHUB_TOKEN` — necesario en `build-app-rootfs.yml` para descargar el asset del rootfs desde el repo privado (ver `docs/bootstrap/rootfs-embebido.md`, sección "Repo privado y GITHUB_TOKEN")

## Versiones fijas reales (`gradle.properties`/`app/build.gradle`)

- NDK `29.0.14206865` (`ndkVersion`, override posible vía `JITPACK_NDK_VERSION`)
- compileSdk/targetSdk/minSdk — ver `app/build.gradle` directamente, cambian con más frecuencia que este doc
- Kotlin, AGP, Gradle — ver `build.gradle` (raíz) y `gradle/wrapper/gradle-wrapper.properties`

## Estructura del Gradle real

```
kairos/                        ← raíz del repo (NO "app/", el fork de termux-app vive acá directo)
├── build.gradle                ← build script root
├── settings.gradle             ← incluye módulos: app, terminal-emulator, terminal-view, termux-shared, llama-engine
├── gradle.properties           ← SDK/NDK versions
├── app/build.gradle             ← módulo app — UI nativa, sin React Native
├── terminal-emulator/build.gradle
├── terminal-view/build.gradle
├── termux-shared/build.gradle
└── llama-engine/build.gradle    ← módulo llama.cpp NDK (ver llama-cpp-local-engine.md)
```

## Troubleshooting

### NDK error
El NDK principal (r29.0.14206865) lo instala el propio workflow de CI; `llama-engine` además
necesita el NDK 27.2.12479018 aparte (ver "Build local" arriba, que sí lo instala solo vía
`sdkmanager`). Si un build local falla por NDK, confirmar cuál de los dos hace falta según qué
módulo está compilando.

### Rootfs 404 en build liviano
Si el repo de origen del rootfs es privado, la build liviana (`build-app.yml`) va a recibir 404
al intentar descargar el rootfs en runtime salvo que el usuario final tenga acceso/token. Ver
`docs/bootstrap/rootfs-embebido.md`.

### llama-engine no compila / build más pesado
`llama-engine` compila llama.cpp+Vulkan desde cero en CADA build (incluido `build-app.yml`, el
"normal") — es un build considerablemente más pesado que sin este módulo. Ver
`docs/ia-local/llama-cpp-local-engine.md`.
