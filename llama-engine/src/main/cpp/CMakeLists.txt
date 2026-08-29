# kairos-llm — wrapper JNI de llama.cpp para Kairos.
#
# Estructura/flags adaptados de OfflineLLM (jegly/OfflineLLM, Apache-2.0,
# basado a su vez en SmolChat-Android) — ver docs/referencias/REFERENCIA_OFFLINELLM.md
# y docs/ia-local/LLAMA_CPP_EMBEBIDO.md para el detalle completo de por qué cada
# flag está donde está. No es código inventado desde cero: es una
# adaptación deliberada de una configuración real que ya compila con
# Vulkan en Android, con nombres/paquete propios de Kairos.

cmake_minimum_required(VERSION 3.22.1)
project("kairos_llm")

# llama.cpp se clona en tiempo de build (tarea Gradle downloadLlamaCpp,
# ver ../../../build.gradle) a llama-engine/llama.cpp — NO es un git
# submodule (Kairos no usa submodules, ver el resto del proyecto) para
# no complicar el flujo "solo GitHub Actions, sin JDK local".
#
# Con LLAMA_BUILD_COMMON/TOOLS/SERVER=ON (ver ../../../build.gradle, rama
# "llama-server-and-terminal-ux") este add_subdirectory ya arma también el target
# ejecutable "llama-server" (tools/server/CMakeLists.txt de llama.cpp) — no hace falta un
# add_subdirectory extra, se recorre solo como parte del árbol normal de llama.cpp.
#
# Bug real de build (2026-08-05, segundo intento): con LLAMA_BUILD_TOOLS=ON,
# tools/CMakeLists.txt de llama.cpp arma TAMBIÉN ~10 herramientas que Kairos no necesita
# (batched-bench, gguf-split, imatrix, llama-bench, completion, perplexity, quantize,
# tokenize, parser, tts) — una de ellas, "llama-debug-template-parser" (tools/parser/), falló
# el LINK en CI con "undefined symbol" para varios símbolos de llama-common (jinja::*,
# autoparser::*, common_log_*, common_peg_arena, llama_grammar_init_impl).
#
# Intento de fix #1 (NO funcionó, confirmado empíricamente — mismo error, mismos 735 pasos de
# build): "EXCLUDE_FROM_ALL" en este add_subdirectory. La teoría (que nada fuera de la
# dependencia real de un target en ALL se construiría) es CMake correcto en general, pero
# Android Gradle Plugin NO invoca ninja con su target por-defecto — enumera TODOS los targets
# vía la File API de CMake y se los pasa a ninja **por nombre, uno por uno** (confirmado
# leyendo el comando real de ninja en el log de CI: una lista explícita con los ~40 targets de
# llama.cpp, incluido "llama-debug-template-parser", sin importar EXCLUDE_FROM_ALL). AGP
# simplemente ignora esa propiedad para su propio descubrimiento de targets.
#
# Causa raíz real (tercer intento, confirmada leyendo tools/parser/CMakeLists.txt real): ese
# archivo SÍ linkea bien contra llama-common (`target_link_libraries(... PRIVATE llama-common
# llama ...)`) — el problema es que los símbolos de llama-common no quedan EXPORTADOS en
# libllama-common.so. Kairos aplica `-fvisibility=hidden` como PUBLIC al target "llama" (abajo)
# — y "llama-common" linkea PUBLIC contra "llama" (`target_link_libraries(llama-common PUBLIC
# llama Threads::Threads)`, al final de common/CMakeLists.txt de llama.cpp) — un compile option
# PUBLIC se propaga a cualquier target que linkee PUBLIC contra el que lo define, así que
# "-fvisibility=hidden" terminaba aplicado también a la compilación de llama-common. Las
# funciones públicas reales de llama.cpp
# (llama_model_load, etc., las que usa kairos_llm) siguen exportadas porque llama.h las marca
# con LLAMA_API (que fuerza visibility=default explícito) — pero los símbolos internos de
# llama-common (jinja/autoparser/common_log, nunca pensados para cruzar un límite de librería
# compartida en el uso normal estático de llama.cpp) no tienen ese marcador y quedaban ocultos.
# Fix real: PRIVATE en vez de PUBLIC en los 2 flags de visibilidad de "llama" — siguen
# aplicando a la compilación propia de "llama" (mismo beneficio original: superficie exportada
# más chica), pero ya no se propagan a lo que linkea contra él.
add_subdirectory(../../../llama.cpp llama.cpp)
set(LLAMA_DIR_RELATIVE "../../../llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)

set(GGML_DIR ${LLAMA_DIR}/ggml)

target_compile_options(
        llama
        PRIVATE
        -fvisibility=hidden -fvisibility-inlines-hidden
)
target_compile_options(
        llama
        PUBLIC
        -ffunction-sections -fdata-sections
)
target_link_options(
        llama
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)

# Wrapper JNI único. La optimización específica de CPU vive en las
# variantes del plugin ggml-cpu (GGML_CPU_ALL_VARIANTS), seleccionadas en
# runtime — no en flags de arquitectura acá, que nunca afectaron la
# velocidad de inferencia real.
add_library(
        kairos_llm
        SHARED
        LLMInference.cpp
        kairos_llm_jni.cpp
)
target_include_directories(
        kairos_llm
        PUBLIC
        ${GGML_DIR}/include
        ${GGML_DIR}/src
        ${LLAMA_DIR}/include
)
target_compile_options(
        kairos_llm
        PUBLIC
        -O3
        -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections
)
# Deliberadamente SIN linkear llama-common — trae el código HTTP/descarga
# de upstream, que no tiene lugar en una app sin permiso de red para esto.
# Los pocos helpers que hacían falta están reimplementados localmente
# sobre la API C de llama.h (ver LLMInference.cpp).
target_link_libraries(
        kairos_llm
        android log llama vulkan
)
# -z max-page-size=16384: requisito real de Google Play desde nov-2025 (dispositivos con
# páginas de memoria de 16KB, ver docs/referencias/REFERENCIA_PODROID.md, "Profundización" 2026-08-01).
# Explícito acá en vez de confiar en que el default del linker de este NDK ya lo aplique —
# llama-engine pinea un NDK distinto (más viejo) al resto del proyecto (ver comentario de
# ndkVersion arriba en build.gradle), así que no asumir el comportamiento por defecto de un
# NDK más nuevo.
target_link_options(
        kairos_llm
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
)

# Librería GGUFReader — lee metadata del .gguf (context_length, chat
# template) sin cargar el modelo completo.
set(TARGET_NAME_GGUF_READER kairos_ggufreader)
add_library(${TARGET_NAME_GGUF_READER} SHARED GGUFReader.cpp)
target_include_directories(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        ${GGML_DIR}/include
)
target_compile_options(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections
)
target_link_libraries(
        ${TARGET_NAME_GGUF_READER}
        ggml
)
target_link_options(
        ${TARGET_NAME_GGUF_READER}
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
)

# llama-server (binario ejecutable real de llama.cpp, servidor HTTP compatible OpenAI) —
# rama "llama-server-and-terminal-ux" (2026-08-05, ver docs/humano/humano71.md/humano72.md).
# Copiado a un directorio de assets generado (KAIROS_LLAMA_SERVER_OUT_DIR, pasado desde
# ../../../build.gradle) en vez de quedar solo en el directorio de build interno de CMake, para
# que AGP lo empaquete en la APK como assets/scripts/llama-server (extraído a Termux por
# KairosBootstrap.kt, igual que los demás scripts de módulo). El target "llama-server" solo
# existe si LLAMA_BUILD_SERVER=ON.
#
# Bug real de build (2026-08-05, primer intento): "add_custom_command(TARGET llama-server
# POST_BUILD ...)" falló en CI con "TARGET 'llama-server' was not created in this directory" —
# la forma TARGET de add_custom_command solo puede adjuntarse a un target definido en el MISMO
# CMakeLists.txt; "llama-server" se define adentro de llama.cpp/tools/server/CMakeLists.txt (un
# add_subdirectory distinto), no acá. Fix intento #2: la forma OUTPUT (DEPENDS en vez de TARGET)
# no tiene esa restricción, más un add_custom_target(... ALL) para engancharlo al build.
#
# Bug real de build (2026-08-06, ver docs/humano/humano81.md): "ALL" en add_custom_target NO
# alcanzaba — confirmado leyendo la lista EXPLÍCITA de targets que AGP le pasa a ninja en un log
# real de CI (mismo mecanismo ya documentado arriba: AGP enumera targets vía File API y se los
# pasa a ninja por nombre, uno por uno) — esa lista tenía "kairos_llm", "llama-server", "mtmd",
# etc., pero NO "kairos_copy_llama_server". Confirmado además con evidencia directa: la tarea
# Gradle verifyLlamaServerAsset (ver ../../build.gradle) falló diciendo que el binario nunca se
# generó, en un build donde buildCMakeRelease terminó OK (los 703 pasos de compilación real
# corrieron bien). Causa: AGP filtra esa lista por TIPO de target (ejecutables/bibliotecas que
# puede empaquetar) — un target UTILITY sin biblioteca/ejecutable asociado (lo que produce
# add_custom_target) queda afuera de esa lista sin importar "ALL", que es una propiedad de CMake
# que AGP simplemente no consulta al armar su propia lista.
#
# Fix real: en vez de depender de que AGP decida construir kairos_copy_llama_server por su
# cuenta, se usa add_dependencies() para que kairos_llm (que SÍ está confirmado en la lista
# explícita de arriba — es la biblioteca JNI que usa el tab Chat IA, se construye siempre) lo
# arrastre como prerequisito de su propio build. add_dependencies() no tiene la restricción de
# "mismo directorio" de add_custom_command(TARGET ...) — funciona con cualquier target visible
# en el árbol completo del proyecto.
# Bug real (2026-08-06, ver docs/humano/humano83.md): confirmado en dispositivo real que el
# binario SÍ se extrae ahora (fix de arriba funcionó) pero falla al ejecutar: "CANNOT LINK
# EXECUTABLE: library libllama-server-impl.so not found". BUILD_SHARED_LIBS=ON (ver
# ../../build.gradle) hace que llama-server sea un ejecutable DINÁMICAMENTE linkeado contra
# varias bibliotecas propias (libggml-base.so, libllama.so, libllama-common.so, libmtmd.so,
# libllama-server-impl.so — cada una su propio .so, confirmado en el log real de ninja:
# "Linking CXX shared library bin/libggml-base.so" etc.) — copiar solo el ejecutable no alcanza,
# hacen falta también sus .so. Se copian todos los .so del mismo directorio bin/ donde CMake ya
# deja a llama-server ($<TARGET_FILE_DIR:llama-server>) — NO se copia el directorio completo
# porque ahí también quedan varios ejecutables CLI de llama.cpp que Kairos no necesita
# (llama-cli, llama-mtmd-cli, etc., todos construidos igual por estar en el árbol de
# add_subdirectory) — filtrado a mano con un comando de shell en vez de glob de CMake, porque
# file(GLOB) corre en tiempo de CONFIGURE (cuando bin/ todavía no existe), y acá hace falta que
# corra en tiempo de BUILD, después de que ninja ya generó los .so reales.
# Bug real (2026-08-06, ver docs/humano/humano84.md): un "bash -c '...'" con comillas/redirects
# embebidos directo acá se corrompía al pasar por 2 capas de escapado de shell (CMake -> ninja ->
# sh) — confirmado en log real de CI: "cp: target '2': No such file or directory". Fix: un
# script .sh real (copy_shared_libs.sh, al lado de este CMakeLists.txt), invocado con
# argumentos simples — sin comillas ni redirects en la línea de COMMAND, toda la lógica de
# shell vive adentro del script, una sola capa real de shell.
if(DEFINED KAIROS_LLAMA_SERVER_OUT_DIR AND TARGET llama-server)
    set(KAIROS_LLAMA_SERVER_COPY "${KAIROS_LLAMA_SERVER_OUT_DIR}/llama-server")
    add_custom_command(
        OUTPUT "${KAIROS_LLAMA_SERVER_COPY}"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${KAIROS_LLAMA_SERVER_OUT_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:llama-server>" "${KAIROS_LLAMA_SERVER_COPY}"
        COMMAND sh "${CMAKE_CURRENT_SOURCE_DIR}/copy_shared_libs.sh" "$<TARGET_FILE_DIR:llama-server>" "${KAIROS_LLAMA_SERVER_OUT_DIR}" "${CMAKE_STRIP}"
        COMMAND "${CMAKE_STRIP}" --strip-unneeded "${KAIROS_LLAMA_SERVER_COPY}"
        DEPENDS llama-server "${CMAKE_CURRENT_SOURCE_DIR}/copy_shared_libs.sh"
        COMMENT "Kairos: copiando llama-server + .so dependientes a assets generados (${KAIROS_LLAMA_SERVER_OUT_DIR})"
    )
    add_custom_target(kairos_copy_llama_server DEPENDS "${KAIROS_LLAMA_SERVER_COPY}")
    add_dependencies(kairos_llm kairos_copy_llama_server)
endif()
