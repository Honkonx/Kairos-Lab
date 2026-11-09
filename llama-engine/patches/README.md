# Parches locales sobre llama.cpp (código vendor ignorado por git)

- `kairos-tools-cmake-b10194.patch`: parche sobre la versión anterior (b10194, `e1a1abb`, 2026-07-30).
- `kairos-tools-cmake-6184e92.patch`: el mismo cambio reaplicado sobre upstream `6184e92` (2026-10-09). Comenta `add_subdirectory` de las herramientas de bench/debug que Kairos no usa.

## Volver a la versión anterior
`cd llama-engine/llama.cpp && git checkout kairos-backup-b10194 && git apply ../patches/kairos-tools-cmake-b10194.patch`
y revertir en `llama-engine/src/main/cpp/LLMInference.cpp` las dos llamadas adaptadas (penalties sin `n_vocab`; `mtmd_helper_bitmap_init_from_buf` sin `opt`). Copia completa sin `.git`: `referencia/backups/llama.cpp-b10194-kairos_2026-10-09.tar.gz`.

## Al compilar en la PC jhosw
Poner `.build-tools/w64devkit/bin` al FINAL del PATH: sin eso `g++` del host (vulkan-shaders-gen) falla con "cannot execute 'as'" cuando ccache no tiene el objeto en caché.
