# Motor de inferencia local embebido (llama.cpp)

## Resumen

Kairos embebe [llama.cpp](https://github.com/ggml-org/llama.cpp) directamente en el APK,
compilado como código nativo (NDK/CMake) en vez de depender de un paquete de Termux. Hay dos
piezas distintas, que comparten el mismo módulo Gradle `llama-engine/`:

| Pieza | Qué es | Notas |
|---|---|---|
| `kairos_llm` (wrapper JNI) | Motor de inferencia en el mismo proceso de la app, usado por la pantalla de Chat — sin puerto de red | Estable, sin cambios de arquitectura desde su primera versión |
| `llama-server` (binario real de llama.cpp) | Servidor HTTP compatible con la API de OpenAI, para que otros módulos de Kairos (procesos Termux separados: agentes de IA en terminal, etc.) puedan usarlo como backend | Confirmado funcionando de punta a punta en dispositivo real, sirviendo en `127.0.0.1:8085` |

## Por qué llama.cpp directo, y por qué embebido en el APK

Se evaluaron varias alternativas (incluyendo empaquetar el servidor completo de Ollama,
compilado con Go+CGO) — se eligió llama.cpp directo por ser la opción con la base de código más
madura y el mejor soporte de aceleración GPU en Android disponible entre los proyectos evaluados.

Se eligió compilarlo como `.so` embebido en el APK, en vez de distribuirlo como paquete de
Termux, por un requisito explícito de diseño: poder elegir en tiempo de ejecución entre usar
aceleración Vulkan o forzar CPU — algo que un paquete de Termux instalado con una variante GPU
fija no permite, porque la elección queda congelada al momento de instalar.

La implementación se basó en un proyecto de referencia externo (con licencia Apache-2.0) que ya
tenía Vulkan compilando de verdad en Android — no solo declarado en el build script, sino
efectivamente linkeando. La clave real para lograr Vulkan en Android no es tanto la versión del
NDK, sino agregar explícitamente los headers de Khronos (`Vulkan-Headers`, `SPIRV-Headers`) que
el NDK no trae completos, más un toolchain de host para el generador de shaders
(`vulkan-shaders-gen`, que debe compilar y correr en la máquina de build, no en el dispositivo
Android). Por eso el módulo `llama-engine` pinea su propia versión de NDK (27.2.12479018),
distinta de la que usa el resto del proyecto para otros componentes nativos.

## Estructura del módulo

| Archivo | Rol |
|---|---|
| `llama-engine/build.gradle` | Configuración de CMake/NDK/Vulkan |
| `llama-engine/src/main/cpp/CMakeLists.txt` | Define los targets nativos, clona llama.cpp como subdirectorio |
| `llama-engine/src/main/cpp/LLMInference.h/.cpp` | Motor de inferencia en C++ (sampling, chat template, caché KV incremental) |
| `llama-engine/src/main/cpp/kairos_llm_jni.cpp` | Puente JNI |
| `llama-engine/src/main/cpp/GGUFReader.cpp` | Lee metadata de archivos `.gguf` (tamaño de contexto, plantilla de chat) sin cargar el modelo completo |
| `llama-engine/src/main/java/com/termux/llm/LlamaEngine.kt` | Wrapper Kotlin (llamadas bloqueantes + callbacks, sin corrutinas) |
| `llama-engine/src/main/java/com/termux/llm/GpuBackend.kt` | Selección de backend: CPU forzado, o Vulkan si está disponible |

El wrapper JNI expone funciones para inicializar backends, detectar el nombre del dispositivo
GPU, cargar un modelo, gestionar el turno de chat, medir velocidad de generación y uso de
contexto, e iniciar/detener una generación en streaming.

## Build: clonado del código fuente de llama.cpp

El módulo clona el repositorio real de llama.cpp (`ggml-org/llama.cpp`) en tiempo de build,
fijado a un tag específico (build de integración continua de upstream — llama.cpp no publica
versiones "estables" tradicionales). Después del clonado, se aplica un parche de texto
idempotente sobre el `CMakeLists.txt` de herramientas de llama.cpp, comentando la construcción de
una decena de utilidades de benchmarking/debug que Kairos no necesita — necesario para evitar
problemas de build descritos más abajo.

`arm64-v8a` es la única arquitectura objetivo, consistente con el resto del proyecto (Android
Bionic ARM64). Para compensar la falta de builds multi-arquitectura, se activan las opciones que
compilan varias variantes de kernels ARM (dotprod/fp16/i8mm/SVE) como plugins seleccionados en
tiempo de ejecución según la CPU real del dispositivo.

## El servidor HTTP (`llama-server`)

Compilar el binario real `llama-server` (no solo el wrapper JNI) requiere activar el subárbol de
"herramientas" de llama.cpp (`LLAMA_BUILD_TOOLS`), que a su vez arrastra la construcción de la
web UI embebida de llama.cpp (deshabilitada explícitamente, `LLAMA_BUILD_UI=OFF` — sin fallar el
build, solo genera código vacío) y del subsistema multimodal `mtmd` (dependencia dura del
servidor, no se puede evitar sin parchear el propio llama.cpp).

### Lecciones de build reales, con causa raíz confirmada

Llevar `llama-server` de "compila" a "corre en el dispositivo" tomó varias iteraciones, cada una
con una causa raíz distinta confirmada leyendo el código fuente real de CMake/llama.cpp — vale la
pena documentarlas porque son problemas genéricos de integración CMake + Android Gradle Plugin
(AGP), no específicos de este proyecto:

1. **AGP filtra targets `UTILITY` de CMake, sin importar la propiedad `ALL`.** Un target de
   utilidad definido con `add_custom_target(... ALL DEPENDS ...)` (usado para copiar el binario
   compilado a la carpeta de assets) nunca se ejecutaba, pese a estar marcado `ALL` — AGP no
   invoca `ninja` con su target por defecto, sino que enumera explícitamente los targets del
   proyecto vía la File API de CMake y solo pasa a `ninja` los que identifica como bibliotecas o
   ejecutables empaquetables, ignorando los targets `UTILITY`. **Solución real**: declarar el
   target de copia como dependencia explícita (`add_dependencies`) de un target que AGP sí
   construye siempre (la biblioteca JNI principal) — así se arrastra como prerequisito de un
   build que ya ocurre, en vez de depender de que AGP lo descubra por sí mismo.

2. **`EXCLUDE_FROM_ALL` no basta para evitar que AGP intente construir un target no deseado.**
   Al intentar excluir herramientas de debug innecesarias del build, se probó marcar el
   subdirectorio entero con `EXCLUDE_FROM_ALL` — sin efecto real, porque AGP enumera los targets
   directamente vía la File API de CMake, sin consultar esa propiedad. La solución real que sí
   funcionó fue un parche de texto sobre el `CMakeLists.txt` de llama.cpp que comenta
   directamente las líneas `add_subdirectory()` de las herramientas no deseadas — si CMake nunca
   define el target, no hay forma de que AGP intente construirlo.

3. **Visibilidad de símbolos `PUBLIC` propagada accidentalmente entre bibliotecas.** Kairos
   aplica banderas de visibilidad oculta (`-fvisibility=hidden`) a la biblioteca principal de
   llama.cpp, para minimizar la superficie exportada del `.so`. Al declararlas como `PUBLIC` en
   CMake, esa configuración se propagaba a cualquier biblioteca que enlazara `PUBLIC` contra
   ella — ocultando accidentalmente símbolos internos de una biblioteca auxiliar que el
   componente de servidor sí necesitaba en tiempo de enlazado. **Fix**: cambiar esas banderas a
   `PRIVATE`, para que sigan aplicando a la compilación propia sin propagarse hacia lo que
   enlaza contra ella. Lección general: en CMake, una opción de compilador declarada `PUBLIC` se
   hereda transitivamente por cualquier target que enlace `PUBLIC` contra el que la declara — hay
   que ser explícito sobre qué realmente necesita propagarse.

4. **Copiar solo el ejecutable no basta cuando se compila con bibliotecas compartidas.** Con
   `BUILD_SHARED_LIBS=ON` (necesario para el sistema de plugins de backends de llama.cpp:
   CPU/Vulkan seleccionables en runtime), cada componente de llama.cpp se compila como su propia
   biblioteca dinámica — el ejecutable `llama-server` depende de varias `.so` auxiliares en
   tiempo de ejecución. El primer intento de empaquetado solo copiaba el binario ejecutable, y el
   dispositivo fallaba con un error de enlazador dinámico por biblioteca faltante. **Fix**: el
   mismo paso de copia también copia las bibliotecas compartidas generadas junto al ejecutable.

5. **Comandos de shell embebidos directamente en una línea `COMMAND` de CMake se corrompen al
   pasar por dos capas de re-serialización** (CMake genera el comando para el archivo de build de
   ninja, y ninja lo vuelve a interpretar vía su propia shell) — comillas y redirecciones
   embebidas no sobreviven ambas capas de escapado. **Fix**: mover toda la lógica de shell a un
   script `.sh` independiente, invocado con argumentos simples sin comillas ni redirecciones en
   la línea de comando de CMake.

6. **Requisito de Google Play para páginas de memoria de 16KB.** Se agregó la bandera de enlazado
   correspondiente (`max-page-size=16384`) a los targets nativos del módulo, como medida
   preventiva alineada con el requisito vigente de Google Play para dispositivos con tamaño de
   página de memoria de 16KB — el módulo pinea su propio NDK, distinto del resto del proyecto, así
   que no correspondía asumir que el flag ya venía aplicado por default.

### Dependencia externa de headers de Vulkan

El enlazado del backend Vulkan depende de dos checkouts externos, clonados como directorios
hermanos del proyecto por el pipeline de integración continua antes de invocar Gradle
(`Vulkan-Headers`, `SPIRV-Headers` de Khronos) — si no están presentes, el build de Vulkan falla
en la etapa de configuración con solo una advertencia (no un error duro), lo que puede dejar el
build cayendo silenciosamente a compilar sin esos includes. Es el punto más fragil del pipeline:
un cambio futuro que reordene o elimine esos pasos del flujo de integración continua rompería
Vulkan de forma silenciosa.

## Cadena completa: del build al módulo de Termux

1. CMake compila `llama-server` y sus bibliotecas dependientes → se copian a la carpeta de assets
   del APK.
2. El instalador de bootstrap de Kairos extrae esos archivos al directorio de scripts del entorno
   Termux embebido en el primer arranque, marcándolos ejecutables.
3. Un módulo dedicado (`llamaserver`) copia el binario a la ruta estándar de binarios de Termux,
   genera scripts de arranque/parada (usando `tmux`, puerto 8085) y un archivo de configuración
   de usuario (modelo elegido, puerto) — mismo patrón que otros módulos de Kairos basados en
   procesos de Termux.
4. El módulo reutiliza los modelos `.gguf` que ya gestiona la pantalla de Chat de IA (mismo
   directorio de almacenamiento de la app, accesible por Termux gracias al `sharedUserId`
   compartido) — no descarga modelos por su cuenta.
5. El módulo se integra en el sistema estándar de módulos de Kairos (instalar/iniciar/detener/
   reinstalar/desinstalar, catálogo, interruptor) sin código especial.
6. Otros módulos de Kairos (agentes de IA en terminal) pueden configurarse para usar este servidor
   como backend, apuntando a la misma URL base compatible con la API de OpenAI que ya usan para
   apuntar a Ollama — confirma que esos módulos nunca estuvieron atados específicamente a Ollama.

## Interfaz de usuario

La pantalla "IA Local" del menú de la app permite: elegir el backend de GPU preferido (CPU
forzado o Vulkan si está disponible), ajustar temperatura y tamaño de contexto con un máximo
dinámico calculado según la RAM real del dispositivo, y gestionar los modelos `.gguf`
descargados. Un catálogo curado de modelos (con tamaños verificados contra el repositorio real de
cada modelo en Hugging Face) permite descargar directamente, además de la opción de pegar una URL
manual.

La descarga de modelos incluye validación multi-capa: verificación de los "magic bytes" del
formato GGUF antes de aceptar el archivo, comparación del tamaño descargado contra el
`Content-Length` declarado por el servidor (con un umbral de tolerancia), limpieza automática de
descargas parciales huérfanas, y un chequeo de espacio libre en disco antes de iniciar la
descarga (con un margen de seguridad del 15% sobre el tamaño estimado que falta descargar).

En la pantalla de Chat, el selector de modelo incluye tanto los modelos locales `.gguf` como los
modelos de Ollama en una sola lista — el motor que responde se decide según qué modelo se elige,
nunca con un interruptor separado. El chat también reenvía el historial de conversación reciente
en cada mensaje (una limitación real que existía antes y se corrigió), separa visualmente la
cadena de razonamiento de modelos que la emiten (formato `<think>...</think>`, común en modelos
tipo DeepSeek-R1/QwQ) del resto de la respuesta, y traduce errores nativos de bajo nivel a
mensajes legibles para el usuario, manteniendo el detalle técnico disponible detrás de un botón
"Ver detalles".

## Limitaciones conocidas, documentadas explícitamente

- Sin búsqueda en internet ni llamada a herramientas (tool-calling) desde el motor local.
- Sin persistencia de historial de conversación entre sesiones de la app (solo dentro de una
  sesión activa).
- Sin soporte de imágenes/visión en el chat (requeriría un proyector de visión adicional, no
  gestionado hoy por el flujo de descarga de modelos).
- Un solo backend de cómputo GPU además de CPU (Vulkan) — OpenCL no está compilado, aunque el
  mecanismo de backends dinámicos de llama.cpp lo permitiría agregar después sin cambios
  arquitectónicos.
- El servidor HTTP solo sirve un modelo por proceso — cambiar de modelo requiere reiniciar el
  servicio, sin mecanismo de intercambio en caliente.
- El puerto del servidor solo escucha en loopback (`127.0.0.1`) por defecto, sin exposición a la
  red local.
