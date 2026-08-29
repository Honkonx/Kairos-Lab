#!/bin/sh
# kairos-llm — copia los .so dependientes de llama-server a los assets generados.
#
# Bug real (2026-08-06, ver docs/humano/humano84.md): un "bash -c '...'" con comillas
# anidadas directo en el COMMAND de add_custom_command (CMakeLists.txt) se corrompía al
# pasar por 2 capas de escapado de shell (CMake -> ninja -> sh) — confirmado en log real de
# CI: "cp: target '2': No such file or directory" / "/bin/sh: 1: true: not found", la
# pipeline de "cp ... 2>/dev/null || true" quedaba partida en tokens sueltos en vez de un
# único script. Un archivo .sh real, invocado con argumentos simples (sin comillas/redirects
# embebidos en el COMMAND de CMake), evita ese problema de raíz — solo hay UNA capa de shell
# real acá adentro.
#
# Bug real de peso (2026-08-27, ver docs/humano263.md): este "cp" copiaba los .so TAL CUAL
# salen de ninja — con toda la info de debug, "not stripped" (confirmado con `file`: mismo
# BuildID que la copia paralela que Android deja en lib/<abi>/, pero esa segunda copia SÍ
# queda stripped porque AGP la optimiza automáticamente al empaquetar el APK; ESTA copia
# (assets/scripts/, la que usa el subproceso llama-server vía ProcessBuilder) nunca pasaba por
# ningún strip). Ejemplo real: libllama-common.so pasó de 4.9MB (stripped, lib/) a 81MB (sin
# stripped, assets/) — mismo binario. Sumado a las ~10 .so que se copian acá, esto era el
# grueso real de por qué el APK sin rootfs casi se duplicó de tamaño. Fix: stripear con el
# mismo binario que usa el propio NDK (pasado como 3er argumento por CMakeLists.txt,
# $<TARGET_FILE:llvm-strip> o CMAKE_STRIP) antes de copiar — mismo contenido funcional, mismo
# BuildID, sin los símbolos de depuración que el subproceso en producción no necesita.
#
# Uso: copy_shared_libs.sh <directorio_origen> <directorio_destino> [ruta_a_strip]
SRC_DIR="$1"
DEST_DIR="$2"
STRIP_BIN="$3"
for f in "$SRC_DIR"/*.so; do
  [ -e "$f" ] || continue
  cp "$f" "$DEST_DIR/" 2>/dev/null
  if [ -n "$STRIP_BIN" ] && [ -x "$STRIP_BIN" ]; then
    "$STRIP_BIN" --strip-unneeded "$DEST_DIR/$(basename "$f")" 2>/dev/null
  fi
done
exit 0
