# docs/ia-local/ — Motor de IA local embebido de Kairos

Esta carpeta documenta el motor de inferencia de IA local embebido en Kairos (llama.cpp, con un
wrapper en proceso y un servidor HTTP compatible con la API de OpenAI), y las auditorías técnicas
del stack de IA local en su conjunto (llama.cpp, Ollama, e integración con CLIs de terceros).

- [LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md) — arquitectura del motor de inferencia local
  embebido, el servidor HTTP `llamaserver` (puerto 8085), y el proceso de build (Vulkan, NDK,
  CMake).
- [AUDITORIA_LLAMA_CPP_2026-08-26.md](AUDITORIA_LLAMA_CPP_2026-08-26.md) — auditoría de código
  real del módulo llama.cpp: build system, Vulkan, consistencia de la interfaz con el backend.
- [AUDITORIA_STACK_IA_LOCAL_2026-08-26.md](AUDITORIA_STACK_IA_LOCAL_2026-08-26.md) — auditoría de
  la integración de Ollama, el chat multi-motor, y los proveedores de IA local para CLIs de
  terceros.
